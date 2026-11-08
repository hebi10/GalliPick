import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path

import play_listing_images as listing


class PlayListingImagesTest(unittest.TestCase):
    def make_assets(self, folder, color_type=2):
        directory = Path(folder)
        screens = []
        for filename in [*listing.SCREENSHOTS, listing.FEATURE]:
            width, height = ((1024, 500) if filename == listing.FEATURE else (1350, 2400))
            header = (b'\x89PNG\r\n\x1a\n' + b'\0\0\0\x0dIHDR' +
                      struct.pack('>II', width, height) + bytes([8, color_type]) + b'\0\0\0')
            (directory / filename).write_bytes(header)
            checksum = hashlib.sha256(header).hexdigest()
            if filename == listing.FEATURE:
                feature = {'file': filename, 'sha256': checksum}
            else:
                scene = listing.SOURCE_SCREENS[len(screens)]
                screens.append({'file': filename, 'source': 'fixture-' + scene + '.png', 'sha256': checksum})
        (directory / 'manifest.json').write_text(json.dumps({'screenshots': screens, 'feature_graphic': feature,
            'source_screens': listing.SOURCE_SCREENS}), encoding='utf-8')
        return directory

    def test_validates_exact_scoped_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertEqual(len(listing.validate(self.make_assets(temp))), 9)

    def test_refuses_incorrect_source_order(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = self.make_assets(temp)
            path = directory / 'manifest.json'
            manifest = json.loads(path.read_text())
            manifest['source_screens'] = manifest['source_screens'][::-1]
            path.write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'source screen order'):
                listing.validate(directory)

    def test_refuses_alpha_screenshots(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, 'alpha'):
                listing.validate(self.make_assets(temp, color_type=6))

    def test_refuses_modified_images(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = self.make_assets(temp)
            (directory / listing.SCREENSHOTS[0]).write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                listing.validate(directory)

    def test_first_five_screens_cover_main_app_tasks(self):
        self.assertEqual(
            listing.SCREENSHOTS[:5],
            ['01-home.png', '02-files.png', '03-duplicates.png', '04-comparison.png', '05-cleanup.png'],
        )
        self.assertEqual(len(listing.SCREENSHOTS), 8)

    def test_refuses_old_unlisted_screenshots(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = self.make_assets(temp)
            (directory / '03-filters.png').write_bytes(b'old image')
            with self.assertRaisesRegex(ValueError, 'Unlisted legacy'):
                listing.validate(directory)

    def test_does_not_use_unapproved_package_name(self):
        self.assertEqual(listing.PACKAGE, 'com.hebi.gallipick')
        self.assertEqual(listing.LANGUAGE, 'ko-KR')


if __name__ == '__main__':
    unittest.main()
