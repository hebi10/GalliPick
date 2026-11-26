import signal
import subprocess
from unittest import TestCase
from unittest.mock import Mock, call, patch

from gp_emulator import stop_emulator, wait_for_boot


class EmulatorLifecycleTest(TestCase):
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
