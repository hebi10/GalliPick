"""Synthetic, unsigned test-only ZIP fixtures for R8 release-policy tests.

Signature tests sign these fixture payloads separately; no real build is implied.
"""
import hashlib
import json
import zipfile

R8_PATH = "BUNDLE-METADATA/com.android.tools/r8.json"
MAP_PATH = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"


def fixture_entries(*, size=112, disabled_percent=20, metadata=True, mapping=True,
                    flags=None, version="8.13.19", bad_checksum=False, extra_dex=False):
    dex = b"dex\n039\x00" + bytes(size - 8)
    entries = {"base/dex/classes.dex": dex}
    if metadata:
        data = {
            "version": version,
            "options": flags if flags is not None else {
                "isObfuscationEnabled": True,
                "isOptimizationsEnabled": True,
                "isShrinkingEnabled": True,
            },
            "stats": {
                "noObfuscationPercentage": disabled_percent,
                "noOptimizationPercentage": 20,
                "noShrinkingPercentage": 20,
            },
            "dexFiles": [{"checksum": "0" * 64 if bad_checksum else hashlib.sha256(dex).hexdigest()}],
        }
        entries[R8_PATH] = json.dumps(data)
    if mapping:
        entries[MAP_PATH] = "original.Example -> a:\n"
    if extra_dex:
        entries["base/assets/extra.dex"] = dex
    return entries


def write_fixture(path, **kwargs):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, value in fixture_entries(**kwargs).items():
            archive.writestr(name, value)
    return path
