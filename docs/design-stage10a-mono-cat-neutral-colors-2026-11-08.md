# GalliPick 10-A — Mono Cat neutral color system

> 기준: 2026-11-08 · `main` 8fe960be · Play closed alpha v0.2.8(code 19) 유지

## 작업 목적

기존 아이보리/민트/녹색 중심 색상만 뉴트럴 그레이·화이트·차콜로 교체하고,
기능 아이콘/고양이 PNG·내비게이션 경로·미디어 데이터 처리 정책을 유지한다.

## 시맨틱 팔레트

| 토큰 | Light | Dark | 용도 |
| --- | --- | --- | --- |
| background | `#F7F8FA` | `#111315` | 화면 바탕 |
| surface | `#FFFFFF` | `#1C1F22` | 카드·모달 |
| panel | `#ECEEF1` | `#292D31` | 비강조 영역 |
| ink | `#1C1E21` | `#F5F6F7` | 본문·헤더 |
| muted | `#5D6269` | `#B1B6BD` | 보조 텍스트 |
| line | `#DDE1E5` | `#3B4046` | 테두리·구분선 |
| primary | `#23262B` | `#E7E9EC` | 주요 CTA·선택된 내비게이션 |
| onPrimary | `#FFFFFF` | `#17191C` | 주요 CTA 내용 |
| selectedContainer | `#ECEEF1` | `#292D31` | 선택된 필터/탭/내비게이션 |
| iconContainer | `#E8EAED` | `#34383D` | 작은 기능 아이콘의 중성 배경 |

`Mint`·`Peach` 같은 색상명 대신 사용 목적이 드러나는
`SelectedContainer`와 `IconContainer`를 제공한다.
카테고리 아이콘과 빈 화면의 배경에는 `IconContainer`, 선택 상태에는
`SelectedContainer`를 사용한다. Android Material3 스킴의
`primaryContainer`, `tertiaryContainer`, `surfaceContainer*`, `outline`도 함께 변경한다.

## 수정 대상

- `app/src/main/java/com/hebi/gallipick/ui/AppTheme.kt`: Light/Dark 시맨틱 색상, Material3 매핑
- `AppUi.kt`: 내비게이션 선택, 빈 화면 보조 아이콘
- `OverviewScreens.kt`: 공통 surface 및 카테고리 아이콘 중성화
- `FileToolbar.kt`: 필터 선택 배경
- `FolderStorageScreen.kt`: 폴더 분석 아이콘 배경
- `DuplicatesScreen.kt`: 보존/보호 표시 아이콘 배경
- `BrandAssets.kt`: 선택된 탭의 아이콘 텍스트 색상을 `Primary`로 통일
- JVM: `ReferenceDesignTokensTest.kt`, `AppThemeContrastTest.kt`
- Android: `GalliDesignSystemTest.kt`의 실제 색상 픽셀 기대값

## 변경하지 않는 항목

- 고양이 일러스트와 홈 4개 PNG·앱 런처 아이콘. 그림체 및 아이콘 색상 정비는 10-B~10-D.
- `values/colors.xml`의 `launcher_background`는 **앱 아이콘 자산**이므로 10-A에서 제외한다.
- 사진/영상 접근, 중복 선택, 보호·즐겨찾기, Room 캐시, Android 휴지통 이동·복원, 신규 기능.
- 앱 ID, `versionCode` 19, 서명키, Play Store listing 및 test track.

## 검증

1. `Android verification` (JVM/Android lint/debug build) 성공.
2. `Android device tests` (Android 계측 테스트) 성공.
3. Light/Dark 본문/보조텍스트/버튼/시맨틱 배경의 4.5:1 이상 대비 테스트.
4. 테마별 선택 아이콘, 4개 탭, 공통 버튼의 48dp 터치 타깃 회귀 테스트.
5. 전체 Native 5환경 × 2테마 캡처·시안 대비 상세 시각 검수는 10-E에서 재확인.
6. 검증 완료 전 `main`에 병합하지 않는다. `[deploy-closed]` 마커와 비공개 테스트 재배포를 실행하지 않는다.

## 다음 단계

- **10-B:** 홈 4개 바로가기 PNG 및 헤더 고양이 로고 전면 교체.
- **10-C:** 하단 탭·파일·정리·비교·설정 아이콘 전체 아웃라인 통일.
- **10-D:** 고양이 5종 캐릭터와 새 컬러의 시각적 조화 보정. 런처/스토어 자산 변경은 별도 범위.
- **10-E:** 5개 화면 환경 × 라이트/다크, 캡처 비교, 실제 Android QA, 대비 및 기존 기능 회귀.

## 기획 원본

- Google Drive: https://docs.google.com/document/d/1Rt383Sg3adjLDP0V2cTbx_Fl27SKxUHWQCIExWDDfjY/edit
- Stage 9-G 캡처: https://drive.google.com/drive/folders/1adiuPVd5-bIEAHEaeqSytwfJU_9THuHv
