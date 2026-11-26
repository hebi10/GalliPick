"""Run the R8 benchmark on a disposable Linux GitHub Actions emulator."""

from collections import deque
from functools import partial
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "build" / "gp-device"
SERIAL = "emulator-5554"


def adb(*args, env, timeout=10):
    return subprocess.run(
        ["adb", *args], env=env, check=True, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
    ).stdout.strip()


def wait_for_boot(process, command):
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Benchmark emulator exited before boot.")
        try:
            if command("shell", "getprop", "sys.boot_completed") == "1":
                command("shell", "input", "keyevent", "82")
                for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                    command("shell", "settings", "put", "global", setting, "0.0")
                return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise RuntimeError("Benchmark emulator did not boot within 240 seconds.")


def signal_group(pid, sig):
    # The emulator owns this isolated process group, never the runner or adb server.
    try:
        os.killpg(pid, sig)
    except ProcessLookupError:
        pass


def stop_emulator(process, command):
    try:
        if process.poll() is None:
            try:
                command("emu", "kill", timeout=10)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
                print(f"Graceful emulator shutdown request failed: {error}", flush=True)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            print("Emulator shutdown exceeded 20 seconds; terminating its process group.", flush=True)
            signal_group(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                print("Emulator still running; killing its process group.", flush=True)
                signal_group(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
    finally:
        # Clean up any remaining emulator child processes after the leader exits.
        signal_group(process.pid, signal.SIGKILL)


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or sys.platform != "linux":
        raise RuntimeError("This runner requires a disposable Linux GitHub Actions VM.")
    sdk = Path(os.environ["ANDROID_HOME"])
    env = dict(os.environ, ANDROID_SERIAL=SERIAL)
    env["PATH"] = str(sdk / "platform-tools") + os.pathsep + env["PATH"]
    command = partial(adb, env=env)
    command("start-server")
    if any(line.split() and line.split()[0] == SERIAL for line in command("devices").splitlines()):
        raise RuntimeError("The benchmark emulator port is already in use.")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = OUTPUT / "metrics.json"
    if report.exists():
        raise RuntimeError("Refusing to use a stale benchmark report.")
    log = OUTPUT / "emulator.log"
    try:
        with log.open("w", encoding="utf-8") as stream:
            process = subprocess.Popen(
                [str(sdk / "emulator" / "emulator"), "-port", "5554", "-avd", "gp-benchmark",
                 "-no-window", "-gpu", "swiftshader_indirect", "-no-snapshot", "-noaudio", "-no-boot-anim"],
                env=env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True,
            )
            try:
                wait_for_boot(process, command)
                subprocess.run(
                    [sys.executable, str(ROOT / "scripts" / "performance" / "gp_device.py")],
                    cwd=ROOT, env=env, check=True, timeout=600,
                )
                if not report.is_file():
                    raise RuntimeError("Device benchmark did not produce its measurements.")
                print("Both device measurements completed; stopping emulator.", flush=True)
            finally:
                stop_emulator(process, command)
        print("Benchmark emulator stopped; measurements are ready to upload.", flush=True)
    except (OSError, RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        if log.exists():
            with log.open(encoding="utf-8", errors="replace") as stream:
                print("Emulator log tail:\n" + "".join(deque(stream, maxlen=30)), flush=True)
        raise


if __name__ == "__main__":
    main()
