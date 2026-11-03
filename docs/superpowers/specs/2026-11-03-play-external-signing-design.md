# Play AAB external signing

The user requested the QuickCut approach after the direct signed GalliPick job rejected a mismatched Actions signing recovery archive. QuickCut's Android build creates an unsigned AAB for external signing. Adopt that separation for GalliPick, then finish the already authorized Play upload.

## Approach and scope

- Keep the existing fixed-key debug/release guards and signed workflows intact.
- Add a distinct non-debuggable, unsigned playPreparation build type and a no-secret artifact workflow. Normal main/owned preparation-branch pushes can prepare binaries; manual runs use main and an explicit matching version.
- Validate the unsigned archive, public bundletool hash, source metadata and absence of signing material. Include the exact public tool and preparation provenance; label every binary as unsigned.
- Restore the previously verified GalliPick key only in a private temporary signing scope. Sign the reviewed artifact with the JDK JAR signer, then run the existing full AAB verifier. Never put a key in source, artifacts, logs or screenshots.
- Check CI and exact source-tree integration before Play upload. Save the signed AAB as a deliverable and upload it to the existing 0.1.7 (8) internal draft. Stop before review submission or rollout.

The alternative is correcting the existing Actions secret for direct signing. External signing avoids that credential-change handoff while retaining the same final certificate checks. No new app features or production dependencies are needed.
