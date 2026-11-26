from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import call, patch

from gp_device import ACTIVITY, PACKAGE, ensure_rooted_emulator, find_control, installed_code_path, launch, measure, startup_time, storage_kib, wait_for_control, prepare_interactive_emulator, launcher_anr_close_point, recover_launcher_anr


class DeviceMetricsTest(TestCase):
    def test_root_reconnects_after_adbd_closes_the_command(self):
        closed = subprocess.CalledProcessError(1, ["adb", "root"], output="adb: unable to connect for root: closed")
        with patch("gp_device.adb", side_effect=["", "2000", closed, "", "0"]) as command:
            ensure_rooted_emulator()
            self.assertEqual(command.call_count, 5)
            command.assert_called_with("shell", "id", "-u", timeout=15)

    def test_already_rooted_emulator_does_not_restart_adbd(self):
        with patch("gp_device.adb", side_effect=["", "0"]) as command:
            ensure_rooted_emulator()
            self.assertEqual(command.call_count, 2)

    def test_root_retries_transient_timeout_but_requires_root_uid(self):
        timeout = subprocess.TimeoutExpired(["adb", "wait-for-device"], 15)
        with patch("gp_device.adb", side_effect=[timeout, "", "0"]), patch("gp_device.time.sleep") as sleep:
            ensure_rooted_emulator()
            sleep.assert_called_once_with(2)
        with (
            patch("gp_device.adb", side_effect=["", "2000", "adbd cannot run as root", "", "2000"] * 3) as command,
            patch("gp_device.time.sleep") as sleep,
            self.assertRaisesRegex(ValueError, "requires a rooted emulator"),
        ):
            ensure_rooted_emulator()
        self.assertEqual(command.call_count, 15)
        self.assertEqual(sleep.call_count, 2)

    def test_unexpected_adb_failure_is_not_retried(self):
        error = subprocess.CalledProcessError(1, ["adb", "root"], output="adb: unexpected failure")
        with patch("gp_device.adb", side_effect=["", "2000", error]) as command:
            with self.assertRaises(subprocess.CalledProcessError):
                ensure_rooted_emulator()
            self.assertEqual(command.call_count, 3)

    def test_launch_uses_supported_numeric_intent_flags_for_cold_and_warm(self):
        for state in ("COLD", "WARM"):
            result = f"Status: ok\nLaunchState: {state}\nActivity: {PACKAGE}/{ACTIVITY}\nTotalTime: 347\nComplete"
            with self.subTest(state=state), patch("gp_device.adb", return_value=result) as command:
                self.assertEqual(launch(state), 347)
                command.assert_called_once_with(
                    "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                    "-f", "0x10008000", "-n", f"{PACKAGE}/{ACTIVITY}",
                )

    def test_startup_requires_the_expected_activity_state_and_duration(self):
        result = f"Status: ok\nLaunchState: WARM\nActivity: {PACKAGE}/{ACTIVITY}\nTotalTime: 347\nComplete"
        self.assertEqual(startup_time(result, "WARM"), 347)
        for invalid in (
            result.replace("WARM", "HOT"), result.replace("ok", "timeout"),
            result.replace(PACKAGE, "another.app"), result.replace("347", "0"),
        ):
            with self.subTest(output=invalid), self.assertRaisesRegex(ValueError, "Expected a successful WARM launch"):
                startup_time(invalid, "WARM")

    def test_controls_are_selected_by_text_or_description_in_the_benchmark_app(self):
        hierarchy = f"""<hierarchy>
          <node package="another.app" text="파일" bounds="[0,0][99,99]" />
          <node package="{PACKAGE}" text="파일" bounds="[0,0][0,0]" />
          <node package="{PACKAGE}" text="파일" bounds="[0,10][30,40]" />
          <node package="{PACKAGE}" content-desc="필터 닫기" bounds="[10,20][40,50]" />
        </hierarchy>"""
        self.assertEqual(find_control(hierarchy, "파일"), (15, 25))
        self.assertEqual(find_control(hierarchy, "필터 닫기"), (25, 35))
        self.assertIsNone(find_control(hierarchy, "정리"))
        disabled = f'<hierarchy><node package="{PACKAGE}" text="재생" enabled="false" bounds="[0,0][20,30]" /></hierarchy>'
        self.assertIsNone(find_control(disabled, "재생", enabled=True))
        self.assertEqual(find_control(disabled.replace('enabled="false"', 'enabled="true"'), "재생", enabled=True), (10, 15))

    def test_ui_dump_retries_timeout_but_requires_exact_app_control(self):
        hierarchy = f'<hierarchy><node package="{PACKAGE}" text="GalliPick" bounds="[0,0][20,30]" /></hierarchy>'
        with (
            patch("gp_device.adb", side_effect=["", subprocess.TimeoutExpired("dump", 15), "", "", hierarchy]),
            patch("gp_device.time.sleep"),
        ):
            self.assertEqual(wait_for_control("GalliPick"), (10, 15))

    def test_ui_timeout_does_not_accept_another_packages_label(self):
        hierarchy = '<hierarchy><node package="another.app" text="GalliPick" bounds="[0,0][20,30]" /></hierarchy>'
        with (
            patch("gp_device.adb", side_effect=["", "", hierarchy]),
            patch("gp_device.capture_ui_failure") as diagnose,
            patch("gp_device.time.monotonic", side_effect=[0, 0, 61]),
            patch("gp_device.time.sleep"),
            self.assertRaisesRegex(ValueError, "did not display"),
        ):
            wait_for_control("GalliPick")
        diagnose.assert_called_once_with("Missing control 'GalliPick'", hierarchy, None)

    def test_interactive_setup_checks_emulator_root_and_disabled_keyguard(self):
        with patch("gp_device.adb", side_effect=["1", "0", "", "", "true", "", ""]) as command:
            prepare_interactive_emulator()
        self.assertEqual(command.call_args_list, [
            call("shell", "getprop", "ro.kernel.qemu", timeout=15),
            call("shell", "id", "-u", timeout=15),
            call("shell", "svc", "power", "stayon", "true", timeout=15),
            call("shell", "locksettings", "set-disabled", "true", timeout=15),
            call("shell", "locksettings", "get-disabled", timeout=15),
            call("shell", "input", "keyevent", "KEYCODE_WAKEUP", timeout=15),
            call("shell", "wm", "dismiss-keyguard", timeout=15),
        ])
        with patch("gp_device.adb", return_value="0") as command, self.assertRaisesRegex(ValueError, "disposable rooted"):
            prepare_interactive_emulator()
        command.assert_called_once_with("shell", "getprop", "ro.kernel.qemu", timeout=15)
        with patch("gp_device.adb", side_effect=["1", "0", "", "", "false"]), self.assertRaisesRegex(ValueError, "lock screen"):
            prepare_interactive_emulator()

    def launcher_dialog(self):
        return """<hierarchy>
          <node package="android" resource-id="android:id/alertTitle" text="Quickstep isn't responding" bounds="[1,1][100,50]" />
          <node package="android" resource-id="android:id/aerr_close" text="Close app" enabled="true" clickable="true" bounds="[70,1143][1010,1269]" />
        </hierarchy>"""

    def test_launcher_recovery_requires_exact_system_dialog_and_identity(self):
        hierarchy = self.launcher_dialog()
        self.assertEqual(launcher_anr_close_point(hierarchy), (540, 1206))
        for other in (hierarchy.replace("Quickstep", "GalliPick"),
                      hierarchy.replace('package="android"', 'package="another.app"'),
                      hierarchy.replace('android:id/aerr_close', 'android:id/aerr_wait'),
                      hierarchy.replace('enabled="true"', 'enabled="false"')):
            with self.subTest(other=other), patch("gp_device.adb") as command:
                self.assertFalse(recover_launcher_anr(other))
                command.assert_not_called()

    def test_launcher_recovery_only_for_resumed_benchmark_and_launcher_anr(self):
        window = ("mCurrentFocus=Window{34f374a u0 Application Not Responding: com.android.launcher3}\n"
                  f"mFocusedApp=ActivityRecord{{10213527 u0 {PACKAGE}/{ACTIVITY} t8}}")
        activity = f"topResumedActivity=ActivityRecord{{10213527 u0 {PACKAGE}/{ACTIVITY} t8}}"
        for focus, resumed in ((window.replace("com.android.launcher3", PACKAGE), activity),
                               (window, activity.replace(PACKAGE, "another.app")),
                               (window.replace(PACKAGE, "another.app"), activity)):
            with self.subTest(focus=focus, resumed=resumed), patch("gp_device.adb", side_effect=["1", "0", focus, resumed]) as command:
                self.assertFalse(recover_launcher_anr(self.launcher_dialog()))
                self.assertFalse(any(c.args[:3] == ("shell", "input", "tap") for c in command.call_args_list))
        with patch("gp_device.adb", side_effect=["1", "0", window, activity, ""]) as command:
            self.assertTrue(recover_launcher_anr(self.launcher_dialog()))
            command.assert_called_with("shell", "input", "tap", "540", "1206", timeout=10)

    def test_initial_smoke_recovers_once_but_still_requires_app_control(self):
        dialog = self.launcher_dialog()
        app = f'<hierarchy><node package="{PACKAGE}" text="GalliPick" bounds="[0,0][20,30]" /></hierarchy>'
        with (patch("gp_device.adb", side_effect=["", "", dialog, "", "", dialog, "", "", app]),
              patch("gp_device.recover_launcher_anr", return_value=True) as recover,
              patch("gp_device.time.sleep")):
            self.assertEqual(wait_for_control("GalliPick", recover_launcher=True), (10, 15))
            recover.assert_called_once_with(dialog)

    def test_initial_smoke_cannot_pass_with_only_launcher_recovery(self):
        dialog = self.launcher_dialog()
        with (patch("gp_device.adb", side_effect=["", "", dialog]),
              patch("gp_device.recover_launcher_anr", return_value=True) as recover,
              patch("gp_device.capture_ui_failure") as diagnose,
              patch("gp_device.time.monotonic", side_effect=[0, 0, 1, 61]),
              patch("gp_device.time.sleep"),
              self.assertRaisesRegex(ValueError, "did not display")):
            wait_for_control("GalliPick", recover_launcher=True)
        recover.assert_called_once_with(dialog)
        diagnose.assert_called_once()

    def test_storage_keeps_installed_code_and_app_data_separate(self):
        code = f"/data/app/~~stub==/{PACKAGE}-stub=="
        data = f"/data/user/0/{PACKAGE}"
        responses = [f"2048\t{code}", f"64\t{data}"]
        with patch("gp_device.adb", side_effect=responses):
            self.assertEqual(storage_kib(code), {"code": 2048, "credential_encrypted_app_data": 64})

    def test_storage_rejects_an_unexpected_apk_path(self):
        for path in ("package:/system/app/base.apk", "package:/data/app/example/split.apk"):
            with self.subTest(path=path), patch("gp_device.adb", return_value=path):
                with self.assertRaisesRegex(ValueError, "exactly one installed APK"):
                    installed_code_path()

    def test_install_and_cleanup_use_package_state_with_noncanonical_adb_output(self):
        with TemporaryDirectory() as directory:
            apk = Path(directory) / "baseline.apk"
            apk.write_bytes(b"test apk")
            code = f"/data/app/~~stub==/{PACKAGE}-stub=="
            package_queries = iter((f"package:{PACKAGE}", ""))

            def adb_response(*args):
                if args == ("shell", "pm", "list", "packages", PACKAGE):
                    return next(package_queries)
                return {
                    ("install", "--no-incremental", "-g", str(apk)): "Success\nInstall command complete in 200 ms",
                    ("shell", "pm", "path", PACKAGE): f"package:{code}/base.apk",
                    ("shell", "am", "force-stop", PACKAGE): "",
                    ("shell", "pidof", PACKAGE): "42",
                    ("uninstall", PACKAGE): "Success\nUninstall completed",
                }[args]

            with (
                patch("gp_device.adb", side_effect=adb_response),
                patch("gp_device.launch", return_value=347),
                patch("gp_device.smoke_test"),
                patch("gp_device.storage_kib", return_value={"code": 2048}),
            ):
                self.assertEqual(measure(apk)["startup_ms"]["warm"]["median"], 347)
