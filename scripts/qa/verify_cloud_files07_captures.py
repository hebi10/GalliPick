#!/usr/bin/env python3
"""Validate CloudFiles07CaptureTest's real PNG assets (synthetic catalog data)."""

import argparse
import json
from pathlib import Path
import struct


CAPTURES = {
    f"{theme}-{name}.png"
    for theme in ("light", "dark")
    for name in (
        "01-combined",
        "02-cloud",
        "03-offline",
        "04-metered",
    )
}


def verify(folder: Path, profile: str, size: str) -> dict:
    width, height = (int(value) for value in size.split("x"))
    manifest_path = folder / f"{profile}-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert manifest["profile"] == profile, "Unexpected capture profile"
    assert "synthetic" in manifest["fixture"], "Captures must not use a real cloud account"
    assert manifest["captureCount"] == len(CAPTURES), "Incomplete screenshot manifest"

    manifest_files = {
        entry["file"]: entry
        for entry in manifest["captures"]
    }
    assert set(manifest_files) == CAPTURES, "Capture filenames do not match the QA contract"
    on_disk = {file.name for file in folder.glob("*.png")}
    assert on_disk == CAPTURES, f"Missing or excess PNG assets: {on_disk ^ CAPTURES}"
    for name in sorted(CAPTURES):
        entry = manifest_files[name]
        path = folder / name
        data = path.read_bytes()
        assert data[:8] == bytes.fromhex("89504e470d0a1a0a"), f"Not a PNG: {name}"
        actual_width, actual_height = struct.unpack(">II", data[16:24])
        assert (actual_width, actual_height) == (width, height), f"Wrong viewport: {name}"
        assert (entry["width"], entry["height"]) == (width, height), f"Manifest mismatch: {name}"
        assert len(data) > 2000, f"Empty screenshot: {name}"

    return {
        "profile": profile,
        "screenshots": len(CAPTURES),
        "themes": ["light", "dark"],
        "views": ["combined", "cloud", "offline", "metered"],
        "viewport": [width, height],
        "data": "synthetic-catalog",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, default=Path("cloud-qa-captures"))
    parser.add_argument("--profile", required=True)
    parser.add_argument("--size", required=True)
    values = parser.parse_args()
    print(json.dumps(
        verify(values.directory, values.profile, values.size),
        ensure_ascii=False,
    ))
