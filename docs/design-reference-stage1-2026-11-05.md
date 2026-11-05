# GalliPick 원본 시안 기반 정밀 리디자인 — 1단계 결과

## 기준 자료

- 디자인 원본: [02_디자인_이미지](https://drive.google.com/drive/u/0/folders/1vuIao6NhMJqTkHtAQeA_UqrpK3IiNrTx)
- 레이아웃 우선: `GalliPick_9screens_design_reference_20261105.png`
- 공통 스타일 우선: `GalliPick_화면별_디자인_가이드_20261104.png`
- 에셋 형태/캐릭터 참고: `GalliPick_아이콘_캐릭터_디자인_시안_20261104.png`
- 코드 기준: `main` / `c38ca544f163f97e0678d76cd70942ae92ace620` (0.2.1)
- 본 단계는 공통 토큰/기존 단일 에셋의 재배치이며, 각 화면의 상세 레이아웃이나 화면 수는 변경하지 않음

## 디자인 팔레트

| 토큰 | 레퍼런스 | 앱 적용 | 비고 |
|---|---|---|---|
| Primary | #1F6F54 | #1F6F54 | 주요 CTA·선택 아이콘 |
| Primary Light / Panel | #E6F4ED | #E6F4ED | 선택·정보 배경 |
| Secondary / Mint | #A4D4B4 | #A4D4B4 | 보조 녹색 |
| Accent / Peach | #FFE7A3 | #FFE7A3 | 보조 강조 |
| Background | #FFF9EF | #FFF9EF | 기본 화면 |
| Surface | #FFF3E2 | #FFF3E2 | 카드/팝업 |
| Text | #1F2937 | #1F2937 | 제목·기본 본문 |
| Muted text | #6B7280 | **#616874** | 원본 팔레트를 유지하되 크림/민트 위 4.5:1 대비를 위해 소폭 진하게 조정 |
| Outline | #E0E4D8 | #E0E4D8 | 미세 구분선 |

다크 모드는 원본 자료에 동등한 독립 다크 팔레트가 없어 기존 접근성 검증을 통과한 `DarkPalette`를 보존합니다.

## 타이포그래피·공통 규격

- 화면 제목 `headlineMedium`, 카드·페이지 제목 `titleLarge`: 24sp Bold
- 섹션 제목 `titleMedium`: 20sp Bold
- 본문 주요 `bodyLarge`: 16sp Medium
- 본문 보조 `bodyMedium`: 14sp Regular
- 라벨·캡션 `labelMedium`: 12sp Medium
- 큰 텍스트 모드에서 안전하게 최소 48dp 터치 타깃 유지
- 카드 모서리 16dp, 버튼 12dp, 카드 내부 14dp, 카드 간격 10dp, 내비 아이콘 24dp
- 불필요한 그림자·그라데이션 신규 추가 없음

## 기존 독립 이미지 에셋 정리

`app/src/main/res/drawable-nodpi/` 안의 고양이 PNG는 보존하고 화면의 의미에 따라 사용하는 공통 함수 `GalliIllustration(scene)`를 유지합니다.

| 역할 | 기존 실제 단일 PNG | 사용 기준 |
|---|---|---|
| 브랜드 마크 | `gp_photo.png` | 공통 앱 헤더의 작은 사진 마크 |
| 정리 시작 캐릭터 | `gp_cat_organize.png` | 홈 환영·정리 |
| 중복 찾기 캐릭터 | `gp_cat_search.png` | 검색/빈 결과 |
| 보호·안전 캐릭터 | `gp_cat_protect.png` | 보호 관련 화면 |
| 완료 캐릭터 | `gp_cat_complete.png` | 결과 완료 |
| 파일 없음 캐릭터 | `gp_cat_empty.png` | 빈 목록·권한 미허용 |

유틸리티 버튼/내비게이션은 원본 가이드의 **단색 선형 픽토그램**에 더 가깝도록 Material Outlined 아이콘을 기본으로 사용합니다. 종전 스타일의 개별 `gp_nav_*.png`, `gp_home.png` 등 원본 파일은 지우지 않고 복원 가능한 상태로 보존합니다.

현재 PNG가 원본 캐릭터의 형태와 **픽셀 단위로 동일하다는 의미는 아닙니다**. 원본 에셋 시트는 완전한 개별 투명 PNG가 아닌 디자인 구성 이미지이므로, 테두리 없는 고품질 독립 이미지 제작은 필요한 경우 별도 에셋 제작·검수 단계에서 진행해야 합니다. 본 단계에서는 배경이 포함된 시트 일부를 무리하게 잘라 넣거나 전체 이미지를 단색으로 틴팅하지 않습니다.

## 변경하지 않은 범위

- 현재 5개 하단 탭 구조, 탭 이동·뒤로가기 (원본 9개 시안과의 구조 차이는 **2단계에서** 결정)
- 홈·파일·필터·정리·중복 비교·휴지통·설정의 각 화면 순서와 로직
- SHA-256 중복 검사, 미디어 권한, 파일 선택 한도, 보호/즐겨찾기, Android 휴지통 승인
- Google Play 서명 키, `versionCode`, 내부 테스트 배포 트랙
- 비공개/프로덕션 배포나 AI 기반 유사 이미지 판정

## 검증 기준

- `ReferenceDesignTokensTest`: 레퍼런스 팔레트·간격·48dp 터치 영역
- `AppThemeContrastTest`: 크림·민트·다크 모드의 본문 대비
- `GalliDesignSystemTest`: 타이포그래피 24/20/16/14/12sp, 브랜드 마크, 라이트/다크 선형 아이콘 가시성, 5개 탭 접근성
- `Android verification`: 단위 테스트, Lint, 디버그 빌드
- `Android device tests`: 에뮬레이터 전체 UI 검사
- `Adaptive UI verification and native captures`: 휴대폰 세로/가로/큰 글씨 160%, 태블릿 세로/가로의 실제 화면 캡처

검증 성공 이후에만 `main` 병합. 병합 후 자동 내부 테스트 정책을 변경하지 않음. 실제 Play Console 게시 상태는 별도로 확인하지 않음.
