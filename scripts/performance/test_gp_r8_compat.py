from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import Mock, patch

from gp_r8_compat import ENTRY_POINTS, WORKERS, WORKER_CONSTRUCTOR, verify_class, verify_optimized_apk


def smali(name, constructor):
    result = ".class public final L" + name.replace(".", "/") + ";\n.super Ljava/lang/Object;\n"
    if constructor:
        result += f"\n.method public constructor {constructor}\n    .registers 3\n    return-void\n.end method\n"
    return result


class R8CompatibilityTest(TestCase):
    def test_original_worker_name_and_exact_constructor_are_present(self):
        verify_class(smali(WORKERS[0], WORKER_CONSTRUCTOR), WORKERS[0], WORKER_CONSTRUCTOR)

    def test_renamed_class_or_reference_without_definition_is_rejected(self):
        for output in (smali("a.b", WORKER_CONSTRUCTOR), f"const-class v0, L{WORKERS[0].replace('.', '/')};"):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "original class definition"):
                verify_class(output, WORKERS[0], WORKER_CONSTRUCTOR)

    def test_missing_wrong_or_nonpublic_constructor_is_rejected(self):
        for output in (
            smali(WORKERS[0], None),
            smali(WORKERS[0], "<init>()V"),
            smali(WORKERS[0], WORKER_CONSTRUCTOR).replace(".method public", ".method private"),
            smali(WORKERS[0], WORKER_CONSTRUCTOR).replace(".method public", ".method public static"),
        ):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "public reflective constructor"):
                verify_class(output, WORKERS[0], WORKER_CONSTRUCTOR)

    def test_class_without_required_constructor_can_be_abstract(self):
        verify_class(".class public abstract Lcom/hebi/gallipick/data/MediaDatabase;\n", "com.hebi.gallipick.data.MediaDatabase", None)

    def test_mapping_and_actual_apk_entry_points_are_required(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "sample.apk"
            apk.write_bytes(b"sample apk")
            with self.assertRaisesRegex(ValueError, "nonempty R8 mapping"):
                verify_optimized_apk(apk, root)
            (root / "mapping.txt").write_text("sample mapping", encoding="utf-8")

            def analyzer(args, **kwargs):
                self.assertEqual(args[:4], ["apkanalyzer", "dex", "code", "--class"])
                self.assertEqual(args[5:], [str(apk)])
                self.assertTrue(kwargs["check"])
                return Mock(stdout=smali(args[4], ENTRY_POINTS[args[4]]))

            with patch("gp_r8_compat.subprocess.run", side_effect=analyzer) as command:
                report = verify_optimized_apk(apk, root)
            self.assertEqual(command.call_count, len(ENTRY_POINTS))
            self.assertEqual([entry["class"] for entry in report["verified_entry_points"]], list(ENTRY_POINTS))
            self.assertEqual(len(report["apk_sha256"]), 64)
            self.assertEqual(len(report["mapping_sha256"]), 64)

    def test_analyzer_failure_cannot_produce_success(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "mapping.txt").write_text("mapping", encoding="utf-8")
            with patch("gp_r8_compat.subprocess.run", side_effect=subprocess.CalledProcessError(1, "apkanalyzer")):
                with self.assertRaises(subprocess.CalledProcessError):
                    verify_optimized_apk(root / "sample.apk", root)
