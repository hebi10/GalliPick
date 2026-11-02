import argparse
import base64
import io
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import zipfile


class ReleaseError(RuntimeError):
    pass


COMMON_MEMBERS = {"signing.properties", "certificate.sha256", "RESTORE.txt"}
KEYSTORE_TYPES = {"gallipick-signing.p12": "PKCS12", "gallipick-signing.jks": "JKS"}


def restore_bundle(encoded, runner_temp, expected_pin):
    if not encoded or not re.fullmatch(r"[0-9a-f]{64}", expected_pin):
        raise ReleaseError("The fixed signing secret or public certificate pin is missing.")
    if len(encoded) > 2_097_152:
        raise ReleaseError("The signing recovery archive exceeds the allowed size.")
    try:
        payload = base64.b64decode("".join(encoded.split()), validate=True)
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            entries = archive.infolist()
            names = {entry.filename for entry in entries}
            keystores = names & KEYSTORE_TYPES.keys()
            if len(entries) != len(COMMON_MEMBERS) + 1 or len(keystores) != 1 or names != COMMON_MEMBERS | keystores:
                raise ReleaseError("The signing recovery archive has unexpected members.")
            keystore = next(iter(keystores))
            if sum(entry.file_size for entry in entries) > 1_048_576:
                raise ReleaseError("The signing recovery archive exceeds the allowed size.")
            if any(entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16) for entry in entries):
                raise ReleaseError("The signing recovery archive contains unsupported entries.")
            contents = {entry.filename: archive.read(entry) for entry in entries}
        bundled_pin = contents["certificate.sha256"].decode("ascii").strip().lower()
        if bundled_pin != expected_pin:
            raise ReleaseError("The signing recovery archive does not match the public certificate pin.")
        properties = {}
        for line in contents["signing.properties"].decode("utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith(("#", "!")):
                continue
            key, value = line.split("=", 1)
            if key in properties:
                raise ReleaseError("The private signing configuration contains duplicate fields.")
            properties[key] = value
        if properties.get("storeFile") != keystore:
            raise ReleaseError("The private signing configuration must use the bundled keystore.")
        if properties.get("storeType", "PKCS12") != KEYSTORE_TYPES[keystore] or not all(properties.get(key) for key in ("storePassword", "keyAlias", "keyPassword")):
            raise ReleaseError("The private signing configuration is incomplete.")
    except ReleaseError:
        raise
    except Exception:
        raise ReleaseError("The signing recovery archive could not be decoded.") from None

    parent = runner_temp.resolve()
    if not parent.is_dir():
        raise ReleaseError("The runner temporary directory is unavailable.")
    directory = Path(tempfile.mkdtemp(prefix="gallipick-signing-", dir=parent))
    directory.chmod(0o700)
    try:
        for name, content in contents.items():
            with os.fdopen(os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
                output.write(content)
    except Exception:
        shutil.rmtree(directory)
        raise ReleaseError("The private signing files could not be restored.") from None
    return directory


def cleanup_signing_directory(directory, runner_temp):
    if directory.is_symlink() or directory.resolve().parent != runner_temp.resolve() or not directory.name.startswith("gallipick-signing-"):
        raise ReleaseError("Refusing to remove a directory outside this release's temporary signing scope.")
    if directory.exists():
        shutil.rmtree(directory)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["restore", "cleanup"])
    args = parser.parse_args()
    runner_temp = os.environ.get("RUNNER_TEMP")
    if not runner_temp:
        raise ReleaseError("RUNNER_TEMP is required for release signing.")
    if args.action == "cleanup":
        directory = os.environ.get("GALLIPICK_SIGNING_DIRECTORY")
        if directory:
            cleanup_signing_directory(Path(directory), Path(runner_temp))
        return
    pin = Path("signing/certificate.sha256").read_text(encoding="ascii").strip().lower()
    directory = restore_bundle(os.environ.get("GALLIPICK_SIGNING_BUNDLE_BASE64", ""), Path(runner_temp), pin)
    try:
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
            output.write(f"GALLIPICK_SIGNING_DIRECTORY={directory}\nGALLIPICK_SIGNING_PROPERTIES={directory / 'signing.properties'}\n")
    except Exception:
        cleanup_signing_directory(directory, Path(runner_temp))
        raise ReleaseError("The private signing configuration could not be registered with the runner.") from None
    print("Fixed GalliPick signing files restored privately.")


if __name__ == "__main__":
    try:
        main()
    except ReleaseError as error:
        raise SystemExit(str(error))
