# Internal testing default

**Goal:** Build verified APK/AAB releases and submit automatic version-tag releases to internal testing; closed testing is an explicit manual choice.

**Scope:** GalliPick only. Existing fixed signing, source checks, exact versionCode policy and WIF authentication remain. No app UI or version change, tester/listing changes, production/open deployment, new keys or automatic draft replacement.

## Tasks

- [x] Publisher: allow exact `internal` and existing explicitly selected closed tracks; default CLI track to `internal`. Keep protocol validation, draft protection, duplicate-version protection and guarded commit/readback. Test internal target isolation, CLI default and closed compatibility.
- [x] Workflow: manual target `internal` (default) or `closed`; `v*` push always internal. Resolve track before credentials and only require `PLAY_CLOSED_TRACK` for closed checks/deployment. Preserve build-only operation without Play credentials, existing job gates and reports.
- [ ] Update documentation and verify shell routing, actionlint and complete release-tool tests; obtain independent review; integrate and run internal `check`.
- [ ] Correct existing signing Secret with the original upload key and confirm an actual new-version release when the required recovery material is available. Current code8 is already uploaded, and the signing Secret pin mismatch remains a separate blocker.

## Acceptance

- A tag cannot select the closed track.
- A manual internal check does not require a closed-track variable.
- A manual closed check uses only the explicitly configured existing closed track.
- Production, open and form-factor targets remain rejected.
- Verification distinguishes source/configuration success from signed builds and actual tester availability.
