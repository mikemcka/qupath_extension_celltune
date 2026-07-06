# Scope Draft — PCA reduction before Leiden/k-means clustering

Status: proposal (seed for `/gsd-spec-phase` or `/gsd-add-phase`)
Author: drafted 2026-07-06
Related: Phase 15 (all-cells Leiden), scanpy fidelity gap #3 ("no PCA")

## Problem

The clustering pipeline (`LeidenModel.cluster` / `clusterViaAnn`, and k-means) builds its
kNN graph on the **z-scored marker matrix directly**, with no dimensionality reduction.
That is fine for a curated ~20–50 marker panel, but real projects can carry **1,000+
per-cell measurements** (each marker × {mean, median, …} × {nucleus, cytoplasm, membrane}
× texture features). At that width three things break:

1. **Feature-count dominance (correctness).** Euclidean kNN weights every column equally,
   so a marker contributing 15 measurements pulls ~15× harder on the distance than a marker
   with 1. Cluster structure is then driven by *how many measurements a marker happens to
   have*, not by biology. This is a silent bias, not just a fidelity nicety.
2. **Distance degradation (correctness).** In ~1000 correlated dimensions Euclidean distances
   concentrate — nearest and farthest neighbours become nearly equidistant — so the kNN graph
   (and therefore Leiden) gets noisy. Reducing to ~30–50 principal components is the standard
   remedy; it is why scanpy and Seurat both PCA first.
3. **Scale (performance).** HNSW build + Jaccard/SNN weighting over 1000-dim vectors is far
   heavier than over 50-dim. This compounds on the Phase-15 all-cells path (tens of millions
   of cells), where the marker width directly multiplies graph-build time and memory.

scanpy's default recipe is `scale → PCA(50) → neighbors → leiden`; `sc.pp.neighbors` builds
the graph on `X_pca` when the matrix has > 50 variables. The extension has no equivalent step.

## Goal

Add an optional PCA reduction applied **after z-scoring, before kNN graph construction**, in
every clustering entry point (single-image preview, all-cells cohort driver, k-means), on by
default when the feature count is large and skipped when it is small — deterministic, so the
Phase-15 reproducible path stays bit-stable.

## Non-goals

- Not changing the SNN/Jaccard weighting or the CWTS CPM optimiser (fidelity gaps #1/#2 stay).
- Not adding per-marker feature *aggregation* (a viable alternative — see Open Questions).
- Not touching the classifier feature pipeline (`DualModelClassifier`) — this is clustering only.
- Not adding UMAP-for-clustering; the 2D UMAP/PCA embedding in the scatter view is display-only
  and unaffected.

## Design decisions (proposed — confirm in spec)

- **D-1 Where.** Insert reduction between `ScatterMath.standardizeColumns(...)` and the kNN
  build. Cleanest surface: a `ScatterMath.reduceForClustering(double[][] std, int nComp)` helper
  returning the projected matrix, plus an optional `pcaComponents` parameter threaded through
  `LeidenModel.cluster` / `clusterViaAnn` (0 or ≥ featureCount ⇒ no reduction).
- **D-2 Engine.** Reuse `smile.feature.extraction.PCA` (already a dependency, used by
  `ScatterMath.fillPca`). Smile PCA is an **exact** covariance eigendecomposition — deterministic,
  no seeded randomized SVD — so reproducibility is preserved for free. `PCA.fit(std)
  .getProjection(nComp).apply(std)`.
- **D-3 Conditional default.** Apply PCA only when `featureCount > threshold` (default 50, mirrors
  scanpy). At/below threshold, pass the z-scored matrix through unchanged (PCA to ≥ p comps is a
  no-op rotation that only adds cost). Default `nComp = min(50, featureCount − 1)`.
- **D-4 No whitening.** Project onto principal axes without rescaling components to unit variance
  (matches scanpy, which runs neighbours on raw PC coordinates). Larger-variance PCs keep their
  weight — that is the intended denoising behaviour.
- **D-5 All-cells fit strategy (critical for 30M).** `PCA.fit` needs the covariance of the full
  matrix; a 30M × 1000 double matrix (~240 GB) cannot be held to fit. **Fit the projection on a
  bounded subsample** (reuse the recall-gate sampling machinery / a configurable cap, e.g. ≤ 200k
  rows), then **apply** that fixed projection to every pooled row streaming. One projection is fit
  once over the pooled sample so all cells share the same PC basis. Deterministic subsample (seed
  42). Below the cap, fit on the full matrix.
- **D-6 Observability.** Log components kept and cumulative variance explained (e.g.
  "PCA: 1000 → 50 comps, 82.4% variance") to the status line, matching the auto-prune log style.
- **D-7 UI.** In the scatter view, add a "Reduce dimensions (PCA)" checkbox (default on) + a
  components spinner (default 50), visible for Leiden and k-means, alongside the existing
  resolution/k controls. Persist as `celltune.clusterPcaComponents` / `celltune.clusterPcaEnabled`.

## Requirements (falsifiable — for the SPEC)

- **PCA-01** With feature count > threshold and PCA on, the kNN graph is built on the projected
  matrix (≤ nComp columns), verified by a seam test asserting the matrix fed to the graph builder
  has `nComp` columns.
- **PCA-02** With feature count ≤ threshold (or PCA off), clustering is byte-identical to the
  current no-PCA behaviour (regression guard on the existing 50-marker path).
- **PCA-03** Two reproducible runs (seed 42) on the same > threshold input yield identical labels
  up to permutation — PCA introduces no nondeterminism.
- **PCA-04** On a synthetic dataset where 3 real markers are each duplicated into 100 noisy
  measurements, PCA-on recovers the known communities at materially higher ARI than PCA-off
  (demonstrates the dominance/degradation fix).
- **PCA-05** All-cells path fits one projection on a bounded subsample and applies it to every
  pooled row; peak memory stays within a documented bound independent of total cell count.
- **PCA-06** Variance-explained and component count are reported to the status line/log.

## Change surface (estimate)

- `ScatterMath.java` — add `reduceForClustering` (+ subsample-fit variant for the cohort path).
- `LeidenModel.java` — optional `pcaComponents` param on `cluster` / `clusterViaAnn` (or reduce
  before the call and keep signatures, threading a boolean/int from callers).
- `CohortClusterModel.java` — fit-on-subsample-then-apply in `poolAllCells`/`writeClusterAllCells`.
- `ScatterPlotView.java` — checkbox + spinner, wire into the fit calls, status-line variance log.
- Tests: `LeidenModelTest` / `ScatterMathTest` (PCA-02/03/04), `CohortClusterModelTest` (PCA-05).
- Docs: USER_GUIDE + CLAUDE.md — update the "three scanpy-fidelity gaps" note (this closes #3).

## Risks / open questions

- **OQ-1 PCA vs feature-selection.** For "many measurements per marker," per-marker aggregation
  (cluster on one value per marker) is more interpretable and also fixes dominance. PCA is the more
  general/standard answer and matches scanpy; aggregation could be a complementary later option.
  Decide which is the default story for users.
- **OQ-2 Threshold + component defaults.** 50/50 mirrors scanpy but is arbitrary for imaging panels;
  may want variance-target selection (keep PCs to X% variance) instead of a fixed count.
- **OQ-3 Subsample size for D-5.** Trade-off between projection stability and fit cost at 30M scale;
  needs a defensible default and a note in the reproducibility docs.
- **OQ-4 Interaction with normalization.** PCA assumes the z-scored input; confirm the
  `FeatureNormalizer` (arcsinh etc.) still runs *before* z-score + PCA as today.

## Evidence hook

The concurrent scanpy validation (`nolan_paper_validation/`) quantifies the PCA effect on a real
~50-marker panel: extension-vs-`scanpy-matched(no-PCA)` isolates the algorithm, and the gap to
`scanpy-default(PCA)` is the PCA contribution. Those numbers should anchor the threshold/default
choices above before locking the spec.
