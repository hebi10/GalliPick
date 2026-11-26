import copy
import hashlib
from pathlib import Path
import tempfile
import unittest

from signing_bundle import ReleaseError
from r8_release_gate import require_optimized_bundle, verify_optimization_evidence
from r8_test_fixture import write_fixture


class R8ReleaseGateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.bundle = Path(temporary.name) / "fixture.aab"

    def test_applicable_bundle_exact_25_percent_is_accepted(self):
        write_fixture(self.bundle, size=10_000_000, disabled_percent=75)
        result = require_optimized_bundle(self.bundle)
        self.assertEqual(result["r8_metadata"]["scores_percent"]["obfuscation"], 25)
        self.assertEqual(result["local_threshold_assessment"], "meets_local_metadata_threshold")

    def test_below_25_percent_for_applicable_bundle_is_blocked(self):
        write_fixture(self.bundle, size=10_000_000, disabled_percent=75.0001)
        with self.assertRaisesRegex(ReleaseError, "25%"):
            require_optimized_bundle(self.bundle)

    def test_small_bundle_still_requires_valid_enabled_compiler_and_mapping(self):
        write_fixture(self.bundle, disabled_percent=99)
        result = require_optimized_bundle(self.bundle)
        self.assertEqual(result["local_threshold_assessment"], "below_local_size_threshold")
        for options in ({"metadata": False}, {"mapping": False}, {"bad_checksum": True},
                        {"version": "8.13.3"}, {"extra_dex": True},
                        {"flags": {"isObfuscationEnabled": False,
                                   "isOptimizationsEnabled": True, "isShrinkingEnabled": True}}):
            with self.subTest(options=options):
                write_fixture(self.bundle, **options)
                with self.assertRaises(ReleaseError):
                    require_optimized_bundle(self.bundle)

    def test_unreadable_bundle_fails_closed(self):
        self.bundle.write_bytes(b"not a ZIP")
        with self.assertRaisesRegex(ReleaseError, "could not inspect"):
            require_optimized_bundle(self.bundle)

    def test_upload_checks_exact_immutable_bytes_and_matching_original_evidence(self):
        write_fixture(self.bundle)
        report = require_optimized_bundle(self.bundle)
        payload = self.bundle.read_bytes()
        evidence = {"bundle_sha256": hashlib.sha256(payload).hexdigest(), "r8_optimization": report}
        self.assertEqual(verify_optimization_evidence(payload, evidence), report)
        # After the file is replaced, the original immutable upload payload still matches.
        write_fixture(self.bundle, disabled_percent=70)
        self.assertEqual(verify_optimization_evidence(payload, evidence), report)
        with self.assertRaisesRegex(ReleaseError, "do not match"):
            verify_optimization_evidence(self.bundle, evidence)

    def test_tampered_or_missing_provenance_is_blocked(self):
        write_fixture(self.bundle)
        report = require_optimized_bundle(self.bundle)
        evidence = {"bundle_sha256": report["bundle_sha256"], "r8_optimization": report}
        for key, value in (("bundle_sha256", "0" * 64), ("r8_optimization", None)):
            with self.subTest(key=key), self.assertRaisesRegex(ReleaseError, "do not match"):
                verify_optimization_evidence(self.bundle, {**evidence, key: value})
        modified = copy.deepcopy(evidence)
        modified["r8_optimization"]["mapping_class_proxy"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ReleaseError, "do not match"):
            verify_optimization_evidence(self.bundle, modified)
