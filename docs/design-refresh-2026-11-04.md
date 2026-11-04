# GalliPick UI refresh — 2026-11-04

## Scope

- Keep the local-only Android MediaStore, duplicate SHA-256 verification, keeper/candidate selection, and system-confirmed trash/restore workflow unchanged.
- Restore interaction feedback across the theme, custom click targets, and media checkboxes.
- Add visible file search, selected-file outlines, and sort-relevant metadata in file grids.
- Clarify the home introduction, empty-state art, duplicate group headers, cleanup empty rows, and protected folder management.
- Use a generated pastel-green cat illustration and matching navigation artwork.

## Asset provenance

The `gp_design_atlas.png` resource is a compressed sprite atlas assembled from user-approved, AI-generated GalliPick concept art. It contains five 104×104 mascot scenes followed by six 48×48 navigation icons starting at y=104. The resource is 520×152 pixels, 64-color PNG, 8,169 bytes, with a transparent background. The original user-facing concept package is `GalliPick_UI_Assets.zip` in the conversation.

The source artwork was produced from a composite concept sheet, so the new atlas is fit for previewing the design, but high-density final assets should be regenerated as individually authored high-resolution images before a public production release.

## Behavior and verification checklist

- Home and empty states retain their existing navigation and permission actions.
- Search dropdown still updates the same MediaFilter query and retains the keyboard behavior.
- File selection, sorting, group keeper choice, system trash confirmation, and restoration retain their original state policies.
- The five Android UI profiles and unit/device tests should be run before merging.
- This branch does not change signing files, versionCode, Play deployment, or release tracks.
