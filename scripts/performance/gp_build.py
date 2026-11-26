"""Measure APK/AAB archives and Gradle runtime dependencies without a release build."""

import argparse
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

from gp_r8_compat import verify_optimized_apk


ROOT = Path(__file__).resolve().parents[2]
PROFILES = ("localOnly", "devCloud", "playCloud")
DEX_NAME = r"classes(?:[2-9]|[1-9][0-9]+)?\.dex"
DEX_HEADER_BYTES = 0x70


def dex_method_ids(header, entry_bytes):
    if len(header) != DEX_HEADER_BYTES or not re.fullmatch(rb"dex\n0(?:35|37|38|39|40)\x00", header[:8]):
        raise ValueError("Unsupported DEX header.")
    file_size, header_size, endian_tag = struct.unpack_from("<III", header, 0x20)
    count, offset = struct.unpack_from("<II", header, 0x58)
    if file_size != entry_bytes or header_size != DEX_HEADER_BYTES or endian_tag != 0x12345678:
        raise ValueError("Invalid DEX dimensions or byte order.")
    if count and (offset < header_size or offset % 4 or offset + 8 * count > file_size):
        raise ValueError("Invalid DEX method identifier table.")
    if not count and offset:
        raise ValueError("Invalid empty DEX method identifier table.")
    return count


def archive_metrics(path):
    pattern = DEX_NAME if path.suffix == ".apk" else rf"[^/]+/dex/{DEX_NAME}"
    dex = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if re.fullmatch(pattern, entry.filename):
                if entry.filename in dex:
                    raise ValueError(f"Duplicate DEX entry in {path.name}.")
                with archive.open(entry) as content:
                    dex[entry.filename] = dex_method_ids(content.read(DEX_HEADER_BYTES), entry.file_size)
    if not dex:
        raise ValueError(f"No packaged DEX entries in {path.name}.")
    return {
        "file": path.name,
        "bytes": path.stat().st_size,
        "dex_files": len(dex),
        "dex_method_ids": sum(dex.values()),
        "dex_method_ids_by_file": dex,
    }


def measure(profile, minified, output):
    debug = profile == "devCloud"
    variant = "debug" if debug else "releaseBenchmark"
    task_suffix = variant[0].upper() + variant[1:]
    wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
    gradle = [
        str(ROOT / wrapper), "--no-configuration-cache", "--console=plain",
        "-PgallipickTestSigning=true", "-PgallipickAdsTest=false", "-PgallipickAdsPreview=false",
        "-PgallipickAdsEnabled=false", "-PgallipickPlayCloudPilot=false",
        "-PgallipickCloudEnvironment=development",
        f"-PgallipickMinifyBenchmark={str(minified).lower()}",
    ]
    if not debug:
        gradle.append(f"-PgallipickBuildBenchmark={profile}")
    print(f"Measuring {profile} ({'R8' if minified else 'baseline'})", flush=True)
    subprocess.run(gradle + [":app:verifyGalliPickR8Configuration", ":app:clean"], cwd=ROOT, check=True)
    subprocess.run(gradle + [f":app:assemble{task_suffix}", f":app:bundle{task_suffix}"], cwd=ROOT, check=True)

    configuration = f"{variant}RuntimeClasspath"
    dependencies = subprocess.check_output(
        gradle + [":app:dependencies", "--configuration", configuration], cwd=ROOT,
        encoding="utf-8",
    )
    if not re.search(rf"(?m)^{configuration} - ", dependencies) or re.search(r"\bFAILED\b|\(n\)", dependencies):
        raise ValueError(f"Runtime dependencies could not be resolved for {profile}.")

    metrics = {}
    for kind, directory in (("apk", "apk"), ("aab", "bundle")):
        artifacts = list((ROOT / "app" / "build" / "outputs" / directory / variant).glob(f"*.{kind}"))
        if len(artifacts) != 1:
            raise ValueError(f"Expected exactly one {kind.upper()} for {variant}.")
        metrics[kind] = archive_metrics(artifacts[0])
        if minified and kind == "apk":
            metrics["r8_compatibility"] = verify_optimized_apk(
                artifacts[0], ROOT / "app" / "build" / "outputs" / "mapping" / variant,
            )

    dependency_file = f"{profile}-{'r8' if minified else 'baseline'}-runtime.txt"
    (output / dependency_file).write_text(dependencies, encoding="utf-8")
    return {
        "profile": profile,
        "gradle_variant": variant,
        "r8": minified,
        "runtime_dependencies": dependency_file,
        **metrics,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles", choices=PROFILES, nargs="+", default=PROFILES)
    parser.add_argument("--r8", action="store_true", help="also measure R8 for the release-like profiles")
    parser.add_argument("--output", type=Path, default=ROOT / "build" / "gp-build")
    args = parser.parse_args()
    if "devCloud" in args.profiles and (ROOT / "app/src/debug/google-services.json").exists():
        parser.error("Run the devCloud measurement in a worktree without debug Firebase credentials.")
    output = args.output.resolve()
    if output.is_relative_to(ROOT / "app" / "build"):
        parser.error("Use an output directory outside the app build folder.")
    try:
        output.mkdir(parents=True, exist_ok=True)
        if any(output.iterdir()):
            parser.error("Use an empty output directory for each measurement.")
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
        report = {"source_commit": commit, "source_dirty": dirty, "measurements": []}
        for profile in dict.fromkeys(args.profiles):
            report["measurements"].append(measure(profile, False, output))
            if args.r8 and profile != "devCloud":
                report["measurements"].append(measure(profile, True, output))
        (output / "metrics.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        raise SystemExit(f"Build measurement stopped before a complete report was written. {error}") from None


if __name__ == "__main__":
    main()
