# CellTune — Code Review

_Review of the CellTune QuPath 0.7 extension, conducted on the `chore/code-review-cleanup`
branch (rebased onto `main` @ `e3f0b4b`, which includes the merged image-pixel-prescreen work)._

CellTune is a researcher-facing tool whose output feeds scientific decisions, so this review
weights **correctness and numerical validity** above cosmetic concerns. Findings are
severity-ranked; each cites `file:line`, states the impact, and points to the remediation
phase. Items already tracked in [.planning/codebase/CONCERNS.md](.planning/codebase/CONCERNS.md)
are referenced rather than duplicated.

Legend: **[FIX]** addressed in this pass · **[DEFER]** documented, left for a follow-up ·
**[WONTFIX]** investigated and intentionally not changed.

---

## Completed stages (merged to `main`)

Work has shipped in reviewed PRs, each verified with `clean compileJava test` on JDK 25 (and
manual QuPath QA where it touched interactive paths):

1. **Code review report** — this document (PR #5).
2. **Correctness fixes** (PR #5) — `LabelStore` thread-safety (synchronised map + guarded
   compound ops), `Resampler` label-index validation, diagnostic logging on the silent
   `hasImageLabels` catches.
3. **Shared helpers / dedup** (PR #5) — `util/RobustStats`, `io/CsvUtils`,
   `util/BackgroundExecutors`.
4. **`FileSystemUtilities`** (PR #5) — pure zip/recursive-delete extracted from `ProjectStateManager`.
5. **Tests + tooling** (PR #5) — `BinaryClassifierRegistry`, `GroundTruthIO`, `ClassManager`,
   `CohortClusterModel`, `RobustStats`, `CsvUtils`, `FileSystemUtilities`, `LabelStore`/`Resampler`
   regressions; **SpotBugs** wired as a non-failing reporting task.
6. **`AnnotationLabelCollector`** (PR #5) — unified the divergent `collectLabelsFromHierarchy`
   copies onto the merge-history-preserving behavior (fixes a latent label-loss bug, #13).
7. **`UtilityScripts`** (PR #5) — five Utility-Scripts-menu helpers lifted out of
   `CellTuneExtension` (~4.5k → 3.7k lines). _QA'd._
8. **`PredictionBatcher`** (PR #6) — unified the three chunked-prediction loops in
   `DualModelClassifier`; now unit-tested via stub predictors. _QA'd: train → predict._
9. **`ImagePrefetcher` + `TileEntryCleaner`** (PR #8) — prefetch lifecycle and tile project-entry
   cleanup extracted from `ReviewController` (~88 lines lighter). _QA'd: tile + cross-image review._
10. **`TrainValMetricsComputer`** (this branch) — `computeTrainValMetrics` + `stratifiedSplit`
    extracted from `DualModelClassifier`; unit-tested with stub trainers/predictors. Dead
    `extractRowSubset`/`extractLabelSubset` removed.
11. **`ScatterMath` + `ScatterPlotCanvas`** (PR `refactor/scatterplot-view`) — the largest
    deferred interactive split, now done. `ScatterMath` (the pure PCA/UMAP/standardise/
    subsample/point-in-polygon core, unit-tested) and `ScatterPlotCanvas` (the Canvas +
    all drawing + box/lasso/legend gestures, behind a read-only `PlotModel` + gesture
    callbacks) were lifted out of `ScatterPlotView`, which drops from ~2.2k → ~1.6k lines
    and keeps all QuPath hierarchy/viewer mutation. Also adds a status-bar progress bar for
    the control-locking assign/apply runs. _QA'd: render modes, box/lasso/legend select,
    viewer↔plot sync, project assign, UMAP, export._
12. **`AnnotationRegionExporter`** (this branch) — the OME-TIFF annotation-region export
    (`exportAnnotationRegions` dialog + the reflective `writeOmePyramid`/`applyBuilderOption`
    helpers + the tileable, polygon-masking `RoiMaskedServer`) lifted verbatim out of
    `CellTuneExtension` into its own root-package class, mirroring the `UtilityScripts` move.
    The menu item delegates; behaviour is preserved 1:1. `CellTuneExtension` drops ~3.7k → ~3.4k
    lines and sheds 19 now-unused imports (the AWT raster + image-server stack). **Needs a manual
    QuPath smoke test** of the export (no automated coverage is possible for the interactive
    OME writer path).
13. **`ProjectStateManager` persistence split** (this branch) — the deferred persistence
    decomposition, done. `io/PredictionPersistence`, `io/BinaryClassifierPersistence`,
    `io/LabelPersistence` and `io/MarkerTablePersistence` were extracted as focused, package-private
    helpers; `ProjectStateManager` keeps thin delegating wrappers so all ~109 call sites and behaviour
    are unchanged (the `FileSystemUtilities` facade pattern). The dead, never-called composite-rule/
    config persistence (~240 lines) was deleted. `ProjectStateManager` drops ~1.6k → ~0.85k (under
    1000). New round-trip tests `PredictionPersistenceTest` and `LabelPersistenceTest`; the binary and
    marker-table helpers stay covered by the existing tests via the unchanged facade. _Pure IO — no
    QuPath QA needed; verified with `clean compileJava test`._
14. **`FeatureMappingService` + `DataPoolingService`** (this branch) — the pure, testable core of the
    deferred `ClassificationPanel.doTrain` split. `classifier/FeatureMappingService`
    (`buildFeatureIndexMap` + `alignRow`, deduping a live copy in `ClassificationPanel` and a dead copy
    in `CellTuneExtension`) and `classifier/DataPoolingService` (`poolImportedRows`) lift the imported-row
    feature-alignment/pooling out of `doTrain`, both unit-tested (`FeatureMappingServiceTest`,
    `DataPoolingServiceTest`).
15. **`ui/TrainingOrchestrator`** (this branch) — the two self-contained, IO-bound concerns of the
    `doTrain` background pipeline lifted out behind explicit parameters: `poolLabelsFromOtherImages`
    (read saved per-image labels project-wide, open each labelled image, extract supplementary rows)
    and `applyToTargetImages` (parallel batch-classify target images + persist per-image predictions).
    Verbatim moves, behaviour preserved 1:1; `doTrain` keeps validation, feature prep, progress UI, the
    `trainAndPredict` call, classifier-state save and FX completion. `ClassificationPanel` ~1.9k → ~1.76k.
    Mirrors the `ReviewController` → `ImagePrefetcher`/`TileEntryCleaner` precedent.
    _QA'd: train → predict, cross-image pooling, batch apply, project save._
16. **`ProjectPredictionSummary`** (this branch) — first slice of the `CellTuneExtension` manager split.
    The whole **Project Prediction Summary** feature (the parallel per-image prediction load behind a
    progress dialog, cohort anomaly analysis, and `ProjectPredictionSummaryView` launch, plus the
    `buildPredictionSummaryRow`/`formatClassCounts`/`formatFlagReasons`/`formatHighlightedRareClasses`
    helpers and the `SummaryInputRow` record) lifted verbatim into root-package
    `ProjectPredictionSummary` (mirroring `UtilityScripts`/`AnnotationRegionExporter`). The menu handler
    delegates via a single call — it persists the current image's predictions first, then
    `ProjectPredictionSummary.show(qupath, predAll)`. `CellTuneExtension` ~3.35k → ~3.12k; three now-dead
    imports dropped (PR #18). _QA'd: summary dialog._
17. **`AnalysisViews`** (this branch) — second slice of the `CellTuneExtension` split: the read-only
    analysis/visualisation launchers grouped into one root-package class (mirroring `UtilityScripts`).
    `showDistanceMeasurements`, `showIntensityHeatmaps`, `showScatterPlot`, `showImagePixelPrescreen` and
    `showFeatureImportance` lifted verbatim; the menu handlers delegate. None write back to session
    state — the only inputs that cross the boundary are passed as parameters (`predAll`, `featureNormalizer`,
    the trained `classifier`) plus a `Runnable` callback for the Class Control re-launch from the scatter
    plot. `showConfusions` was **left in place** (it writes `lastAgreementRates` and triggers
    auto-classify, so it's not read-only). `CellTuneExtension` ~3.12k → ~2.85k; seven now-dead imports
    dropped (PR #19). _QA'd: all five launched views._
18. **`ImportExport`** (this branch) — third slice of the `CellTuneExtension` split: the whole
    cell-table / ground-truth / marker-table import &amp; export group moved to one root-package class.
    `exportCellTable` (self-contained), `exportGroundTruth` (+ the static `appendImportedRowsToCsv` /
    `appendOtherImageLabelsToCsv` helpers and `askExportFeatureOptions`/`ExportFeatureOptions`),
    `importMarkerTable` and `importGroundTruth` lifted verbatim. The exports are read-only (state passed
    as parameters); the two **imports return their result** (`CellTypeTable`, or a `GroundTruthImportResult`
    of new labels + imported rows/feature-names) and the extension's thin handler assigns the fields and
    calls `syncPanelState()` in one place — so `ImportExport` has no session-state side effects. The only
    behaviour delta is that `syncPanelState()` now runs just after the import's save/notify instead of just
    before (end state identical). `ensureActiveBinaryMarker` and the two binary GT wrappers stay in the
    extension. `CellTuneExtension` ~2.85k → ~2.21k; 16 now-dead imports dropped (PR #20). _QA'd: export/
    import flows + the new Clear-imported-rows button._
19. **`BinaryClassifierManager`** (this branch) — fourth slice, and the first **stateful** one. Binary
    mode (`enterBinaryMode`/`exitBinaryMode`, `showBinaryClassifiers`, and `scrubBinaryPerImageLabels`)
    plus its private state (the `preBinary*` multi-class snapshot and the marker `binaryRegistry`) moved
    into root-package `BinaryClassifierManager`. The shared session fields it must read/write (label
    store, classifier, imported rows/feature-names, active marker + class names) are reached through a
    `BinaryClassifierManager.Host` interface the extension implements with plain accessors, plus
    `syncPanelState()` / `selectAndExpandDockPanel()`; so those fields stay where the rest of the
    extension reads them. Lifted verbatim (incl. the contamination self-heal + snapshot-only-on-transition
    logic). `resetInMemoryState` now calls `binaryManager.reset()`. `applyBinaryClassifierToImages` and
    `ensureActiveBinaryMarker` stay in the extension (shared `binaryTargetImages` / used by the import-export
    wrappers). `CellTuneExtension` ~2.21k → **~2.0k** (from ~4.5k originally); two now-dead imports dropped.
    **Needs careful manual QuPath QA** — enter/switch/exit binary mode, train + persist a binary classifier,
    the self-heal on contaminated stores, and that multi-class state is correctly restored on exit.

**Still open (deferred):** the remaining large interactive/stateful decompositions that need manual
QuPath QA to verify safely — the residual `doTrain` orchestration (validation/feature-prep/progress
UI/save/FX completion left inline after stage 15; further extraction has diminishing returns), the
`io/LabelPersistence` **dedup of the entangled `collectLabelsFromAnnotations`/`persist*` methods across
`CellTuneExtension` and `ClassificationPanel`** (#8 — distinct from the per-image-file IO extracted in
stage 13), the `CellTuneExtension` manager split, and the `ReviewController` tile/normal strategy split.
See the per-item rationale in the **Structure** section below.

---

## Summary

| # | Severity | Finding | Disposition |
|---|----------|---------|-------------|
| 1 | High | `LabelStore` shared `LinkedHashMap` is unsynchronised; mutated from UI, training, and image-switch threads | **[FIX]** Phase B |
| 2 | — | Unguarded `project.getEntry()` dereferences | **[WONTFIX]** — false positive after audit (all sites already guarded) |
| 3 | Medium | `Resampler` does not validate class indices → `ArrayIndexOutOfBoundsException` on corrupt labels | **[FIX]** Phase B |
| 4 | Low | Two `hasImageLabels` IOException catches return `false` with no log | **[FIX]** Phase B (debug log; rest already logged) |
| 5 | Low | Robust-z math duplicated with **divergent** behaviour across two analyzers | **[FIX]** Phase C (preserve both behaviours) |
| 6 | Low | CSV escaping reimplemented three times with inconsistent quoting | **[FIX]** Phase C |
| 7 | Low | Background executors created ad hoc in 4+ classes; no shared factory | **[FIX]** Phase C |
| 8 | Low | Label-persistence helpers duplicated across two large files | **[DEFER]** — not a clean dedup (see #13); entangled with per-class state |
| 13 | Medium | `collectLabelsFromHierarchy` differed between the two copies — panel version lacked the merge-history-preservation guard (potential label data-loss) | **[FIX]** — unified into `AnnotationLabelCollector` (merge-preserving), with regression tests |
| 9 | Medium | Six files exceed 1000 lines; god-object orchestration methods | **[FIX]** Phase D (safe extractions only) / **[DEFER]** (large splits) |
| 10 | Medium | Core IO/model logic largely untested; near-zero UI coverage | **[FIX]** Phase E added BinaryClassifierRegistry/GroundTruthIO/RobustStats/CsvUtils/FileSystemUtilities tests + LabelStore/Resampler regressions / **[DEFER]** (UI) |
| 11 | Low | No static analysis configured | **[FIX]** Phase E — SpotBugs wired, non-failing |
| 12 | — | arcsinh "NaN on negative input" | **[WONTFIX]** — not a bug (see below) |

---

## Correctness & numerics

### 1. `LabelStore` is not thread-safe — **High** — [FIX]
[model/LabelStore.java:27](src/main/java/qupath/ext/celltune/model/LabelStore.java#L27)
backs labels with a bare `LinkedHashMap`. The same instance is mutated from the JavaFX
thread (manual labelling), the training thread, and the image-switch listener. The
iterate-and-modify methods (`retainClasses` L152-154, `renameClass` L168,
`restoreMergedLabels` L188) and the streaming readers (`getClassNames` L108) can throw
`ConcurrentModificationException` if labelling overlaps a training run. CLAUDE.md already
documents the constraint ("LabelStore is not thread-safe"); this elevates it from a
convention to an enforced invariant.
**Fix (Phase B):** wrap in `Collections.synchronizedMap(new LinkedHashMap<>())` and guard
the compound read-modify methods on the map monitor. Add a concurrent put/iterate test.

### 2. Unguarded `project.getEntry()` dereferences — **investigated** — [WONTFIX]
`project.getEntry(imageData)` returns `null` when an image is opened without a project, so an
earlier pass flagged ~38 call sites as risky. **On audit, every site is already guarded** —
either by a null-check on the result (`if (entry != null)` / ternary) or by an early
`if (project == null) return;` upstream (e.g. [TrainingTileExtractor.java:151](src/main/java/qupath/ext/celltune/ui/TrainingTileExtractor.java#L151)
guards :172). The four originally-cited "unguarded" sites
([CompositeClassificationDialog.java:65](src/main/java/qupath/ext/celltune/ui/CompositeClassificationDialog.java#L65)/:225,
[DistanceMeasurementsDialog.java:100](src/main/java/qupath/ext/celltune/ui/DistanceMeasurementsDialog.java#L100),
[TrainingTileExtractor.java:172](src/main/java/qupath/ext/celltune/ui/TrainingTileExtractor.java#L172),
[ClassificationPanel.java:485](src/main/java/qupath/ext/celltune/ui/ClassificationPanel.java#L485))
are all correctly null-checked. No change; adding redundant guards would be noise. The
codebase is disciplined here.

### 3. `Resampler` does not validate label indices — **Medium** — [FIX]
[classifier/Resampler.java](src/main/java/qupath/ext/celltune/classifier/Resampler.java)
indexes per-class buckets by label value without bounds-checking. A corrupt label
(e.g. class `99` when `nClasses == 5`, possible after an out-of-sync class edit) throws a
bare `ArrayIndexOutOfBoundsException` deep in training rather than a diagnosable error.
**Fix (Phase B):** validate indices ∈ `[0, nClasses)` up front and fail with a clear message.

### 4. Silent catches — **Low** — [FIX, narrow]
Mostly a false positive: [ScatterPlotView.java:816](src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java#L816)
already `logger.error(...)`s and shows the user an actionable message, and the
[ProjectStateManager](src/main/java/qupath/ext/celltune/io/ProjectStateManager.java) load
paths already `logger.warn(...)` on every exception. The only genuinely silent paths are the
two `hasImageLabels(...)` cheap existence checks that `catch (IOException) { return false; }`.
**Fix (Phase B):** added a `logger.debug(...)` to both so a path-resolution failure is
diagnosable. Behaviour unchanged.

### 12. arcsinh on negative input — **investigated** — [WONTFIX]
An earlier pass flagged [FeatureNormalizer.java:124](src/main/java/qupath/ext/celltune/model/FeatureNormalizer.java#L124)
as emitting `NaN` for negative values. **This is not a bug.** The transform is
`log(x/c + sqrt(x²/c² + 1))`; the radicand `x²/c² + 1` is always ≥ 1, so `sqrt` never
produces `NaN`, and `x + sqrt(x²+1)` is strictly positive even for negative `x`. arcsinh is
*deliberately* defined for negatives — background-subtracted cytometry intensities are
routinely negative — so clamping to 0 (as the original suggestion proposed) would be
**scientifically incorrect**. No change. (Cofactor ≤ 0 is already validated, the only real
NaN source.)

---

## Duplication / shared helpers

### 5. Divergent robust-z implementations — **Low** — [FIX, carefully]
[model/PixelCohortAnalyzer.java:394](src/main/java/qupath/ext/celltune/model/PixelCohortAnalyzer.java#L394)
(`robustZ`) is NaN-aware and falls back to mean/std on a degenerate MAD, so an outlier above
a flat baseline is still surfaced. [model/CohortAnomalyAnalyzer.java:255](src/main/java/qupath/ext/celltune/model/CohortAnomalyAnalyzer.java#L255)
(`robustZScores`) is **not** NaN-aware and returns all-zeros when MAD < 1e-12. These are
genuinely different statistics, not an accidental copy.
**Fix (Phase C):** extract to `util/RobustStats` with **two named variants** so each call
site keeps its current behaviour — do not silently unify (would change anomaly output).

### 6. CSV escaping reimplemented three times — **Low** — [FIX]
[CellTableExporter](src/main/java/qupath/ext/celltune/io/CellTableExporter.java) quotes only
feature names; [ProjectSummaryCsvExporter](src/main/java/qupath/ext/celltune/io/ProjectSummaryCsvExporter.java)
and [PixelStatsCsvExporter](src/main/java/qupath/ext/celltune/io/PixelStatsCsvExporter.java)
quote all fields with `Locale.ROOT` numeric formatting. Inconsistent quoting risks malformed
CSV when a class/marker name contains a comma or quote.
**Fix (Phase C):** single `util/CsvUtils`; verify against existing `*CsvExporterTest`.

### 7. Ad-hoc background executors — **Low** — [FIX]
Daemon single-thread / bounded pools are re-created by hand in `ClassControlDialog`,
`ReviewController`, `DistanceMeasurementsDialog`, `IntensityHeatmapView`.
**Fix (Phase C):** a `util/BackgroundExecutors` factory for naming + daemon setup; call sites
keep their own lifecycle.

### 8. Duplicated label persistence — **Low** — [DEFER]
`collectLabelsFromAnnotations`, `persistCurrentImageSampledIds`, and
`persistReviewedLabelsByImage` exist in **both**
[CellTuneExtension.java](src/main/java/qupath/ext/celltune/CellTuneExtension.java) and
[ClassificationPanel.java](src/main/java/qupath/ext/celltune/ui/ClassificationPanel.java).
On inspection this is **not** a clean mechanical dedup: the `persist*` methods are entangled
with per-class instance state (`lastSampledCellImageMap`, `activeBinaryClassFilter()`,
`activeBinaryMarker`) and would need 4–5 context parameters threaded through, and the
`collectLabelsFromHierarchy` pair is genuinely divergent (see #13). Extracting an
`io/LabelPersistence` helper is still worthwhile but must be done with manual QuPath QA, so it
is **deferred** to keep this pass behavior-preserving.

### 13. `collectLabelsFromHierarchy` divergence — **Medium** — [FIX]
The shared-looking static `collectLabelsFromHierarchy(hierarchy, store, allowedClasses)` had
**two non-identical copies**:
- `CellTuneExtension` preserved merge history: before overwriting a detection's label it
  checked `cls.equals(LabelStore.innermostOriginal(existing))` and skipped, so a previously
  merged label was not clobbered by the bare annotation class.
- `ClassificationPanel` had **no such guard** — it called `store.setLabel(id, cls)`
  unconditionally. This copy runs inside `doTrain()` (label collection before training), so a
  user who merged classes and then retrained while leftover point annotations existed could
  silently overwrite merged labels with their pre-merge class — a label data-loss bug.

**Fixed:** both now delegate to one shared, merge-preserving collector,
[model/AnnotationLabelCollector.java](src/main/java/qupath/ext/celltune/model/AnnotationLabelCollector.java);
the two dead 2-arg overloads were removed and the now-unused `PathObjectTools` imports dropped.
Behaviour is covered by
[AnnotationLabelCollectorTest](src/test/java/qupath/ext/celltune/model/AnnotationLabelCollectorTest.java)
(merge-preservation, chained merge, overwrite-on-different-class, allowed-class filter,
area-annotations-ignored). This is the one DEFER that was promoted to FIX because it is a
correctness bug, and the unified logic is directly unit-testable without the UI.

---

## Structure (large files)

### 9. Six files exceed 1000 lines — **Medium**
| File | Lines | God-method |
|------|-------|-----------|
| `CellTuneExtension.java` | ~2.0k (was ~4.5k) | lifecycle + state I/O + dialog launchers; utility scripts, region export, project-prediction-summary, analysis-view launchers, import/export + binary-mode manager now extracted |
| `ClassificationPanel.java` | ~1.76k (was ~1.9k) | `doTrain()` cross-image pooling + batch apply → `TrainingOrchestrator`; pure feature-mapping/data-pooling already extracted |
| `ScatterPlotView.java` | ~1.6k (was ~2.0k) | `recompute()` ~200 lines; visual layer + math now extracted |
| `ProjectStateManager.java` | ~0.85k (was ~1.5k) | split into Prediction/Binary/Label/MarkerTable persistence helpers |
| `DualModelClassifier.java` | ~1.0k | `trainAndPredict()` ~380 lines |
| `ReviewController.java` | ~0.9k | tile-mode vs normal-mode divergence |

**Done [FIX]:**
- `FileSystemUtilities` extracted from `ProjectStateManager`: the pure
  `zipDirectory` / `deleteDirectoryRecursively` file operations now live in
  [io/FileSystemUtilities.java](src/main/java/qupath/ext/celltune/io/FileSystemUtilities.java)
  with direct unit tests; `ProjectStateManager` keeps thin delegating wrappers so internal
  callers and the existing reset test are unaffected.
- `UtilityScripts` extracted from `CellTuneExtension`: the five standalone Utility-Scripts-menu
  helpers (cell filtering, hierarchy resolution, annotation locking, measurement deletion,
  GeoJSON import) plus their private helpers moved verbatim to
  [UtilityScripts.java](src/main/java/qupath/ext/celltune/UtilityScripts.java) as static methods.
  The menu items now delegate. The only behavioural delta is the hierarchy-event **source**
  (a private sentinel instead of the extension instance) — verified safe: no listener filters
  by source, matching the existing `CohortClusterModel` pattern. `CellTuneExtension` shrank
  from ~4.5k to ~3.7k lines. **Needs a manual QuPath smoke test** of the five menu items, since
  these interactive scripts have no automated coverage.

**Done [FIX]:**
- `DualModelClassifier` → `PredictionBatcher`: the **three** near-identical chunked-prediction
  loops (in `trainAndPredict`, `predictOnly`, `predictAndCollect`) are unified into one
  [PredictionBatcher.predict](src/main/java/qupath/ext/celltune/classifier/PredictionBatcher.java)
  with the models supplied as injected `ChunkPredictor` callbacks and set-population via a
  `PredictionSink`. The shared FX-thread apply moved to `applyOnFxThreadBlocking`. Because the
  loop no longer holds classifier state, it is now **unit-tested with stub predictors**
  ([PredictionBatcherTest](src/test/java/qupath/ext/celltune/classifier/PredictionBatcherTest.java)) —
  closing the "no ML coverage" gap for the argmax→label, disagreement-count, chunking, and
  sink-population logic. `DualModelClassifier` dropped ~134 lines.

**Also done [FIX]:**
- `DualModelClassifier` → `TrainValMetricsComputer`: `computeTrainValMetrics` (80/20 stratified
  split → resample train fold → train eval copies → score) extracted to
  [TrainValMetricsComputer.java](src/main/java/qupath/ext/celltune/classifier/TrainValMetricsComputer.java)
  behind injected trainer/predictor callbacks, so it's unit-tested with stubs + a perfect
  predictor ([TrainValMetricsComputerTest](src/test/java/qupath/ext/celltune/classifier/TrainValMetricsComputerTest.java)).
  The shared `stratifiedSplit` moved there too (early-stopping calls it). Removed two dead
  helpers (`extractRowSubset`/`extractLabelSubset`). `DualModelClassifier` ~3.5k→ smaller again.

**Done [FIX]:**
- `exportAnnotationRegions` (OME-TIFF export) → [AnnotationRegionExporter.java](src/main/java/qupath/ext/celltune/AnnotationRegionExporter.java):
  the heaviest Utility-Scripts helper — the export dialog, the reflective `writeOmePyramid`/
  `applyBuilderOption` OME-writer calls, and the tileable polygon-masking `RoiMaskedServer` —
  moved verbatim out of `CellTuneExtension` into its own root-package class (mirroring the
  `UtilityScripts` move). The menu item delegates; behaviour preserved 1:1. `CellTuneExtension`
  drops ~3.7k → ~3.4k and sheds 19 now-dead imports (the AWT raster + image-server stack).
  Compiles + full test suite green. **Manual QuPath smoke test of the export still required** —
  the interactive OME-writer path has no automated coverage.

**[DEFER]** — left for a follow-up:
- `ProjectFileLayout` (path constants): low risk but low value; skipped to avoid churn.

**Also done [FIX]:**
- `ScatterPlotView` → `ScatterMath` + `ScatterPlotCanvas` — the largest deferred interactive
  split. The pure numerics (standardise / PCA / UMAP / subsample / point-in-polygon) moved to
  [model/ScatterMath.java](src/main/java/qupath/ext/celltune/model/ScatterMath.java), **now
  unit-tested** ([ScatterMathTest](src/test/java/qupath/ext/celltune/model/ScatterMathTest.java),
  10 cases) — closing the no-coverage gap for the embedding core (PCA/UMAP themselves stay out,
  they load native OpenBLAS/ARPACK, so they're QA-covered like `CohortClusterModel`). The whole
  visual layer — the `Canvas`, all drawing (axis/dots/selection outlines/legend) and the
  box/lasso/legend gestures — moved to
  [ui/ScatterPlotCanvas.java](src/main/java/qupath/ext/celltune/ui/ScatterPlotCanvas.java),
  behind a read-only `PlotModel` interface + gesture callbacks, so the drawing↔mouse geometry
  coupling stays internal and `ScatterPlotView` keeps **all** QuPath hierarchy/viewer mutation
  (behaviour preserved 1:1). The view drops ~2.2k → ~1.6k lines. This split was unblocked
  (vs. the rationale below) because the heavy interactive paths were **manually QA'd** in QuPath
  and the extracted math is directly unit-testable. A status-bar progress bar was also added so
  the control-locking assign/apply runs show legible progress.

**Also done [FIX]:**
- `ProjectStateManager` persistence split — extracted `PredictionPersistence` /
  `BinaryClassifierPersistence` / `LabelPersistence` / `MarkerTablePersistence` as focused
  package-private helpers behind delegating wrappers (behaviour-preserving), deleted the dead
  composite-rule/config persistence, ~1.6k → ~0.85k lines, round-trip tests added. See completed
  stage 13. (This is the per-image label-**file IO**; the #8 dedup of the entangled
  `collectLabelsFromAnnotations`/`persist*` methods across the two big UI/extension classes is still
  deferred — it needs per-class state threading + QuPath QA.)
- `ClassificationPanel.doTrain()` pure core — `FeatureMappingService` (feature index map + row align,
  also dedups a dead `CellTuneExtension` copy) and `DataPoolingService` (imported-row pooling)
  extracted and unit-tested. See completed stage 14.
- `ClassificationPanel.doTrain()` IO blocks — `ui/TrainingOrchestrator.poolLabelsFromOtherImages`
  (cross-image label pooling) and `applyToTargetImages` (parallel batch apply) extracted verbatim.
  See completed stage 15.

**[DEFER]** — larger decompositions, same rationale (near-zero UI coverage, need manual QuPath QA):
- `ClassificationPanel.doTrain()` residual orchestration — validation, feature prep, progress UI, the
  `trainAndPredict` call, classifier-state save and FX completion stay inline (diminishing returns to
  extract further; tightly bound to the panel's controls and the JavaFX lifecycle)
- `CellTuneExtension` → `BinaryClassifierManager` + `ReviewModeOrchestrator` + `ImageStateSync` + `MenuItemFactory`
  (in progress, ~4.5k → ~2.0k so far: **Project Prediction Summary** → `ProjectPredictionSummary` (stage 16),
  read-only **analysis-view launchers** → `AnalysisViews` (stage 17), **import/export** → `ImportExport`
  (stage 18), and **binary mode** → `BinaryClassifierManager` (stage 19) extracted; the remaining stateful
  pieces — review-mode, image-state-sync (`handleImageChange`), `showConfusions`/reset, and `MenuItemFactory`
  — remain)
- `ReviewController` → `TileModeStrategy` / `NormalModeStrategy` + `ReviewQueueManager`. Two
  self-contained concerns are **done**: the prefetch lifecycle →
  [ui/ImagePrefetcher.java](src/main/java/qupath/ext/celltune/ui/ImagePrefetcher.java), and the
  tile project-entry cleanup → [ui/TileEntryCleaner.java](src/main/java/qupath/ext/celltune/ui/TileEntryCleaner.java)
  (verbatim behavior, preserved snapshot timing). The **full tile/normal strategy split stays
  deferred** — investigation showed the manual-selection state (`selectedManualCellId`) is
  irreducibly cross-cutting (set by the tile selection listener, read by `labelAndNext` to pick
  which cell is labelled, cleared on navigation, and exposed to the UI toolbar), so a clean
  strategy boundary isn't possible without first adding queue/labeling test coverage. The
  failure modes (mislabeled cell, listener leak, orphaned entries) are data-affecting and not
  reliably caught by manual QA.

---

## Tests & tooling

### 10. Test coverage gaps — **Medium**
Untested core logic with clear seams. **Fixed (Phase E):** added
`BinaryClassifierRegistryTest` (sanitizeMarkerName / path-traversal),
`GroundTruthIOTest` (CSV import parsing, non-numeric→0, skip rules),
`RobustStatsTest`, `CsvUtilsTest`, `FileSystemUtilitiesTest`, plus regression tests on the
Phase-B fixes (`LabelStore` concurrency, `Resampler` validation).
**[DEFER]:** `ClassManager` (merge/undo) and `CohortClusterModel` need Project/PathObject
scaffolding; UI-render tests require a JavaFX harness / TestFX — both out of scope for this
pass. 21 of 23 UI classes remain untested.

### 11. No static analysis — **Low** — [FIX]
[build.gradle.kts](build.gradle.kts) had no SpotBugs/Checkstyle/ErrorProne.
**Fixed (Phase E):** SpotBugs (`com.github.spotbugs` plugin) is wired as a **non-failing**
reporting task (`ignoreFailures = true`, MEDIUM confidence, main sources only). Run
`./gradlew spotbugsMain` → `build/reports/spotbugs/main.html`. It is deliberately **not** part
of `check` until the baseline is triaged. Documented in CLAUDE.md. The first run produces a
baseline to work down over time — treat as a backlog, not a gate.

---

## Notes confirmed correct (no action)

- `CellFeatureExtractor` NaN→0 conversion and immutable feature-column ordering — intentional, tested.
- `CellTableExporter` NaN→"NA" CSV handling — tested.
- `DistanceMeasurementsDialog` STRtree self-exclusion — tested, matches the documented pitfall.
- Early-stopping 80/20 stratified split before resampling — correct per RISKS.md.
- `JvmModuleOpener` `java.base/java.lang` opening — required for Smile natives; correct.
