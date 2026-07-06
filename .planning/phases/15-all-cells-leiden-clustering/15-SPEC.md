# Phase 15: All-Cells Leiden Clustering (True-Scanpy) — Specification

**Created:** 2026-07-06
**Ambiguity score:** 0.15 (gate: ≤ 0.20)
**Requirements:** 8 locked

## Goal

Cohort Leiden phenotype clustering gains a true-scanpy (`sc.tl.leiden`-style) all-cells mode — pool every cell across all project images into one feature matrix, build one HNSW-based kNN graph over all cells, SNN/Jaccard-weight it, run a single CWTS Leiden partition, and write each cell's community label back to its source image — replacing the per-cell kNN label-transfer as the mechanism (while transfer stays selectable), and HNSW replaces brute-force kNN for Leiden graph construction in both single-image and cohort scope.

## Background

Phase 14 shipped Leiden clustering with two paths: single-image Leiden clusters all active cells directly, and **cohort scope fits Leiden on a bounded subsample then assigns every other cell via kNN label transfer** (`LeidenModel.transferLabels`, [LeidenModel.java:340](../../../src/main/java/qupath/ext/celltune/model/LeidenModel.java#L340); driven by `CohortClusterModel.writeClusterAcrossProjectLeiden`, [CohortClusterModel.java:320](../../../src/main/java/qupath/ext/celltune/model/CohortClusterModel.java#L320)). The transfer is `sc.tl.ingest`-style, not `sc.tl.leiden`-style: it is a downstream classifier, not community detection over all cells.

Two problems motivate this phase:
1. **Semantics.** Stock scanpy `sc.tl.leiden` never transfers — every cell is a node in one graph and gets its label from the partition. The current cohort result is an approximation of that.
2. **Performance.** The transfer write is slow because it is brute-force kNN: each of potentially tens of millions of cells is scanned against the whole reference cloud (up to `MAX_UMAP_CELLS` = 20,000 rows), O(nCells × nRef) per image. The graph build in `LeidenModel.featureKnn` ([LeidenModel.java:52](../../../src/main/java/qupath/ext/celltune/model/LeidenModel.java#L52)) is likewise brute-force O(n²), acceptable only for the bounded sample.

What does NOT exist today: any all-cohort pooling-into-one-graph path; any approximate-NN (HNSW) index; any recall validation of an approximate graph against exact kNN; any two-pass UUID-keyed cohort write. Build tooling is `build.gradle.kts` on Maven Central with the `implementation` + `shadow` bundling pattern already used for `nl.cwts:networkanalysis` ([build.gradle.kts:46](../../../build.gradle.kts)), so an HNSW dependency is a one-line addition. Environment target: tens of millions of cells across a project, 100 GB+ RAM available (memory is not the binding constraint; wall-clock and correctness are).

## Requirements

1. **All-cells cohort mode**: Cohort Leiden can cluster every cell across all images in one graph.
   - Current: cohort Leiden only fits on a subsample and label-transfers; no all-cells path exists
   - Target: a cohort "cluster all cells" mode pools all cells → builds one kNN graph → runs one CWTS Leiden partition → writes each cell's label back; selectable alongside the retained transfer mode
   - Acceptance: on a synthetic multi-image project with known ground-truth communities, all-cells mode assigns every cell a label from a single partition (no reference/query split), and a test asserts label count == total cell count and community recovery purity ≥ threshold

2. **Both cohort modes selectable**: The Phase 14 transfer path is retained, not removed.
   - Current: cohort Leiden has exactly one mechanism (transfer)
   - Target: the dialog exposes both cohort modes — "cluster all cells" (default) and "transfer from sample" — and each produces a valid cohort `Cluster` measurement
   - Acceptance: switching the mode toggle and running each produces labels via its respective code path; `transferLabels` and its Phase 14 tests remain present and passing

3. **HNSW kNN graph build**: Leiden graph construction uses an approximate-NN index instead of brute force, in both single-image and cohort scope.
   - Current: `featureKnn` is brute-force O(n²); infeasible above the bounded sample
   - Target: an HNSW index builds the kNN graph for Leiden in both single-image and cohort paths; the SNN/Jaccard weighting and CWTS Leiden stages downstream are unchanged
   - Acceptance: a test builds an HNSW graph on a synthetic cloud and confirms the downstream Leiden labels match exact-kNN Leiden within tolerance (ARI ≥ threshold); single-image Leiden on a large synthetic input completes via the HNSW path

4. **Runtime recall gate**: The approximate graph's fidelity is validated each run and enforced.
   - Current: no recall validation exists
   - Target: each Leiden run measures HNSW recall against exact `featureKnn` on a subsample; if recall < 95%, auto-tune `efSearch`/`efConstruction` and retry, and if still < 95% abort the run with an actionable error (no labels written)
   - Acceptance: a test with deliberately degraded HNSW params triggers the auto-tune-then-abort path (no `Cluster` measurement written, error surfaced); a test with adequate params passes the gate and proceeds

5. **Memory-safe two-pass write with UUID keying**: The cohort write never holds all hierarchies in memory and maps labels back robustly.
   - Current: the transfer write streams one image at a time; no all-cohort pooling exists
   - Target: pass 1 reads each image, extracts marker features + records each cell's `PathObject` UUID, and releases the hierarchy; after the single Leiden partition, pass 2 re-reads each image and writes the `Cluster` measurement keyed on UUID (not iteration order)
   - Acceptance: a test pools a synthetic two-image set, clusters, and confirms every cell's written label matches its pooled-row label by UUID even when the second read returns cells in a different order

6. **Deterministic, seeded runs**: The all-cells run is reproducible via the existing reproducibility toggle.
   - Current: no cohort all-cells run exists
   - Target: HNSW build and CWTS Leiden are seeded from the existing reproducibility toggle so the same project + params reproduce the same clustering (structure-identical; bit-identity may require ordered/single-threaded HNSW insertion, which is noted where relevant)
   - Acceptance: two consecutive all-cells runs on the same synthetic input with the toggle on yield identical labels (up to label permutation); a documented note states the bit-identity caveat under parallel build

7. **Batch progress, cancel, and soft ceiling**: The long cohort job is observable, interruptible, and guarded.
   - Current: the transfer write shows a status line + progress bar but no cancellation and no scale guard
   - Target: the all-cells job reports per-phase progress (pooling X/N images, graph build, Leiden, writing X/N images), supports cancellation that leaves images unmodified, and warns/confirms before starting when the pooled cell count exceeds a configurable soft threshold
   - Acceptance: cancelling mid-run leaves no partial `Cluster` writes on not-yet-written images; exceeding the soft threshold surfaces a confirm dialog before any work begins; progress advances through each named phase

8. **Preview re-sync after write**: The interactive plot reflects the persisted result.
   - Current: the scatter/UMAP preview is subsample-based and its legend reflects the preview fit, which differs from a fresh cohort run
   - Target: after the all-cells run completes, the scatter/legend re-syncs to the final all-cells clustering so preview and persisted labels visually agree; the persisted `Cluster` measurement always comes from the full all-cells run, not the preview
   - Acceptance: after an all-cells write, the plot legend cluster count equals the count written to cells; a manual check confirms the overlay colours match the re-synced legend

## Boundaries

**In scope:**
- Cohort "cluster all cells" mode: pool → one HNSW kNN graph → SNN/Jaccard weight → single CWTS Leiden → write back
- Retaining the Phase 14 kNN label-transfer mode as a selectable cohort alternative
- HNSW-based kNN graph construction for Leiden in both single-image and cohort scope
- Runtime recall gate (measure vs exact `featureKnn` on a subsample; auto-tune then abort < 95%)
- Two-pass, memory-safe cohort write with UUID-keyed label mapping
- Seeded/deterministic runs via the existing reproducibility toggle
- Per-phase progress, cancellation, and a configurable soft cell-count ceiling with confirm
- Re-syncing the scatter/legend to the final all-cells clustering after write
- Unit tests: pooling/identity mapping, recall gate vs exact kNN, UUID write-back, all-cells community recovery, HNSW-vs-exact Leiden agreement
- An HNSW dependency added to `build.gradle.kts` (implementation + shadow) and bundled in the fat JAR

**Out of scope:**
- Matching scanpy's default *modularity* (RBConfiguration) quality function — CWTS exposes only CPM; documented fidelity gap, no library swap this phase
- Matching scanpy's UMAP fuzzy-simplicial connectivities — SNN/Jaccard weighting is retained; documented fidelity gap
- PCA dimensionality reduction before neighbors — skipped (near-identity for a ~20-marker panel); documented
- Implementing NN-descent / literal pynndescent — HNSW is the chosen ANN; pynndescent parity is a possible later refinement
- k-means cohort path — unchanged
- Changing the `Cluster` measurement name, overlay mapper, or cluster→class assignment machinery — reused as-is
- Bit-identical reproduction of any external scanpy run — not achievable given CWTS/CPM + Leiden RNG; explicitly not promised

## Constraints

- **Dependency:** HNSW must be a Maven Central artifact bundled via the existing `implementation` + `shadow` pattern; if no suitable maintained Java HNSW is available at plan time, that is a planning blocker to resolve before execution (fallback: implement the ANN index in-repo).
- **Scale:** must remain correct and memory-safe at tens of millions of cells with 100 GB+ RAM; two image reads (pool, then write) are accepted; live `PathObject` hierarchies are not held across all images simultaneously.
- **Correctness gate:** no cohort `Cluster` labels are written if the recall gate fails after auto-tune.
- **Purity:** `LeidenModel` (and any new ANN/graph helper) stays pure-array with no QuPath/JavaFX types, unit-testable on synthetic clouds — mirroring the Phase 14 pattern.
- **Non-destructive:** phenotype `getPathClass()` is never changed; only the numeric `Cluster` measurement is written, matching Phase 14 behavior.

## Acceptance Criteria

- [ ] Cohort dialog exposes both "cluster all cells" and "transfer from sample" modes; each writes a valid `Cluster` measurement
- [ ] All-cells mode assigns every cell a label from a single Leiden partition (label count == cell count) on a synthetic multi-image project
- [ ] HNSW graph build is used for Leiden in both single-image and cohort scope; downstream Leiden labels match exact-kNN Leiden within the agreed ARI tolerance
- [ ] Runtime recall gate: degraded HNSW params trigger auto-tune then abort with no labels written; adequate params pass and proceed
- [ ] Cohort write is two-pass and maps labels by `PathObject` UUID; a reordered second read still assigns every cell its correct label
- [ ] Two consecutive all-cells runs with the reproducibility toggle on yield identical labels up to permutation
- [ ] Progress advances through named phases; cancellation leaves not-yet-written images unmodified; exceeding the soft ceiling shows a confirm dialog before work starts
- [ ] After an all-cells write, the scatter legend cluster count equals the count written to cells
- [ ] `transferLabels` and its Phase 14 tests remain present and passing
- [ ] New unit tests cover pooling/identity mapping, recall gate, UUID write-back, and all-cells community recovery; full `test` + `shadowJar` build is green
- [ ] USER_GUIDE/CLAUDE.md document the all-cells mode and the three fidelity gaps vs stock scanpy (CPM not modularity, SNN/Jaccard not UMAP connectivities, no PCA)

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                        |
|--------------------|-------|------|--------|--------------------------------------------------------------|
| Goal Clarity       | 0.90  | 0.75 | ✓      | Replace transfer mechanism with all-cells; HNSW everywhere   |
| Boundary Clarity   | 0.85  | 0.70 | ✓      | Both modes kept; single-image in scope; fidelity gaps listed |
| Constraint Clarity | 0.80  | 0.65 | ✓      | Recall gate, determinism, soft ceiling, dep pattern fixed    |
| Acceptance Criteria| 0.80  | 0.70 | ✓      | 11 pass/fail checks incl. reorder + gate + reproducibility   |
| **Ambiguity**      | 0.15  | ≤0.20| ✓      |                                                              |

Status: ✓ = met minimum, ⚠ = below minimum (planner treats as assumption)

## Interview Log

| Round | Perspective     | Question summary                              | Decision locked                                            |
|-------|-----------------|-----------------------------------------------|------------------------------------------------------------|
| 1     | Boundary Keeper | Fate of Phase 14 transfer path                | Add all-cells alongside transfer; both selectable          |
| 1     | Failure Analyst | How to enforce the ≥95% recall gate           | Runtime check; auto-tune then abort if still below         |
| 1     | Boundary Keeper | How to surface preview-vs-final divergence    | Re-sync scatter/legend to final clustering after write     |
| 2     | Failure Analyst | Reproducibility of the all-cells run          | Seeded via existing reproducibility toggle (bit-caveat noted)|
| 2     | Failure Analyst | Behavior at extreme scale / OOM / time        | Progress + cancel + configurable soft cell-count ceiling   |
| 2     | Boundary Keeper | Does single-image Leiden scope change         | Yes — route single-image Leiden through HNSW too           |

---

*Phase: 15-all-cells-leiden-clustering*
*Spec created: 2026-07-06*
*Next step: /gsd-discuss-phase 15 — implementation decisions (HNSW library choice, mode-toggle UI, recall-gate mechanics, two-pass driver design)*
