#!/usr/bin/env python3
"""Safely update only the Korean GalliPick store icon from the checked-in cat art."""
import argparse
import hashlib
import os
from pathlib import Path
import struct
import sys

from play_listing_images import api, upload, BASE, LANGUAGE


def validate_icon(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(path)
    size = path.stat().st_size
    if size > 1_048_576 or size < 64:
        raise ValueError("Play icon PNG must be 1 MiB or less")
    header = path.read_bytes()[:26]
    if len(header) != 26 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError("Play icon is not a PNG")
    width, height = struct.unpack(">II", header[16:24])
    if (width, height) != (512, 512) or header[24] != 8 or header[25] != 6:
        raise ValueError("Play icon must be a 512x512 32-bit RGBA PNG")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f"Valid Play icon: 512x512 RGBA, {size} bytes, SHA-256 {digest}")
    return digest


def apply(icon: Path, token: str) -> None:
    edit = api(token, "POST", f"{BASE}/edits", {})
    edit_id = edit.get("id")
    if not edit_id:
        raise RuntimeError("Play did not issue an edit ID")
    endpoint = f"{BASE}/edits/{edit_id}/listings/{LANGUAGE}/icon"
    api(token, "DELETE", endpoint)
    upload(token, edit_id, "icon", icon)
    image_set = api(token, "GET", endpoint)
    if len(image_set.get("images", [])) != 1:
        raise RuntimeError("Play did not return one GalliPick icon")
    response = api(token, "POST", f"{BASE}/edits/{edit_id}:commit", {})
    if not response.get("id"):
        raise RuntimeError("Play icon edit commit returned no ID")
    # A new edit confirms the accepted listing state, not Google review/publishing.
    follow_up = api(token, "POST", f"{BASE}/edits", {})
    follow_up_id = follow_up.get("id")
    if not follow_up_id:
        raise RuntimeError("Play did not issue a verification edit")
    images = api(token, "GET", f"{BASE}/edits/{follow_up_id}/listings/{LANGUAGE}/icon")
    if len(images.get("images", [])) != 1:
        raise RuntimeError("Play icon was not present in a fresh edit")
    print("Google Play accepted and read back the GalliPick ko-KR icon edit. Check review/publishing separately.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--icon", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    options = parser.parse_args()
    validate_icon(options.icon)
    if options.apply:
        token = os.getenv("PLAY_ACCESS_TOKEN", "")
        if not token:
            raise RuntimeError("Missing PLAY_ACCESS_TOKEN")
        apply(options.icon, token)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, FileNotFoundError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
