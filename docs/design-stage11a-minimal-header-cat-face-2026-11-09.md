# GalliPick 11-A — 상단 헤더 고양이 얼굴 마크

- 기준: `main` 34c0c917 / 기존 Play 테스트 v0.2.9(code 20)
- 목적: 사용자가 선택한 고양이 얼굴만 있는 미니멀 시안을 상단 GalliPick 문구 옆에 적용
- 원본 기준 이미지: https://drive.google.com/file/d/1aEufrX_YvZBwYoCpzpT2_xYgCrGT26tp/view
- 라이트/다크 미리보기: https://drive.google.com/file/d/17u6n7F1fC3gX2GAkQgqhZR-J7NxNuvKM/view

## 변경 범위

- `app/src/main/res/drawable-nodpi/gp_brand_cat_face.png`: 승인된 고양이 얼굴에서 분리·보정한 투명 배경 192×192 리소스
- `BrandAssets.kt`의 `GalliBrandMark()`: 기존 단색 벡터의 tint 렌더링 대신, 새 아이콘 PNG를 `Image`로 렌더링하여 라이트/다크 양쪽에서 얼굴의 밝은 부분과 검은 표정을 유지
- `GalliAppHeader`의 34dp 표시 크기, 로고 위치, 로딩·새로고침, 키보드/스크롤/내비게이션 동작은 그대로 유지
- 원본 `gp_brand_cat.xml`, 홈 바로가기, 하단 탭 아이콘, 런처/Play 스토어 아이콘 및 브랜드 고양이 일러스트는 수정하거나 삭제하지 않음

## 검증

- `GalliDesignSystemTest`: 라이트·다크에서 눈·코·입 선 및 밝은 얼굴 영역을 확인; PNG의 투명 모서리 확인
- `AdaptiveOverviewTest`: 기존 홈 바로가기 벡터 4종 보존 및 신규 헤더 PNG 포맷/투명도 확인
- PR Android verification, Android device tests 및 10환경 캡처 워크플로 결과 확인 후에만 병합
- 이번 PR에는 `versionCode` 증가나 Google Play 신규 업로드 없음
