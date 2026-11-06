#!/usr/bin/env python3
"""Validate the nine reference screens and five outcome captures without extra packages."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

SIGNATURE = b"\x89PNG\r\n\x1a\n"
NATIVE_SCREENS = {
    "home": "01-home",
    "files": "02-files",
    "filters": "08-files-filter-dialog",
    "duplicate_results": "09-duplicates-scanned",
    "duplicate_compare": "10-duplicate-comparison",
    "media_detail": "02c-real-jpeg-detail",
    "trash_real_activity": "05c-trash-empty-real-activity",
    "settings_light": "05b-settings-light",
    "settings_dark": "05-settings-dark",
}
MODAL_SCREENS = {
    "trash_populated": "09-trash-list.png",
    "result_success": "12-cleanup-result.png",
    "result_partial": "13-cleanup-partial.png",
    "result_canceled": "14-cleanup-canceled.png",
    "result_failed": "15-cleanup-failed.png",
    "result_unknown": "16-cleanup-unknown.png",
}


def image_info(path: Path) -> dict:
    content = path.read_bytes()
    if len(content) < 8_192 or not content.startswith(SIGNATURE) or content[12:16] != b"IHDR":
        raise ValueError(f"PNG screenshot is empty or invalid: {path}")
    width, height = struct.unpack(">II", content[16:24])
    if min(width, height) < 640:
        raise ValueError(f"Screenshot viewport unexpectedly small: {path} ({width}x{height})")
    return {
        "file": path.name,
        "width": width,
        "height": height,
        "bytes": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
    }


def validate(native: Path, modal: Path, profile: str) -> dict:
    manifests = list(native.glob("*-manifest.json"))
    if len(manifests) != 1:
        raise ValueError(f"Expected one native manifest, found {len(manifests)}")
    manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
    if manifest.get("profile") != profile:
        raise ValueError("Capture profile and manifest profile do not match")
    if tuple(manifest.get(k) for k in ("photos", "videos", "protectedMedia")) != (24, 6, 2):
        raise ValueError("The stage-7A MediaStore fixture inventory has changed")
    if manifest.get("detailFailures") or manifest.get("testFailure"):
        raise ValueError("A native screen capture failed")
    captured = manifest.get("captures")
    if not isinstance(captured, list) or len(captured) != 15:
        raise ValueError("The full Android activity must capture exactly 15 screens")
    native_files = list(native.glob("*.png"))
    modal_files = list(modal.glob("*.png"))
    if len(native_files) != 15 or len(modal_files) != 16:
        raise ValueError(f"Unexpected image count: {len(native_files)} native, {len(modal_files)} modal")
    by_screen = {row.get("screen"): row.get("file") for row in captured if isinstance(row, dict)}
    if len(by_screen) != 15:
        raise ValueError("Duplicate or malformed names in the screenshot manifest")

    native_info = {}
    for screen, name in NATIVE_SCREENS.items():
        actual = by_screen.get(name)
        if not actual or not (native / actual).is_file():
            raise ValueError(f"Missing reference screen: {screen} ({name})")
        native_info[screen] = image_info(native / actual)
    modal_info = {}
    for screen, filename in MODAL_SCREENS.items():
        path = modal / filename
        if not path.is_file():
            raise ValueError(f"Missing modal / outcome: {screen} ({filename})")
        modal_info[screen] = image_info(path)
    for row in captured:
        if not (native / row["file"]).is_file():
            raise ValueError(f"Manifest lists missing screenshot: {row['file']}")
        image_info(native / row["file"])
    for file in modal_files:
        image_info(file)
    if len({value["sha256"] for value in native_info.values()}) < 8:
        raise ValueError("Unexpected duplicate imagery across the nine reference screens")
    if len({modal_info[key]["sha256"] for key in MODAL_SCREENS if key.startswith("result_")}) != 5:
        raise ValueError("The five result states must display distinct screenshots")
    return {
        "profile": profile,
        "status": "passed",
        "capture_method": manifest.get("captureMethod"),
        "counts": {"native": len(native_files), "modal": len(modal_files)},
        "fixture": {"photos": 24, "videos": 6, "protected": 2},
        "reference_screens": native_info,
        "modal_and_result_states": modal_info,
        "notes": [
            "The real-app trash screen validates Android system insets but has an empty catalog.",
            "The 11-item populated trash screenshot uses test-owned MediaStore fixture data in an isolated modal surface.",
            "PNG structural checks cannot replace manual visual review or Samsung device testing.",
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--native", type=Path, required=True)
    parser.add_argument("--modal", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = validate(args.native, args.modal, args.profile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Design QA passed: {args.profile}, 9 reference scenes and 5 outcome states")


if __name__ == "__main__":
    main()
