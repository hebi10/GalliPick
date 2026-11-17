#!/usr/bin/env python3
"""Fail-closed *read-only* preflight for the GalliPick Cloud internal release.

Cloud configuration is only decoded in CI memory, never printed or persisted.
Play release status comes from play_publish.py CHECK mode (no upload or commit).
This script does not check Play Console Data Safety / App Access declarations.
"""
import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

PACKAGE = "com.hebi.gallipick"
PROJECT = "gallipick-dev"
PUBLIC_PRIVACY = "https://hebi10.github.io/GalliPick/privacy.html"
EXPECTED_VERSION = "0.2.13"
EXPECTED_CODE = 24


class PreflightError(Exception):
    pass


def check_main_source(source: str, sha: str, ref: str, expected: str) -> None:
    if ref != "refs/heads/main" or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise PreflightError("Cloud preflight requires an exact main commit")
    if sha != expected:
        raise PreflightError("Checked-out source and GitHub event SHA disagree")
    versions = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', source, re.M)
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)", source, re.M)
    if versions != [EXPECTED_VERSION] or codes != [str(EXPECTED_CODE)]:
        raise PreflightError("Cloud preflight is pinned to GalliPick 0.2.13 (24)")


def check_firebase_cloud_configuration(encoded: str, certificate_sha1: str) -> None:
    normalized = certificate_sha1.lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{40}", normalized) or not encoded:
        raise PreflightError("Play cloud config or Play app-signing SHA-1 is missing")
    try:
        raw = base64.b64decode(encoded, validate=True)
        if not 20 < len(raw) <= 512_000:
            raise ValueError()
        obj = json.loads(raw)
        if obj["project_info"]["project_id"] != PROJECT:
            raise ValueError()
        clients = [
            item for item in obj["client"]
            if item.get("client_info", {}).get("android_client_info", {}).get("package_name") == PACKAGE
        ]
        if len(clients) != 1:
            raise ValueError()
        client = clients[0]
        oauth = client["oauth_client"]
        if not any(
            item.get("client_type") == 1
            and item.get("android_info", {}).get("package_name") == PACKAGE
            and item.get("android_info", {}).get("certificate_hash", "").replace(":", "").lower() == normalized
            for item in oauth
        ):
            raise ValueError()
        if not any(item.get("client_type") == 3
                   and item.get("client_id", "").endswith(".apps.googleusercontent.com")
                   for item in oauth):
            raise ValueError()
        if not client["api_key"][0]["current_key"] or not client["client_info"]["mobilesdk_app_id"]:
            raise ValueError()
    except (ValueError, TypeError, KeyError, IndexError, AttributeError, binascii.Error):
        raise PreflightError(
            "Firebase project, Android package, Play app-signing SHA-1 or OAuth configuration is invalid"
        ) from None


def check_public_privacy(page: bytes) -> None:
    if len(page) > 300_000:
        raise PreflightError("Privacy notice unexpectedly large")
    text = page.decode("utf-8", errors="strict")
    for required in ("GalliPick 개인정보처리방침", "2026년 11월 16일", "Firebase", "클라우드", "Google"):
        if required not in text:
            raise PreflightError("Public privacy page is missing required cloud disclosures")


def check_play_inventory(report: dict, code: int = EXPECTED_CODE) -> dict:
    if not isinstance(report, dict) or report.get("package") != PACKAGE or report.get("track") != "internal" \
        or report.get("mode") != "check" or report.get("status") != "checked" \
        or report.get("api_accepted") is not False:
        raise PreflightError("Google Play read-only inventory is incomplete or was not a check")
    codes = report.get("used_version_codes")
    tracks = report.get("tracks")
    if not isinstance(codes, list) or any(type(c) is not int or c < 1 for c in codes):
        raise PreflightError("Google Play returned invalid uploaded version codes")
    if codes != sorted(set(codes)):
        raise PreflightError("Google Play version codes are not a sorted unique inventory")
    if not isinstance(tracks, list) or not any(
        isinstance(t, dict) and t.get("track") == "internal" for t in tracks
    ):
        raise PreflightError("Existing internal test track is missing")
    for track in tracks:
        if not isinstance(track, dict) or not isinstance(track.get("releases"), list):
            raise PreflightError("Google Play track inventory format is invalid")
        if track.get("track") == "internal" and any(
            item.get("status") != "completed" for item in track["releases"]
        ):
            raise PreflightError("Internal track has a draft or incomplete release")
    if codes and max(codes) >= code:
        raise PreflightError("VersionCode 24 is already used or is not higher than uploaded codes")
    return {
        "package": PACKAGE,
        "track": "internal",
        "target_version_name": EXPECTED_VERSION,
        "target_version_code": code,
        "used_version_codes": codes,
        "highest_uploaded_version_code": max(codes, default=0),
        "play_checked": True,
        "play_uploaded": False,
        "data_safety_declarations_verified": False,
        "firebase_server_runtime_verified": False,
        "privacy_url_verified_separately": True,
    }


def write_json(output: Path, result: dict) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("cloud", "play"))
    parser.add_argument("--play-report", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    opts = parser.parse_args()
    try:
        source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
        checked_out = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        check_main_source(source, os.getenv("GITHUB_SHA", ""), os.getenv("GITHUB_REF", ""), checked_out)
        if opts.mode == "cloud":
            check_firebase_cloud_configuration(
                os.getenv("CLOUD_CONFIG_BASE64", ""),
                os.getenv("PLAY_APP_SIGNING_SHA1", ""),
            )
            try:
                with urlopen(PUBLIC_PRIVACY, timeout=15) as response:
                    if response.status != 200:
                        raise PreflightError("Public privacy page is not HTTP 200")
                    check_public_privacy(response.read(300_001))
            except (OSError, HTTPError, URLError, UnicodeError):
                raise PreflightError("Public privacy page could not be inspected") from None
            report = {
                "source_sha": checked_out,
                "source_verified": True,
                "cloud_config_verified": True,
                "play_signing_oauth_verified": True,
                "public_privacy_verified": True,
                "play_uploaded": False,
            }
        else:
            if not opts.play_report or not opts.play_report.is_file():
                raise PreflightError("Missing Google Play CHECK report")
            report = check_play_inventory(json.loads(opts.play_report.read_text(encoding="utf-8")))
            report["source_sha"] = checked_out
        write_json(opts.output, report)
        print("GalliPick cloud internal preflight:", opts.mode, "verified; Play unchanged.")
        return 0
    except (PreflightError, ValueError, OSError, UnicodeError, subprocess.SubprocessError) as error:
        print("Cloud release preflight blocked:", str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
