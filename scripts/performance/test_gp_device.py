from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import patch

from gp_device import ACTIVITY, PACKAGE, find_control, installed_code_path, measure, startup_time, storage_kib


class DeviceMetricsTest(TestCase):
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
