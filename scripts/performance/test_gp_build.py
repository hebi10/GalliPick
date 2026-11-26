import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from gp_build import archive_metrics, dex_method_ids


def fake_dex(method_ids):
    header_bytes = 0x70
    size = header_bytes + 8 * method_ids
    data = bytearray(size)
    data[:8] = b"dex\n039\x00"
    struct.pack_into("<III", data, 0x20, size, header_bytes, 0x12345678)
    struct.pack_into("<II", data, 0x58, method_ids, header_bytes if method_ids else 0)
    return data


class BuildMetricsTest(unittest.TestCase):
    def test_apk_includes_each_root_dex_and_ignores_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", fake_dex(20))
                archive.writestr("classes2.dex", fake_dex(5))
                archive.writestr("assets/classes3.dex", b"ignored")
            metrics = archive_metrics(path)
            self.assertEqual(metrics["bytes"], path.stat().st_size)
            self.assertEqual(metrics["dex_method_ids_by_file"], {"classes.dex": 20, "classes2.dex": 5})
            self.assertEqual(metrics["dex_files"], 2)
            self.assertEqual(metrics["dex_uncompressed_bytes"], len(fake_dex(20)) + len(fake_dex(5)))
            self.assertEqual(metrics["dex_uncompressed_bytes_by_file"], {"classes.dex": len(fake_dex(20)), "classes2.dex": len(fake_dex(5))})
            self.assertEqual(metrics["dex_method_ids"], 25)

    def test_bundle_includes_dex_from_each_module(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.aab"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("base/dex/classes.dex", fake_dex(12))
                archive.writestr("feature/dex/classes10.dex", fake_dex(9))
                archive.writestr("base/assets/dex/classes.dex", b"ignored")
            self.assertEqual(archive_metrics(path)["dex_method_ids"], 21)

    def test_fails_when_package_dex_is_missing_or_duplicated(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("assets/data.txt", b"example")
            with self.assertRaisesRegex(ValueError, "No packaged DEX"):
                archive_metrics(path)
            with zipfile.ZipFile(path, "a") as archive, warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr("classes.dex", fake_dex(1))
                archive.writestr("classes.dex", fake_dex(2))
            with self.assertRaisesRegex(ValueError, "Duplicate DEX"):
                archive_metrics(path)

    def test_fails_on_invalid_header_or_identifier_table(self):
        dex = fake_dex(5)
        self.assertEqual(dex_method_ids(dex[:0x70], len(dex)), 5)
        with self.assertRaisesRegex(ValueError, "Unsupported DEX header"):
            dex_method_ids(dex[:0x40], len(dex))
        with self.assertRaisesRegex(ValueError, "Invalid DEX dimensions"):
            dex_method_ids(dex[:0x70], len(dex) + 1)
        struct.pack_into("<I", dex, 0x58, 6)
        with self.assertRaisesRegex(ValueError, "Invalid DEX method identifier table"):
            dex_method_ids(dex[:0x70], len(dex))
