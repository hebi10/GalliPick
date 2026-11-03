# Play external signing implementation plan

> **For agentic workers:** Use focused parallel implementation, independent review and verified completion gates.

**Goal:** Finish the latest GalliPick AAB upload using QuickCut's build-then-sign pattern.
**Architecture:** A separate unsigned variant and no-secret preparation artifact feed the existing pinned-signer AAB verifier after private JDK signing.
**Tech Stack:** Kotlin Gradle DSL, GitHub Actions, Python, JDK 17, bundletool.
**Spec:** ../specs/2026-11-03-play-external-signing-design.md

## Global constraints
- Version0.1.7/code8/packagecom.hebi.gallipick; min30/target36/non-debuggable remain fixed.
- Existing signed-release protections remain active; preparation artifacts cannot be mistaken for signed uploads.
- No credential replacement, new keys, app UI changes or public release side effects.

## Review focus
- Signature metadata, wrong source metadata or private material must reject preparation.
- Tool bytes must match the pinned official digest.
- Signed and unsigned output names/statuses must remain distinct.
- Final AAB must match the already verified original certificate and source tree.

## Tasks
- [x] Add playPreparation and automatic no-secret preparation workflow; inspect configuration and actual CI output.
- [x] Add prepare_aab.py with meaningful metadata/signature/tool/ZIP/output regression cases; run all release-tooling tests.
- [x] Independently review and integrate after Android CI and preparation pass.
- [x] Sign exact prepared bytes privately; verify all payload signatures, source metadata and checksums.
- [x] Save signed AAB, upload to the existing Play draft and confirm server acceptance without submitting or rolling out.

The signed AAB was saved and uploaded from the user-provided Downloads copy on 2026-11-03. Play accepted artifact8(0.1.7) and confirmed the internal draft was saved. Review submission and rollout remain separate actions.
