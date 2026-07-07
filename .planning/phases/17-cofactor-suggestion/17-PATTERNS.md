# Phase 17: In-QuPath Cofactor Suggestion - Pattern Map

**Mapped:** 2026-07-07
**Files analyzed:** 5 (2 new main classes, 1 new test, 1 modified main class, and the cluster of reused-unchanged classes whose call sites are excerpted below)
**Analogs found:** 5 / 5 (every new/modified file resolves to a strong in-repo analog; the estimator has an exact stylistic analog and the whole-project scope has an exact functional analog)

> **Path correction (verify in tasks):** CONTEXT.md and SPEC.md refer to `model/RobustStats.java`. The file is actually at **`src/main/java/qupath/ext/celltune/util/RobustStats.java`** (package `qupath.ext.celltune.util`), NOT `model/`. Its API used here: `RobustStats.median(double[])` (line 37), `RobustStats.medianIgnoreNaN(double[])` (line 54), and the constant `RobustStats.MAD_TO_SIGMA = 0.6745` (line 27). Plan tasks must import `qupath.ext.celltune.util.RobustStats`.

> **Modality correction (verify in tasks):** CONTEXT §Integration Points flags this as "planner to verify". CONFIRMED: `NormalizationPane` is launched **`APPLICATION_MODAL`** (`NormalizationPane.java:169`) via a blocking `pane.showAndWait()` (`CellTuneExtension.java:1699`). A suggest window owned by `qupath.getStage()` while an `APPLICATION_MODAL` `NormalizationPane` is showing will be **blocked by the modal**. The suggest window MUST be owned by the `NormalizationPane`'s own `stage` (`stage.initOwner(normalizationPaneStage)`), not by `qupath.getStage()`. See the "Shared Patterns → Non-modal window ownership under a modal parent" section.

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `src/main/java/qupath/ext/celltune/model/CofactorEstimator.java` (NEW — name per D-14 discretion) | model (pure-array estimator core) | transform (per-feature array → per-feature cofactor + global median) | `model/NeighborhoodModel.java` (pure static, JavaFX-free, synthetic-tested) + `model/ImagePixelStats.percentileSorted` / `util/RobustStats` (the actual math primitives) | exact (style) + exact (math reuse) |
| `src/main/java/qupath/ext/celltune/ui/CofactorSuggestionDialog.java` (NEW — name per D-05/discretion) | UI component (non-modal window) | request-response + batch (off-FX pooling) | `ui/NeighborhoodAnalysisDialog.java` (non-modal stage + progress + off-FX worker + `Platform.runLater`); results table from `ui/ClusterAssignmentPane.java` (per-row `GridPane`); whole-project run UX from `ui/ScatterPlotView.java`'s all-cells driver | exact (non-modal/progress/scope shape) |
| `src/test/java/qupath/ext/celltune/model/CofactorEstimatorTest.java` (NEW) | test | transform | `src/test/java/qupath/ext/celltune/model/NeighborhoodModelTest.java` (synthetic-cloud, fixed-seed `Random`, pure-array, JUnit 5) | exact (style) |
| `src/main/java/qupath/ext/celltune/ui/NormalizationPane.java` (MODIFY: add "Suggest…" button beside the cofactor spinner, arcsinh-only visibility, receive applied global cofactor into the spinner) | UI component | event-driven (button → dialog → write-back) | itself — the cofactor spinner block (lines 76-85) + `updateCofactorVisibility()` (lines 205-213) | exact (self-extension) |

**Reused UNCHANGED** (no edit; call-site excerpts below so tasks are grep-verifiable): `model/ImagePixelStats.java` (`percentileSorted`), `util/RobustStats.java` (`median`/`medianIgnoreNaN`/`MAD_TO_SIGMA`), `model/CohortClusterModel.java` (`poolAllCells` + `CancellationToken` + `PooledData`), `model/CellFeatureExtractor.java` (`extractMatrix`), `model/FeatureNormalizer.java` (arcsinh formula + cofactor range), `ui/FeatureSelectionPane.java` (`showAndWait`, grouping), `model/PixelCohortAnalyzer.java` (dead-channel/signal-floor exclusion idea).

---

## Pattern Assignments

### `src/main/java/qupath/ext/celltune/model/CofactorEstimator.java` (NEW — model, transform)

**Style analog:** `model/NeighborhoodModel.java`. **Math-reuse analogs:** `ImagePixelStats.percentileSorted` + `util/RobustStats`.

**Purity + class-header convention to copy verbatim** (`NeighborhoodModel.java:1-27`) — package `model`, `final class`, private ctor, "every method is static and a pure function of primitive arrays (no JavaFX, no QuPath types)" — this is the D-04 requirement that satisfies the SPEC deterministic-recovery test:
```java
package qupath.ext.celltune.model;

import java.util.ArrayList;
// ... only java.* / smile.* / same-package imports — NO javafx, NO qupath.lib

/**
 * Pure numerical core of ... Every method is static and a pure function of
 * primitive arrays (no JavaFX, no QuPath types), so ... is unit-testable in
 * isolation against synthetic clouds — mirroring {@code ScatterMath} ...
 */
public final class NeighborhoodModel {
    private NeighborhoodModel() {}
```

**Percentile primitive to call (D-01) — reuse `ImagePixelStats.percentileSorted`, do NOT re-derive** (`ImagePixelStats.java:429-446`). It requires an **ascending-sorted** array (caller sorts first); it interpolates and is NaN-safe for `n==0` (returns `NaN`), `n==1` (returns `sorted[0]`):
```java
public static double percentileSorted(double[] sorted, double percentile) {
    int n = sorted.length;
    if (n == 0) return Double.NaN;
    if (n == 1) return sorted[0];
    double p = Math.max(0.0, Math.min(100.0, percentile));
    double rank = (p / 100.0) * (n - 1);
    int lo = (int) Math.floor(rank);
    int hi = (int) Math.ceil(rank);
    if (lo == hi) return sorted[lo];
    double frac = rank - lo;
    return sorted[lo] * (1.0 - frac) + sorted[hi] * frac;
}
```
The per-feature cofactor = `percentileSorted(sortedFeatureColumn, P_BACKGROUND)` where `P_BACKGROUND` is the **fixed constant the researcher calibrates** (D-03 — method locked, numeric knob open; calibration target USER_GUIDE §4.2: raw-fluorescence panel → global cofactor in the tens ~25-50, MIBI → ~0.05). The table's `p99` value-scale column (D-09) is another `percentileSorted(sorted, 99.0)` call; `median`/`p1` likewise (see `ImagePixelStats.java:235-237` for the exact `median`/`p1`/`p99` call triple already used elsewhere).

**Robust median for the global aggregation (D-10, COF-06) — reuse `RobustStats.median`, do NOT hand-roll** (`util/RobustStats.java:37-48`). Note the package is `util`:
```java
public static double median(double[] values) {
    if (values.length == 0) return 0.0;
    double[] sorted = Arrays.copyOf(values, values.length);
    Arrays.sort(sorted);
    int mid = sorted.length / 2;
    if ((sorted.length & 1) == 1) return sorted[mid];
    return 0.5 * (sorted[mid - 1] + sorted[mid]);
}
```
Global cofactor = `RobustStats.median(perFeatureCofactors[])` computed over ONLY the non-dead / non-saturated features (D-11). Use `MAD_TO_SIGMA = 0.6745` (line 27) if a MAD-based degenerate/dead detector is chosen (Constraint: "reuse `RobustStats` conventions rather than ad-hoc statistics").

**Dead/saturated exclusion idea to mirror (D-11) — from `PixelCohortAnalyzer`** (`PixelCohortAnalyzer.java:37-44`, `SIGNAL_FOREGROUND_FLOOR = 0.05`): a channel/feature participates in the aggregate ONLY if it clears a signal floor; near-dead markers (whose distribution "hovers at the noise floor") are excluded so their meaningless jitter cannot skew the recommendation. Mirror this as a per-feature `boolean dead`/`saturated` flag on the result row (advisory display + excluded-from-median), computed from near-zero variance (dead) and a top-of-range saturation check. Follow that file's static-analyzer, no-QuPath-types shape:
```java
private static final double SIGNAL_FOREGROUND_FLOOR = 0.05;
```

**Suggested result shape (Claude's discretion, D-09):** a `record` per feature — `record FeatureCofactor(String feature, int nCells, double background, double median, double p99, double cofactor, boolean excluded, String reason)` — plus a top-level `record CofactorSuggestion(List<FeatureCofactor> perFeature, double globalCofactor)` returned from a single pure static entry point, e.g. `estimate(String[] featureNames, double[][] columns, double percentile)` where `columns[f]` is one feature's per-cell values. Keep the range clamp to the spinner's valid `(0.01, 10000)` in the model or leave it to the caller — but the Constraint requires the recommended value stay `> 0` and within range.

**Guard convention (mirror `NeighborhoodModel`/`percentileSorted`):** return neutral/`NaN` on empty input, never throw on degenerate data (SPEC: "NaN/degenerate-robust"). Determinism is free here — this estimator has no randomness; the "fixed seed" in the acceptance test applies to the SYNTHETIC DATA GENERATION, not the estimator (see the test analog).

---

### `src/main/java/qupath/ext/celltune/ui/CofactorSuggestionDialog.java` (NEW — UI, request-response + off-FX batch)

**Analog:** `ui/NeighborhoodAnalysisDialog.java` (non-modal + progress + off-FX worker); results table `ui/ClusterAssignmentPane.java`; whole-project run UX `ui/ScatterPlotView.java`.

**Non-modal stage construction to copy (D-05) — BUT owned by the NormalizationPane stage, not qupath** (`NeighborhoodAnalysisDialog.java:416-424`):
```java
Stage s = new Stage();
s.setTitle("Cellular Neighborhoods");
s.initOwner(qupath.getStage());          // ← for Phase 17: pass the NormalizationPane stage instead
s.initModality(Modality.NONE);
s.setScene(new Scene(rootScroll, 540, 840));
s.setOnHidden(e -> clearOverlayColoring());
return s;
```
For Phase 17: `s.initOwner(<NormalizationPane's Stage>)` + `Modality.NONE`. See the modality-correction note at the top — the parent `NormalizationPane` is `APPLICATION_MODAL`, so ownership by qupath's stage would block the child. The dialog exposes a `show()` (non-blocking) exactly like `NeighborhoodAnalysisDialog.show()` (lines 181-183).

**Off-FX worker + progress + status + `Platform.runLater` batching to copy verbatim (D-05, D-07 run-triggered)** (`NeighborhoodAnalysisDialog.java:634-662` and `:708-796`) — the exact "Run button → daemon Thread → try/catch/finally → `Platform.runLater` for every UI touch" shape:
```java
runBtn.setDisable(true);
progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
logArea.clear();
Thread worker = new Thread(() -> {
    try {
        runPipeline(...);                       // heavy compute off the FX thread
    } catch (Exception ex) {
        logger.warn("... run failed", ex);
        log("ERROR: " + ex.getMessage());       // log() itself wraps Platform.runLater (line 1395)
    } finally {
        Platform.runLater(() -> { runBtn.setDisable(false); progressBar.setProgress(0); });
    }
}, "CellTune-Neighborhoods");
worker.setDaemon(true);
worker.start();
```
And the `log` helper to copy (`:1395-1397`) — every log line already marshals to the FX thread, so the model's `Consumer<String> log` can be `this::log` directly:
```java
private void log(String msg) {
    Platform.runLater(() -> logArea.appendText(msg + "\n"));
}
```

**Scope selector (D-13: default = whole project) — copy the `ToggleGroup` + `RadioButton` scope pattern** (`NeighborhoodAnalysisDialog.java:129-135, 284-290, 347-350`):
```java
private final ToggleGroup scopeGroup = new ToggleGroup();
private final RadioButton currentScopeRadio = new RadioButton("Current image");
private final RadioButton projectScopeRadio = new RadioButton("Whole project");
// ...
currentScopeRadio.setToggleGroup(scopeGroup);
projectScopeRadio.setToggleGroup(scopeGroup);
currentScopeRadio.setSelected(true);          // ← Phase 17 D-13 flips this: default projectScopeRadio
// ...
projectScopeRadio.setDisable(project == null);
Runnable syncScope = () -> projectBox.setDisable(!projectScopeRadio.isSelected());
scopeGroup.selectedToggleProperty().addListener((o, a, b) -> syncScope.run());
syncScope.run();
```

**Calibration picker (D-12) — reuse `FeatureSelectionPane`, blocking `showAndWait()`** (`FeatureSelectionPane.java:76, 179-188`). The 3-arg ctor pre-selects a subset; pass the per-marker mean-intensity feature names as `preSelected` (D-12). Its selection is fully independent of the normalize set (COF-02):
```java
public FeatureSelectionPane(Stage owner, List<String> featureNames, List<String> preSelected) { ... }
public List<String> showAndWait() {            // returns selected names, or null if cancelled
    confirmed = false;
    stage.showAndWait();
    if (!confirmed) return null;
    return allFeatures.stream().filter(FeatureItem::isSelected).map(FeatureItem::getName)
            .collect(Collectors.toList());
}
```
To pre-select "per-marker mean-intensity features" (D-12) reuse the grouping classifier `FeatureSelectionPane.groupOf(name)` (`:225-244`) — markers are any name whose group is neither `GROUP_MORPHOLOGY`/`GROUP_NEIGHBORS`/`GROUP_EMBEDDINGS`/`GROUP_OTHER`; combine with a "Mean" token filter. NOTE: `FeatureSelectionPane`'s picker is a `Stage` opened with `showAndWait()` (blocking) — from the non-modal suggest window this is fine (it is a transient owned picker), but own it by the suggest window's stage.

**Results table (D-09) — copy the per-row `GridPane` build from `ClusterAssignmentPane`** (`ClusterAssignmentPane.java:82-137`). One header row of bold labels, then one row per feature; the Phase-17 columns are `feature | n cells | background | median | p99 | suggested cofactor` (plus an excluded/flag indicator per D-11). The `boldLabel` header helper (`:216-220`) and the `String.format("%,d", counts[c])` numeric formatting (`:116`) are directly reusable:
```java
grid.add(boldLabel("Cluster"), 0, 0);
grid.add(boldLabel("Cells"), 1, 0);
// ...
grid.add(new Label(String.format("%,d", counts[c])), 1, row);
```
The global cofactor "shown prominently" (D-10, COF-06) is a single bold `Label` above/below the grid. A `ScrollPane` wraps the grid (`:148-152`).

**Whole-project scope (D-13) — call `CohortClusterModel.poolAllCells` (all cells, memory-safe two-pass)** (see the reused-unchanged excerpt below). The dialog only needs PASS 1 (pool → per-feature columns), not the cluster/write-back of `writeClusterAllCells` — so it calls `poolAllCells` directly and transposes `PooledData.rows` (z-scored!) OR, more likely, pools RAW rows. **Caveat:** `poolAllCells` z-scores its returned `rows` (`CohortClusterModel.java:947`), which is wrong for a background-intensity estimator that must see raw intensities. The planner must either (a) add a raw-row variant / flag, or (b) reconstruct raw values from `rows * sd + mean` using the returned `mean`/`sd`, or (c) pass a normalizer of `null` and pool without z-scoring via a small new pooling path. Flag this as a real design fork.

**Soft-ceiling confirm + mid-run Cancel + `CancellationToken` (D-08) — copy the all-cells run UX from `ScatterPlotView`** (`ScatterPlotView.java:130-131, 2101-2147`). The soft-ceiling pref and the count-only pre-scan are already built:
```java
private static final IntegerProperty ALL_CELLS_SOFT_CEILING =
        PathPrefs.createPersistentPreference("celltune.allCellsSoftCeiling", 50_000_000);
// ...
final CohortClusterModel.CancellationToken token = new CohortClusterModel.CancellationToken();
// count-only pre-scan (no feature extraction) → extra confirm before pooling
long estimate = estimatePooledCellCount(project, images, token);   // ScatterPlotView.java:2288-2313
int ceiling = ALL_CELLS_SOFT_CEILING.get();
if (estimate > ceiling) {
    boolean proceed = confirmOnFx("Large all-cells run", String.format(
            "The selected images have an estimated %,d cells, above the configured %,d-cell soft "
            + "ceiling. ... Continue anyway?", estimate, ceiling));
    if (!proceed) { /* cancel */ return; }
}
```
The cheap `estimatePooledCellCount` pre-scan (`ScatterPlotView.java:2288-2313`) sums `imageData.getHierarchy().getDetectionObjects().size()` per image without feature extraction — reuse the same shape (or the same pref) rather than a full pool for the confirm. The Cancel button + `volatile CancellationToken allCellsToken` wiring (`:227-234, 456-466`) is the template for the dialog's Cancel; `poolAllCells` already checks `token.isCancelled()` at each per-image boundary (`CohortClusterModel.java:878-882`).

**Open-image scope — read live cells via `CellFeatureExtractor`** (see reused-unchanged excerpt). For one image the dialog builds per-feature columns from the live hierarchy's detections (`extractMatrix` → transpose to columns, or `extractRowRaw` per cell). COF-03: in-memory only, no geojson/file streaming.

**Apply action (D-05, COF-07) — a callback into `NormalizationPane`'s spinner, no data mutation.** The dialog takes a `DoubleConsumer applyGlobalCofactor` (or a direct reference to the pane's setter) constructed by `NormalizationPane`; on Apply it calls it with the clamped global value and (per D-05) leaves the Normalise Features dialog open. It NEVER runs normalization or touches measurements.

---

### `src/test/java/qupath/ext/celltune/model/CofactorEstimatorTest.java` (NEW — test, transform)

**Analog:** `src/test/java/qupath/ext/celltune/model/NeighborhoodModelTest.java` — extend its exact conventions.

**Header + import + fixed-seed convention to copy** (`NeighborhoodModelTest.java:1-23`):
```java
package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
// ...
class NeighborhoodModelTest {
    private static final double EPS = 1e-9;
```

**Synthetic-recovery test shape to mirror (COF-04 acceptance: known background recovered within a factor of ~2, deterministic under a fixed seed)** (`NeighborhoodModelTest.java:27-45` — fixed-seed `Random`, synthetic cloud, assert against a known ground truth):
```java
@Test
void knnMatchesBruteForceSetOnRandomCloud() {
    Random rng = new Random(42);            // ← fixed seed (COF-04 "deterministic under a fixed seed")
    int n = 200;
    // ... generate synthetic data with a KNOWN structure ...
    int[][] actual = NeighborhoodModel.kNearestNeighborIndices(xs, ys, k);
    for (int i = 0; i < n; i++) {
        assertEquals(...);                  // ← assert recovery against the known injected structure
    }
}
```
Phase-17 tests to write: (1) inject a per-cell distribution with a KNOWN background level `B` plus a separated positive population; assert the estimated cofactor lands within a factor of ~2 of `B` (COF-04). (2) A raw-fluorescence-scale panel (values in hundreds-thousands) → global median cofactor in the tens (acceptance criterion / D-03 range sanity). (3) Global = `RobustStats.median` of per-feature values for a known set (COF-06). (4) Dead/saturated features excluded from the median (D-11). (5) Degenerate/NaN input returns neutral without throwing. All pure-array, no QuPath/JavaFX types (mirrors the `NeighborhoodModelTest` header comment "Synthetic clouds only — no QuPath/JavaFX APIs").

**Determinism assertion style** (`NeighborhoodModelTest.java` uses `assertEquals`/`assertArrayEquals` with `EPS` for doubles) — the estimator is deterministic by construction (no RNG inside it), so a "same input → same output" test is trivially satisfied; the seed governs the synthetic data generator only.

---

### `src/main/java/qupath/ext/celltune/ui/NormalizationPane.java` (MODIFY — UI, event-driven)

**Analog:** itself. Two existing regions are the hook points; add a "Suggest…" `Button` and wire it.

**Cofactor spinner + its row (the launch host) — lines 76-86** (the button goes into `transformRow` beside `cofactorSpinner`):
```java
cofactorLabel = new Label("Cofactor:");
cofactorSpinner = new Spinner<>(0.01, 10000.0, existing != null ? existing.getArcsinhCofactor() : 1.0, 1.0);
cofactorSpinner.setEditable(true);
cofactorSpinner.setPrefWidth(100);

cofactorHint = new Label("(fluor ~25–50, MIBI 0.05 — scale-dependent)");
cofactorHint.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");

HBox transformRow =
        new HBox(8, new Label("Transform:"), transformCombo, cofactorLabel, cofactorSpinner, cofactorHint);
```
Add a `Button suggestBtn = new Button("Suggest…")` (or from the strings bundle, see below) into this `HBox`. Its handler constructs and `show()`s the new `CofactorSuggestionDialog`, passing (a) `stage` as the owner (the modal-parent — see modality note), (b) the feature names, and (c) an apply callback that does `cofactorSpinner.getValueFactory().setValue(clampedGlobal)`. The write-back is COF-07: single action, no mutation.

**Arcsinh-only visibility toggle to EXTEND (D-06) — `updateCofactorVisibility()`, lines 205-213** (the Suggest button must follow the exact same `setVisible`/`setManaged` pairing so it appears only for arcsinh, absent for sqrt — COF-01 acceptance):
```java
private void updateCofactorVisibility() {
    boolean isArcsinh = transformCombo.getValue() == Transform.ARCSINH;
    cofactorLabel.setVisible(isArcsinh);
    cofactorLabel.setManaged(isArcsinh);
    cofactorSpinner.setVisible(isArcsinh);
    cofactorSpinner.setManaged(isArcsinh);
    cofactorHint.setVisible(isArcsinh);
    cofactorHint.setManaged(isArcsinh);
    // ← Phase 17: add suggestBtn.setVisible(isArcsinh); suggestBtn.setManaged(isArcsinh);
}
```
This method is already called on transform change (`transformCombo.setOnAction(e -> updateCofactorVisibility())`, line 73) and at construction (line 175), so extending it here covers both COF-01 acceptance cases.

**Applying the value into the spinner respects its range (COF-07)** — the spinner is constructed `new Spinner<>(0.01, 10000.0, ...)` (line 77), so its `SpinnerValueFactory.DoubleSpinnerValueFactory` clamps automatically; the estimator/dialog should still clamp to `(0.01, 10000)` before calling so the displayed value equals the recommendation (never silently clamped to a bound). Note: `NormalizationPane` currently does NOT own a `FeatureSelectionPane` — its own feature list (lines 34-37, inline `ListView<FeatureItem>`) is separate; the suggest tool's picker is an INDEPENDENT `FeatureSelectionPane` (COF-02), so no coupling to this pane's list.

---

## Shared Patterns

### Non-modal window ownership under a modal parent
**Source:** `NeighborhoodAnalysisDialog.java:416-424` (non-modal stage) + `NormalizationPane.java:168-169` (`initOwner(owner)` + `Modality.APPLICATION_MODAL`) + `CellTuneExtension.java:1699` (blocking `pane.showAndWait()`).
**Apply to:** `CofactorSuggestionDialog`. Because `NormalizationPane` is `APPLICATION_MODAL` and shown via a blocking `showAndWait()`, the suggest window MUST `initOwner(normalizationPaneStage)` (child of the modal, so it is not blocked) + `Modality.NONE`. Do NOT own it by `qupath.getStage()`. The `NormalizationPane` therefore needs to expose its `stage` (currently `private final Stage stage`, line 33) to the launch handler — add a package-private getter or construct the dialog from inside the pane.

### Off-FX compute + `Platform.runLater` UI marshalling
**Source:** `NeighborhoodAnalysisDialog.java:634-662` (worker thread + try/catch/finally) and `:1395-1397` (`log` wraps `Platform.runLater`).
**Apply to:** the suggest dialog's Run action — all pooling/estimation off a daemon `Thread`; every `statusLabel`/`progressBar`/table/log touch via `Platform.runLater`. Reuse the `Consumer<String> log = this::log` sink so `poolAllCells`'s progress messages route straight to the FX thread. CLAUDE.md "UI thread safety" mandates this.

### Memory-safe all-cells pooling (two-pass, no sampling)
**Source:** `CohortClusterModel.poolAllCells` (`CohortClusterModel.java:849-959`) + `CancellationToken` (`:735-745`) + `PooledData` record (`:767-776`).
**Apply to:** the suggest dialog's whole-project scope (D-13/COF-08). Reuse `poolAllCells` unchanged for pass 1 (pool every cell, no cap → rare markers preserved). Excerpt of the exact API:
```java
public static PooledData poolAllCells(
        Project<BufferedImage> project, List<String> images, List<String> markers,
        String classFilter, FeatureNormalizer normalizer,
        ImageData<BufferedImage> openData, String openName,
        CancellationToken token, Consumer<String> log) { ... }

public record PooledData(double[][] rows, long[] uuidMsb, long[] uuidLsb, int[] imageOrdinal,
        String[] imageNames, double[] mean, double[] sd, int totalCells, boolean cancelled) {}

public static final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
}
```
**Raw-vs-zscored caveat (design fork for planner):** `poolAllCells` returns `rows` already **z-scored** (`CohortClusterModel.java:944-947`). The background-intensity estimator needs RAW intensities. Options: reconstruct raw via `rows[i][j] * sd[j] + mean[j]`, or add a raw-pooling seam. The `token.isCancelled()` check is at the top of each per-image loop (`:878-882`) — cancellation works out of the box.

### Soft-ceiling confirm + count-only pre-scan
**Source:** `ScatterPlotView.java:130-131` (`ALL_CELLS_SOFT_CEILING` pref, default `50_000_000`) + `:2288-2313` (`estimatePooledCellCount`, sums `getDetectionObjects().size()` per image, cancellation-aware) + `:2132-2147` (the `estimate > ceiling` confirm).
**Apply to:** the whole-project run (D-08). Reuse the same pref key `celltune.allCellsSoftCeiling` (do not invent a second ceiling) and the same cheap pre-scan shape before pooling. This mirrors Phase 15 D-10/D-11/D-12.

### Percentile + robust-median math primitives (never re-derive)
**Source:** `ImagePixelStats.percentileSorted` (`ImagePixelStats.java:429-446`, interpolated, requires ascending-sorted input, NaN-safe) + `RobustStats.median`/`medianIgnoreNaN`/`MAD_TO_SIGMA` (`util/RobustStats.java:37, 54, 27`).
**Apply to:** `CofactorEstimator`'s per-feature cofactor (a percentile), the table's median/p1/p99 value-scale columns, and the global median aggregation. Sort each feature column ascending once (`Arrays.sort`) then call `percentileSorted` at the calibrated `P_BACKGROUND`, `50.0`, `99.0` as needed — exactly as `ImagePixelStats.java:235-237` already does for `median`/`p1`/`p99`.

### Dead/saturated exclusion (signal floor)
**Source:** `PixelCohortAnalyzer.java:37-44` (`SIGNAL_FOREGROUND_FLOOR = 0.05`; "near-dead markers ... excluded so their meaningless relative jitter cannot manufacture spurious z-scores").
**Apply to:** D-11 — flag dead (near-zero variance / all-background) and saturated features in the table and exclude them from the global median. Follow the analyzer's static, no-QuPath-types, unit-tested shape; put the detection in `CofactorEstimator` (pure) so the test can cover it.

### In-memory open-image reads
**Source:** `CellFeatureExtractor.extractMatrix` (`CellFeatureExtractor.java:131-152`) + `extractRowRaw` (`:112`).
**Apply to:** open-image scope (COF-03). `extractMatrix` returns a flat `float[]` of `nCells * nFeatures`, NaN→0f, parallelised; transpose to per-feature columns for the estimator. Use the RAW variant (`extractRowRaw`, or `extractMatrix` with a null normalizer) so the estimator sees un-normalized intensities.

### FeatureNormalizer reused UNCHANGED (contract not touched — SPEC out-of-scope)
**Source:** `FeatureNormalizer.java:99-106` (cofactor getter/setter, `setArcsinhCofactor` throws if `<= 0`) + `:126-136` (arcsinh formula) — the spinner range `(0.01, 10000)` matches the `> 0` invariant.
**Apply to:** nothing is edited here. The tool only WRITES the spinner value; `NormalizationPane.showAndWait()` (lines 183-201) later calls `normalizer.setArcsinhCofactor(cofactorSpinner.getValue())` as it does today. Cofactor must stay `> 0` and in range (Constraint) or `setArcsinhCofactor` throws:
```java
public void setArcsinhCofactor(double cofactor) {
    if (cofactor <= 0) throw new IllegalArgumentException("Cofactor must be positive");
    this.arcsinhCofactor = cofactor;
}
```

### UI strings via ResourceBundle
**Source:** CLAUDE.md "QuPath 0.7 API notes" — all UI text from `ResourceBundle.getBundle("qupath.ext.celltune.ui.strings")`; bundle at `src/main/resources/qupath/ext/celltune/ui/strings.properties`.
**Apply to:** new button/dialog labels ("Suggest…", scope radios, column headers) SHOULD be added to `strings.properties` per house convention — though note `NeighborhoodAnalysisDialog` and `ClusterAssignmentPane` use inline string literals, so this is a soft convention the planner may follow either way.

## No Analog Found

Every file for this phase has a strong in-repo analog. Two minor concerns have no direct analog but a clear resolution:

| Concern | Role | Data Flow | Reason / Resolution |
|---|---|---|---|
| RAW (un-z-scored) all-cells pooling for a background-intensity estimand | model | batch | `poolAllCells` z-scores its `rows` (`CohortClusterModel.java:947`); no existing caller needs raw pooled intensities. Resolution options in the "poolAllCells" shared-pattern note (reconstruct via `mean`/`sd`, add a raw seam, or pool with `null` normalizer). Design fork the planner must decide — not a missing pattern, just an unused code path. |
| Exposing `NormalizationPane`'s modal `Stage` to a child non-modal window | UI | — | `NormalizationPane.stage` is currently `private` (line 33) with no getter, and no other pane in this codebase spawns a non-modal child from inside a modal `showAndWait()`. Add a package-private accessor or build the dialog from within the pane; own the child by that stage (see modality note). Minor structural add, no existing template. |

## Metadata

**Analog search scope:** `src/main/java/qupath/ext/celltune/model/`, `src/main/java/qupath/ext/celltune/util/`, `src/main/java/qupath/ext/celltune/ui/`, `src/test/java/qupath/ext/celltune/model/`, `.planning/phases/15-all-cells-leiden-clustering/15-PATTERNS.md` (house format).
**Files read in full:** `NormalizationPane.java` (326), `NeighborhoodAnalysisDialog.java` (1398), `util/RobustStats.java` (163), `ClusterAssignmentPane.java` (230).
**Files read in targeted sections:** `NeighborhoodModel.java` (1-75, header/purity + kNN shape), `ImagePixelStats.java` (420-469, `percentileSorted`), `CohortClusterModel.java` (730-989, `CancellationToken`/`PooledData`/`poolAllCells`), `FeatureSelectionPane.java` (1-110, 175-294, ctor/showAndWait/grouping), `PixelCohortAnalyzer.java` (30-89, signal-floor exclusion), `CellFeatureExtractor.java` (125-162, `extractMatrix`), `FeatureNormalizer.java` (108-141, arcsinh/cofactor), `NeighborhoodModelTest.java` (1-75, test conventions), `ScatterPlotView.java` (2095-2164, 2280-2319, all-cells soft-ceiling + pre-scan), `CellTuneExtension.java` (1690-1712, NormalizationPane launch).
**Path/modality corrections surfaced:** `RobustStats` is in `util/` not `model/`; `NormalizationPane` is `APPLICATION_MODAL` (child ownership required); `poolAllCells` returns z-scored rows (raw fork needed).
**Pattern extraction date:** 2026-07-07
