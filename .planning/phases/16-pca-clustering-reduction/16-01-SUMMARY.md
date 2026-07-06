---
phase: 16-pca-clustering-reduction
plan: 01
subsystem: ml
tags: [pca, dimensionality-reduction, leiden, k-means, scanpy-fidelity, smile, java]

# Dependency graph
requires:
  - phase: 15-05
    provides: "ScatterPlotView single-image/all-cells Leiden clustering (clusterViaAnn, writeClusterAllCells, cohort-mode radio pair) that this phase's PCA step is inserted in front of"
provides:
  - "ScatterMath.pcaReduce(std, maxComponents=50, threshold=50, fitSampleCap=100k, seed=42) -> PcaReduction(reduced, applied, nComponents, cumulativeVariance, projector) -- conditional, deterministic exact-SVD PCA reduction applied after z-scoring, before the clustering kNN graph"
  - "Conditional PCA wired into: single-image/preview Leiden + k-means fit, the all-cells cohort driver (CohortClusterModel.writeClusterAllCells), and the Leiden kNN-transfer query path (queryProjector)"
  - "UI: \"Reduce dims (PCA)\" checkbox (default on) + components spinner (default 50); celltune.clusterPcaEnabled / celltune.clusterPcaComponents prefs; status line reports \"PCA: p -> k comps, X% variance\""
  - "Closes scanpy fidelity gap #3 (no PCA before neighbours) -- only 2 documented gaps (CPM vs modularity, SNN/Jaccard vs UMAP connectivities) remain"
affects: [future-clustering-work]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Conditional no-op reduction gated by column-count threshold, mirroring scanpy's sc.pp.neighbors PCA-when->50-vars behaviour -- preserves byte-identical small-panel behaviour"
    - "Fit-on-bounded-seeded-subsample, apply-to-all-rows pattern (reused from the Phase 15 ANN recall-gate sampling idiom) to bound PCA fit cost/memory independent of total pooled cell count"
    - "Centroids/heatmaps always computed in ORIGINAL marker space even when clustering ran on the PCA-reduced matrix -- pinned by a dedicated regression test"
    - "Reusable UnaryOperator<double[][]> projector returned alongside the reduced matrix so a later kNN-transfer query step can project new rows into the identical fitted PC basis"

key-files:
  created: []
  modified:
    - src/main/java/qupath/ext/celltune/model/ScatterMath.java
    - src/test/java/qupath/ext/celltune/model/ScatterMathTest.java
    - src/main/java/qupath/ext/celltune/model/CohortClusterModel.java
    - src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java
    - src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java
    - src/test/java/qupath/ext/celltune/model/LeidenModelTest.java
    - CLAUDE.md
    - USER_GUIDE.md

key-decisions:
  - "RETROACTIVE RECORD: this entire phase (design note, implementation, tests, docs) was built and committed inline during/immediately after Phase 15's execution, outside the normal plan-write-then-execute GSD flow. This PLAN/SUMMARY pair was filed afterward, purely as bookkeeping, to bring the planning artifacts (ROADMAP/STATE/REQUIREMENTS) in line with what actually shipped. No code was written or modified by the recording process itself -- see the parent orchestration turn for confirmation no src/ changes occurred during recording."
  - "PCA engine choice: reuse smile.feature.extraction.PCA (already a project dependency, used by ScatterMath.fillPca for the 2D display embedding) -- an EXACT covariance eigendecomposition, not a randomized/seeded SVD, so the reproducible-seed clustering path stays bit-stable for free."
  - "No whitening: project onto principal axes without rescaling components to unit variance, matching scanpy's behaviour of running neighbours on raw PC coordinates -- larger-variance PCs keep their weight (intended denoising behaviour)."
  - "PCA subsample-fit seed (42) is fixed and deliberately independent of any caller's own reproducibility toggle (Leiden/k-means seed), so PCA reduction itself never introduces nondeterminism regardless of that toggle's state."
  - "Per-cluster centroids/heatmaps are ALWAYS computed in original marker space, never PCA space, even when clustering itself ran on the PCA-reduced matrix -- required for the Assign-Clusters dialog and interpretive marker view to remain meaningful to users; pinned by CohortClusterModelTest.centroidsAndCountsStayInMarkerSpaceRegardlessOfPcaClusteringInput."

requirements-completed: [PCA-01, PCA-02, PCA-03, PCA-04, PCA-05, PCA-06]

# Metrics
duration: "recorded retroactively -- original inline implementation spanned commits 41415fa..decc05f (2026-07-06, ~15:58-16:00 local +10:00, ~11 min wall-clock across 6 commits)"
completed: 2026-07-06
---

# Phase 16 Plan 01: Conditional PCA Dimensionality Reduction Before Clustering Summary

**Added a conditional, deterministic PCA reduction step (scanpy's `scale -> PCA -> neighbors` recipe) between z-scoring and the clustering kNN graph across all three clustering entry points (single-image preview, all-cells cohort driver, Leiden kNN-transfer), closing the third and final documented scanpy-fidelity gap; recorded here retroactively because the work was implemented, tested, and committed inline rather than through the normal plan-then-execute flow.**

**This is a retroactive record.** The feature described below was already fully built, tested, and
merged before this PLAN/SUMMARY pair existed. This SUMMARY documents what was delivered, by which
commits, with what test coverage and real-data validation result -- it does not represent new work
performed during the recording itself. No files under `src/` were touched while writing this record.

## Performance

- **Duration:** N/A (retroactive) -- original inline delivery: commits `41415fa` (15:58:52) through `decc05f` (16:00:09), 2026-07-06, ~11 minutes wall-clock across 6 commits
- **Completed:** 2026-07-06
- **Tasks:** 4 (reconstructed from the delivered commits; see Task Commits below)
- **Files modified:** 8 (2 main sources + 1 UI source modified for wiring; 3 test files; 2 docs files)

## Accomplishments

- `ScatterMath.pcaReduce(double[][] std, int maxComponents, int threshold, int fitSampleCap, long seed)`
  returns `PcaReduction(reduced, applied, nComponents, cumulativeVariance, projector)`. Identity
  pass-through when PCA is disabled (`maxComponents <= 0`), the column count is at or below
  `threshold` (default 50, mirroring scanpy's `sc.pp.neighbors` PCA-when->50-vars behaviour), or
  there are fewer than 2 rows -- the small-curated-panel path is byte-identical to the pre-PCA
  behaviour. Otherwise reduces to `min(maxComponents, cols-1, rows-1)` components via
  `smile.feature.extraction.PCA` -- an exact covariance eigendecomposition (deterministic, no
  randomized SVD), so the reproducible-seed clustering path stays bit-stable.
- All-cells fit strategy: above `fitSampleCap` (default 100,000) rows, the projection is FIT on a
  deterministic seeded (42, independent of any caller reproducibility toggle) subsample via a new
  `ScatterMath.randomSubsample(n, count, seed)` overload, then APPLIED to every pooled row --
  bounding fit cost/memory independent of total cohort cell count. Below the cap, fits on the full
  matrix. The fitted projection is also exposed as a reusable `UnaryOperator<double[][]> projector`.
- Wired into all three clustering entry points: (1) the single-image/interactive scatter-plot
  preview fit for both Leiden and k-means (`ScatterPlotView`), (2) `CohortClusterModel.writeClusterAllCells`'s
  all-cells cohort driver, and (3) the Leiden kNN-transfer cohort-assign path, which now threads a
  `queryProjector` (identity when PCA was not applied) so each query image's z-scored rows are
  projected into the SAME PC basis the fitted Leiden reference lives in before the kNN vote.
- Per-cluster centroids and heatmaps (`CohortClusterModel.centroidsAndCounts`, the Assign-Clusters
  dialog) are always computed from the ORIGINAL marker-space matrix, never the PCA-reduced one --
  clustering can run in PC space, but the interpretive marker view must not. A dedicated regression
  test clusters a synthetic pool in PCA-reduced space and asserts the returned centroid width is
  `nMarkers`, not `nComponents`.
- UI: a "Reduce dims (PCA)" checkbox (default on) and a components spinner (default 50) sit next to
  the existing resolution/k controls, persisted as `celltune.clusterPcaEnabled` /
  `celltune.clusterPcaComponents` preferences. When reduction is applied, the status line/log reports
  `"PCA: {p} -> {nComp} comps, {variance}% variance"`.
- Test coverage: `ScatterMathTest` covers `pcaReduce`'s conditional skip-by-threshold/disable,
  determinism, bounded seeded-subsample fit-then-apply-to-all-rows, and cumulative-variance
  reporting. `LeidenModelTest` adds a synthetic dominance test: 2 true signal columns (separating 3
  known communities) drowned by 60 independent, cluster-agnostic noise columns (62 total, >
  threshold) -- both PCA-off and PCA-on run through the identical `clusterViaAnn` call; PCA-off
  collapses to a single giant community (ARI ~ 0.00) while PCA-on recovers the true communities
  (ARI > 0.8, empirically ~1.00), isolating the reduction itself as the cause. `CohortClusterModelTest`
  adds the marker-space centroid guard described above. Full suite reported green (398/398) at the
  time of delivery; `spotlessCheck` clean; `shadowJar` builds.
- Documentation: CLAUDE.md's fidelity-gap note reduced from three gaps to two (CPM vs modularity,
  SNN/Jaccard vs UMAP connectivities), with a new paragraph describing `ScatterMath.pcaReduce`'s
  wiring, defaults, determinism, the marker-space centroid contract, and the Leiden transfer
  `queryProjector`; the Tests table updated for the new `ScatterMathTest` row and the extended
  `LeidenModelTest`/`CohortClusterModelTest` coverage descriptions. USER_GUIDE.md S11.5 shrinks its
  "three fidelity gaps" callout to the two remaining ones and adds a new callout documenting the PCA
  checkbox/spinner, threshold, marker-space centroids, and the all-cells bounded-subsample fit.
- **Real-data validation** (outside this repo, in `C:\Users\Mikem\nolan_paper_validation`): the
  extension's Leiden was re-run WITH PCA on the Nolan CODEX CRC dataset (56 markers reduced to 50
  components, 98.4% cumulative variance explained) and compared against the paper's published
  clustering the same way the no-PCA baseline had been compared in the Phase 15 validation.
  Agreement improved slightly (ARI 0.204 -> 0.233), and the near-inert behaviour on this curated
  ~56-marker panel (just over the 50-column threshold) versus a larger measurement-width panel
  confirmed the conditional design is doing the right thing (a small effect on an already-curated
  panel, with the larger effect reserved for wide per-cell-measurement panels, per the synthetic
  dominance test above).

## Task Commits

Reconstructed from the delivered commit history (all 2026-07-06, local +10:00):

1. **Task 1: ScatterMath.pcaReduce — conditional dimensionality reduction** - `41415fa` (feat) + `1e873d6` (test)
2. **Task 2: Wire conditional PCA into the all-cells driver and Leiden transfer** - `91ecfaa` (feat) + `89b4433` (test)
3. **Task 3: Wire conditional PCA into the scatter-plot preview fit + UI** - `8212c1f` (feat)
4. **Task 4: Document conditional PCA, close scanpy fidelity gap #3** - `decc05f` (docs)

**This retroactive record commit:** (this commit, pending — `docs(phase-16): record conditional PCA clustering reduction as a retroactive phase`)

## Files Created/Modified

- `src/main/java/qupath/ext/celltune/model/ScatterMath.java` - Added `pcaReduce` (+ overload with scanpy-mirroring defaults), the `PcaReduction` record, and a seeded `randomSubsample(n, count, seed)` overload.
- `src/test/java/qupath/ext/celltune/model/ScatterMathTest.java` - New tests for `pcaReduce`'s conditional behaviour, determinism, subsample-fit, and variance reporting.
- `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` - Conditional PCA wired into `writeClusterAllCells` (pooled matrix reduced before `clusterViaAnn`, centroids computed in original marker space) and the Leiden kNN-transfer query path (`queryProjector`).
- `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` - Added the marker-space centroid guard test.
- `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` - Added the synthetic PCA-on-vs-off dominance ARI test.
- `src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java` - "Reduce dims (PCA)" checkbox + components spinner, `celltune.clusterPcaEnabled`/`celltune.clusterPcaComponents` prefs, preview-fit wiring, status-line variance log.
- `CLAUDE.md` - Fidelity-gap note updated (two gaps remain), `pcaReduce` wiring/contract documented, Tests table updated.
- `USER_GUIDE.md` - S11.5 fidelity-gaps callout shrunk to two; new PCA callout added (checkbox/spinner, threshold, marker-space centroids, all-cells bounded-subsample fit).

## Decisions Made

See `key-decisions` in frontmatter. Most significant: (1) this entire phase is a retroactive
bookkeeping record, not new implementation work -- the code was already built, tested, and merged
inline; (2) reused Smile's exact PCA (no randomized SVD) to keep the reproducible-seed clustering
path bit-stable for free; (3) centroids/heatmaps always stay in marker space regardless of what
space clustering ran in, enforced by a dedicated test.

## Deviations from Plan

None — this plan was authored after the work it describes was already complete, specifically to
match the delivered commits. There is no "plan vs. actual" divergence to document because the plan
text itself was reverse-derived from the actual commits (`41415fa`, `1e873d6`, `91ecfaa`, `8212c1f`,
`89b4433`, `decc05f`).

## Issues Encountered

None. (The retroactive-recording process itself encountered no blockers; see the parent
orchestration turn for confirmation that no `src/` files were modified while producing this record.)

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- All three documented scanpy fidelity gaps are now resolved to two remaining, permanently
  documented, out-of-scope differences (CPM vs modularity, SNN/Jaccard vs UMAP fuzzy-simplicial-set
  connectivities) — no further PCA-related work is anticipated for the current clustering pipeline.
- The `queryProjector` seam on the Leiden kNN-transfer path is available for any future clustering
  entry point that needs to project new rows into an existing fitted PC basis.
- Real-data validation lives in `C:\Users\Mikem\nolan_paper_validation` (external to this repo) and
  should be referenced, not duplicated, by any future comparison work.

---
*Phase: 16-pca-clustering-reduction*
*Completed: 2026-07-06*

## Self-Check: PASSED

All six delivered commits (`41415fa`, `1e873d6`, `91ecfaa`, `8212c1f`, `89b4433`, `decc05f`) verified
present in `git log --oneline --all`. Files listed above verified present on disk via the commit
diffs (`git show --stat`) inspected while authoring this record.
