#!/usr/bin/env python3
"""Stage 8 native-capture integrity audit. This does not replace visual review."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from verify_design_captures import image_info

VIEWPORTS = {
    "phone-portrait": (1080, 2400),
    "phone-landscape": (2400, 1080),
    "phone-large-text": (1080, 2400),
    "tablet-portrait": (1600, 2560),
    "tablet-landscape": (2560, 1600),
}
NATIVE = {
    "home-shortcuts": "01-home",
    "file-selection": "02-files",
    "duplicate-horizontal-list": "09-duplicates-scanned",
    "duplicate-keeper-first-compare": "10-duplicate-comparison",
}
MODALS = {
    "duplicate-compare": "06-duplicate-compare.png",
    "confirmed-bytes-success": "12-cleanup-result.png",
    "confirmed-bytes-partial": "13-cleanup-partial.png",
    "canceled": "14-cleanup-canceled.png",
    "failed": "15-cleanup-failed.png",
    "unknown": "16-cleanup-unknown.png",
}


def audit(native: Path, modal: Path, design: Path, profile: str) -> dict:
    if profile not in VIEWPORTS:
        raise ValueError("Unsupported Stage 8 viewport: " + profile)
    prior = json.loads(design.read_text(encoding="utf-8"))
    if prior.get("status") != "passed" or prior.get("profile") != profile:
        raise ValueError("Stage 7-G screenshot audit was not completed for this profile")
    if prior.get("counts") != {"native": 15, "modal": 16}:
        raise ValueError("Expected exactly 15 real-activity and 16 modal screenshots")
    fixture = prior.get("fixture", {})
    if (fixture.get("photos"), fixture.get("videos"), fixture.get("protected")) != (24, 6, 2):
        raise ValueError("Stage 8 fixture identity or coverage changed")
    manifests = list(native.glob("*-manifest.json"))
    if len(manifests) != 1:
        raise ValueError("Expected one native Android capture manifest")
    manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
    if manifest.get("profile") != profile:
        raise ValueError("Native manifest/profile mismatch")
    captures = {row["screen"]: row["file"] for row in manifest.get("captures", [])}
    expected_size = VIEWPORTS[profile]

    def verified(path: Path) -> dict:
        if not path.is_file():
            raise ValueError("Missing Stage 8 screenshot: " + str(path))
        entry = image_info(path)
        if (entry["width"], entry["height"]) != expected_size:
            raise ValueError("Wrong Android viewport for " + path.name)
        return entry

    native_checks = {}
    for title, screen in NATIVE.items():
        name = captures.get(screen)
        if not name:
            raise ValueError("Missing native screen identity: " + screen)
        native_checks[title] = verified(native / name)
    modal_checks = {title: verified(modal / file) for title, file in MODALS.items()}
    key_hashes = [value["sha256"] for value in native_checks.values()]
    if len(set(key_hashes)) != len(key_hashes):
        raise ValueError("A key screen was reused instead of being captured independently")
    outcome_hashes = [modal_checks[x]["sha256"] for x in ("confirmed-bytes-success", "confirmed-bytes-partial", "canceled", "failed", "unknown")]
    if len(set(outcome_hashes)) != len(outcome_hashes):
        raise ValueError("Cleanup result states are not distinct screenshots")
    audit_bytes = design.read_bytes()
    return {
        "stage": "8-G",
        "status": "passed",
        "profile": profile,
        "head_sha": os.getenv("GITHUB_SHA", ""),
        "capture_counts": prior["counts"],
        "prior_design_audit_sha256": hashlib.sha256(audit_bytes).hexdigest(),
        "native": native_checks,
        "modal": modal_checks,
        "limitations": [
            "Pixel structure and capture identity checks do not prove visual fidelity.",
            "Simulated MediaStore examples do not replace real-device trash approval/restore QA.",
            "Google Play publication and signing are not exercised by this audit.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--modal", type=Path, required=True)
    parser.add_argument("--design-audit", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.native, args.modal, args.design_audit, args.profile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Stage 8-G capture integrity passed: {args.profile}, {len(NATIVE)} native + {len(MODALS)} modal targets")


if __name__ == "__main__":
    main()
