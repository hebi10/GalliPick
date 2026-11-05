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
                screens.append({'file': filename, 'sha256': checksum})
        (directory / 'manifest.json').write_text(json.dumps({'screenshots': screens, 'feature_graphic': feature}), encoding='utf-8')
        return directory

    def test_validates_exact_scoped_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            self.assertEqual(len(listing.validate(self.make_assets(temp))), 8)

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

    def test_does_not_use_unapproved_package_name(self):
        self.assertEqual(listing.PACKAGE, 'com.hebi.gallipick')
        self.assertEqual(listing.LANGUAGE, 'ko-KR')


if __name__ == '__main__':
    unittest.main()
