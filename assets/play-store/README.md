# GalliPick Google Play preview assets

## Current Korean store visuals — 2026-11-05

`ko-KR/` contains seven 1350 × 2400 RGB PNG screenshots and a 1024 × 500 RGB PNG feature graphic, plus the original 512 × 512 store icon. The screenshot names follow the intended viewing order:

1. `01-home.png` — home overview and local storage information
2. `02-files.png` — file browsing
3. `03-filters.png` — file filter dialog
4. `04-duplicates.png` — verified exact-duplicate groups
5. `05-comparison.png` — choose which matching file to keep
6. `06-cleanup.png` — potential cleanup categories
7. `07-dark-mode.png` — theme settings

All screenshots use actual Android API 36 instrumentation captures from [native verification run 41947414464](https://github.com/hebi10/GalliPick/actions/runs/41947414464) at commit `aa695aeea24f395b8802cf3202de3916d45fc3f9`. Each is framed against a cream background with a short caption; the UI contents are not generated or altered. Test/example media and storage figures are not a user's personal files. This is not a claim that every screen is visible in the currently published Play version.

The feature graphic is a separate marketing composition using the newly integrated GalliPick cat illustration. It avoids promising AI, similar-image detection, automatic deletion or immediate storage reclamation. The Play icon was deliberately left unchanged.

The checksums and provenance are in `ko-KR/manifest.json`. Rebuild the store images from genuine native captures with `scripts/design/package_store_assets.py`. Validate and upload these files only through `scripts/release/play_listing_images.py`. The workflow `.github/workflows/play-listing-images.yml` reuses the existing GalliPick Play authentication and updates only the `com.hebi.gallipick` `ko-KR` phone screenshots and feature graphic after the merged asset change on `main`.

**Submission and publication are separate**: passing image validation or a successful API edit commit does not prove that Google has approved or published the new listing assets. Check Play Console's publishing overview after the workflow run.
