#!/usr/bin/env python3
"""Validate real Android screenshots of the Stage 12-C review inbox."""
import argparse
import json
import struct
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--captures", required=True)
parser.add_argument("--profile", required=True)
parser.add_argument("--theme", required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()
root = Path(args.captures)
manifest_file = root / "stage12c-manifest.json"
manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
expected = {"01-review-list", "02-review-detail"}
assert manifest["profile"] == args.profile, "Profile mismatch"
assert manifest["theme"] == args.theme, "Theme mismatch"
assert manifest["count"] == 2, "Capture count mismatch"
assert len(manifest["captures"]) == 2, "Manifest inventory mismatch"
assert {item["name"] for item in manifest["captures"]} == expected
assert {p.stem for p in root.glob("*.png")} == expected, "PNG inventory mismatch"

sizes = set()
for item in manifest["captures"]:
    path = root / item["file"]
    data = path.read_bytes()
    assert data[:8] == bytes.fromhex("89504e470d0a1a0a"), "Invalid PNG"
    width, height = struct.unpack(">II", data[16:24])
    assert width == item["width"] and height == item["height"], "Viewport mismatch"
    assert len(data) > 20000, "Blank/undersized screenshot"
    sizes.add((width, height))
assert len(sizes) == 1, "Screenshots from different viewport sizes"

out = Path(args.output)
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({
    "status": "passed",
    "profile": args.profile,
    "theme": args.theme,
    "screenshots": 2,
    "size": list(sizes.pop()),
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Stage12-C screenshot audit passed:", args.profile, args.theme)
