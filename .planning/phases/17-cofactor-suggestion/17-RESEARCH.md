# Phase 17: In-QuPath Cofactor Suggestion - Research

**Researched:** 2026-07-07
**Domain:** arcsinh-cofactor estimation for multiplexed-imaging cell measurements; QuPath 0.7 JavaFX extension; pure-array model + non-modal tool window; memory-safe cohort pooling
**Confidence:** HIGH (this is a reuse-driven phase; every touchpoint verified against source in this session)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Robust-percentile background estimator. The cofactor for each feature is a percentile of that feature's per-cell distribution near the background/signal knee, reusing `ImagePixelStats.percentileSorted` (interpolated). No model fitting — deterministic, cheap, robust when a marker is positive only in a rare subset. (Chosen over 2-population GMM/Otsu and over median/MAD.)
- **D-02:** Fixed rule for all features — the SAME percentile is applied identically to every selected feature (not adaptive per-feature). Predictable and easy to unit-test.
- **D-03:** The exact percentile value is NOT locked — the researcher calibrates it against SPEC targets (raw-fluorescence → global in the tens; MIBI ≈ 0.05) then it becomes a fixed constant. **(RESOLVED in this research: `BACKGROUND_PERCENTILE = 50.0` — see the calibration section.)**
- **D-04:** The estimator is a pure-array model class (no QuPath/JavaFX types), unit-tested on synthetic distributions with a known injected background — follows the `LeidenModel`/`NeighborhoodModel` convention.
- **D-05:** Non-modal tool window (house style, like `NeighborhoodAnalysisDialog`) with a progress bar + Cancel; compute off the JavaFX thread (UI updates via `Platform.runLater`). The Normalise Features dialog stays open; Apply writes the recommended global cofactor into its spinner.
- **D-06:** Launched from a "Suggest…" button beside the cofactor spinner in `NormalizationPane`, visible/enabled only for the arcsinh transform (mirrors the spinner's own arcsinh-only visibility).
- **D-07:** Run is user-triggered via a Run/Compute button — the window does NOT auto-compute on open.
- **D-08:** Reuse Phase 15's configurable soft-ceiling confirm (~50M pooled cells) before a whole-project run; per-phase/per-image progress and a `CancellationToken`-backed Cancel mirror the cohort-write UX.
- **D-09:** Rich diagnostic table — one row per selected feature: `feature | n cells | background estimate | median | p99 | suggested cofactor`. Per-feature values are advisory/display only.
- **D-10:** One recommended global cofactor = median of the per-feature cofactors, shown prominently.
- **D-11:** Dead (all-background / near-zero variance) and saturated markers are flagged in the table and excluded from the global-median aggregation — reuse the signal-gated / dead-channel exclusion idea from `PixelCohortAnalyzer`.
- **D-12:** The calibration picker is a reused `FeatureSelectionPane` (grouped/searchable, selection independent of the normalize set, no hardcoded measurement name), pre-selecting the per-marker mean-intensity features by default.
- **D-13:** Default scope = whole project (all cells); open image also available. Whole-project pooling reuses `CohortClusterModel.poolAllCells`' memory-safe two-pass pattern (no sampling — rare markers preserved).

### Claude's Discretion
- The exact percentile constant (RESOLVED here per D-03: 50.0).
- The precise "background estimate" summary statistic shown in the table (may differ from the percentile used as the cofactor).
- Number/column formatting; button micro-layout.
- The estimator's internal home (a new pure class such as `CofactorEstimator` vs a static helper) — recommend a new class `CofactorEstimator`.
- Status-line / log phrasing for progress and for the applied recommendation.

### Deferred Ideas (OUT OF SCOPE)
None deferred at discuss-phase. Recorded OUT of scope in SPEC.md: transformed-histogram preview; a selectable estimator menu; per-feature apply/per-feature normalization; alternative normalization transforms (CLR/z-score/min-max/reference-channel/ComBAT); reading measurements from geojson/CSV/file streams; persisting suggestions across sessions; bounded/sampled project pooling.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| COF-01 | Launch a cofactor-suggestion tool from the Normalise Features workflow | "Suggest…" button in `NormalizationPane` beside the cofactor spinner, arcsinh-only visibility mirroring `updateCofactorVisibility()` (line 205). Opens a non-modal owned window. **Modality caveat below.** |
| COF-02 | Grouped/searchable calibration picker, independent of the normalize set, no hardcoded measurement name | Reuse `FeatureSelectionPane(owner, featureNames, preSelected)` → `showAndWait()` returns `List<String>`. Default pre-selection computed at runtime via `IntensityHeatmap.discoverMarkerFeatures(featureNames)` (no hardcoded name). |
| COF-03 | In-memory computation only, no geojson/file streaming | Open-image: `CellFeatureExtractor.extractRowRaw(cell)` / `extractMatrix`. Whole-project: `CohortClusterModel.poolAllCells` (reads `ImageData` per entry, never geojson/CSV). |
| COF-04 | Per-feature background-level estimator | New pure `CofactorEstimator` class; per-feature cofactor = `percentileSorted(sortedRawVals, 50.0)`. Synthetic recovery within factor-of-2 verified analytically (simulation below). |
| COF-05 | Per-feature results table | D-09 rich table: `feature | n cells | background | median | p99 | suggested cofactor`. Mirror `ClusterAssignmentPane` grid pattern; `TableView` recommended for N sortable rows. |
| COF-06 | Single recommended global cofactor aggregated across features | Global = `RobustStats.median(perFeatureCofactors)` over non-excluded features (D-10/D-11). |
| COF-07 | Apply into existing cofactor input in one action | Apply writes `globalCofactor` into `NormalizationPane`'s cofactor spinner (`setValue`, clamped to 0.01–10000). No mutation, no normalization run. |
| COF-08 | Scope selector: open image vs whole project (pooled) | Scope radio: "Open image" (live `ImageData` detections) vs "Whole project (all cells)" (`poolAllCells`, all cells, two-pass). Default whole-project (D-13). |
</phase_requirements>

## Summary

Phase 17 adds a "Suggest cofactor…" tool to the existing Normalise Features (arcsinh) workflow. It is a **heavily reuse-driven** phase: the estimator is a new small pure-array model class (`CofactorEstimator`), everything else composes existing, verified machinery — `FeatureSelectionPane` (calibration picker), `CohortClusterModel.poolAllCells` (memory-safe whole-project pooling), `CellFeatureExtractor.extractRowRaw`/`extractMatrix` (open-image reads), `ImagePixelStats.percentileSorted` (the estimator statistic + table's p99), `RobustStats.median` (global aggregation), `NeighborhoodAnalysisDialog`'s non-modal-window + worker-thread + progress/cancel pattern, and `NormalizationPane`'s cofactor spinner (launch host + apply target). The `FeatureNormalizer` arcsinh formula is reused unchanged.

Two findings materially shape the plan. **(1) The estimand is the background/negative intensity LEVEL.** `arcsinh(x/c)` collapses values below `c` toward linear and log-compresses values above it, so `c` must sit at the background/signal knee — it tracks the LOW end of the per-cell distribution, not a high signal percentile. I calibrated the single fixed percentile against both SPEC targets by simulation and analysis: **`BACKGROUND_PERCENTILE = 50.0` (the median of each feature's per-cell distribution)** produces a recommended GLOBAL cofactor of ~35–47 on a raw-fluorescence panel (in the target tens ~25–50) AND ~0.05 on a MIBI-scale panel — the SAME constant, differing only because the intensity scales differ. It recovers a known injected background within a factor of ~2 for positive fractions up to ~40% (the SPEC tolerance), and is the most robust/standard percentile choice. The residual gap (markers that are positive in ≥~50% of cells) is handled by the D-11 dead/saturated exclusion and flagged as an Open Question.

**(2) Two path/architecture corrections for the planner.** `RobustStats` lives at `util/RobustStats.java`, NOT `model/RobustStats.java` as CONTEXT/SPEC state — flagged prominently below. And `CohortClusterModel.poolAllCells` returns **z-scored** rows plus per-marker `mean`/`sd`; the cofactor estimator needs **raw** per-cell values, so the plan must either (a) add a raw-pooling sibling that skips the final `standardizeColumns` (reusing the identical two-pass loop; recommended), or (b) recover raw exactly as `raw = z*sd + mean` per marker (lossless, but indirect). Finally, `NormalizationPane` is **`APPLICATION_MODAL`** — the non-modal suggest window (D-05) must be a child window owned by the `NormalizationPane` stage (`Modality.WINDOW_MODAL` owned by the pane, or a non-modal owned window whose owner chain reaches the pane) or the modal parent will block interaction.

**Primary recommendation:** Add a pure `CofactorEstimator` model class with `BACKGROUND_PERCENTILE = 50.0`; wire a "Suggest…" button in `NormalizationPane` that opens a non-modal window OWNED BY the `NormalizationPane` stage; add a raw-pooling variant to `CohortClusterModel` for whole-project scope; aggregate per-feature medians via `RobustStats.median` excluding dead/saturated markers; Apply writes one value into the existing spinner.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Percentile cofactor math, global aggregation, dead/saturated detection | Model (pure-array `CofactorEstimator`) | — | D-04: JavaFX-free, deterministic, unit-testable on synthetic clouds. Owns all numeric logic. |
| Per-cell raw measurement read (open image) | Model (`CellFeatureExtractor.extractRowRaw`) | — | In-memory `PathObject.getMeasurementList()` only (COF-03). |
| Whole-project all-cells pooling (memory-safe) | Model (`CohortClusterModel.poolAllCells` + raw variant) | — | Two-pass, no sampling, `CancellationToken` (D-13/COF-08). |
| Calibration feature selection | UI (`FeatureSelectionPane`) | Model (`IntensityHeatmap.discoverMarkerFeatures` for the default set) | Independent picker; default pre-selection derived, not hardcoded (COF-02/D-12). |
| Tool window / progress / cancel / off-FX compute | UI (new suggest window) | — | Non-modal owned window; worker thread; `Platform.runLater` (D-05/D-07). |
| Results table (per-feature diagnostics) | UI (new; mirror `ClusterAssignmentPane`) | — | Display-only; advisory per-feature values (D-09/COF-05). |
| Launch host + apply target (cofactor spinner) | UI (`NormalizationPane`) | — | Suggest button + `spinner.getValueFactory().setValue(...)` write-back (COF-01/COF-07). |
| arcsinh transform (unchanged) | Model (`FeatureNormalizer`) | — | Reused as-is; the tool never changes the formula/contract. |

## Standard Stack

This phase introduces **no new dependencies**. Everything is JDK-25 stdlib + existing project classes + JavaFX (already present). This is a constraint from SPEC ("add no new heavy dependencies").

### Core (existing classes reused)
| Class | Path | Purpose | Verified |
|-------|------|---------|----------|
| `ImagePixelStats.percentileSorted` | `model/ImagePixelStats.java:429` | The estimator statistic + table's p99. `static double percentileSorted(double[] sorted, double percentile)`; **p in [0,100]**; linear interpolation; requires **ascending-sorted** input; returns `NaN` on empty, `sorted[0]` on n==1. Does **not** filter NaN — caller pre-filters. | VERIFIED (read source) |
| `RobustStats.median` / `.medianIgnoreNaN` | `util/RobustStats.java:37,54` | Global aggregation = median of per-feature cofactors; `medianIgnoreNaN` for degenerate safety. `MAD_TO_SIGMA = 0.6745` (line 27). | VERIFIED — **PATH CORRECTION: `util/`, not `model/`** |
| `CellFeatureExtractor` | `model/CellFeatureExtractor.java` | Open-image per-cell reads. `extractRowRaw(PathObject)` (line 112) = raw values, no normalizer, NaN→0f. `extractMatrix(cells)` (line 131) row-major flat `float[]`, applies normalizer if set, NaN→0f. Use `extractRowRaw` / a null-normalizer extractor for the estimator. | VERIFIED |
| `CohortClusterModel.poolAllCells` | `model/CohortClusterModel.java:849` | Whole-project all-cells two-pass pooling. **Returns z-scored rows + mean/sd** — see the raw-recovery note. `CancellationToken` at line 735. | VERIFIED |
| `FeatureSelectionPane` | `ui/FeatureSelectionPane.java` | Calibration picker. Constructor `(Stage owner, List<String> featureNames, List<String> preSelected)` line 76; **`preSelected` null/empty → all selected**, else pre-selects those. `showAndWait()` returns `List<String>` (line 179). `setTitle(String)` line 170. Groups: Markers / Morphology / Neighbors / Embeddings / Other (`groupOf`, line 225). | VERIFIED |
| `IntensityHeatmap.discoverMarkerFeatures` | `model/IntensityHeatmap.java:68` | Default pre-selection for D-12 **without hardcoding**: returns all `"<marker>: Cell: Mean"` names (excludes derived/neighbor means). `markerLabel(name)` strips the `": Cell: Mean"` suffix. | VERIFIED |
| `FeatureNormalizer` | `model/FeatureNormalizer.java` | Reused **unchanged**. `getArcsinhCofactor()` (99), `setArcsinhCofactor(double)` (104) — **throws if ≤ 0**. arcsinh at line 133: `ln(x/c + sqrt(x²/c² + 1))`. Default 1.0. | VERIFIED |
| `NormalizationPane` | `ui/NormalizationPane.java` | Launch host + apply target. Cofactor spinner `new Spinner<>(0.01, 10000.0, …, 1.0)` line 77; arcsinh-only visibility `updateCofactorVisibility()` line 205. **`stage.initModality(Modality.APPLICATION_MODAL)` line 169** — see Modality pitfall. `stage` field is `private` with no accessor — plan must add one. | VERIFIED |
| `PixelCohortAnalyzer` | `model/PixelCohortAnalyzer.java` | Dead/saturated exclusion pattern to mirror (D-11). `SIGNAL_FOREGROUND_FLOOR = 0.05` (line 44) gates signal-bearing channels; near-dead channels (p99 at noise floor / near-zero variance) excluded from z. Flag codes: `BACKGROUND_HEAVY`, `SATURATED`, `WEAK_SIGNAL`, `INTENSITY_OUTLIER`. | VERIFIED |

### Supporting (UI patterns to mirror)
| Class | Path | Purpose | When to Use |
|-------|------|---------|-------------|
| `NeighborhoodAnalysisDialog` | `ui/NeighborhoodAnalysisDialog.java` | Non-modal window + `ProgressBar` + Cancel + worker `Thread` + `Platform.runLater`. `s.initOwner(qupath.getStage())` + `s.initModality(Modality.NONE)` (lines 418-419); worker pattern lines 700-793; `progressBar.setProgress(frac)` via `Platform.runLater`. | The suggest window's structure. |
| `ClusterAssignmentPane` | `ui/ClusterAssignmentPane.java` | Per-row results grid (`GridPane`, lines 82-127). | The per-feature table; `TableView` recommended for N sortable rows. |
| `ScatterPlotView` | `ui/ScatterPlotView.java` | Soft-ceiling confirm + all-cells UX. `ALL_CELLS_SOFT_CEILING = PathPrefs.createPersistentPreference("celltune.allCellsSoftCeiling", 50_000_000)` (line 130); `Dialogs.showConfirmDialog` before an all-cells run. | Mirror the D-08 soft-ceiling confirm; may reuse the same pref or add a cofactor-specific one. |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| p50 (median) as the percentile | p40 or p60 | p40 (~34 raw / 0.040 MIBI) and p60 (~49 raw / 0.071 MIBI) also land in-range, but p50 is the most standard/robust, recovers a known background tightest (ratio 1.0–1.8 up to 40% positive), and stays conservative as positive fraction rises. p60+ drifts into log-compression sooner. |
| Add `poolAllCellsRaw` sibling | Recover raw via `z*sd + mean` | Recovery is lossless (verified: even zero-variance columns recover correctly since `z=0 ⇒ raw=mean=constant`) but indirect and couples the estimator to z-score internals. A raw sibling reusing the identical two-pass loop is clearer and testable. |
| `GridPane` results table | `TableView` | `GridPane` matches the house `ClusterAssignmentPane` pattern; `TableView` gives sortable N-row semantics closer to the SPEC "N rows" wording. Either is acceptable — planner's discretion. |
| GMM/Otsu split for background | Percentile (D-01, locked) | GMM/Otsu is more principled for bimodal data but non-deterministic/costly and fragile for rare-positive markers; SPEC locked the percentile family. |

**Installation:** No new packages. Build unchanged: `./gradlew test`, `./gradlew shadowJar`.

**Version verification:** N/A — no external dependency added. Existing deps (`build.gradle.kts`) unchanged.

## The D-03 Calibration — `BACKGROUND_PERCENTILE = 50.0`

### What the cofactor tracks (the estimand)
`arcsinh(x/c) = ln(x/c + sqrt((x/c)² + 1))`. For `x << c` this is ≈ `x/c` (near-linear, "no-op" regime); for `x >> c` it is ≈ `ln(2x/c)` (log-compressed). So the cofactor `c` is the intensity scale at which a per-cell value transitions from linear to compressed. To "collapse the background/negative population while preserving the positive population," `c` must sit at the **background/signal knee** — i.e. it tracks the **background/negative intensity LEVEL** (the low end of the per-cell distribution), NOT a high signal percentile. USER_GUIDE §4.2 confirms: "pick the value near the background/signal boundary… if almost every value is far above it, everything is log-compressed and low-end detail is lost" (this is why cofactor 150 on raw fluorescence is a near-no-op — the TRMscreen COMET assessment found ~74% of cells then sit in the linear regime).

For a per-cell marker distribution where the **majority of cells are negative/background** (the common case in a multiplex panel — most markers are expressed by a minority of cell types), the median of that distribution sits inside the background population and therefore reports the background level. `arcsinh(x/median)` then puts the bulk of background cells at/below the linear-to-compressed knee and lifts the positive population into the compressed regime — exactly the desired behavior. `[VERIFIED: analytical + simulation]`

### Calibration result (single constant hits both targets)
Simulated per-cell mixtures (lognormal background + separated lognormal positive population, per-marker positive fraction 5–45%), computing per-feature `percentileSorted(sorted, p)` then the panel GLOBAL = median across markers. Seed-fixed, pure-stdlib (no numpy), mirroring the deterministic test convention.

| percentile `p` | RAW-FLUOR global (bg≈30, pos≈800) — target tens ~25–50 | MIBI global (bg≈0.04, pos≈1.5) — target ≈0.05 |
|---:|---:|---:|
| 40 | 34.0 | 0.040 |
| **50** | **37.7 – 47** | **0.053** |
| 60 | 49.3 | 0.071 |
| 70 | 63.8 | 0.121 |
| 75 | 258.6 (log-compression regime) | 0.122 |
| 80 | 341.5 | 0.783 |

**`p = 50.0` hits both SPEC targets with the SAME constant** — raw-fluorescence lands ~35–47 (in the tens ~25–50 ✓, well below the ~150 no-op level) and MIBI lands ≈0.053 (≈0.05 ✓, Hartmann et al. 2021). The two outcomes differ purely because the intensity scales differ, exactly as the objective requires. Above ~p70–75 the percentile climbs into the positive population and the estimate explodes into the hundreds/thousands (the log-compression/no-op failure mode the USER_GUIDE warns against). `[VERIFIED: simulation, this session]`

### Known-background recovery (SPEC COF-04 tolerance)
Single-marker `p50` recovery of the injected background median (ratio = recovered/injected; factor-of-2 = ratio in [0.5, 2.0]):

| positive fraction | p50 / injected-bg ratio (raw-fluor & MIBI, identical by scale-invariance) | within factor-2? |
|---:|---:|:--:|
| 0.00 | 1.00 | ✓ |
| 0.10 | 1.09 | ✓ |
| 0.25 | 1.30 | ✓ |
| 0.40 | 1.78 | ✓ |
| 0.50 | 4.6 | ✗ (residual gap — see Open Questions) |

The estimator recovers the injected background within a factor of ~2 for markers whose positive fraction is **below ~50%** — which covers the overwhelming majority of real panel markers. Markers positive in ≥~50% of cells (where "background" is genuinely ill-defined) are the residual gap, mitigated by D-11 exclusion. `[VERIFIED: simulation, this session]`

### Recommendation
```java
// CofactorEstimator (new pure-array model class)
public static final double BACKGROUND_PERCENTILE = 50.0;  // p50 / median of the per-cell distribution
```
Justification: median is the most robust, standard, deterministic percentile; it sits in the background population whenever <50% of cells are positive; it hits both the raw-fluorescence (tens) and MIBI (~0.05) targets with one fixed value; it recovers a known injected background within the SPEC factor-of-2 tolerance up to 40% positive; and it is trivially unit-testable. Clamp the final global to [0.01, 10000] before write-back (spinner range; `setArcsinhCofactor` throws on ≤0).

## Architecture Patterns

### System Architecture Diagram
```
NormalizationPane (APPLICATION_MODAL dialog, arcsinh selected)
   │  user clicks "Suggest…" (visible only for arcsinh)
   ▼
CofactorSuggestWindow  ── OWNED BY NormalizationPane.stage ──►  (must not be a bare Modality.NONE
   │   non-modal-to-QuPath but child-of-the-modal-pane so it is interactive)
   │
   ├─► "Select calibration features…" ─► FeatureSelectionPane(owner, allFeatureNames,
   │        preSelected = IntensityHeatmap.discoverMarkerFeatures(allFeatureNames))
   │        .showAndWait() → List<String> selectedFeatures     [independent of normalize set]
   │
   ├─► Scope radio:  ( ) Open image     (•) Whole project (all cells)   [default whole-project]
   │
   └─► [Run] ─► worker Thread (off FX):
              scope == OPEN IMAGE:
                 cells = hier.getCellObjects() ?: hier.getDetectionObjects()
                 per feature → extractRowRaw values → double[]
              scope == WHOLE PROJECT:
                 (soft-ceiling confirm if est. pooled > 50M — D-08)
                 poolAllCellsRaw(project, images, selectedFeatures, token, log::)   ← raw variant
                 → per-feature raw column arrays (memory-safe two-pass, all cells, no sampling)
                        │
                        ▼
              CofactorEstimator.estimate(featureName, double[] rawValues):
                 sort → n, background=p50, median=p50, p99=percentileSorted(...,99)
                 cofactor = clamp(p50, 0.01, 10000)
                 deadOrSaturated? (near-zero variance / p99 at floor / saturation) → flag+exclude
                        │
                        ▼
              Platform.runLater:
                 results table  (feature | n cells | background | median | p99 | cofactor | flag)
                 GLOBAL = RobustStats.median(cofactors of NON-excluded features)   ← shown prominently
                        │
                        ▼
   [Apply] ─► NormalizationPane.cofactorSpinner.getValueFactory().setValue(clamp(GLOBAL))
             (no mutation, no normalization run — user still confirms normalize as today)
```
Data flow: measurements never leave memory (COF-03); the only write is the single spinner value on Apply (COF-07).

### Recommended Project Structure
```
model/
├── CofactorEstimator.java          # NEW — pure-array; BACKGROUND_PERCENTILE=50.0; estimate() + aggregation + dead/saturated
└── CohortClusterModel.java         # ADD poolAllCellsRaw(...) sibling (skip final standardizeColumns) OR reuse mean/sd recovery
ui/
├── CofactorSuggestWindow.java      # NEW — non-modal OWNED window; picker + scope + Run/Cancel + table + Apply
└── NormalizationPane.java          # ADD "Suggest…" button (arcsinh-only) + expose stage/spinner write-back accessor
test/…/model/
└── CofactorEstimatorTest.java      # NEW — synthetic recovery, aggregation, dead/saturated exclusion, raw-fluor range
```

### Pattern 1: Pure-array estimator (D-04, mirrors LeidenModel/NeighborhoodModel)
**What:** A JavaFX-free, static-method model class operating on `double[]` per-feature arrays.
**When:** All numeric cofactor logic.
```java
// CofactorEstimator — no QuPath/JavaFX imports; deterministic
public static final double BACKGROUND_PERCENTILE = 50.0;

public static FeatureResult estimate(String feature, double[] rawValues) {
    double[] finite = dropNaN(rawValues);            // percentileSorted does NOT filter NaN
    Arrays.sort(finite);                              // percentileSorted requires ascending input
    int n = finite.length;
    double background = ImagePixelStats.percentileSorted(finite, BACKGROUND_PERCENTILE);
    double median     = background;                   // same p50 here; table column reuses it
    double p99        = ImagePixelStats.percentileSorted(finite, 99.0);
    double cofactor   = clamp(background, 0.01, 10000.0);
    boolean dead      = n == 0 || (p99 - finite[0]) < EPS;   // near-zero variance / all-background
    boolean saturated = /* mirror PixelCohortAnalyzer saturation gate */;
    return new FeatureResult(feature, n, background, median, p99, cofactor, dead, saturated);
}

public static double globalCofactor(List<FeatureResult> rs) {
    double[] keep = rs.stream().filter(r -> !r.dead() && !r.saturated())
                       .mapToDouble(FeatureResult::cofactor).toArray();     // D-11 exclusion
    return clamp(RobustStats.median(keep), 0.01, 10000.0);                  // D-10/COF-06
}
```

### Pattern 2: Raw whole-project pooling (COF-08/D-13) — the poolAllCells adaptation
**What:** `poolAllCells` reuses the exact memory-safe two-pass loop but z-scores at the end and returns z-scored rows + `mean`/`sd`. The estimator needs raw values.
**Recommended:** Add `poolAllCellsRaw(...)` that is byte-identical to `poolAllCells` (lines 878-942 loop: per-image `readImageData()` for disk images / live `openData` for the open image, `extractor.extractMatrix`, `CancellationToken` checked per image, hierarchy released per iteration) but **returns `rawRows` directly and skips `ScatterMath.standardizeColumns`**. Column-major regrouping per feature happens in the caller/estimator.
**Alternative (no new method):** call `poolAllCells` with a **null normalizer**, then recover raw per marker: `raw[i][j] = z[i][j] * sd[j] + mean[j]` (lossless; verified correct even for zero-variance columns). Prefer the raw sibling for clarity + testability.
**Anti-pattern:** Do NOT feed z-scored values into the percentile estimator — z-scores are centered at 0 with unit sd, so `p50 ≈ 0` and the recovered "cofactor" would be meaningless/non-positive (would throw in `setArcsinhCofactor`).

### Pattern 3: Non-modal OWNED tool window under a modal parent (D-05 + modality fix)
**What:** `NeighborhoodAnalysisDialog` uses `initOwner(qupath.getStage()) + Modality.NONE`. That pattern works when the launcher is itself non-modal. **`NormalizationPane` is `APPLICATION_MODAL`**, so a window merely owned by `qupath.getStage()` with `Modality.NONE` would be blocked by the modal pane.
**Fix:** Own the suggest window by the **`NormalizationPane` stage**, not `qupath.getStage()`:
```java
suggestStage.initOwner(normalizationPane.getStage());   // child of the modal pane → interactive
suggestStage.initModality(Modality.NONE);               // stays non-blocking within that owner subtree
```
A child window of an `APPLICATION_MODAL` owner is not itself blocked by that owner's modality; the modal block applies to windows OUTSIDE the owner chain. `NormalizationPane` must expose `public Stage getStage()` (the `stage` field is currently `private` with no accessor). `Modality.WINDOW_MODAL` owned by the pane is a stricter alternative (blocks only the pane while the suggest window is open) but D-05 wants the pane to stay usable, so prefer `Modality.NONE` + pane ownership.

### Pattern 4: Off-FX worker + progress + cancel (D-05/D-07/D-08)
Mirror `NeighborhoodAnalysisDialog` lines 700-793: disable Run, `progressBar.setProgress(INDETERMINATE)`, spawn a named `Thread`, do all pooling/estimation off-FX, push progress via `frac -> Platform.runLater(() -> progressBar.setProgress(frac))`, publish results and re-enable Run in a `finally` `Platform.runLater`. Whole-project scope: soft-ceiling `Dialogs.showConfirmDialog` before the run (reuse `celltune.allCellsSoftCeiling` = 50M) and thread a `CohortClusterModel.CancellationToken` (cancel checked per image, lines 878-882).

### Anti-Patterns to Avoid
- **Estimating on z-scored values.** `poolAllCells` z-scores — p50 of z-scores ≈ 0 ⇒ invalid non-positive cofactor. Use raw.
- **Hardcoding a measurement name** for the default selection. Compute it via `IntensityHeatmap.discoverMarkerFeatures(featureNames)` (COF-02 acceptance).
- **`Modality.NONE` owned by `qupath.getStage()`** under the modal `NormalizationPane` — window is unclickable. Own it by the pane.
- **Bounded sampling for whole-project scope.** SPEC explicitly rejects it (rare markers). Use all-cells `poolAllCells`.
- **Applying per-feature cofactors** or running normalization on Apply. Apply sets the spinner only (COF-07).
- **Mutating measurements / persisting anything.** The tool is transient advisory output.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Interpolated percentile | Custom percentile with off-by-one rank math | `ImagePixelStats.percentileSorted(sorted, p)` | Already handles n==1, [0,100] clamp, linear interp, empty→NaN. |
| Median aggregation | Ad-hoc sort+midpoint | `RobustStats.median` / `medianIgnoreNaN` | Even/odd handling + NaN-aware variant already tested. |
| Grouped/searchable feature picker | New checkbox tree | `FeatureSelectionPane` | Grouping (markers/morphology/neighbors/embeddings/other), search, pre-select, independent selection — all done. |
| Default "which features are markers" | String parsing of measurement names | `IntensityHeatmap.discoverMarkerFeatures` | Correctly picks `"<marker>: Cell: Mean"`, excludes derived/neighbor means; no hardcoding. |
| Memory-safe all-cells pooling | New project streamer | `CohortClusterModel.poolAllCells` loop (raw variant) | Two-pass, live-open-image handling, per-image release, cancellation — battle-tested at 30M+ cells. |
| Non-modal window + progress + cancel | New dialog scaffolding | `NeighborhoodAnalysisDialog` pattern | Owner/modality/worker/`Platform.runLater`/progress already correct. |
| Dead/saturated channel detection | New heuristics | `PixelCohortAnalyzer` gate ideas (`SIGNAL_FOREGROUND_FLOOR`, near-zero-variance, saturation) | Consistent with the rest of the extension; avoids MAD-blowup on dead channels. |
| Soft-ceiling confirm | New pref + dialog | `celltune.allCellsSoftCeiling` pref + `Dialogs.showConfirmDialog` | Phase 15 UX already persisted and consistent. |

**Key insight:** The only genuinely new code is `CofactorEstimator` (small, pure) + a thin `poolAllCellsRaw` sibling + one UI window that composes existing panes/patterns + a button and accessor on `NormalizationPane`. Almost nothing here is novel math or novel UI.

## Runtime State Inventory

Not applicable — this is a greenfield feature phase (new tool), not a rename/refactor/migration. No stored data keys, service configs, OS registrations, secrets, or build artifacts are renamed. The only persisted artifact touched is an optional reuse of the existing `celltune.allCellsSoftCeiling` preference (read-only reuse; no schema change). **None — verified: the phase adds a transient advisory tool and writes exactly one spinner value on Apply; it persists nothing new.**

## Common Pitfalls

### Pitfall 1: Estimating on z-scored pooled data
**What goes wrong:** `poolAllCells` returns z-scored rows; feeding those to the percentile estimator yields `p50 ≈ 0` and a non-positive "cofactor" that throws in `setArcsinhCofactor` (≤0 guard).
**Why:** The all-cells driver z-scores for clustering; the cofactor tracks the RAW background level.
**How to avoid:** Use raw values — `extractRowRaw` (open image) and a `poolAllCellsRaw` sibling (or `z*sd+mean` recovery) for whole-project.
**Warning sign:** Recommended global near 0 / spinner rejects the value.

### Pitfall 2: Modality deadlock — non-modal window under the modal Normalise pane
**What goes wrong:** `NormalizationPane` is `APPLICATION_MODAL`; a suggest window with `initOwner(qupath.getStage())` + `Modality.NONE` opens but is unclickable (the modal pane blocks all windows outside its owner chain).
**Why:** Application-modal blocks every window that is not a descendant of the modal window's owner subtree.
**How to avoid:** `suggestStage.initOwner(normalizationPane.getStage())`. Add a `getStage()` accessor to `NormalizationPane` (currently private).
**Warning sign:** The suggest window shows but ignores clicks; QuPath and the pane are frozen relative to it.

### Pitfall 3: NaN / missing measurements pull the background down
**What goes wrong:** `extractRowRaw`/`extractMatrix` map missing/NaN measurements to `0f`, so cells lacking a marker land at 0 in that feature's distribution, biasing p50 toward 0 (a spuriously small cofactor) if many cells miss the measurement.
**Why:** The extractor's documented NaN→0 policy is fine for ML but conflates "missing" with "true zero background" here.
**How to avoid:** For the estimator, prefer reading raw values and **dropping true NaN before sorting** (percentileSorted does not filter NaN); decide explicitly whether a legitimately-measured 0 counts as background (it does) vs a missing measurement (drop). Document the choice; the D-11 dead-channel flag catches the degenerate all-zero case.
**Warning sign:** A marker present on only some images shows an implausibly tiny cofactor.

### Pitfall 4: Marker positive in ≥~50% of cells (residual calibration gap)
**What goes wrong:** For a marker expressed by the majority of cells, p50 sits in the POSITIVE population and overestimates the background (ratio ~4.6× at 50% positive), inflating that feature's cofactor.
**Why:** The median only tracks background when background is the majority.
**How to avoid:** The D-11 exclusion should also drop such markers from the global median where detectable (e.g. very high foreground coverage), and the per-feature value stays advisory. This is the documented residual gap (Open Questions).
**Warning sign:** One marker's suggested cofactor is orders of magnitude above the panel median.

### Pitfall 5: Wrong RobustStats import path
**What goes wrong:** CONTEXT/SPEC cite `model/RobustStats.java`; the class is actually `qupath.ext.celltune.util.RobustStats`. Importing `...model.RobustStats` fails to compile.
**How to avoid:** Import `qupath.ext.celltune.util.RobustStats`. Flagged prominently for the planner.

## Code Examples

### Percentile estimator (verified API shape)
```java
// Source: model/ImagePixelStats.java:429 (percentileSorted), util/RobustStats.java:37 (median)
double[] finite = /* raw values, NaN dropped */;
Arrays.sort(finite);                                   // MUST be ascending
double background = ImagePixelStats.percentileSorted(finite, 50.0);   // p in [0,100]
double p99        = ImagePixelStats.percentileSorted(finite, 99.0);
double global     = RobustStats.median(perFeatureCofactors);          // over kept features
```

### Default calibration selection (no hardcoded name — COF-02/D-12)
```java
// Source: model/IntensityHeatmap.java:68
List<String> defaults = IntensityHeatmap.discoverMarkerFeatures(allFeatureNames); // "<marker>: Cell: Mean"
var picker = new FeatureSelectionPane(ownerStage, allFeatureNames, defaults);
picker.setTitle("Calibration features");
List<String> selected = picker.showAndWait();          // null if cancelled
```

### Apply into the spinner (COF-07)
```java
// Source: ui/NormalizationPane.java:77 (spinner). Add accessor: public Spinner<Double> getCofactorSpinner()
double v = Math.max(0.01, Math.min(10000.0, globalCofactor));
normalizationPane.getCofactorSpinner().getValueFactory().setValue(v);   // no mutation, no normalize run
```

### Non-modal owned window (modality fix — D-05)
```java
// Source: ui/NeighborhoodAnalysisDialog.java:416-419 pattern, adapted for the modal parent
Stage s = new Stage();
s.initOwner(normalizationPane.getStage());   // NOT qupath.getStage() — parent is APPLICATION_MODAL
s.initModality(Modality.NONE);
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual cofactor guess (spinner only, default 1) | Automated background-percentile suggestion (this phase) | Phase 17 | Removes the guess; raw-fluorescence users get ~35–47 instead of the no-op 1. |
| Fixed community cofactors (0.05 MIBI; ~25–50 fluor prose) | Data-driven p50 of the actual per-cell distribution | Phase 17 | Same ballpark as community values but derived from the user's data, per panel scale. |

**Deprecated/outdated:** None. The `FeatureNormalizer` arcsinh contract is stable and reused unchanged.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Real panel markers are positive in <50% of cells (so p50 sits in background) | Calibration / Pitfall 4 | For majority-positive markers the per-feature cofactor over-estimates; mitigated by D-11 exclusion + advisory-only per-feature values. Global median is robust to a few such markers. |
| A2 | The "background estimate" table column and the cofactor may both be p50 (same statistic) | D-09 table | If the user expects a distinct background statistic (e.g. p10) vs the cofactor, the table is redundant. Discretion per D-09; low risk (advisory display). |
| A3 | Reusing `celltune.allCellsSoftCeiling` (50M) for the cofactor whole-project confirm is acceptable | D-08 | If a separate ceiling is wanted, add a new pref; trivial. |
| A4 | A `Modality.NONE` window owned by an `APPLICATION_MODAL` stage is interactive (JavaFX owner-subtree exemption) | Pattern 3 / Pitfall 2 | If the JavaFX version blocks even owned children, fall back to `WINDOW_MODAL` owned by the pane (blocks only the pane). **[ASSUMED — standard JavaFX modality semantics; confirm empirically in a manual UI check.]** |

## Open Questions

1. **Markers positive in ≥~50% of cells (residual calibration gap).**
   - What we know: p50 recovers a known background within factor-2 up to ~40% positive; at 50% it drifts to ~4.6×.
   - What's unclear: whether any selected calibration marker in a real panel is majority-positive, and whether D-11's exclusion catches it (D-11 targets dead/saturated, not majority-positive).
   - Recommendation: rely on the global-median's robustness to a minority of inflated features; keep per-feature values advisory; optionally extend the D-11 flag to "high foreground coverage" markers. Do NOT change the locked p50 for this — the SPEC tolerance is met for the normal case.

2. **Exact dead/saturated criteria for D-11 in the per-cell (not per-pixel) setting.**
   - What we know: `PixelCohortAnalyzer` gates on `SIGNAL_FOREGROUND_FLOOR = 0.05` and near-zero p99/variance — but that's per-pixel image stats, not per-cell measurement distributions.
   - What's unclear: the numeric thresholds for "dead" (near-zero variance across cells) and "saturated" (p99 at the panel's max) when operating on per-cell means.
   - Recommendation: dead = `(p99 - min) < EPS` or near-zero MAD; saturated = optional (per-cell means rarely saturate; can flag if p99 equals the observed global max within EPS). Planner to pick thresholds; unit-test both.

3. **Whether the "background estimate" column should differ from the cofactor percentile.**
   - What we know: D-09 lists `background estimate` and `suggested cofactor` as separate columns; Claude's Discretion allows them to differ.
   - Recommendation: show background = p50 (the cofactor source) plus median and p99 as the scale summary; keeps the table honest and simple. Planner's call.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 25 (with `javac`) | Build/compile | ✓ (per CLAUDE.md; project requires it) | 25.x | none — build fails without it (foojay `IBM_SEMERU` trap; set `-Dorg.gradle.java.installations.auto-download=false` + `installations.paths=$JAVA_HOME`) |
| Gradle wrapper | Build/test | ✓ (bundled) | 9.2.1 (pinned) | — |
| QuPath 0.7.0 API | Compile/runtime | ✓ (pinned in settings.gradle.kts) | 0.7.0 | — |
| JavaFX | UI | ✓ (via QuPath) | bundled | — |
| No new libs | This phase | ✓ | — | — |

**Missing dependencies with no fallback:** None — this phase adds no dependencies. (Note: the researcher's calibration used pure-Python stdlib because `numpy` was unavailable in the research sandbox; this does not affect the Java build, which has no Python dependency.)

**Missing dependencies with fallback:** None.

## Validation Architecture

This project has Nyquist validation enabled (`workflow.nyquist_validation: true` in `.planning/config.json`).

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (Jupiter), `org.junit.jupiter.api.Test` |
| Config file | `build.gradle.kts` (`test` task) |
| Test location | `src/test/java/qupath/ext/celltune/model/`, mirroring main; pure-array convention (no QuPath/JavaFX imports) — see `NeighborhoodModelTest`, `LeidenModelTest` |
| Quick run command | `./gradlew test --tests "*CofactorEstimator*"` |
| Full suite command | `./gradlew test` |
| Determinism convention | `new Random(42)` (or fixed seed); synthetic blobs/clouds; static assertions with an `EPS` tolerance |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| COF-04 | Known-background recovery within factor-2, deterministic under fixed seed | unit | `./gradlew test --tests "*CofactorEstimator*recoversKnownBackground*"` | ❌ Wave 0 |
| COF-04 | `BACKGROUND_PERCENTILE == 50.0` produces expected p50 on a hand-built array | unit | `./gradlew test --tests "*CofactorEstimator*percentile*"` | ❌ Wave 0 |
| COF-06 | Global = median of per-feature cofactors (known set → known median) | unit | `./gradlew test --tests "*CofactorEstimator*globalIsMedian*"` | ❌ Wave 0 |
| D-11 | Dead / saturated markers flagged AND excluded from the global median | unit | `./gradlew test --tests "*CofactorEstimator*excludesDeadSaturated*"` | ❌ Wave 0 |
| Acceptance | Raw-fluorescence-range sanity: synthetic raw-fluor panel → global in the tens (~25–50) | unit | `./gradlew test --tests "*CofactorEstimator*rawFluorescenceRange*"` | ❌ Wave 0 |
| Acceptance | MIBI-scale sanity: synthetic MIBI panel → global ≈ 0.05 (order-of-magnitude) | unit | `./gradlew test --tests "*CofactorEstimator*mibiRange*"` | ❌ Wave 0 |
| COF-06 | NaN / degenerate robustness (empty feature, all-NaN, single value) | unit | `./gradlew test --tests "*CofactorEstimator*degenerate*"` | ❌ Wave 0 |
| COF-08 | `poolAllCellsRaw` returns raw (not z-scored) rows; identity/count vs `poolAllCells` | unit | `./gradlew test --tests "*CohortClusterModel*Raw*"` | ❌ Wave 0 (extend `CohortClusterModelTest`) |
| COF-01 | "Suggest…" button present+enabled for arcsinh, absent/disabled for sqrt | **manual-only** | UI launch in QuPath (JavaFX stage; no headless harness in this project) | — |
| COF-02 | Picker independent of normalize set; grouped+searchable; default = marker means | **manual-only** (+ unit for `discoverMarkerFeatures` already covered by `IntensityHeatmapTest`) | UI launch | — |
| COF-03 | In-memory only (no geojson/CSV) | code-review + manual | inspection: only `getMeasurementList()`/`ImageData` reads; no IO-parser calls | — |
| COF-05 | Table shows N rows with value-scale summary + per-feature cofactor | **manual-only** | UI launch | — |
| COF-07 | Apply sets the spinner in one action; no mutation/normalize | **manual-only** | UI launch; verify spinner value, no measurement change | — |
| COF-08 | Scope selector; whole-project pools all cells (rare markers present); open-image uses open cells | **manual-only** (pooling math is unit-tested; wiring is manual) | UI launch on a real project | — |

Automated coverage: the entire estimator (COF-04, COF-06, D-11, both range sanity checks, degenerate handling) and the raw-pooling primitive (COF-08 math). Manual-only: the JavaFX UI surface (COF-01/02/05/07 wiring, COF-08 scope wiring) — consistent with the project's existing UI tests being limited to non-JavaFX logic (`FeatureSelectionPaneTest` covers grouping logic, not stage rendering).

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "*CofactorEstimator*"` (fast; pure-array).
- **Per wave merge:** `./gradlew test` (full suite) + `./gradlew spotlessCheck` (blocking format gate).
- **Phase gate:** Full suite green + manual UI checklist (COF-01/02/05/07/08 wiring) before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] `src/test/java/qupath/ext/celltune/model/CofactorEstimatorTest.java` — covers COF-04, COF-06, D-11, raw-fluor + MIBI range, degenerate handling. Follow `NeighborhoodModelTest`'s synthetic-cloud + `new Random(seed)` convention; NO QuPath/JavaFX imports.
- [ ] Extend `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` — add `poolAllCellsRaw` raw-vs-zscored assertion (or the `z*sd+mean` recovery equivalence test).
- [ ] Framework install: none — JUnit 5 already wired.

## Security Domain

`security_enforcement` is not set in `.planning/config.json` (absent). This is a local desktop scientific-imaging extension with no network, auth, session, or external-input surface introduced by this phase (in-memory measurement reads only, COF-03).

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | No auth surface |
| V3 Session Management | no | No sessions |
| V4 Access Control | no | Local single-user desktop |
| V5 Input Validation | yes (light) | Clamp the recommended cofactor to [0.01, 10000] before `setArcsinhCofactor` (throws on ≤0); drop NaN before percentile; guard empty feature arrays |
| V6 Cryptography | no | None |

### Known Threat Patterns for this stack
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Out-of-range / non-positive cofactor into `setArcsinhCofactor` | Denial of Service (exception on Apply) | Clamp to spinner range [0.01, 10000] before write-back |
| Unbounded memory on whole-project pooling | Denial of Service (OOM) | Reuse `poolAllCells` two-pass memory-safe pattern + 50M soft-ceiling confirm (D-08); never sample/hold redundant copies |
| Background-thread mutation of QuPath objects off FX thread | Tampering / crash | Compute off-FX; the tool writes NOTHING to hierarchy — only the spinner value via `Platform.runLater` on Apply |

## Sources

### Primary (HIGH confidence — read in this session)
- `model/ImagePixelStats.java:429` — `percentileSorted` signature, [0,100] range, interpolation, NaN behavior.
- `util/RobustStats.java:27,37,54` — `MAD_TO_SIGMA=0.6745`, `median`, `medianIgnoreNaN`. **PATH: `util/`, not `model/`.**
- `model/CohortClusterModel.java:735,767,849,878-959,1575` — `CancellationToken`, `PooledData` (z-scored!), `poolAllCells`, the two-pass loop, `detections`.
- `model/CellFeatureExtractor.java:93,112,131` — `extractRow`/`extractRowRaw`/`extractMatrix`, NaN→0f policy.
- `model/FeatureNormalizer.java:99,104,126-138` — cofactor getter/setter (throws ≤0), arcsinh formula, reused unchanged.
- `model/IntensityHeatmap.java:33,68,92` — `MARKER_MEAN_SUFFIX`, `discoverMarkerFeatures`, `markerLabel`.
- `model/PixelCohortAnalyzer.java:44,175-217` — `SIGNAL_FOREGROUND_FLOOR`, dead-channel exclusion / flag codes (D-11 pattern).
- `ui/NormalizationPane.java:54,77,168-169,205-212` — spinner range, `APPLICATION_MODAL`, arcsinh-only visibility, private stage.
- `ui/FeatureSelectionPane.java:76,170,179,225` — constructor/pre-select, `setTitle`, `showAndWait→List<String>`, `groupOf`.
- `ui/NeighborhoodAnalysisDialog.java:416-419,700-793` — non-modal owned window + worker + progress + cancel pattern.
- `ui/ClusterAssignmentPane.java:82-127` — per-row grid pattern.
- `ui/ScatterPlotView.java:130,1444` — 50M soft-ceiling pref + confirm dialog.
- `src/test/java/qupath/ext/celltune/model/NeighborhoodModelTest.java` — synthetic-cloud, fixed-seed, EPS-tolerance test convention.
- `CellTuneExtension.java:1698` — `new NormalizationPane(qupath.getStage(), …)` (owner is the QuPath main stage).
- `USER_GUIDE.md §4.2` — cofactor heuristic + raw-fluor (~25–50) / MIBI (0.05) acceptance targets.
- `README.md §References` — Hartmann et al. 2021 (MIBI 0.05); Schapiro `norm_methods` catalog.
- D-03 calibration simulation (this session, `/scratchpad/calib2.py`, `calib3.py`) — p50 hits both targets; factor-2 recovery to 40% positive.

### Secondary (MEDIUM confidence)
- Schapiro Lab `norm_methods` (https://github.com/SchapiroLabor/norm_methods) — "background identification" family grounds the estimand (per SPEC/README; not re-fetched this session, cited from README).

### Tertiary (LOW confidence)
- JavaFX owner-subtree modality exemption (A4) — standard JavaFX semantics from training; confirm with the manual UI check.

## Metadata

**Confidence breakdown:**
- Standard stack / reuse points: HIGH — every class, signature, and cited line verified against source this session.
- D-03 percentile constant (50.0): HIGH — analytical estimand reasoning + seed-fixed simulation confirming both targets and factor-2 recovery.
- Architecture patterns: HIGH — mirror existing, verified panes.
- Modality fix (A4): MEDIUM — reasoning is sound and standard, but flagged for empirical UI confirmation.
- Dead/saturated thresholds (Open Q2): MEDIUM — pattern reuse is clear; exact per-cell numeric thresholds are planner's call.

**Research date:** 2026-07-07
**Valid until:** ~2026-08-06 (30 days — codebase is stable; no fast-moving external deps).
