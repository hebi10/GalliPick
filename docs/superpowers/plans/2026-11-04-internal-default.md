# Internal testing default

**Goal:** Build verified APK/AAB releases and submit automatic version-tag releases to internal testing; closed testing is an explicit manual choice.

**Scope:** GalliPick only. Existing fixed signing, source checks, exact versionCode policy and WIF authentication remain. No app UI or behavior change, tester/listing changes, production/open deployment, new keys or automatic draft replacement. A fresh release version/code may be prepared for the first actual upload.

## Tasks

- [x] Publisher: allow exact `internal` and existing explicitly selected closed tracks; default CLI track to `internal`. Keep protocol validation, draft protection, duplicate-version protection and guarded commit/readback. Test internal target isolation, CLI default and closed compatibility.
- [x] Workflow: manual target `internal` (default) or `closed`; `v*` push always internal. Resolve track before credentials and only require `PLAY_CLOSED_TRACK` for closed checks/deployment. Preserve build-only operation without Play credentials, existing job gates and reports.
- [x] Update documentation and verify shell routing, actionlint and complete release-tool tests; obtain independent review; integrate and run internal `check`.
- [x] Confirm the first fresh-version internal release after updating the signing Secret with the recovered original key. The Secret update, successful check/build, existing code8 draft resolution, code9 upload, accepted API commit/fresh edit readback and Console internal tester availability are verified.

## Acceptance

- A tag cannot select the closed track.
- A manual internal check does not require a closed-track variable.
- A manual closed check uses only the explicitly configured existing closed track.
- Production, open and form-factor targets remain rejected.
- Verification distinguishes source/configuration success from signed builds and actual tester availability.

## Initial verification result — check #3 attempt 1

PR #8 merged to main `3299a9a00588c502360abb798fe58c12842f1855`; tested and merged trees match. All 72 release-tool tests, actionlint and shell routing checks pass. Android verification and all 80 device tests pass. Live internal check #3 (run `41790941768`) passed source/WIF/Play preflight and failed the unchanged signing recovery certificate pin. Build and deploy did not run. Original signing recovery, internal draft resolution and a fresh versionCode remain prerequisites for an actual release.

## Original key recovery

The original JKS has now been recovered from a private backup. Its existing certificate pin, private-key access and an actual signature roundtrip all pass. A corrected four-file recovery ZIP passes the existing restore path and an independent review. No new key or upload-key reset is needed. The user has now updated the GitHub Secret. Check #3 attempt2 and signed APK/AAB build #4 both pass. The existing code8 internal draft was released before the fresh code9 deployment. Private artifacts are not committed to this repository.

## First fresh-version release

Source `e2c37ae7c935060eeaf591ea5fc720b9b26ccbea` released 0.1.8/code9 without app behavior changes. Run `41801195257` completed all 7 jobs in 14m17s: 72 release-tool tests, 80 Android 16 device tests, fixed-key APK/AAB validation, upload version/hash match, accepted API commit and fresh edit readback. The downloaded report confirms committed/internal/code9 with API acceptance and track verification. Fresh Play Console inspection confirmed active/latest0.1.8, versionCode9 and internal tester availability on 2026-11-04. Version-tag releases remain internal-only; manual runs default to build/internal and closed testing is explicit. This run used manual deploy/internal. Code9 is already uploaded; the next new upload requires at least code10. API acceptance and Google review/tester availability remain distinct.
