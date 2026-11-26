"""Measure AAB DEX size and compiler-provided R8 scores, without uploading to Play."""

import argparse
from collections import Counter
from decimal import Decimal
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile


DEX_NAME = r"classes(?:[2-9]|[1-9][0-9]+)?\.dex"
MODULE_DEX = re.compile(rf"[^/]+/dex/{DEX_NAME}")
R8_METADATA = "BUNDLE-METADATA/com.android.tools/r8.json"
MAPPING = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
APP_DEX_THRESHOLD_BYTES = 10_000_000
MIN_COVERAGE_PERCENT = Decimal("25")
FIELDS = {
    "obfuscation": ("noObfuscationPercentage", "isObfuscationEnabled"),
    "optimization": ("noOptimizationPercentage", "isOptimizationsEnabled"),
    "shrinking": ("noShrinkingPercentage", "isShrinkingEnabled"),
}


def stream_sha256(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(block)
    return digest.hexdigest()


def file_sha256(path):
    with Path(path).open("rb") as stream:
        return stream_sha256(stream)


def unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate R8 metadata field: " + key)
        result[key] = value
    return result


def reject_json_constant(value):
    raise ValueError("Non-finite JSON number: " + value)


def metadata_error(status, reason, version=None):
    return {"status": status, "reason": reason, "r8_version": version,
            "dex_checksums_verified": False, "scores_percent": None,
            "options_enabled": None}


def reliable_stats_version(version):
    # R8's metadata reader discards stats from versions with a known overflow bug.
    # See R8BuildMetadataImpl.canHaveOverflowInStatsMetadata.
    if not isinstance(version, str):
        return False
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
    if not match:
        return False
    major, minor, patch = map(int, match.groups())
    if major == 8:
        fixed_patches = {10: 33, 11: 23, 12: 19, 13: 4}
        return minor >= 10 and patch >= fixed_patches.get(minor, 0)
    return major >= 9 and (major, minor, patch) != (9, 0, 0)


def read_r8_metadata(archive, dex_entries):
    if R8_METADATA not in archive.namelist():
        return metadata_error("missing", "No embedded r8.json; Play percentages cannot be inferred.")
    info = archive.getinfo(R8_METADATA)
    if info.file_size > 4 * 1024 * 1024:
        return metadata_error("invalid", "R8 metadata exceeds the diagnostic reader limit.")
    try:
        data = json.loads(archive.read(info).decode("utf-8"), parse_float=Decimal,
                          object_pairs_hook=unique_json_object, parse_constant=reject_json_constant)
    except (UnicodeError, ValueError) as error:
        return metadata_error("invalid", f"Cannot parse R8 metadata: {error}")
    if not isinstance(data, dict):
        return metadata_error("invalid", "R8 metadata root is not an object.")
    version = data.get("version")
    if not isinstance(version, str) or not version:
        return metadata_error("invalid", "Missing R8 compiler version.")
    if not reliable_stats_version(version):
        return metadata_error("unverified_version", "Compiler version is unsupported or has unreliable statistics.", version)

    records = data.get("dexFiles")
    if not isinstance(records, list) or not records:
        return metadata_error("incomplete", "R8 DEX checksum list is unavailable.", version)
    checksums = []
    for record in records:
        checksum = record.get("checksum") if isinstance(record, dict) else None
        if not isinstance(checksum, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", checksum):
            return metadata_error("invalid", "Invalid SHA-256 in R8 DEX metadata.", version)
        checksum = checksum.lower()
        checksums.append(checksum)
        # Newer R8 versions also report individual DEX sizes.
        if "sizeInBytes" in record:
            size = record["sizeInBytes"]
            if type(size) is not int or not any(
                entry["sha256"] == checksum and entry["uncompressed_bytes"] == size for entry in dex_entries
            ):
                return metadata_error("invalid", "R8 DEX size does not match the AAB.", version)
    if Counter(checksums) != Counter(entry["sha256"] for entry in dex_entries):
        return metadata_error("invalid", "R8 metadata does not describe exactly the packaged module DEX files.", version)

    stats, options = data.get("stats"), data.get("options")
    if not isinstance(stats, dict) or not isinstance(options, dict):
        result = metadata_error("incomplete", "R8 statistics or compiler options are unavailable.", version)
        result["dex_checksums_verified"] = True
        return result
    percentages, enabled, raw = {}, {}, {}
    for name, (stat_key, option_key) in FIELDS.items():
        value, flag = stats.get(stat_key), options.get(option_key)
        if value is None or flag is None:
            result = metadata_error("incomplete", f"Missing R8 statistic or option: {name}.", version)
            result["dex_checksums_verified"] = True
            return result
        if type(value) not in (int, Decimal) or type(flag) is not bool:
            return metadata_error("invalid", f"Unexpected type for R8 statistic or option: {name}.", version)
        number = Decimal(value)
        if not number.is_finite() or not 0 <= number <= 100:
            return metadata_error("invalid", f"R8 percentage outside 0..100: {name}.", version)
        raw[name] = float(number)
        percentages[name] = float(Decimal(100) - number)
        enabled[name] = flag

    return {"status": "verified", "reason": None, "r8_version": version,
            "dex_checksums_verified": True, "scores_percent": percentages,
            "disabled_percent": raw, "options_enabled": enabled}


def read_mapping_summary(archive):
    if MAPPING not in archive.namelist():
        return {"status": "missing", "mapped_class_rows": None, "renamed_class_rows": None,
                "renamed_class_rows_percent": None, "sha256": None}
    mapped = renamed = 0
    with archive.open(MAPPING) as stream:
        checksum = stream_sha256(stream)
    try:
        with archive.open(MAPPING) as stream:
            with io.TextIOWrapper(stream, encoding="utf-8") as lines:
                for line in lines:
                    match = re.fullmatch(r"(\S+) -> (\S+):", line.rstrip("\r\n"))
                    if match:
                        mapped += 1
                        renamed += match[1] != match[2]
    except UnicodeError:
        return {"status": "invalid", "mapped_class_rows": None, "renamed_class_rows": None,
                "renamed_class_rows_percent": None, "sha256": checksum}
    return {"status": "available" if mapped else "empty", "mapped_class_rows": mapped,
            "renamed_class_rows": renamed,
            "renamed_class_rows_percent": round(renamed * 100 / mapped, 4) if mapped else None,
            "sha256": checksum}


def assess_threshold(total_bytes, other_dex_bytes, metadata):
    if total_bytes < APP_DEX_THRESHOLD_BYTES:
        return "below_local_size_threshold"
    if other_dex_bytes:
        return "unverified_non_module_dex"
    if metadata["status"] != "verified":
        return "unverified_metadata"
    if all(metadata["options_enabled"][key] and
           Decimal(str(metadata["scores_percent"][key])) >= MIN_COVERAGE_PERCENT for key in FIELDS):
        return "meets_local_metadata_threshold"
    return "below_local_metadata_threshold"


def inspect_bundle(bundle):
    bundle = Path(bundle)
    if bundle.suffix.lower() != ".aab":
        raise ValueError("Provide an Android App Bundle (.aab).")
    # Open once so the digest and ZIP inspection use the same file descriptor.
    with bundle.open("rb") as stream:
        checksum = stream_sha256(stream)
        stream.seek(0)
        return inspect_zip(stream, checksum)


def inspect_bundle_bytes(payload):
    # The uploader has already read the exact immutable bytes it will transmit.
    return inspect_zip(io.BytesIO(payload), hashlib.sha256(payload).hexdigest())


def inspect_zip(source, bundle_checksum):
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate entries in AAB.")
        module_entries = []
        other_bytes = 0
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.endswith(".dex"):
                continue
            if not MODULE_DEX.fullmatch(entry.filename):
                other_bytes += entry.file_size
                continue
            with archive.open(entry) as stream:
                magic = stream.read(8)
            if len(magic) != 8 or not re.fullmatch(rb"dex\n\d{3}\x00", magic) or entry.file_size < 0x70:
                raise ValueError("Invalid packaged DEX: " + entry.filename)
            with archive.open(entry) as stream:
                checksum = stream_sha256(stream)
            module_entries.append({"entry": entry.filename, "uncompressed_bytes": entry.file_size,
                                   "sha256": checksum})
        if not module_entries:
            raise ValueError("No packaged module DEX in AAB.")
        module_entries.sort(key=lambda item: item["entry"])
        module_bytes = sum(entry["uncompressed_bytes"] for entry in module_entries)
        total_bytes = module_bytes + other_bytes
        metadata = read_r8_metadata(archive, module_entries)
        mapping = read_mapping_summary(archive)
    return {
        "schema": "gp-dex-metrics-v1",
        "bundle_sha256": bundle_checksum,
        "dex_uncompressed_bytes": total_bytes,
        "module_dex_uncompressed_bytes": module_bytes,
        "other_dex_uncompressed_bytes": other_bytes,
        "module_dex_entries": module_entries,
        "r8_metadata": metadata,
        "mapping_class_proxy": mapping,
        "policy": {"app_dex_threshold_bytes_conservative": APP_DEX_THRESHOLD_BYTES,
                   "minimum_percent_each": int(MIN_COVERAGE_PERCENT),
                   "effective_from": "2027-02"},
        "local_threshold_assessment": assess_threshold(total_bytes, other_bytes, metadata),
        "play_console_status": "unverified",
    }


def reduction_percent(before, after):
    return round((before - after) * 100 / before, 4) if before else None


def compare_builds(measurements):
    profiles = {}
    for measurement in measurements:
        profile = measurement["profile"]
        mode = measurement["r8"]
        modes = profiles.setdefault(profile, {})
        if mode in modes:
            raise ValueError("Duplicate profile/mode in build measurements.")
        modes[mode] = measurement
    result = []
    for profile, modes in profiles.items():
        if set(modes) != {False, True}:
            continue
        before, after = modes[False], modes[True]
        comparison = {"profile": profile}
        for kind in ("apk", "aab"):
            for field in ("bytes", "dex_uncompressed_bytes", "dex_method_ids"):
                comparison[f"{kind}_{field}_reduction_percent"] = reduction_percent(
                    before[kind][field], after[kind][field])
        result.append(comparison)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = inspect_bundle(args.bundle)
        rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.output:
            if args.output.resolve() == args.bundle.resolve():
                raise ValueError("Report path must not overwrite the AAB.")
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        else:
            print(rendered, end="")
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit(f"DEX inspection failed: {error}") from None


if __name__ == "__main__":
    main()
