# GP-PERF-00 — synthetic SQLite baseline and reproducibility

2026-11-18 · GP-PERF-00 (host measurement) · GalliPick

## Scope and provenance
- Baseline source pattern: current main \`MediaRepository.confirmedSimilarSurvivor()\` on \`635e945e665410f4a6c81db11dfa1ee9c80fb0e6\` (pHash behavior unchanged from \`dad9e11d\`): for each of 3 selected photos fetch \`allPhotoHashes()\`, then fetch all hashes once more to check up to 7 survivor candidates.
- Candidate improvement: [PR #132](https://github.com/hebi10/GalliPick/pull/132) uses \`MediaDao.photoHash(uri, fingerprint)\` on the existing SQLite \`PRIMARY KEY(uri, fingerprint)\` and checks all 10 requested proofs directly.
- Data: deterministic synthetic 1,000, 10,000, 30,000 and 50,000-row SQLite databases. Exactly 3 reviewed selected proofs, followed by 7 potential surviving copies, of which only the last is valid. **No user photographs, cloud credentials or private media are involved.**
- Testing: each query strategy executes alternately for 7 timed repetitions after warmup; samples include Python-side row mapping; the same survivor identity must be returned by both. Query-plan assertion requires an indexed lookup. Separate Python tracemalloc snapshots are for peak Python allocations (not total process RSS).
- The baseline refresh proxy repeats the old full-delete/full-insert/prune/volume-epoch transaction in memory and rolls it back, recording 3–5 repetitions. This is not a measured production app refresh or optimized comparison.

## One host-run measurement

Linux x86_64 · CPython 3.13.5 · SQLite 3.46.1 · in-memory DB · 7 paired repetitions. Raw JSON and runner results are available from the attached artifact or reproducible GitHub Actions benchmark; results vary with machine and cache.

| Media rows | Old p50 (ms) | New p50 (ms) | Old p95 (ms) | New p95 (ms) | Reads old/new | Python peak MiB old/new | Old full-replace p50 (ms) |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 3.832 | 0.040 | 4.289 | 0.087 | 4 / 10 | 0.6 / <0.1 | 3.4 |
| 10,000 | 40.280 | 0.061 | 42.790 | 0.087 | 4 / 10 | 7.6 / <0.1 | 36.4 |
| 30,000 | 148.824 | 0.106 | 156.340 | 0.260 | 4 / 10 | 22.8 / <0.1 | 146.2 |
| 50,000 | 275.560 | 0.110 | 293.415 | 0.143 | 4 / 10 | 38.1 / <0.1 | 204.4 |

**Interpretation:** Avoiding 4 full table materializations significantly reduces this **host workload**, even though 10 indexed lookups execute. These ratios are *not* real Android/Room speedup claims, and the exact nanosecond/millisecond times are not stable across runners. Existing proof/volume checks and \`MediaStore\` revalidation remain part of the real algorithm and are not included in this proxy.

## CI reproduction

Source code: \`scripts/performance/gp_perf00.py\` and \`scripts/performance/test_gp_perf00.py\`.

- Run \`python3 -m unittest discover -s scripts/performance -p 'test_*.py' -v\` to validate the probes.
- Run \`python3 scripts/performance/gp_perf00.py --sizes 1000 10000 30000 50000 --repetitions 7 --output performance-output/report.json --markdown performance-output/report.md\`.
- Workflow: \`.github/workflows/gp-perf-00-sqlite.yml\`. It writes both portable files to a non-secret 30-day GitHub Actions artifact.

## Outstanding real-device validation
- **Android Room**: actual DAO \`allPhotoHashes\` vs \`photoHash\` query wall time and PSS on a Play-compatible API 36 emulator/physical Samsung test device (same catalog, same warmup).
- **Full catalog refresh**: actual \`MediaStoreReader.load()\`, \`MediaDao.replaceAccessible()\`, and \`SharedMediaCatalog.awaitSnapshot()\` elapsed times and Flow invalidations with unchanged, changed, revoked-permission, and reset-volume snapshots.
- **Screen responsiveness**: Compose scroll frame timing, cold/warm startup and peak memory with 1k–50k media; prefer instrumented profiling instead of attributing SQLite query changes to a whole-app UX speedup.
- **GP-PERF-02 gate**: do not replace \`DELETE/INSERT\` with incremental upserts until retention of protection, fingerprint/volume-epoch invalidation, permission-revocation safety and rollback behavior has parity tests.
- **Deployment**: this benchmarking PR changes no app runtime or release artifacts. No Play upload and no Firebase deployment.

Related: [GP-PERF-01 PR #132](https://github.com/hebi10/GalliPick/pull/132) · [GP-CORE-PLAN #131](https://github.com/hebi10/GalliPick/issues/131) · [Drive plan](https://docs.google.com/document/d/1Azl14iCD91P4WDwDma8zEOfGjG1FlxMBuMod2VGZPXo/edit).
