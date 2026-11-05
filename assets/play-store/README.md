# GalliPick Google Play preview assets

## Final redesign screenshots — 2026-11-05, stage 7

The seven ordered `ko-KR/01-home.png` through `07-comparison.png` store screenshots and the 1024 × 500 `feature-graphic.png` are regenerated from **verified, genuine Android API 36 screen captures**. They replace screenshots made before the full stage 1–6 redesign.

- **Capture source commit:** `2d3cf757a934d4588984b644ebe246ee64e9a37a` (navigation and dark utility icon corrections verified)
- **Capture workflow:** [Run #42023456154](https://github.com/hebi10/GalliPick/actions/runs/42023456154), phone-portrait profile (all five device profiles passed)
- **Store image renderer:** `scripts/design/package_store_assets.py`; only genuine on-device UI pixels are pasted within a cream marketing frame and caption, never re-generated as fake app UI
- **Source media:** Test-owned EXAMPLE fixtures, not user photos
- **Provenance and hashes:** `ko-KR/manifest.json`; `source_commit` and `source_run` identify the approved screenshot run

**Navigation-first viewing order:** 01 home, 02 files, 03 complete-duplicate scan, 04 cleanup, 05 dark settings, followed by 06 advanced filter dialog and 07 A/B duplicate comparison. The first five are real top-level Android screens with the complete five-tab bar; the last two intentionally show overlays and are not presented as independent navigation tabs. The feature graphic uses the checked-in GalliPick cat asset.

The actual Android screenshots are the only source of app UI pixels. AI-generated design-study boards are not app screenshots and must never be included in the Play uploads. The dark-mode solid-square icon defect was corrected before these images were regenerated. No similar-photo detection, automatic deletion, notification scheduling, or backup features are advertised.

### Publishing behavior

`.github/workflows/play-listing-images.yml` validates all image hashes, RGB dimensions, and the seven screenshot order, then uses the existing GalliPick Google Play authentication to submit **only** the `ko-KR` `phoneScreenshots` and `featureGraphic` types for `com.hebi.gallipick` when store assets change on `main`. It does not edit other apps, tracks, the store icon, or published binaries.

`.github/workflows/play-deploy.yml` separately prepares the fixed-key release AAB/APK and, for the intentional `[deploy-internal]` version-bump merge, targets only the internal testing track. Both Play workflows share a concurrency group to prevent concurrent Google Play edit sessions.

**API submission is not publication.** Successful GitHub Actions or edits do not guarantee store visibility or that testers can install the updated version. The user has requested that Play Console actual availability not be checked during this stage.

## Store icon updates

The former `ko-KR/app-icon.png` was a legacy photo-card icon and is intentionally removed to prevent accidental reuse. On a main-branch change to the icon or its generator, [the dedicated icon workflow](../../.github/workflows/play-store-icon.yml) builds and validates the single 32-bit 512 px image, then submits **only** the `com.hebi.gallipick` `ko-KR` `icon` image type. It does not touch screenshots, feature graphics, testing tracks, or other apps.

[2026-11-05 GalliPick icon update](https://github.com/hebi10/GalliPick/actions/runs/41964686350) completed: the Google Play API accepted the new `ko-KR` icon edit, and a new edit confirmed that one icon is present. Submission is not Google approval. Check the Play Console publishing overview for the final store-visible image.
