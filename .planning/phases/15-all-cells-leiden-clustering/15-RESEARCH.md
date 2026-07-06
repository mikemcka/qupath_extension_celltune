# Phase 15: All-Cells Leiden Clustering (True-Scanpy) - Research

**Researched:** 2026-07-06
**Domain:** Approximate-NN (HNSW) graph construction for Java graph-clustering at cohort scale (tens of millions of cells), memory-safe two-pass cohort write-back, runtime recall validation
**Confidence:** HIGH (ANN library recommendation, CWTS/graph scaling), MEDIUM (recall-gate tuning constants, determinism guarantee), LOW (exact wall-clock/memory figures at 30M scale — no benchmark was run in this session)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**ANN library & metric**
- D-01: Do NOT pre-commit to a specific ANN library. The phase researcher (this document) evaluates candidates (jelmerk/hnswlib-core, Lucene HNSW, others) on maintenance, licence (must be compatible with GPL-3.0), Maven Central availability, API fit, and perf at ~30M×20-D, and returns a recommendation before the library is locked. This resolves the SPEC's flagged planning blocker. **→ Resolved below in "Standard Stack": jelmerk/hnswlib-core is recommended.**
- D-02: In-repo NN-descent (pure-array, in `LeidenModel`) is the fallback if no suitable maintained Java ANN library exists. It is not the default path.
- D-03: Distance metric is **Euclidean** on z-scored markers — identical to the current exact `featureKnn` `squaredDistance`. This keeps the recall gate a like-for-like comparison and leaves downstream SNN weighting + CWTS Leiden unchanged. (If a future decision switches to cosine, the exact baseline must switch too.)

**Cohort mode UI**
- D-04: Present the two cohort modes as a **radio pair** — "Cluster all cells" / "Transfer from sample" — visible **only in project scope** (hidden in single-image scope where transfer is irrelevant).
- D-05: **All-cells is the default**, framed as the exact/true-scanpy option; transfer is framed as the fast/approximate option. Retire nothing — transfer stays fully functional.
- D-06: Single-image Leiden gains no mode selector; it simply routes its kNN graph build through the same ANN index (D-01) instead of brute-force `featureKnn`.

**Recall-gate mechanics**
- D-07: Recall is measured on a **proportional, capped** subsample of query cells (≈0.1% of pooled cells, capped at ~10k) — exact kNN computed for just those, compared against the ANN graph's neighbors → mean recall. Keeps cost O(sample × N), not O(N²).
- D-08: On recall < 95%, **escalate query-time `efSearch` geometrically** (e.g. ×2 up to a cap) and re-measure — no index rebuild. If still < 95% after the cap, **abort** the run with an actionable error; no `Cluster` labels are written.
- D-09: `efSearch`, the recall threshold, and gate internals are **hidden defaults**. The run reports the **measured recall** to the status line (e.g. "ANN recall 0.982 — passed"). No user-facing ANN knobs.

**Batch UX (ceiling / progress / cancel)**
- D-10: A **configurable soft ceiling of 50M pooled cells** triggers a confirm dialog before the batch starts (above the normal tens-of-millions, so it only catches accidental oversized runs). It warns/confirms — it does not hard-block.
- D-11: Progress reports **per-phase + per-image counts** — "Pooling 12/40 images", "Building kNN graph…", "Running Leiden…", "Writing 12/40 images" — mirroring the existing per-image status style in the transfer write.
- D-12: Cancellation during the write pass **leaves already-written images intact** (the `Cluster` measurement is a non-destructive numeric column) and the summary **reports which images were / were not updated**. No rollback pass (too costly at 30M). Cancel is honored between images, and during pool/graph/Leiden phases.

**Determinism**
- D-13: The all-cells run is seeded from the **existing reproducibility toggle** (drives both the ANN build seed and CWTS Leiden seed/random-starts). Structure-identical reproduction is the guarantee; bit-identity may require ordered/single-threaded ANN insertion — note this caveat where relevant, do not over-promise. **→ See "Common Pitfalls: ANN build determinism" below — this caveat is more severe than the CONTEXT text implies for the recommended library; read before planning acceptance criterion 6.**

### Claude's Discretion
- Exact edge/graph memory representation feeding the CWTS `Network` (`LargeIntArray`/`LargeDoubleArray`), the internal layout of the pooled feature matrix, the precise `efSearch` escalation cap, and where the two-pass driver lives (new method in `CohortClusterModel` vs a new helper) — all planner/researcher territory, constrained only by the SPEC's memory-safety and purity requirements.
- UUID capture uses `PathObject.getID()` (serialized, stable across reads) — the mechanism is settled; storage form (two-long vs string key) is the planner's call.

### Deferred Ideas (OUT OF SCOPE)
- Literal pynndescent / UMAP fuzzy-simplicial connectivities / modularity (RBConfiguration) quality function — full scanpy fidelity beyond the current CPM + SNN/Jaccard + HNSW approach; possible future refinement, explicitly out of scope here.
- PCA reduction before neighbors — near-identity for a ~20-marker panel; revisit only if larger panels/feature spaces are introduced.
- Spatially-aware Leiden (SpatialLeiden) — belongs to spatial-neighborhood work, not marker phenotyping.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| LEI-06 | Cohort/project Leiden offers an all-cells mode that clusters every cell across all images in one graph (pool → single CWTS Leiden partition → write labels back), selectable alongside the retained transfer mode. | "Standard Stack" (HNSW pick) + "Architecture Patterns: Two-Pass All-Cells Driver" + "Code Examples" give the pooling/graph/write pipeline; "Don't Hand-Roll" table covers what to reuse from `CohortClusterModel`/`LeidenModel`. |
| LEI-07 | Leiden kNN graph construction (single-image and cohort) uses HNSW, recall-validated at runtime against exact `featureKnn` on a subsample and gated at ≥95% (auto-tune then abort on failure). | "Standard Stack" (jelmerk API fit incl. `ef`/`efConstruction`), "Architecture Patterns: Recall Gate", "Common Pitfalls: recall-gate cost and escalation", "Code Examples: recall measurement". |
| LEI-08 | All-cells cohort write is memory-safe via two passes (pool features + record identity, releasing hierarchies; then re-read and write) and maps labels back by stable `PathObject` UUID. | "Architecture Patterns: Two-Pass All-Cells Driver", "Code Examples: UUID keying", confirmed `getID()` stability via existing codebase usage (`ClassificationPanel`, `GroundTruthIO`, `TrainingTileExtractor` — all key by `getID().toString()` across re-reads already). |
| LEI-09 | The scatter/UMAP preview stays subsample-based while the persisted `Cluster` measurement comes from the full all-cells run; the divergence is surfaced. | "Architecture Patterns: UI wiring & re-sync", existing `activateClusterMapper`/`clusterMeasurementStale` mechanics in `ScatterPlotView.java` (verified by reading the file, lines ~1444, 1638-1642, 1696). |
| LEI-10 | Tests cover cohort pooling/identity mapping, the ANN recall gate vs exact kNN, UUID-keyed label write-back, and all-cells community recovery on synthetic clouds. | "Validation Architecture" section maps each requirement to concrete JUnit tests mirroring `LeidenModelTest`'s existing synthetic-cloud pattern. |
</phase_requirements>

## Summary

The primary deliverable of this research is the ANN library recommendation resolving CONTEXT decision D-01: **`com.github.jelmerk:hnswlib-core`** (pure Java, Apache-2.0, Maven Central, actively maintained) is recommended over Apache Lucene's internal `org.apache.lucene.util.hnsw` package and over native-binding alternatives (Spotify Voyager, hnswlib-jna). jelmerk exposes a purpose-built, simple `Item`/`Builder`/`HnswIndex` API with a ready-made `DistanceFunctions.DOUBLE_EUCLIDEAN_DISTANCE` that matches the project's `double[]` z-scored rows exactly, `withEf`/`withEfConstruction`/`withM` tuning knobs, and a documented parallel bulk-add (`addAll(items, numThreads, listener, interval)`) — all without adding any native binary to the shadow JAR (unlike Voyager/hnswlib-jna, which are JNI/JNA wrappers around C++ and would compound the packaging risk the project already carries for Smile's OpenBLAS/ARPACK natives). Lucene's HNSW classes are real and usable standalone (`HnswGraphBuilder.create(RandomVectorScorerSupplier, M, beamWidth, seed)` even supports a construction seed, an edge jelmerk's `ThreadLocalRandom`-based level assignment lacks), but they live in an internal utility package designed to back Lucene's own codec/segment machinery, not a documented public kNN API — using them standalone means hand-implementing `RandomVectorScorerSupplier`/vector-value plumbing, and pinning to Lucene's fast-moving `9.x`→`10.x` internals is a real long-term maintenance risk for a small extension.

The single most important finding beyond the pick itself: **jelmerk/hnswlib-core's HNSW level assignment (`HnswIndex.assignLevel`) draws from `ThreadLocalRandom.current()` with no seed hook**, so — contrary to the softer "bit-identity may need single-threaded insertion" caveat in CONTEXT D-13 — even a fully single-threaded, sequential build is **not** run-to-run deterministic out of the box. Achieving the phase's acceptance criterion #6 ("two consecutive all-cells runs... yield identical labels up to permutation") requires either subclassing `HnswIndex` to override `assignLevel` with an externally-seeded `Random` (feasible — the method and class are non-final/public) or accepting that only the *Leiden* stage is bit-reproducible and documenting that graph-topology (and therefore final label) reproducibility is "best-effort, not guaranteed" for this release. This must be an explicit planning decision, not an assumption.

The second major finding: the existing `LeidenModel.buildJaccardWeightedNetwork`/`buildClosedNeighborSets`/`jaccard` code (boxed `HashSet<Integer>` per node plus a global `HashSet<Long>` edge-dedup set) is correct and fine at the current ~50k-row cohort-sample bound, but does **not** scale efficiently to tens of millions of nodes — boxed-collection overhead at that scale is tens of GB and materially slower than a primitive-array rewrite (sorted-neighbor-array intersection counting). This is squarely inside the phase's "Claude's Discretion" territory (exact edge/graph memory representation) but the planner needs to know it is now in scope: at 30M nodes × k≈15, this is not a hypothetical concern.

**Primary recommendation:** Add `com.github.jelmerk:hnswlib-core:1.2.1` via the existing `implementation` + `shadow` pattern; build one HNSW index per Leiden run (single-image and cohort, both routed the same way) over `double[]` rows using `DistanceFunctions.DOUBLE_EUCLIDEAN_DISTANCE`; measure recall on a capped subsample against the existing exact `featureKnn`; only then hand the ANN-graph adjacency lists into a *rewritten, primitive-array* Jaccard/SNN weighting step feeding the unchanged CWTS `Network`/`LeidenAlgorithm` call.

## Architectural Responsibility Map

This is a single-process desktop JVM extension (no browser/server tiers) — the relevant "tiers" are internal layers within the JVM.

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| ANN kNN graph construction (HNSW build + query) | Pure model layer (`LeidenModel`, new ANN helper) | — | Must stay JavaFX/QuPath-free and unit-testable on synthetic clouds, per the project's established `LeidenModel`/`NeighborhoodModel` pattern |
| SNN/Jaccard edge weighting + CWTS Leiden | Pure model layer (`LeidenModel`) | — | Unchanged downstream consumer; only its input source changes (ANN adjacency instead of brute-force `featureKnn`) |
| Recall gate (exact-vs-ANN comparison, efSearch escalation) | Pure model layer (new helper, ideally `LeidenModel` or sibling) | — | Needs both the exact and ANN kNN primitives — testable in isolation with synthetic data and a "degraded ANN" stub |
| Two-pass cohort pooling + UUID-keyed write-back | Streaming driver layer (`CohortClusterModel`) | QuPath I/O (`ProjectImageEntry.readImageData`/`saveImageData`) | Mirrors the existing `sample`/`writeClusterAcrossProject*` split; must never hold all hierarchies in memory at once (established project principle) |
| Progress/cancel/soft-ceiling UX, mode radio, preview re-sync | UI layer (`ScatterPlotView`) | JavaFX `Platform.runLater` marshalling | All mutation of the *open* image's live hierarchy must be marshalled to the FX thread (existing `applyMeasurement`/`applyClasses` pattern); background images are mutated off-thread |
| HNSW dependency bundling | Build tier (`build.gradle.kts`) | — | `implementation` + `shadow`, matching the existing CWTS/XGBoost/LightGBM/Smile pattern; no native binaries this time (see Standard Stack) |

## Standard Stack

### Core

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `com.github.jelmerk:hnswlib-core` | 1.2.1 (latest release, 2025-03-23) [VERIFIED: Maven Central / GitHub releases API] | Approximate-NN (HNSW) index for the Leiden kNN graph, single-image and cohort scope | Pure Java, zero native deps, Apache-2.0 (GPL-3.0-compatible per FSF: "Apache License 2.0 is compatible with GPLv3... combined software must be under GPLv3" [CITED: apache.org/licenses/GPL-compatibility.html, gplv3.fsf.org/wiki]), purpose-built `Item`/`Index` API that matches the "build once, query every point" full-kNN-graph use case exactly |
| `nl.cwts:networkanalysis` | 1.3.0 (already bundled, unchanged) | CWTS Leiden/Louvain community detection downstream of the ANN graph | No change this phase — reused as-is |

**Installation:**
```kotlin
// build.gradle.kts — add alongside the existing CWTS block
implementation("com.github.jelmerk:hnswlib-core:1.2.1")
shadow("com.github.jelmerk:hnswlib-core:1.2.1")
```

**Version verification:** [VERIFIED via GitHub Releases API `GET /repos/jelmerk/hnswlib/releases`] latest tag `v1.2.1`, published 2025-03-23T19:48:03Z; latest commit on `master` (`ed19fc4`, "fix for issue 74") is the same date. Prior releases: v1.2.0 (2025-03-06, "Repackage classes to avoid JPMS issues"), v1.1.3 (2025-01-02). This is a small, single-maintainer (Jelmer Kuperus) project with an infrequent-but-real release cadence (roughly 2-4 releases/year since ~2019) — not abandoned, but do not expect fast turnaround on a filed issue. No CVEs or known security advisories found in this session (not exhaustively checked against an OSS vulnerability DB).

### Supporting

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `com.github.jelmerk:hnswlib-core-jdk17` | 1.2.1 | Optional: SIMD (Java Vector API) `float[]` Euclidean/cosine/inner-product distance functions (`VECTOR_FLOAT_128_EUCLIDEAN_DISTANCE`, `VECTOR_FLOAT_256_EUCLIDEAN_DISTANCE`) | Only if profiling later shows the distance-function inner loop is the bottleneck. **Not recommended as the default path**: it depends on `jdk.incubator.vector`, an incubator module that (unlike the project's existing `--add-opens` workaround via `JvmModuleOpener`) cannot be enabled reflectively at runtime — it needs `--add-modules jdk.incubator.vector` on the QuPath launch command, which the project does not currently require of users. Adding this dependency would introduce exactly the kind of launch-flag friction the project has deliberately engineered around for Smile. Skip unless a later profiling pass justifies it. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| jelmerk/hnswlib-core | Apache Lucene `org.apache.lucene.util.hnsw` (`lucene-core`, latest `10.5.0` [VERIFIED: Maven Central versions page]) | Apache-2.0, GPL-3.0-compatible, and its `HnswGraphBuilder.create(RandomVectorScorerSupplier, M, beamWidth, seed)` **does** take a construction seed (a genuine determinism edge over jelmerk) [VERIFIED: `HnswGraphBuilder.java` source, `public static HnswGraphBuilder create(...long seed...)`, `randSeed` field]. But: (a) the API is low-level — there is no ready `Item`/`float[]` wrapper; the caller must implement `RandomVectorScorerSupplier`/`KnnVectorValues`-style plumbing to feed raw vectors and to walk `OnHeapHnswGraph.getNeighbors(level, node)` to emit an edge list; (b) `lucene-core` is a large dependency (much more than an ANN index — full indexing/search engine) whose `util.hnsw` package is an internal implementation detail of the codec/index-writer pipeline rather than a documented standalone public API, so signatures have shifted across recent major versions (distinct 9.x vs 10.x javadoc pages observed) [MEDIUM confidence — package-instability claim inferred from javadoc URL churn and package naming/placement, not from an explicit Lucene stability statement found in this session]; (c) bundling all of `lucene-core` to use one internal utility package is a heavier, less-idiomatic addition than a small purpose-built library. Net: more determinism control, more integration and long-term-maintenance cost. Reasonable fallback if jelmerk becomes unmaintained. |
| jelmerk/hnswlib-core | Spotify `com.spotify:voyager` (JNI wrapper around hnswlib C++, Apache-2.0, active — production use at Spotify) [VERIFIED via WebSearch + Maven Central listing] | Best raw throughput of the options found, but is a native (JNI) library shipped with per-OS/arch platform classifiers — this compounds, rather than avoids, the packaging complexity the project already accepts for Smile's OpenBLAS/ARPACK natives (cross-platform native binaries in the shadow JAR, `JvmModuleOpener`-style startup fragility). Not recommended given a pure-Java option meets the stated 30M-point, 100GB-RAM budget. |
| jelmerk/hnswlib-core | `com.stepstone.search.hnswlib.jna:hnswlib-jna` (JNA wrapper, native hnswlib C++) | Same native-packaging downside as Voyager, smaller community; not investigated further given the pure-Java option is sufficient. |
| CWTS-fed HNSW adjacency | Literal `pynndescent`/NN-descent reimplementation | Explicitly out of scope per SPEC/CONTEXT (D-02 in-repo NN-descent is the *fallback if no ANN lib exists*, not chosen here since jelmerk is available and suitable) |

### API fit detail (jelmerk/hnswlib-core), verified against source

- Package (as of 1.2.x, post the "repackage for JPMS" release): `com.github.jelmerk.hnswlib.core` (older `0.0.x`/pre-1.2 releases used `com.github.jelmerk.knn` — **do not follow tutorials/StackOverflow examples written against the old package name**).
- Core types: `Item<TId, TVector>` (id + vector + `dimensions()`), `Index<TId, TVector, TItem, TDistance>`, concrete `HnswIndex<TId, TVector, TItem, TDistance>`.
- Build: `HnswIndex.newBuilder(dimensions, distanceFunction, maxItemCount)` (or the `DistanceFunctions`-typed overload) → `.withM(m)` → `.withEf(ef)` → `.withEfConstruction(efConstruction)` → `.build()` [VERIFIED: `HnswIndex.java` `BuilderBase`/`Builder`/`newBuilder` static factories, lines ~1075-1530].
- Distance function for this project's `double[]` rows: `DistanceFunctions.DOUBLE_EUCLIDEAN_DISTANCE` [VERIFIED: `DistanceFunctions.java`, `DoubleEuclideanDistance` class + its `public static final` constant, mirrored 1:1 by a `FLOAT_EUCLIDEAN_DISTANCE` for a `float[]` variant]. This returns true Euclidean distance (not squared) — ordering-equivalent to `LeidenModel.squaredDistance` for kNN purposes, so no behavior change to downstream ranking, but do not compare raw distance *values* between the ANN and exact paths (only neighbor-set membership, for the recall gate).
- Full-graph query: call `index.findNearest(vector, k)` once per pooled row (there is no single "build me the whole kNN graph" call) — the phase must loop `IntStream.range(0, n).parallel().forEach(...)` exactly as `LeidenModel.featureKnn` already does for the brute-force path, just querying the ANN index instead of scanning `rows[]` directly. `findNearest` returns `List<SearchResult<TItem, TDistance>>` ordered by distance ascending [VERIFIED: `HnswIndex.java` line ~477].
- Parallel bulk add: `Index.addAll(Collection<TItem> items, int numThreads, ProgressListener listener, int progressUpdateInterval)` exists on the `Index` interface and delegates to `HnswIndex.addAll(...)` [VERIFIED: `HnswIndex.java` inner class delegation, lines ~1218-1229]. Internally, node mutation is guarded by a single `ReentrantLock globalLock` plus per-node `synchronized` blocks (verified in source) — so `addAll` with `numThreads > 1` is safe, but item **insertion order across threads is not fixed** (whichever thread acquires the lock next wins), which is the source of the non-determinism flagged as a Common Pitfall below.
- Tunable knobs map directly to the required hidden-default set: `M` (graph fanout), `efConstruction` (build-time accuracy/speed), `ef` (query-time accuracy/speed — this is what D-08's escalation increases at query time via `index.setEf(newEf)` without rebuilding [VERIFIED: `HnswIndex.java` `public void setEf(int ef)`, line ~688]).

## Architecture Patterns

### System Architecture Diagram

```
                         ┌─────────────────────────────────────────────┐
                         │        ScatterPlotView (UI, project scope)  │
                         │  radio: [Cluster all cells*] [Transfer]     │
                         └───────────────┬───────────────────────────--┘
                                          │ user clicks "Write cluster measurement"
                                          ▼
                         ┌─────────────────────────────────────────────┐
                         │ CohortClusterModel.writeClusterAllCells(...)│  (new, two-pass)
                         └───────────────┬─────────────────────────────┘
                                          │
             ┌────────────────────────────┼─────────────────────────────┐
             │ PASS 1: pool               │                             │ PASS 2: write
             ▼                            │                             ▼
  for each ProjectImageEntry:             │                  for each ProjectImageEntry:
  ┌─────────────────────────┐             │                  ┌──────────────────────────┐
  │ readImageData()         │             │                  │ readImageData() (again)  │
  │ extract z-scored rows   │             │                  │ look up each cell's UUID │
  │ record PathObject.getID │             │                  │  → its pooled row's label│
  │ RELEASE hierarchy       │             │                  │ write "Cluster" measurmt.│
  └──────────┬───────────────┘             │                  │ saveImageData(); save    │
             │ pooled double[][] + UUID[]  │                  └──────────────────────────┘
             ▼                            │
  ┌─────────────────────────────────────────────────────────┐
  │  LeidenModel (pure, no QuPath/FX types)                  │
  │                                                          │
  │  1. ANN graph build: HnswIndex over pooled rows           │
  │     (Euclidean, hidden M/efConstruction defaults)         │
  │  2. Recall gate: sample ≤10k query cells, exact featureKnn │
  │     vs ANN findNearest → mean overlap                     │
  │       recall < 0.95 → setEf(ef*2), re-measure (loop,      │
  │       cap N escalations) → still <0.95 → ABORT, no write   │
  │  3. SNN/Jaccard edge weighting over ANN adjacency          │
  │     (primitive-array rewrite — see Common Pitfalls)        │
  │  4. CWTS Network + LeidenAlgorithm.findClustering          │
  │     (unchanged; seeded from reproducibility toggle)        │
  │                                                          │
  │  → int[] communityLabel[pooledRowIndex]                   │
  └───────────────────────────┬───────────────────────────────┘
                              │ label[] aligned to pooled UUID[]
                              ▼
                   (feeds PASS 2 above via a UUID → label map)
```

### Recommended Project Structure

No new packages needed — this phase extends existing classes in place:

```
src/main/java/qupath/ext/celltune/model/
├── LeidenModel.java          # + hnswKnn(...) ANN graph builder, + recall gate helper(s)
├── CohortClusterModel.java   # + poolAllCells(...) (pass 1) and writeClusterAllCells(...) (pass 2 driver)
src/main/java/qupath/ext/celltune/ui/
├── ScatterPlotView.java      # + cohort-mode RadioButton pair (project scope only), + cancel wiring,
│                              #   + soft-ceiling confirm, + preview re-sync after all-cells write
src/test/java/qupath/ext/celltune/model/
├── LeidenModelTest.java      # + HNSW-vs-exact agreement tests, + recall-gate tests
├── CohortClusterModelTest.java  # new (does not exist today — see Validation Architecture)
```

### Pattern 1: Recall Gate (D-07/D-08/D-09)

**What:** Measure ANN fidelity against the existing exact `featureKnn` on a small, capped subsample; escalate `ef` (query-time only) on failure; abort if still failing.
**When to use:** Once per Leiden run (single-image and cohort), immediately after the ANN index build and before SNN weighting.
**Example:**
```java
// Illustrative — follows the pattern already used by LeidenModel.featureKnn for
// parallel per-row search, applied to a capped subsample instead of every row.
int sampleSize = Math.min(10_000, Math.max(1, (int) Math.round(n * 0.001)));
int[] sampleIdx = CohortClusterModel.sampleIndices(n, sampleSize, new Random(seed));

// Exact reference (existing brute-force method, unchanged):
int[][] exact = new int[sampleIdx.length][];
IntStream.range(0, sampleIdx.length).parallel().forEach(s -> {
    exact[s] = LeidenModel.nearestForRowExactPublicVariant(rows, sampleIdx[s], k); // reuse, do not duplicate
});

int ef = DEFAULT_EF;
double recall;
int escalations = 0;
do {
    index.setEf(ef); // query-time only — no rebuild (jelmerk HnswIndex#setEf)
    int[][] approx = queryAll(index, rows, sampleIdx, k);
    recall = meanOverlap(exact, approx);
    if (recall >= 0.95) break;
    ef *= 2;
    escalations++;
} while (escalations <= MAX_ESCALATIONS);

if (recall < 0.95) {
    throw new AnnRecallException("ANN recall " + recall + " below 0.95 after " + escalations + " escalations");
}
```

### Pattern 2: Two-Pass All-Cells Cohort Write (LEI-08)

**What:** Pass 1 streams every image, extracts z-scored marker rows + `PathObject.getID()`, releases the hierarchy. Between passes, the ANN graph + recall gate + Leiden run happens once over the fully pooled matrix. Pass 2 re-reads each image, builds a `Map<UUID, Integer>` (or a `long[2]`-keyed structure — `UUID.getMostSignificantBits()/getLeastSignificantBits()` — for a boxing-free variant at 30M scale) from that image's slice of the pooled UUID/label arrays, and writes `Cluster` per cell by UUID lookup, not by iteration order.
**When to use:** The all-cells cohort mode only; the retained transfer mode's existing single-pass stream (`writeClusterAcrossProjectLeiden`) is untouched.
**Example:**
```java
// Pass 1 (per image, mirrors CohortClusterModel.sample's per-image loop but
// captures ALL cells, not a bounded sample, plus identity):
List<PathObject> cells = detections(imageData);
float[] flat = extractor.extractMatrix(cells);
for (int i = 0; i < cells.size(); i++) {
    long msb = cells.get(i).getID().getMostSignificantBits();
    long lsb = cells.get(i).getID().getLeastSignificantBits();
    pooledIds.add(msb, lsb);         // primitive long-pair store, not String
    pooledRows.add(zscore(flat, i)); // using this image's per-cohort mean/sd
    pooledImageIdx.add(imageOrdinal);
}
entry.close-or-release-hierarchy(); // do not retain imageData across the loop

// ... (ANN graph + recall gate + Leiden run over the full pooledRows) ...

// Pass 2 (per image again):
Map<Long, Integer> msbLsbToLabel = buildLookupForThisImage(pooledIds, labels, imageOrdinal);
for (PathObject cell : detections(reReadImageData)) {
    UUID id = cell.getID();
    Integer label = msbLsbToLabel.get(pack(id.getMostSignificantBits(), id.getLeastSignificantBits()));
    cell.getMeasurementList().put(CLUSTER_MEASUREMENT, label == null ? -1.0 : label + 1.0);
}
```
`PathObject.getID()` is already used as a stable cross-read identity key throughout this codebase (`ClassificationPanel`, `GroundTruthIO`, `TrainingTileExtractor`, `DualModelClassifier`, etc., all key by `getID().toString()`), so its stability across a close/re-read cycle is already relied upon elsewhere — not a new assumption for this phase, just a new *scale*.

### Pattern 3: UI Wiring — Cohort Mode Radio Pair (D-04/D-05)

**What:** A `RadioButton`/`ToggleGroup` pair, visible only when `scope == Scope.PROJECT`, mirroring the existing `methodCombo` show/hide binding pattern already in `ScatterPlotView` (lines ~327-346: `managedProperty().bind(visibleProperty())` + a listener that flips visibility).
**When to use:** Added next to (not replacing) the existing `Method {k-means, Leiden}` combo; only relevant when `Method == LEIDEN` **and** `scope == PROJECT`.
**Anti-pattern to avoid:** Do not repurpose `methodCombo` itself for this — it selects the clustering *algorithm* (k-means vs Leiden); the new radio selects the *cohort assignment mechanism* for Leiden specifically. Conflating them breaks the k-means cohort path (unchanged per SPEC).

### Anti-Patterns to Avoid

- **Boxed-collection Jaccard/SNN weighting at cohort scale:** The current `buildJaccardWeightedNetwork`/`buildClosedNeighborSets` (per-node `HashSet<Integer>`, a global `HashSet<Long>` edge dedup) is fine at ≤50k rows but must be rewritten with primitive `int[]` sorted-neighbor arrays and merge-based intersection counting before it is used at 30M nodes — see Common Pitfalls.
- **Rebuilding the ANN index to escalate recall:** D-08 explicitly requires re-querying with a higher `ef` via `setEf()`, not rebuilding the index. jelmerk's `ef` is a live, mutable field on the built index (`setEf`), so this is directly supported — do not add an index-rebuild path for the escalation loop.
- **Using `String` UUID keys at 30M-cell scale for the write-back map:** every other UUID-keying site in this codebase uses `getID().toString()` (a 36-char boxed `String`) because those call sites operate per-image (thousands of cells), not per-cohort (tens of millions). At all-cells scale, switching to a primitive `(long, long)` pair (or a single packed structure) avoids ~10-20x the string/object overhead. This is a deliberate, phase-specific deviation from the codebase's existing convention, not an inconsistency — flag it in code comments so it isn't "fixed" back to `.toString()` in review.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Approximate nearest-neighbor index | Custom HNSW implementation | `com.github.jelmerk:hnswlib-core` | Well-tested, tunable, already benchmarked against the reference nmslib/hnswlib C++ implementation by its author; reimplementing HNSW correctly (level assignment, layer search, pruning heuristics) is a multi-week undertaking with subtle correctness bugs (this is exactly what D-02's NN-descent fallback exists for — only if no library is usable, which is not the case here) |
| Leiden/Louvain community detection | Custom graph partitioning | `nl.cwts:networkanalysis` (unchanged, already bundled) | Out of scope for this phase — no change needed |
| UUID parsing/packing for a fast lookup key | Custom UUID string-splitting | `java.util.UUID.getMostSignificantBits()`/`getLeastSignificantBits()` (already a public no-alloc JDK API) | Already implicitly relied upon everywhere `getID()` is called; just avoid the extra `.toString()` allocation at cohort scale |
| Progress/cancellation plumbing for a long batch job | A bespoke ad-hoc cancel flag threaded through every method signature | A single `AtomicBoolean cancelled` (or a small `CancellationToken`-style holder) checked at each phase boundary and each per-image iteration, exposed to the UI via a `Cancel` button | No existing pattern for *true* mid-run cancellation exists in this codebase today (searched — only post-hoc "cancelled" dialog-choice labels exist, e.g. `ScatterPlotView.java` lines 1168/1397/1841); this must be built new but should stay minimal — do not over-engineer a generic task-cancellation framework for one call site |

**Key insight:** The phase's actual new engineering surface is narrower than it first appears — CWTS Leiden, the SNN/Jaccard *concept*, the two-pass streaming *pattern*, and UUID-based identity are all already proven in this codebase. The genuinely new pieces are (1) the ANN index itself (a well-supported library, not hand-rolled), (2) the recall gate (small, testable, novel to this codebase), (3) a primitive-array rewrite of the edge-weighting step for scale, and (4) real mid-run cancellation (does not exist yet).

## Common Pitfalls

### Pitfall 1: ANN build determinism is weaker than it looks (affects LEI-06 acceptance criterion 6)

**What goes wrong:** Two consecutive all-cells runs with the reproducibility toggle ON produce different Leiden labels (even up to permutation), because the *upstream* ANN graph topology differs run to run.
**Why it happens:** `HnswIndex.assignLevel(double lambda)` calls `ThreadLocalRandom.current().nextDouble()` directly [VERIFIED: `HnswIndex.java` line ~1125] — there is no constructor/builder parameter to inject a seeded `Random`. Additionally, `addAll(items, numThreads, ...)` mutates the graph behind a single `ReentrantLock` plus per-node `synchronized` blocks, so with `numThreads > 1` the *order* in which concurrent threads acquire the lock (and thus the order nodes are actually linked into the graph) is scheduler-dependent, not just level-assignment-dependent.
**How to avoid:** For the all-cells run specifically, when the reproducibility toggle is ON: (a) build the index with `numThreads == 1` (sequential `add()` calls in a fixed, deterministic pooled-row order) to remove the concurrent-insertion-order source of variance, AND (b) if bit-for-bit graph reproducibility is required (not just "structure-identical" per SPEC's own wording), subclass `HnswIndex` to override `public int assignLevel(double lambda)` with a call into an instance-seeded `java.util.Random` instead of `ThreadLocalRandom` — `assignLevel` is `public` and the class is non-final, so this is a legitimate, contained override, not a hack. If the planner decides the SPEC's "structure-identical... up to label permutation" language is satisfied by "same Leiden seed acting on an ANN graph that is highly likely, but not proven, to be recall-gate-equivalent between runs," that is an acceptable planning call — but it must be an explicit, documented decision, not a silent assumption, because it is weaker than what D-13's own text implies is achievable.
**Warning signs:** A reproducibility test (mirroring `LeidenModelTest.clusterSameSeedProducesIdenticalLabels`) that clusters the same pooled synthetic input twice with the toggle on and asserts identical labels will be flaky/failing if the sequential-build + assignLevel-override mitigation above is skipped.

### Pitfall 2: Boxed-collection Jaccard/SNN weighting does not scale to cohort size

**What goes wrong:** At 30M pooled cells with k≈15 graph neighbors, `buildClosedNeighborSets` allocates 30M `HashSet<Integer>` objects (~16 boxed entries each) and `buildJaccardWeightedNetwork`'s `seenEdges` is a single `HashSet<Long>` sized for up to ~225M boxed `Long` edge keys. Rough order-of-magnitude memory: tens of GB in collection/boxing overhead alone, on top of the actual edge data — plausible within the stated 100GB+ RAM budget but wasteful, and the boxed hashing/GC pressure will dominate wall-clock time far more than the ANN query itself.
**Why it happens:** The existing code was written and tuned for the ≤50k-row cohort-sample bound (Phase 14), where boxed collections are a non-issue; this phase is the first time the Jaccard-weighting step itself needs to scale.
**How to avoid:** Rewrite the closed-neighbor-set + Jaccard + edge-dedup step using sorted primitive `int[]` arrays per node (the ANN `findNearest` results, sorted once) and a merge-based intersection count (two sorted-pointer walk, O(k) per pair instead of a hash lookup per element) instead of `HashSet<Integer>`/`HashSet<Long>`. Edge dedup can be achieved by only emitting each undirected edge from the lower-indexed endpoint when it also appears in the higher-indexed endpoint's neighbor list (symmetrize-by-construction) rather than a global "seen edges" set, if the ANN k-NN graph is made explicitly symmetric before this step — or accept a lighter-weight open-addressing long-set if full symmetrization isn't guaranteed. This exact representation is explicitly "Claude's Discretion" per CONTEXT, but the *need* to change it is now a confirmed, in-scope finding, not a hypothetical.
**Warning signs:** A synthetic-scale smoke test at, say, 2-5M synthetic rows (not full 30M — too slow for CI) showing multi-minute wall-clock time or multi-GB heap growth in the weighting step alone, disproportionate to the ANN build/query time, is the signal this rewrite is needed before real-world 30M-cell use.

### Pitfall 3: Recall-gate cost must stay bounded regardless of cohort size

**What goes wrong:** A naive recall check that scans the *entire* pooled set against exact `featureKnn` reintroduces the O(n²) cost this whole phase exists to avoid.
**Why it happens:** It's tempting to reuse `LeidenModel.featureKnn` wholesale "just to be safe" instead of writing a query-only exact-neighbor helper for a small subsample.
**How to avoid:** D-07 already specifies the fix (≈0.1% capped at ~10k query cells) — the implementation must compute exact neighbors **only for the sampled query rows** against the **full pooled set** (O(sample × n), not O(n²)), which needs a new "exact kNN for a small query set against a large reference set" helper distinct from the existing all-pairs `featureKnn`. This is structurally identical to the existing `transferLabels`/`nearestReferenceIndices` brute-force-against-reference pattern already in `LeidenModel` — that code can likely be reused/adapted directly (it already does "small query set vs large reference set" brute force) rather than writing a new method from scratch.
**Warning signs:** Recall-gate wall-clock time that scales with the *square* of cohort size rather than linearly with `sampleSize × n`.

### Pitfall 4: No existing true mid-run cancellation to copy

**What goes wrong:** Planner/implementer assumes an existing cancellation mechanism can be reused for LEI-06's cancel requirement (D-12) and is surprised to find none exists.
**Why it happens:** The codebase has several UI flows that use the word "cancel" (`ScatterPlotView.java` lines 1168, 1397, 1841), but all of them are pre-flight dialog-choice cancellations ("user declined the confirm dialog"), not mid-batch interruption of an already-running background thread.
**How to avoid:** Plan for a small, new `AtomicBoolean`/cancellation-flag mechanism checked at phase boundaries (pool/graph/Leiden/write) and between per-image iterations within pass 1 and pass 2, wired to a `Cancel` button that becomes visible only during the all-cells run. Keep it minimal — this is a new but small primitive, not a framework.
**Warning signs:** A cancellation test (LEI-10-adjacent) that expects a `Future`/`Task`-based cancel mechanism to already exist and fails when no such hook is found in `CohortClusterModel`.

## Code Examples

### Recall measurement — reusing the existing "query small set vs large reference" shape

`LeidenModel.transferLabels`/`nearestReferenceIndices` (lines 340-417 of the current file) already implements "for each of a small set of query rows, find the k nearest rows in a (possibly much larger) reference array, brute-force, parallel across query rows." The recall gate needs exactly this same shape (query = the capped sample, reference = the full pooled set), just returning neighbor *indices* to compare against the ANN graph's neighbor lists for the same rows, rather than a majority-vote label. Adapting/extracting the existing bounded-heap selection logic (`nearestReferenceIndices`) instead of re-deriving it avoids duplicating the O(n log k) selection code that already exists and is already tested.

### Association-strength normalization note (already-solved constraint, do not re-derive)

The existing `LeidenModel.cluster` already calls `rawNetwork.createNormalizedNetworkUsingAssociationStrength()` before clustering, specifically because CPM's `resolution` has no natural "1.0" scale against raw Jaccard weights [existing in-code comment, verified by reading `LeidenModel.java` lines 220-228]. This is unchanged by this phase — the ANN-sourced graph feeds into the *same* Jaccard-weight → association-strength-normalize → Leiden pipeline; only the source of the raw kNN adjacency changes (ANN query vs brute-force scan). Do not re-derive or second-guess this normalization step when integrating the ANN path.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Cohort Leiden: fit on bounded sample (≤50k), kNN-label-transfer (`sc.tl.ingest`-style) to every other cell | Cohort Leiden: pool ALL cells, one HNSW kNN graph, one CWTS Leiden partition (`sc.tl.leiden`-style), UUID write-back | This phase (v1.5, Phase 15) | Semantically matches stock scanpy's all-cells community detection instead of approximating it via a downstream classifier; removes the O(nCells × nRef) brute-force transfer bottleneck |
| Brute-force O(n²) `featureKnn` for Leiden graph construction (both single-image and cohort) | HNSW approximate-NN graph construction (both scopes), gated by a runtime recall check | This phase | Makes single-image Leiden viable on very large images and cohort Leiden viable at tens-of-millions-of-cells scale; brute-force `featureKnn` is retained only as the recall-gate's exact reference and (per D-02) as an in-repo fallback path if no ANN library were viable |

**Deprecated/outdated:** None — Phase 14's transfer path is explicitly retained (not deprecated), per D-05/LEI-06 boundary ("retire nothing").

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | jelmerk/hnswlib-core's `org.apache.lucene.util.hnsw` "not a stable standalone public API" characterization (package-instability inference) is based on javadoc URL churn/package placement, not an explicit Lucene project statement found in this session | Standard Stack: Alternatives Considered | If Lucene's HNSW package is actually stable/committed-to as public API, the Lucene fallback becomes more attractive than presented; low practical risk since jelmerk is the primary recommendation regardless |
| A2 | "Structure-identical... up to label permutation" reproducibility (SPEC acceptance criterion 6) can be satisfied by (single-threaded ANN build + existing Leiden seed) without also overriding `assignLevel`'s `ThreadLocalRandom` use | Common Pitfalls: Pitfall 1 | If the acceptance test is strict, a run-to-run flake will surface; the `assignLevel` override is the safe mitigation and should be planned in, not treated as optional, unless the team explicitly relaxes the acceptance criterion |
| A3 | Rough memory/wall-clock order-of-magnitude estimates for the boxed-collection Jaccard-weighting step at 30M nodes (Common Pitfalls: Pitfall 2) are back-of-envelope reasoning about JVM collection overhead, not a measured benchmark in this session | Common Pitfalls: Pitfall 2 | If actual overhead is smaller than estimated, the primitive-array rewrite could be deprioritized to a later optimization pass rather than done in this phase; recommend a small synthetic-scale (1-5M row) timing smoke test early in execution to confirm before committing to the rewrite's scope |
| A4 | No CVEs/security advisories exist for `com.github.jelmerk:hnswlib-core` | Standard Stack: Core table footnote | Not exhaustively checked against an OSS vulnerability database (e.g., OSV, GitHub Advisory DB) in this session; low risk given it's a small, dependency-free (besides itself) pure-Java library |

**If this table is empty:** N/A — see rows above.

## Open Questions

1. **Does the planner accept "best-effort" ANN-topology reproducibility, or does it require the `assignLevel` seeding override?**
   - What we know: jelmerk's default level assignment is unseeded; a subclass override is a clean, contained fix.
   - What's unclear: whether SPEC acceptance criterion 6's "identical labels up to permutation" is meant to tolerate the (likely small, but nonzero) probability that a different ANN graph topology yields a different Leiden partition even with the same downstream seed.
   - Recommendation: plan the `assignLevel`-override + single-threaded-build mitigation as an explicit task rather than leaving it implicit; it is cheap to build and removes the ambiguity entirely.

2. **What is the actual wall-clock/memory cost of the primitive-array Jaccard/SNN rewrite at real cohort scale (tens of millions of cells)?**
   - What we know: the current boxed-collection approach is correct but architecturally mismatched to that scale (Pitfall 2).
   - What's unclear: exact numbers — no benchmark was run this session (would require a multi-GB synthetic dataset and meaningful wall-clock, out of scope for a research pass).
   - Recommendation: the planner should schedule a small synthetic-scale timing smoke test (1-5M rows) as an early execution task to validate the rewrite's necessity and sizing before committing to a specific data structure, per the existing project practice of profiling before optimizing (see `featureKnn`'s own javadoc discussing its bounded-max-heap optimization history).

3. **Where exactly does the soft-ceiling (50M cells, D-10) count get computed** — before pass 1 starts (requires a cheap "count detections per image" pre-scan across the whole project) or is it acceptable to confirm the dialog based on a rough estimate (e.g., image count × average detections seen historically)?
   - What we know: QuPath's `PathObjectHierarchy.getObjects(null, PathObject.class)` requires a full hierarchy read to get an exact count; a pre-scan pass adds a third full project read before pooling even starts.
   - What's unclear: whether a cheaper proxy (e.g., a fast per-image object count without fully materializing feature vectors) is available via the QuPath API, or whether the existing per-image `ProjectImageEntry` metadata already caches a detection count.
   - Recommendation: the planner should check whether `ImageData`/`PathObjectHierarchy` exposes a count-only accessor cheaper than a full `getObjects()` materialization; if not, accept the extra lightweight pre-scan (count-only, no feature extraction) as the cost of the confirm dialog, since it is far cheaper than the two full passes it gates.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK | Build/run | ✓ | 25.0.2 (Temurin) [VERIFIED: `java -version` in this session] | — |
| Gradle wrapper | Build | ✓ | `gradlew.bat` present in repo root | — |
| Maven Central reachability | Dependency resolution (`com.github.jelmerk:hnswlib-core`) | Assumed ✓ (standard project build already resolves Maven Central deps for CWTS/XGBoost/LightGBM/Smile) | — | — |
| `jdk.incubator.vector` module | Optional `hnswlib-core-jdk17` SIMD distance functions | Present in JDK 25 as an incubator module, but requires an explicit `--add-modules` launch flag not currently used by this project | — | Skip `hnswlib-core-jdk17`; use plain `hnswlib-core`'s scalar `DOUBLE_EUCLIDEAN_DISTANCE` (recommended default — see Standard Stack) |

**Missing dependencies with no fallback:** None identified.

**Missing dependencies with fallback:** `hnswlib-core-jdk17`'s SIMD path — fallback is simply not using it (plain `hnswlib-core` scalar distance function), which is the recommended default anyway.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (already in use project-wide) |
| Config file | `build.gradle.kts` (`testImplementation(libs.junit)`, `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`) — no separate JUnit config file |
| Quick run command | `./gradlew test --tests "qupath.ext.celltune.model.LeidenModelTest"` (and, once created, `--tests "qupath.ext.celltune.model.CohortClusterModelTest"`) |
| Full suite command | `./gradlew test` (and `./gradlew check` for the Spotless-gated full build) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| LEI-06 | All-cells mode assigns every pooled cell a label from a single partition; label count == total cell count; community recovery purity ≥ threshold on synthetic multi-image blobs | unit | `./gradlew test --tests "qupath.ext.celltune.model.LeidenModelTest.allCellsClusterAssignsEveryRow*"` (new test method) | ❌ Wave 0 |
| LEI-07 | HNSW-vs-exact-kNN downstream Leiden labels agree within an ARI tolerance; single-image Leiden on a large synthetic input completes via the HNSW path | unit | `./gradlew test --tests "qupath.ext.celltune.model.LeidenModelTest.hnswVsExactLeidenAgreesByAri*"` (new) | ❌ Wave 0 |
| LEI-07 (recall gate) | Degraded HNSW params (artificially low `ef`) trigger the auto-tune-then-abort path; adequate params pass and proceed | unit | `./gradlew test --tests "qupath.ext.celltune.model.LeidenModelTest.recallGate*"` (new) | ❌ Wave 0 |
| LEI-08 | Pools a synthetic two-image set, clusters, and confirms every cell's written label matches its pooled-row label by UUID even when the second read returns cells in a **different order** | unit | `./gradlew test --tests "qupath.ext.celltune.model.CohortClusterModelTest.uuidWriteBackSurvivesReorder*"` (new file) | ❌ Wave 0 |
| LEI-09 | After an all-cells write, the plot legend cluster count equals the count written to cells | manual + unit (the label/count reconciliation logic itself is unit-testable; the visual overlay match is manual, mirroring SPEC's own acceptance-criterion wording "a manual check confirms the overlay colours match") | unit for count reconciliation; manual for the visual check | manual-only justification: `activateClusterMapper`'s JavaFX `MeasurementMapper`/`Viewer` interaction has no existing headless test harness in this codebase (confirmed — no `ui/ScatterPlotViewTest` exists) | ❌ (count-reconciliation logic) |
| LEI-10 | Cohort pooling/identity mapping, ANN recall gate, UUID write-back, all-cells community recovery — all on synthetic clouds | unit (aggregate of the rows above) | `./gradlew test --tests "qupath.ext.celltune.model.*"` | See rows above |

### Sampling Rationale

- **Per task commit:** run the single new/changed test class (`LeidenModelTest` and/or `CohortClusterModelTest`) — fast (seconds), synthetic data only, no QuPath project I/O.
- **Per wave merge:** `./gradlew test` (full suite) — confirms no regression in the retained transfer path (`transferLabels` and its Phase 14 tests must remain green per the SPEC's own acceptance criteria) and in unrelated model/UI tests.
- **Phase gate:** `./gradlew test` + `./gradlew shadowJar` green (SPEC acceptance criterion: "full `test` + `shadowJar` build is green") before `/gsd-verify-work`. `shadowJar` specifically validates that the new `hnswlib-core` dependency bundles cleanly into the fat JAR without classpath conflicts (jelmerk 1.2.x's JPMS repackaging in v1.2.0 was explicitly done to avoid module-path collisions, which is favorable here, but the shadow-jar build is still the only way to confirm no transitive conflict with `nl.cwts:networkanalysis`/Smile/XGBoost/LightGBM's existing dependency graph).
- Synthetic-scale note (ties to Open Question 2): none of the above commands should be asked to run at real 30M-cell scale — CI-appropriate synthetic sizes (hundreds to low thousands of rows for correctness tests) are sufficient to validate the *logic*; a separate, manually-run timing smoke test at 1-5M synthetic rows (not part of the standard `test` task) is recommended once during implementation to validate the Pitfall 2 rewrite's necessity, not on every commit.

### Wave 0 Gaps

- [ ] `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` — does not exist today; needed for LEI-08's UUID-reorder test and general two-pass driver coverage (pooling, write-back, cancellation-leaves-images-intact).
- [ ] New test methods in the existing `LeidenModelTest.java` for: HNSW-vs-exact agreement (ARI), the recall gate (pass/escalate/abort paths), and all-cells label-count == cell-count community recovery.
- [ ] No framework install needed — JUnit 5 is already wired; only new test *files/methods* are required, not new tooling.

## Security Domain

`security_enforcement` is not explicitly disabled in `.planning/config.json`, so this section is included per policy, though this phase's actual attack surface is minimal: CellTune is a local, single-user QuPath desktop extension operating on local project files with no network listener, no authentication surface, and no multi-tenant data boundary.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | No auth surface in this extension |
| V3 Session Management | No | N/A — desktop extension, no sessions |
| V4 Access Control | No | N/A — single local user, local filesystem permissions govern access |
| V5 Input Validation | Marginally yes | The soft cell-count ceiling (D-10) and the recall-gate abort (D-08) are themselves a form of input/scale validation preventing runaway resource consumption from an oversized or malformed project; no external/untrusted input parsing is introduced by this phase (marker values come from the user's own QuPath project) |
| V6 Cryptography | No | No crypto operations introduced |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Denial of Service via unbounded memory/time (accidental 100M+-cell project pooled with no guard) | Denial of Service | The soft ceiling confirm dialog (D-10) and cancellable batch (D-12) are the mitigations already designed into this phase; no additional control needed |
| Silent data corruption from a race between pass-1 pooling and pass-2 write-back if a user edits/deletes cells in the open image between passes | Tampering (accidental, not adversarial) | Not explicitly addressed by CONTEXT/SPEC — worth flagging to the planner as an edge case: pass 2's UUID lookup should treat "UUID from pass 1 not found in pass 2's re-read" as a silently-skipped cell (already the natural behavior of a `Map.get()` returning null → -1, per the Pattern 2 code example), not a crash |

## Sources

### Primary (HIGH confidence)
- `com.github.jelmerk:hnswlib-core` GitHub repository, README, and source (`HnswIndex.java`, `DistanceFunctions.java`, `Jdk17DistanceFunctions.java`) fetched directly — https://github.com/jelmerk/hnswlib
- jelmerk/hnswlib GitHub Releases + Tags API (`GET /repos/jelmerk/hnswlib/releases`, `/tags`) — confirms v1.2.1 (2025-03-23) is latest
- `nl.cwts:networkanalysis` GitHub repository + tags API — confirms 1.3.0 (already bundled) is latest — https://github.com/CWTSLeiden/networkanalysis
- Apache Lucene `HnswGraphBuilder.java`, `RandomVectorScorerSupplier.java`, `VectorSimilarityFunction.java` source fetched directly from `apache/lucene` `main` branch — https://github.com/apache/lucene
- Apache Software Foundation, "Apache License v2.0 and GPL Compatibility" — https://www.apache.org/licenses/GPL-compatibility.html
- FSF GPLv3 Wiki, "Compatible licenses" — https://gplv3.fsf.org/wiki/index.php/Compatible_licenses
- Direct reads of this repository's `LeidenModel.java`, `CohortClusterModel.java`, `ScatterPlotView.java`, `LeidenModelTest.java`, `build.gradle.kts`, `CLAUDE.md`, `.planning/config.json`
- `java -version` executed in this session confirming JDK 25.0.2 Temurin is installed

### Secondary (MEDIUM confidence)
- Maven Central / mvnrepository.com listings for `com.github.jelmerk:hnswlib-core` and `org.apache.lucene:lucene-core` version history (via WebSearch, cross-checked against the GitHub Releases API for jelmerk specifically)
- Package-instability characterization of `org.apache.lucene.util.hnsw` (javadoc URL churn across 9.x/10.x observed; no explicit Lucene project statement of internal-vs-public-API status was located — see Assumptions Log A1)

### Tertiary (LOW confidence)
- Spotify Voyager and hnswlib-jna described from WebSearch summaries only (license/packaging model), not independently verified against source — sufficient for the "native-binding tradeoff" argument since jelmerk is the actual recommendation, but not verified to the same depth

## Metadata

**Confidence breakdown:**
- Standard stack (ANN library pick): HIGH — verified directly against source code and release metadata for all three real candidates (jelmerk, Lucene, Voyager/hnswlib-jna tier)
- Architecture (two-pass driver, recall gate, UI wiring): HIGH for the parts that extend already-read, already-understood existing code (`CohortClusterModel`, `ScatterPlotView`, `LeidenModel`); MEDIUM for the specific primitive-array Jaccard/SNN rewrite sizing (no benchmark run)
- Pitfalls: HIGH for the ANN-determinism finding (directly verified in `HnswIndex.java` source — this is a novel, load-bearing finding not present in the CONTEXT/SPEC text); MEDIUM for the boxed-collection scaling estimate (reasoned, not measured)
- Determinism/reproducibility guarantee (SPEC acceptance criterion 6): MEDIUM — the gap between D-13's stated caveat and the actual jelmerk behavior is real and documented, but the planner's chosen mitigation approach is not yet decided

**Research date:** 2026-07-06
**Valid until:** ~2026-10 (30 days would be short for a library-selection decision at this depth; Java ANN library ecosystem moves slowly — jelmerk releases roughly 2-4x/year, Lucene HNSW internals move faster but are not the chosen path). Re-verify the jelmerk version pin if planning is deferred more than ~3 months.
