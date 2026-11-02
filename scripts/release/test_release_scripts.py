import base64
import hashlib
import io
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from publish_release import decode_api_response, require_release_absent, validate_release_files, validate_remote_assets, validate_tag_target
from signing_bundle import ReleaseError, cleanup_signing_directory, restore_bundle
from verify_apk import parse_apk_metadata, parse_source_version, validate_release_metadata, write_release_files


PIN = "d84aa7f6f6e402c4cf2722894f518042a223f143666c4bbf847f4c46ad3423f9"
SOURCE = 'versionCode = 7\nversionName = "0.1.6"\n'
BADGING = """package: name='com.hebi.gallipick' versionCode='7' versionName='0.1.6' platformBuildVersionName='16'
minSdkVersion:'30'
targetSdkVersion:'36'
application-label:'GalliPick'
"""


def encoded_bundle(overrides=None, extra=None, *, key_name="gallipick-signing.p12", omit=(), entries=()):
    contents = {
        key_name: b"fake key fixture only",
        "signing.properties": b"storeFile=gallipick-signing.p12\nstoreType=PKCS12\nkeyAlias=gallipick\nstorePassword=fixture-value\nkeyPassword=fixture-value\n",
        "certificate.sha256": (PIN + "\n").encode(),
        "RESTORE.txt": b"fixture",
    }
    contents.update(overrides or {})
    contents.update(extra or {})
    for name in omit:
        contents.pop(name, None)
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as archive:
        for name, value in list(contents.items()) + list(entries):
            archive.writestr(name, value)
    return base64.b64encode(payload.getvalue()).decode()


class SigningBundleTests(unittest.TestCase):
    def test_jks_restore_preserves_separate_passwords_and_private_permissions(self):
        properties = b"storeFile=gallipick-signing.jks\nstoreType=JKS\nkeyAlias=fixture-alias\nstorePassword=fixture-store-only\nkeyPassword=fixture-key-only\n"
        with tempfile.TemporaryDirectory() as root:
            try:
                directory = restore_bundle(encoded_bundle({"signing.properties": properties}, key_name="gallipick-signing.jks"), Path(root), PIN)
            except ReleaseError as error:
                self.fail(f"A valid JKS recovery bundle was rejected: {error}")
            self.assertEqual(set(p.name for p in directory.iterdir()), {"gallipick-signing.jks", "signing.properties", "certificate.sha256", "RESTORE.txt"})
            self.assertEqual((directory / "signing.properties").read_bytes(), properties)
            self.assertEqual((directory / "gallipick-signing.jks").read_bytes(), b"fake key fixture only")
            self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
            self.assertTrue(all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in directory.iterdir()))
            cleanup_signing_directory(directory, Path(root))
            self.assertFalse(directory.exists())

    def test_legacy_pkcs12_bundle_can_omit_store_type(self):
        properties = b"storeFile=gallipick-signing.p12\nkeyAlias=gallipick\nstorePassword=fixture-store\nkeyPassword=fixture-key\n"
        with tempfile.TemporaryDirectory() as root:
            directory = restore_bundle(encoded_bundle({"signing.properties": properties}), Path(root), PIN)
            self.assertEqual((directory / "signing.properties").read_bytes(), properties)

    def test_bundle_must_contain_exactly_one_supported_keystore(self):
        candidates = [
            encoded_bundle(omit=("gallipick-signing.p12",)),
            encoded_bundle(extra={"gallipick-signing.jks": b"second fixture"}),
            encoded_bundle(key_name="gallipick-signing.keystore"),
        ]
        for candidate in candidates:
            with self.subTest(candidate_length=len(candidate)), tempfile.TemporaryDirectory() as root:
                with self.assertRaises(ReleaseError):
                    restore_bundle(candidate, Path(root), PIN)
                self.assertEqual(list(Path(root).iterdir()), [])

    def test_duplicate_keystore_archive_entry_is_rejected_before_write(self):
        for name in ("gallipick-signing.p12", "gallipick-signing.jks"):
            with self.subTest(name=name):
                with self.assertWarns(UserWarning):
                    candidate = encoded_bundle(key_name=name, entries=[(name, b"duplicate fixture")])
                with tempfile.TemporaryDirectory() as root:
                    with self.assertRaises(ReleaseError):
                        restore_bundle(candidate, Path(root), PIN)
                    self.assertEqual(list(Path(root).iterdir()), [])

    def test_keystore_store_type_must_match_the_bundled_format(self):
        for name, store_type in [
            ("gallipick-signing.p12", "JKS"),
            ("gallipick-signing.jks", "PKCS12"),
            ("gallipick-signing.jks", "JCEKS"),
            ("gallipick-signing.jks", None),
        ]:
            with self.subTest(name=name, store_type=store_type), tempfile.TemporaryDirectory() as root:
                properties = f"storeFile={name}\nkeyAlias=fixture\nstorePassword=fixture-store\nkeyPassword=fixture-key\n"
                if store_type is not None:
                    properties += f"storeType={store_type}\n"
                with self.assertRaises(ReleaseError):
                    restore_bundle(encoded_bundle({"signing.properties": properties.encode()}, key_name=name), Path(root), PIN)
                self.assertEqual(list(Path(root).iterdir()), [])

    def test_jks_properties_cannot_reference_external_or_other_keystore(self):
        for store_file in ("/outside/gallipick-signing.jks", "../gallipick-signing.jks", "keys/gallipick-signing.jks", "gallipick-signing.p12"):
            with self.subTest(store_file=store_file), tempfile.TemporaryDirectory() as root:
                properties = f"storeFile={store_file}\nstoreType=JKS\nkeyAlias=fixture\nstorePassword=fixture-store\nkeyPassword=fixture-key\n".encode()
                with self.assertRaises(ReleaseError):
                    restore_bundle(encoded_bundle({"signing.properties": properties}, key_name="gallipick-signing.jks"), Path(root), PIN)
                self.assertEqual(list(Path(root).iterdir()), [])

    def test_missing_common_member_is_rejected_even_with_supported_keystore(self):
        for missing in ("signing.properties", "certificate.sha256", "RESTORE.txt"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as root:
                with self.assertRaises(ReleaseError):
                    restore_bundle(encoded_bundle(omit=(missing,)), Path(root), PIN)
                self.assertEqual(list(Path(root).iterdir()), [])

    def test_restore_accepts_only_expected_files_and_uses_private_permissions(self):
        with tempfile.TemporaryDirectory() as root:
            directory = restore_bundle(encoded_bundle(), Path(root), PIN)
            self.assertEqual(directory.parent, Path(root))
            self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
            self.assertEqual(set(p.name for p in directory.iterdir()), {"gallipick-signing.p12", "signing.properties", "certificate.sha256", "RESTORE.txt"})
            self.assertTrue(all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in directory.iterdir()))
            cleanup_signing_directory(directory, Path(root))
            self.assertFalse(directory.exists())

    def test_corrupt_base64_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ReleaseError):
                restore_bundle("not a bundle", Path(root), PIN)
            self.assertEqual(list(Path(root).iterdir()), [])

    def test_unexpected_or_traversal_member_is_rejected_before_write(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ReleaseError):
                restore_bundle(encoded_bundle(extra={"../private.txt": b"unsafe"}), Path(root), PIN)
            self.assertEqual(list(Path(root).iterdir()), [])

    def test_different_public_pin_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ReleaseError):
                restore_bundle(encoded_bundle({"certificate.sha256": b"0" * 64}), Path(root), PIN)
            self.assertEqual(list(Path(root).iterdir()), [])

    def test_properties_cannot_reference_external_keystore(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ReleaseError):
                restore_bundle(encoded_bundle({"signing.properties": b"storeFile=/outside/key.p12\nstorePassword=fixture\nkeyAlias=gallipick\nkeyPassword=fixture\n"}), Path(root), PIN)

    def test_oversized_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ReleaseError):
                restore_bundle(encoded_bundle({"RESTORE.txt": b"x" * 1_048_577}), Path(root), PIN)

    def test_cleanup_refuses_directory_outside_owned_runner_temp(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as outside:
            with self.assertRaises(ReleaseError):
                cleanup_signing_directory(Path(outside), Path(root))
            self.assertTrue(Path(outside).exists())


class ReleaseMetadataTests(unittest.TestCase):
    def test_matching_release_metadata_is_accepted(self):
        metadata = parse_apk_metadata(BADGING)
        self.assertEqual(validate_release_metadata("v0.1.6", SOURCE, metadata, [PIN], PIN), "0.1.6")

    def test_tag_version_package_code_label_and_debuggable_must_match(self):
        metadata = parse_apk_metadata(BADGING)
        invalid = [
            ("v0.1.7", metadata),
            ("v0.1.6", dict(metadata, package="com.hebi.gallipick.preview")),
            ("v0.1.6", dict(metadata, version_code=6)),
            ("v0.1.6", dict(metadata, label="GalliPick 미리보기")),
            ("v0.1.6", dict(metadata, debuggable=True)),
            ("v0.1.6", dict(metadata, min_sdk=29)),
            ("v0.1.6", dict(metadata, target_sdk=35)),
        ]
        for tag, candidate in invalid:
            with self.subTest(tag=tag, metadata=candidate), self.assertRaises(ReleaseError):
                validate_release_metadata(tag, SOURCE, candidate, [PIN], PIN)

    def test_wrong_or_multiple_signers_are_rejected(self):
        for certificates in [["0" * 64], [PIN, PIN], []]:
            with self.subTest(certificates=certificates), self.assertRaises(ReleaseError):
                validate_release_metadata("v0.1.6", SOURCE, parse_apk_metadata(BADGING), certificates, PIN)

    def test_non_semver_tag_and_old_version_code_are_rejected(self):
        with self.assertRaises(ReleaseError):
            validate_release_metadata("v0.1.6;echo unsafe", SOURCE, parse_apk_metadata(BADGING), [PIN], PIN)
        with self.assertRaises(ReleaseError):
            parse_source_version('versionCode = 6\nversionName = "0.1.6"')

    def test_release_filename_and_checksum_match_exact_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            apk = root / "input.apk"
            apk.write_bytes(b"verified release fixture")
            output = root / "output"
            result = write_release_files(apk, output, "v0.1.6")
            self.assertEqual(result.name, "GalliPick-v0.1.6-release.apk")
            self.assertEqual(result.read_bytes(), apk.read_bytes())
            self.assertEqual((output / "SHA256SUMS").read_text(), hashlib.sha256(apk.read_bytes()).hexdigest() + "  " + result.name + "\n")


class PublishingTests(unittest.TestCase):
    def test_existing_draft_or_public_release_is_never_recreated(self):
        for draft in [False, True]:
            with self.subTest(draft=draft), patch("publish_release.github_api", return_value=[{"tag_name": "v0.1.6", "draft": draft}]) as api:
                with self.assertRaises(ReleaseError):
                    require_release_absent("hebi10/GalliPick", "v0.1.6")
                api.assert_called_once_with("repos/hebi10/GalliPick/releases?per_page=100&page=1")

    def test_release_lookup_checks_following_pages_for_drafts(self):
        full_page = [{"tag_name": f"v0.0.{number}", "draft": False} for number in range(100)]
        with patch("publish_release.github_api", side_effect=[full_page, [{"tag_name": "v0.1.6", "draft": True}]]):
            with self.assertRaises(ReleaseError):
                require_release_absent("hebi10/GalliPick", "v0.1.6")

    def test_only_missing_remote_resource_is_treated_as_absent(self):
        self.assertIsNone(decode_api_response("HTTP/2.0 404 Not Found\ncontent-type: application/json\n\n{\"message\":\"Not Found\"}", allow_missing=True))
        for code in [401, 403, 429, 500]:
            with self.subTest(code=code), self.assertRaises(ReleaseError):
                decode_api_response(f"HTTP/2.0 {code} Error\n\n{{}}", allow_missing=True)

    def test_remote_tag_must_resolve_to_tested_commit(self):
        expected = "a" * 40
        validate_tag_target({"type": "commit", "sha": expected}, expected)
        with self.assertRaises(ReleaseError):
            validate_tag_target({"type": "commit", "sha": "b" * 40}, expected)

    def test_draft_asset_names_and_sizes_must_match_exactly(self):
        expected = {"GalliPick-v0.1.6-release.apk": 20, "SHA256SUMS": 100}
        valid = {"isDraft": True, "tagName": "v0.1.6", "assets": [{"name": name, "size": size} for name, size in expected.items()]}
        validate_remote_assets(valid, "v0.1.6", expected)
        for changed in [dict(valid, isDraft=False), dict(valid, tagName="v0.1.5"), dict(valid, assets=valid["assets"] + [{"name": "extra", "size": 1}])]:
            with self.subTest(changed=changed), self.assertRaises(ReleaseError):
                validate_remote_assets(changed, "v0.1.6", expected)

    def test_local_release_rejects_modified_apk_before_any_remote_write(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            source = root / "source.apk"
            source.write_bytes(b"fixture")
            output = root / "dist"
            staged = write_release_files(source, output, "v0.1.6")
            validate_release_files(output, "v0.1.6")
            staged.write_bytes(b"changed")
            with self.assertRaises(ReleaseError):
                validate_release_files(output, "v0.1.6")


if __name__ == "__main__":
    unittest.main()
