#!/usr/bin/env python3
"""Stage 9-G screenshot identity and viewport audit; visual fidelity requires human review."""
import argparse
import hashlib
import json
import os
from pathlib import Path

from verify_design_captures import image_info
from verify_stage8_captures import VIEWPORTS

REAL_NEW = {
    "cleanup-history-empty-navigation": "01-cleanup-history-empty.png",
    "photo-analysis-idle-navigation": "02-photo-analysis-idle.png",
}
FEATURES = (
    "01-analysis-complete",
    "02-analysis-scrolled",
    "03-analysis-running",
    "04-similar-results",
    "05-similar-compare",
    "06-burst-results",
    "07-burst-compare",
    "08-soft-focus-results",
    "09-history-records",
    "10-history-confirm",
    "11-history-empty",
    "12-history-dark",
)


def audit(stage8: Path, native: Path, feature: Path, profile: str) -> dict:
    if profile not in VIEWPORTS:
        raise ValueError("Unrecognized Android profile: " + profile)
    prior = json.loads(stage8.read_text(encoding="utf-8"))
    if prior.get("stage") != "8-G" or prior.get("status") != "passed" or prior.get("profile") != profile:
        raise ValueError("Prior Stage 8 audit missing or mismatched")
    if prior.get("capture_counts") != {"native": 15, "modal": 16}:
        raise ValueError("Stage 8 app/modal captures have changed unexpectedly")
    resolution = VIEWPORTS[profile]

    def checked(path: Path) -> dict:
        info = image_info(path)
        if (info["width"], info["height"]) != resolution:
            raise ValueError(f"Wrong viewport for {path.name}: {info['width']} x {info['height']}")
        return info

    if len(list(native.glob("*.png"))) != len(REAL_NEW):
        raise ValueError("Expected exactly two new real-MainActivity captures")
    real = {kind: checked(native / name) for kind, name in REAL_NEW.items()}
    if len({x["sha256"] for x in real.values()}) != len(real):
        raise ValueError("The real-app routes produced duplicate screenshots")
    manifests = list(feature.glob("stage9g-manifest.json"))
    if len(manifests) != 1:
        raise ValueError("Stage9G Compose fixture manifest absent")
    manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
    if manifest.get("profile") != profile or manifest.get("count") != len(FEATURES):
        raise ValueError("Stage9G fixture profile/count mismatch")
    if manifest.get("captureType") != "Compose feature components, not MainActivity":
        raise ValueError("Stage9G fixture origin must be explicit")
    rows = manifest.get("captures")
    if not isinstance(rows, list) or len(rows) != len(FEATURES):
        raise ValueError("Missing staged feature capture manifest entries")
    indexed = {row["name"]: row for row in rows}
    if set(indexed) != set(FEATURES) or len(indexed) != len(rows):
        raise ValueError("Stage9G feature names mismatch or duplicate entries")
    if len(list(feature.glob("*.png"))) != len(FEATURES):
        raise ValueError("Expected exactly twelve actual Compose screenshot PNGs")
    features = {}
    for name in FEATURES:
        record = indexed[name]
        if record["file"] != name + ".png":
            raise ValueError("Fixture capture filename mismatch: " + name)
        current = checked(feature / record["file"])
        if current["bytes"] < 50_000:
            raise ValueError(f"Suspiciously blank Android feature viewport: {name} ({current['bytes']} bytes)")
        if (record["width"], record["height"]) != resolution:
            raise ValueError("Fixture capture manifest viewport mismatch")
        features[name] = current
    by_digest = {}
    for name, info in features.items():
        by_digest.setdefault(info["sha256"], []).append(name)
    duplicates = [names for names in by_digest.values() if len(names) > 1]
    permitted = {"01-analysis-complete", "02-analysis-scrolled"}
    if any(set(names) != permitted for names in duplicates):
        raise ValueError(f"Unexpected duplicated feature screenshots: {duplicates}")
    return {
        "stage": "9-G",
        "status": "passed",
        "profile": profile,
        "head_sha": os.environ.get("GITHUB_SHA", ""),
        "capture_method": "UiAutomation.takeScreenshot of native Android window",
        "counts": {
            "prior_main_activity": 15,
            "prior_modal": 16,
            "additional_real_main_activity": len(real),
            "new_feature_components": len(features),
            "total_without_stage9a_folder": 15 + 16 + len(real) + len(features),
        },
        "real_main_activity": real,
        "feature_component_fixtures": features,
        "same_viewport_exceptions": duplicates,
        "prior_stage8_sha256": hashlib.sha256(stage8.read_bytes()).hexdigest(),
        "not_verified_by_capture": [
            "Feature component fixtures do not demonstrate real end-to-end app navigation.",
            "PNG integrity and uniqueness are not a pixel-wise visual comparison with the original design.",
            "Samsung hardware, Android system trash approval, storage capacity and Play Console status require separate evidence.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage8-audit", type=Path, required=True)
    parser.add_argument("--real-native", type=Path, required=True)
    parser.add_argument("--feature-components", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.stage8_audit, args.real_native, args.feature_components, args.profile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Stage9G capture audit passed: {args.profile} / "
          f"{report['counts']['total_without_stage9a_folder']} Android viewport images + 1 Stage9A folder")


if __name__ == "__main__":
    main()
