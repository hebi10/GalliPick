# GalliPick Play automation implementation plan

**Goal:** Build verified fixed-key APK/AAB artifacts in GitHub Actions and submit AAB updates to the existing closed testing track.

**Architecture:** Keep existing signing and artifact validators. A dedicated main-only/manual or main-ancestor version-tag workflow runs tests, builds both artifacts, and uses Google authentication plus a small Android Publisher client. Play credentials and the exact closed track are configured separately; no credentials enter repository files or artifacts.

**Scope:** `com.hebi.gallipick` only. No production/open track writes, tester edits, store listing changes, new signing keys, or version rewrites. Each release must increment the source versionCode. Existing public APK release remains available.

## Tasks

- [ ] Add a tested Android Publisher client: inspect existing tracks/bundles/APKs; reject an unconfigured/invalid or production/open/internal target; reject reused version codes before upload; verify upload hash/version; update only the selected closed track; validate and commit with `ERROR_IF_IN_REVIEW`; verify committed track via a fresh edit. Never retry an ambiguous commit or fall back to unsubmitted review changes.
- [ ] Add Actions workflow with `v*` tag automation and manual `check`, `build`, `deploy`. Reuse fixed signing and validators; exact source verification; main ancestry; serialized Play operations; scoped credentials; checked artifacts; cleanup. Support keyless OIDC configuration or a repository service-account JSON secret. Build-only works without Play credentials.
- [ ] Document exact setup, permissions, triggers, artifact links, review limitations and current account blockers. Test release client and validate workflow syntax. Obtain independent review.
- [ ] Push/merge implementation and run a concrete configuration check. If account authorization is missing, leave a precise actionable handoff after finishing all independent work.

## Review focus

Existing uploads including draft/internal versions count as used; unknown/custom closed track IDs require explicit selection; concurrent console edits invalidate API edits; commits may be accepted even when a connection times out; API acceptance is distinct from approved tester availability. Failed authentication/signing must stop without generating replacement keys or publishing an unchecked artifact.

## Decisions

Use version-tag deployment rather than deploying every source push. This preserves explicit versionCode increments and avoids uploading unchanged versions. Default manual mode builds artifacts. Deployment is limited to the configured existing closed track; no automatic track creation.
