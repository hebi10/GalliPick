"""Prepare an unsigned AAB and pinned tools for separate signing; never publish."""
import argparse
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

from signing_bundle import ReleaseError
from verify_aab import command_output, parse_manifest, sha256, validate_metadata, verify_archive


BUNDLETOOL_VERSION = "1.18.3"
BUNDLETOOL_SHA256 = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"


def require_unsigned(bundle):
    with zipfile.ZipFile(bundle) as archive:
        for entry in archive.infolist():
            name = entry.filename.upper()
            if name.startswith("META-INF/"):
                leaf = name.rsplit("/", 1)[-1]
                if leaf.endswith((".SF", ".RSA", ".DSA", ".EC")) or leaf.startswith("SIG-"):
                    raise ReleaseError("Unsigned preparation refuses existing AAB signature material.")


def prepare_bundle(bundle, bundletool, tag, destination, source, source_commit):
    if destination.exists() or destination.is_symlink():
        raise ReleaseError("Unsigned preparation requires a new output directory.")
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise ReleaseError("The unsigned preparation source identity is invalid.")
    # Both executables and bundle bytes are staged from the exact snapshots checked here.
    with tempfile.TemporaryDirectory(prefix="gallipick-unsigned-") as temporary:
        snapshot = Path(temporary) / "bundle.aab"
        tool = Path(temporary) / "bundletool.jar"
        shutil.copyfile(bundle, snapshot)
        shutil.copyfile(bundletool, tool)
        tool_digest = sha256(tool)
        if tool_digest != BUNDLETOOL_SHA256:
            raise ReleaseError("The public bundletool JAR does not match the pinned release hash.")
        verify_archive(snapshot)
        require_unsigned(snapshot)
        command = ["java", "-jar", str(tool)]
        command_output(command + ["validate", "--bundle=" + str(snapshot)], "Unsigned bundle validation")
        xml = command_output(command + ["dump", "manifest", "--bundle=" + str(snapshot), "--module=base"],
                             "Unsigned bundle manifest inspection")
        metadata = parse_manifest(xml)
        validate_metadata(tag, source, metadata)
        digest = sha256(snapshot)
        filename = f"GalliPick-{tag}-unsigned.aab"
        provenance = {
            "status": "unsigned_preparation", "signed": False,
            "source_commit": source_commit, "tag": tag,
            "bundle_file": filename, "bundle_sha256": digest, "bundle_bytes": snapshot.stat().st_size,
            "bundletool_file": "bundletool.jar", "bundletool_version": BUNDLETOOL_VERSION,
            "bundletool_sha256": tool_digest,
            "validation": ["bundletool validate", "base manifest matches source",
                           "signature metadata absent", "private signing material absent"],
        }
        destination.mkdir(parents=True)
        shutil.copyfile(snapshot, destination / filename)
        shutil.copyfile(tool, destination / "bundletool.jar")
        (destination / "SHA256SUMS").write_text(
            f"{digest}  {filename}\n{tool_digest}  bundletool.jar\n", encoding="ascii")
        (destination / "manifest.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        (destination / "provenance.json").write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return filename


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--bundletool", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.bundle.is_file() or not args.bundletool.is_file():
        raise ReleaseError("An unsigned AAB and the pinned public bundletool JAR are required.")
    source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    commit = command_output(["git", "rev-parse", "HEAD"], "Preparation source verification").strip()
    filename = prepare_bundle(args.bundle, args.bundletool, args.tag, args.output, source, commit)
    print(f"Unsigned preparation staged: {filename}. Signing and full signature verification remain required.")


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        raise SystemExit(str(error))
    except (OSError, UnicodeError, zipfile.BadZipFile):
        raise SystemExit("Unsigned preparation files could not be read or staged.") from None
