# Native screenshot fixtures

`store-coast-preview.mp4` is a capture-only coastal landscape sample created for GalliPick on 2026-11-04. Its source artwork was generated for this project with the built-in image generator; no third-party stock photograph was used.

- Purpose: attractive, non-personal example media for actual MediaStore-based store screenshots.
- Video: 4.000 seconds, 1280 × 720, H.264 Constrained Baseline, yuv420p, 24 fps, no audio.
- Video SHA-256: `b8bc2064de65ee4fc1664c49e79a5b39e4443f01e51ed15b72e0576f9dcf85ae`.
- Source artwork SHA-256: `8b086084b5c7313f67e3a251ae55f78dc70b954ed510ba586c0810eb425b38d0`.
- Inserted display name: `EXAMPLE_coast_video.mp4`.

`NativeScreenCaptureTest` reads this real video and derives its duration, dimensions, and file size from the file. Captures must be rerun whenever the fixture changes so displayed statistics stay accurate. This asset lives under `androidTest` and is not bundled in the production app.

The existing `preview-baseline.mp4` remains unchanged for playback regression tests.
