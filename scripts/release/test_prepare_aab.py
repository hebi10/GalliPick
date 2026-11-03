import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from signing_bundle import ReleaseError

try:
    import prepare_aab
except ModuleNotFoundError:
    prepare_aab = None


SOURCE = 'versionCode = 8\nversionName = "0.1.7"\n'
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="com.hebi.gallipick" android:versionCode="8" android:versionName="0.1.7">
 <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/>
 <application android:debuggable="false"/>
</manifest>'''
TOOL_BYTES = b"public bundletool boundary fixture"


class PrepareAabTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(prepare_aab, "Unsigned AAB preparation is not implemented")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.project = Path(temporary.name)
        (self.project / "app").mkdir()
        (self.project / "app/build.gradle.kts").write_text(SOURCE)
        self.bundle = self.project / "input.aab"
        self.tool = self.project / "downloaded-tool.jar"
        self.tool.write_bytes(TOOL_BYTES)
        self.output = self.project / "output"
        self.write_bundle()

    def write_bundle(self, extra=None):
        with zipfile.ZipFile(self.bundle, "w") as archive:
            archive.writestr("base/manifest/AndroidManifest.xml", b"fixture protobuf manifest")
            archive.writestr("base/dex/classes.dex", b"unsigned fixture payload")
            # A JAR manifest alone is not a signature.
            archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
            for name, content in (extra or {}).items():
                archive.writestr(name, content)

    def run_preparation(self, *, manifest=MANIFEST, tag="v0.1.7", tool_succeeds=True, pin_fixture_tool=True):
        def external(command, **kwargs):
            self.assertIs(kwargs["shell"], False)
            if command == ["git", "rev-parse", "HEAD"]:
                return subprocess.CompletedProcess(command, 0, "a" * 40 + "\n", "")
            self.assertEqual(command[:2], ["java", "-jar"])
            snapshot_tool = Path(command[2])
            self.assertNotEqual(snapshot_tool, self.tool)
            self.assertEqual(snapshot_tool.read_bytes(), TOOL_BYTES)
            operation = command[3:]
            snapshot = Path(operation[1].split("=", 1)[1]) if operation[0] == "validate" else Path(operation[2].split("=", 1)[1])
            self.assertNotEqual(snapshot, self.bundle)
            self.assertEqual(snapshot.read_bytes(), self.bundle.read_bytes())
            if operation == ["validate", "--bundle=" + str(snapshot)]:
                return subprocess.CompletedProcess(command, 0 if tool_succeeds else 1, "private diagnostic", "")
            if operation == ["dump", "manifest", "--bundle=" + str(snapshot), "--module=base"]:
                return subprocess.CompletedProcess(command, 0, manifest, "")
            self.fail("Unexpected bundletool invocation")

        previous = Path.cwd()
        output = io.StringIO()
        try:
            os.chdir(self.project)
            with contextlib.ExitStack() as stack:
                # The actual official JAR is an external boundary. Its expected digest is
                # replaced only for successful fixture runs; the wrong-hash test uses the pin.
                if pin_fixture_tool:
                    stack.enter_context(patch("prepare_aab.BUNDLETOOL_SHA256", hashlib.sha256(TOOL_BYTES).hexdigest()))
                stack.enter_context(patch("verify_aab.subprocess.run", side_effect=external))
                stack.enter_context(patch.object(sys, "argv", ["prepare_aab.py", "--bundle", str(self.bundle),
                    "--bundletool", str(self.tool), "--tag", tag, "--output", str(self.output)]))
                stack.enter_context(contextlib.redirect_stdout(output))
                prepare_aab.main()
        finally:
            os.chdir(previous)
        return output.getvalue()

    def test_unsigned_preparation_preserves_both_binaries_and_explicit_public_status(self):
        original = self.bundle.read_bytes()
        message = self.run_preparation()
        self.assertEqual({path.name for path in self.output.iterdir()}, {
            "GalliPick-v0.1.7-unsigned.aab", "bundletool.jar", "manifest.json", "provenance.json", "SHA256SUMS"})
        self.assertEqual((self.output / "GalliPick-v0.1.7-unsigned.aab").read_bytes(), original)
        self.assertEqual((self.output / "bundletool.jar").read_bytes(), TOOL_BYTES)
        self.assertEqual((self.output / "SHA256SUMS").read_text(),
            hashlib.sha256(original).hexdigest() + "  GalliPick-v0.1.7-unsigned.aab\n" +
            hashlib.sha256(TOOL_BYTES).hexdigest() + "  bundletool.jar\n")
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual((manifest["package"], manifest["version_code"], manifest["version_name"]),
                         ("com.hebi.gallipick", 8, "0.1.7"))
        provenance = json.loads((self.output / "provenance.json").read_text())
        self.assertEqual(provenance["status"], "unsigned_preparation")
        self.assertIs(provenance["signed"], False)
        self.assertEqual(provenance["source_commit"], "a" * 40)
        self.assertEqual(provenance["bundle_sha256"], hashlib.sha256(original).hexdigest())
        self.assertEqual(provenance["bundletool_sha256"], hashlib.sha256(TOOL_BYTES).hexdigest())
        self.assertNotIn(str(self.project), json.dumps(provenance))
        self.assertIn("unsigned", message.lower())
        self.assertNotIn("upload", message.lower())

    def test_existing_signature_metadata_is_rejected_even_with_mixed_case(self):
        for name in ["META-INF/UPLOAD.SF", "META-INF/UPLOAD.RSA", "META-INF/UPLOAD.DSA", "META-INF/UPLOAD.EC",
                     "meta-inf/upload.sf", "META-INF/SIG-custom"]:
            with self.subTest(name=name):
                self.write_bundle({name: b"signature fixture"})
                with self.assertRaises(ReleaseError):
                    self.run_preparation()
                self.assertFalse(self.output.exists())

    def test_wrong_package_version_sdk_and_debuggable_are_rejected(self):
        for old, new in [("com.hebi.gallipick", "wrong.package"), ('versionCode="8"', 'versionCode="7"'),
                         ('versionName="0.1.7"', 'versionName="0.1.6"'), ('minSdkVersion="30"', 'minSdkVersion="29"'),
                         ('targetSdkVersion="36"', 'targetSdkVersion="35"'), ('debuggable="false"', 'debuggable="true"')]:
            with self.subTest(value=new), self.assertRaises(ReleaseError):
                self.run_preparation(manifest=MANIFEST.replace(old, new))
            self.assertFalse(self.output.exists())
        with self.assertRaises(ReleaseError):
            self.run_preparation(tag="v0.1.6")
        self.assertFalse(self.output.exists())

    def test_wrong_official_tool_hash_is_rejected(self):
        with self.assertRaises(ReleaseError):
            self.run_preparation(pin_fixture_tool=False)
        self.assertFalse(self.output.exists())

    def test_private_material_guards_are_retained(self):
        for name, content in [("base/assets/signing.properties", b"fixture-only configuration"),
                              ("base/assets/upload.jks", b"fixture-only key"),
                              ("base/assets/notes.txt", b"-----BEGIN PRIVATE KEY-----")]:
            with self.subTest(name=name):
                self.write_bundle({name: content})
                with self.assertRaises(ReleaseError):
                    self.run_preparation()
                self.assertFalse(self.output.exists())

    def test_existing_output_is_not_reused_or_overwritten(self):
        self.output.mkdir()
        with self.assertRaises(ReleaseError):
            self.run_preparation()
        (self.output / "existing.txt").write_text("preserve")
        with self.assertRaises(ReleaseError):
            self.run_preparation()
        self.assertEqual((self.output / "existing.txt").read_text(), "preserve")
        self.assertEqual({path.name for path in self.output.iterdir()}, {"existing.txt"})

    def test_tool_failure_is_sanitized_and_leaves_no_preparation(self):
        with self.assertRaises(ReleaseError) as failure:
            self.run_preparation(tool_succeeds=False)
        self.assertNotIn("private diagnostic", str(failure.exception))
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
