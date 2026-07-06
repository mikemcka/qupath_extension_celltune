# Phase 15: All-Cells Leiden Clustering (True-Scanpy) - Context

**Gathered:** 2026-07-06
**Status:** Ready for planning

<domain>
## Phase Boundary

Add a true-scanpy (`sc.tl.leiden`-style) all-cells cohort clustering mode — pool every cell across all project images into one feature matrix, build one approximate-NN (HNSW) kNN graph over all cells, SNN/Jaccard-weight it, run a single CWTS Leiden partition, and write each cell's community label back to its source image by UUID — selectable alongside the retained Phase 14 kNN label-transfer mode, and route Leiden kNN graph construction through the ANN index in both single-image and cohort scope. Guarded by a runtime ANN recall gate (≥95% vs exact `featureKnn`).

This phase is the "revisit with an ANN index if slow" sub-decision that Phase 14's design note explicitly deferred.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**8 requirements are locked.** See `15-SPEC.md` for full requirements, boundaries, and acceptance criteria.

Downstream agents MUST read `15-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):**
- Cohort "cluster all cells" mode: pool → one HNSW kNN graph → SNN/Jaccard weight → single CWTS Leiden → write back
- Retaining the Phase 14 kNN label-transfer mode as a selectable cohort alternative
- HNSW-based kNN graph construction for Leiden in both single-image and cohort scope
- Runtime recall gate (measure vs exact `featureKnn` on a subsample; auto-tune then abort < 95%)
- Two-pass, memory-safe cohort write with UUID-keyed label mapping
- Seeded/deterministic runs via the existing reproducibility toggle
- Per-phase progress, cancellation, and a configurable soft cell-count ceiling with confirm
- Re-syncing the scatter/legend to the final all-cells clustering after write
- Unit tests: pooling/identity mapping, recall gate vs exact kNN, UUID write-back, all-cells community recovery, HNSW-vs-exact Leiden agreement
- HNSW dependency added to `build.gradle.kts` (implementation + shadow), bundled in the fat JAR

**Out of scope (from SPEC.md):**
- Matching scanpy's modularity (RBConfiguration) quality function — CWTS exposes only CPM (documented gap)
- Matching scanpy's UMAP fuzzy-simplicial connectivities — SNN/Jaccard retained (documented gap)
- PCA dimensionality reduction before neighbors — skipped for ~20-marker panel (documented)
- Implementing NN-descent / literal pynndescent — HNSW is the chosen ANN (NN-descent only as fallback)
- k-means cohort path — unchanged
- Changing the `Cluster` measurement name, overlay mapper, or cluster→class assignment machinery — reused as-is
- Bit-identical reproduction of any external scanpy run — not achievable given CWTS/CPM + Leiden RNG

</spec_lock>

<decisions>
## Implementation Decisions

### ANN library & metric
- **D-01:** Do NOT pre-commit to a specific ANN library. The phase researcher evaluates candidates (jelmerk/hnswlib-core, Lucene HNSW, others) on maintenance, licence (must be compatible with GPL-3.0), Maven Central availability, API fit, and perf at ~30M×20-D, and returns a recommendation before the library is locked. This resolves the SPEC's flagged planning blocker.
- **D-02:** In-repo NN-descent (pure-array, in `LeidenModel`) is the fallback if no suitable maintained Java ANN library exists. It is not the default path.
- **D-03:** Distance metric is **Euclidean** on z-scored markers — identical to the current exact `featureKnn` `squaredDistance`. This keeps the recall gate a like-for-like comparison and leaves downstream SNN weighting + CWTS Leiden unchanged. (If a future decision switches to cosine, the exact baseline must switch too.)

### Cohort mode UI
- **D-04:** Present the two cohort modes as a **radio pair** — "Cluster all cells" / "Transfer from sample" — visible **only in project scope** (hidden in single-image scope where transfer is irrelevant).
- **D-05:** **All-cells is the default**, framed as the exact/true-scanpy option; transfer is framed as the fast/approximate option. Retire nothing — transfer stays fully functional.
- **D-06:** Single-image Leiden gains no mode selector; it simply routes its kNN graph build through the same ANN index (D-01) instead of brute-force `featureKnn`.

### Recall-gate mechanics
- **D-07:** Recall is measured on a **proportional, capped** subsample of query cells (≈0.1% of pooled cells, capped at ~10k) — exact kNN computed for just those, compared against the ANN graph's neighbors → mean recall. Keeps cost O(sample × N), not O(N²).
- **D-08:** On recall < 95%, **escalate query-time `efSearch` geometrically** (e.g. ×2 up to a cap) and re-measure — no index rebuild. If still < 95% after the cap, **abort** the run with an actionable error; no `Cluster` labels are written.
- **D-09:** `efSearch`, the recall threshold, and gate internals are **hidden defaults**. The run reports the **measured recall** to the status line (e.g. "ANN recall 0.982 — passed"). No user-facing ANN knobs.

### Batch UX (ceiling / progress / cancel)
- **D-10:** A **configurable soft ceiling of 50M pooled cells** triggers a confirm dialog before the batch starts (above the normal tens-of-millions, so it only catches accidental oversized runs). It warns/confirms — it does not hard-block.
- **D-11:** Progress reports **per-phase + per-image counts** — "Pooling 12/40 images", "Building kNN graph…", "Running Leiden…", "Writing 12/40 images" — mirroring the existing per-image status style in the transfer write.
- **D-12:** Cancellation during the write pass **leaves already-written images intact** (the `Cluster` measurement is a non-destructive numeric column) and the summary **reports which images were / were not updated**. No rollback pass (too costly at 30M). Cancel is honored between images, and during pool/graph/Leiden phases.

### Determinism
- **D-13:** The all-cells run is seeded from the **existing reproducibility toggle** (drives both the ANN build seed and CWTS Leiden seed/random-starts). Structure-identical reproduction is the guarantee; bit-identity may require ordered/single-threaded ANN insertion — note this caveat where relevant, do not over-promise.

### Claude's Discretion
- Exact edge/graph memory representation feeding the CWTS `Network` (`LargeIntArray`/`LargeDoubleArray`), the internal layout of the pooled feature matrix, the precise `efSearch` escalation cap, and where the two-pass driver lives (new method in `CohortClusterModel` vs a new helper) — all planner/researcher territory, constrained only by the SPEC's memory-safety and purity requirements.
- UUID capture uses `PathObject.getID()` (serialized, stable across reads) — the mechanism is settled; storage form (two-long vs string key) is the planner's call.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked requirements (read first)
- `.planning/phases/15-all-cells-leiden-clustering/15-SPEC.md` — Locked requirements, boundaries, acceptance criteria for this phase. MUST read before planning.

### Prior-phase design & decisions
- `.planning/notes/leiden-clustering-design.md` — Phase 14 research/design note. Defines the PhenoGraph/scanpy three-step recipe, the CWTS library rationale, cohort assignment via `sc.tl.ingest` kNN transfer (what this phase adds the all-cells alternative to), and the explicit "kNN algorithm: start brute-force; revisit with an ANN lib if slow" deferral this phase resolves.
- `.planning/phases/14-leiden-phenotype-clustering/14-01-PLAN.md` — Phase 14 plan (transfer path, LeidenModel structure, UI wiring) that Phase 15 builds on.

### Implementation touchpoints (code)
- `src/main/java/qupath/ext/celltune/model/LeidenModel.java` — pure-array Leiden core: `featureKnn` (line 52, brute-force graph build to be ANN-routed), `transferLabels` (line 340, retained), `squaredDistance` (Euclidean baseline for the recall gate).
- `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` — cohort streaming passes; `writeClusterAcrossProjectLeiden` (line 320) and `writeClusterAcrossProject` (line 359) driver where the all-cells two-pass mode is added; docstring (line 22) states the "never hold the whole cohort in memory" principle the two-pass design must honor.
- `src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java` — the cohort write action (~line 1560), `clusterMeasurementStale` gating, scatter/legend re-sync target; Method {k-means, Leiden} combo pattern to mirror for the cohort-mode radio pair.
- `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` — JUnit 5 synthetic-graph test pattern; new tests assert cluster quality / recall / UUID mapping, never raw label ids.
- `build.gradle.kts` — `implementation(...)` + `shadow(...)` bundling pattern (CWTS at line 46) for adding the ANN dependency; Maven Central; GPL-3.0 licence constraint for any new dep.

### External algorithm references
- CWTS Leiden library — https://github.com/CWTSLeiden/networkanalysis (already bundled `nl.cwts:networkanalysis:1.3.0`).
- scanpy `sc.tl.leiden` (all-cells clustering, the semantic target) — https://scanpy.readthedocs.io/en/stable/generated/scanpy.tl.leiden.html
- scanpy `sc.tl.ingest` (kNN transfer, the retained mode) — https://scanpy.readthedocs.io/en/stable/generated/scanpy.tl.ingest.html
- pynndescent (NN-descent, the fallback algorithm & scanpy's neighbor method) — https://github.com/lmcinnes/pynndescent

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `LeidenModel` SNN/Jaccard edge weighting + CWTS `LeidenAlgorithm` invocation: reused unchanged downstream of the new ANN graph build.
- `LeidenModel.transferLabels` + `CohortClusterModel.writeClusterAcrossProjectLeiden`: retained intact as the "transfer from sample" mode.
- `CohortClusterModel.sample` pooling primitive: the all-cells pass-1 pooling is this without a sample cap, plus per-cell UUID capture.
- `ScatterPlotView` cohort write threading, progress bar, status-line consumer, and `activateClusterMapper`/legend: reused; extended with the mode radio pair and post-run re-sync.
- Existing reproducibility toggle (k-means multi-restart mirror): drives ANN + Leiden seeds.

### Established Patterns
- Pure-array model classes (no QuPath/JavaFX types) unit-tested on synthetic clouds — `LeidenModel`/`NeighborhoodModel` style; the ANN graph builder and recall gate must follow this so they're testable.
- Non-destructive numeric `Cluster` measurement; phenotype `getPathClass()` never changed.
- Memory-safe streaming: never hold all image hierarchies simultaneously — two-pass (pool features + UUIDs, release; re-read + write) upholds this.
- `implementation` + `shadow` dependency bundling into the fat JAR.

### Integration Points
- ANN graph build replaces the `featureKnn` brute-force call for both single-image and cohort Leiden.
- New all-cells two-pass driver slots alongside the transfer driver in `CohortClusterModel`, selected by the project-scope mode radio in `ScatterPlotView`.
- Post-run re-sync hooks the scatter/legend refresh to the final all-cells labels.

</code_context>

<specifics>
## Specific Ideas

- Recall status message style: "ANN recall 0.982 — passed" on the status line (transparent without exposing knobs).
- Cohort-mode radio labels: "Cluster all cells" (default/exact) and "Transfer from sample" (fast/approx).
- Progress phrasing mirrors the existing per-image transfer write: "Writing 12/40 images".

</specifics>

<deferred>
## Deferred Ideas

- **Literal pynndescent / UMAP fuzzy-simplicial connectivities / modularity (RBConfiguration) quality function** — full scanpy fidelity beyond the current CPM + SNN/Jaccard + HNSW approach; possible future refinement, explicitly out of scope here.
- **PCA reduction before neighbors** — near-identity for a ~20-marker panel; revisit only if larger panels/feature spaces are introduced.
- **Spatially-aware Leiden (SpatialLeiden)** — belongs to spatial-neighborhood work, not marker phenotyping.

None of these block this phase; discussion stayed within scope.

</deferred>

---

*Phase: 15-all-cells-leiden-clustering*
*Context gathered: 2026-07-06*
