---
phase: 15-all-cells-leiden-clustering
plan: 04
subsystem: ml
tags: [leiden, cohort, two-pass, packed-uuid, cancellation, java]

# Dependency graph
requires:
  - phase: 15-01
    provides: "HnswKnnIndex ANN wrapper, seeded single-threaded reproducible build"
  - phase: 15-02
    provides: "AnnRecallException, LeidenModel.clusterViaAnn -- HNSW-routed single-partition Leiden"
  - phase: 15-03
    provides: "Primitive-array SNN/Jaccard rewrite (cohort-scale), clusterViaAnn proven to assign every pooled row a label from a single partition and reproduce up to permutation"
provides:
  - "CohortClusterModel.CancellationToken -- minimal AtomicBoolean-backed mid-run cancellation primitive (no prior analog in this codebase)"
  - "CohortClusterModel.poolAllCells (pass 1) -- streams every cell (no sample cap) across selected images, z-scores the full pooled matrix (ScatterMath.standardizeColumns), captures each cell's packed (msb,lsb) UUID instead of getID().toString(), releases each hierarchy before the next, honors cancellation between images"
  - "CohortClusterModel.labelMapForImage -- pure, reorder-independent (msb,lsb) -> label lookup for one image's pooled slice, keyed on a small UuidKey(msb,lsb) record"
  - "CohortClusterModel.writeClusterAllCells (pass 2 driver) -- pools once, runs a SINGLE LeidenModel.clusterViaAnn partition over the whole pooled matrix, re-reads each image and writes Cluster by UUID lookup (reusing applyMeasurement's FX marshalling verbatim); aborts writing nothing on AnnRecallException; reports written vs unwritten images on cancel"
  - "CohortClusterModel.runPass2Loop / clusterOrAbort -- pure, package-private seams the driver delegates to, extracted so cancellation bookkeeping and recall-gate-abort handling are unit-testable without a live Project/ImageData"
affects: [15-05-ui-docs]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Packed (msb,lsb) UUID keying (UuidKey record) instead of getID().toString() at cohort scale -- a documented, phase-specific deviation from this codebase's usual UUID-as-string convention, flagged in code comments so it is not 'fixed' back"
    - "Growable primitive long[]/int[] accumulators (GrowableLongArray/GrowableIntArray) instead of boxed List<Long>/List<Integer> while pooling every cell across the cohort"
    - "Orchestration-logic extraction: writeClusterAllCells's per-image loop (runPass2Loop) and recall-gate-abort branch (clusterOrAbort) are both pure package-private helpers taking a Supplier/BiPredicate, so the exact production decision logic is unit-testable without a live QuPath Project/ImageData -- mirrors the existing labelsForRows Function<double[][],int[]> injection pattern already used by writeClusterAcrossProject/assignAcrossProject"

key-files:
  created: []
  modified:
    - src/main/java/qupath/ext/celltune/model/CohortClusterModel.java
    - src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java

key-decisions:
  - "AllCellsResult.recall is a -1.0 sentinel, not a real measured value: LeidenModel.clusterViaAnn (Plan 02/03, not in this plan's files_modified) does not currently return its internal recall-gate measurement -- only clusters or throws AnnRecallException. Exposing the real recall end-to-end would require changing LeidenModel.java's public API, which is out of this plan's declared file scope (CohortClusterModel.java + its test only). The plan's own task text hedges this with 'capture the measured recall if clusterViaAnn exposes it' -- since it does not yet, the sentinel is used and documented in the AllCellsResult javadoc for Plan 05's awareness (Plan 05's UI wiring wants a real recall value on the status line per D-09; this may need a small LeidenModel change in that plan)."
  - "UUID map key is a UuidKey(long msb, long lsb) record, not a single packed/XOR-folded Long: a record's auto-generated equals/hashCode over both fields gives exact, collision-free equality at zero extra implementation cost, still avoiding the codebase's usual getID().toString() boxed-String overhead at cohort scale. Chosen over XOR-folding msb^lsb into one Long (theoretically collision-prone, however astronomically unlikely) purely because it removes any collision argument entirely, at the same asymptotic cost."
  - "Image ordinal (pass-1 -> pass-2 correlation) is simply each image's index position in the caller-supplied `images` list, threaded through PooledData.imageOrdinal per pooled row, rather than a separate name-based lookup structure. Both poolAllCells and writeClusterAllCells iterate the SAME images list in the SAME order, so ordinal correspondence is guaranteed without extra bookkeeping; PooledData.imageNames is retained (the images list itself) for symmetry with the plan's specified record shape."
  - "z-score mean/sd for the all-cells pool is computed via the existing ScatterMath.standardizeColumns(data, outMean, outSd) helper (already used by ScatterPlotView for the sub-clustering z-score), reused verbatim rather than re-deriving column standardization -- pass 1 accumulates raw double[] rows per pooled cell, then standardizes once at the end in a single pass over the whole pooled matrix."
  - "Extracted two pure package-private seams (runPass2Loop, clusterOrAbort) from writeClusterAllCells specifically to satisfy Task 3's TDD requirement for cancel-leaves-written-intact and abort-writes-nothing tests without needing a live Project/ImageData (no Mockito or lightweight Project fake is available in this codebase's test dependencies) -- per the plan's explicit 'expose small package-private seams if needed rather than requiring a full project' guidance. Both are called by writeClusterAllCells itself (not test-only duplicates), so the tests exercise the exact production decision logic."

requirements-completed: []  # LEI-06/LEI-08/LEI-10 span this plan and Plan 05 (UI wiring, docs); not marking complete until satisfied across all contributing plans, per Plans 01-03's precedent.

# Metrics
duration: 15min
completed: 2026-07-06
---

# Phase 15 Plan 04: Two-Pass All-Cells Cohort Driver (UUID Write-Back) Summary

**Added `CohortClusterModel.writeClusterAllCells`, a memory-safe two-pass all-cells cohort driver that pools every cell across a project via `poolAllCells` (packed `(msb,lsb)` UUID capture, not `getID().toString()`), runs a single `LeidenModel.clusterViaAnn` partition over the fully pooled matrix, and re-reads each image to write the `Cluster` measurement by UUID lookup (`labelMapForImage`) -- reorder-safe, cancellable between images, and aborting with zero writes on an `AnnRecallException`.**

## Performance

- **Duration:** ~15 min (git commit-to-commit span from Plan 03's completion)
- **Started:** 2026-07-06T13:09:51+10:00 (approx, following 15-03's completion commit)
- **Completed:** 2026-07-06T13:24:39+10:00
- **Tasks:** 3 completed (plus two small in-plan refactors to keep Task 3 testable)
- **Files modified:** 2 (both modified, none created)

## Accomplishments

- `CohortClusterModel.CancellationToken` -- a minimal `AtomicBoolean`-backed cancellation flag (no prior true mid-run cancellation primitive existed in this codebase; only pre-flight dialog-decline "cancellations" did).
- `CohortClusterModel.poolAllCells` (pass 1, LEI-06/LEI-08): streams **every** cell (no sample cap, unlike `sample()`) across the selected images, extracting raw marker rows and z-scoring the **entire** pooled matrix in one pass via `ScatterMath.standardizeColumns`, capturing each cell's packed `(msb,lsb)` UUID (`GrowableLongArray`/`GrowableIntArray` -- boxing-free accumulators) and source-image ordinal, honoring cancellation between images, and never retaining a hierarchy past its own loop iteration.
- `CohortClusterModel.UuidKey(long msb, long lsb)` + `labelMapForImage` -- a pure, package-private helper building a reorder-independent `(msb,lsb) -> label` lookup for one image's pooled slice; this is the exact seam the LEI-08 reorder test exercises.
- `CohortClusterModel.AllCellsResult` + `writeClusterAllCells` (pass 2 driver, LEI-06/LEI-08): pools once, runs a **single** `LeidenModel.clusterViaAnn` partition over the whole pooled matrix (no per-image sub-clustering, no transfer), then re-reads each image and writes `Cluster` by UUID lookup, reusing `applyMeasurement`'s existing FX-thread marshalling verbatim (not reimplemented). An `AnnRecallException` aborts before pass 2 starts -- zero images written (T-15-10). Cancellation during pass 2 stops before the next image; already-saved images keep their `Cluster` measurement (T-15-07); the result reports exactly which images were / were not written.
- Extracted two pure, package-private seams so Task 3's tests exercise the *actual* production decision logic without a live `Project`/`ImageData` (no Mockito available in this project's test dependencies): `runPass2Loop` (cancellation + written/not-written bookkeeping) and `clusterOrAbort` (turns a thrown `AnnRecallException` into a plain `ClusterOutcome`).
- New tests: `uuidWriteBackSurvivesReorder` (two synthetic images with real `PathObject.getID()`s; image B's cells read back both **shuffled** and **reversed** all resolve to their correct pass-1 label by UUID, not position), `poolingIdentityEveryUuidResolvesToExactlyOneLabelPerImage` (pooled row count == total detections; each image's label map has exactly that image's cell count, no cross-image leakage), four `runPass2Loop`/`CancellationToken` tests (cancel-leaves-written-intact, no-cancellation, failed-image-does-not-stop-loop, token semantics), and two `clusterOrAbort` tests (forced `AnnRecallException` aborts with 0 cells/null result; a successful supplier is not aborted).
- Full `./gradlew test` (380 tests, 0 failures/errors) and `./gradlew spotlessCheck` (formatting) both green.

## Task Commits

1. **Task 1: CancellationToken + poolAllCells (pass 1) + packed-UUID label map** - `506a188` (feat)
2. **Task 2: writeClusterAllCells (pass 2 driver)** - `95e464d` (feat)
   - Follow-up refactor (extracted for Task 3 testability): `c510841` (refactor -- `runPass2Loop`), `db52a01` (refactor -- `clusterOrAbort`)
3. **Task 3: CohortClusterModelTest -- reorder-safe write-back, pooling identity, cancel, abort** - `4a2ef70` (test)

**Plan metadata:** (this commit, pending)

## Files Created/Modified

- `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` -- Added `CancellationToken`, `PooledData`/`poolAllCells` (pass 1), `UuidKey`/`labelMapForImage`, `AllCellsResult`/`ClusterOutcome`/`writeClusterAllCells` (pass 2 driver), `Pass2Outcome`/`runPass2Loop`, `clusterOrAbort`, and `GrowableLongArray`/`GrowableIntArray` boxing-free accumulators. Class javadoc extended (not replaced) to describe the new all-cells two-pass mode alongside the existing sample/assign passes.
- `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` -- Added the LEI-08 reorder/pooling-identity tests (real `PathObject`s via `ROIs.createRectangleROI`/`PathObjects.createDetectionObject`, mirroring `AnnotationLabelCollectorTest`'s pattern) and the `runPass2Loop`/`clusterOrAbort` unit tests (LEI-10 Test C/D).

## Decisions Made

See `key-decisions` in frontmatter: (1) `AllCellsResult.recall` is a documented `-1.0` sentinel pending a future `LeidenModel` API change (out of this plan's file scope); (2) `UuidKey(msb,lsb)` record key over an XOR-folded single `Long` (collision-free at equal cost); (3) image ordinal = position in the caller's `images` list, threaded through pooled rows rather than a separate lookup; (4) `ScatterMath.standardizeColumns` reused verbatim for the all-cells z-score; (5) `runPass2Loop`/`clusterOrAbort` extracted as pure seams so the driver's cancellation and abort logic is unit-testable without a live QuPath `Project`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Extracted `runPass2Loop` and `clusterOrAbort` as pure package-private seams**
- **Found during:** Task 3 (writing the cancel-leaves-written-intact and abort-writes-nothing tests)
- **Issue:** `writeClusterAllCells` as written in Task 2 inlined both the per-image cancellation/write loop and the recall-gate try/catch directly against live `Project`/`ImageData` calls. This codebase has no `Project` mock/fake and no Mockito in its test dependencies (`build.gradle.kts` test deps are `libs.bundles.qupath` + `libs.junit` only), so Task 3's Test C (cancel) and Test D (abort) could not exercise the real driver's decision logic without a full, heavyweight `Project`/`ImageData` fixture -- which the plan itself anticipated and explicitly permitted working around ("expose small package-private seams if needed rather than requiring a full project").
- **Fix:** Extracted the per-image loop's cancellation-check-then-write/skip bookkeeping into `runPass2Loop(images, token, BiPredicate<ordinal,name>)`, and the recall-gate try/catch into `clusterOrAbort(Supplier<LeidenResult>)`. `writeClusterAllCells` now delegates to both (no duplicate logic); the tests inject synthetic `BiPredicate`/`Supplier` callbacks to exercise the exact same decision logic without touching QuPath I/O.
- **Files modified:** `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java`
- **Verification:** `./gradlew compileJava` green after each refactor; full `./gradlew test` green afterward (380 tests); `writeClusterAllCells`'s observable behavior is unchanged (verified by re-checking Task 2's grep gates after the refactor).
- **Committed in:** `c510841`, `db52a01` (both before Task 3's test commit)

---

**Total deviations:** 1 auto-fixed (1 blocking, split across two small refactor commits)
**Impact on plan:** Necessary to satisfy Task 3's TDD requirement given no `Project` test fixture exists in this codebase; no scope creep -- both seams are called by production code, not test-only shims, and `writeClusterAllCells`'s public contract/behavior is unchanged.

## Issues Encountered

`./gradlew spotlessApply` reformatted both modified files after the initial edits (wrapping a few long lines -- the `runPass2Loop` signature, a ternary condition, and several test assertion calls -- to the 120-column limit). Re-ran `spotlessCheck` and the full test suite afterward to confirm no behavioral change before the Task 3 commit.

## TDD Gate Compliance

Task 3 was marked `tdd="true"`. As in Plans 01-03, the tests exercise already-implemented package-private seams (`labelMapForImage` from Task 1; `runPass2Loop`/`clusterOrAbort`, extracted from Task 2's `writeClusterAllCells` specifically so they could be tested) rather than discovering a new API via a standalone failing test first. The commit is typed `test(...)` (Task 3 added zero new production code beyond the two extraction refactors, which were committed separately as their own `refactor(...)` commits) rather than separate RED/GREEN commits -- consistent with the same documented deviation recorded in Plans 01-03 for the same underlying reason (the seams' signatures were designed together with their tests, not discovered via a failing test against an unknown API).

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `CohortClusterModel.writeClusterAllCells` is a complete, tested, memory-safe all-cells cohort driver ready for Plan 05 to wire into `ScatterPlotView`: the exact call shape (`project, images, markers, graphK, resolution, randomStarts, seed, reproducible, classFilter, normalizer, openData, openName, token, log, progress`) and `AllCellsResult(nClusters, recall, cellsWritten, imagesWritten, imagesNotWritten, aborted, cancelled)` return contract match the interface Plan 05 already expects (per its own frontmatter `key_links`/`interfaces` section, written before this plan executed).
- `CohortClusterModel.CancellationToken` is ready for Plan 05's Cancel button (`token.cancel()`) wiring.
- Known gap for Plan 05's awareness: `AllCellsResult.recall` is currently always `-1.0` (see key-decisions) -- Plan 05's D-09 status-line requirement ("ANN recall 0.982 -- passed") will need either a small `LeidenModel.clusterViaAnn` API change to surface the internal recall-gate measurement, or an explicit decision to omit/soften that part of the status line until such a change is made. This was not addressed here because it would have required modifying `LeidenModel.java`, which is outside this plan's declared `files_modified` scope.
- No blockers for Plan 05 (UI wiring: single-image HNSW routing, cohort-mode radio pair, soft ceiling, progress/cancel, docs).

---
*Phase: 15-all-cells-leiden-clustering*
*Completed: 2026-07-06*

## Self-Check: PASSED

All claimed files found on disk (`CohortClusterModel.java`, `CohortClusterModelTest.java`, this SUMMARY.md); all five task/refactor commits (`506a188`, `95e464d`, `c510841`, `db52a01`, `4a2ef70`) found in git history; full test suite (380 tests, 0 failures/errors) and `spotlessCheck` verified green in this session.
