"""Offline safety checks for Cloud internal Play read-only preflight."""
import base64
import json
import unittest

from cloud_play_preflight import (
    PACKAGE, EXPECTED_CODE, PreflightError, check_firebase_cloud_configuration,
    check_main_source, check_play_inventory, check_public_privacy,
)


class CloudPlayPreflightTests(unittest.TestCase):
    sha = "a" * 40
    release_source = 'versionCode = 24\nversionName = "0.2.13"\n'

    def sample_cloud(self, certificate="b" * 40):
        data = {
            "project_info": {"project_id": "gallipick-dev"},
            "client": [{
                "client_info": {
                    "android_client_info": {"package_name": PACKAGE},
                    "mobilesdk_app_id": "1:123456789:android:abcdef",
                },
                "oauth_client": [
                    {"client_type": 1, "android_info": {
                        "package_name": PACKAGE, "certificate_hash": certificate,
                    }},
                    {"client_type": 3, "client_id": "qa.apps.googleusercontent.com"},
                ],
                "api_key": [{"current_key": "synthetic-test-not-an-api-key"}],
            }],
        }
        return base64.b64encode(json.dumps(data).encode()).decode()

    def sample_play(self, codes=None, status="completed"):
        if codes is None:
            codes = [21, 22, 23]
        return {
            "package": PACKAGE, "track": "internal", "mode": "check",
            "api_accepted": False, "status": "checked",
            "used_version_codes": codes,
            "tracks": [
                {"track": "internal", "releases": [{
                    "status": status, "version_codes": [23],
                }]},
                {"track": "alpha", "releases": [{
                    "status": "completed", "version_codes": [22],
                }]},
            ],
        }

    def test_source_matches_main_tag_and_code(self):
        check_main_source(self.release_source, self.sha, "refs/heads/main", self.sha)
        for source, sha, ref, expected in [
            (self.release_source, self.sha, "refs/heads/feature", self.sha),
            ('versionCode = 23\nversionName = "0.2.12"', self.sha, "refs/heads/main", self.sha),
            (self.release_source, "f" * 40, "refs/heads/main", self.sha),
        ]:
            with self.subTest(ref=ref, source=source):
                with self.assertRaises(PreflightError):
                    check_main_source(source, sha, ref, expected)

    def test_cloud_oauth_requires_actual_play_app_signing_certificate(self):
        check_firebase_cloud_configuration(self.sample_cloud(), "B" * 40)
        with self.assertRaises(PreflightError):
            check_firebase_cloud_configuration(self.sample_cloud(), "a" * 40)
        with self.assertRaises(PreflightError):
            check_firebase_cloud_configuration(self.sample_cloud(), "")
        with self.assertRaises(PreflightError):
            check_firebase_cloud_configuration("not-base64", "b" * 40)
        other = self.sample_cloud().replace("gallipick-dev", "wrong-project")
        # Base64 JSON cannot be manipulated by text replacement; decode and re-encode.
        raw = json.loads(base64.b64decode(self.sample_cloud()))
        raw["project_info"]["project_id"] = "wrong-project"
        malformed = base64.b64encode(json.dumps(raw).encode()).decode()
        with self.assertRaises(PreflightError):
            check_firebase_cloud_configuration(malformed, "b" * 40)

    def test_privacy_disclosure_is_required(self):
        check_public_privacy(
            "GalliPick 개인정보처리방침 2026년 11월 16일 Firebase 클라우드 Google".encode("utf-8")
        )
        for message in ("GalliPick 개인정보처리방침", "GalliPick 개인정보처리방침 Firebase"):
            with self.assertRaises(PreflightError):
                check_public_privacy(message.encode("utf-8"))

    def test_live_inventory_must_be_checked_and_new_version_unused(self):
        output = check_play_inventory(self.sample_play())
        self.assertTrue(output["play_checked"])
        self.assertFalse(output["play_uploaded"])
        self.assertFalse(output["data_safety_declarations_verified"])
        self.assertEqual(output["highest_uploaded_version_code"], 23)
        with self.assertRaises(PreflightError):
            check_play_inventory(self.sample_play(codes=[22, 23, EXPECTED_CODE]))
        with self.assertRaises(PreflightError):
            check_play_inventory(self.sample_play(codes=[22, 24, 25]))
        with self.assertRaises(PreflightError):
            check_play_inventory(self.sample_play(status="draft"))
        with self.assertRaises(PreflightError):
            check_play_inventory({**self.sample_play(), "api_accepted": True})
        with self.assertRaises(PreflightError):
            check_play_inventory({**self.sample_play(), "tracks": []})
        with self.assertRaises(PreflightError):
            check_play_inventory(self.sample_play(codes=[23, 22]))
        with self.assertRaises(PreflightError):
            check_play_inventory(self.sample_play(codes=["23"]))


if __name__ == "__main__":
    unittest.main()
