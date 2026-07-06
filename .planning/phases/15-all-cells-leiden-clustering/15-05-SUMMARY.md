---
phase: 15-all-cells-leiden-clustering
plan: 05
subsystem: ui
tags: [leiden, hnsw, javafx, cohort, all-cells, scatterplotview, docs]

# Dependency graph
requires:
  - phase: 15-02
    provides: "LeidenModel.clusterViaAnn -- HNSW-routed single-partition Leiden, AnnRecallException on recall-gate failure"
  - phase: 15-04
    provides: "CohortClusterModel.writeClusterAllCells (two-pass all-cells driver), AllCellsResult, CancellationToken"
provides:
  - "Single-image / interactive-preview Leiden fit routed through LeidenModel.clusterViaAnn (HNSW), replacing the brute-force LeidenModel.cluster call (D-06)"
  - "Project-scope-only cohort-mode radio pair: 'Cluster all cells' (default) / 'Transfer from sample', visible only when scope==PROJECT && method==LEIDEN"
  - "writeClusterAllCellsAcrossProject(): soft-ceiling confirm (D-10, configurable celltune.allCellsSoftCeiling default 50M), per-phase progress/status (Pooling/Building kNN graph/Running Leiden/Writing), Cancel button wired to CancellationToken (D-12), degrade-gracefully recall status line (D-09)"
  - "Post-write re-sync: scatter legend, on-slide overlay, and the 'Assign Cohort Clusters to Classes' dialog all reflect the final all-cells cluster count/centroids -- not the preview subsample's fitNClusters/fitCentroids -- via a new fit-state install (fitAllCellsCounts) and CohortClusterModel.assignAcrossProjectByMeasurement (assigns by each cell's already-written Cluster value, not by re-transferring against the stale preview reference)"
  - "USER_GUIDE.md S11.5/S11.6 + CLAUDE.md: all-cells mode, single-image HNSW routing, recall-gate/abort behaviour, batch UX, preview-vs-persisted divergence, and the three documented scanpy-fidelity gaps (CPM not modularity, SNN/Jaccard not UMAP connectivities, no PCA)"
affects: [16-future-leiden-work, phase-15-verification]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Fit-state re-sync after an out-of-band cohort write: a boolean flag (allCellsWriteActive) set on a successful all-cells write and cleared on the next preview Recompute, branches which cluster->class assignment path (measurement-based vs preview-reference-transfer) applyClustersToClasses() uses -- avoids assigning classes against a Leiden reference that no longer matches what was actually persisted"
    - "Measurement-based assignment as a second, Project-free seam (classForMeasurementValue / assignmentsFromMeasurement) mirroring the runPass2Loop/clusterOrAbort pattern from Plan 04 -- decodes each cell's already-written Cluster value rather than recomputing cluster membership"
    - "Cheap count-only pre-scan (no feature extraction) for the soft-ceiling estimate, kept separate from poolAllCells's full feature pool"

key-files:
  created: []
  modified:
    - src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java
    - src/main/java/qupath/ext/celltune/model/CohortClusterModel.java
    - src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java
    - USER_GUIDE.md
    - CLAUDE.md

key-decisions:
  - "D-09 status-line wording softened rather than fabricated: LeidenModel.clusterViaAnn does not surface its internal recall-gate measurement end-to-end, so AllCellsResult.recall remains the -1.0 sentinel documented in 15-04. The status line shows the measured recall ONLY when a real value is available and omits the clause otherwise -- no invented number is ever displayed. A future small LeidenModel/CohortClusterModel API change could fully satisfy D-09's exact 'ANN recall 0.982 -- passed' wording; deferred as out of this plan's declared file scope (matches 15-04's own carried note)."
  - "First human-verify pass (Task 4) surfaced a real defect, not a false negative: the all-cells write action re-synced only the on-slide overlay color ramp (activateClusterMapper), not the scatter legend or the 'Assign Cohort Clusters to Classes' dialog, which both kept showing the last preview Recompute's subsample cluster count/heatmap. Fixed post-checkpoint (Rule 1 -- bug) across three commits: AllCellsResult gained pooled centroids/counts (CohortClusterModel), ScatterPlotView installs the full all-cells fit-state (fitNClusters/fitCentroids/fitAllCellsCounts/fitMean/fitSd) on the FX thread after a successful write and clears the stale Leiden reference, and cluster->class assignment routes through a new measurement-based path when the currently-installed fit-state came from an all-cells write. The user re-verified after this fix and approved."
  - "Conditional PCA (ScatterMath.pcaReduce) work visible in the git log adjacent to these commits is a SEPARATE, later addition -- not part of 15-05's scope, delivered under its own commits (41415fa..82fac8b) and to be recorded as its own phase/plan, not attributed here."

requirements-completed: [LEI-06, LEI-07, LEI-09]

# Metrics
duration: ~74min (Task 1-3 span) + post-checkpoint fix session
completed: 2026-07-06
---

# Phase 15 Plan 05: All-Cells UI Wiring, HNSW Routing, and Fidelity Docs Summary

**Wired `ScatterPlotView`'s cohort-mode radio pair ("Cluster all cells" default / "Transfer from sample") to `CohortClusterModel.writeClusterAllCells` with a soft-ceiling confirm, per-phase progress, a working Cancel, and a graceful (non-fabricated) recall status line; routed the single-image/preview Leiden fit through the same HNSW `clusterViaAnn` path; and, after the human-verify checkpoint caught a stale-legend defect, fixed the post-write re-sync so the scatter legend, overlay, and Assign-Clusters dialog all reflect the final all-cells partition instead of the preview subsample.**

## Performance

- **Duration:** ~74 min (Task 1 commit `637b50f` at 13:39:56 to the post-checkpoint fix's final test commit `4dfd384` at 14:53:44, 2026-07-06, local +10:00) plus the intervening manual GUI verification time (not counted as agent work)
- **Started:** 2026-07-06T13:39:56+10:00
- **Completed:** 2026-07-06T14:53:44+10:00
- **Tasks:** 3 automated tasks + 1 human-verify checkpoint (approved on the second pass, after a post-checkpoint bug fix)
- **Files modified:** 5 (ScatterPlotView.java, CohortClusterModel.java, CohortClusterModelTest.java, USER_GUIDE.md, CLAUDE.md)

## Accomplishments

- Single-image / interactive-preview Leiden fit now builds its kNN graph via `LeidenModel.clusterViaAnn` (HNSW) instead of the brute-force `LeidenModel.cluster` call (D-06, the single-image clause of LEI-07); the rare `AnnRecallException` from an interactive fit is caught and surfaced to the status line without crashing the Recompute thread or leaving controls disabled.
- A project-scope-only `RadioButton`/`ToggleGroup` pair -- "Cluster all cells" (default) and "Transfer from sample" -- is visible exactly when `scope == PROJECT && method == LEIDEN` (driven by both the method-combo listener and `applyScopeOverrides()`, not duplicated logic); k-means and single-image scope are unaffected.
- `writeClusterAllCellsAcrossProject()` mirrors the existing confirm-thread-progress-finally shape: a cheap count-only pre-scan estimates the pooled cell count for the soft-ceiling confirm (D-10, configurable `celltune.allCellsSoftCeiling`, default 50,000,000); a `CancellationToken` backs a Cancel button wired into the existing controls-disabled lifecycle (D-12); per-phase status messages (Pooling / Building kNN graph / Running Leiden / Writing X/N) stream from `CohortClusterModel.writeClusterAllCells`'s log/progress callbacks; on `AllCellsResult.aborted` (recall-gate failure) an actionable error is surfaced and no re-sync happens.
- Post-checkpoint bug fix (found during the user's first manual verification pass): the all-cells write previously re-synced only the on-slide overlay via `activateClusterMapper`, leaving the scatter legend and "Assign Cohort Clusters to Classes" dialog showing the stale preview subsample's cluster count/heatmap. Fixed by (1) `CohortClusterModel.AllCellsResult` now carrying pooled centroids and per-cluster counts (`centroidsAndCounts`) alongside the pooled mean/sd, (2) `ScatterPlotView` installing the full all-cells fit-state (`fitNClusters`, `fitCentroids`, new `fitAllCellsCounts` field, `fitMarkers`, `fitMean`, `fitSd`) on the FX thread after a successful write and clearing the now-stale Leiden reference, and (3) a new `allCellsWriteActive` flag routing `applyClustersToClasses()`'s project-scope assignment through the new `CohortClusterModel.assignAcrossProjectByMeasurement` (decodes each cell's already-written `Cluster` value) instead of the preview-reference transfer path, which would otherwise contradict what was actually persisted.
- USER_GUIDE.md S11.5/S11.6 documents both cohort modes, the single-image HNSW routing, the recall-gate abort behaviour, the soft-ceiling/progress/Cancel batch UX, the preview-vs-persisted divergence, and a "Fidelity vs stock scanpy" note covering the three documented gaps (CPM not modularity/RBConfiguration, SNN/Jaccard weighting not UMAP fuzzy-simplicial connectivities, no PCA before neighbours). CLAUDE.md extended with the hnswlib-core dependency note, the packed-UUID write-back deviation, the cohort-mode radio wiring, and the reproducibility caveat.
- Full `./gradlew test` and `./gradlew spotlessCheck` green after every task and after the post-checkpoint fix; `./gradlew shadowJar` green (confirmed by the user during manual verification, hnswlib-core bundles cleanly alongside CWTS/Smile/XGBoost/LightGBM).
- Human-verify checkpoint (Task 4) approved by the user on the second pass, including a full multi-image-project run: single-image Leiden Recompute fits via HNSW with no ANN error; the cohort-mode radio pair shows/hides correctly; "Cluster all cells" advances through Pooling -> Building kNN graph -> Running Leiden -> Writing X/N; the scatter legend's cluster count equals the count written to cells and overlay colours match; Cancel mid-write reports written vs. unwritten images with already-written images retaining their Cluster measurement.

## Task Commits

1. **Task 1: Route single-image Leiden fit through HNSW + add the cohort-mode radio pair** - `637b50f` (feat)
2. **Task 2: All-cells write action -- confirm, progress, cancel, recall status, re-sync** - `3a9097b` (feat)
3. **Task 3: Document all-cells mode + scanpy-fidelity gaps** - `9422c2d` (docs)
4. **Task 4: Human verification of the all-cells GUI flow** - checkpoint; first pass found the stale-legend/Assign-dialog defect described above

Post-checkpoint bug fix (Rule 1 - bug, found during the user's manual re-verification):
- `1838400` (fix) - `AllCellsResult` carries all-cells centroids/counts + measurement-based assign (model layer)
- `b569a5b` (fix) - re-sync legend/Assign dialog to the all-cells partition after write (UI layer)
- `4dfd384` (test) - unit tests for `centroidsAndCounts` and the measurement-based assignment seams

**Plan metadata:** (this commit)

_Note: the user then re-verified the full GUI flow, including on a full multi-image project, and approved._

## Files Created/Modified

- `src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java` - HNSW routing for the single-image/preview fit; cohort-mode radio pair + visibility binding; `writeClusterAllCellsAcrossProject()` (confirm/progress/cancel/recall/re-sync); post-checkpoint fix installing the full all-cells fit-state and the `allCellsWriteActive`-gated measurement-based assignment dispatch.
- `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` - `AllCellsResult` extended with pooled centroids/counts/mean/sd (`centroidsAndCounts`); new `assignAcrossProjectByMeasurement`, `classForMeasurementValue`, `assignmentsFromMeasurement` seams for measurement-based cluster->class assignment.
- `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` - Tests for `centroidsAndCounts` (basic multi-cluster, empty-cluster-stays-zero, defensive skip of out-of-range/sentinel labels) and `classForMeasurementValue`/`assignmentsFromMeasurement` (NaN/unclustered/unmapped handling, full matched object/class extraction against real `PathObject`s).
- `USER_GUIDE.md` - S11.5/S11.6 all-cells mode, HNSW routing, recall gate, batch UX, preview-vs-persisted divergence, and the three fidelity-gap note.
- `CLAUDE.md` - hnswlib-core dependency note, all-cells two-pass mode + packed-UUID deviation, cohort-mode radio wiring, reproducibility caveat, fidelity gaps.

## Decisions Made

See `key-decisions` in frontmatter: (1) the D-09 recall status line is softened (shown only when a real value exists) rather than fabricated, given `AllCellsResult.recall`'s documented -1.0 sentinel carried over from 15-04; (2) the post-checkpoint stale-legend/Assign-dialog defect was a real bug (Rule 1), fixed via a fit-state re-sync + a new measurement-based assignment path, not a scope change; (3) the conditional-PCA commits visible adjacent in the git log are separate, later work and are explicitly NOT attributed to this plan.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Scatter legend and Assign-Clusters dialog did not re-sync to the all-cells partition after write**
- **Found during:** Task 4 (human verification, first pass)
- **Issue:** `writeClusterAllCellsAcrossProject` called `activateClusterMapper` (on-slide overlay color ramp) after a successful write, but never updated the shared fit-state (`fitNClusters`/`fitCentroids`) that the scatter legend and the "Assign Cohort Clusters to Classes" dialog read. Both continued showing the last preview Recompute's subsample cluster count/heatmap (e.g. 27) instead of the final all-cells count (e.g. 38) actually written to cells.
- **Fix:** `CohortClusterModel.AllCellsResult` now returns pooled centroids and per-cluster counts (`centroidsAndCounts`) plus the pooled mean/sd; `ScatterPlotView` installs the complete all-cells fit-state on the FX thread after a successful (non-cancelled, non-aborted) write -- `fitNClusters`, `fitCentroids`, a new `fitAllCellsCounts` field, `fitMarkers`, `fitMean`, `fitSd` -- and clears the now-stale `fitLeidenReference`/`fitLeidenReferenceLabels`. A new `allCellsWriteActive` flag (set on that successful write, cleared on the next preview Recompute) routes `applyClustersToClasses()`'s project-scope assignment through `CohortClusterModel.assignAcrossProjectByMeasurement` (reads each cell's already-written `Cluster` value) instead of the stale preview-reference transfer path.
- **Files modified:** `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java`, `src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java`, `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java`
- **Verification:** `./gradlew compileJava`/`test`/`spotlessCheck` green after each fix commit; the user manually re-verified the full GUI flow -- including a full multi-image project -- and confirmed the scatter legend's cluster count now equals the count written to cells with matching overlay colours, and approved.
- **Committed in:** `1838400`, `b569a5b`, `4dfd384`

---

**Total deviations:** 1 auto-fixed (1 bug, found via the human-verify checkpoint and fixed across 3 commits before final approval)
**Impact on plan:** The fix was necessary for the plan's own LEI-09 success criterion (legend/overlay must reflect the final all-cells cluster count, not the preview subsample) -- not scope creep. No architectural change; the fix reused the existing fit-state fields plus one new field (`fitAllCellsCounts`) and one new Project-free model seam pattern already established in Plan 04.

## Issues Encountered

None beyond the deviation documented above. The checkpoint's normal function (catching a real GUI defect that has no headless test coverage, per the plan's own rationale for the human-verify gate) worked as intended.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- Phase 15 (all-cells true-scanpy Leiden clustering) now has all 5 plans (15-01 through 15-05) executed and their human-verify checkpoint approved, including on a full multi-image project.
- `AllCellsResult.recall`'s -1.0 sentinel (carried from 15-04, softened rather than fabricated in this plan's status line per D-09) remains a known, documented gap; a future small `LeidenModel.clusterViaAnn` API change could surface the real measured recall end-to-end if a later phase wants the exact "ANN recall 0.982 -- passed" wording.
- Conditional PCA (`ScatterMath.pcaReduce`, commits `41415fa`..`82fac8b`) was added after this plan's checkpoint approval but is separate work, not part of 15-05 -- it should be recorded under its own phase/plan when that work is finalized.
- Phase-level verification (VERIFICATION.md) and marking the phase/REQUIREMENTS.md complete are explicitly deferred to the orchestrator's next step, not performed by this plan's execution.

---
*Phase: 15-all-cells-leiden-clustering*
*Completed: 2026-07-06*

## Self-Check: PASSED

All claimed files found on disk (`ScatterPlotView.java`, `CohortClusterModel.java`, `CohortClusterModelTest.java`, `USER_GUIDE.md`, `CLAUDE.md`, this SUMMARY.md); all task and post-checkpoint-fix commits (`637b50f`, `3a9097b`, `9422c2d`, `1838400`, `b569a5b`, `4dfd384`) found in git history via `git log --oneline`.
