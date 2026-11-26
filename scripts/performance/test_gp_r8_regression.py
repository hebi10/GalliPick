from copy import deepcopy
from unittest import TestCase
from unittest.mock import patch

from gp_r8_regression import (
    PHOTO_A, PHOTO_B, PHOTO_C, VIDEO, DUPLICATE_WORKER, OCR_WORKER,
    verify_baseline, verify_upgrade, verify_final, verify_original_files,
)


def baseline():
    expected = {PHOTO_A: "same", PHOTO_B: "same", VIDEO: "video"}
    return {
        "schema_version": 8,
        "media": {name: "uri:" + name for name in expected},
        "protected": ["uri:" + PHOTO_A],
        "hashes": {name: digest for name, digest in expected.items() if name != VIDEO},
        "ocr": {},
        "scan_phase": ("COMPLETE",),
        "ocr_phase": None,
        "work": [("old", DUPLICATE_WORKER, 2)],
    }, expected


class RegressionEvidenceTest(TestCase):
    def test_baseline_requires_real_hashes_and_protection(self):
        state, expected = baseline()
        verify_baseline(state, expected)
        for field, value in (("protected", []), ("hashes", {}), ("work", []), ("scan_phase", ("FAILED",))):
            altered = dict(state, **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_baseline(altered, expected)

    def test_singleton_video_is_not_a_duplicate_candidate_but_original_is_checked(self):
        state, expected = baseline()
        verify_baseline(state, expected)
        with self.assertRaisesRegex(ValueError, "singleton video"):
            verify_baseline(dict(state, hashes=expected.copy()), expected)
        remote = {name: "/fixture/" + name for name in expected}
        with patch("gp_r8_regression.adb", side_effect=["same  photo-a", "same  photo-b", "video  clip"]) as command:
            verify_original_files(remote, expected)
            self.assertEqual(command.call_count, 3)
        with patch("gp_r8_regression.adb", side_effect=["same  photo-a", "same  photo-b", "changed  clip"]):
            with self.assertRaisesRegex(ValueError, "Synthetic original was changed"):
                verify_original_files(remote, expected)

    def test_upgrade_rejects_reset_settings_or_lost_protection_cache_and_work(self):
        state, _ = baseline()
        prefs = {"file_list_view": "true"}
        verify_upgrade(state, deepcopy(state), prefs, prefs)
        for field, value in (("schema_version", 9), ("media", {}), ("protected", []), ("hashes", {}), ("work", [])):
            altered = dict(state, **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_upgrade(state, altered, prefs, prefs)
        with self.assertRaises(ValueError):
            verify_upgrade(state, state, prefs, {"file_list_view": "false"})

    def test_final_requires_new_worker_and_both_scripts_and_unchanged_bytes(self):
        before, expected = baseline()
        expected[PHOTO_C] = "same"
        after = deepcopy(before)
        after["hashes"] = {name: digest for name, digest in expected.items() if name != VIDEO}
        after["media"][PHOTO_C] = "uri:" + PHOTO_C
        after["ocr_phase"] = ("COMPLETE",)
        after["work"] += [("new", DUPLICATE_WORKER, 2), ("ocr", OCR_WORKER, 2)]
        after["ocr"] = {name: ("SUCCESS", "RECEIPT 2026 서울 여행") for name in (PHOTO_A, PHOTO_B, PHOTO_C)}
        verify_final(before, after, expected)
        for field, value in (("work", before["work"]), ("ocr", {}), ("hashes", {}), ("protected", [])):
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_final(before, dict(after, **{field: value}), expected)
        after["ocr"][PHOTO_A] = ("SUCCESS", "RECEIPT 2026")
        with self.assertRaisesRegex(ValueError, "bilingual OCR"):
            verify_final(before, after, expected)
