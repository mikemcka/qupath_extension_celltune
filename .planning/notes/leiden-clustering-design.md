---
title: Leiden Clustering for Phenotyping — Design & Research
date: 2026-07-02
context: Research session before planning a Leiden clustering option in the scatter-plot phenotyping workflow
---

# Leiden Clustering for Phenotyping

## Problem / motivation

The interactive **Scatter Plots and Clustering** dialog (User Guide §11) currently offers
**only k-means** for unsupervised phenotyping: cells are z-scored on selected markers and
partitioned with `KMeans.fit(active, k)`. k-means forces the user to pick `k`, assumes roughly
spherical, equal-size clusters, and struggles to separate the rare immune populations that
matter most in multiplex imaging.

The field standard for single-cell / multiplex-imaging phenotyping is **graph-based community
detection** — build a k-nearest-neighbour graph in feature space and run **Leiden** on it. It
needs no `k` (a **resolution** parameter instead), finds non-spherical and unequal-size
populations, and is what scanpy / scimap / SPACEc all use. This note captures the research and
the codebase integration points so we can plan a Leiden option alongside k-means.

Goal: add **Leiden** as a selectable clustering method in the scatter-plot workflow (both
current-image and project/cohort scope), reusing the existing standardisation, embedding,
colouring, and cluster→class assignment machinery unchanged.

---

## The shared recipe (what every reference tool actually does)

All the tools below implement the **same three-step pipeline**, originating from **PhenoGraph**
(Levine et al. 2015, CyTOF phenotyping) and standardised by scanpy:

1. **kNN graph** in feature space (raw markers, or PCA-reduced) — `sc.pp.neighbors`.
2. **Edge weighting** — Jaccard / shared-nearest-neighbour (PhenoGraph) or UMAP-style
   "connectivities" (scanpy).
3. **Leiden** community detection on that weighted graph, controlled by a **`resolution`**
   parameter (higher → more clusters). **There is no `k`** — resolution replaces it.

Implication for us: Leiden slots into the *same* insertion point as k-means in
`ScatterPlotView.recompute()`, but (a) the "Clusters (k)" spinner becomes a **resolution**
control, and (b) a new **kNN-graph build + edge-weight** step precedes the clustering call.

---

## Reference implementations to study

| Tool | Language | How it does Leiden | Reference |
|---|---|---|---|
| **scimap** (labsyspharm) | Python | `sm.tl.cluster(method='leiden')` — also `kmeans`, `phenograph`. Thin wrapper over scanpy. Closest analog to our multiplex-imaging phenotyping use case. | https://scimap-doc.readthedocs.io/en/latest/tl/sm.tl.cluster/ · https://scimap.xyz/tutorials/md/clustering_scimap/ · https://github.com/labsyspharm/scimap |
| **SPACEc** | Python | Leiden for cell-type annotation; cellular-neighbourhood analysis uses kNN windows → clustering (the Schürch/Nolan recipe we already validated). | https://www.nature.com/articles/s41467-025-65658-3 · https://spacec.readthedocs.io/en/stable/tutorials/06_cell_neighborhood_analysis.html |
| **SPIAT** | R | Not Leiden directly — uses **Rphenograph** (kNN + community detection) and hierarchical clustering for neighbourhoods. Good reference for the graph-community *concept*. | https://bioconductor.org/packages/release/bioc/vignettes/SPIAT/inst/doc/neighborhood.html · https://mblue9.github.io/SPIAT/articles/introduction.html |
| **scanpy / squidpy** | Python | The canonical `sc.tl.leiden` (via `leidenalg` + `igraph`) — the reference algorithm API everyone wraps. | https://scanpy.readthedocs.io/en/stable/generated/scanpy.tl.leiden.html · https://www.sc-best-practices.org/cellular_structure/clustering.html |
| **SpatialLeiden** | Python | Spatially-aware Leiden (multiplex layers: expression + spatial neighbourhood). More relevant to §18 spatial neighbourhoods than to marker phenotyping, but worth noting for future work. | https://link.springer.com/article/10.1186/s13059-025-03489-7 · https://pmc.ncbi.nlm.nih.gov/articles/PMC11804054/ |

**PhenoGraph** is the most directly relevant conceptual reference for *phenotyping* clustering
(Jaccard-weighted kNN graph + Louvain/Leiden); both scimap and SPIAT expose it.

---

## Java implementation path (the one that matters)

Smile 3.1.1 (already bundled) has **no** graph / community-detection support — confirmed by
codebase search. The canonical Java Leiden library is:

**`nl.cwts:networkanalysis:1.3.0`** — https://github.com/CWTSLeiden/networkanalysis

- Authored by **Traag, van Eck & Waltman** — the *same authors* as `leidenalg`, i.e. the
  reference Leiden implementation the Python tools trace back to. Building on it means we run the
  same algorithm as scanpy/scimap, not a reimplementation.
- **Input:** a `Network` object / edge list — **not** a kNN graph. So *we* build the kNN graph in
  feature space (Java kNN), Jaccard-weight the edges (PhenoGraph-style), and feed edges in.
- **Programmatic API:** `Network`, `Clustering`, `LeidenAlgorithm` (README documents the CLI, but
  the classes are public). Requires Java 8+, undirected networks, CPM or Modularity quality.
- **Parameters** map cleanly to UI controls: `resolution` (default 1.0), `randomness` (0.01),
  `iterations` (10), `random-starts` (1), `seed`. The seed / random-starts mirror the k-means
  multi-restart reproducibility work already shipped (`NeighborhoodModel.DEFAULT_N_INIT`).
- **Bundling:** add via the existing `implementation(...)` + `shadow(...)` pattern in
  `build.gradle.kts` (same as Smile/XGBoost/LightGBM).

Maven/Gradle coords:
```gradle
implementation("nl.cwts:networkanalysis:1.3.0")
shadow("nl.cwts:networkanalysis:1.3.0")
```

Open sub-decision: kNN in feature space. Options — (a) brute-force kNN (fine for the sampled
pool, ≤50k rows; O(n²) is borderline), (b) reuse a spatial-index trick, or (c) add an approximate
-NN lib (HNSW). Note `NeighborhoodModel.kNearestNeighborIndices` is **spatial (x,y)** only, so it
is *not* directly reusable for feature-space kNN — a new kNN helper is needed.

---

## Codebase integration points (from exploration)

### Insertion point — `ScatterPlotView.recompute()`
`src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java`

- **k-means call:** `KMeans km = KMeans.fit(active, kEff);` then `km.y` → labels (~line 692–693).
- **Data clustered:** `active[m][selCols.length]` — active (annotation/class-filtered) rows,
  z-scored selected-marker columns via `ScatterMath.standardizeColumns(activeRaw, mean, sd)`
  (~line 686). Leiden receives the **same** matrix — no upstream change.
- **k control:** `kSpinner` (Spinner, range [2,50], default 8), read ~line 614; `kEff = min(k,m)`.
- **Centroids:** computed post-fit by averaging z-scored rows per cluster into
  `cents[k][selCols.length]`, stored in `fitCentroids` (~lines 699–716).
- **Label plumbing:** `subCluster[m]` → mapped back to full-length `newCluster[n]` (non-active =
  −1) → stored in `cluster[]`; the canvas reads `cluster()` for colouring; the legend and
  `applyClustersToClasses()` consume it. **All of this is method-agnostic** — Leiden reuses it.

### Method-selection UI
Add a `ComboBox<ClusterMethod>` {KMEANS, LEIDEN} near the embedding selector (~lines 210–231);
when LEIDEN is chosen, swap the "Clusters (k)" spinner for a **resolution** control (and
optionally iterations/seed).

### Standardisation / helpers
`src/main/java/qupath/ext/celltune/model/ScatterMath.java` — `standardizeColumns(...)` (z-score,
population sd; captures mean/sd for cohort replay). Reuse unchanged; feed the same matrix to the
new kNN-graph builder.

### Project / cohort scope — the key design wrinkle
`src/main/java/qupath/ext/celltune/model/CohortClusterModel.java`

- Cohort flow: `sample()` pools a bounded random sample across images → fit **one** model → 
  `assignAcrossProject()` streams each image and assigns every cell by **`nearestCentroid()`**
  (Euclidean to the fitted z-scored centroids), ~lines 173–268, 342–358.
- **Leiden has no centroids.** So cohort-scale Leiden needs a decision (see Open Questions).

### Build config
`build.gradle.kts` (lines ~1–93) — Smile 3.1.1 via `implementation` + `shadow`; shadow plugin
`com.gradleup.shadow` 8.3.5. Add the CWTS dep the same way. No graph libs currently present.

### Testing pattern
`src/test/java/qupath/ext/celltune/model/NeighborhoodModelTest.java` — JUnit 5, synthetic data,
assert cluster **quality** (purity / modularity), never raw label ids. A new `LeidenModelTest`
should build synthetic graphs (two cliques + sparse inter-clique edges) and assert recovery.

---

## Proposed new components (to be firmed up in the plan)

| Component | Purpose |
|---|---|
| `LeidenModel` (model pkg) | Pure, static, testable core: build kNN graph from a z-scored matrix, Jaccard-weight edges, run CWTS Leiden, return labels. Mirrors `NeighborhoodModel` style. Return a `ClusterResult`-compatible record so downstream code is unchanged. |
| feature-space kNN helper | `int[][] featureKnn(double[][] rows, int k)` — new (spatial kNN in `NeighborhoodModel` is x/y only). |
| `ClusterMethod` enum + UI wiring | {KMEANS, LEIDEN} combo in `ScatterPlotView`; resolution control; branch in `recompute()`. |
| cohort assignment strategy | Post-hoc synthetic centroids (reuse `nearestCentroid`) **or** kNN-vote — see Open Questions. |

## Reused unchanged
`ScatterMath.standardizeColumns`, the embedding (PCA/UMAP), `ScatterPlotCanvas` colouring,
legend/selection, `ClusterAssignmentPane`, `applyClusterMapping` (current image), and — if we
produce synthetic centroids — `CohortClusterModel.assignAcrossProject`.

---

## Resolved decisions (locked at planning, 2026-07-02)

- **Scope:** current-image **and** whole-project (cohort) in v1 — parity with k-means.
- **Cohort assignment: kNN label transfer (RESOLVED).** Research into how the reference tools
  handle multi-image cohorts showed: scanpy/scimap/SPACEc **cluster all cells at once** (no assign
  step), and when they transfer labels to new/held-out cells they use **kNN label transfer** —
  scanpy [`sc.tl.ingest`](https://scanpy.readthedocs.io/en/stable/generated/scanpy.tl.ingest.html)
  ("the only supported value for the labeling method is 'knn'"). **No established tool uses
  synthetic centroids for Leiden** — centroids are a k-means concept and averaging a non-spherical
  community into one point defeats the method. So the earlier "synthetic centroids" idea was
  rejected. CellTune fits Leiden on the pooled *sample* (it already samples for scale) then assigns
  every cell by kNN-vote against the labelled sample — the `ingest` mechanism, fitting the existing
  sample→stream architecture. (Per-cluster mean profiles are still computed for the *display*
  heatmap only.)
- **Controls:** Method {k-means, Leiden} selector; resolution replaces "Clusters (k)"; a
  reproducibility toggle mirrors the shipped k-means multi-restart; kNN graph-k and Jaccard
  weighting stay hidden defaults.
- **Library:** `nl.cwts:networkanalysis:1.3.0`.

Filed as **Phase 14 (v1.5)** — see `phases/14-leiden-phenotype-clustering/14-01-PLAN.md`.

## Remaining open questions (deferred to execution / tuning)
2. **`resolution` default & range.** scanpy default 1.0. Expose a slider/spinner (e.g. 0.1–3.0)?
   Show the resulting cluster count after fit.
3. **kNN `k` for the graph.** PhenoGraph default ~30; scanpy `n_neighbors` default 15. Fixed
   sensible default (hidden) vs exposed control?
4. **Edge weighting.** Jaccard/SNN (PhenoGraph) vs plain kNN adjacency. Jaccard is the standard;
   modest extra code.
5. **kNN algorithm.** Brute-force (ok for ≤50k sampled pool) vs approximate-NN dependency. Start
   brute-force; revisit if slow.
6. **Determinism.** CWTS Leiden takes a seed + random-starts — expose a "reproducible" toggle
   mirroring the k-means multi-restart checkbox already shipped.
7. **Also add Leiden to §18 spatial neighbourhoods?** Out of scope for v1 (SpatialLeiden is the
   proper approach there); keep this note's scope to marker phenotyping (§11).

---

## Sources

- scimap — https://github.com/labsyspharm/scimap · https://scimap-doc.readthedocs.io/en/latest/tl/sm.tl.cluster/ · https://scimap.xyz/tutorials/md/clustering_scimap/ · https://arxiv.org/html/2405.02076v1
- SPACEc — https://www.nature.com/articles/s41467-025-65658-3 · https://spacec.readthedocs.io/en/stable/tutorials/06_cell_neighborhood_analysis.html
- SPIAT — https://bioconductor.org/packages/release/bioc/vignettes/SPIAT/inst/doc/neighborhood.html · https://mblue9.github.io/SPIAT/articles/introduction.html
- scanpy Leiden — https://scanpy.readthedocs.io/en/stable/generated/scanpy.tl.leiden.html · https://www.sc-best-practices.org/cellular_structure/clustering.html
- SpatialLeiden — https://link.springer.com/article/10.1186/s13059-025-03489-7 · https://pmc.ncbi.nlm.nih.gov/articles/PMC11804054/
- CWTS Leiden Java library — https://github.com/CWTSLeiden/networkanalysis
- Leiden algorithm paper (Traag et al. 2019) — https://arxiv.org/abs/1810.08473
