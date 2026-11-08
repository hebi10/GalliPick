#!/usr/bin/env python3
"""Stage 10-E: verify theme-specific native Android capture provenance and coverage.

This validates screenshot identity, fixture provenance and the requested theme.
It does not claim visual parity or Samsung hardware testing.
"""
import argparse
import json
from pathlib import Path

from verify_design_captures import image_info
from verify_stage8_captures import VIEWPORTS


def read_one(directory: Path, pattern: str) -> dict:
    files = list(directory.glob(pattern))
    if len(files) != 1:
        raise ValueError(f"Expected exactly one {pattern} in {directory}, found {len(files)}")
    return json.loads(files[0].read_text(encoding="utf-8"))


def audit(profile: str, theme: str, native: Path, modal: Path,
          folder: Path, real: Path, features: Path, prior: Path) -> dict:
    if profile not in VIEWPORTS or theme not in ("light", "dark"):
        raise ValueError("Unknown capture profile or theme")
    expected_viewport = VIEWPORTS[profile]
    manifest = read_one(native, "*-manifest.json")
    modals = read_one(modal, "stage10e-theme.json")
    feature = read_one(features, "stage9g-manifest.json")
    last_audit = json.loads(prior.read_text(encoding="utf-8"))
    for source, label in ((manifest, "MainActivity"), (modals, "modal fixtures"),
                          (feature, "feature fixtures")):
        if source.get("captureTheme") != theme:
            raise ValueError(f"{label} was not captured in {theme} mode")
    if manifest.get("profile") != profile or feature.get("profile") != profile:
        raise ValueError("Native capture profile mismatch")
    if modals.get("count") != 16 or feature.get("count") != 12:
        raise ValueError("Incomplete Compose fixture screenshots")
    if last_audit.get("stage") != "9-G" or last_audit.get("status") != "passed":
        raise ValueError("Prior screenshot integrity and regression audit failed")
    if last_audit.get("profile") != profile:
        raise ValueError("Prior capture profile mismatch")

    expected_counts = {"real_main_activity": 15, "modal_detail": 16,
                       "stage9g_real_activity": 2, "stage9g_feature": 12, "stage9a_folder": 1}
    directories = {"real_main_activity": native, "modal_detail": modal,
                   "stage9g_real_activity": real, "stage9g_feature": features,
                   "stage9a_folder": folder}
    digest_groups = {}
    for kind, directory in directories.items():
        pngs = sorted(directory.glob("*.png"))
        if len(pngs) != expected_counts[kind]:
            raise ValueError(f"{kind}: expected {expected_counts[kind]} PNGs, found {len(pngs)}")
        sizes = set()
        digests = []
        for p in pngs:
            i = image_info(p)
            sizes.add((i["width"], i["height"]))
            digests.append(i["sha256"])
        if sizes != {expected_viewport}:
            raise ValueError(f"{kind}: wrong native viewport {sizes} != {expected_viewport}")
        digest_groups[kind] = len(set(digests))
    if digest_groups["real_main_activity"] < 10 or digest_groups["modal_detail"] < 12:
        raise ValueError("Excessive repeated images, screenshot integrity questionable")
    return {
        "stage": "10-E", "status": "passed", "profile": profile, "theme": theme,
        "capture_type": "real MainActivity + explicitly separated Compose fixture screens",
        "counts": expected_counts,
        "total_images": sum(expected_counts.values()),
        "distinct_image_counts": digest_groups,
        "provenance": "Android 36 emulator UiAutomation viewport, test-owned fixtures",
        "not_verified": [
            "Visual parity with approved design and subjective image quality require image review.",
            "Real Samsung phone or foldable behavior and Android trash system UI are not proven by emulator images.",
            "A theme transition screenshot in 05-settings-dark and 05b-settings-light deliberately uses the opposite theme.",
            "Historic names 02-calendar-dark and 12-history-dark are preserved for regression compatibility even in forced light mode.",
        ],
    }


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--profile", required=True)
    p.add_argument("--theme", choices=["light", "dark"], required=True)
    p.add_argument("--native", type=Path, required=True)
    p.add_argument("--modal", type=Path, required=True)
    p.add_argument("--folder", type=Path, required=True)
    p.add_argument("--real", type=Path, required=True)
    p.add_argument("--features", type=Path, required=True)
    p.add_argument("--stage9g-audit", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    result = audit(args.profile, args.theme, args.native, args.modal,
                   args.folder, args.real, args.features, args.stage9g_audit)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Stage 10-E structural QA passed: {args.profile} / {args.theme} / {result['total_images']} PNGs")


if __name__ == "__main__":
    main()
