---
phase: 17-cofactor-suggestion
plan: 02
subsystem: model
tags: [cohort-clustering, cofactor, raw-intensity, pooling, z-score, CohortClusterModel]

# Dependency graph
requires:
  - phase: 17-cofactor-suggestion (17-01)
    provides: CofactorEstimator (the consumer that needs RAW pooled intensities) — sibling wave, not a build dependency
provides:
  - "CohortClusterModel.poolAllCellsRaw(...) — two-pass, memory-safe, no-sampling all-cells pooling sibling that returns RAW (un-standardized) per-cell rows"
  - "Regression test proving poolAllCellsRaw returns raw values while poolAllCells z-scores the same input"
affects: [17-03, 17-04, cofactor-suggestion-tool, ScatterPlotView-cofactor-wiring]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Raw-vs-standardized pooling sibling: clone the existing two-pass pooling loop verbatim, differ ONLY in the final standardization step, keep the PooledData record shape identical (mean/sd returned as zero arrays for the raw path)"

key-files:
  created: []
  modified:
    - src/main/java/qupath/ext/celltune/model/CohortClusterModel.java
    - src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java

key-decisions:
  - "poolAllCellsRaw is a fresh sibling method, NOT a refactor of poolAllCells — poolAllCells is left byte-for-byte untouched so the z-scored cohort-clustering path carries zero regression risk"
  - "mean/sd on the raw PooledData are zero arrays (unused by the estimator), kept only to preserve the shared PooledData record shape"
  - "Raw values obtained by passing normalizer=null to CellFeatureExtractor.extractMatrix (no z*sd+mean recovery — that would couple the estimator to z-score internals)"

patterns-established:
  - "Raw pooling sibling pattern: identical memory-safe/no-sampling/cancellation loop, only the terminal standardize-or-not step differs"

requirements-completed: [COF-03, COF-08]

# Metrics
duration: ~12min
completed: 2026-07-08
---

# Phase 17 Plan 02: Raw All-Cells Pooling Sibling Summary

**`CohortClusterModel.poolAllCellsRaw` — a two-pass, memory-safe, no-sampling clone of `poolAllCells` that returns RAW per-cell intensities (skips `ScatterMath.standardizeColumns`) so the cofactor estimator sees the true background level instead of z-scored rows collapsing to p50≈0.**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-07-08T10:41Z (after 17-01 summary commit)
- **Completed:** 2026-07-08T10:48Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added `public static PooledData poolAllCellsRaw(...)` immediately after `poolAllCells` with an identical parameter list. Its body is a byte-identical copy of `poolAllCells`' per-image two-pass loop (`entriesByName`, `new CellFeatureExtractor(markers, normalizer)`, the `rawRows`/`msbList`/`lsbList`/`ordinalList`/`totalCells`/`cancelled` accumulators, `token.isCancelled()` per image, `selectImageSource(...)`, `detections(...)`, `extractor.extractMatrix(...)`, the `classFilterActive` filter, packed-`(msb,lsb)` UUID capture, per-image release/log) EXCEPT it SKIPS the terminal `ScatterMath.standardizeColumns` and returns the raw pooled matrix.
- `mean`/`sd` are returned as zero arrays (unused by the estimator) purely to preserve the shared `PooledData` record shape; `poolAllCells` and `PooledData` are unchanged.
- Added `poolAllCellsRawReturnsRawRowsWhilePoolAllCellsZScores` to `CohortClusterModelTest` — pools two synthetic images (M1 = {10,20,30,40,50} and {60,70,80}) through both siblings on identical input, asserts the raw column equals the injected values (sorted == {10,20,30,40,50,60,70,80}, mean ≈ 45) while `poolAllCells` centres the same column (z mean ≈ 0), proving raw ≠ z-scored.

## Task Commits

Each task was committed atomically (plain git, freeform messages — hand-orchestrated project, no gsd-sdk, no hooks):

1. **Task 1: Add poolAllCellsRaw sibling to CohortClusterModel** — `edff66a` (feat)
2. **Task 2: Prove raw-vs-zscored with a CohortClusterModelTest case** — `9b96992` (test)

**Plan metadata:** this SUMMARY commit (docs)

## Files Created/Modified
- `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` — added `poolAllCellsRaw(...)` (raw sibling of `poolAllCells`, ~110 lines incl. Javadoc). No other method touched.
- `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` — added the raw-vs-zscored regression test.

## Verification

- `./gradlew compileJava` — BUILD SUCCESSFUL (Task 1 gate).
- `./gradlew test --tests "*CohortClusterModel*Raw*"` — BUILD SUCCESSFUL (new test green).
- `./gradlew test --tests "*CohortClusterModel*"` — 35 tests, 0 failures (no regression in the existing pooling/write-back suite).
- `./gradlew clean compileJava test` (final full gate) — BUILD SUCCESSFUL.
- **Full suite: 428 tests, 0 failures, 0 errors, 0 skipped** across 43 test classes.

Acceptance-criteria greps:
- `grep -c 'public static PooledData poolAllCellsRaw' …CohortClusterModel.java` → `1` ✓
- `grep -Ec 'geojson|GeoJson|FileReader|BufferedReader' …CohortClusterModel.java` → `0` (in-memory only, COF-03) ✓
- Exactly ONE actual `ScatterMath.standardizeColumns(` call remains (line 947, inside the untouched `poolAllCells`); `poolAllCellsRaw` contains none ✓
- `grep -c 'poolAllCellsRawReturnsRawRowsWhilePoolAllCellsZScores' …CohortClusterModelTest.java` → `1` ✓

## Decisions Made
- Kept `poolAllCells` byte-for-byte untouched (fresh sibling, no shared-helper extraction) to guarantee zero regression risk on the established z-scored cohort-clustering path.
- Raw values sourced via `normalizer=null` → `extractMatrix` returns un-normalized measurements; deliberately did NOT reconstruct raw via `z*sd+mean` (would couple the estimator to z-score internals — see plan objective / RESEARCH.md Pitfall 1).

## Deviations from Plan

**None functionally — plan executed as written.** One documentation note on an acceptance-criterion grep:

- Task 1's acceptance criteria expected `grep -c 'standardizeColumns' …CohortClusterModel.java` → `1`. The actual count is `3`, but this is expected and not a code deviation: only **one** is an actual method **call** (line 947, in the untouched `poolAllCells`). The other two matches are (a) the Javadoc `{@link ScatterMath#standardizeColumns}` reference that the Task 1 `<action>` *explicitly mandated* ("Add a Javadoc that states … skips `{@link ScatterMath#standardizeColumns}`"), and (b) an explanatory `// RAW path: SKIP ScatterMath.standardizeColumns` comment. The criterion's intent — `poolAllCellsRaw` performs no standardization and `poolAllCells` still standardizes exactly once — is fully satisfied (verified via `grep -n 'ScatterMath.standardizeColumns('` → single hit at line 947). The literal `-c 1` was inconsistent with the plan's own mandated Javadoc; the mandated Javadoc was honored.

## Issues Encountered
- None. (A local `python3` on PATH is misconfigured for the whole-suite XML aggregation; substituted `grep`/`awk` — no impact on the build or tests.)

## Next Phase Readiness
- `poolAllCellsRaw` is ready to feed `CofactorEstimator` (17-01) with RAW pooled intensities — the whole-project cofactor tool (17-03/17-04) can now pool raw values without z-scoring or hand-rolling a raw path.
- No blockers. `STATE.md`/`ROADMAP.md` intentionally left unmodified per hand-orchestrated overrides (orchestrator owns them).

## Self-Check: PASSED

- Files exist: `CohortClusterModel.java`, `CohortClusterModelTest.java`, `17-02-SUMMARY.md` — all FOUND.
- Commits exist: `edff66a` (feat), `9b96992` (test) — both FOUND.
- `.planning/STATE.md` / `.planning/ROADMAP.md` not modified by this executor (the pending STATE.md edit is the orchestrator's own `status: ready_to_execute → executing` bookkeeping; left unstaged).

---
*Phase: 17-cofactor-suggestion*
*Completed: 2026-07-08*
