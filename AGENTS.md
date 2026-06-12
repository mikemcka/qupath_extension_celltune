# CellTune QuPath Extension — Agent Instructions

A QuPath 0.7 extension for human-in-the-loop cell classification using dual-model active learning (XGBoost + LightGBM + optional Random Forest). No Python dependency — everything runs in Java/JavaFX.

## Build

**Requires:** JDK 25 (see [README.md](README.md#prerequisites))

```bash
export JAVA_HOME=/path/to/jdk-25   # adjust to your install
./gradlew shadowJar
# Output: build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar
```

- The `shadowJar` task produces a single fat JAR bundling XGBoost4J and LightGBM4J.
- The Gradle wrapper (`gradlew`) is included — no separate Gradle install needed.
- `settings.gradle.kts` pins QuPath version (`0.7.0`) and uses the `foojay-resolver-convention` plugin for JDK toolchain resolution.
- ML dependencies (from `build.gradle.kts`): `ml.dmlc:xgboost4j_2.13:2.1.4` and `io.github.metarank:lightgbm4j:4.6.0-2`.

## Architecture

Entry point: `src/main/java/qupath/ext/celltune/CellTuneExtension.java` — registers menus, docks the sidebar panel, and manages project-level state.

| Package | Key Classes | Purpose |
|---------|-------------|---------|
| `model/` | `CellFeatureExtractor`, `LabelStore`, `FeatureNormalizer`, `CellPrediction`, `CellTypeTable`, `PopulationSet`, `CohortAnomalyAnalyzer`, `CohortAnomalyReport` | Feature extraction, label storage, normalization, cell predictions, population definitions, cohort-level anomaly scoring |
| `classifier/` | `DualModelClassifier`, `XGBoostModel`, `LightGBMModel`, `RandomForestModel`, `CompositeClassifier`, `CompositeClassificationRule`, `ClassifierState`, `FeaturePruner`, `HyperparameterTuner`, `Resampler`, `ResamplingStrategy`, `TrainingMetrics`, `UncertaintySampler`, `ModelType` | ML training/inference (XGBoost, LightGBM, Random Forest), composite multi-marker rules, sampling, resampling, hyperparameter tuning |
| `gating/` | `GatingExpression`, `GatingRule` | Marker-based landmark gating (AST expression parser, multi-threshold cascade) |
| `ui/` | `ClassificationPanel`, `BinaryClassifierPanel`, `CompositeClassificationDialog`, `ClassControlDialog`, `PopulationPanel`, `ReviewController`, `ReviewToolbar`, `ManualLabelToolbar`, `FeatureImportanceView`, `FeatureSelectionPane`, `TrainingMetricsView`, `ConfusionMatrixView`, `ValidationConfusionMatrixView`, `ProjectPredictionSummaryView`, `ChannelSelector`, `NormalizationPane`, `ImageSelectionPane`, `SelectionHighlightOverlay`, `TrainingTileExtractor`, `DistanceMeasurementsDialog` | All JavaFX panels, dialogs, and toolbars |
| `io/` | `ProjectStateManager`, `BinaryClassifierRegistry`, `ClassManager`, `CellTableExporter`, `GroundTruthIO`, `MarkerTableImporter`, `ProjectSummaryCsvExporter` | Export (cell table, ground truth CSV, project summary CSV) and import (marker table, project state); binary classifier registry tracks named classifiers across a project; `ClassManager` backs the Class Control dialog (add/delete/merge/undo-merge on PathClasses + persisted label files) |

See [USER_GUIDE.md](USER_GUIDE.md) for end-user workflows and [RISKS.md](RISKS.md) for the full risk register.

## QuPath 0.7 API notes

Verified against QuPath 0.7.0:

- Extension class implements `qupath.lib.gui.extensions.QuPathExtension` — override `installExtension(QuPathGUI)`, `getName()`, `getDescription()`, `getQuPathVersion()`.
- Add menu items via `qupath.getMenu("Extensions>" + EXTENSION_NAME, true)` (returns/creates a `Menu`).
- All UI text comes from `ResourceBundle.getBundle("qupath.ext.celltune.ui.strings")`.
- Read cell measurements via `PathObject.getMeasurementList().get(name)` — returns `NaN` if missing (default to `0f`).
- Use `qupath.fx.dialogs.Dialogs` (not raw JavaFX `Alert`) for dialogs/notifications.
- Use `PathPrefs.createPersistentPreference(...)` for prefs that survive sessions.
- Non-modal windows: `stage.initOwner(qupath.getStage())` + `Modality.NONE` so QuPath stays usable.
- Build pins Java 25 via the `qupath-conventions` plugin (`languageVersion=25`); `foojay-resolver-convention` in `settings.gradle.kts` can auto-provision the toolchain. See [BUILD.md](BUILD.md).

## Key Conventions

- **UI thread safety**: Background training/prediction threads must batch all QuPath object updates via `Platform.runLater()`. Never call `setPathClass()` or fire hierarchy events from a background thread directly.
- **QuPath public API only**: Use only `qupath.lib.*` public APIs. No internal or deprecated APIs. APIs to watch: `PathObjectSelectionModel`, `PathClass.fromString()`, `project.getEntry(imageData)`, `qupath.getAnalysisTabPane()`.
- **Null-check project entries**: `project.getEntry(imageData)` returns null when an image is open without a project — always null-check.
- **LabelStore is not thread-safe**: `LabelStore` uses a plain `LinkedHashMap`. Do not access it concurrently from training threads and UI threads.
- **Serialization**: Classifier state and per-image labels are saved as JSON+Base64 via Gson to `<project>/celltune/`. Binary classifiers are stored under `<project>/celltune/binary/` and tracked by `BinaryClassifierRegistry` via `binary-registry.json`.
- **Feature column ordering**: Feature vectors must use the same column ordering at training and inference time. `CellFeatureExtractor` handles this — do not bypass it.
- **Marker name sanitization**: `BinaryClassifierRegistry.sanitizeMarkerName()` is called internally on all marker names to prevent path traversal and ensure safe filesystem use. Do not write marker names to disk paths without going through this method.
- **Class Control background tasks**: Long-running operations in `ClassControlDialog` (delete/merge/undo) run on a single-threaded `bgExecutor` and must re-enable their trigger button in a `finally` block. Failing to do so leaves the button greyed out indefinitely. Catch `Exception` (not just `IOException`) so unchecked failures surface as an error dialog instead of silently disabling the UI.

## Known Pitfalls

- **LightGBM SHAP crashes the JVM**: Calling `LGBMBooster.predictForMat(... C_API_PREDICT_CONTRIB)` causes a fatal SIGSEGV. `LightGBMModel.computeMeanAbsShap()` exists but is intentionally not called. Do not re-enable LightGBM SHAP without testing in isolation first.
- **XGBoost4J version lock**: The Java API changed significantly between 1.x, 2.x, and 3.x. The project uses `xgboost4j_2.13:2.1.4` (non-GPU). Do not upgrade without thorough testing — `predictContrib()` (TreeSHAP) behaviour and method signatures may change across major versions.
- **Binary classification SHAP**: For exactly 2 classes, feature importance bars are identical for both classes — this is expected (see [RISKS.md](RISKS.md#24-binary-classification-shap-display)).
- **CompositeClassificationRule validation**: Rules enforce a max of 128 conditions and a max rule name length of 120 characters. Marker names in rules are validated against the live `BinaryClassifierRegistry` at build time.
- **Same-class nearest-neighbour distances must stay O(n log n) AND must self-exclude**: `DistanceMeasurementsDialog.sameClassNearestNeighbourDistances()` uses a JTS `STRtree`. JTS's 3-arg `nearestNeighbour(env, item, ItemDistance)` does NOT automatically exclude the query item — the `ItemDistance` callback must compare item references and return `+Infinity` for a self-match (we use unique `Object` markers per index so reference equality is meaningful). Without this, every cell's "Distance to other X" comes back as 0. Do not replace the STRtree path with a brute-force pairwise loop — at 500k+ cells per image the O(n²) variant takes minutes to hours per class. Annotation and cross-class distances use QuPath's `DistanceTools`, which is already spatially indexed; only the same-class path is owned by this extension. Tests: `ui/DistanceMeasurementsDialogTest`.

## Tests

Unit tests live under `src/test/java/qupath/ext/celltune/` mirroring the main package structure:

| Test class | Covers |
|-----------|--------|
| `classifier/CompositeClassificationRuleTest` | Rule construction, polarity parsing, serialization |
| `classifier/ResamplerTest` | Resampling strategies |
| `classifier/TrainingMetricsTest` | Metrics calculation |
| `gating/GatingExpressionTest`, `GatingRuleTest` | AST parsing and gating evaluation |
| `io/ProjectStateManagerBinaryGroundTruthTest` | Binary ground truth export/import round-trip |
| `io/ProjectSummaryCsvExporterTest` | CSV output format |
| `model/CellPredictionTest`, `CellTypeTableTest`, `CohortAnomalyAnalyzerTest`, `FeatureNormalizerTest`, `LabelStoreTest`, `PopulationSetTest` | Core model logic |
| `ui/DistanceMeasurementsDialogTest` | STRtree-based same-class nearest-neighbour distances: self-exclusion, NaN handling, brute-force agreement on random clouds |

Run all tests with `./gradlew test`.

Full risk register: [RISKS.md](RISKS.md)
