# GalliPick 10-B — Mono Cat 홈 아이콘 및 헤더 로고

기준: `main` 0fc74e67 · 2026-11-08 · v0.2.8(code 19) 유지

## 목표
10-A의 화이트/차콜 컬러를 유지하고, 입체감·파스텔색·그라데이션 없는 균일한 평면형 홈 아이콘 4종과 독립 헤더 고양이 마크를 적용한다.

| 기존 홈 PNG | 새 리소스 | 의미 |
| --- | --- | --- |
| `gp_photo.png` | `drawable/gp_home_photo.xml` | 사진 겹침 및 정리 |
| `gp_duplicates.png` | `drawable/gp_home_duplicates.xml` | 겹친 사진과 비교 |
| `gp_video.png` | `drawable/gp_home_large_file.xml` | 문서 형태와 크기 지표 |
| `gp_old_files.png` | `drawable/gp_home_old_files.xml` | 폴더와 시계 |
| 헤더에서 재사용 중이던 `gp_photo.png` | `drawable/gp_brand_cat.xml` | 고양이 얼굴 독립 마크 |

- 64x64 뷰포트, 동일한 3.2px 중심의 둥근 획, 투명 배경. 실제 표시는 48/56dp 홈, 34dp 헤더.
- Compose `Icon`의 `Ink` 틴트로 라이트 #1C1E21 / 다크 #F5F6F7 동기화. 텍스트가 카드 역할과 접근성 이름을 담당하므로 아이콘 자체는 중복 낭독하지 않음.
- 고양이를 모든 기능 아이콘에 넣지 않고 헤더에서만 표현. 기존 고양이 5종 PNG는 10-D까지 그대로 보존.
- 기존 PNG는 삭제하지 않고 후속 자산 정리 단계까지 원본으로 보관. 기존 스토어·런처 이미지 변경 없음.

## 보존되는 동작
- 사진·영상 정리: 전체 미디어 필터로 파일 탭 이동.
- 중복 파일 찾기: 완전 중복 검사 화면.
- 용량 큰 파일: 100 MiB 이상 / 용량 정렬.
- 오래된 파일: 촬영일 1년 이전 / 오래된 순.
- 네 개 카드의 전체 클릭 영역, 48dp 이상 터치 영역, 좁은 화면 1열·보통 화면 2열·태블릿 2열 및 기존 헤더 로딩/새로고침 동작 유지.
- 미디어 접근, 휴지통/보호/삭제 경로, 앱 ID, versionCode, Play 배포 설정 변경 없음.

## 검증
1. `AdaptiveOverviewTest`: 5개 벡터 리소스 패키징과 오래된 파일의 필터 유지.
2. `GalliDesignSystemTest`: 헤더의 고양이 마크가 Light/Dark `Ink` 색으로 렌더되며 불투명 타일이 아닌지 확인.
3. 기존 `ReferenceHomeScreenTest`와 적응형 화면/기능 테스트 유지.
4. PR HEAD에서 `Android verification` 및 `Android device tests` 성공 후에만 `main` 병합. 전체 5환경 × 2테마 시각 캡처는 10-E 통합 단계에서 재검수.

## 후속
- 10-C 하단 탭/모든 기능 아이콘 통일
- 10-D 고양이 일러스트·런처 색상 정비(스토어 자산 별도 결정)
- 10-E 실제 기기별 Light/Dark 캡처·최종 회귀 테스트

기획 원본: https://docs.google.com/document/d/1Rt383Sg3adjLDP0V2cTbx_Fl27SKxUHWQCIExWDDfjY/edit
