"""Synthetic archive and score-contract tests, not measurements of GalliPick."""

from copy import deepcopy
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
import warnings
import zipfile

from gp_dex_metrics import (
    R8_METADATA, MAPPING, APP_DEX_THRESHOLD_BYTES, assess_threshold,
    compare_builds, inspect_bundle, reliable_stats_version,
)


def fake_dex(size=112):
    return b"dex\n039\x00" + bytes(size - 8)


def metadata(*dex_files):
    return {
        "version": "8.13.19",
        "options": {
            "isObfuscationEnabled": True,
            "isOptimizationsEnabled": True,
            "isShrinkingEnabled": True,
        },
        "stats": {
            "noObfuscationPercentage": 75,
            "noOptimizationPercentage": 15.25,
            "noShrinkingPercentage": 20,
        },
        "dexFiles": [{"checksum": hashlib.sha256(dex).hexdigest()} for dex in dex_files],
    }


class DexMetricsTest(TestCase):
    def bundle(self, directory, files, data=None, mapping=None):
        path = Path(directory) / "test.aab"
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, value in files.items():
                archive.writestr(name, value)
            if data is not None:
                archive.writestr(R8_METADATA, data if isinstance(data, str) else json.dumps(data))
            if mapping is not None:
                archive.writestr(MAPPING, mapping)
        return path

    def test_reads_exact_embedded_scores_and_uncompressed_module_dex(self):
        with TemporaryDirectory() as directory:
            first, second = fake_dex(1000), fake_dex(2000)
            bundle = self.bundle(directory, {"base/dex/classes.dex": first,
                                             "feature/dex/classes2.dex": second}, metadata(first, second),
                                 "original.A -> a.b:\n    int field -> x\noriginal.B -> original.B:\n")
            report = inspect_bundle(bundle)
            self.assertEqual(report["dex_uncompressed_bytes"], 3000)
            self.assertEqual(report["module_dex_uncompressed_bytes"], 3000)
            self.assertEqual(report["r8_metadata"]["status"], "verified")
            self.assertTrue(report["r8_metadata"]["dex_checksums_verified"])
            self.assertEqual(report["r8_metadata"]["scores_percent"],
                             {"obfuscation": 25.0, "optimization": 84.75, "shrinking": 80.0})
            self.assertEqual(report["mapping_class_proxy"]["renamed_class_rows_percent"], 50.0)
            self.assertEqual(report["bundle_sha256"], hashlib.sha256(bundle.read_bytes()).hexdigest())
            self.assertEqual(report["play_console_status"], "unverified")

    def test_exact_threshold_and_each_independent_score(self):
        dex = fake_dex(APP_DEX_THRESHOLD_BYTES)
        with TemporaryDirectory() as directory:
            data = metadata(dex)
            path = self.bundle(directory, {"base/dex/classes.dex": dex}, data)
            result = inspect_bundle(path)
            self.assertEqual(result["local_threshold_assessment"], "meets_local_metadata_threshold")
            for key in ("noObfuscationPercentage", "noOptimizationPercentage", "noShrinkingPercentage"):
                altered = deepcopy(data)
                altered["stats"][key] = 75.0001
                path = self.bundle(directory, {"base/dex/classes.dex": dex}, altered)
                with self.subTest(key=key):
                    self.assertEqual(inspect_bundle(path)["local_threshold_assessment"],
                                     "below_local_metadata_threshold")

    def test_disabled_compiler_option_cannot_pass(self):
        dex = fake_dex()
        with TemporaryDirectory() as directory:
            data = metadata(dex)
            data["options"]["isObfuscationEnabled"] = False
            report = inspect_bundle(self.bundle(directory, {"base/dex/classes.dex": dex}, data))
            self.assertEqual(assess_threshold(APP_DEX_THRESHOLD_BYTES, 0, report["r8_metadata"]),
                             "below_local_metadata_threshold")

    def test_missing_metadata_is_unknown_not_zero_even_with_mapping(self):
        with TemporaryDirectory() as directory:
            path = self.bundle(directory, {"base/dex/classes.dex": fake_dex()},
                               mapping="original.A -> a:\n")
            report = inspect_bundle(path)
            self.assertEqual(report["r8_metadata"]["status"], "missing")
            self.assertIsNone(report["r8_metadata"]["scores_percent"])
            self.assertEqual(report["mapping_class_proxy"]["renamed_class_rows_percent"], 100)
            self.assertEqual(assess_threshold(APP_DEX_THRESHOLD_BYTES, 0, report["r8_metadata"]),
                             "unverified_metadata")

    def test_missing_statistics_is_not_a_pass(self):
        with TemporaryDirectory() as directory:
            dex = fake_dex()
            data = metadata(dex)
            del data["stats"]["noShrinkingPercentage"]
            report = inspect_bundle(self.bundle(directory, {"base/dex/classes.dex": dex}, data))
            self.assertEqual(report["r8_metadata"]["status"], "incomplete")
            self.assertTrue(report["r8_metadata"]["dex_checksums_verified"])
            self.assertIsNone(report["r8_metadata"]["scores_percent"])

    def test_checksum_mismatch_or_missing_or_extra_dex_is_rejected(self):
        first, second = fake_dex(112), fake_dex(120)
        with TemporaryDirectory() as directory:
            for data in (metadata(second), metadata(first, second), metadata()):
                report = inspect_bundle(self.bundle(directory, {"base/dex/classes.dex": first}, data))
                with self.subTest(data=data):
                    self.assertNotEqual(report["r8_metadata"]["status"], "verified")
                    self.assertIsNone(report["r8_metadata"]["scores_percent"])

    def test_untrusted_stats_types_ranges_and_duplicate_fields(self):
        with TemporaryDirectory() as directory:
            dex = fake_dex()
            for value in (True, "25", -1, 100.1, float("nan"), float("inf")):
                data = metadata(dex)
                data["stats"]["noObfuscationPercentage"] = value
                report = inspect_bundle(self.bundle(directory, {"base/dex/classes.dex": dex}, data))
                with self.subTest(value=value):
                    self.assertEqual(report["r8_metadata"]["status"], "invalid")
            raw = json.dumps(metadata(dex)).replace('"version":', '"version":"8.13.19","version":', 1)
            report = inspect_bundle(self.bundle(directory, {"base/dex/classes.dex": dex}, raw))
            self.assertEqual(report["r8_metadata"]["status"], "invalid")

    def test_known_bad_compiler_stats_versions_are_not_used(self):
        for version in ("8.9.10", "8.10.32", "8.11.22", "8.12.18", "8.13.3", "9.0.0", "main"):
            with self.subTest(version=version):
                self.assertFalse(reliable_stats_version(version))
        for version in ("8.10.33", "8.13.19", "9.0.1", "9.3.16"):
            self.assertTrue(reliable_stats_version(version))
        with TemporaryDirectory() as directory:
            dex = fake_dex()
            data = metadata(dex)
            data["version"] = "8.13.3"
            report = inspect_bundle(self.bundle(directory, {"base/dex/classes.dex": dex}, data))
            self.assertEqual(report["r8_metadata"]["status"], "unverified_version")

    def test_non_module_dex_is_counted_but_not_claimed_as_r8_covered(self):
        with TemporaryDirectory() as directory:
            dex = fake_dex()
            path = self.bundle(directory, {"base/dex/classes.dex": dex,
                                          "base/assets/payload.dex": b"asset"}, metadata(dex))
            report = inspect_bundle(path)
            self.assertEqual(report["dex_uncompressed_bytes"], len(dex) + 5)
            self.assertEqual(report["other_dex_uncompressed_bytes"], 5)
            self.assertEqual(assess_threshold(APP_DEX_THRESHOLD_BYTES, 5, report["r8_metadata"]),
                             "unverified_non_module_dex")

    def test_duplicate_entries_invalid_dex_and_missing_dex_fail(self):
        with TemporaryDirectory() as directory:
            path = self.bundle(directory, {"base/dex/classes.dex": fake_dex()})
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(path, "a") as archive:
                    archive.writestr("base/dex/classes.dex", fake_dex())
            with self.assertRaisesRegex(ValueError, "Duplicate entries"):
                inspect_bundle(path)
            path = self.bundle(directory, {"base/dex/classes.dex": b"not a dex"})
            with self.assertRaisesRegex(ValueError, "Invalid packaged DEX"):
                inspect_bundle(path)
            path = self.bundle(directory, {"base/assets/other.dex": fake_dex()})
            with self.assertRaisesRegex(ValueError, "No packaged module DEX"):
                inspect_bundle(path)

    def test_metadata_reported_dex_size_must_match_if_present(self):
        with TemporaryDirectory() as directory:
            dex = fake_dex()
            data = metadata(dex)
            data["dexFiles"][0]["sizeInBytes"] = len(dex) + 1
            report = inspect_bundle(self.bundle(directory, {"base/dex/classes.dex": dex}, data))
            self.assertEqual(report["r8_metadata"]["status"], "invalid")

    def test_build_reduction_is_separate_and_compares_only_same_profile(self):
        def measurement(profile, r8, size, dex, methods):
            archive = {"bytes": size, "dex_uncompressed_bytes": dex, "dex_method_ids": methods}
            return {"profile": profile, "r8": r8, "apk": archive, "aab": archive}
        result = compare_builds([
            measurement("localOnly", False, 100, 80, 30),
            measurement("localOnly", True, 80, 20, 15),
            measurement("devCloud", False, 1000, 800, 100),
        ])
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["aab_bytes_reduction_percent"], 20.0)
        self.assertEqual(result[0]["aab_dex_uncompressed_bytes_reduction_percent"], 75.0)
        self.assertEqual(result[0]["aab_dex_method_ids_reduction_percent"], 50.0)
        self.assertNotIn("obfuscation", result[0])
