import shutil
import subprocess
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from play_store_icon import validate_icon, apply


ROOT = Path(__file__).resolve().parents[2]
GENERATOR = ROOT / "scripts/design/GenerateStoreIcon.java"
SOURCE = ROOT / "app/src/main/res/drawable-nodpi/gp_cat_organize.png"


class StoreIconTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("java"), "A Java runtime is required")
    def test_generated_icon_matches_play_requirements(self):
        with tempfile.TemporaryDirectory() as folder:
            image = Path(folder) / "app-icon.png"
            subprocess.run(["java", str(GENERATOR), str(SOURCE), str(image)], check=True, capture_output=True, text=True)
            digest = validate_icon(image)
            self.assertEqual(len(digest), 64)
            self.assertLess(image.stat().st_size, 1_048_576)

    def test_icon_only_commit_does_not_touch_other_listing_images(self):
        calls = []
        def fake_api(token, method, endpoint, data=None, content_type="application/json"):
            calls.append((method, endpoint))
            if method == "POST" and endpoint.endswith("/edits"):
                return {"id": str(sum(1 for action, url in calls if action == "POST" and url.endswith("/edits")))}
            if method == "POST" and endpoint.endswith(":commit"):
                return {"id": "1"}
            if method == "GET" and endpoint.endswith("/icon"):
                return {"images": [{"id": "icon-id"}]}
            return {}

        with patch("play_store_icon.api", side_effect=fake_api), patch("play_store_icon.upload") as uploader:
            apply(Path("test-app-icon.png"), "fixture-access-token")
            uploader.assert_called_once_with("fixture-access-token", "1", "icon", Path("test-app-icon.png"))
        edited = [(method, url) for method, url in calls if method in ("DELETE", "GET")]
        self.assertTrue(edited)
        self.assertTrue(all(url.endswith("/listings/ko-KR/icon") for _, url in edited))
        self.assertTrue(all("/applications/com.hebi.gallipick/" in url for _, url in calls))


if __name__ == "__main__":
    unittest.main()
