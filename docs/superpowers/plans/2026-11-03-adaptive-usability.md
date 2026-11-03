# Adaptive usability implementation plan

> **For agentic workers:** Use focused implementation tasks and independent review. Track completed work below.

**Goal:** Make duplicate cleanup decisions and file navigation clear on phones and tablets.
**Architecture:** Retain the current Compose callbacks and domain safety policies. Adjust layout constraints and presentation in existing UI files; native instrumentation provides regression and screenshot evidence.
**Tech Stack:** Kotlin, Jetpack Compose, Android instrumentation, GitHub Actions.
**Spec:** docs/superpowers/specs/2026-11-03-adaptive-usability-design.md

## Global Constraints
- Preserve mint/cream branding, rounded icons, offline operation and Android trash confirmation.
- No new dependencies, no automatic deletion, no unrelated features.
- All UI must support narrow/short windows and large text through reachable scrolling/actions.
- Keep keeper changes distinct from preview-target changes and preserve protection/favorite exclusions.

## Review Focus
- Two-file duplicate groups: keeper and candidate should appear promptly, with distinct markers.
- Groups with more than two files: target switching must not show the same file twice or change keeper.
- Short windows and keyboard: filter final fields and apply remain reachable without overlap.
- Tablet file view: no empty detail pane; pane can be closed after opening.
- Large font: controls must wrap or scroll instead of clipping.

## Tasks
1. Duplicate result hierarchy and recommendation evidence: DuplicatesScreen.kt, DuplicateRoles.kt and their tests. Keep public callbacks unchanged. Add meaningful marker/scope/compact-scan regressions before code.
2. Comparison layout and decision labels: DuplicateCompare.kt and adaptive comparison tests. Consume DuplicateRoles.reason(item); preserve its current API and panel semantics. Verify stable targets, short-window scrolling and protection behavior.
3. Filter adaptive form: FilterDialog.kt and focused instrumentation. Separate weighted scroll body/footer, two-column wide layout, explicit advanced narrow section, consistent unit labels/help. Preserve parse semantics and filter state.
4. Home/cleanup/settings: OverviewScreens.kt and focused instrumentation. Compact home hero, primary actions first, responsive columns, compact zero cards, readable settings width.
5. File browsing/integration: FilesScreen.kt, FileToolbar.kt and related tests. Show closable side pane only after selection, align select-all with list count, retain single-line toolbar.
6. Verify: run local available checks, CI unit/lint/build and native regression tests, capture all three profiles, inspect outputs and obtain independent code/visual review. Fix findings, commit and integrate approved changes to repository.

## Progress
- [x] Scope accepted from user's request to implement the reviewed feedback.
- [ ] Tasks 1–5 implemented and reviewed.
- [ ] CI and native captures verified.
- [ ] Repository integrated and results delivered.
