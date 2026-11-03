# Adaptive usability verification — 2026-11-03

## Source and integration

- PR: https://github.com/hebi10/GalliPick/pull/3
- Verified source: `6d828e87f7323cbb326add46813aa07fdccf9a8e`.
- Squash merge on main: `4cd2c8060a32b21bf37a3403c6e96964226f4495`.
- Both have the identical Git tree `b95a46ed95b7dde9942f50b1f32689df3ab32a49`.

## Automated checks

| Check | Result | Evidence |
| --- | --- | --- |
| JVM tests | 88 passed; 0 failures, errors or skips | [Android verification](https://github.com/hebi10/GalliPick/actions/runs/41577647440) |
| Android lint | 0 errors, 27 warnings | Same verification run |
| Release tooling | 25 Python tests passed locally and in CI | Same verification run |
| Full native instrumentation | 80 tests passed | [Android device tests](https://github.com/hebi10/GalliPick/actions/runs/41577647636) |
| Adaptive instrumentation | 48 tests passed in each of five profiles | [Adaptive verification and captures](https://github.com/hebi10/GalliPick/actions/runs/41577642410) |
| Native capture test | 11 required screens per profile; no detail failures | Same capture run |

Android compilation and runtime checks ran in GitHub Actions. The local Gradle wrapper could not download its distribution in the managed workspace; no local Android test pass is claimed.

## Native screen coverage

All profiles use API 36 emulators. These are viewport checks, not a claim of coverage of every physical device or Android release.

| Profile | Pixels | Density | Font scale | Screens |
| --- | --- | --- | --- | --- |
| Phone portrait | 1080 × 2400 | 420 dpi | 100% | 11 |
| Phone landscape | 2400 × 1080 | 420 dpi | 100% | 11 |
| Phone large text | 1080 × 2400 | 420 dpi | 160% | 11 |
| Tablet portrait | 1600 × 2560 | 240 dpi | 100% | 11 |
| Tablet landscape | 2560 × 1600 | 240 dpi | 100% | 11 |

Each profile captures home, files, duplicates before/after scan, cleanup, dark settings, size sorting, video-duration sorting, filters, filters with the real IME, and duplicate comparison. Four repository-owned `EXAMPLE` media files are inserted into MediaStore; no user gallery is used.

The capture test requires a visible input-method window for the keyboard case and checks that the entire focused field and Apply action remain above it. Images are raw full-viewport `UiAutomation.takeScreenshot` output, without cropping or compositing. Artifact digests, image dimensions, all 55 image files and manifest completeness were checked before packaging.

The harness used the existing exact System UI ANR Wait handler once in phone portrait, phone large-text and tablet landscape. The dialogs were dismissed before capture, and capture guards reject screenshots containing those overlays. This does not imply the emulator's System UI ANR cause was fixed.

## Review and resolved findings

Independent reviewers opened all 55 final native PNGs individually (22 phone, 22 tablet, 11 large-text) and found no remaining P1/P2 visual defects. Source review covered candidate/keeper policy preservation, stable grid keys, scan controls, comparison state, filter input preservation and touch target sizes.

- Short phone landscape originally spent the first viewport on duplicate headings. The final layout puts keeper and candidate cards side by side, with the noninteractive keep marker and candidate checkbox fully visible immediately.
- Compact keyboard layouts originally clipped the focused field or obscured validation errors. Separate clipped scrolling and action areas preserve a full input field; invalid Apply reveals the error without discarding entered values.
- Comparison actions use reachable outer scrolling and keep pending revalidation feedback visible. Tests verify the action callback before asserting the resulting notice.
- Empty tablet details no longer reserve a blank pane. Home actions, cleanup categories and settings use available width while retaining readable content widths.

## Delivery and scope

`GalliPick-improved-screens-2026-11-03.zip` contains the 55 raw native images, manifests, instrumentation summaries, provenance and checksums.

Archive SHA-256: `33991aa0053f8a9a53ed0ee88d8d9b7184cbc161a800bdf21d46bac425292e6b`.

This change updates repository UI and verification infrastructure. The app version remains 0.1.6 (7); package identity, signing credentials and Play release artifacts are unchanged.
