#!/usr/bin/env python3
"""Validate and replace GalliPick's Korean Play screenshots in a single edit."""
import argparse
import hashlib
import json
import os
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

PACKAGE = 'com.hebi.gallipick'
LANGUAGE = 'ko-KR'
SCREENSHOTS = [
    '01-home.png', '02-files.png', '03-duplicates.png', '04-comparison.png',
    '05-cleanup.png', '06-date-filter.png', '07-large-files.png', '08-trash.png',
]
SOURCE_SCREENS = [
    '01-home', '02-files', '09-duplicates-scanned', '10-duplicate-comparison',
    '04-cleanup', '08-files-filter-dialog', '06-files-size-descending', '07-trash-review',
]
FEATURE = 'feature-graphic.png'
BASE = f'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}'


def png_size(path: Path):
    with path.open('rb') as file:
        header = file.read(26)
    if len(header) != 26 or header[:8] != b'\x89PNG\r\n\x1a\n' or header[12:16] != b'IHDR':
        raise ValueError(f'Not a PNG: {path.name}')
    width, height = struct.unpack('>II', header[16:24])
    if header[25] not in (2, 6):
        raise ValueError(f'Unsupported PNG color mode: {path.name}')
    if header[25] == 6:
        raise ValueError(f'Play screenshots cannot contain alpha: {path.name}')
    return width, height


def validate(directory: Path):
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    files = [(x['file'],x['sha256']) for x in manifest.get('screenshots',[])]
    if [x[0] for x in files] != SCREENSHOTS:
        raise ValueError('Unexpected number or order of screenshot assets')
    if manifest.get('source_screens') != SOURCE_SCREENS:
        raise ValueError('Unexpected source screen order')
    for screenshot, expected_scene in zip(manifest.get('screenshots', []), SOURCE_SCREENS):
        source = screenshot.get('source', '')
        if not isinstance(source, str) or not (
            source == expected_scene + '.png' or source.endswith('-' + expected_scene + '.png')
        ):
            raise ValueError('Screenshot provenance does not match the approved scene: ' + expected_scene)
    unexpected = sorted(
        file.name for file in directory.glob('[0-9][0-9]-*.png')
        if file.name not in SCREENSHOTS
    )
    if unexpected:
        raise ValueError(f'Unlisted legacy Play screenshots are present: {unexpected}')
    feature = manifest.get('feature_graphic', {})
    if feature.get('file') != FEATURE:
        raise ValueError('Missing feature graphic manifest')
    files.append((FEATURE,feature['sha256']))
    for name, expected_hash in files:
        path = directory / name
        if not path.is_file():
            raise FileNotFoundError(path)
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected_hash:
            raise ValueError(f'SHA-256 mismatch: {name}')
        size = png_size(path)
        if size != ((1024,500) if name == FEATURE else (1350,2400)):
            raise ValueError(f'Unexpected Play dimensions {size}: {name}')
        if path.stat().st_size > 15_000_000:
            raise ValueError(f'Image too large: {name}')
    print(f'Validated {len(SCREENSHOTS)} genuine Android screenshot composites, one feature graphic, no alpha')
    return files


def api(token, method, url, data=None, content_type='application/json'):
    body = None if data is None else (json.dumps(data).encode('utf-8') if isinstance(data,dict) else data)
    req = urllib.request.Request(url, method=method, data=body,
        headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json',
                 'Content-Type':content_type})
    try:
        with urllib.request.urlopen(req, timeout=90) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        message = error.read(2048).decode('utf-8', errors='replace')
        raise RuntimeError(f'Google Play API {method} request failed (HTTP {error.code}): {message}') from None


def upload(token, edit_id, kind, path):
    endpoint = f'{BASE}/edits/{edit_id}/listings/{LANGUAGE}/{kind}'
    response = api(token, 'POST', endpoint.replace('androidpublisher.googleapis.com/androidpublisher/v3',
        'androidpublisher.googleapis.com/upload/androidpublisher/v3') + '?uploadType=media',
        path.read_bytes(), content_type='image/png')
    if not response.get('image',{}).get('id'):
        raise RuntimeError(f'No uploaded image ID from Google Play: {path.name}')


def apply(directory, token):
    edit = api(token, 'POST', f'{BASE}/edits', {})
    edit_id = edit.get('id')
    if not edit_id:
        raise RuntimeError('Play did not issue an edit ID')
    for image_type, names in [('phoneScreenshots', SCREENSHOTS), ('featureGraphic', [FEATURE])]:
        endpoint = f'{BASE}/edits/{edit_id}/listings/{LANGUAGE}/{image_type}'
        api(token, 'DELETE', endpoint)
        for filename in names:
            upload(token, edit_id, image_type, directory / filename)
        result = api(token, 'GET', endpoint)
        if len(result.get('images',[])) != len(names):
            raise RuntimeError(f'Google Play did not report the complete {image_type} image set')
    commit = api(token, 'POST', f'{BASE}/edits/{edit_id}:commit', {})
    if not commit.get('id'):
        raise RuntimeError('Play edit commit returned no ID')
    readback = api(token, 'POST', f'{BASE}/edits', {})
    readback_id = readback.get('id')
    if not readback_id:
        raise RuntimeError('Committed edit but could not create a fresh readback edit; check Play Console')
    try:
        for image_type, names in [('phoneScreenshots', SCREENSHOTS), ('featureGraphic', [FEATURE])]:
            endpoint = f'{BASE}/edits/{readback_id}/listings/{LANGUAGE}/{image_type}'
            result = api(token, 'GET', endpoint)
            if len(result.get('images', [])) != len(names):
                raise RuntimeError(f'Google Play commit accepted but fresh {image_type} readback differs; check Play Console')
    finally:
        api(token, 'DELETE', f'{BASE}/edits/{readback_id}')
    print('Google Play accepted and independently read back 8 ko-KR phone screenshots and 1 feature graphic. Review/publishing state not confirmed.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, default=Path('assets/play-store/ko-KR'))
    parser.add_argument('--apply', action='store_true', help='Update Korean GalliPick Play images')
    args = parser.parse_args()
    validate(args.directory)
    if args.apply:
        token = os.getenv('PLAY_ACCESS_TOKEN','')
        if not token:
            raise RuntimeError('Missing PLAY_ACCESS_TOKEN')
        apply(args.directory,token)

if __name__ == '__main__':
    try:
        main()
    except (RuntimeError,ValueError,FileNotFoundError,KeyError) as err:
        print(str(err),file=sys.stderr)
        sys.exit(1)
