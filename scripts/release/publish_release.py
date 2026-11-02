import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from signing_bundle import ReleaseError
from verify_apk import TAG_PATTERN


def decode_api_response(output, allow_missing=False):
    normalized = output.replace("\r\n", "\n")
    status = re.match(r"HTTP/\S+\s+(\d+)", normalized)
    if status and int(status[1]) == 404 and allow_missing:
        return None
    if not status or int(status[1]) not in (200, 201):
        raise ReleaseError("GitHub API verification failed; no existing resource will be overwritten.")
    try:
        return json.loads(normalized.split("\n\n", 1)[1])
    except (IndexError, ValueError):
        raise ReleaseError("GitHub returned an invalid verification response.") from None


def github_api(path, method="GET", fields=None, allow_missing=False):
    command = ["gh", "api", "--include", "--method", method, path]
    for key, value in (fields or {}).items():
        command.extend(["-f", f"{key}={value}"])
    run = subprocess.run(command, text=True, capture_output=True)
    return decode_api_response(run.stdout, allow_missing)


def run_gh(arguments):
    run = subprocess.run(["gh", *arguments], text=True, capture_output=True)
    if run.returncode:
        raise ReleaseError("A GitHub release operation failed. Inspect the draft and workflow before retrying; no overwrite was attempted.")
    return run.stdout


def require_release_absent(repository, tag):
    for page in range(1, 101):
        releases = github_api(f"repos/{repository}/releases?per_page=100&page={page}")
        if not isinstance(releases, list):
            raise ReleaseError("The existing release list could not be verified.")
        if any(release.get("tag_name") == tag for release in releases):
            raise ReleaseError("A release for this version already exists. It will not be overwritten automatically.")
        if len(releases) < 100:
            return
    raise ReleaseError("The complete existing release list could not be verified.")


def validate_tag_target(target, expected_commit):
    if target.get("type") != "commit" or target.get("sha") != expected_commit:
        raise ReleaseError("The existing release tag does not match the exact tested commit.")


def ensure_remote_tag(repository, tag, commit, create_missing):
    reference = github_api(f"repos/{repository}/git/ref/tags/{tag}", allow_missing=True)
    if reference is None:
        if not create_missing:
            raise ReleaseError("The pushed release tag is missing on GitHub.")
        reference = github_api(f"repos/{repository}/git/refs", "POST", {"ref": f"refs/tags/{tag}", "sha": commit})
    target = reference.get("object", {})
    for _ in range(5):
        if target.get("type") != "tag":
            break
        identifier = target.get("sha", "")
        if not re.fullmatch(r"[0-9a-f]{40}", identifier):
            raise ReleaseError("The annotated release tag has an invalid target.")
        target = github_api(f"repos/{repository}/git/tags/{identifier}").get("object", {})
    validate_tag_target(target, commit)


def validate_release_files(directory, tag):
    if not re.fullmatch(TAG_PATTERN, tag):
        raise ReleaseError("The release tag must use vMAJOR.MINOR.PATCH.")
    apk = directory / f"GalliPick-{tag}-release.apk"
    checksums = directory / "SHA256SUMS"
    if not directory.is_dir() or {path.name for path in directory.iterdir()} != {apk.name, checksums.name}:
        raise ReleaseError("The release directory must contain only its verified APK and SHA256SUMS.")
    if any(path.is_symlink() or not path.is_file() for path in (apk, checksums)):
        raise ReleaseError("Release assets must be regular files.")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if checksums.read_text(encoding="ascii") != f"{digest}  {apk.name}\n":
        raise ReleaseError("The release APK no longer matches its staged checksum.")
    return apk, checksums


def validate_remote_assets(release, tag, expected, draft=True):
    assets = release.get("assets", [])
    actual = {asset.get("name"): asset.get("size") for asset in assets}
    if release.get("isDraft") is not draft or release.get("tagName") != tag or len(assets) != len(expected) or actual != expected:
        raise ReleaseError("The draft release assets do not match the verified local release.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--create-tag", action="store_true")
    args = parser.parse_args()
    if args.repo != "hebi10/GalliPick" or not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        raise ReleaseError("The release repository or tested commit is invalid.")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if head != args.commit:
        raise ReleaseError("The checkout does not match the exact tested commit.")
    apk, checksums = validate_release_files(args.dist, args.tag)
    if not args.notes.is_file():
        raise ReleaseError("The release notes are missing.")
    require_release_absent(args.repo, args.tag)
    ensure_remote_tag(args.repo, args.tag, args.commit, args.create_tag)
    common = ["--repo", args.repo]
    run_gh(["release", "create", args.tag, str(apk), str(checksums), "--draft", "--verify-tag", "--target", args.commit, "--title", f"GalliPick {args.tag}", "--notes-file", str(args.notes), *common])
    draft = json.loads(run_gh(["release", "view", args.tag, "--json", "isDraft,tagName,assets", *common]))
    validate_remote_assets(draft, args.tag, {path.name: path.stat().st_size for path in (apk, checksums)})
    readback = Path(tempfile.mkdtemp(prefix="gallipick-release-readback-", dir=os.environ.get("RUNNER_TEMP")))
    try:
        run_gh(["release", "download", args.tag, "--pattern", apk.name, "--pattern", checksums.name, "--dir", str(readback), *common])
        downloaded_apk, downloaded_checksums = validate_release_files(readback, args.tag)
        if downloaded_checksums.read_bytes() != checksums.read_bytes() or hashlib.sha256(downloaded_apk.read_bytes()).digest() != hashlib.sha256(apk.read_bytes()).digest():
            raise ReleaseError("The uploaded draft APK failed readback verification. The release remains a draft.")
    finally:
        shutil.rmtree(readback)
    ensure_remote_tag(args.repo, args.tag, args.commit, False)
    run_gh(["release", "edit", args.tag, "--draft=false", "--latest", *common])
    published = json.loads(run_gh(["release", "view", args.tag, "--json", "isDraft,tagName,assets,url", *common]))
    validate_remote_assets(published, args.tag, {path.name: path.stat().st_size for path in (apk, checksums)}, draft=False)
    if published.get("url") != f"https://github.com/{args.repo}/releases/tag/{args.tag}":
        raise ReleaseError("The published release URL could not be verified.")
    print(published["url"])


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        raise SystemExit(str(error))
