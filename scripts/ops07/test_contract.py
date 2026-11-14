"""Offline OPS-07 automatic-event and release authorization negative fixtures."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ops07audit", ROOT / "scripts/ops07/audit.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def module(path):
    full = ROOT / path
    if not full.is_file():
        return None
    spec = importlib.util.spec_from_file_location("ops07_" + full.stem, full)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class NegativeScenarios(unittest.TestCase):
    def test_auto_publisher_is_detected(self):
        for event in ("push","workflow_run","issue_comment"):
            with self.subTest(event=event):
                example = ("on:\n  "+event+":\n    types: [completed]\n"
                           "jobs:\n  publish:\n    if: needs.gate.outputs.ready == 'true'\n"
                           "    runs-on: ubuntu-latest\n    steps:\n      - run: python3 publisher.py deploy\n")
                self.assertTrue(audit.automatic_mutations("play-release.yml", example))

    def test_manual_or_disabled_publisher_is_allowed(self):
        example = ("on:\n  workflow_run:\n    types: [completed]\n  workflow_dispatch:\n"
                   "jobs:\n  publish:\n    if: github.event_name == 'workflow_dispatch' && inputs.approved == true\n"
                   "    runs-on: ubuntu-latest\n    steps:\n      - run: python3 publisher.py deploy\n"
                   "  release:\n    if: >-\n      false && github.event_name == 'workflow_run'\n"
                   "    runs-on: ubuntu-latest\n    steps:\n      - run: gh release create v1\n")
        self.assertEqual(audit.automatic_mutations("play-release.yml", example), [])

    def test_manual_or_auto_condition_does_not_count_as_approval(self):
        example = ("on:\n  workflow_run:\n    types: [completed]\n  workflow_dispatch:\n"
                   "jobs:\n  upload:\n    if: >-\n"
                   "      github.event_name == 'workflow_dispatch' ||\n"
                   "      github.event_name == 'workflow_run'\n"
                   "    runs-on: ubuntu-latest\n    steps:\n      - run: python3 listing.py --apply\n")
        self.assertTrue(audit.automatic_mutations("store-listing.yml", example))


class AppContracts(unittest.TestCase):
    def test_all_release_stages_and_no_automatic_publishers(self):
        problems = audit.findings()
        self.assertEqual(problems, [], "\n".join(problems))

    def test_mismatched_source_and_fake_store_approval(self):
        mod = module("scripts/ops06/store_gate.py")
        self.assertIsNotNone(mod, "OPS-06 store guard is not present")
        env = {"GITHUB_REPOSITORY": audit.REPOSITORY, "GITHUB_EVENT_NAME": "workflow_dispatch",
               "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": "a"*40,
               "OPS06_EXPECTED_MAIN_SHA": "a"*40, "OPS06_STORE_APPROVED": "true",
               "OPS06_CONFIRMATION": mod.PHRASE,
               "OPS06_BATCH_ID": "2026-11-13-"+mod.APP_SLUG}
        for key, value in (("GITHUB_EVENT_NAME", "workflow_run"),
                           ("GITHUB_REF", "refs/heads/feature"),
                           ("OPS06_STORE_APPROVED", "false"),
                           ("OPS06_CONFIRMATION", "WRONG"),
                           ("OPS06_EXPECTED_MAIN_SHA", "b"*40)):
            with self.subTest(key=key):
                with patch.object(mod, "command_output") as network:
                    with self.assertRaises(mod.StoreApprovalError):
                        mod.verify({**env,key:value})
                    network.assert_not_called()

    def test_unapproved_closed_track_wrong_bundle_and_draft(self):
        mod = module("scripts/ops05/promotion.py")
        self.assertIsNotNone(mod, "OPS-05 closed promotion source missing")
        env = {"GITHUB_EVENT_NAME":"workflow_dispatch","GITHUB_REF":"refs/heads/main",
               "GITHUB_REPOSITORY":audit.REPOSITORY,"EXPECTED_REPO":audit.REPOSITORY,
               "GITHUB_SHA":"a"*40,"EXPECTED_MAIN_SHA":"a"*40,
               "BATCH_ID":"2026-11-13-ops07","APP_PACKAGE":audit.PACKAGE,
               "INTERNAL_TRACK":"internal","CLOSED_TRACK":"alpha",
               "VERSION_CODE":"19","AAB_SHA256":"b"*64,"MODE":"promote",
               "FINAL_BATCH_APPROVED":"true","APPROVAL_PHRASE":mod.APPROVAL,
               "HOMECREW_FINAL_APPROVAL":"HOMECREW_FINAL_RELEASE_APPROVED"}
        for key, value in (("FINAL_BATCH_APPROVED","false"),
                           ("AAB_SHA256","z"*64),
                           ("CLOSED_TRACK","production"),
                           ("CLOSED_TRACK","internal"),
                           ("EXPECTED_MAIN_SHA","c"*40)):
            with self.subTest(key=key,value=value):
                with self.assertRaises(mod.Stop):
                    mod.parse_request({**env,key:value})
        req=mod.parse_request(env)
        tracks=[{"track":"internal","releases":[
            {"status":"completed","versionCodes":["19"],"name":"1.0"}]},
            {"track":"alpha","releases":[]}]
        self.assertEqual(mod.make_plan(tracks,[{"versionCode":19,"sha256":"b"*64}],req)["state"],"ready")
        with self.assertRaises(mod.Stop):
            mod.make_plan(tracks,[{"versionCode":19,"sha256":"c"*64}],req)
        tracks[0]["releases"][0]["status"]="draft"
        with self.assertRaises(mod.Stop):
            mod.make_plan(tracks,[{"versionCode":19,"sha256":"b"*64}],req)

    def test_ops03_signed_build_request_cannot_forge_approval_or_source(self):
        path = ".github/workflows/release-build.yml"
        original_read = audit.read
        original = original_read(path)
        variants = (
            ("FINAL_BATCH_APPROVED", "final_batch_approved", "'true'"),
            ("EXPECTED_MAIN_SHA", "expected_main_sha", "$" + "{{ github.sha }}"),
        )
        for key, field, forged in variants:
            with self.subTest(key=key):
                expected = key + ": " + "$" + "{{ inputs." + field + " }}"
                self.assertIn(expected, original)
                changed = original.replace(expected, key + ": " + forged, 1)
                with patch.object(audit, "read", side_effect=lambda target:
                                  changed if target == path else original_read(target)):
                    blockers = audit.findings()
                self.assertTrue(any("OPS03_BINDING: GalliPick " + key in item
                                    for item in blockers), blockers)

    def test_ops03_signed_build_gate_runs_and_required_ci_is_not_skipped(self):
        path = ".github/workflows/release-build.yml"
        original_read = audit.read
        original = original_read(path)
        gate = "      - name: Gate release batch, main SHA and required CI\n"
        dispatch = "      - name: Dispatch existing signed AAB build-only mode\n"
        required = """REQUIRED_CI_JSON: '["Android verification","Android device tests"]'"""
        for value in (gate, dispatch, required):
            self.assertIn(value, original)
        variants = (
            ("removed", original.replace(gate, "", 1)),
            ("skipped", original.replace(gate, gate + "        if: false\n", 1)),
            ("ignored_failure", original.replace(gate, gate + "        continue-on-error: true\n", 1)),
            ("dispatch_on_failure", original.replace(dispatch, dispatch + "        if: always()\n", 1)),
            ("empty_ci", original.replace(required, "REQUIRED_CI_JSON: '[]'", 1)),
            ("no_ci_success", original.replace(
                'if latest.get("status")!="completed" or latest.get("conclusion")!="success": refuse("Required CI not successful: "+name)',
                "if False: refuse('unreachable')", 1)),
        )
        for label, altered in variants:
            with self.subTest(label=label):
                with patch.object(audit, "read", side_effect=lambda target:
                                  altered if target == path else original_read(target)):
                    blockers = audit.findings()
                self.assertTrue(any(item.startswith(("OPS03_PREFLIGHT:", "OPS03_REQUIRED_CI:",
                                                       "OPS03_DISPATCH_BYPASS:")) for item in blockers), blockers)

    def test_ops03_gate_is_not_ignored_overridden_or_bypassed_by_new_job(self):
        path = ".github/workflows/release-build.yml"
        original_read = audit.read
        original = original_read(path)
        token = "          GH_TOKEN: " + "$" + "{{ github.token }}"
        self.assertIn(token, original)
        self.assertIn("python3 - <<'PY'", original)
        variants = (
            ("step_ci_override", original.replace(
                token, token + "\n          REQUIRED_CI_JSON: '[]'", 1)),
            ("masked_preflight", original.replace(
                "python3 - <<'PY'", "python3 - <<'PY' || true", 1)),
            ("independent_dispatch", original + "\n  bypass:\n"
             "    runs-on: ubuntu-latest\n"
             "    permissions:\n      actions: write\n"
             "    steps:\n"
             "      - run: gh api --method POST repos/x/actions/workflows/play-bundle.yml/dispatches\n"),
        )
        for label, altered in variants:
            with self.subTest(label=label):
                with patch.object(audit, "read", side_effect=lambda target:
                                  altered if target == path else original_read(target)):
                    issues = audit.findings()
                self.assertTrue(any(item.startswith(("OPS03_REQUIRED_CI:", "OPS03_PREFLIGHT:",
                                                       "OPS03_SCOPE:")) for item in issues), issues)

    def test_internal_only_approval_cannot_target_closed_track(self):
        path = ".github/workflows/play-internal-release.yml"
        original_read = audit.read
        original = original_read(path)
        self.assertIn("play_publish.py check --track internal", original)
        self.assertIn("play_publish.py deploy --track internal", original)
        variants = (
            ("check", original.replace("play_publish.py check --track internal",
                                       "play_publish.py check --track alpha", 1)),
            ("deploy", original.replace("play_publish.py deploy --track internal",
                                        "play_publish.py deploy --track alpha", 1)),
        )
        for operation, altered in variants:
            with self.subTest(operation=operation):
                with patch.object(audit, "read", side_effect=lambda target:
                                  altered if target == path else original_read(target)):
                    issues = audit.findings()
                self.assertTrue(any("INTERNAL_TRACK: GalliPick " + operation in item
                                    for item in issues), issues)

    def test_internal_track_rejects_duplicate_later_override(self):
        path = ".github/workflows/play-internal-release.yml"
        original_read = audit.read
        original = original_read(path)
        check = "play_publish.py check --track internal"
        deploy = "play_publish.py deploy --track internal"
        for operation, needle in (("check", check), ("deploy", deploy)):
            with self.subTest(operation=operation):
                self.assertIn(needle, original)
                changed = original.replace(needle, needle + " --track alpha", 1)
                with patch.object(audit, "read", side_effect=lambda target:
                                  changed if target == path else original_read(target)):
                    issues = audit.findings()
                self.assertTrue(any("INTERNAL_TRACK: GalliPick " + operation in item
                                    for item in issues), issues)

    def test_closed_promotion_inputs_cannot_be_forged(self):
        source_path = ".github/workflows/play-closed-release.yml"
        original_read = audit.read
        original = original_read(source_path)
        variants = (
            ("FINAL_BATCH_APPROVED", "final_batch_approved", "'true'"),
            ("APPROVAL_PHRASE", "approval_phrase", "PROMOTE_VERIFIED_INTERNAL_VERSION"),
            ("EXPECTED_MAIN_SHA", "expected_main_sha", "$" + "{{ github.sha }}"),
            ("CLOSED_TRACK", "closed_track", "alpha"),
            ("AAB_SHA256", "aab_sha256", "'" + "0" * 64 + "'"),
        )
        for key, field, forged in variants:
            with self.subTest(key=key):
                expected = key + ": " + "$" + "{{ inputs." + field + " }}"
                self.assertIn(expected, original)
                changed = original.replace(expected, key + ": " + forged, 1)
                with patch.object(audit, "read", side_effect=lambda path:
                                  changed if path == source_path else original_read(path)):
                    blockers = audit.findings()
                self.assertTrue(any("CLOSED_BINDING: GalliPick " + key in item
                                    for item in blockers), blockers)

    def test_missing_existing_aab_cannot_be_published(self):
        mod=module("scripts/ops04/internal.py")
        self.assertIsNotNone(mod, "OPS-04 immutable AAB guard missing")
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(mod,"BUNDLE_PATH",Path(tmp)):
                with self.assertRaises(mod.GateError):
                    mod.verify()


if __name__ == "__main__":
    unittest.main()
