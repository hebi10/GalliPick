"""Offline safety checks for Cloud internal Play read-only preflight."""
import base64
import json
import unittest

from cloud_play_preflight import (
    PACKAGE, PROJECT, PreflightError, check_firebase_cloud_configuration,
    check_main_source, check_play_inventory, check_public_privacy,
)


class CloudPlayPreflightTests(unittest.TestCase):
    sha = "a" * 40
    version = "0.2.27"
    code = 38
    release_source = f'versionCode = {code}\nversionName = "{version}"\n'

    @staticmethod
    def encode(data):
        return base64.b64encode(json.dumps(data).encode()).decode()

    def sample_cloud(self, certificate="b" * 40):
        return {
            "project_info": {"project_id": PROJECT, "project_number": "123456789"},
            "client": [{
                "client_info": {
                    "android_client_info": {"package_name": PACKAGE},
                    "mobilesdk_app_id": "1:123456789:android:abcdef",
                },
                "oauth_client": [
                    {"client_type": 1, "client_id": "123456789-android.apps.googleusercontent.com",
                     "android_info": {"package_name": PACKAGE, "certificate_hash": certificate}},
                    {"client_type": 3, "client_id": "123456789-web.apps.googleusercontent.com"},
                ],
                "api_key": [{"current_key": "synthetic-test-not-an-api-key"}],
            }],
        }

    def sample_play(self, codes=None, status="completed"):
        if codes is None:
            codes = [35, 36, 37]
        return {
            "package": PACKAGE, "track": "internal", "mode": "check",
            "api_accepted": False, "status": "checked",
            "used_version_codes": codes,
            "tracks": [
                {"track": "internal", "releases": [{"status": status, "version_codes": [37]}]},
                {"track": "alpha", "releases": [{"status": "completed", "version_codes": [36]}]},
            ],
        }

    def test_source_matches_main_and_returns_current_version(self):
        self.assertEqual(check_main_source(self.release_source, self.sha, "refs/heads/main", self.sha),
                         (self.version, self.code))
        for source, sha, ref, expected in [
            (self.release_source, self.sha, "refs/heads/feature", self.sha),
            ('versionCode = 38\nversionName = "0.2.27-debug"', self.sha, "refs/heads/main", self.sha),
            (self.release_source + "versionCode = 39\n", self.sha, "refs/heads/main", self.sha),
            (self.release_source, "f" * 40, "refs/heads/main", self.sha),
        ]:
            with self.subTest(ref=ref, source=source):
                with self.assertRaises(PreflightError):
                    check_main_source(source, sha, ref, expected)

    def test_cloud_oauth_requires_actual_play_app_signing_certificate(self):
        config = self.encode(self.sample_cloud())
        check_firebase_cloud_configuration(config, "B" * 40)
        for encoded, sha1 in ((config, "a" * 40), (config, ""), ("not-base64", "b" * 40)):
            with self.assertRaises(PreflightError):
                check_firebase_cloud_configuration(encoded, sha1)

    def test_cloud_oauth_rejects_dev_fallback_and_mismatched_project(self):
        config = self.sample_cloud()
        config["project_info"]["project_id"] = "gallipick-dev"
        with self.assertRaises(PreflightError):
            check_firebase_cloud_configuration(self.encode(config), "b" * 40)

        for client_index in (0, 1):
            with self.subTest(client_index=client_index):
                config = self.sample_cloud()
                config["client"][0]["oauth_client"][client_index]["client_id"] = (
                    "999999999-external.apps.googleusercontent.com"
                )
                with self.assertRaises(PreflightError):
                    check_firebase_cloud_configuration(self.encode(config), "b" * 40)

        config = self.sample_cloud()
        config["client"][0]["oauth_client"].pop(0)
        with self.assertRaises(PreflightError):
            check_firebase_cloud_configuration(self.encode(config), "b" * 40)

    def test_cloud_oauth_requires_unambiguous_production_clients(self):
        config = self.sample_cloud()
        config["client"][0]["oauth_client"].append(
            {"client_type": 3, "client_id": "123456789-extra.apps.googleusercontent.com"}
        )
        with self.assertRaises(PreflightError):
            check_firebase_cloud_configuration(self.encode(config), "b" * 40)

        config = self.sample_cloud()
        config["client"][0]["client_info"]["mobilesdk_app_id"] = "1:999999999:android:abcdef"
        with self.assertRaises(PreflightError):
            check_firebase_cloud_configuration(self.encode(config), "b" * 40)

    def test_privacy_disclosure_is_required(self):
        check_public_privacy(
            f"GalliPick 개인정보처리방침 2026년 11월 20일 {PACKAGE} Firebase 클라우드 Google".encode("utf-8")
        )
        for message in ("GalliPick 개인정보처리방침", "GalliPick 개인정보처리방침 Firebase 클라우드 Google"):
            with self.assertRaises(PreflightError):
                check_public_privacy(message.encode("utf-8"))

    def test_live_inventory_must_be_checked_and_new_version_unused(self):
        output = check_play_inventory(self.sample_play(), self.version, self.code)
        self.assertTrue(output["play_checked"])
        self.assertFalse(output["play_uploaded"])
        self.assertFalse(output["data_safety_declarations_verified"])
        self.assertEqual(output["target_version_name"], self.version)
        self.assertEqual(output["target_version_code"], self.code)
        self.assertEqual(output["highest_uploaded_version_code"], 37)
        invalid_reports = [
            self.sample_play(codes=[36, 37, self.code]),
            self.sample_play(codes=[36, 38, 39]),
            self.sample_play(status="draft"),
            {**self.sample_play(), "api_accepted": True},
            {**self.sample_play(), "tracks": []},
            self.sample_play(codes=[37, 36]),
            self.sample_play(codes=["37"]),
        ]
        for report in invalid_reports:
            with self.subTest(report=report):
                with self.assertRaises(PreflightError):
                    check_play_inventory(report, self.version, self.code)


if __name__ == "__main__":
    unittest.main()
