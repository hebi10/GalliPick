# Review Improvements Implementation Plan

> **For agentic workers:** Use subagent-driven-development or executing-plans to implement the scoped tasks with regression evidence.

**Goal:** Resolve the approved v0.1.1 review findings and improve everyday media cleanup.

**Architecture:** Preserve Compose/MVVM/Room and native MediaStore authority. Keep selections tied to file versions; separate catalog failures from empty results; share/coalesce analysis flows.

**Tech Stack:** Kotlin, Jetpack Compose, Room, WorkManager, Android ImageDecoder/VideoView; no added dependencies.

**Spec:** `docs/superpowers/specs/2026-11-01-review-improvements-design.md`

## Global Constraints

- Android 11+, target 36, local-only, no INTERNET permission.
- Existing rounded Piki brand and protection/last-copy safeguards remain.
- Maximum selection/request: 500. Filters clear selections; verified unchanged files retain selection on resume.
- Independent files may be implemented concurrently; a single owner runs Gradle after all edits settle.

## Review Focus

- Same PARTIAL access mode can represent different allowed files; never show cached revoked media.
- Selected content can change before refresh or deletion; compare selection-time fingerprint.
- Protected/favorite copies remain excluded even when user chooses a different keeper.
- Provider/volume errors must never appear as a successful empty scan.
- Player/decoder lifecycle and large images must not leak or exhaust memory.

### Task 1: Catalog reliability and analysis cost

Files: data readers/repository/database/worker; domain Models only if needed; data tests.
- [x] Add meaningful tests for volume cache epochs and failed snapshots before implementation.
- [x] Track getVersion per volume atomically with snapshot replacement; invalidate stale digests; propagate query failures.
- [x] Share catalog flows and coalesce digest-driven duplicate computation without slowing checkpoints.
- [x] Verify tests/build; review changes.

### Task 2: Selection and deletion lifecycle

Files: AppViewModel, MainActivity, selection policy + tests.
- [x] Add regressions for filter changes, unchanged/changed refreshed selections and 500/501 limits.
- [x] Keep selection-time MediaItems; hide all catalog data during access revalidation; restore only unchanged eligible items.
- [x] Clear selection for changed filters; cap single/all/recommended selections at 500; allow explicit per-group keeper.
- [x] Show scrollable names/folders in the confirmation dialog, preserve final system validation.
- [x] Verify regressions and existing tests; review.

### Task 3: Files and duplicate experience

Files: FilesScreen, DuplicatesScreen, TrashScreen, AppUi; UI tests.
Interfaces: `AppUiState.duplicateKeepers: Map<String,String>`, `vm.chooseDuplicateKeeper(group: DuplicateGroup, uri: String)`, selection limit 500.
- [x] Add quick filters and meaningful populated UI regression tests.
- [x] Hide empty selection bar, retain explicit grid state, label search accessibly.
- [x] Add permission entry point, keeper action and progress semantics to duplicate screen.
- [x] Cap trash selection at 500 with early feedback. Verify tests and screenshots.

### Task 4: Internal preview

Files: MediaComponents, new MediaPreview; relevant UI tests.
- [x] Implement bounded native photo decode with transform gestures and video playback via VideoView.
- [x] Keep existing external-open action; dispose/pause resources appropriately; show recoverable errors.
- [x] Verify populated photo/player UI and lifecycle behavior; record the remaining wide/physical-layout verification scope.

### Task 5: Integration and delivery

- [x] Run all unit tests, lint, debug+androidTest builds; inspect failures by root cause.
- [x] Independent review selection safety, version invalidation, failure propagation and player lifecycle; resolve material findings.
- [x] Record actual evidence/limitations, update version to 0.1.2 and README, commit completed work.

## Verification limits

Android 11/API 30 전체 22개와 JVM 59개 통과. 넓은 화면 실기기·폴더블/큰 글꼴 및 수만 파일 성능은 이번 환경에서 확인하지 못했으며 검증 기록에 별도로 명시했다. Android 16 소프트웨어 부팅 실패와 UTP 시작 단계 실패는 제품 테스트 통과로 계산하지 않았다.
