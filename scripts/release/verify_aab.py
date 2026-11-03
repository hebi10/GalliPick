"""Validate a signed Play upload bundle and stage public release evidence only."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from signing_bundle import ReleaseError
from verify_apk import TAG_PATTERN, parse_source_version


ANDROID = "{http://schemas.android.com/apk/res/android}"
JAVA_VERIFIER = Path(__file__).with_name("VerifyAabSignature.java")
PRIVATE_MARKER = re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----")


def parse_manifest(xml):
    try:
        if re.search(r"<!\s*(DOCTYPE|ENTITY)\b", xml, re.IGNORECASE):
            raise ValueError()
        manifest = ET.fromstring(xml)
        applications = manifest.findall("application")
        sdks = manifest.findall("uses-sdk")
        if manifest.tag != "manifest" or len(applications) != 1 or len(sdks) != 1:
            raise ValueError()
        application, sdk = applications[0], sdks[0]
        debuggable = application.get(ANDROID + "debuggable", "false")
        if debuggable not in ("true", "false"):
            raise ValueError()
        return {
            "package": manifest.attrib["package"],
            "version_code": int(manifest.attrib[ANDROID + "versionCode"]),
            "version_name": manifest.attrib[ANDROID + "versionName"],
            "min_sdk": int(sdk.attrib[ANDROID + "minSdkVersion"]),
            "target_sdk": int(sdk.attrib[ANDROID + "targetSdkVersion"]),
            "debuggable": debuggable == "true",
        }
    except (ET.ParseError, KeyError, ValueError):
        raise ReleaseError("The base-module AAB manifest is incomplete or invalid.") from None


def validate_metadata(tag, source, metadata):
    version, code = parse_source_version(source)
    if not re.fullmatch(TAG_PATTERN, tag) or tag != "v" + version:
        raise ReleaseError("The AAB release tag does not match the source version.")
    expected = {"package": "com.hebi.gallipick", "version_code": code, "version_name": version,
                "min_sdk": 30, "target_sdk": 36, "debuggable": False}
    if metadata != expected:
        raise ReleaseError("The AAB package, version, SDK levels or release flags do not match the source.")
    return version, code


def verify_archive(bundle):
    try:
        with zipfile.ZipFile(bundle) as archive:
            names = set()
            for entry in archive.infolist():
                name = entry.filename
                lowered = name.lower()
                if (lowered in names or "\\" in name or name.startswith("/")
                        or ".." in PurePosixPath(name).parts or stat.S_ISLNK(entry.external_attr >> 16)):
                    raise ReleaseError("The AAB contains duplicate or unsafe ZIP entries.")
                names.add(lowered)
                if (lowered.endswith((".p12", ".pfx", ".jks", ".keystore", ".key", ".pem", "signing.properties"))
                        or "signing-recovery" in lowered):
                    raise ReleaseError("The AAB contains private signing material.")
                if entry.is_dir():
                    continue
                with archive.open(entry) as content:
                    tail = b""
                    while chunk := content.read(65536):
                        data = tail + chunk
                        if PRIVATE_MARKER.search(data):
                            raise ReleaseError("The AAB contains private signing material.")
                        tail = data[-128:]
    except ReleaseError:
        raise
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile):
        raise ReleaseError("The AAB archive could not be verified.") from None


def command_output(command, purpose):
    try:
        result = subprocess.run(command, text=True, capture_output=True, shell=False, timeout=180)
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise ReleaseError(f"{purpose} failed; no AAB was staged.") from None
    if result.returncode:
        raise ReleaseError(f"{purpose} failed; no AAB was staged.")
    return result.stdout


def verify_signature(bundle, pin):
    if not re.fullmatch(r"[0-9a-f]{64}", pin):
        raise ReleaseError("The public signing certificate pin is invalid.")
    raw = command_output(["java", str(JAVA_VERIFIER), str(bundle), pin], "AAB payload signature verification")
    try:
        signature = json.loads(raw)
        if (set(signature) != {"certificate_sha256", "verified_entries"}
                or signature["certificate_sha256"] != pin or type(signature["verified_entries"]) is not int
                or signature["verified_entries"] < 1):
            raise ValueError()
    except (TypeError, ValueError):
        raise ReleaseError("AAB payload signature verification returned invalid evidence.") from None
    return signature


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as content:
        while chunk := content.read(65536):
            digest.update(chunk)
    return digest.hexdigest()


def stage_release(bundle, destination, tag, metadata, signature, source_commit, bundletool_sha256):
    if not re.fullmatch(TAG_PATTERN, tag) or not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        raise ReleaseError("The AAB provenance identity is invalid.")
    if destination.exists() and (not destination.is_dir() or any(destination.iterdir())):
        raise ReleaseError("The AAB output directory must be empty.")
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / f"GalliPick-{tag}-release.aab"
    shutil.copyfile(bundle, output)
    digest = sha256(output)
    (destination / "SHA256SUMS").write_text(f"{digest}  {output.name}\n", encoding="ascii")
    (destination / "manifest.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    provenance = {
        "source_commit": source_commit, "tag": tag, "bundle_file": output.name,
        "bundle_sha256": digest, "bundle_bytes": output.stat().st_size,
        "signer_certificate_sha256": signature["certificate_sha256"],
        "verified_payload_entries": signature["verified_entries"],
        "bundletool_sha256": bundletool_sha256,
        "validation": ["bundletool validate", "base manifest matches source", "all payload JAR signatures match pinned signer", "private signing material absent"],
    }
    (destination / "provenance.json").write_text(json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--bundletool", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.bundle.is_file() or not args.bundletool.is_file():
        raise ReleaseError("A built AAB and verified bundletool JAR are required.")
    source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    pin = Path("signing/certificate.sha256").read_text(encoding="ascii").strip().lower()
    commit = command_output(["git", "rev-parse", "HEAD"], "Source commit verification").strip()
    # Validate a private snapshot, then stage those same bytes rather than a mutable input path.
    with tempfile.TemporaryDirectory(prefix="gallipick-aab-verify-") as temporary:
        snapshot = Path(temporary) / "bundle.aab"
        shutil.copyfile(args.bundle, snapshot)
        verify_archive(snapshot)
        signature = verify_signature(snapshot, pin)
        tool = ["java", "-jar", str(args.bundletool.resolve())]
        command_output(tool + ["validate", "--bundle=" + str(snapshot)], "Bundletool validation")
        xml = command_output(tool + ["dump", "manifest", "--bundle=" + str(snapshot), "--module=base"], "Bundletool manifest inspection")
        metadata = parse_manifest(xml)
        validate_metadata(args.tag, source, metadata)
        output = stage_release(snapshot, args.output, args.tag, metadata, signature, commit, sha256(args.bundletool))
    print(f"Verified Play upload bundle staged: {output.name} with SHA256SUMS and public provenance.")


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        raise SystemExit(str(error))
    except (OSError, UnicodeError):
        raise SystemExit("AAB verification files could not be read or staged.") from None
