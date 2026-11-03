# Adaptive media review usability

The user approved implementing the preceding review of all 30 native phone/tablet captures. The existing Kotlin/Compose app, mint/cream palette, rounded icons, offline processing, MediaStore permissions, and Android-confirmed trash flow remain the foundation.

## Intended behavior
- Duplicate results prioritize actual files. Finished scans use a compact summary without a completed progress bar. Global candidate selection and group selection clearly name their scope. A fixed keeper marker is visually different from a deletion checkbox.
- Comparison labels describe the displayed file's current role. Changing a comparison target is distinct from choosing a keeper. Show actual recommendation evidence, including the date field/value used. Cap preview height so metadata/actions remain reachable on tablet portrait; keep side-by-side previews when width and height allow. Preserve stable preview targets when keeper changes.
- File browsing uses full width until a file is opened; wide screens then show a closable detail pane. Selection stays next to the list count. Preserve the compact search/type/filter/sort toolbar and scroll-away behavior.
- Filter scroll content never draws behind its separate action footer. Respect keyboard/system insets. Wide windows use two columns, narrow windows expose advanced conditions explicitly. Size labels and input conversion agree; byte conversion details belong in help.
- Home places scan/cleanup actions near the top, compacts the introduction, and places the on-device message near the summary. Wide home/cleanup layouts use columns; settings use a readable bounded width. Zero-result cleanup categories use compact rows rather than oversized empty cards.
- Keep clear plain Korean; do not introduce cutesy wording, new dependencies, animation, networking, or automatic deletion.

## Approach
Incrementally adjust current composables and retain their callbacks/state policies. A wholesale visual rewrite would add risk without helping these observed issues. Shared selection and duplicate policies remain authoritative.

## Verification
Existing unit/release/device checks plus focused adaptive UI regressions. Capture actual native UI again for phone portrait, tablet portrait and tablet landscape, inspecting all output screens. Also exercise narrow/short windows and large font where layout behavior changes. Four seeded files are test data, never the user's gallery.
