---
phase: 17-cofactor-suggestion
reviewed: 2026-07-08T01:19:29Z
depth: deep
review_base: b869b9e..HEAD
files_reviewed: 7
files_reviewed_list:
  - src/main/java/qupath/ext/celltune/model/CofactorEstimator.java
  - src/main/java/qupath/ext/celltune/model/CohortClusterModel.java
  - src/main/java/qupath/ext/celltune/ui/CofactorSuggestionDialog.java
  - src/main/java/qupath/ext/celltune/ui/NormalizationPane.java
  - src/main/java/qupath/ext/celltune/CellTuneExtension.java
  - src/test/java/qupath/ext/celltune/model/CofactorEstimatorTest.java
  - src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java
findings:
  critical: 0
  high: 0
  medium: 1
  low: 4
  total: 5
status: issues_found
---

# Phase 17: Code Review Report — In-QuPath Cofactor Suggestion

**Reviewed:** 2026-07-08T01:19:29Z
**Depth:** deep (cross-file: estimator ↔ extractor ↔ dialog ↔ pooling)
**Files reviewed:** 7 (5 main + 2 test)
**Status:** issues_found (advisory / non-blocking)

## Summary

The Phase 17 changes are well-structured and the design corrections called out in the task
(owner-Stage injection, raw pooling sibling) are implemented correctly and are **not** re-litigated
here. Concretely verified as sound:

- **`poolAllCellsRaw` fidelity** — byte-identical to `poolAllCells` (same `entriesByName` /
  `selectImageSource` / `PoolReadException` / per-image `token` check / packed-UUID capture / GC
  release / `int totalCells` reporting count) except it skips `ScatterMath.standardizeColumns` and
  returns zero `mean`/`sd` arrays to preserve the `PooledData` shape. No divergence in the two-pass
  loop, cancellation checks, or open-image handling. Memory is actually *lower* than `poolAllCells`
  (no second z-scored copy). The `CohortClusterModelTest` addition proves raw vs z-scored behaviour.
- **UI-thread safety** — Run executes on a daemon worker off the FX thread; every scene-graph touch
  (`log`, `renderResults`, button/progress resets, the soft-ceiling confirm) marshals through
  `Platform.runLater` / a `CountDownLatch`. No `setPathClass`/hierarchy mutation from the worker;
  the dialog reads detections only. `CancellationToken` (AtomicBoolean) is honored at every
  per-image boundary and re-checked after the pre-scan and after pooling. Apply mutates only the
  spinner value on the FX thread — no measurement/normalizer mutation, no normalize run (COF-07).
- **QuPath conventions** — `project.getEntry(openData)` is null-checked; `Dialogs` (not raw `Alert`)
  used throughout; owner-Stage injection and `Modality.NONE` correct; the only two callers of the
  changed constructors (`NormalizationPane`, `CofactorSuggestionDialog`) are both updated.
- **Estimator math** — p50 background, `spread < 1e-9` dead / `p50 >= 10000` saturated exclusion,
  global median over kept features, `[0.01, 10000]` clamp, and empty/all-excluded fallback to 1.0
  are all correct and match the tests.

One substantive latent-correctness finding (Medium) and four Low quality/robustness items follow.
No Critical/High issues, no security concerns, no thread-safety violations.

## Medium

### MED-01: Estimator's documented "NaN = not measured, dropped" contract is defeated upstream — unmeasured cells become 0.0 background samples

**Files:**
- `src/main/java/qupath/ext/celltune/model/CofactorEstimator.java:97-99` (contract)
- `src/main/java/qupath/ext/celltune/model/CellFeatureExtractor.java:144` (NaN→0f coercion)
- `src/main/java/qupath/ext/celltune/ui/CofactorSuggestionDialog.java:295` (open-image feed)
- `src/main/java/qupath/ext/celltune/ui/CofactorSuggestionDialog.java:351` (whole-project feed, via `poolAllCellsRaw`)

**Issue:** `estimateFeature` documents and implements NaN-dropping — *"NaN values are dropped (they
mean 'not measured', not 'true zero background')"* — via `dropNaN`. But every production caller
sources its columns from `CellFeatureExtractor.extractMatrix`, which coerces missing/NaN
measurements to `0f` (`matrix[offset+j] = Double.isNaN(v) ? 0f : (float) v`). Both the open-image
path (`poolOpenImage` → `extractMatrix`) and the whole-project path (`poolWholeProject` →
`poolAllCellsRaw` → `extractMatrix`) therefore guarantee the estimator **never receives a NaN**. The
`dropNaN` branch is dead in production (exercised only by unit tests that inject NaN directly), and
the exact contamination it was written to prevent can occur: a genuinely unmeasured cell contributes
`0.0` to the background percentile instead of being excluded.

**Concrete failure scenario:** A project with heterogeneous marker panels — e.g. `FoxP3: Cell: Mean`
present on only 2 of 6 images. In whole-project scope every cell is pooled; the 4 images lacking
FoxP3 return `NaN` from the measurement list → coerced to `0.0`. If those 4 images hold the majority
of cells, the FoxP3 column's p50 collapses to `0.0` → `cofactor = clamp(0) = 0.01`. Because real
signal cells exist in the other 2 images, the column's `spread` is large, so it is **not** flagged
dead and enters the global median (`globalCofactor`), dragging the recommended cofactor toward the
`0.01` floor. The user sees `background 0.00, suggested 0.01, flag (blank)` for FoxP3 and a biased
headline recommendation. Homogeneous panels (every cell measured on every channel — the stated
raw-fluor / MIBI targets) are unaffected, which is why this is Medium not High, but it is a real
gap between the estimator's stated contract and the wired data path.

**Fix:** Preserve the "not measured" signal end-to-end rather than coercing it away before the
estimator. Either (a) add a raw NaN-preserving extraction path for the calibration reads (e.g. a
variant of `extractMatrix` that keeps `NaN` instead of `0f`) so `dropNaN` actually fires, or (b) if
NaN cannot be preserved, drop the misleading NaN paragraph from `estimateFeature`'s Javadoc and
instead detect the all-/mostly-zero-with-signal-tail case explicitly. Minimal, contained option:

```java
// CellFeatureExtractor — add alongside extractMatrix, used only by the cofactor calibration reads:
public double[] extractColumnPreservingNaN(List<PathObject> cells, String feature) {
    double[] col = new double[cells.size()];
    for (int i = 0; i < cells.size(); i++) {
        col[i] = cells.get(i).getMeasurementList().get(feature); // keep NaN — do NOT coerce to 0
    }
    return col;
}
```
Then feed per-feature columns from that into `estimateFeature`, letting `dropNaN` exclude unmeasured
cells as documented.

## Low

### LOW-01: `activeToken` is a write-only (dead) field

**File:** `src/main/java/qupath/ext/celltune/ui/CofactorSuggestionDialog.java:83,231,245`

**Issue:** `activeToken` is assigned (`= token`, line 231) and cleared (`= null`, line 245) but never
read anywhere. The Cancel button handler cancels the **local** `token` (line 233), not `activeToken`.
The field carries no behaviour — if the intent was to let a second Run or an external caller cancel
an in-flight run, that wiring is missing.

**Fix:** Either remove the field, or, if external cancellation was intended, have `runSuggestion`
first `cancel()` any non-null `activeToken` before starting a new run (and read it from Cancel). As
written, delete `activeToken` and rely on the local `token`.

### LOW-02: Whole-project transpose materializes a full second copy of the pooled matrix

**File:** `src/main/java/qupath/ext/celltune/ui/CofactorSuggestionDialog.java:357-371` (`poolWholeProject`)

**Issue:** `pooled.rows()` (`[nCells][nFeatures]`) stays referenced through `pooled` while the loop
builds `columns` (`[nFeatures][nCells]`), so peak heap is ≈2× the pooled raw matrix. At the
whole-project soft-ceiling (`celltune.allCellsSoftCeiling`, default 50,000,000 cells) with a
moderate marker count this is multiple GB of transient duplication — at odds with the memory-safe
posture the all-cells design otherwise maintains. (Performance/memory is largely out of v1 scope;
noted because the task specifically asked about `poolAllCellsRaw`/whole-project memory behaviour. The
duplication is in the dialog transpose, not in `poolAllCellsRaw` itself.)

**Fix:** Build column-major directly (either pool into columns in `poolAllCellsRaw` via an overload,
or copy row-by-row and null out `pooled`'s reference as early as possible). Lower-effort mitigation:
process the estimator one feature-column at a time so only one `double[nCells]` is live at once
instead of the full `double[nFeatures][nCells]` alongside the rows.

### LOW-03: Soft-ceiling pre-scan re-reads every image's full hierarchy (doubled disk I/O per whole-project run)

**File:** `src/main/java/qupath/ext/celltune/ui/CofactorSuggestionDialog.java:379-412` (`estimatePooledCellCount`)

**Issue:** The pre-scan calls `entry.readImageData()` on every non-open image purely to read
`getDetectionObjects().size()`, then `poolAllCellsRaw` reads each image **again** to pool. Every
`.qpdata` hierarchy is deserialized twice per whole-project run. The "cheap count-only pre-scan"
comment understates the cost — loading a hierarchy to count it is not cheap. (No new resource leak:
like all cohort code here, the read `ImageData`/server is not closed and is left to GC — consistent
with `poolAllCells`/`NeighborhoodCohort`, so not Phase-17-specific.)

**Fix:** Accept the double-read as the price of an accurate soft-ceiling estimate (there is no
lighter QuPath detection-count API), but drop or reword the "cheap" comment so it does not imply the
pre-scan is free. If a cheaper heuristic is acceptable, estimate from a cached/summary count instead
of a full re-read.

### LOW-04: `globalCofactor(List)` NPEs on null despite the class's "never throws" posture; `median` column duplicates `background`

**File:** `src/main/java/qupath/ext/celltune/model/CofactorEstimator.java:109,154-155`

**Issue (two minor items):**
1. `globalCofactor` is `public` and does `results.stream()` with no null guard — it throws NPE on a
   `null` argument, whereas its sibling `estimateFeature` is explicitly documented as never throwing
   on degenerate input. Inconsistent robustness for a public API surface.
2. `median` is assigned `= background` (identical p50 statistic) and rendered as a **separate**
   diagnostics column next to `background` (`CofactorSuggestionDialog.java:452-453`), presenting the
   user two identically-valued columns under different headers. Documented as intentional, but it is
   confusing display.

**Fix:** (1) Guard `globalCofactor`: `if (results == null || results.isEmpty()) return NEUTRAL_FALLBACK;`
before the stream. (2) Drop either the `median` field/column or relabel it so it does not read as an
independent statistic (e.g. omit it, or show a distinct robust statistic).

---

_Reviewed: 2026-07-08T01:19:29Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: deep_
