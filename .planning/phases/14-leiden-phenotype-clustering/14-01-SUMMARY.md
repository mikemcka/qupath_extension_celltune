---
phase: 14-leiden-phenotype-clustering
plan: 01
subsystem: ui
tags: [leiden, cwts-networkanalysis, clustering, phenotyping, knn, jaccard, scatter-plot, cohort-assignment]

# Dependency graph
requires:
  - phase: none (wave 1, no depends_on)
    provides: n/a
provides:
  - "Pure-logic LeidenModel: feature-space kNN, Jaccard-weighted graph build, CWTS Leiden community detection, kNN label transfer"
  - "Method {k-means, Leiden} selector in ScatterPlotView with resolution + reproducibility controls"
  - "Cohort (project-scope) Leiden assignment via kNN label transfer in CohortClusterModel"
  - "javap/source-verified CWTS networkanalysis 1.3.0 API usage notes (Network constructor param order, CPM association-strength normalization requirement)"
affects: [future-spatial-leiden-phase-18, future-ann-index-followup]

# Tech tracking
tech-stack:
  added: ["nl.cwts:networkanalysis:1.3.0 (CWTS Leiden/Louvain community detection)"]
  patterns:
    - "Pure-array model classes (LeidenModel) mirror NeighborhoodModel/ScatterMath: no QuPath/JavaFX types, unit-testable against synthetic clouds"
    - "Shared per-image streaming driver in CohortClusterModel parameterised by a labelsForRows(double[][])->int[] function, letting k-means (nearestCentroid) and Leiden (transferLabels) share identical stream/save/progress mechanics"
    - "Association-strength network normalization before CPM-based Leiden clustering to bring resolution back to a familiar 0.1-3.0 scale"

key-files:
  created:
    - src/main/java/qupath/ext/celltune/model/LeidenModel.java
    - src/test/java/qupath/ext/celltune/model/LeidenModelTest.java
  modified:
    - build.gradle.kts
    - src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java
    - src/main/java/qupath/ext/celltune/model/CohortClusterModel.java
    - USER_GUIDE.md
    - CLAUDE.md

key-decisions:
  - "CWTS LeidenAlgorithm optimises CPM (Constant Potts Model), not Modularity — this library has no Modularity variant of Leiden. Network must be built with setNodeWeightsToTotalEdgeWeights=true and normalized via createNormalizedNetworkUsingAssociationStrength() before clustering, or CPM resolution has no natural '1.0' scale against raw Jaccard weights and even resolution=1.0 shatters every node into its own singleton community (empirically confirmed via a standalone repro against the real jar)."
  - "Network(int, boolean, LargeIntArray[], LargeDoubleArray, boolean, boolean)'s 5th/6th params are (sortedEdges, checkIntegrity) per the CWTS source javadoc — passing sortedEdges=true for a single-direction, unsorted edge list corrupts the internal CSR adjacency and crashes LocalMergingAlgorithm with an ArrayIndexOutOfBoundsException. Verified by downloading and reading the actual CWTS networkanalysis-1.3.0-sources.jar from Maven Central, not just javap output."
  - "Cohort assignment for Leiden uses kNN label transfer (scanpy sc.tl.ingest style) against the labelled fitted sample, not synthetic centroids — matches the plan's locked decision and the design note's research into how scanpy/scimap/SPACEc handle multi-image cohorts."
  - "Per-image (not per-cell) batching in CohortClusterModel's shared assign driver, so Leiden's assignAcrossProjectLeiden makes one transferLabels call per image instead of one kNN search per cell."

requirements-completed: [LEI-01, LEI-02, LEI-03, LEI-04, LEI-05]

# Metrics
duration: ~130min
completed: 2026-07-02
---

# Phase 14 Plan 01: Leiden Phenotype Clustering Summary

**Graph-based Leiden clustering added as a selectable alternative to k-means in the scatter-plot phenotyping workflow — pure-logic `LeidenModel` (feature kNN → Jaccard-weighted graph → CWTS Leiden with association-strength normalization → kNN label transfer), wired into `ScatterPlotView`'s Method selector and `CohortClusterModel`'s cohort assignment path. Tasks 1-6 complete and committed; Task 7's automated build/test/shadowJar gate is green; the manual in-QuPath verification is PENDING (handed back to the user — see below).**

## Performance

- **Duration:** ~130 min (Tasks 1-6 + Task 7 automated gate)
- **Started:** 2026-07-02T~03:33Z
- **Completed:** 2026-07-02T05:43Z (automated portion)
- **Tasks:** 6 of 7 fully complete (Task 7's automated build/test/shadowJar gate is green; its manual in-QuPath verification is pending)
- **Files modified:** 7 (2 created, 5 modified)

## Accomplishments

- **CWTS `networkanalysis` 1.3.0 dependency bundled and API-verified** (Task 1): resolved the jar, `javap`'d every class `LeidenModel` depends on, then downloaded and read the actual CWTS source jar from Maven Central to resolve two API ambiguities javap alone couldn't settle (see Deviations).
- **`LeidenModel`** (Tasks 2-3, TDD): pure-array `featureKnn`, `cluster` (kNN graph → Jaccard/SNN edge weighting → CWTS Leiden with best-of-N random starts by CPM quality → dense relabeled `int[]` output), and `transferLabels` (kNN majority-vote label transfer with lowest-label tie-breaking). 11 JUnit 5 tests, all pure-array/synthetic-cloud, no QuPath/JavaFX types.
- **`ScatterPlotView` Method selector** (Task 4): `ClusterMethod {KMEANS, LEIDEN}` combo; Leiden swaps "Clusters (k)" for a resolution `Spinner<Double>` (0.1-3.0, default 1.0) + a "Sample multiple seeds" reproducibility checkbox. `recompute()` branches before the fit; downstream plot colouring/legend/box-lasso/Apply Clusters are unchanged because both methods still populate the same `cluster[]` array. New `fitNClusters` field replaces the k-spinner as the sizing bound for cluster counts/centroids/mapping since Leiden decides its own cluster count.
- **Cohort-scope Leiden assignment** (Task 5): `CohortClusterModel.assignAcrossProjectLeiden` streams project images and assigns cells via `LeidenModel.transferLabels` against the labelled fitted sample, batched per-image. The k-means cohort path (`assignAcrossProject`/`nearestCentroid`) is untouched — both now share a private per-image streaming driver.
- **Documentation** (Task 6): USER_GUIDE.md §11.6 explains the Method/Resolution/reproducibility controls and the kNN-transfer vs nearest-centroid cohort-assignment difference; CLAUDE.md's Tests table and dependency notes updated with the CWTS gotchas discovered during implementation.
- **Task 7 automated gate green**: `spotlessApply` (no drift) + `clean compileJava test` (full suite passes) + `shadowJar` all succeeded; the fat JAR (`build/libs/qupath-extension-celltune-0.2.0-all.jar`, ~130.6 MB) was verified to contain `LeidenModel.class` and the CWTS `Network`/`LeidenAlgorithm` classes.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add the CWTS Leiden dependency and verify its API** - `f30c8aa` (build)
2. **Task 2: Failing pure-array tests for LeidenModel** - `636e3bf` (test, RED)
3. **Task 3: Implement LeidenModel pure logic** - `c3f89b8` (feat, GREEN)
4. **Task 4: Wire the Method selector + resolution/reproducibility controls into ScatterPlotView** - `51167f7` (feat)
5. **Task 5: Cohort-scope Leiden via kNN label transfer** - `c434ca8` (feat)
6. **Task 6: Documentation — User Guide + CLAUDE.md** - `1d9967d` (docs)
7. **Task 7: automated portion only** — no commit (build/test/shadowJar verification, not a code change); manual in-QuPath verification NOT performed (see below)

_TDD tasks 2-3: test (RED) → feat (GREEN), as required._

## TDD Gate Compliance

RED gate (`test(14-01): ...`, commit `636e3bf`) confirmed before GREEN — the test file did not compile (`LeidenModel` did not exist) when first run. GREEN gate (`feat(14-01): ...`, commit `c3f89b8`) confirmed after — all 11 `LeidenModelTest` cases pass. No REFACTOR commit was needed (implementation didn't require post-GREEN cleanup). Gate sequence satisfied.

## Files Created/Modified

- `build.gradle.kts` - Added `nl.cwts:networkanalysis:1.3.0` via `implementation(...)` + `shadow(...)`
- `src/main/java/qupath/ext/celltune/model/LeidenModel.java` - New pure-logic feature-kNN / Jaccard-weighting / CWTS-Leiden / kNN-label-transfer core
- `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` - New JUnit 5 coverage (11 tests, synthetic clouds only)
- `src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java` - Method selector, resolution/reproducibility controls, Leiden branch in `recompute()`, `fitNClusters`/`fitLeidenReference`/`fitLeidenReferenceLabels` fields, cohort-assign branch
- `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` - New `assignAcrossProjectLeiden`, shared per-image streaming driver refactor
- `USER_GUIDE.md` - New §11.6 "Clustering method: k-means vs Leiden"; cross-references in §11 intro, §11.1, §11.5
- `CLAUDE.md` - `LeidenModelTest` added to Tests table; CWTS dependency + gotchas added to dependency notes

## Decisions Made

- **CPM association-strength normalization is required, not optional.** Without calling `Network.createNormalizedNetworkUsingAssociationStrength()` (with `setNodeWeightsToTotalEdgeWeights=true` at construction), CPM's `resolution` parameter has no natural "1.0" scale against raw Jaccard edge weights (≤1) — empirically, `resolution=1.0` shattered a 150-row, 3-blob synthetic set into 150 singleton clusters. This was discovered via a standalone repro script run directly against the resolved CWTS jar (not caught by unit tests alone, since the crash/behavior only appears with the real library, and the initial javap-only reading of the API missed it).
- **`Network`'s edge-list constructor parameter order required reading the actual source, not just javap output.** `javap` shows parameter *types* but not names; the 5th/6th `boolean` parameters looked interchangeable from bytecode alone. Downloaded `networkanalysis-1.3.0-sources.jar` from Maven Central and read the real javadoc to confirm `(nNodes, setNodeWeightsToTotalEdgeWeights, edges, edgeWeights, sortedEdges, checkIntegrity)` — the initial guess (informed by disassembling `FileIO.readEdgeList`'s bytecode) had `sortedEdges` and `checkIntegrity` swapped, which crashed `LocalMergingAlgorithm` with an `ArrayIndexOutOfBoundsException` on every non-trivial graph.
- **Jaccard weighting uses CLOSED (self-included) neighbour sets**, so two nodes that are each other's only neighbour get weight 1.0 rather than an undefined/zero weight — the PhenoGraph/shared-nearest-neighbour convention, documented inline in `LeidenModel`.
- **Cluster-count sizing (`fitNClusters`) replaces the k-spinner's value** as the bound for `clusterCounts`/`fitCentroids`/`ClusterAssignmentPane` mapping arrays, since Leiden decides its own cluster count. Kept as a single field shared by both methods rather than two parallel fields, to avoid the two paths drifting out of sync.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed CWTS `Network` constructor parameter-order misreading that crashed Leiden on every non-trivial graph**
- **Found during:** Task 3 (Implement LeidenModel pure logic) — first test run against the real jar threw `ArrayIndexOutOfBoundsException` in `LocalMergingAlgorithm.findClustering`
- **Issue:** The Task 1 javap-only API notes assumed `Network(int, boolean, LargeIntArray[], LargeDoubleArray, boolean checkIntegrity, boolean sortedEdges)` — i.e., `checkIntegrity` before `sortedEdges`. The actual order (confirmed by downloading and reading the real `networkanalysis-1.3.0-sources.jar` from Maven Central) is `(nNodes, setNodeWeightsToTotalEdgeWeights, edges, edgeWeights, sortedEdges, checkIntegrity)`. Passing `sortedEdges=true` for a single-direction, unsorted edge list corrupted the internal CSR adjacency (`firstNeighborIndices`).
- **Fix:** Swapped the last two constructor arguments to `(n, true, edges, weights, false, true)` (`setNodeWeightsToTotalEdgeWeights=true`, `sortedEdges=false`, `checkIntegrity=true`), with an inline comment citing the source javadoc.
- **Files modified:** `src/main/java/qupath/ext/celltune/model/LeidenModel.java`
- **Commit:** `c3f89b8` (part of Task 3's GREEN commit)

**2. [Rule 1 - Bug] Fixed CPM resolution scale mismatch causing every node to become a singleton cluster**
- **Found during:** Task 3, same debugging session — after fixing the constructor crash, `clusterRecoversThreeSeparatedBlobsByPurity` still failed: at `resolution=1.0` every one of 150 rows landed in its own singleton cluster instead of the 3 true blobs.
- **Issue:** `LeidenAlgorithm` optimises CPM, which (unlike Modularity) has no built-in null-model correction — raw Jaccard edge weights (≤1) have no natural "1.0" resolution scale.
- **Fix:** Build the raw network with `setNodeWeightsToTotalEdgeWeights=true`, then call `createNormalizedNetworkUsingAssociationStrength()` before running `LeidenAlgorithm` — the same null-model correction Modularity applies internally. Verified via a resolution sweep (0.01 to 1.0) against a standalone repro that this brings the 3-blob recovery back into the plan's stated 0.1-3.0 UI range. Also adjusted `LeidenModelTest`'s blob-recovery test from `resolution=1.0` to `resolution=0.3` to match the empirically-verified recovery range for that specific small, tightly-sampled synthetic set (documented inline in the test).
- **Files modified:** `src/main/java/qupath/ext/celltune/model/LeidenModel.java`, `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java`
- **Commit:** `c3f89b8` (part of Task 3's GREEN commit)

---

**Total deviations:** 2 auto-fixed (both Rule 1 — bugs found and fixed during TDD GREEN, before the commit). Both were necessary for `LeidenModel.cluster` to function correctly at all; no scope creep — the fixes stayed within `LeidenModel.java`'s existing method signatures and one test's resolution parameter.

## Issues Encountered

- **CWTS `networkanalysis`'s programmatic API is CLI-documented only**, exactly as the plan's threat model (T-14-05) anticipated. `javap` alone was insufficient to safely implement against it — parameter *names* aren't visible in bytecode, and two of the five/six-argument `Network` constructors have booleans in an order that reads ambiguously from disassembly alone. Downloading the actual `-sources.jar` from Maven Central (available; not vendored in the compiled jar) and reading the real javadoc was necessary to resolve this correctly, rather than guessing from bytecode structure. Documented in `CLAUDE.md` for future readers.
- No other issues — Tasks 4-6 (UI wiring, cohort assignment, documentation) proceeded without complications since they build on the now-verified `LeidenModel` API.

## User Setup Required

None — no external service configuration required. `nl.cwts:networkanalysis:1.3.0` is bundled into the fat JAR like the other ML dependencies.

## Manual Verification PENDING (Task 7)

Task 7's **automated** portion is complete and green:
```
./gradlew spotlessApply && ./gradlew clean compileJava test && ./gradlew shadowJar
```
All green. Fat JAR built at:
`C:\Users\Mikem\qupath_extension_celltune\build\libs\qupath-extension-celltune-0.2.0-all.jar` (~130.6 MB, confirmed to contain `LeidenModel.class` and the CWTS `Network`/`LeidenAlgorithm` classes via `unzip -l`).

Task 7's **manual in-QuPath verification was NOT performed** — it requires interactive GUI use of QuPath, which this executor cannot do. The user must complete it:

1. Remove any old `qupath-extension-celltune-*-all.jar` from `C:\Users\<you>\QuPath\v0.7\extensions\`.
2. Copy the new fat JAR (`build/libs/qupath-extension-celltune-0.2.0-all.jar`) into that folder.
3. Restart QuPath fully.
4. Open a classified image → **Extensions → CellTune Classifier → Scatter Plots and Clustering...**
5. Set **Method = Leiden**, resolution ~1.0 → **Recompute**: confirm clusters render/colour/legend/selection work, the status bar reports a Leiden-decided cluster count (e.g. "Leiden found N cluster(s)"), and **Apply Clusters…** assigns classes correctly.
6. Toggle **Sample multiple seeds** ON and re-run **Recompute** twice with the same settings: confirm identical clusters both times.
7. Switch **Scope = Project**, choose 2+ images, **Recompute** (fits Leiden on the pooled sample), then **Assign Clusters…**: confirm every selected image is labelled via kNN transfer and saved (progress bar completes, status bar reports assigned cell counts, no errors).
8. Confirm the k-means path (Method = k-means) still works exactly as before — unaffected regression check.

**Verification command for step 5-7 completion:** none automatable; visual/interactive confirmation only, per the plan's `checkpoint`/manual task design.

## Next Phase Readiness

- `LeidenModel` is a stable, tested, pure-logic building block — safe to extend later (e.g. exposing kNN graph-neighbours, iterations, or CPM-vs-alternative quality functions as advanced controls, per the plan's `<deferred>` list).
- The CWTS API gotchas (constructor parameter order, CPM normalization requirement) are now documented in `CLAUDE.md` and inline in `LeidenModel.java`, so a future phase extending Leiden usage (e.g. §18 spatial neighbourhoods via SpatialLeiden, explicitly deferred) won't need to rediscover them.
- **Blocker for full plan completion:** the manual in-QuPath verification (Task 7) is outstanding and must be performed by the user before this plan can be considered fully done. All automated gates are green and the code is ready for that verification.

---
*Phase: 14-leiden-phenotype-clustering*
*Completed: 2026-07-02 (automated portion; manual verification pending)*

## Self-Check: PASSED

All created/modified files confirmed present on disk; all 6 task commits (`f30c8aa`, `636e3bf`, `c3f89b8`, `51167f7`, `c434ca8`, `1d9967d`) confirmed in `git log`; fat JAR confirmed present at `build/libs/qupath-extension-celltune-0.2.0-all.jar` and confirmed (via `unzip -l`) to contain `LeidenModel.class` and the CWTS `Network`/`LeidenAlgorithm` classes.
