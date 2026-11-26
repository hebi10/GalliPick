from copy import deepcopy
from unittest import TestCase

from gp_r8_regression import (
    PHOTO_A, PHOTO_B, PHOTO_C, VIDEO, DUPLICATE_WORKER, OCR_WORKER,
    verify_baseline, verify_upgrade, verify_final,
)


def baseline():
    expected = {PHOTO_A: "same", PHOTO_B: "same", VIDEO: "video"}
    return {
        "schema_version": 8,
        "media": {name: "uri:" + name for name in expected},
        "protected": ["uri:" + PHOTO_A],
        "hashes": expected.copy(),
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
        after["hashes"] = expected.copy()
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
