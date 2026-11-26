import os
from pathlib import Path
import signal
import subprocess
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import Mock, call, patch

from gp_emulator import AVD_NAME, SYSTEM_IMAGE, benchmark_environment, prepare_avd, verify_avd, stop_emulator, wait_for_boot


class EmulatorLifecycleTest(TestCase):
    def test_sdk_tools_share_an_explicit_isolated_avd_home(self):
        with TemporaryDirectory() as directory:
            sdk = Path(directory) / "sdk"
            inherited = {"RUNNER_TEMP": directory, "PATH": "/usr/bin",
                         "XDG_CONFIG_HOME": "/legacy/config", "ANDROID_SDK_HOME": "/legacy/sdk"}
            env = benchmark_environment(inherited, sdk)
            home = Path(directory) / "gp-android-home"
            self.assertEqual(env["ANDROID_USER_HOME"], str(home))
            self.assertEqual(env["ANDROID_EMULATOR_HOME"], str(home))
            self.assertEqual(env["ANDROID_AVD_HOME"], str(home / "avd"))
            self.assertEqual(env["PATH"], str(sdk / "platform-tools") + os.pathsep + "/usr/bin")
            self.assertNotIn("XDG_CONFIG_HOME", env)
            self.assertNotIn("ANDROID_SDK_HOME", env)
            self.assertIn("XDG_CONFIG_HOME", inherited)

    def test_prepare_creates_avd_and_checks_emulator_visibility(self):
        with TemporaryDirectory() as directory:
            sdk = Path(directory) / "sdk"
            env = benchmark_environment({"RUNNER_TEMP": directory, "PATH": "/usr/bin"}, sdk)
            avd_home = Path(env["ANDROID_AVD_HOME"])
            avd_path = avd_home / f"{AVD_NAME}.avd"

            def run(*args, **kwargs):
                self.assertEqual(kwargs["env"], env)
                if args[0][0] == "avdmanager":
                    avd_path.mkdir()
                    (avd_home / f"{AVD_NAME}.ini").write_text("path=" + str(avd_path))
                    (avd_path / "config.ini").write_text("abi.type=x86_64")
                    return Mock()
                return Mock(stdout=AVD_NAME + "\n")

            with patch("gp_emulator.subprocess.run", side_effect=run) as command:
                prepare_avd(sdk, env)
            self.assertEqual(command.call_args_list, [
                call(["avdmanager", "create", "avd", "--force", "--name", AVD_NAME,
                      "--package", SYSTEM_IMAGE, "--device", "pixel_6", "--path", str(avd_path)],
                     env=env, input="no\n", check=True, text=True, timeout=120),
                call([str(sdk / "emulator" / "emulator"), "-list-avds"],
                     env=env, check=True, text=True, stdout=subprocess.PIPE, timeout=30),
            ])

    def test_avd_check_rejects_missing_registration_or_visibility(self):
        with TemporaryDirectory() as directory:
            sdk = Path(directory) / "sdk"
            env = benchmark_environment({"RUNNER_TEMP": directory, "PATH": "/usr/bin"}, sdk)
            avd_home = Path(env["ANDROID_AVD_HOME"])
            avd_path = avd_home / f"{AVD_NAME}.avd"
            with patch("gp_emulator.subprocess.run", return_value=Mock(stdout=AVD_NAME + "\n")):
                with self.assertRaisesRegex(RuntimeError, "AVD not registered"):
                    verify_avd(sdk, env)
            avd_path.mkdir(parents=True)
            (avd_home / f"{AVD_NAME}.ini").write_text("path=" + str(avd_path))
            (avd_path / "config.ini").write_text("abi.type=x86_64")
            with patch("gp_emulator.subprocess.run", return_value=Mock(stdout="another_avd\n")):
                with self.assertRaisesRegex(RuntimeError, "AVD not registered"):
                    verify_avd(sdk, env)

    def test_successful_shutdown_is_bounded_and_cleans_children(self):
        process = Mock(pid=123)
        process.poll.return_value = None
        command = Mock()
        with patch("gp_emulator.os.killpg") as kill:
            stop_emulator(process, command)
        command.assert_called_once_with("emu", "kill", timeout=10)
        process.wait.assert_called_once_with(timeout=20)
        kill.assert_called_once_with(123, signal.SIGKILL)

    def test_hung_shutdown_escalates_only_owned_process_group(self):
        process = Mock(pid=123)
        process.poll.return_value = None
        process.wait.side_effect = [
            subprocess.TimeoutExpired("emulator", 20),
            subprocess.TimeoutExpired("emulator", 10),
            0,
        ]
        command = Mock(side_effect=subprocess.TimeoutExpired("adb emu kill", 10))
        with patch("gp_emulator.os.killpg") as kill:
            stop_emulator(process, command)
        self.assertEqual(process.wait.call_args_list, [call(timeout=20), call(timeout=10), call(timeout=10)])
        self.assertEqual(kill.call_args_list, [
            call(123, signal.SIGTERM), call(123, signal.SIGKILL), call(123, signal.SIGKILL),
        ])

    def test_cleanup_failure_is_not_reported_as_success(self):
        process = Mock(pid=123)
        process.poll.return_value = None
        process.wait.side_effect = subprocess.TimeoutExpired("emulator", 10)
        with patch("gp_emulator.os.killpg"), self.assertRaises(subprocess.TimeoutExpired):
            stop_emulator(process, Mock())

    def test_emulator_boot_requires_ready_device_and_disables_animations(self):
        process = Mock()
        process.poll.return_value = None
        command = Mock(return_value="1")
        wait_for_boot(process, command)
        self.assertEqual(command.call_args_list, [
            call("shell", "getprop", "sys.boot_completed"),
            call("shell", "input", "keyevent", "82"),
            call("shell", "settings", "put", "global", "window_animation_scale", "0.0"),
            call("shell", "settings", "put", "global", "transition_animation_scale", "0.0"),
            call("shell", "settings", "put", "global", "animator_duration_scale", "0.0"),
        ])

    def test_early_emulator_exit_fails_boot(self):
        process = Mock()
        process.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, "exited before boot"):
            wait_for_boot(process, Mock())
