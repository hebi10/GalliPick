"""Verify persistent reflection entry points in an optimized APK, without deobfuscation."""

import hashlib
from pathlib import Path
import re
import subprocess


WORKERS = (
    "com.hebi.gallipick.data.DuplicateScanWorker",
    "com.hebi.gallipick.data.PhotoAnalysisWorker",
    "com.hebi.gallipick.data.PhotoOcrWorker",
    "com.hebi.gallipick.data.cloud.CloudAutoBackupWorker",
)
WORKER_CONSTRUCTOR = "<init>(Landroid/content/Context;Landroidx/work/WorkerParameters;)V"
ROOM_DATABASE = "com.hebi.gallipick.data.MediaDatabase"
ENTRY_POINTS = {
    **dict.fromkeys(WORKERS, WORKER_CONSTRUCTOR),
    ROOM_DATABASE: None,
    ROOM_DATABASE + "_Impl": "<init>()V",
}


def verify_class(smali, name, constructor):
    descriptor = "L" + name.replace(".", "/") + ";"
    classes = re.findall(r"(?m)^\s*\.class[ \t]+[^\r\n]*?[ \t]*(L[^;\r\n]+;)[ \t]*$", smali)
    if classes != [descriptor]:
        raise ValueError(f"Optimized APK is missing the original class definition: {name}")
    if constructor is None:
        return
    methods = re.findall(r"(?m)^\s*\.method[ \t]+([^\r\n]+)$", smali)
    for declaration in methods:
        parts = declaration.split()
        if parts and parts[-1] == constructor and "public" in parts[:-1] and "static" not in parts[:-1] and "abstract" not in parts[:-1]:
            return
    raise ValueError(f"Optimized APK is missing the public reflective constructor: {name} {constructor}")


def verify_optimized_apk(apk, mapping_dir, analyzer="apkanalyzer"):
    mapping = Path(mapping_dir) / "mapping.txt"
    if not mapping.is_file() or mapping.stat().st_size == 0:
        raise ValueError("Optimized build did not produce a nonempty R8 mapping.")
    checked = []
    for name, constructor in ENTRY_POINTS.items():
        # Deliberately do not pass mapping/ProGuard options: verify names in actual DEX.
        result = subprocess.run(
            [analyzer, "dex", "code", "--class", name, str(apk)],
            check=True, text=True, stdout=subprocess.PIPE, timeout=90,
        )
        verify_class(result.stdout, name, constructor)
        checked.append({"class": name, "public_constructor": constructor})
    return {
        "apk_sha256": hashlib.sha256(Path(apk).read_bytes()).hexdigest(),
        "mapping_sha256": hashlib.sha256(mapping.read_bytes()).hexdigest(),
        "verified_entry_points": checked,
        "scope": "DEX definitions and reflective constructors; not live SDK or upgrade runtime QA",
    }
