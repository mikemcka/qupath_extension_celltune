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

## Summary

| # | Severity | Finding | Disposition |
|---|----------|---------|-------------|
| 1 | High | `LabelStore` shared `LinkedHashMap` is unsynchronised; mutated from UI, training, and image-switch threads | **[FIX]** Phase B |
| 2 | Medium | Unguarded `project.getEntry()` dereferences at a subset of ~38 call sites | **[FIX]** Phase B (audited subset) |
| 3 | Medium | `Resampler` does not validate class indices → `ArrayIndexOutOfBoundsException` on corrupt labels | **[FIX]** Phase B |
| 4 | Medium | Swallow-all `catch (Throwable)` / silent `catch` hide state-corruption causes | **[FIX]** Phase B (add logging) |
| 5 | Low | Robust-z math duplicated with **divergent** behaviour across two analyzers | **[FIX]** Phase C (preserve both behaviours) |
| 6 | Low | CSV escaping reimplemented three times with inconsistent quoting | **[FIX]** Phase C |
| 7 | Low | Background executors created ad hoc in 4+ classes; no shared factory | **[FIX]** Phase C |
| 8 | Low | Label-persistence helpers triplicated across two large files | **[FIX]** Phase C |
| 9 | Medium | Six files exceed 1000 lines; god-object orchestration methods | **[FIX]** Phase D (safe extractions only) / **[DEFER]** (large splits) |
| 10 | Medium | Core IO/model logic largely untested; near-zero UI coverage | **[FIX]** Phase E (logic tests) / **[DEFER]** (UI) |
| 11 | Low | No static analysis configured | **[FIX]** Phase E (SpotBugs, non-failing) |
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

### 2. Unguarded `project.getEntry()` dereferences — **Medium** — [FIX]
`project.getEntry(imageData)` returns `null` when an image is opened without a project.
There are ~38 call sites; **most are already guarded** (`project != null ? project.getEntry(...) : null`).
The unguarded sites that immediately dereference the result include
[CompositeClassificationDialog.java:65](src/main/java/qupath/ext/celltune/ui/CompositeClassificationDialog.java#L65)
& :225, [DistanceMeasurementsDialog.java:100](src/main/java/qupath/ext/celltune/ui/DistanceMeasurementsDialog.java#L100),
[TrainingTileExtractor.java:172](src/main/java/qupath/ext/celltune/ui/TrainingTileExtractor.java#L172),
[ClassificationPanel.java:485](src/main/java/qupath/ext/celltune/ui/ClassificationPanel.java#L485).
See also [.planning/codebase/CONCERNS.md](.planning/codebase/CONCERNS.md) which tracks the
`CellTuneExtension` prediction-path sites.
**Fix (Phase B):** audit; add a guarded helper and apply only to the unguarded sites.
Leave already-guarded sites untouched.

### 3. `Resampler` does not validate label indices — **Medium** — [FIX]
[classifier/Resampler.java](src/main/java/qupath/ext/celltune/classifier/Resampler.java)
indexes per-class buckets by label value without bounds-checking. A corrupt label
(e.g. class `99` when `nClasses == 5`, possible after an out-of-sync class edit) throws a
bare `ArrayIndexOutOfBoundsException` deep in training rather than a diagnosable error.
**Fix (Phase B):** validate indices ∈ `[0, nClasses)` up front and fail with a clear message.

### 4. Swallow-all / silent catches obscure failures — **Medium** — [FIX]
[ScatterPlotView.java:816](src/main/java/qupath/ext/celltune/ui/ScatterPlotView.java#L816)
catches `Throwable` around the embedding fallback (separate from the legitimate
`LinkageError` catch at L764), and several load paths in
[ProjectStateManager.java](src/main/java/qupath/ext/celltune/io/ProjectStateManager.java)
return `null` on a bare `catch`. The fallback behaviour is correct, but a silent failure
makes state-corruption undebuggable for a researcher in the field.
**Fix (Phase B):** keep the fallbacks; add warning-level logging with context. No behaviour
change beyond logging.

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

### 8. Triplicated label persistence — **Low** — [FIX]
`collectLabelsFromAnnotations`, `persistCurrentImageSampledIds`, and
`persistReviewedLabelsByImage` exist in **both**
[CellTuneExtension.java](src/main/java/qupath/ext/celltune/CellTuneExtension.java) and
[ClassificationPanel.java](src/main/java/qupath/ext/celltune/ui/ClassificationPanel.java).
**Fix (Phase C):** extract to one `io/LabelPersistence` helper; both delegate. Highest-value,
cleanly testable dedup.

---

## Structure (large files)

### 9. Six files exceed 1000 lines — **Medium**
| File | Lines | God-method |
|------|-------|-----------|
| `CellTuneExtension.java` | ~3.8k | lifecycle + state I/O + 16 dialog launchers + utility scripts |
| `ClassificationPanel.java` | ~1.8k | `doTrain()` ~600 lines |
| `ScatterPlotView.java` | ~2.0k | `recompute()` ~200 lines, scope divergence |
| `ProjectStateManager.java` | ~1.5k | 30+ load/save methods, mixed concerns |
| `DualModelClassifier.java` | ~1.0k | `trainAndPredict()` ~380 lines |
| `ReviewController.java` | ~0.9k | tile-mode vs normal-mode divergence |

**This pass [FIX]** — only safe, compile-checkable, UI-free extractions (Phase D):
`PredictionBatcher` + `TrainValMetricsComputer` out of `DualModelClassifier`;
`FileSystemUtilities` + `ProjectFileLayout` out of `ProjectStateManager`;
`UtilityScripts` out of `CellTuneExtension`.

**[DEFER]** — larger decompositions left for a follow-up with manual QuPath QA, because there
is near-zero UI test coverage to catch regressions:
- `ClassificationPanel.doTrain()` → `TrainingOrchestrator` + `DataPoolingService` + `FeatureMappingService`
- `ScatterPlotView` → `ScatterPlotModel` + `EmbeddingEngine` + `ScopeManager` + `ClusterAssignmentEngine` + `DragSelectionHandler`
- `ProjectStateManager` → `ClassifierStatePersistence` + `LabelPersistence` + `PredictionPersistence` + `BinaryClassifierPersistence`
- `CellTuneExtension` → `BinaryClassifierManager` + `ReviewModeOrchestrator` + `ImageStateSync` + `MenuItemFactory`
- `ReviewController` → `TileModeStrategy` / `NormalModeStrategy` + `ReviewQueueManager` + `ImagePrefetcher`

---

## Tests & tooling

### 10. Test coverage gaps — **Medium**
Untested core logic with clear seams: `ClassManager` (merge/undo), `BinaryClassifierRegistry.sanitizeMarkerName`
(path-traversal), `GroundTruthIO` round-trip, `CohortClusterModel`. 21 of 23 UI classes have
no tests.
**Fix (Phase E):** add logic tests for the above plus the new utilities. **[DEFER]** UI-render
tests (require a JavaFX harness / TestFX — out of scope for this pass).

### 11. No static analysis — **Low** — [FIX]
[build.gradle.kts](build.gradle.kts) has no SpotBugs/Checkstyle/ErrorProne.
**Fix (Phase E):** wire SpotBugs as a **non-failing** reporting task to establish a baseline;
document how to run it in CLAUDE.md.

---

## Notes confirmed correct (no action)

- `CellFeatureExtractor` NaN→0 conversion and immutable feature-column ordering — intentional, tested.
- `CellTableExporter` NaN→"NA" CSV handling — tested.
- `DistanceMeasurementsDialog` STRtree self-exclusion — tested, matches the documented pitfall.
- Early-stopping 80/20 stratified split before resampling — correct per RISKS.md.
- `JvmModuleOpener` `java.base/java.lang` opening — required for Smile natives; correct.
