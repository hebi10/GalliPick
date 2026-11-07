"""Submit verified GalliPick bundles to internal testing or an explicitly selected closed track."""
import argparse
import hashlib
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from signing_bundle import ReleaseError
from verify_apk import TAG_PATTERN


PACKAGE = "com.hebi.gallipick"
ROOT = "https://androidpublisher.googleapis.com"
EDITS = f"/androidpublisher/v3/applications/{PACKAGE}/edits"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, *args, **kwargs):
        return None


class ApiError(ReleaseError):
    def __init__(self, status=None):
        self.status = status
        super().__init__(f"Google Play API request failed (HTTP {status})." if status else
                         "Google Play API response could not be confirmed.")


class PlayClient:
    def __init__(self, token, transport=None):
        if not isinstance(token, str) or not token or any(character.isspace() for character in token):
            raise ReleaseError("PLAY_ACCESS_TOKEN must contain a valid nonempty access token.")
        self.token = token
        self.transport = transport or build_opener(NoRedirect()).open

    def request(self, method, path, data=None):
        if not (path == EDITS or path.startswith(EDITS + "/") or path.startswith("/upload" + EDITS + "/")):
            raise ReleaseError("The API request is outside the authorized application.")
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/octet-stream" if isinstance(data, bytes) else "application/json"
            if not isinstance(data, bytes):
                data = json.dumps(data).encode("utf-8")
        try:
            with self.transport(Request(ROOT + path, data=data, headers=headers, method=method), timeout=180) as response:
                raw = response.read()
            result = json.loads(raw) if raw else {}
            if not isinstance(result, dict):
                raise ValueError()
            return result
        except HTTPError as error:
            raise ApiError(error.code) from None
        except (URLError, OSError, HTTPException, ValueError, UnicodeError):
            raise ApiError() from None

    def create_edit(self):
        edit = self.request("POST", EDITS, {}).get("id")
        if not isinstance(edit, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", edit):
            raise ReleaseError("Google Play returned an invalid edit identifier.")
        return EDITS + "/" + edit

    def delete_edit(self, edit):
        try:
            self.request("DELETE", edit)
            return True
        except ReleaseError:
            return False



    def upload_bundle_resumable(self, edit, payload, report=None):
        """Single-session bundle upload. Never starts a second session after an uncertain result."""
        if not isinstance(edit, str) or not edit.startswith(EDITS + "/") or not isinstance(payload, bytes) or not payload:
            raise ReleaseError("A valid Play edit and nonempty verified bundle are required.")
        upload_path = "/resumable/upload" + edit + "/bundles?uploadType=resumable"
        if report is not None:
            report.update(upload_protocol="resumable", upload_stage="session_start")
        initial = Request(
            ROOT + upload_path, data=b"", method="POST",
            headers={
                "Authorization": "Bearer " + self.token,
                "Accept": "application/json",
                "Content-Type": "application/json; charset=UTF-8",
                "X-Upload-Content-Type": "application/octet-stream",
                "X-Upload-Content-Length": str(len(payload)),
            },
        )
        try:
            with self.transport(initial, timeout=180) as response:
                location = response.headers.get("Location") if response.headers else None
        except HTTPError as error:
            raise ApiError(error.code) from None
        except (URLError, OSError, HTTPException, ValueError, UnicodeError):
            raise ApiError() from None
        if not isinstance(location, str):
            raise ReleaseError("Google Play did not return a resumable upload session.")
        parsed = urlsplit(location)
        allowed_path = (
            "/resumable/upload" + edit + "/bundles",
            "/upload" + edit + "/bundles",
        )
        if (parsed.scheme != "https" or parsed.hostname not in {"androidpublisher.googleapis.com", "www.googleapis.com"}
                or parsed.username is not None or parsed.password is not None or parsed.port is not None
                or parsed.fragment or parsed.path not in allowed_path):
            raise ReleaseError("Google Play returned an untrusted resumable upload destination.")
        if report is not None:
            report["upload_stage"] = "media_transfer"
        upload = Request(
            location, data=payload, method="PUT",
            headers={
                "Authorization": "Bearer " + self.token,
                "Accept": "application/json",
                "Content-Type": "application/octet-stream",
                "Content-Length": str(len(payload)),
            },
        )
        try:
            with self.transport(upload, timeout=180) as response:
                raw = response.read()
            result = json.loads(raw) if raw else {}
            if not isinstance(result, dict):
                raise ValueError()
        except HTTPError as error:
            if report is not None:
                report["upload_result"] = "unknown"
            raise ApiError(error.code) from None
        except (URLError, OSError, HTTPException, ValueError, UnicodeError):
            if report is not None:
                report["upload_result"] = "unknown"
            raise ApiError() from None
        if report is not None:
            report["upload_stage"] = "media_transferred"
        return result

def version_code(value):
    if not (type(value) is int or isinstance(value, str) and re.fullmatch(r"[1-9][0-9]*", value)):
        raise ReleaseError("A Play version code is missing or invalid.")
    code = int(value)
    if not 1 <= code <= 2100000000:
        raise ReleaseError("A Play version code is outside the Android range.")
    return code


def objects(response, key):
    values = response.get(key, [])
    if not isinstance(values, list) or any(not isinstance(item, dict) for item in values):
        raise ReleaseError("Google Play returned an invalid resource list.")
    return values


def load_inputs(bundle, manifest, notes):
    try:
        provenance = manifest.with_name("provenance.json")
        files = (bundle, manifest, notes, provenance)
        if (any(path.is_symlink() or not path.is_file() for path in files)
                or bundle.parent.resolve() != manifest.parent.resolve()):
            raise ValueError()
        metadata = json.loads(manifest.read_text(encoding="utf-8"))
        evidence = json.loads(provenance.read_text(encoding="utf-8"))
        payload = bundle.read_bytes()
        digest = hashlib.sha256(payload).hexdigest()
        if (not isinstance(metadata, dict) or set(metadata) != {
                "package", "version_code", "version_name", "min_sdk", "target_sdk", "debuggable"}
                or metadata["package"] != PACKAGE or type(metadata["version_code"]) is not int
                or not isinstance(metadata["version_name"], str)
                or not re.fullmatch(TAG_PATTERN, "v" + metadata["version_name"])
                or type(metadata["min_sdk"]) is not int or metadata["min_sdk"] != 30
                or type(metadata["target_sdk"]) is not int or metadata["target_sdk"] != 36
                or metadata["debuggable"] is not False):
            raise ValueError()
        version_code(metadata["version_code"])
        if (not isinstance(evidence, dict) or evidence.get("bundle_file") != bundle.name
                or evidence.get("bundle_sha256") != digest or not payload
                or type(evidence.get("bundle_bytes")) is not int or evidence["bundle_bytes"] != len(payload)
                or evidence.get("tag") != "v" + metadata["version_name"]
                or not isinstance(evidence.get("source_commit"), str)
                or not re.fullmatch(r"[0-9a-f]{40}", evidence["source_commit"])
                or any(not isinstance(evidence.get(key), str) or not re.fullmatch(r"[0-9a-f]{64}", evidence[key])
                       for key in ("signer_certificate_sha256", "bundletool_sha256"))
                or type(evidence.get("verified_payload_entries")) is not int
                or evidence["verified_payload_entries"] < 1 or evidence.get("signed") is False):
            raise ValueError()
        text = notes.read_text(encoding="utf-8").strip()
        if (not text or len(text) > 500 or "<" in text or ">" in text
                or any(ord(character) < 32 and character not in "\n\t" for character in text)):
            raise ValueError()
        return metadata, evidence, payload, text
    except (OSError, ValueError, TypeError, AttributeError, UnicodeError):
        raise ReleaseError("Verified AAB, matching signed provenance, manifest and plain text notes (1–500 characters) are required.") from None


def run(client, mode, track, *, bundle=None, manifest=None, notes=None, report=None, upload_protocol="media"):
    report = report if report is not None else {}
    report.update(package=PACKAGE, mode=mode, api_accepted=False, play_approval="not_confirmed", status="started")
    if (not isinstance(track, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", track)
            or (track.casefold() in {"production", "beta", "internal"} and track != "internal")
            or mode not in {"check", "deploy"}):
        raise ReleaseError("Select internal or an existing closed track; production, open, form-factor tracks and internal case variants are forbidden.")
    report["track"] = track
    if upload_protocol not in {"media", "resumable"}:
        raise ReleaseError("Unsupported bundle upload protocol.")
    inputs = load_inputs(bundle, manifest, notes) if mode == "deploy" else None
    edit = client.create_edit()
    try:
        tracks = objects(client.request("GET", edit + "/tracks"), "tracks")
        codes, public_tracks, selected = set(), [], None
        for item in tracks:
            name = item.get("track")
            if not isinstance(name, str) or not name or any(ord(character) < 32 for character in name):
                raise ReleaseError("Google Play returned an invalid track identifier.")
            releases = objects(item, "releases")
            public_releases = []
            for release in releases:
                values = release.get("versionCodes", [])
                if not isinstance(values, list) or not isinstance(release.get("status"), str):
                    raise ReleaseError("Google Play returned invalid track releases.")
                used = [version_code(value) for value in values]
                codes.update(used)
                public_releases.append({"status": release["status"], "version_codes": used})
            public_tracks.append({"track": name, "releases": public_releases})
            if name == track:
                if selected is not None:
                    raise ReleaseError("Google Play returned duplicate track identifiers.")
                selected = item
        for resource in ("bundles", "apks"):
            for artifact in objects(client.request("GET", edit + "/" + resource), resource):
                codes.add(version_code(artifact.get("versionCode")))
        report.update(tracks=public_tracks, used_version_codes=sorted(codes))
        if selected is None:
            raise ReleaseError("The configured internal or closed track does not exist; track creation is disabled.")
        if mode == "check":
            report["status"] = "checked"
            return report
        metadata, evidence, payload, text = inputs
        code = metadata["version_code"]
        if codes and code <= max(codes):
            raise ReleaseError("The source versionCode must be greater than every existing APK, bundle and track version, including drafts/internal uploads.")
        if any(release.get("status") != "completed" for release in objects(selected, "releases")):
            raise ReleaseError("The target has a draft, halted or in-progress release; resolve it in Play Console before deployment.")
        report.update(version_code=code, version_name=metadata["version_name"], source_commit=evidence["source_commit"],
                      bundle_sha256=evidence["bundle_sha256"])
        if upload_protocol == "resumable":
            uploaded = client.upload_bundle_resumable(edit, payload, report)
        else:
            report["upload_stage"] = "media_transfer"
            uploaded = client.request("POST", "/upload" + edit + "/bundles?uploadType=media", payload)
        report["upload_stage"] = "bundle_uploaded"
        if version_code(uploaded.get("versionCode")) != code or uploaded.get("sha256") != evidence["bundle_sha256"]:
            raise ReleaseError("Google Play's uploaded bundle version or SHA256 does not match the verified artifact.")
        target = edit + "/tracks/" + quote(track, safe="")
        release = {"name": metadata["version_name"], "versionCodes": [str(code)], "status": "completed",
                   "releaseNotes": [{"language": "ko-KR", "text": text}]}
        client.request("PUT", target, {"track": track, "releases": [release]})
        client.request("POST", edit + ":validate")
        try:
            client.request("POST", edit + ":commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW")
        except ApiError as error:
            if error.status is None or error.status >= 500:
                edit = None
                report.update(status="commit_unknown", api_accepted=None)
                raise ReleaseError("Commit outcome is unknown. Check Play Console before any retry; this client will not retry the commit.") from None
            raise
        edit = None
        report.update(api_accepted=True, status="committed_unverified", track_verified=False)
        verification = client.create_edit()
        try:
            actual = client.request("GET", verification + "/tracks/" + quote(track, safe=""))
            releases = objects(actual, "releases")
            if (actual.get("track") != track or len(releases) != 1 or any(
                    releases[0].get(key) != value for key, value in release.items())):
                raise ReleaseError("Commit was accepted by the API, but fresh track verification failed. Check Play Console; approval/tester availability is not confirmed.")
            report.update(status="committed", track_verified=True)
        finally:
            report["verification_edit_deleted"] = client.delete_edit(verification)
            if not report["verification_edit_deleted"]:
                report["status"] = "committed_cleanup_failed"
                raise ReleaseError("Commit was accepted, but the verification edit could not be deleted. Check Play Console before retrying.")
        return report
    finally:
        if edit:
            report["temporary_edit_deleted"] = client.delete_edit(edit)
            if not report["temporary_edit_deleted"] and report["status"] == "checked":
                report["status"] = "cleanup_failed"
                raise ReleaseError("The configuration was read, but its temporary edit could not be deleted. Check Play Console.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("check", "deploy"))
    parser.add_argument("--track", default="internal")
    parser.add_argument("--upload-protocol", choices=("media", "resumable"), default="media")
    parser.add_argument("--report", type=Path, required=True)
    for argument in ("bundle", "manifest", "notes"):
        parser.add_argument("--" + argument, type=Path)
    args = parser.parse_args(argv)
    report = {"package": PACKAGE, "status": "failed", "api_accepted": False, "play_approval": "not_confirmed"}
    try:
        client = PlayClient(os.environ.get("PLAY_ACCESS_TOKEN", ""))
        run(client, args.mode, args.track, bundle=args.bundle, manifest=args.manifest, notes=args.notes, report=report,
            upload_protocol=args.upload_protocol)
        print("Play configuration checked." if args.mode == "check" else
              "Commit accepted by Google Play API and track verified. Play approval and tester availability are not confirmed.")
    except ReleaseError as error:
        if report["status"] == "started":
            report["status"] = "failed"
        report["message"] = str(error)
        raise
    finally:
        try:
            args.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        except (OSError, UnicodeError):
            raise ReleaseError("The public Play status report could not be written.") from None


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        raise SystemExit(str(error))
