import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from signing_bundle import ReleaseError


TAG_PATTERN = r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"


def parse_source_version(source):
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", source, re.MULTILINE)
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', source, re.MULTILINE)
    if len(codes) != 1 or len(names) != 1 or int(codes[0]) < 7:
        raise ReleaseError("The app must declare one release version and a versionCode of at least 7.")
    return names[0], int(codes[0])


def validate_source_tag(tag, source):
    if not re.fullmatch(TAG_PATTERN, tag):
        raise ReleaseError("The release tag must use vMAJOR.MINOR.PATCH.")
    version, code = parse_source_version(source)
    if tag != "v" + version:
        raise ReleaseError("The release tag does not match the app version.")
    return version, code


def parse_apk_metadata(badging):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    label = re.search(r"^application-label:'([^']+)'", badging, re.MULTILINE)
    minimum = re.search(r"^minSdkVersion:'(\d+)'", badging, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.MULTILINE)
    if not all((package, label, minimum, target)):
        raise ReleaseError("The APK metadata is incomplete.")
    return {"package": package[1], "version_code": int(package[2]), "version_name": package[3], "label": label[1], "min_sdk": int(minimum[1]), "target_sdk": int(target[1]), "debuggable": "application-debuggable" in badging}


def validate_release_metadata(tag, source, metadata, certificate_digests, pin):
    version, code = validate_source_tag(tag, source)
    if metadata != {"package": "com.hebi.gallipick", "version_code": code, "version_name": version, "label": "GalliPick", "min_sdk": 30, "target_sdk": 36, "debuggable": False}:
        raise ReleaseError("The APK package, version, label or release flags do not match the tested source.")
    if not re.fullmatch(r"[0-9a-f]{64}", pin) or certificate_digests != [pin]:
        raise ReleaseError("The APK does not have the single pinned GalliPick signing certificate.")
    return version


def write_release_files(apk, destination, tag):
    if not re.fullmatch(TAG_PATTERN, tag):
        raise ReleaseError("The release tag must use vMAJOR.MINOR.PATCH.")
    destination.mkdir(parents=True, exist_ok=True)
    if list(destination.iterdir()):
        raise ReleaseError("The release output directory must be empty.")
    output = destination / f"GalliPick-{tag}-release.apk"
    shutil.copyfile(apk, output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (destination / "SHA256SUMS").write_text(f"{digest}  {output.name}\n", encoding="ascii")
    return output


def command_output(command):
    run = subprocess.run(command, text=True, capture_output=True)
    if run.returncode:
        raise ReleaseError("An Android APK verification command failed; no release was staged.")
    return run.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["source", "apk"])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    version, _ = validate_source_tag(args.tag, source)
    if args.action == "source":
        print(f"Release source version verified: {version}.")
        return
    if not args.apk or not args.apk.is_file() or not args.output:
        raise ReleaseError("A built APK and empty output directory are required.")
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise ReleaseError("An Android SDK is required for APK verification.")
    tools = Path(sdk) / "build-tools/35.0.0"
    badging = command_output([str(tools / "aapt2"), "dump", "badging", str(args.apk)])
    signatures = command_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)])
    certificates = [value.lower().replace(":", "") for value in re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$", signatures, re.MULTILINE)]
    pin = Path("signing/certificate.sha256").read_text(encoding="ascii").strip().lower()
    validate_release_metadata(args.tag, source, parse_apk_metadata(badging), certificates, pin)
    with zipfile.ZipFile(args.apk) as archive:
        if any(name.lower().endswith((".p12", ".jks", ".keystore", "signing.properties")) or "signing-recovery" in name.lower() for name in archive.namelist()):
            raise ReleaseError("The APK contains private signing material.")
    output = write_release_files(args.apk, args.output, args.tag)
    print(f"Verified release staged: {output.name} with SHA256SUMS.")


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        raise SystemExit(str(error))
