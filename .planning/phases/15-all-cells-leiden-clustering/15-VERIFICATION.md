---
phase: 15-all-cells-leiden-clustering
verified: 2026-07-06T07:49:43Z
status: passed
score: 20/20 must-haves verified (across 5 plans; 1 via override)
overrides_applied: 1
overrides:
  - must_have: "The run reports per-phase progress (Pooling/Building graph/Running Leiden/Writing) and the measured ANN recall to the status line; a Cancel button interrupts mid-run"
    reason: "LeidenModel.clusterViaAnn does not surface its internal recall-gate measurement end-to-end (AllCellsResult.recall is a documented -1.0 sentinel, carried from 15-04 through 15-05). The status line shows the measured recall ONLY when a real value is available and omits the clause otherwise -- no fabricated number is ever shown. The recall GATE itself (LEI-07, >=0.95 threshold with escalate-then-abort) is fully implemented, enforced, and tested (LeidenModel.gateAnnRecall / AnnRecallException) -- only the user-facing status-line NUMBER is deferred pending a future small LeidenModel API change. Per-phase progress and Cancel (the rest of this must-have) are fully implemented and were manually verified/approved by the user on a full multi-image project."
    accepted_by: "orchestrator (pre-authorized per task briefing; consistent with the user's own approval of the 15-05 human-verify checkpoint, which ran with this exact softened status line)"
    accepted_at: "2026-07-06T07:49:43Z"
---

# Phase 15: All-Cells Leiden Clustering Verification Report

**Phase Goal:** Replace the Phase 14 cohort kNN label-transfer with a single all-cohort clustering (sc.tl.leiden-style): pool every cell across all project images into one feature matrix, build an approximate-NN (HNSW) kNN graph over all cells, SNN/Jaccard-weight it, run one CWTS Leiden partition, and write each cell's community label back to its source image — no label transfer.
**Verified:** 2026-07-06T07:49:43Z
**Status:** passed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (merged from ROADMAP LEI-06..10 + all 5 PLAN must_haves)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Fat JAR bundles hnswlib-core so ANN classes load at runtime | VERIFIED | `build.gradle.kts:52-53` has `implementation`/`shadow` for `com.github.jelmerk:hnswlib-core:1.2.1`; `./gradlew shadowJar` green; `jar tf build/libs/*-all.jar \| grep com/github/jelmerk` returns 53 classes |
| 2 | HNSW index buildable/queryable over double[][] rows for kNN (Euclidean) | VERIFIED | `HnswKnnIndex.knn`/`build`/`queryRow` (`HnswKnnIndex.java:98-235`) use `DistanceFunctions.DOUBLE_EUCLIDEAN_DISTANCE`; `HnswKnnIndexTest` (7 tests) covers recall vs exact, self-exclusion, length, degenerate n=0/1 — all green |
| 3 | Reproducible builds are deterministic build-to-build | VERIFIED | `HnswKnnIndex.build(reproducible=true)` uses single-threaded fixed-order `add()` (best-effort, documented in class javadoc); `reproducibleBuildIsByteIdenticalAcrossTwoConsecutiveRuns` test passes |
| 4 | Query-time ef raisable via setEf without rebuild | VERIFIED | `HnswKnnIndex.setEf(int)` (`HnswKnnIndex.java:174-178`) mutates the live index; `setEfEscalationRaisesOrMaintainsRecallWithoutRebuilding` test passes; used by `LeidenModel.annNeighborsWithGate` via `index.setEf(ef)` inside the gate's escalation closure |
| 5 | Leiden kNN graph buildable from HNSW instead of brute-force, single-image and cohort | VERIFIED | `LeidenModel.clusterViaAnn` (`LeidenModel.java:393-426`) and `annNeighborsWithGate` route through `HnswKnnIndex`; called from both `ScatterPlotView`'s single-image/preview fit (`ScatterPlotView.java:1019-1025`) and `CohortClusterModel.writeClusterAllCells` (`CohortClusterModel.java:1106`) |
| 6 | Each ANN Leiden run measures recall on a capped subsample vs exact featureKnn, proceeds only when recall >= 0.95 | VERIFIED | `LeidenModel.gateAnnRecall` (`LeidenModel.java:287-312`): sample size `min(10_000, max(1,round(n*0.001)))`, exact reference via `exactNeighborsForQueries` (O(sample×n), never `featureKnn`'s O(n²)), threshold `RECALL_THRESHOLD=0.95` |
| 7 | Recall <0.95 after ef escalation aborts via AnnRecallException, no labels written | VERIFIED | `gateAnnRecall` throws `AnnRecallException` after `MAX_ESCALATIONS=4` failed doublings (`LeidenModel.java:301-310`); `clusterViaAnnAbortsWhenRecallGateFails` + `recallGateEscalatesThenAbortsWhenAnnNeverImproves` tests pass; `CohortClusterModel.clusterOrAbort` converts the exception before pass 2 ever starts — `abortWritesNothingWhenRecallGateFails` test confirms |
| 8 | HNSW-routed Leiden agrees with exact-kNN Leiden by ARI on synthetic data | VERIFIED | `clusterViaAnnAgreesWithExactClusterByAri` test (ARI >= 0.85 threshold) passes |
| 9 | SNN/Jaccard weighting scales via primitive sorted-array merge-intersection, no boxed HashSet | VERIFIED | `computeJaccardEdges`/`buildClosedNeighborArrays`/`jaccardPrimitive` (`LeidenModel.java:553-660`) — zero live `HashSet<Integer>`/`HashSet<Long>` usage; the only 4 `HashSet<Integer>` string matches in the file are javadoc `{@code}` references to the retired pattern (grep-verified) |
| 10 | Primitive-array weighting is byte-identical to boxed implementation on small inputs | VERIFIED | `jaccardEdgesForTestMatchesBoxedReferenceOnVariousSizes` (n=10..200, k=2/5/15) asserts exact `==` weight equality against a boxed reference kept test-file-only |
| 11 | clusterViaAnn assigns every pooled row a label from a single partition (no reference/query split) | VERIFIED | `clusterViaAnnAssignsEveryRowFromSinglePartitionAndRecoversBlobsByPurity` asserts `labels().length == rows.length` and per-blob purity > 0.9 |
| 12 | Two consecutive reproducible clusterViaAnn runs yield identical labels up to permutation | VERIFIED | `clusterViaAnnReproducibleRunsAreIdenticalUpToPermutation` asserts ARI==1.0 across two seeded runs |
| 13 | Pass 1 pools every cell's z-scored row + packed (long,long) UUID across all images, releasing hierarchies | VERIFIED | `CohortClusterModel.poolAllCells` (`CohortClusterModel.java:772-866`) streams every image (no sample cap), captures `getID().getMostSignificantBits()/getLeastSignificantBits()` (not `.toString()`), does not retain `ImageData` past its loop iteration |
| 14 | A single clusterViaAnn partition runs over the fully pooled matrix, no per-image sub-clustering | VERIFIED | `writeClusterAllCells` calls `LeidenModel.clusterViaAnn(clusterMatrix, ...)` exactly once (`CohortClusterModel.java:1105-1106`) |
| 15 | Pass 2 re-reads each image and writes Cluster keyed on UUID, reorder-safe | VERIFIED | `labelMapForImage` (`CohortClusterModel.java:887-896`) + pass-2 UUID lookup (`CohortClusterModel.java:1152-1172`); `uuidWriteBackSurvivesReorder` test reads image B's cells shuffled AND reversed and confirms correct labels by UUID, not position |
| 16 | Cancellation honored at phase boundaries and between images; already-written images keep their Cluster; summary reports written vs unwritten | VERIFIED | `CancellationToken` checked in `poolAllCells` (top of per-image loop) and `runPass2Loop` (top of each iteration, `CohortClusterModel.java:1235-1258`); `cancelLeavesWrittenIntactAndReportsUnwrittenImages` + `noCancellationProcessesEveryImage` + `cancellationTokenReflectsCancelCall` tests pass |
| 17 | AnnRecallException propagates so NO Cluster measurement is written on any image | VERIFIED | `clusterOrAbort` (`CohortClusterModel.java:996-1002`) catches the exception BEFORE pass 2's loop starts; `writeClusterAllCells` returns immediately with `aborted=true`, zero images processed |
| 18 | Single-image/preview Leiden fit routes through clusterViaAnn (D-06), not brute-force | VERIFIED | `ScatterPlotView.java:1019-1025` calls `LeidenModel.clusterViaAnn(...)`; `grep -c 'LeidenModel.cluster('  ScatterPlotView.java` == 0 (no direct brute-force call remains) |
| 19 | Radio pair "Cluster all cells" (default)/"Transfer from sample" visible only in project scope + Leiden, hidden otherwise | VERIFIED | `updateCohortModeVisibility()` (`ScatterPlotView.java:2408-2412`): `visible = scope==PROJECT && method==LEIDEN`; driven by both `applyScopeOverrides()` and the method-combo listener; `cohortModeAllCells.setSelected(true)` sets the all-cells default |
| 20 | Choosing "Cluster all cells" runs the two-pass driver; "Transfer from sample" runs the retained Phase 14 path | VERIFIED | `ScatterPlotView.java:1769-1770` dispatches to `writeClusterAllCellsAcrossProject()`; `writeClusterAcrossProjectLeiden` call site retained (`ScatterPlotView.java:1930`) for the transfer radio |
| 21 | Soft-ceiling confirm dialog before batch when pooled cells exceed configurable 50M threshold | VERIFIED | `estimatePooledCellCount` (cheap count-only pre-scan, `ScatterPlotView.java:2212-2234`) + `ALL_CELLS_SOFT_CEILING` persistent pref (default 50,000,000) + `confirmOnFx` dialog (`ScatterPlotView.java:2073-2088`) |
| 22 | Per-phase progress (Pooling/Building graph/Running Leiden/Writing) + measured recall to status line; Cancel interrupts mid-run | PASSED (override) | Per-phase progress messages and Cancel are fully implemented and human-verified (`ScatterPlotView.java:2051,2103,1072,1103-1104` log messages; `cancelAllCellsBtn` wired to `token.cancel()`). Recall number is shown only when available (`AllCellsResult.recall` sentinel -1.0 today) — see override entry above |
| 23 | After all-cells write, legend/overlay re-syncs to the final all-cells cluster count, not preview subsample | VERIFIED | Post-checkpoint bug fix installs `fitNClusters`/`fitCentroids`/`fitAllCellsCounts` from `AllCellsResult` and calls `activateClusterMapper(viewer, fNClusters)` (`ScatterPlotView.java:2136-2161`); human-verified by the user on a full multi-image project (per task briefing) |
| 24 | USER_GUIDE and CLAUDE.md document all-cells mode + three scanpy-fidelity gaps | VERIFIED | USER_GUIDE.md §11.5 documents both cohort modes, recall gate, batch UX, and CPM/UMAP/PCA fidelity notes (`grep` confirms CPM, UMAP/connectivities, PCA all present); CLAUDE.md documents hnswlib-core dep, packed-UUID deviation, all-cells mode |

**Score:** 24/24 truths verified (23 directly verified, 1 via documented override)

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `build.gradle.kts` | hnswlib-core dependency | VERIFIED | Lines 52-53, implementation + shadow |
| `src/main/java/qupath/ext/celltune/model/HnswKnnIndex.java` | ANN kNN wrapper | VERIFIED | 244 lines, substantive, tested |
| `src/main/java/qupath/ext/celltune/model/AnnRecallException.java` | recall-gate abort exception | VERIFIED | extends RuntimeException, documented |
| `src/main/java/qupath/ext/celltune/model/LeidenModel.java` | clusterViaAnn + recall gate + primitive Jaccard | VERIFIED | 766 lines; `clusterViaAnn`, `gateAnnRecall`, `computeJaccardEdges` all present and substantive |
| `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` | writeClusterAllCells two-pass driver | VERIFIED | 1481 lines; `poolAllCells`, `labelMapForImage`, `writeClusterAllCells`, `CancellationToken`, `AllCellsResult` all present |
| `src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java` | HNSW routing + radio pair + all-cells action | VERIFIED | 2990 lines; `clusterViaAnn`, `cohortModeAllCells`, `writeClusterAllCellsAcrossProject`, `activateClusterMapper` reuse all present |
| `src/test/java/qupath/ext/celltune/model/HnswKnnIndexTest.java` | recall/determinism tests | VERIFIED | 7 tests, all pass |
| `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` | recall-gate + ARI + all-cells tests | VERIFIED | 26 tests, all pass |
| `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` | reorder/pooling/cancel/abort tests | VERIFIED | 22 tests, all pass; `uuidWriteBackSurvivesReorder` present |
| `USER_GUIDE.md` | all-cells docs + fidelity gaps | VERIFIED | §11.5/§11.6 present, CPM/UMAP/PCA gaps documented |
| `CLAUDE.md` | hnswlib/all-cells/packed-UUID notes | VERIFIED | ANN dep, all-cells cohort mode, packed-UUID deviation all documented |

### Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| `HnswKnnIndex.knn` | `HnswIndex.findNearest` | per-row parallel query loop | WIRED | `HnswKnnIndex.java:111,204` |
| `HnswKnnIndex` reproducible build | seeded level assignment | single-threaded fixed-order add | WIRED (best-effort, documented) | `HnswKnnIndex.java:148-157`; subclassing genuinely unworkable in jelmerk 1.2.1 (bytecode-verified in 15-01); fallback explicitly pre-authorized by the plan |
| `LeidenModel.clusterViaAnn` | `HnswKnnIndex.knn`/live index | ANN kNN graph build replacing featureKnn | WIRED | `LeidenModel.java:403,437-454` |
| recall gate | `AnnRecallException` | throw on recall <0.95 after escalation cap | WIRED | `LeidenModel.java:306-310` |
| `clusterViaAnn` | `buildJaccardWeightedNetwork` | unchanged downstream SNN/Jaccard + CWTS pipeline | WIRED | `LeidenModel.java:404` |
| primitive-array Jaccard weighting | `new Network(...)` | same constructor + association-strength normalization | WIRED | `LeidenModel.java:511`, `, false, true)` suffix preserved |
| `CohortClusterModel.writeClusterAllCells` | `LeidenModel.clusterViaAnn` | single all-cells partition | WIRED | `CohortClusterModel.java:1106` |
| pass 2 per-image write | packed (msb,lsb) → label map | UUID lookup, not iteration order | WIRED | `CohortClusterModel.java:1152-1167` |
| open-image write | `applyMeasurement` FX marshalling | reused verbatim | WIRED | `CohortClusterModel.java:1174` |
| single-image interactive Leiden fit | `LeidenModel.clusterViaAnn` | HNSW graph build replacing `cluster` in preview fit | WIRED | `ScatterPlotView.java:1019-1025` |
| cohort-mode radio pair | `CohortClusterModel.writeClusterAllCells` | all-cells action dispatch | WIRED | `ScatterPlotView.java:1769-1770, 2090` |
| post-run re-sync | `activateClusterMapper(viewer, allCellsNClusters)` | final all-cells cluster count, not fitNClusters | WIRED | `ScatterPlotView.java:2154` (uses `fNClusters` from `AllCellsResult`, not the preview's) |
| Cancel button | `CancellationToken.cancel()` | mid-run interruption | WIRED | `ScatterPlotView.java:461-464` |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|---------------------|--------|
| `writeClusterAllCells` labels | `labels` from `clusterOutcome.result()` | `LeidenModel.clusterViaAnn` over the pooled matrix (real cell data via `poolAllCells`) | Yes | FLOWING |
| pass-2 written `Cluster` value | `values[i]` per cell | `labelMap.get(UuidKey(...))` keyed to the real pooled labels | Yes | FLOWING |
| `AllCellsResult.recall` | `recall` field | Hardcoded `-1.0` sentinel — `clusterViaAnn` does not return recall | No | STATIC (documented, overridden — see above) |
| scatter legend `fitNClusters` after write | `fNClusters` | `AllCellsResult.nClusters()` ← real `clusterOutcome.result().nClusters()` | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Full test suite green | `./gradlew test` | BUILD SUCCESSFUL, 398 tests, 0 failures | PASS |
| Model-layer phase 15 tests green | `./gradlew test --tests HnswKnnIndexTest --tests LeidenModelTest --tests CohortClusterModelTest` | BUILD SUCCESSFUL | PASS |
| Fat JAR bundles hnswlib-core cleanly | `./gradlew shadowJar` + `jar tf ... \| grep com/github/jelmerk \| wc -l` | 53 classes present, BUILD SUCCESSFUL | PASS |
| Formatting clean | `./gradlew spotlessCheck` | BUILD SUCCESSFUL | PASS |
| No brute-force `LeidenModel.cluster(` call remains in ScatterPlotView | `grep -c 'LeidenModel.cluster(' ScatterPlotView.java` | 0 | PASS |
| No live boxed `HashSet<Integer>`/`HashSet<Long>` in the weighting path | `grep -n 'HashSet<Integer>' LeidenModel.java` | 4 matches, all javadoc `{@code}` comments, zero live code | PASS |
| All commit hashes cited across 5 SUMMARYs exist in git history | `git cat-file -e <hash>` for 17 hashes | All 17 OK | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|--------------|--------|----------|
| LEI-06 | 15-03, 15-04, 15-05 | All-cells mode: pool → single CWTS Leiden partition → write back, selectable alongside retained transfer mode | SATISFIED | `writeClusterAllCells` (single partition, `CohortClusterModel.java:1053-1211`); radio pair (`ScatterPlotView.java:432-448`); transfer path retained (`writeClusterAcrossProjectLeiden` still called) |
| LEI-07 | 15-01, 15-02, 15-05 | HNSW ANN kNN (single-image + cohort), recall validated ≥95%, auto-tune then abort | SATISFIED | `HnswKnnIndex` + `gateAnnRecall`/`clusterViaAnn` (both scopes route through it); `AnnRecallException` abort path tested |
| LEI-08 | 15-04 | Two-pass memory-safe write, packed UUID identity | SATISFIED | `poolAllCells` (pass 1, releases hierarchies) + `labelMapForImage`/pass 2 (UUID lookup); `uuidWriteBackSurvivesReorder` proves reorder-safety |
| LEI-09 | 15-05 | Preview stays subsample-based; persisted Cluster from full all-cells run; divergence surfaced | SATISFIED | Legend/overlay/Assign-dialog re-sync to `AllCellsResult.nClusters` post-write (fixed after human-verify caught the original gap); USER_GUIDE.md "Legend re-sync" bullet documents the divergence explicitly to the user |
| LEI-10 | 15-01 through 15-04 | Automated tests: pooling/identity, ANN recall gate vs exact, UUID write-back, all-cells recovery | SATISFIED | `HnswKnnIndexTest` (7), `LeidenModelTest` (26, incl. recall-gate + ARI + all-cells + reproducibility), `CohortClusterModelTest` (22, incl. UUID reorder, pooling identity, cancel, abort) — all green |

No orphaned requirements found: REQUIREMENTS.md maps only LEI-06..10 to Phase 15, and all five are claimed (across the 5 plans' `requirements:` frontmatter) and satisfied above.

Note (non-blocking, informational): REQUIREMENTS.md's checkbox list and Traceability table still show LEI-06..10 as unchecked/"Pending". This mirrors the same lag already present for other completed phases in this file (e.g., XFER-01..04, CN-01..04 from Phases 12-13 are also still "Pending" despite those phases having later phases built on top of them), so it appears to be a project-wide document-maintenance step handled outside individual phase execution (e.g., at milestone completion) rather than a Phase 15 deliverable gap. None of the 5 Phase 15 plans declared `REQUIREMENTS.md` in their `files_modified`.

### Anti-Patterns Found

None. No `TODO`/`FIXME`/`placeholder`/"not yet implemented" strings in any of the 5 modified/created production files. No empty handlers, no stubbed returns on the all-cells path. The only "boxed HashSet" text matches are javadoc explaining what was deliberately removed.

### Human Verification Required

None outstanding. The phase's one GUI checkpoint (15-05 Task 4, `gate="blocking"`) was manually run and approved by the user on a full multi-image QuPath project, per the task briefing: single-image HNSW routing confirmed with no ANN error, cohort radio pair visibility confirmed in both directions, all-cells write confirmed advancing through Pooling → Building graph → Running Leiden → Writing with legend/Assign re-sync to the all-cells count, and cancel-leaves-written-intact confirmed. This is corroborated by 15-05-SUMMARY.md's detailed account of a first-pass defect (stale legend) being caught by this exact checkpoint, fixed across 3 commits (`1838400`, `b569a5b`, `4dfd384`, all verified present in git history), and re-approved.

### Gaps Summary

No blocking gaps. One documented, pre-accepted deviation: `AllCellsResult.recall` is a `-1.0` sentinel rather than the real measured ANN recall value, because `LeidenModel.clusterViaAnn`'s public signature does not yet surface its internal `gateAnnRecall` measurement. The status line degrades gracefully (omits the recall clause rather than fabricating a number) and this was already in place — and implicitly accepted — during the human-verify checkpoint's approval. The underlying correctness gate (recall must be ≥0.95 or the run aborts with zero labels written) is fully implemented, enforced at both the single-image and cohort call sites, and covered by passing tests (`LeidenModelTest`'s recall-gate pass/escalate/abort tests, `CohortClusterModelTest`'s `abortWritesNothingWhenRecallGateFails`). This is recorded as an override above rather than a gap, per the task briefing's explicit instruction not to re-raise it as blocking.

Conditional PCA (`ScatterMath.pcaReduce`, visible integrated into `CohortClusterModel.writeClusterAllCells`'s current signature) is separate, later work per both the 15-05-SUMMARY and the task briefing, and was excluded from this verification's scope.

---

*Verified: 2026-07-06T07:49:43Z*
*Verifier: Claude (gsd-verifier)*
