# GalliPick UI 리디자인 0단계 — 기준·화면 인벤토리·차이 분석

> 상태: **0단계 기준 조사 및 증거 수집**. UI 구현은 변경하지 않음.
> 소스 기준: `main` `4080fbee9749b85992fdaa682c5f91636d64d933`, GalliPick `0.1.9 / versionCode 10`, `com.hebi.gallipick`.
> 작성일: 2026-11-05 (Asia/Seoul).

## 디자인 원본의 우선순위

1. [최신 9개 화면 레퍼런스](https://drive.google.com/file/d/1147DiXv6uZoHm86eRV65n-bNA0yGrz4O/view) — 정보 구조와 화면의 주요 배치 우선.
2. [화면별 디자인 가이드](https://drive.google.com/file/d/19YfKDb4l7RBP-jDJRHvMGUIbDLwMeWTQ/view) — 카드·버튼·여백·타이포그래피.
3. [아이콘·캐릭터 가이드](https://drive.google.com/file/d/1s2FLKQI4nISAoALTX5pc3bkNAGlo-XyX/view) — 브랜드 에셋.
4. 현재 앱의 안전/접근성/기능 동작 — 시안과 충돌할 때 우선.

**결정:** 시안의 탭 수·액션이 현재 앱과 다를 수 있으므로 기존 **홈·파일·중복·정리·설정 5개 목적지**를 보존한다. 새 기능 추가는 이번 디자인 범위 밖이다.

## 9개 기준 화면의 정보·행동·현재 UI 차이

| ID | 화면 | 시안의 시각적 구성과 주동작 | 현재 코드와 구현 차이 | 작업 차수 | 코드 |
|---|---|---|---|---|---|
| R01 | 홈 | 캐릭터 헤더·저장공간 게이지·기능 바로가기 카드·하단 내비게이션 | 크림/녹색/고양이/저장공간은 구현. 바로가기는 중복·정리 두 항목 중심이며 카드 구성·위계가 시안과 다름 | 2차 | `OverviewScreens.kt`, `AppUi.kt` |
| R02 | 파일 목록 | 항상 보이는 검색·유형 칩·정렬·촘촘한 미디어 그리드·선택 표시 | 검색 입력은 팝업, 유형/조건/정렬은 가로 스크롤 도구 메뉴. 그리드/선택 구현됨 | 2차 | `FilesScreen.kt`, `FileToolbar.kt` |
| R03 | 필터 설정 | 날짜 범위/조건 칩/그룹별 입력/하단 적용 CTA가 분명한 전용 페이지 | 전체 다이얼로그+접히는 고급 조건. 최근 7/30일·날짜 입력·Compose 달력 있음. 레이아웃은 상이 | 2차 | `FilterDialog.kt` |
| R04 | 중복 파일 | 요약 카드, 결과 그룹 카드, 남길/정리 후보 표시 및 하단 행동 | SHA-256 완전 일치 검사/그룹 선택/남김·보호 구분 이미 구현. 정보 밀도·카드 구조 상이 | 3차 | `DuplicatesScreen.kt`, `DuplicateRoles.kt` |
| R05 | 중복 비교 | 사진 2개 동시 비교·각 파일 정보·남김/정리 후보·하단 행동 | 모바일에서는 A/B 탭, 넓은 화면에서 나란히 비교. 반응형 안전성과 영상 재생 제약 때문에 그대로 2열 강제하지 않음 | 3차 | `DuplicateCompare.kt` |
| R06 | 파일 상세 | 독립 상세 페이지처럼 큰 미리보기/메타정보/작업 아이콘·바 | 모바일 전체/가로 대응 상세 `Dialog`로 제공. 원본 열기/보호/앱 내 미리보기 있음. 시안의 새 공유·이동·영구삭제는 미구현 | 4차 | `MediaComponents.kt`, `MediaPreview.kt` |
| R07 | 휴지통 | 파일 썸네일 목록, 상태 필터와 하단 복원/영구 삭제 | 실제는 Android 관리 휴지통과 복원·선택. 목록/확인 모달 존재, 영구 삭제 없음(추가 금지), 시각적 구조 상이 | 4차 | `TrashScreen.kt`, `TrashReviewDialog.kt` |
| R08 | 설정 | 간결한 단일 목록, 테마 토글·보호 폴더·정리 알림/분석 등 | 테마 라디오·미디어 접근·휴지통·보호 폴더·캐시·개인정보를 카드로 제공. 알림/백업 설정은 없음(추가 금지) | 5차 | `OverviewScreens.kt` |
| R09 | 정리 완료 | 고양이 축하 일러스트, 완료 수·용량, 홈/다음 작업 버튼 | **독립 결과 화면 없음**. `MainActivity.kt`의 Android 승인 후 재조회 결과를 상단 메시지로 표시. 사용량 확보 수치와 휴지통 이동 용량을 혼동하지 않는 별도 결과 UI 필요 | 5차 | `MainActivity.kt`, `AppUi.kt` |

## 현재 앱 UI 인벤토리

### 목적지(5개)
- `Destination.HOME` → `HomeScreen` (`OverviewScreens.kt`)
- `Destination.FILES` → `FilesScreen` (`FilesScreen.kt`)
- `Destination.DUPLICATES` → `DuplicatesScreen` (`DuplicatesScreen.kt`)
- `Destination.CLEANUP` → `CleanupScreen` (`OverviewScreens.kt`)
- `Destination.SETTINGS` → `SettingsScreen` (`OverviewScreens.kt`)
- 부속 화면: `showTrash` → `TrashScreen` (설정에서 진입; 6번째 하단 탭으로 추가하지 않음)

### 상세/모달(10개 유형 이상)
| 구분 | 실제 UI 컴포넌트 | 파일 | 검수 포인트 |
|---|---|---|---|
| 파일 검색 | `DropdownMenu` + `OutlinedTextField` | `FileToolbar.kt` | 검색 열림/입력/키보드 |
| 유형·정렬·조건 메뉴 | `DropdownMenu`, `DropdownMenuItem` | `FileToolbar.kt` | 선택/해제/길고 큰 글씨 |
| 상세 필터 | `FilterDialog`, `FilterDialogContent` | `FilterDialog.kt` | 스크롤·하단 CTA·입력 검증 |
| 날짜 | Material3 `DatePicker` + 맞춤 `Dialog` | `FilterDialog.kt` | 가로 짧은 화면 입력 모드 |
| 필터 저장 | `SavePresetDialog` | `FilesScreen.kt` | 이름 입력·적용·취소 |
| 파일 상세 | `MediaDetailDialog` / `MediaDetail` | `MediaComponents.kt` | 미리보기/보호/폴더 |
| 사진·영상 | `MediaPreviewDialog`, `PhotoPreview`, `VideoPreview` | `MediaPreview.kt` | 확대/재생/오류/종료 |
| 중복 비교 | `DuplicateCompareDialog` | `DuplicateCompare.kt` | A/B, 태블릿 양열, 후보 보호 |
| 비교 파일 메뉴 | `DropdownMenu` | `DuplicateCompare.kt` | 같은 파일 두 번 비교 방지 |
| 휴지통 이동/복원 검토 | `TrashReviewDialog` | `TrashReviewDialog.kt` | 이동/남김 탭·용량·후보 |
| 캐시 초기화 | `ClearAnalysisCacheDialog` | `OverviewScreens.kt` | 원본 유지 안내·취소 |
| 메시지/안내 | `state.message`, `EmptyState` | `AppUi.kt` | 성공·취소·일부 완료·실패 |

### Android 시스템 UI(앱에서 리디자인 불가)
- 갤러리/사진·동영상 접근 권한 요청 및 범위 설정
- `MediaStore.createTrashRequest`의 시스템 휴지통 이동·복원 승인창
- 외부 앱 파일 열기용 Android 선택 UI

## 전역 상태·화면 검수 목록

| 상태 | 현재 경로 | 테스트에서 필요한 장면 |
|---|---|---|
| 권한 없음·부분 허용 | `AppUi.kt`, `HomeScreen`, `FilesScreen` | 권한 안내/재진입 |
| 목록 로딩·조회 실패·빈 결과 | `FilesScreen.kt`, `EmptyState` | 오류 vs 0건 구분 |
| 중복 검사 대기·진행·일시정지·완료·실패 | `DuplicatesScreen.kt` | 진행률/재시도/완료 |
| 남길 파일·보호·즐겨찾기·후보 | `DuplicateRoles.kt`, `DuplicatesScreen.kt` | 후보 선택 안전 |
| 휴지통 대상·남길 사본·복원·선택 제한 | `TrashReviewDialog.kt`, `TrashScreen.kt` | 승인 전후, Android 취소 |
| 파일 미리보기 성공/오류, 영상 재생 | `MediaPreview.kt` | 작은 화면/화면 회전 |
| 캐시 초기화·필터 저장 | `OverviewScreens.kt`, `FilesScreen.kt` | 확인/취소 |
| 실제 완료·부분 완료·취소·확인 불가 | `MainActivity.kt` | 결과 UI와 실제 재조회 일치 |
| 라이트·다크·글씨 160% | `AppTheme.kt`, 공통 컴포넌트 | 명암비·줄바꿈·터치 타깃 |

## 시안의 미구현 기능 및 처리 결정

- **영구 삭제:** 구현하지 않음. Android 관리 휴지통의 복원 중심 설계를 유지.
- **자동 백업·정리 알림:** 구현하지 않음. 설정 목록에 작동하지 않는 토글 추가 금지.
- **유사 이미지 감지/AI 분석:** 구현하지 않음. 기존 SHA-256 완전 일치 중복 검사만 명시.
- **정리 완료의 확보 저장공간:** 휴지통 이동은 즉시 여유 용량 확보와 같지 않음. 실제로 확인된 이동 건수/용량과 저장공간 확보량을 별도 구분.
- **파일 공유/이동/영구 삭제 아이콘:** 현재 구현에 없는 작업 버튼을 장식용으로 추가 금지. 원본 열기·앱 미리보기·보호 등 실제 사용 가능한 작업만 표시.
- **하단 메뉴 4개:** 기존 5개 탭과 경로를 보존.

## 기존 0.1.9 화면 증거 및 새 캡처 규약

- 기준 소스: `4080fbee9749b85992fdaa682c5f91636d64d933`.
- 기존 GitHub Actions `Adaptive UI verification and native captures`는 예제 미디어만 사용. 새 브랜치 `codex/ui-captures-redesign-stage0-baseline-20261105`는 **문서만 변경**하여 동일 앱 코드를 실제 기기 에뮬레이터로 다시 캡처한다.
- 프로필: `phone-portrait`, `phone-landscape`, `phone-large-text` (160%), `tablet-portrait`, `tablet-landscape`.
- 각 프로필: 기존 메인 흐름 11장(`captures/`) + 세부/모달 11장(`modal-captures/`) = 22장. 5 프로필 전체 **총 110장 기대**.
- 디렉터리: `baseline-v0.1.9/<profile>/primary/` 및 `baseline-v0.1.9/<profile>/modal/`.
- 캡처 파일 규칙(검수용): `GP-v019-<profile>-<screen-id>-<state>.png`. 예: `GP-v019-phone-portrait-R02-files.png`. 원본 자동 캡처 이름도 함께 보존해 출처 확인.
- 시안 파일 규칙: `reference-R01-home.png` ... `reference-R09-result.png` (원본 9화면 합성 이미지 자체는 별도 참조하며, 임의 자르기로 화질을 변경하지 않음).
- 비교 검수 규칙: `design-diff-R<NN>-<profile>.md` — 구현/부분/미구현, 결함, 다음 PR, 실제 캡처 경로, 검수자.
- 캡처 성공은 **그 장면에서의 자동 동작/레이아웃 확인**이지 실제 삼성 기기 점검이나 모든 빈 상태/시스템 창 확인을 대신하지 않음.
- 기존 실행: [0.1.9 main Android 검증](https://github.com/hebi10/GalliPick/actions/runs/41966866562), [기기 테스트](https://github.com/hebi10/GalliPick/actions/runs/41966866549).

## 이후 PR 분리·검증 완료 조건

| 단계 | 제안 브랜치/PR 주제 | 코드 범위 | 단계별 제출물 |
|---|---|---|---|
| 1 | `codex/gallipick-design-system` | `AppTheme.kt`, `AppUi.kt`, `BrandAssets.kt` | 공통 토큰/컴포넌트 캡처 |
| 2 | `codex/gallipick-redesign-home-files-filter` | `OverviewScreens.kt`, `FilesScreen.kt`, `FileToolbar.kt`, `FilterDialog.kt` | R01~03 시안 비교 |
| 3 | `codex/gallipick-redesign-cleanup-duplicates` | `OverviewScreens.kt`, `DuplicatesScreen.kt`, `DuplicateCompare.kt` | R04~05 및 정리 후보 |
| 4 | `codex/gallipick-redesign-details-trash` | `MediaComponents.kt`, `MediaPreview.kt`, `TrashScreen.kt`, `TrashReviewDialog.kt` | R06~07 및 확인 모달 |
| 5 | `codex/gallipick-redesign-settings-results` | `OverviewScreens.kt`, `AppUi.kt`, `MainActivity.kt`의 상태 UI만 | R08~09/상태별 결과 |
| 6 | `codex/ui-captures-redesign-final` | UI 테스트·캡처·접근성 | 5개 화면 프로필 비교·회귀 |
| 7 | `codex/gallipick-redesign-release` | 이미지·릴리스 메타데이터 | 새 버전 code 상승, 내부 테스트만 |

**완료 정의(0단계):** 9개 시안 차이표, 목적지·상세·모달 인벤토리, 기능 제외 정책, 0.1.9 기준 에뮬레이터 원본 5개 환경 캡처, 스크린샷/PR 규약을 확보·보관한 뒤 0단계를 완료로 표기한다. 다음 단계는 실제 디자인 토큰 개선이며 이 문서에서는 UI 소스 코드를 바꾸지 않는다.
