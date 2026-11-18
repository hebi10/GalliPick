import unittest

from gp_perf00 import (
    build_database, case, indexed, legacy, measure, percent, refresh_full_replace, run
)


class PerformanceProbeContractTest(unittest.TestCase):
    def test_index_path_and_identical_survivor(self):
        report = measure(120, 3)
        self.assertTrue(report["proof_parity"])
        self.assertEqual(4, report["legacy"]["sql_statements"])
        self.assertEqual(10, report["indexed"]["sql_statements"])
        self.assertTrue(any("INDEX" in part for part in report["sqlite_index_plan"]))

    def test_changed_fingerprint_and_epoch_fail_closed(self):
        db = build_database(120)
        try:
            selected, survivors = case(120)
            for changed in [(selected[0][0], "wrong", selected[0][2], selected[0][3]),
                            (selected[0][0], selected[0][1], "wrong-epoch", selected[0][3]),
                            (selected[0][0], selected[0][1], selected[0][2], "ffffffffffffffff")]:
                invalid = [changed] + selected[1:]
                self.assertIsNone(legacy(db, invalid, survivors)[0])
                self.assertIsNone(indexed(db, invalid, survivors)[0])
        finally:
            db.close()

    def test_unchanged_refresh_rollback_keeps_catalog_and_hashes(self):
        db = build_database(120)
        try:
            refresh_full_replace(db, 120)
            self.assertEqual(120, db.execute("SELECT COUNT(*) FROM media_cache").fetchone()[0])
            self.assertEqual(120, db.execute(
                "SELECT COUNT(*) FROM photo_perceptual_hash").fetchone()[0])
        finally:
            db.close()

    def test_bounded_configuration_and_percentiles(self):
        self.assertEqual(2.0, percent([4, 1, 2, 3], 1/3))
        with self.assertRaises(ValueError):
            run([10], 2)


if __name__ == "__main__":
    unittest.main()
