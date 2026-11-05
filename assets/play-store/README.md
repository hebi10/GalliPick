# GalliPick Google Play preview assets

## Current Korean store visuals — 2026-11-05

`ko-KR/` contains seven 1350 × 2400 RGB PNG screenshots and a 1024 × 500 RGB PNG feature graphic, and the previous store icon has been retired. The current 512 × 512 icon is generated on demand from the checked-in cat artwork. The screenshot names follow the intended viewing order:

1. `01-home.png` — home overview and local storage information
2. `02-files.png` — file browsing
3. `03-filters.png` — file filter dialog
4. `04-duplicates.png` — verified exact-duplicate groups
5. `05-comparison.png` — choose which matching file to keep
6. `06-cleanup.png` — potential cleanup categories
7. `07-dark-mode.png` — theme settings

All screenshots use actual Android API 36 instrumentation captures from [native verification run 41947414464](https://github.com/hebi10/GalliPick/actions/runs/41947414464) at commit `aa695aeea24f395b8802cf3202de3916d45fc3f9`. Each is framed against a cream background with a short caption; the UI contents are not generated or altered. Test/example media and storage figures are not a user's personal files. This is not a claim that every screen is visible in the currently published Play version.

The feature graphic is a separate marketing composition using the newly integrated GalliPick cat illustration. It avoids promising AI, similar-image detection, automatic deletion or immediate storage reclamation. The Play icon uses the same green-hatted cat as the Android launcher. The Java 17 source-file tool `scripts/design/GenerateStoreIcon.java` generates an opaque 512 × 512 sRGB RGBA PNG from `app/src/main/res/drawable-nodpi/gp_cat_organize.png` without network access or new art assets. The resulting PNG is attached to the `GalliPick Play icon` workflow as an artifact.

The checksums and provenance are in `ko-KR/manifest.json`. Rebuild the store images from genuine native captures with `scripts/design/package_store_assets.py`. Validate and upload these files only through `scripts/release/play_listing_images.py`. The workflow `.github/workflows/play-listing-images.yml` reuses the existing GalliPick Play authentication and updates only the `com.hebi.gallipick` `ko-KR` phone screenshots and feature graphic after the merged asset change on `main`.

**Submission and publication are separate**: passing image validation or a successful API edit commit does not prove that Google has approved or published the new listing assets. Check Play Console's publishing overview after the workflow run.

## Store icon updates

The former `ko-KR/app-icon.png` was a legacy photo-card icon and is intentionally removed to prevent accidental reuse. On a main-branch change to the icon or its generator, [the dedicated icon workflow](../../.github/workflows/play-store-icon.yml) builds and validates the single 32-bit 512 px image, then submits **only** the `com.hebi.gallipick` `ko-KR` `icon` image type. It does not touch screenshots, feature graphics, testing tracks, or other apps.

Submission is not Google approval. Check the Play Console publishing overview for the final store-visible image.
