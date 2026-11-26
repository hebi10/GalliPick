"""OPS-04 offline tests. No Google Play or GitHub mutations."""
import importlib.util
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "ops04" / "internal.py"
spec = importlib.util.spec_from_file_location("ops04_internal", SCRIPT)
internal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(internal)
from r8_test_fixture import write_fixture
from r8_release_gate import require_optimized_bundle

class Ops04SafetyTests(unittest.TestCase):
    def environment(self, mode="publish"):
        return {
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": "a" * 40,
            "EXPECTED_MAIN_SHA": "a" * 40,
            "GITHUB_REPOSITORY": "hebi10/fixture",
            "BATCH_ID": "2026-11-13-fixture",
            "BUILD_RUN_ID": "12345",
            "ARTIFACT_NAME": "verified-signed-aab-123",
            "VERSION_CODE": "123",
            "VERSION_NAME": "1.0.0",
            "AAB_SHA256": "b" * 64,
            "DEPLOY_MODE": mode,
            "FINAL_RELEASE_APPROVED": "false",
            "APPROVAL_PHRASE": "",
        }

    def test_publish_requires_separate_approval_before_network(self):
        with patch.dict(os.environ, self.environment(), clear=True):
            with patch.object(internal, "github") as api:
                with self.assertRaises(internal.GateError):
                    internal.guard()
                api.assert_not_called()

    def test_bad_main_sha_rejected_before_network(self):
        data = self.environment(mode="check")
        data["EXPECTED_MAIN_SHA"] = "c" * 40
        with patch.dict(os.environ, data, clear=True):
            with patch.object(internal, "github") as api:
                with self.assertRaises(internal.GateError):
                    internal.guard()
                api.assert_not_called()

    def test_invalid_artifact_name_rejected(self):
        data = self.environment(mode="check")
        data["ARTIFACT_NAME"] = "../other-run"
        with patch.dict(os.environ, data, clear=True):
            with patch.object(internal, "github") as api:
                with self.assertRaises(internal.GateError):
                    internal.guard()
                api.assert_not_called()

    def test_production_cloud_artifact_requires_matching_explicit_selection(self):
        data = self.environment(mode="check")
        data["CLOUD_PRODUCTION"] = "true"
        with self.assertRaises(internal.GateError):
            internal.verify_cloud_artifact_selection(data)
        data["ARTIFACT_NAME"] = "GalliPick-v1.0.0-cloud-production-play-bundle"
        self.assertTrue(internal.verify_cloud_artifact_selection(data))
        data["CLOUD_PRODUCTION"] = "false"
        with self.assertRaises(internal.GateError):
            internal.verify_cloud_artifact_selection(data)

    def test_missing_bundle_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(internal, "BUNDLE_PATH", Path(folder)):
                with self.assertRaises(internal.GateError):
                    internal.verify()

    def test_verify_requires_original_r8_provenance_and_rechecks_actual_bundle(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            bundle = write_fixture(root / "GalliPick-v1.0.0-release.aab")
            tool = root / "bundletool.jar"
            tool.write_bytes(b"pinned fixture")
            metadata = {"version_code": 123, "version_name": "1.0.0", "package": "com.hebi.gallipick"}
            (root / "manifest.json").write_text(json.dumps(metadata))
            original = {"source_commit": "a" * 40, "bundle_sha256": internal.digest(bundle),
                        "r8_optimization": require_optimized_bundle(bundle)}
            provenance = root / "provenance.json"
            data = {**self.environment("check"), "APP_PACKAGE": "com.hebi.gallipick",
                    "AAB_SHA256": original["bundle_sha256"], "BUNDLETOOL_JAR": str(tool),
                    "GITHUB_OUTPUT": str(root / "github-output")}
            xml = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.hebi.gallipick" android:versionCode="123" android:versionName="1.0.0"/>'
            real_digest = internal.digest
            real_read = Path.read_text
            pinned = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
            with patch.dict(os.environ, data, clear=True), \
                    patch.object(internal, "BUNDLE_PATH", root), \
                    patch.object(internal, "OUT", root / "verified"), \
                    patch.object(internal, "digest", side_effect=lambda p: pinned if p == tool else real_digest(p)), \
                    patch.object(internal, "invoke", return_value=xml), \
                    patch.object(Path, "read_text", autospec=True,
                                 side_effect=lambda path, *args, **kwargs:
                                 "b" * 64 if str(path) == "signing/certificate.sha256"
                                 else real_read(path, *args, **kwargs)):
                provenance.write_text(json.dumps({key: value for key, value in original.items()
                                                 if key != "r8_optimization"}))
                with self.assertRaisesRegex(internal.GateError, "R8 release gate"):
                    internal.verify()
                self.assertFalse((root / "verified").exists())
                provenance.write_text(json.dumps(original))
                internal.verify()
                self.assertEqual(json.loads((root / "verified/verified.json").read_bytes())["r8_optimization"],
                                 original["r8_optimization"])

    def test_sha256_streaming_matches_reference(self):
        with tempfile.TemporaryDirectory() as folder:
            bundle = Path(folder) / "x.aab"
            bundle.write_bytes(b"ops04-immutable-byte-fixture")
            import hashlib
            self.assertEqual(internal.digest(bundle), hashlib.sha256(bundle.read_bytes()).hexdigest())

    def test_workflow_never_builds_or_starts_on_push(self):
        source = (ROOT / ".github" / "workflows" / "play-internal-release.yml").read_text(encoding="utf-8")
        self.assertIn("  workflow_dispatch:", source)
        self.assertNotRegex(source, r"(?m)^  (push|workflow_run|issue_comment|schedule|pull_request):")
        self.assertIn("inputs.mode == 'publish'", source)
        self.assertIn("PUBLISH_VERIFIED_INTERNAL_AAB", source)
        self.assertIn("scripts/ops04/internal.py verify", source)
        self.assertNotIn("bundleRelease", source)
        self.assertNotIn("assembleRelease", source)
        self.assertNotIn("gh release create", source)
        self.assertIn("androidpublisher", source)

if __name__ == "__main__":
    unittest.main()
