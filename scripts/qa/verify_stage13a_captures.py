#!/usr/bin/env python3
"""Verify genuine Android screenshots of the GalliPick local OCR search."""
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

folder = Path(args.captures)
manifest = json.loads((folder / "stage13a-manifest.json").read_text(encoding="utf-8"))
expected = {"01-ocr-scope", "02-ocr-matching-results"}
assert manifest["profile"] == args.profile, "Profile mismatch"
assert manifest["theme"] == args.theme, "Theme mismatch"
assert manifest["count"] == 2, "Capture count mismatch"
assert {item["name"] for item in manifest["captures"]} == expected
assert {image.stem for image in folder.glob("*.png")} == expected
sizes = set()
for entry in manifest["captures"]:
    b = (folder / entry["file"]).read_bytes()
    assert b[:8] == bytes.fromhex("89504e470d0a1a0a"), "Invalid PNG"
    w, h = struct.unpack(">II", b[16:24])
    assert (w, h) == (entry["width"], entry["height"])
    assert len(b) > 20_000, "Blank/undersized screenshot"
    sizes.add((w, h))
assert len(sizes) == 1, "Viewport mismatch"

output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({
    "status": "passed",
    "profile": args.profile,
    "theme": args.theme,
    "screen_count": 2,
    "dimensions": list(sizes.pop()),
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("13-A OCR native screenshot audit passed:", args.profile, args.theme)
