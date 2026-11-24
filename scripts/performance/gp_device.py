"""Compare isolated Play Cloud APKs on a disposable, rooted Android emulator."""

import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import statistics
import subprocess
import time
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build" / "gp-device"
PACKAGE = "com.hebi.gallipick.benchmark"
ACTIVITY = "com.hebi.gallipick.MainActivity"
UI_DUMP = "/data/local/tmp/gallipick-benchmark-window.xml"
STARTUP_SAMPLES = 5


def adb(*args):
    result = subprocess.run(
        ["adb", *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        encoding="utf-8", timeout=180,
    )
    return result.stdout.strip()


def startup_time(output, expected_state):
    fields = dict(re.findall(r"(?m)^(Status|LaunchState|Activity|TotalTime):[ \t]*([^\r\n]+)$", output))
    activity = fields.get("Activity")
    time_ms = fields.get("TotalTime", "")
    if (
        fields.get("Status") != "ok"
        or fields.get("LaunchState") != expected_state
        or activity != f"{PACKAGE}/{ACTIVITY}"
        or not time_ms.isdecimal()
        or int(time_ms) <= 0
    ):
        raise ValueError(f"Expected a successful {expected_state} launch of {PACKAGE}. Android reported:\n{output}")
    return int(time_ms)


def launch(expected_state):
    return startup_time(adb(
        "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
        "--activity-new-task", "--activity-clear-task", "-n", f"{PACKAGE}/{ACTIVITY}",
    ), expected_state)


def find_control(hierarchy, label):
    for node in ElementTree.fromstring(hierarchy).iter("node"):
        if node.get("package") != PACKAGE or label not in (node.get("text"), node.get("content-desc")):
            continue
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if bounds:
            left, top, right, bottom = map(int, bounds.groups())
            if left < right and top < bottom:
                return (left + right) // 2, (top + bottom) // 2
    return None


def wait_for_control(label):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        adb("shell", "rm", "-f", UI_DUMP)
        try:
            adb("shell", "uiautomator", "dump", UI_DUMP)
            point = find_control(adb("shell", "cat", UI_DUMP), label)
        except (subprocess.CalledProcessError, ElementTree.ParseError):
            point = None
        if point is not None:
            return point
        time.sleep(0.4)
    raise ValueError(f"The benchmark app did not display {label!r}.")


def smoke_test():
    wait_for_control("GalliPick")
    for label in ("파일", "파일 조건 선택", "필터 닫기"):
        x, y = wait_for_control(label)
        adb("shell", "input", "tap", str(x), str(y))
    wait_for_control("파일 조건 선택")


def storage_kib():
    installed = re.fullmatch(r"package:(/data/app/[\w./~+=-]+/base\.apk)", adb("shell", "pm", "path", PACKAGE))
    if not installed:
        raise ValueError("Expected the benchmark package to contain exactly one installed APK.")
    code = str(PurePosixPath(installed[1]).parent)
    data = f"/data/user/0/{PACKAGE}"
    metrics = {}
    for name, path in (("code", code), ("credential_encrypted_app_data", data)):
        size = re.fullmatch(rf"(\d+)[ \t]+{re.escape(path)}", adb("shell", "du", "-sk", path))
        if not size:
            raise ValueError(f"Could not measure the installed {name} directory.")
        metrics[name] = int(size[1])
    return metrics


def measure(apk):
    print(f"Measuring {apk.stem}", flush=True)
    if adb("install", "-g", str(apk)).splitlines()[-1:] != ["Success"]:
        raise ValueError(f"Could not install {apk.name}.")
    try:
        # Exercise first-run setup before timing repeated app launches.
        launch("COLD")
        smoke_test()
        adb("shell", "am", "force-stop", PACKAGE)
        installed = storage_kib()
        cold = []
        warm = []
        for _ in range(STARTUP_SAMPLES):
            adb("shell", "am", "force-stop", PACKAGE)
            cold.append(launch("COLD"))
            pid = adb("shell", "pidof", PACKAGE)
            warm.append(launch("WARM"))
            if pid != adb("shell", "pidof", PACKAGE):
                raise ValueError("The benchmark app process changed during a warm start.")
    finally:
        if adb("uninstall", PACKAGE) != "Success":
            raise ValueError("Could not remove the isolated benchmark app.")
    return {
        "mode": apk.stem,
        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "installed_kib": installed,
        "startup_ms": {
            "cold": {"samples": cold, "median": statistics.median(cold)},
            "warm": {"samples": warm, "median": statistics.median(warm)},
        },
        "home_files_filter_smoke": "passed",
    }


def main():
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise ValueError("Run this benchmark on a disposable Android emulator.")
    if f"package:{PACKAGE}" in adb("shell", "pm", "list", "packages", PACKAGE).splitlines():
        raise ValueError("Use a disposable emulator without the benchmark app already installed.")
    adb("root")
    adb("wait-for-device")
    if adb("shell", "id", "-u") != "0":
        raise ValueError("Installed storage measurement requires a rooted emulator.")

    report = {
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "android_api_level": int(adb("shell", "getprop", "ro.build.version.sdk")),
        "android_build_fingerprint": adb("shell", "getprop", "ro.build.fingerprint"),
        "profile": "playCloud",
        "startup_metric": "am start -W TotalTime",
        "measurements": [],
    }
    try:
        for mode in ("baseline", "r8"):
            report["measurements"].append(measure(OUTPUT / f"{mode}.apk"))
    finally:
        adb("shell", "rm", "-f", UI_DUMP)
    (OUTPUT / "metrics.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stdout or str(error)) from None
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error)) from None
