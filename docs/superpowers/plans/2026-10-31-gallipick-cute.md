# GalliPick Cute Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** GalliPick에 픽이 마스코트와 생성 아이콘을 적용하고 모든 화면을 둥근 민트·크림 콘셉트로 통일한다.

**Architecture:** 기존 Compose 화면·ViewModel·데이터 계약을 유지하고 공유 테마와 이미지 표시 계층을 교체한다. 공통 UI, 개별 화면, 이미지 자산을 파일 소유권으로 나누어 병렬 작업하고 통합 후 기존 회귀 및 실제 화면을 검증한다.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Material Rounded icons, 투명 PNG, Android adaptive icon, 기존 Gradle/JUnit/Android instrumentation.

**Spec:** [2026-10-31-gallipick-cute-design.md](../specs/2026-10-31-gallipick-cute-design.md)

## Global Constraints

- minSdk 30 / target·compileSdk 36. 로컬 처리 및 인터넷 권한 없음 정책 유지.
- 동작 변경 없이 화면과 자산만 변경. 보호·즐겨찾기·중복 생존 사본 검증·권한·시스템 휴지통 승인/복원 유지.
- 팔레트: 배경 `#FAF9F5`, Ink `#25483F`, Muted `#597267`, Line `#DCE6DF`, Panel `#EEF4EE`, Mint `#CDE6D8`, Peach `#F5D6BD`.
- 일반 텍스트 대비 4.5:1 이상. 작은 활성 설명은 Ink 또는 대비를 충족하는 더 진한 보조 텍스트색 사용.
- 주 카드·대화상자 곡률 16–24dp. 주요 터치 영역 48dp 이상과 선택/역할 의미 유지.
- 새 기능·의존성·데이터 계층 변경 없이 기존 UI 패턴 확장.

## Review Focus

- 작은 설명과 비활성 조작: 밝은 민트/패널에서 읽히고 조작 가능 여부가 구별되는지.
- 다섯 탐색 목적지: 생성 아이콘의 보이는 크기와 선택 표시, 라벨/접근성 의미가 일치하는지.
- 긴 한글·빈 데이터·일부 접근: 문구 잘림 없이 기존 안내와 스크롤을 유지하는지.
- 선택/보호/즐겨찾기: 둥근 배지나 색 변경이 삭제 제외·복원 의미를 흐리지 않는지.
- 이미지 축소와 adaptive 마스크: 투명 테두리·잡색·잘림 없이 픽이와 주요 아이콘이 구별되는지.

---

### Task 1: 생성 자산과 공통 테마

**Files:**
- Create: `app/src/main/res/drawable-nodpi/gp_mascot.png`와 `gp_home`, `gp_files`, `gp_duplicates`, `gp_cleanup`, `gp_settings`, `gp_photo`, `gp_video`, `gp_trash`, `gp_shield`, `gp_folder` PNG.
- Modify: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`.
- Modify: `app/src/main/java/com/hebi/gallipick/ui/AppUi.kt`; 필요한 이미지 helper는 같은 `ui` 패키지에 추가.

**Interfaces:**
- Consumes: 기존 `Destination`, `GalliPickTheme`, `ActionButton`, `IconAction`, `EmptyState` 호출 계약.
- Produces: 다섯 목적지와 의미별 PNG 리소스, 공유 색·모서리·버튼·탭 스타일. 기존 화면 호출은 동작을 유지한다.

- [x] 같은 콘셉트의 투명 PNG를 생성·추출하고 리소스 이름을 위 계약에 맞춘다.
- [x] 원래 색을 보존하는 명시적 크기 이미지 표시와 Material Rounded 조작 아이콘을 적용한다.
- [x] 공유 테마·헤더·버튼·탭·알림·빈 상태를 변경하고 선택 의미와 disabled 동작을 보존한다.
- [x] 민트 배경 adaptive 런처에 픽이를 배치하고 원형/둥근 사각 마스크, 투명 가장자리 및 탐색 아이콘 크기를 확인한다.
- [x] 텍스트 대비 계산과 실제 선택/비활성 상태를 확인한다. Muted의 4.5:1 미달 조합을 활성 본문에 사용하지 않는다.

### Task 2: 화면 전체에 콘셉트 적용

**Files:** `OverviewScreens.kt`, `FilesScreen.kt`, `DuplicatesScreen.kt`, `TrashScreen.kt`, `MediaComponents.kt`, `FilterDialog.kt` (모두 `app/src/main/java/com/hebi/gallipick/ui/`).

**Interfaces:**
- Consumes: Task 1의 공통 테마·아이콘 자산과 기존 화면 입력/콜백.
- Produces: 기능과 데이터 계약이 같은 홈·파일·중복·정리·설정·휴지통·필터·상세 화면.

- [x] 홈의 픽이/통계/정리 시작 카드를 구성하고 권한·일부 접근·로컬 분석 안내를 유지한다.
- [x] 목록·미리보기·상세·선택 배지·중복 그룹·휴지통에 둥근 형태와 일관된 색을 적용한다.
- [x] 필터 입력·칩·드롭다운·대화상자와 설정/보호 폴더 카드를 같은 콘셉트로 마무리한다.
- [x] 긴 한글·빈 상태·선택 의미를 캡처/코드로 검토한다. 일부 접근·보호·즐겨찾기·넓은 화면 분기 코드를 보존하며 실제 폴더블 검증은 별도 항목으로 남긴다.

### Task 3: 통합 검증과 전달

**Files:** 기존 `app/src/test/**`, `app/src/androidTest/java/com/hebi/gallipick/MediaIntegrationTest.kt`, `UiSmokeTest.kt`; 결과는 `docs/verification.md`에 별도 기록.

**Interfaces:**
- Consumes: 통합된 UI와 자산. 기존 테스트의 의미와 보호 정책은 변경하지 않는다.
- Produces: 이번 시각 변경에 대한 빌드·회귀 결과 및 실제 화면 검토 증거.

- [x] `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2` 실행. 기존 JVM 회귀 38개 실패/오류 0, lint 오류 0, debug 앱/기기 테스트 APK 빌드 성공 확인.
- [x] Android 16/API 36에서 기존 통합 검사 4개 실행: 실제 MediaStore 중복 검사, 생존 사본 외부 삭제 차단, 보호 설정 유지/후보 제외, PARTIAL 접근 재검증 중 목록 숨김.
- [x] UI smoke로 홈·파일·필터·중복·정리·설정 진입 확인, 홈/필터 캡처 확인. 라벨과 보호/휴지통 동작을 시각 변경 때문에 약화하지 않는다.
- [x] 캡처 및 런처에서 텍스트 대비, 선택/비활성 상태, 긴 한글, PNG 투명 가장자리, adaptive 마스크와 아이콘 구별을 검토한다.
- [x] `git diff --check`와 변경 범위 검토. 이번 실행 결과만 기록하고 기존 실기기 미검증 범위는 유지한다.
- [x] 검토 의견을 반영하고 최종 APK 및 변경 내용을 전달한다.
