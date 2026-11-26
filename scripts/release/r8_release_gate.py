"""Fail-closed R8 release policy for signed AAB staging and the exact upload payload."""
import json
from pathlib import Path
import sys
import zipfile

from signing_bundle import ReleaseError

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "performance"))
from gp_dex_metrics import (  # noqa: E402
    APP_DEX_THRESHOLD_BYTES, FIELDS, MIN_COVERAGE_PERCENT,
    inspect_bundle, inspect_bundle_bytes,
)


def require_optimized_bundle(bundle):
    """Return evidence from the actual bundle; do not trust a previously claimed score."""
    try:
        report = inspect_bundle_bytes(bundle) if isinstance(bundle, bytes) else inspect_bundle(bundle)
    except (OSError, ValueError, TypeError, RuntimeError, zipfile.BadZipFile, NotImplementedError):
        raise ReleaseError("R8 release gate: could not inspect the actual AAB.") from None

    metadata = report["r8_metadata"]
    if metadata["status"] != "verified" or metadata["dex_checksums_verified"] is not True:
        raise ReleaseError("R8 release gate: missing, invalid or unverified compiler metadata.")
    if report["other_dex_uncompressed_bytes"]:
        raise ReleaseError("R8 release gate: non-module DEX requires independent verification.")
    if any(metadata["options_enabled"].get(key) is not True for key in FIELDS):
        raise ReleaseError("R8 release gate: all three compiler optimization options must be enabled.")
    mapping = report["mapping_class_proxy"]
    if mapping["status"] != "available" or not mapping["sha256"]:
        raise ReleaseError("R8 release gate: the embedded mapping is missing or invalid.")
    if (report["dex_uncompressed_bytes"] >= APP_DEX_THRESHOLD_BYTES
            and report["local_threshold_assessment"] != "meets_local_metadata_threshold"):
        raise ReleaseError(
            f"R8 release gate: DEX optimization, shrinking and obfuscation must each meet "
            f"{MIN_COVERAGE_PERCENT}% for an applicable bundle."
        )
    return report


def verify_optimization_evidence(bundle, provenance):
    """Recompute from the very bytes being verified; exact build evidence must match."""
    report = require_optimized_bundle(bundle)
    expected = provenance.get("r8_optimization")
    try:
        same = (
            isinstance(expected, dict)
            and json.dumps(expected, sort_keys=True, separators=(",", ":"), allow_nan=False)
            == json.dumps(report, sort_keys=True, separators=(",", ":"), allow_nan=False)
            and provenance.get("bundle_sha256") == report["bundle_sha256"]
        )
    except (TypeError, ValueError):
        same = False
    if not same:
        raise ReleaseError("R8 release gate: the actual AAB and original optimization evidence do not match.")
    return report
