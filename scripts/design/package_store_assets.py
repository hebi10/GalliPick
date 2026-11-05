#!/usr/bin/env python3
"""Package authentic Android instrumentation screenshots for GalliPick Play listing."""
import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

BACKGROUND = '#FAF9F5'
INK = '#25483F'
MUTED = '#597267'
LINE = '#DCE6DF'

SCREENS = (
    ('01-home.png', '01-home', '사진과 동영상을 한눈에', '기기 안에서 저장공간과 미디어 확인'),
    ('02-files.png', '02-files', '원하는 파일을 빠르게', '사진과 동영상을 검색하고 정렬'),
    ('03-filters.png', '08-files-filter-dialog', '원하는 조건만 골라서', '날짜·용량·길이 등 상세 필터'),
    ('04-duplicates.png', '09-duplicates-scanned', '같은 내용의 파일 찾기', '완전히 일치하는 파일의 중복 검사'),
    ('05-comparison.png', '10-duplicate-comparison', '남길 파일 직접 선택', '원본을 비교하고 안전하게 정리'),
    ('06-cleanup.png', '04-cleanup', '정리 후보부터 살펴보기', '대용량 동영상과 오래된 파일 확인'),
    ('07-dark-mode.png', '05-settings-dark', '눈에 편안한 화면', '라이트·다크 모드를 선택'),
)


def font(size, bold=False):
    name = 'NotoSansCJK-Bold.ttc' if bold else 'NotoSansCJK-Regular.ttc'
    found = [Path('/usr/share/fonts/opentype/noto') / name,
             Path('/usr/share/fonts/truetype/nanum') / ('NanumGothicBold.ttf' if bold else 'NanumGothic.ttf')]
    for file in found:
        if file.is_file():
            return ImageFont.truetype(str(file), size)
    raise FileNotFoundError('Korean font not found; install fonts-noto-cjk')


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def screenshot_image(raw, title, subtitle):
    canvas = Image.new('RGB', (1350, 2400), BACKGROUND)
    draw = ImageDraw.Draw(canvas)
    draw.text((120, 74), title, font=font(62, bold=True), fill=INK)
    draw.text((120, 160), subtitle, font=font(29), fill=MUTED)
    image = Image.open(raw).convert('RGB')
    image.thumbnail((1060, 2110), Image.Resampling.LANCZOS)
    x = (canvas.width - image.width) // 2
    y = 245 + (2110 - image.height) // 2
    draw.rectangle((x - 2, y - 2, x + image.width + 1, y + image.height + 1), outline=LINE, width=2)
    canvas.paste(image, (x, y))
    return canvas


def feature_image(cat_path):
    canvas = Image.new('RGB', (1024, 500), BACKGROUND)
    draw = ImageDraw.Draw(canvas)
    draw.ellipse((650, 56, 1030, 436), fill='#EEF4EE')
    draw.text((74, 78), 'GalliPick', font=font(79, bold=True), fill=INK)
    draw.text((74, 214), '사진과 동영상을', font=font(40, bold=True), fill=INK)
    draw.text((74, 270), '한곳에서 안전하게 정리', font=font(40, bold=True), fill=INK)
    draw.text((75, 383), '파일 정렬  ·  중복 검사  ·  휴지통', font=font(25), fill=MUTED)
    cat = Image.open(cat_path).convert('RGBA')
    cat.thumbnail((360, 360), Image.Resampling.LANCZOS)
    canvas.paste(cat, (680 + (310 - cat.width) // 2, 110 + (310 - cat.height) // 2), cat)
    return canvas


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--captures', type=Path, required=True)
    parser.add_argument('--cat', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--source-run', default='')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {'app': 'GalliPick', 'locale': 'ko-KR', 'source_commit': args.source_commit,
                'source_run': args.source_run, 'created_utc': datetime.now(timezone.utc).isoformat(),
                'provenance': 'Genuine Android emulator UI screenshots. Test media, not a user gallery.',
                'screenshots': [], 'feature_graphic': {}}
    for target, name, title, subtitle in SCREENS:
        candidates = list(args.captures.rglob('*-' + name + '.png'))
        if len(candidates) != 1:
            raise ValueError(f'Expected one screenshot named {name}, got {len(candidates)}')
        path = args.output / target
        screenshot_image(candidates[0], title, subtitle).save(path, format='PNG', optimize=True)
        manifest['screenshots'].append({'file': target, 'source': candidates[0].name,
                                         'dimensions': [1350, 2400], 'sha256': sha256(path)})
    graphic = args.output / 'feature-graphic.png'
    feature_image(args.cat).save(graphic, format='PNG', optimize=True)
    manifest['feature_graphic'] = {'file': graphic.name, 'dimensions': [1024, 500], 'sha256': sha256(graphic)}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Prepared 7 genuine screenshot composites + 1 feature graphic for:', args.source_commit)

if __name__ == '__main__':
    main()
