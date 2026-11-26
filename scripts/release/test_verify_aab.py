import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from signing_bundle import ReleaseError
from r8_test_fixture import fixture_entries, write_fixture

try:
    import verify_aab
except ModuleNotFoundError:
    verify_aab = None


SOURCE = 'versionCode = 8\nversionName = "0.1.7"\n'
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="com.hebi.gallipick" android:versionCode="8" android:versionName="0.1.7">
 <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/>
 <application android:debuggable="false"/>
</manifest>'''
METADATA = {"package": "com.hebi.gallipick", "version_code": 8, "version_name": "0.1.7",
            "min_sdk": 30, "target_sdk": 36, "debuggable": False}
JAVA_SOURCE = Path(__file__).with_name("VerifyAabSignature.java")


def firebase_resource(project):
    return ("Package 'com.hebi.gallipick':\n0x7f1200a0 - string/cloud_firebase_project_id\n"
            f'\t(default) - [STR] "{project}"\n\n')


class AabMetadataTests(unittest.TestCase):
    def api(self):
        self.assertIsNotNone(verify_aab, "AAB verification is not implemented")
        return verify_aab

    def test_manifest_must_match_the_release_source_and_android_contract(self):
        api = self.api()
        self.assertEqual(api.parse_manifest(MANIFEST), METADATA)
        self.assertEqual(api.validate_metadata("v0.1.7", SOURCE, METADATA), ("0.1.7", 8))
        self.assertFalse(api.parse_manifest(MANIFEST.replace(' android:debuggable="false"', ''))["debuggable"])

    def test_wrong_identity_version_sdk_or_debuggable_is_rejected(self):
        api = self.api()
        for key, value in [("package", "com.hebi.gallipick.preview"), ("version_code", 7),
                           ("version_name", "0.1.6"), ("min_sdk", 29), ("target_sdk", 35), ("debuggable", True)]:
            with self.subTest(key=key), self.assertRaises(ReleaseError):
                api.validate_metadata("v0.1.7", SOURCE, dict(METADATA, **{key: value}))
        with self.assertRaises(ReleaseError):
            api.validate_metadata("v0.1.6", SOURCE, METADATA)

    def test_malformed_or_ambiguous_manifest_is_rejected(self):
        api = self.api()
        for manifest in ["not xml", MANIFEST.replace('<application android:debuggable="false"/>', ''),
                         MANIFEST.replace('</manifest>', '<application/></manifest>'),
                         MANIFEST.replace('android:minSdkVersion="30"', 'android:minSdkVersion="x"'),
                         MANIFEST.replace('android:debuggable="false"', 'android:debuggable="unknown"'),
                         '<!DOCTYPE manifest [<!ENTITY x "private">]>' + MANIFEST]:
            with self.subTest(manifest=manifest[:30]), self.assertRaises(ReleaseError):
                api.parse_manifest(manifest)

    def test_firebase_project_must_be_the_unambiguous_default_for_the_selected_environment(self):
        api = self.api()
        prod = firebase_resource("gallipick-production")
        api.validate_cloud_project(prod, "production")
        api.validate_cloud_project(firebase_resource("gallipick-dev"), "development")
        for resources in ("", firebase_resource("gallipick-dev"), prod + firebase_resource("gallipick-dev"),
                          prod.replace("com.hebi.gallipick", "wrong.package"),
                          prod + '\tlocale: "ko" - [STR] "gallipick-dev"\n'):
            with self.subTest(resources=resources), self.assertRaises(ReleaseError):
                api.validate_cloud_project(resources, "production")

    def test_archive_rejects_private_material_unsafe_names_and_duplicate_entries(self):
        api = self.api()
        with tempfile.TemporaryDirectory() as root:
            bundle = Path(root) / "fixture.aab"
            for name, content in [("base/assets/recovery.JKS", b"fixture"),
                                  ("base/assets/signing.properties", b"fixture"),
                                  ("base/assets/GalliPick-signing-recovery.zip", b"fixture"),
                                  ("base/assets/notes.txt", b"-----BEGIN PRIVATE KEY-----"),
                                  ("../outside", b"fixture"), ("base\\assets\\key", b"fixture")]:
                with self.subTest(name=name):
                    with zipfile.ZipFile(bundle, "w") as archive:
                        archive.writestr(name, content)
                    with self.assertRaises(ReleaseError):
                        api.verify_archive(bundle)
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("base/assets/file", b"one")
                archive.writestr("BASE/assets/file", b"two")
            with self.assertRaises(ReleaseError):
                api.verify_archive(bundle)

    def test_public_bundle_content_is_allowed_and_bad_zip_is_sanitized(self):
        api = self.api()
        with tempfile.TemporaryDirectory() as root:
            bundle = Path(root) / "fixture.aab"
            with zipfile.ZipFile(bundle, "w") as archive:
                archive.writestr("base/manifest/AndroidManifest.xml", b"fixture protobuf")
                archive.writestr("META-INF/services/example.Service", b"public service")
            api.verify_archive(bundle)
            bundle.write_bytes(b"not a zip containing private diagnostic text")
            with self.assertRaises(ReleaseError) as failure:
                api.verify_archive(bundle)
            self.assertNotIn("private diagnostic", str(failure.exception))

    def test_external_tool_failures_do_not_echo_command_output(self):
        api = self.api()
        failed = subprocess.CompletedProcess(["java"], 1, "sensitive stdout", "sensitive stderr")
        with patch("verify_aab.subprocess.run", return_value=failed):
            with self.assertRaises(ReleaseError) as failure:
                api.command_output(["java", "fixture"], "Bundle validation")
        self.assertNotIn("sensitive", str(failure.exception))

    def test_staged_bundle_checksums_and_json_describe_only_verified_public_facts(self):
        api = self.api()
        with tempfile.TemporaryDirectory() as root:
            bundle = Path(root) / "input.aab"
            write_fixture(bundle)
            output = Path(root) / "output"
            api.stage_release(bundle, output, "v0.1.7", METADATA,
                              {"certificate_sha256": "a" * 64, "verified_entries": 3}, "b" * 40, "c" * 64)
            self.assertEqual({p.name for p in output.iterdir()},
                             {"GalliPick-v0.1.7-release.aab", "SHA256SUMS", "manifest.json", "provenance.json"})
            digest = hashlib.sha256(bundle.read_bytes()).hexdigest()
            self.assertEqual((output / "SHA256SUMS").read_text(), digest + "  GalliPick-v0.1.7-release.aab\n")
            self.assertEqual(json.loads((output / "manifest.json").read_text()), METADATA)
            provenance = json.loads((output / "provenance.json").read_text())
            self.assertEqual(provenance["source_commit"], "b" * 40)
            self.assertEqual(provenance["bundle_sha256"], digest)
            self.assertEqual(provenance["signer_certificate_sha256"], "a" * 64)
            self.assertEqual(provenance["r8_optimization"]["bundle_sha256"], digest)
            self.assertEqual(provenance["r8_optimization"]["mapping_class_proxy"]["status"], "available")
            self.assertNotIn(str(root), json.dumps(provenance))
            with self.assertRaises(ReleaseError):
                api.stage_release(bundle, output, "v0.1.7", METADATA,
                                  {"certificate_sha256": "a" * 64, "verified_entries": 3}, "b" * 40, "c" * 64)


@unittest.skipUnless(all(shutil.which(tool) for tool in ("java", "keytool", "jarsigner")), "JDK 17 tools required")
class AabSignatureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.keystore = cls.root / "test-only.p12"
        for alias in ("one", "two"):
            result = subprocess.run(["keytool", "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                                     "-validity", "2", "-dname", "CN=AAB verifier test fixture", "-storetype", "PKCS12",
                                     "-keystore", str(cls.keystore), "-storepass", "test-fixture-only", "-keypass", "test-fixture-only"],
                                    capture_output=True)
            if result.returncode:
                raise AssertionError("Cannot prepare the temporary test signing fixture")
        cert = subprocess.run(["keytool", "-exportcert", "-alias", "one", "-keystore", str(cls.keystore),
                               "-storepass", "test-fixture-only"], capture_output=True, check=True).stdout
        cls.pin = hashlib.sha256(cert).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def bundle(self, name, *, signed=True, alias="one", r8=False, **options):
        bundle = self.root / name
        with zipfile.ZipFile(bundle, "w") as archive:
            archive.writestr("base/manifest/AndroidManifest.xml", b"fixture protobuf manifest")
            if r8:
                for entry, content in fixture_entries(**options).items():
                    archive.writestr(entry, content)
            else:
                archive.writestr("base/dex/classes.dex", b"fixture payload")
            archive.writestr("META-INF/services/example.Service", b"signed metadata payload")
        if signed:
            self.sign(bundle, alias)
        return bundle

    def sign(self, bundle, alias):
        result = subprocess.run(["jarsigner", "-keystore", str(self.keystore), "-storepass", "test-fixture-only",
                                 "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", str(bundle), alias], capture_output=True)
        self.assertEqual(result.returncode, 0, "Cannot sign the temporary test fixture")

    def verify(self, bundle, pin=None):
        self.assertTrue(JAVA_SOURCE.is_file(), "Actual AAB payload signature verification is not implemented")
        return subprocess.run(["java", str(JAVA_SOURCE), str(bundle), pin or self.pin], text=True, capture_output=True)

    def test_signed_payloads_and_signed_non_signature_metadata_are_verified(self):
        result = self.verify(self.bundle("valid.aab"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {"certificate_sha256": self.pin, "verified_entries": 3})

    def test_unsigned_payload_is_rejected(self):
        self.assertNotEqual(self.verify(self.bundle("unsigned.aab", signed=False)).returncode, 0)

    def test_tampered_payload_is_rejected_without_echoing_contents(self):
        bundle = self.bundle("tampered.aab")
        with zipfile.ZipFile(bundle) as archive:
            contents = {entry.filename: archive.read(entry) for entry in archive.infolist()}
        contents["base/dex/classes.dex"] = b"private diagnostic payload"
        with zipfile.ZipFile(bundle, "w") as archive:
            for name, content in contents.items():
                archive.writestr(name, content)
        result = self.verify(bundle)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("private diagnostic", result.stderr + result.stdout)

    def test_unsigned_metadata_cannot_hide_under_meta_inf(self):
        bundle = self.bundle("unsigned-metadata.aab")
        with zipfile.ZipFile(bundle, "a") as archive:
            archive.writestr("META-INF/unsigned-payload.txt", b"unsigned")
        self.assertNotEqual(self.verify(bundle).returncode, 0)

    def test_wrong_certificate_and_multiple_signers_are_rejected(self):
        bundle = self.bundle("wrong-pin.aab")
        self.assertNotEqual(self.verify(bundle, "0" * 64).returncode, 0)
        self.sign(bundle, "two")
        self.assertNotEqual(self.verify(bundle).returncode, 0)

    def run_cli(self, bundle, *, manifest=MANIFEST, validation_fails=False,
                cloud_environment=None, cloud_resources=""):
        self.assertIsNotNone(verify_aab, "AAB verification is not implemented")
        # Only the unavailable official bundletool and git commit boundary are doubled.
        # The archive, snapshot, XML validation, Java signature verification and output are real.
        real_run = subprocess.run
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        project = Path(temporary.name)
        (project / "app").mkdir()
        (project / "app/build.gradle.kts").write_text(SOURCE)
        (project / "signing").mkdir()
        (project / "signing/certificate.sha256").write_text(self.pin + "\n")
        tool = project / "bundletool.jar"
        tool.write_bytes(b"bundletool boundary fixture")
        output = project / "output"

        def external(command, **kwargs):
            if command == ["git", "rev-parse", "HEAD"]:
                return subprocess.CompletedProcess(command, 0, "b" * 40 + "\n", "")
            if command[:3] == ["java", "-jar", str(tool)]:
                operation = command[3:]
                snapshot = Path(next(argument.removeprefix("--bundle=") for argument in operation
                                     if argument.startswith("--bundle=")))
                self.assertNotEqual(snapshot, bundle)
                self.assertEqual(snapshot.read_bytes(), bundle.read_bytes())
                if operation == ["validate", "--bundle=" + str(snapshot)]:
                    return subprocess.CompletedProcess(command, int(validation_fails), "private tool diagnostic", "")
                if operation == ["dump", "manifest", "--bundle=" + str(snapshot), "--module=base"]:
                    return subprocess.CompletedProcess(command, 0, manifest, "")
                if operation == ["dump", "resources", "--bundle=" + str(snapshot),
                                 "--resource=string/cloud_firebase_project_id", "--values"]:
                    return subprocess.CompletedProcess(command, 0, cloud_resources, "")
                self.fail("Unexpected bundletool invocation")
            return real_run(command, **kwargs)

        argv = ["verify_aab.py", "--bundle", str(bundle), "--bundletool", str(tool),
                "--tag", "v0.1.7", "--output", str(output)]
        if cloud_environment:
            argv.extend(["--cloud-environment", cloud_environment])
        previous = Path.cwd()
        try:
            os.chdir(project)
            with patch.object(sys, "argv", argv), \
                    patch("verify_aab.subprocess.run", side_effect=external):
                verify_aab.main()
        finally:
            os.chdir(previous)
        return output

    def test_cli_stages_the_same_signed_snapshot_after_tool_validation(self):
        bundle = self.bundle("cli-valid.aab", r8=True)
        output = self.run_cli(bundle)
        self.assertEqual((output / "GalliPick-v0.1.7-release.aab").read_bytes(), bundle.read_bytes())
        self.assertEqual(json.loads((output / "manifest.json").read_text()), METADATA)
        self.assertEqual(json.loads((output / "provenance.json").read_text())["signer_certificate_sha256"], self.pin)

    def test_cli_tool_failure_and_wrong_manifest_never_stage_a_bundle(self):
        bundle = self.bundle("cli-reject.aab", r8=True)
        for arguments in [{"validation_fails": True}, {"manifest": MANIFEST.replace("com.hebi.gallipick", "wrong.package")}]:
            with self.subTest(arguments=arguments), self.assertRaises(ReleaseError) as failure:
                self.run_cli(bundle, **arguments)
            self.assertNotIn("private tool diagnostic", str(failure.exception))

    def test_cli_rejects_even_correctly_signed_bundle_when_r8_metadata_is_missing(self):
        bundle = self.bundle("cli-no-r8.aab", r8=True, metadata=False)
        with self.assertRaisesRegex(ReleaseError, "R8 release gate"):
            self.run_cli(bundle)

    def test_cli_requires_production_project_evidence_from_the_signed_snapshot(self):
        bundle = self.bundle("cli-cloud.aab", r8=True)
        with self.assertRaises(ReleaseError):
            self.run_cli(bundle, cloud_environment="production", cloud_resources=firebase_resource("gallipick-dev"))
        output = self.run_cli(bundle, cloud_environment="production",
                              cloud_resources=firebase_resource("gallipick-production"))
        provenance = json.loads((output / "provenance.json").read_text())
        self.assertEqual(provenance["cloud_environment"], "production")
        self.assertEqual(provenance["firebase_project_id"], "gallipick-production")


if __name__ == "__main__":
    unittest.main()
