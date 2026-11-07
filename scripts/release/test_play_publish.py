import contextlib
import hashlib
from http.client import IncompleteRead
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit

from signing_bundle import ReleaseError

try:
    import play_publish
except ModuleNotFoundError:
    play_publish = None


TOKEN = "fixture-access-token-never-public"


class Response(io.BytesIO):
    def __init__(self, data):
        super().__init__(json.dumps(data).encode())


class PlayServer:
    def __init__(self):
        self.calls = []
        self.tracks = [
            {"track": "production", "releases": [{"status": "completed", "versionCodes": ["1"]}]},
            {"track": "alpha", "releases": [{"status": "completed", "versionCodes": ["2"]}]},
            {"track": "internal", "releases": [{"status": "draft", "versionCodes": ["3"]}]},
        ]
        self.bundles = [{"versionCode": 4}]
        self.apks = [{"versionCode": 5}]
        self.upload_result = None
        self.fail_at = None
        self.commit_timeout = True
        self.edit_count = 0
        self.pending = None

    def open(self, request, timeout):
        url = urlsplit(request.full_url)
        path = url.path
        self.calls.append((request.method, request.full_url, request.data))
        assert url.scheme == "https" and url.hostname == "androidpublisher.googleapis.com"
        assert request.get_header("Authorization") == "Bearer " + TOKEN
        assert timeout >= 120
        if self.fail_at and self.fail_at in path:
            if self.fail_at == ":commit" and self.commit_timeout:
                raise TimeoutError(TOKEN)
            raise HTTPError(request.full_url, 400, TOKEN, {}, io.BytesIO(TOKEN.encode()))
        if path.endswith("/edits"):
            self.edit_count += 1
            return Response({"id": "edit" + str(self.edit_count)})
        if request.method == "DELETE":
            return Response({})
        if path.endswith("/tracks"):
            return Response({"tracks": self.tracks})
        if path.endswith("/apks"):
            return Response({"apks": self.apks})
        if path.endswith("/bundles"):
            if request.method == "GET":
                return Response({"bundles": self.bundles})
            assert path.startswith("/upload/androidpublisher/v3/applications/com.hebi.gallipick/edits/")
            assert parse_qs(url.query) == {"uploadType": ["media"]}
            assert request.get_header("Content-type") == "application/octet-stream"
            return Response(self.upload_result or {
                "versionCode": 6, "sha256": hashlib.sha256(request.data).hexdigest()})
        if "/tracks/" in path:
            track = path.rsplit("/", 1)[1]
            if request.method == "PUT":
                self.pending = json.loads(request.data)
                assert self.pending["track"] == track
                return Response(self.pending)
            return Response(next(item for item in self.tracks if item["track"] == track))
        if path.endswith(":validate"):
            return Response({"id": "edit1"})
        if path.endswith(":commit"):
            assert request.data is None
            assert parse_qs(url.query) == {"changesInReviewBehavior": ["ERROR_IF_IN_REVIEW"]}
            self.tracks = [self.pending if item["track"] == self.pending["track"] else item for item in self.tracks]
            return Response({"id": "edit1"})
        raise AssertionError("Unexpected API request: " + request.method + " " + path)


class PlayPublishTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(play_publish, "Android Publisher client is not implemented")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.bundle = self.directory / "GalliPick-v0.1.7-release.aab"
        self.bundle.write_bytes(b"verified signed bundle bytes")
        self.manifest = self.directory / "manifest.json"
        self.metadata = {"package": "com.hebi.gallipick", "version_code": 6,
                         "version_name": "0.1.7", "min_sdk": 30, "target_sdk": 36, "debuggable": False}
        self.manifest.write_text(json.dumps(self.metadata))
        self.provenance = self.directory / "provenance.json"
        self.evidence = {"source_commit": "a" * 40, "tag": "v0.1.7", "bundle_file": self.bundle.name,
                         "bundle_sha256": hashlib.sha256(self.bundle.read_bytes()).hexdigest(),
                         "bundle_bytes": self.bundle.stat().st_size, "signer_certificate_sha256": "b" * 64,
                         "verified_payload_entries": 2, "bundletool_sha256": "c" * 64}
        self.provenance.write_text(json.dumps(self.evidence))
        self.notes = self.directory / "notes.txt"
        self.notes.write_text("사용성을 개선했습니다.\n", encoding="utf-8")
        self.server = PlayServer()
        self.client = play_publish.PlayClient(TOKEN, transport=self.server.open)
        self.report = {}

    def run_client(self, mode="deploy", track="alpha", upload_protocol="media"):
        return play_publish.run(self.client, mode, track, bundle=self.bundle, manifest=self.manifest,
                                notes=self.notes, report=self.report, upload_protocol=upload_protocol)

    def test_check_reads_every_source_and_deletes_edit_without_upload_or_commit(self):
        report = self.run_client("check")
        self.assertEqual(report["used_version_codes"], [1, 2, 3, 4, 5])
        self.assertEqual([track["track"] for track in report["tracks"]], ["production", "alpha", "internal"])
        self.assertFalse(report["api_accepted"])
        self.assertEqual(self.server.calls[-1][0], "DELETE")
        self.assertFalse(any(":commit" in url or url.startswith("https://androidpublisher.googleapis.com/upload/")
                             for _, url, _ in self.server.calls))

    def test_explicit_closed_alpha_deploy_changes_only_target_and_verifies_in_a_fresh_edit(self):
        before = json.loads(json.dumps(self.server.tracks))
        report = self.run_client(track="alpha")
        release = {"name": "0.1.7", "versionCodes": ["6"], "status": "completed",
                   "releaseNotes": [{"language": "ko-KR", "text": "사용성을 개선했습니다."}]}
        self.assertEqual(self.server.pending, {"track": "alpha", "releases": [release]})
        self.assertEqual(self.server.tracks[0], before[0])
        self.assertEqual(self.server.tracks[2], before[2])
        self.assertTrue(report["api_accepted"])
        self.assertTrue(report["track_verified"])
        self.assertEqual(report["play_approval"], "not_confirmed")
        self.assertEqual(self.server.edit_count, 2)
        self.assertIn("/edit2/tracks/alpha", self.server.calls[-2][1])
        self.assertEqual(self.server.calls[-1][0], "DELETE")
        self.assertEqual(sum(method == "PUT" for method, _, _ in self.server.calls), 1)

    def test_internal_deploy_changes_only_target_and_verifies_in_a_fresh_edit(self):
        self.server.tracks[2]["releases"][0]["status"] = "completed"
        before = json.loads(json.dumps(self.server.tracks))
        report = self.run_client(track="internal")
        release = {"name": "0.1.7", "versionCodes": ["6"], "status": "completed",
                   "releaseNotes": [{"language": "ko-KR", "text": "사용성을 개선했습니다."}]}
        self.assertEqual(self.server.pending, {"track": "internal", "releases": [release]})
        self.assertEqual(self.server.tracks[:2], before[:2])
        self.assertTrue(report["api_accepted"])
        self.assertTrue(report["track_verified"])
        self.assertEqual(report["play_approval"], "not_confirmed")
        self.assertEqual(self.server.edit_count, 2)
        self.assertEqual([(method, urlsplit(url).path) for method, url, _ in self.server.calls
                          if method == "PUT"],
                         [("PUT", "/androidpublisher/v3/applications/com.hebi.gallipick/edits/edit1/tracks/internal")])
        self.assertEqual(urlsplit(self.server.calls[-2][1]).path,
                         "/androidpublisher/v3/applications/com.hebi.gallipick/edits/edit2/tracks/internal")
        self.assertEqual(self.server.calls[-2][0], "GET")
        self.assertEqual(self.server.calls[-1][0], "DELETE")

    def test_all_used_codes_including_internal_drafts_and_unassigned_artifacts_block_upload(self):
        for source in ["internal", "bundles", "apks"]:
            with self.subTest(source=source):
                self.server = PlayServer()
                self.client = play_publish.PlayClient(TOKEN, transport=self.server.open)
                if source == "internal":
                    self.server.tracks[2]["releases"][0]["versionCodes"] = ["6"]
                else:
                    setattr(self.server, source, [{"versionCode": 7}])
                with self.assertRaises(ReleaseError):
                    self.run_client()
                self.assertFalse(any("/upload/" in url for _, url, _ in self.server.calls))
                self.assertEqual(self.server.calls[-1][0], "DELETE")

    def test_forbidden_unconfigured_and_form_factor_tracks_fail_without_mutation(self):
        for track in ["", "production", "beta", "wear:production", "wear:alpha", "wear:internal",
                      "unknown", "Beta", "Internal", "INTERNAL", "alpha/qa", " alpha", "alpha ", "alpha\n",
                      ".alpha", "a" * 101, None]:
            with self.subTest(track=track), self.assertRaises(ReleaseError):
                self.run_client(track=track)
        self.assertFalse(any(method == "PUT" or "/upload/" in url for method, url, _ in self.server.calls))

    def test_internal_case_variants_cannot_be_used_as_custom_closed_tracks(self):
        self.server.tracks.extend([{"track": "Internal", "releases": []},
                                   {"track": "INTERNAL", "releases": []}])
        for track in ["Internal", "INTERNAL"]:
            with self.subTest(track=track), self.assertRaises(ReleaseError):
                self.run_client(track=track)
        self.assertEqual(self.server.calls, [])

    def test_missing_internal_track_rejects_before_upload_and_deletes_edit(self):
        self.server.tracks = self.server.tracks[:2]
        with self.assertRaises(ReleaseError):
            self.run_client(track="internal")
        self.assertEqual(self.report.get("used_version_codes"), [1, 2, 4, 5])
        self.assertFalse(any("/upload/" in url for _, url, _ in self.server.calls))
        self.assertEqual(self.server.calls[-1][0], "DELETE")

    def test_internal_draft_rejects_before_upload_and_deletes_edit(self):
        with self.assertRaises(ReleaseError):
            self.run_client(track="internal")
        self.assertEqual(self.report.get("used_version_codes"), [1, 2, 3, 4, 5])
        self.assertFalse(any("/upload/" in url for _, url, _ in self.server.calls))
        self.assertEqual(self.server.calls[-1][0], "DELETE")

    def test_internal_deploy_checks_duplicate_codes_across_all_tracks_and_artifacts(self):
        for source in ["production", "alpha", "internal", "bundles", "apks"]:
            with self.subTest(source=source):
                self.server = PlayServer()
                self.server.tracks[2]["releases"][0]["status"] = "completed"
                self.client = play_publish.PlayClient(TOKEN, transport=self.server.open)
                self.report = {}
                if source in {"bundles", "apks"}:
                    setattr(self.server, source, [{"versionCode": 6}])
                else:
                    next(item for item in self.server.tracks if item["track"] == source)["releases"][0]["versionCodes"] = ["6"]
                with self.assertRaises(ReleaseError):
                    self.run_client(track="internal")
                self.assertIn(6, self.report.get("used_version_codes", []))
                self.assertFalse(any("/upload/" in url for _, url, _ in self.server.calls))
                self.assertEqual(self.server.calls[-1][0], "DELETE")

    def test_explicit_existing_custom_closed_track_is_allowed(self):
        self.server.tracks.append({"track": "closed-qa", "releases": []})
        self.run_client(track="closed-qa")
        self.assertEqual(self.server.pending["track"], "closed-qa")

    def test_noncompleted_target_release_is_never_overwritten(self):
        for status in ["draft", "inProgress", "halted"]:
            with self.subTest(status=status):
                self.server.tracks[1]["releases"][0]["status"] = status
                with self.assertRaises(ReleaseError):
                    self.run_client()
        self.assertFalse(any("/upload/" in url for _, url, _ in self.server.calls))

    def test_manifest_and_signed_provenance_must_match_actual_staged_bytes(self):
        invalid = [("package", "other.package"), ("version_code", True), ("version_name", 7),
                   ("min_sdk", 29), ("target_sdk", 35), ("debuggable", True)]
        for key, value in invalid:
            with self.subTest(key=key):
                self.manifest.write_text(json.dumps({**self.metadata, key: value}))
                with self.assertRaises(ReleaseError):
                    self.run_client()
        self.manifest.write_text(json.dumps(self.metadata))
        for key, value in [("bundle_sha256", "0" * 64), ("source_commit", "bad"),
                           ("bundle_file", "other.aab"), ("tag", "v0.1.6"),
                           ("bundle_bytes", 1), ("verified_payload_entries", 0),
                           ("signer_certificate_sha256", "bad")]:
            with self.subTest(key=key):
                self.provenance.write_text(json.dumps({**self.evidence, key: value}))
                with self.assertRaises(ReleaseError):
                    self.run_client()
        self.assertEqual(self.server.calls, [])

    def test_notes_must_be_nonempty_plain_text_within_500_characters(self):
        for text in [" ", "가" * 501, "<ko-KR>설명</ko-KR>", "설명\x00"]:
            with self.subTest(text=text[:20]):
                self.notes.write_text(text, encoding="utf-8")
                with self.assertRaises(ReleaseError):
                    self.run_client()
        self.assertEqual(self.server.calls, [])

    def test_provenance_identity_and_hashes_must_be_strings(self):
        for key, value in [("source_commit", int("1" * 40)), ("signer_certificate_sha256", int("1" * 64)),
                           ("bundletool_sha256", int("1" * 64))]:
            with self.subTest(key=key):
                self.server = PlayServer()
                self.client = play_publish.PlayClient(TOKEN, transport=self.server.open)
                self.provenance.write_text(json.dumps({**self.evidence, key: value}))
                with self.assertRaises(ReleaseError):
                    self.run_client()
        self.assertEqual(self.server.calls, [])

    def resumable_server(self, *, location=None, upload_error=None, session_error=None):
        original = self.server.open
        destination = (
            "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/"
            "applications/com.hebi.gallipick/edits/edit1/bundles?uploadType=resumable&upload_id=test-session"
        )
        class SessionResponse(Response):
            def __init__(self, uri):
                super().__init__({})
                self.headers = {"Location": uri}

        def transport(request, timeout):
            url = urlsplit(request.full_url)
            if request.method == "POST" and url.path.startswith("/resumable/upload/androidpublisher/"):
                self.server.calls.append((request.method, request.full_url, request.data))
                assert timeout >= 120
                assert request.get_header("Authorization") == "Bearer " + TOKEN
                assert parse_qs(url.query) == {"uploadType": ["resumable"]}
                headers = {k.lower(): v for k, v in request.header_items()}
                assert headers["x-upload-content-type"] == "application/octet-stream"
                assert headers["x-upload-content-length"] == str(self.bundle.stat().st_size)
                assert request.data == b""
                if session_error:
                    raise HTTPError(request.full_url, session_error, "masked", {}, io.BytesIO())
                return SessionResponse(location or destination)
            if request.method == "PUT" and url.path.startswith("/upload/androidpublisher/"):
                self.server.calls.append((request.method, request.full_url, request.data))
                assert timeout >= 120
                assert request.get_header("Authorization") == "Bearer " + TOKEN
                assert request.data == self.bundle.read_bytes()
                assert {k.lower(): v for k, v in request.header_items()}["content-type"] == "application/octet-stream"
                if upload_error:
                    raise HTTPError(request.full_url, upload_error, "masked", {}, io.BytesIO())
                return Response({"versionCode": self.metadata["version_code"],
                                 "sha256": hashlib.sha256(request.data).hexdigest()})
            return original(request, timeout)
        self.client = play_publish.PlayClient(TOKEN, transport=transport)

    def test_resumable_bundle_upload_uses_single_session_then_verifies_only_alpha(self):
        self.resumable_server()
        report = self.run_client(upload_protocol="resumable")
        self.assertEqual(report["status"], "committed")
        self.assertTrue(report["track_verified"])
        self.assertEqual(report["upload_stage"], "bundle_uploaded")
        calls = [(method, urlsplit(url).path) for method, url, _ in self.server.calls]
        self.assertEqual(sum(path.startswith("/resumable/upload") for _, path in calls), 1)
        self.assertEqual(sum(method == "PUT" and path.startswith("/upload/") for method, path in calls), 1)
        self.assertEqual(self.server.tracks[0]["releases"][0]["versionCodes"], ["1"])
        self.assertEqual(self.server.tracks[1]["releases"][0]["versionCodes"], ["6"])
        self.assertEqual(self.server.tracks[2]["releases"][0]["versionCodes"], ["3"])

    def test_resumable_session_rejects_untrusted_redirect_before_sending_payload(self):
        self.resumable_server(location="https://external.example/upload/androidpublisher/v3/applications/com.hebi.gallipick/edits/edit1/bundles")
        with self.assertRaisesRegex(ReleaseError, "untrusted resumable"):
            self.run_client(upload_protocol="resumable")
        self.assertEqual(self.report.get("upload_stage"), "session_start")
        self.assertTrue(self.report["temporary_edit_deleted"])
        self.assertFalse(any(method == "PUT" for method, _, _ in self.server.calls))
        self.assertFalse(any(":commit" in url for _, url, _ in self.server.calls))

    def test_resumable_failed_transfer_never_commits_or_retries(self):
        self.resumable_server(upload_error=500)
        with self.assertRaises(play_publish.ApiError):
            self.run_client(upload_protocol="resumable")
        self.assertEqual(self.report.get("upload_stage"), "media_transfer")
        self.assertEqual(self.report.get("upload_result"), "unknown")
        self.assertFalse(self.report["api_accepted"])
        self.assertTrue(self.report["temporary_edit_deleted"])
        self.assertEqual(sum(method == "PUT" for method, _, _ in self.server.calls), 1)
        self.assertFalse(any(":commit" in url for _, url, _ in self.server.calls))

    def test_resumable_session_failure_never_starts_another_session(self):
        self.resumable_server(session_error=503)
        with self.assertRaises(play_publish.ApiError):
            self.run_client(upload_protocol="resumable")
        self.assertEqual(self.report.get("upload_stage"), "session_start")
        self.assertTrue(self.report["temporary_edit_deleted"])
        self.assertEqual(sum("/resumable/upload" in url for _, url, _ in self.server.calls), 1)
        self.assertFalse(any(method == "PUT" for method, _, _ in self.server.calls))

    def test_upload_hash_or_code_mismatch_prevents_track_update(self):
        digest = self.evidence["bundle_sha256"]
        for result in [{"versionCode": 6, "sha256": "0" * 64}, {"versionCode": 7, "sha256": digest}]:
            with self.subTest(result=result):
                self.server.upload_result = result
                with self.assertRaises(ReleaseError):
                    self.run_client()
        self.assertFalse(any(method == "PUT" for method, _, _ in self.server.calls))

    def test_validation_failure_prevents_commit_and_deletes_edit(self):
        self.server.fail_at = ":validate"
        with self.assertRaises(ReleaseError) as failure:
            self.run_client()
        self.assertNotIn(TOKEN, str(failure.exception))
        self.assertFalse(any(":commit" in url for _, url, _ in self.server.calls))
        self.assertEqual(self.server.calls[-1][0], "DELETE")

    def test_ambiguous_commit_is_not_retried_and_reports_console_check(self):
        self.server.fail_at = ":commit"
        with self.assertRaises(ReleaseError) as failure:
            self.run_client()
        self.assertIn("Console", str(failure.exception))
        self.assertEqual(sum(":commit" in url for _, url, _ in self.server.calls), 1)
        self.assertEqual(self.report["status"], "commit_unknown")
        self.assertIsNone(self.report["api_accepted"])
        self.assertNotIn(TOKEN, json.dumps(self.report) + str(failure.exception))

    def test_in_review_commit_rejection_never_falls_back_or_retries(self):
        self.server.fail_at = ":commit"
        self.server.commit_timeout = False
        with self.assertRaises(ReleaseError):
            self.run_client()
        self.assertEqual(sum(":commit" in url for _, url, _ in self.server.calls), 1)
        self.assertFalse(any("changesNotSentForReview" in url for _, url, _ in self.server.calls))
        self.assertFalse(self.report["api_accepted"])
        self.assertEqual(self.server.calls[-1][0], "DELETE")

    def test_failed_fresh_readback_preserves_explicit_api_acceptance(self):
        original = self.client.transport

        def fail_readback(request, timeout):
            if "/edit2/tracks/" in request.full_url:
                raise HTTPError(request.full_url, 500, TOKEN, {}, io.BytesIO(TOKEN.encode()))
            return original(request, timeout)

        self.client.transport = fail_readback
        with self.assertRaises(ReleaseError) as failure:
            self.run_client()
        self.assertTrue(self.report["api_accepted"])
        self.assertFalse(self.report["track_verified"])
        self.assertEqual(self.report["status"], "committed_unverified")
        self.assertEqual(self.server.calls[-1][0], "DELETE")
        self.assertNotIn(TOKEN, str(failure.exception))

    def test_malformed_remote_codes_stop_before_upload(self):
        for value in [True, "", "-1", 0, 2100000001]:
            with self.subTest(value=value):
                self.server.bundles = [{"versionCode": value}]
                with self.assertRaises(ReleaseError):
                    self.run_client()
        self.assertFalse(any("/upload/" in url for _, url, _ in self.server.calls))

    def test_readback_cleanup_failure_is_reported_after_accepted_commit(self):
        original = self.client.transport

        def fail_cleanup(request, timeout):
            if request.method == "DELETE" and request.full_url.endswith("/edit2"):
                raise TimeoutError(TOKEN)
            return original(request, timeout)

        self.client.transport = fail_cleanup
        with self.assertRaises(ReleaseError) as failure:
            self.run_client()
        self.assertTrue(self.report["api_accepted"])
        self.assertTrue(self.report["track_verified"])
        self.assertFalse(self.report["verification_edit_deleted"])
        self.assertIn("Console", str(failure.exception))

    def test_check_cleanup_failure_does_not_report_success(self):
        original = self.client.transport

        def fail_cleanup(request, timeout):
            if request.method == "DELETE":
                raise TimeoutError(TOKEN)
            return original(request, timeout)

        self.client.transport = fail_cleanup
        with self.assertRaises(ReleaseError):
            self.run_client("check")
        self.assertEqual(self.report["status"], "cleanup_failed")

    def test_incomplete_commit_response_is_sanitized_and_never_retried(self):
        original = self.client.transport
        commits = []

        def partial_commit(request, timeout):
            if ":commit" in request.full_url:
                commits.append(request.full_url)
                original(request, timeout).close()
                raise IncompleteRead(TOKEN.encode())
            return original(request, timeout)

        self.client.transport = partial_commit
        with self.assertRaises(ReleaseError) as failure:
            self.run_client()
        self.assertEqual(len(commits), 1)
        self.assertEqual(self.report["status"], "commit_unknown")
        self.assertEqual(self.server.tracks[1]["releases"][0]["versionCodes"], ["6"])
        self.assertNotEqual(self.server.calls[-1][0], "DELETE")
        self.assertNotIn(TOKEN, str(failure.exception))

    def test_cli_writes_public_report_and_reads_token_only_from_environment(self):
        output = io.StringIO()
        report_path = self.directory / "play-status.json"
        with patch.dict(os.environ, {"PLAY_ACCESS_TOKEN": TOKEN}), \
                patch("play_publish.PlayClient", return_value=self.client), contextlib.redirect_stdout(output):
            play_publish.main(["check", "--track", "alpha", "--report", str(report_path)])
        self.assertNotIn(TOKEN, output.getvalue() + report_path.read_text())
        self.assertEqual(json.loads(report_path.read_text())["status"], "checked")

    def test_cli_check_defaults_to_internal_without_track_argument(self):
        output = io.StringIO()
        report_path = self.directory / "play-status.json"
        with patch.dict(os.environ, {"PLAY_ACCESS_TOKEN": TOKEN}), \
                patch("play_publish.PlayClient", return_value=self.client), contextlib.redirect_stdout(output):
            play_publish.main(["check", "--report", str(report_path)])
        report = json.loads(report_path.read_text())
        self.assertEqual(report["track"], "internal")
        self.assertEqual(report["status"], "checked")
        self.assertEqual(report["used_version_codes"], [1, 2, 3, 4, 5])
        self.assertFalse(any(method == "PUT" or "/upload/" in url or ":commit" in url
                             for method, url, _ in self.server.calls))
        self.assertEqual(self.server.calls[-1][0], "DELETE")
        self.assertNotIn(TOKEN, output.getvalue() + report_path.read_text())

    def test_missing_token_creates_failure_report_before_any_network_request(self):
        report_path = self.directory / "play-status.json"
        with patch.dict(os.environ, {"PLAY_ACCESS_TOKEN": ""}), self.assertRaises(ReleaseError):
            play_publish.main(["check", "--track", "alpha", "--report", str(report_path)])
        self.assertEqual(json.loads(report_path.read_text())["status"], "failed")
        self.assertEqual(self.server.calls, [])


if __name__ == "__main__":
    unittest.main()
