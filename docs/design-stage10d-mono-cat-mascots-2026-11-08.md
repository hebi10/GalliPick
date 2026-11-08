# GalliPick 10-D — Mono Cat 캐릭터 및 브랜드 자산 정리

- 기준: 2026-11-08 · 10-C 기준 커밋 `48330b7a`
- 범위: 기존 고양이 씬 5종의 색상·실루엣 정돈, 그린/민트 장식 축소, 라이트·다크 테마 조화
- 10-A의 뉴트럴 팔레트와 10-B 헤더 마크, 10-C 공통 기능 아이콘은 그대로 유지
- 다크 모드는 글자와 고양이 외곽선 간 대비가 약한 영역에 **얇은 중성 회색 실루엣 아웃라인**을 보강한 전용 PNG 5종 사용. 그림자나 그라데이션이 아닌 4px 고정 윤곽을 적용한다.
- v0.2.8 (code 19) / 패키지 / 권한 / 파일 처리 / Google Play 배포는 변경하지 않음

## 신규 캐릭터 자산
| 기존 PNG (보존) | 신규 PNG | 역할 |
| --- | --- | --- |
| `gp_cat_organize.png` | `gp_cat_organize_mono.png` | 홈 환영·정리·설정 |
| `gp_cat_search.png` | `gp_cat_search_mono.png` | 사진 분석·검색 |
| `gp_cat_protect.png` | `gp_cat_protect_mono.png` | 보호 폴더 |
| `gp_cat_complete.png` | `gp_cat_complete_mono.png` | 완료·결과 |
| `gp_cat_empty.png` | `gp_cat_empty_mono.png` | 비어 있는 상태 |

모두 640×640px, RGBA 투명 배경으로 준비. 각 포즈는 폴더, 돋보기, 체크 방패, 완료 동작, 잠자는 고양이로 역할을 구분한다. 모자를 회색으로, 주변 녹색 바닥을 제거한 신규 일러스트이며, 이전 그림의 **픽셀 단위 색상 수정본이 아니라 별도 제작한 리디자인 에셋**임을 명시한다. 생성형 이미지 기반의 시안이므로 작은 해상도/실기기 품질·시각적 일관성은 10-E에서 최종 검수한다.

## 적용 코드
- `BrandAssets.kt`: `GalliScene`과 `IsGalliDark`로 라이트/다크 PNG 매핑. 장식 이미지는 중복 음성 안내 없음.
- `OverviewScreens.kt`: 정리 화면에서 PNG 직접 참조를 `GalliIllustration(GalliScene.ORGANIZE)`로 통합.
- `PhotoAnalysisScreen.kt`: 사진 분석 화면의 직접 PNG 참조를 `GalliIllustration(GalliScene.SEARCH)`로 통합.
- `GalliBrandMark`의 단색 고양이 심벌은 10-B 리소스를 유지해 헤더 34dp에서 동작. 런처/스토어 대표 이미지는 변경하지 않는다.
- 기존 `gp_cat_*.png` 5개는 삭제하지 않으며 비교/롤백/출처 확인용으로 저장소에 유지한다.

## 원본 자산 추적 (Git blob SHA)
- organize `93985310179d5160b7de470047dbb00f76fa2f0e`
- search `56442520a77bfddd5829a20f32498106782fb0cb`
- protect `0423438abc54edf8e1c57c9e0870d64b1f37e91e`
- complete `e10212a949c329d3b9675b4ceef293a19484f1`
- empty `00cb538f9845d3158cb47ae26780ab8cd4078355`

## 신규 에셋 SHA-256
- organize `3ad828186c8698eb75609ed4a32766ab14bdf0a0c09847d2fbfdff0050f34c84`
- search `2a477e292453e6f74da40774dfcf6c4e779ce00e2eb4bc5caa72774f72d6f026`
- protect `f5990663b676c74268319e5a35fe490266156e3e813958c387d06154ac031a84`
- complete `1f7a1aee9b16f05fa62a39a3744ad6352798f641dade1cacf067e654eb6c383c`
- empty `7555b4d8bb1d16189019b8dd8d584162ec21b5fce90550926f00051aca3b1e97`

## 다크 테마 자산 SHA-256
- organize `095fd52878b346c758b4881bcb826c1749790dbe116d9b889c5dfdb8a1ce59e8`
- search `71275a091088f7e66bb8eec4f814a48a779595944da88965a39d3b9c1a71fa3f`
- protect `ee5adadf969fc306a3c68a71e538eff9c6754a74f59c3e6c528d866b8f253d0d`
- complete `2d94f87fd550879b9b60bfd7e5b9100ff9c73b01c633f7c65b5d026dc7dbb973`
- empty `1834e8fb2ca64bd747d0ee5746e8537aa8902cb0a437aa7bfe8cf91e611646eb`

## 제작 및 보관
새 이미지는 2026-11-08 이미지 생성 도구로 제작한 Mono Cat 대체 시안을 5개 장면별로 분리, 투명 여백 640px 정렬, SHA-256 및 디자인 비교판으로 검증했다. 기존 이미지 원본은 프로젝트 저장소가 출처이며, 원래 제작자의 권리/라이선스 문서는 별도 확인이 필요하다.

- Google Drive 원본·미리보기·제작 manifest: https://drive.google.com/drive/folders/1dv4m-T8YfU8xGHYMGxCQd5KQs9s0-FnH
- 전체 기획 문서: https://docs.google.com/document/d/1Rt383Sg3adjLDP0V2cTbx_Fl27SKxUHWQCIExWDDfjY/edit

## 검증
1. `Android verification` — JVM 테스트, Lint, 디버그 APK 빌드
2. `Android device tests` — 5장 파일의 압축·투명도·단색화(녹색 축소) 및 전체 계측 테스트
3. 10-E — 네이티브 화면 5환경 × 2테마 캡처·시각 비교, 다크 배경에서의 윤곽/컬러 가독성, 큰 글씨·좁은 화면·태블릿 확인
4. `main` 병합은 최종 같은 HEAD의 Android 기본/기기 CI 2종 성공 후. Play 비공개 테스트 배포는 별도 승인 필요.

## 남은 작업
- 10-E 전체 화면 캡처 및 Light/Dark 시각·접근성·핵심 기능 회귀 QA
- 실기기(삼성/폴더블) 시각 검증과 필요 시 캐릭터 미세 보정
- 앱 런처/Play 스토어 브랜드 자산 교체는 10-E 평가 이후 별도 결정
