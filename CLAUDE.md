# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. It is the single source of truth for build, architecture, and conventions. (`AGENTS.md` is a stub pointing here so other agent tooling resolves it too.)

## What this is

A QuPath 0.7 extension for human-in-the-loop cell classification using dual-model active learning (XGBoost + LightGBM + optional Random Forest). **No Python dependency** — everything runs in Java/JavaFX. It trains two models on the same labels, flags cells where the models disagree, and surfaces those for manual review in an iterative train → review → retrain loop.

## Build & test

Requires **JDK 25** with `JAVA_HOME` set to it (QuPath 0.7 mandates Java 25; verify with `java -version` → starts with `25`). Use the bundled Gradle wrapper — no separate Gradle install needed.

```bash
# Linux/macOS
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
chmod +x gradlew
./gradlew clean compileJava
./gradlew test
./gradlew shadowJar    # → build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar
```

```powershell
# Windows
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat clean compileJava
.\gradlew.bat test
.\gradlew.bat shadowJar
```

- `shadowJar` produces the single fat JAR that bundles XGBoost4J + LightGBM4J; **install only this JAR** — partial classpath JARs break native model loading.
- **Run a single test class:** `./gradlew test --tests "qupath.ext.celltune.ui.DistanceMeasurementsDialogTest"` (use `--tests "*.methodName"` for a single method).
- `settings.gradle.kts` pins QuPath `0.7.0` and uses `foojay-resolver-convention` for JDK toolchain resolution; the `qupath-conventions` plugin pins `languageVersion=25`.
- ML deps (`build.gradle.kts`): `ml.dmlc:xgboost4j_2.13:2.1.4` (non-GPU) and `io.github.metarank:lightgbm4j:4.6.0-2`.

### Install in QuPath

Copy the fat JAR into the QuPath extensions folder and restart QuPath fully:

- Windows: `C:\Users\<you>\QuPath\v0.7\extensions\`
- Linux: `~/.local/share/QuPath/v0.7/extensions/`
- macOS: `~/Library/Application Support/QuPath/v0.7/extensions/`

Troubleshooting: build fails with toolchain errors → confirm `JAVA_HOME` points to JDK 25. No menu entries → confirm the new JAR is in the correct folder and QuPath was restarted fully. Native model issues → use the shadow JAR only, never partial classpath JARs.

## Architecture

Entry point: `src/main/java/qupath/ext/celltune/CellTuneExtension.java` — registers menus, docks the sidebar panel, and manages project-level state. Data flows as the active-learning loop: seed labels → train dual classifiers → inspect inter-model confusion → review disagreement cells → merge labels → retrain.

| Package | Key Classes | Purpose |
|---------|-------------|---------|
| `model/` | `CellFeatureExtractor`, `LabelStore`, `FeatureNormalizer`, `CellPrediction`, `CellTypeTable`, `PopulationSet`, `CohortAnomalyAnalyzer`, `CohortAnomalyReport`, `IntensityHeatmap` | Feature extraction, label storage, normalization, cell predictions, population definitions, cohort-level anomaly scoring, per-class mean marker-intensity heatmap computation |
| `classifier/` | `DualModelClassifier`, `XGBoostModel`, `LightGBMModel`, `RandomForestModel`, `CompositeClassifier`, `CompositeClassificationRule`, `ClassifierState`, `FeaturePruner`, `HyperparameterTuner`, `Resampler`, `ResamplingStrategy`, `TrainingMetrics`, `UncertaintySampler`, `ModelType` | ML training/inference (XGBoost, LightGBM, Random Forest), composite multi-marker rules, sampling, resampling, hyperparameter tuning |
| `gating/` | `GatingExpression`, `GatingRule` | Marker-based landmark gating (AST expression parser, multi-threshold cascade) |
| `ui/` | `ClassificationPanel`, `BinaryClassifierPanel`, `CompositeClassificationDialog`, `ClassControlDialog`, `PopulationPanel`, `ReviewController`, `ReviewToolbar`, `ManualLabelToolbar`, `FeatureImportanceView`, `FeatureSelectionPane`, `CellTableExportPane`, `TrainingMetricsView`, `ConfusionMatrixView`, `ValidationConfusionMatrixView`, `ProjectPredictionSummaryView`, `IntensityHeatmapView`, `ScatterPlotView`, `ProjectClusteringDialog`, `ClusterPreviewWindow`, `ChannelSelector`, `NormalizationPane`, `ImageSelectionPane`, `SelectionHighlightOverlay`, `TrainingTileExtractor`, `DistanceMeasurementsDialog` | All JavaFX panels, dialogs, and toolbars. `ScatterPlotView` is the interactive PCA/UMAP + k-means cell scatter plot (annotation/class gating, cluster-marker subsetting, cluster→class assignment, legend click-to-select); it launches `ProjectClusteringDialog` (the **Project Clustering…** button — there is no longer a separate menu item), the project-wide batch variant that fits one k-means on a pooled sample and assigns every cell cohort-wide to its nearest centroid via `entry.readImageData()`/`saveImageData()`, never holding the whole project in memory; `ClusterPreviewWindow` is the non-interactive PCA/UMAP scatter preview of that pooled sample. See [USER_GUIDE.md §11](USER_GUIDE.md#11-cell-scatter-plot--clustering--gating) |
| `io/` | `ProjectStateManager`, `BinaryClassifierRegistry`, `ClassManager`, `CellTableExporter`, `GroundTruthIO`, `MarkerTableImporter`, `ProjectSummaryCsvExporter` | Export (cell table, ground truth CSV, project summary CSV) and import (marker table, project state); binary classifier registry tracks named classifiers across a project; `ClassManager` backs the Class Control dialog (add/delete/merge/undo-merge on PathClasses + persisted label files) |
| `util/` | `JvmModuleOpener` | Runtime JVM helpers. `JvmModuleOpener.ensureJavaLangOpen()` opens `java.base/java.lang` to unnamed modules at startup (called from `CellTuneExtension.installExtension`) so Smile's native PCA/UMAP load without `--add-opens` launch flags |

The 5-tier weighted uncertainty sampling that builds the review queue lives in `classifier/UncertaintySampler`; see [USER_GUIDE.md](USER_GUIDE.md) for the end-user workflows and [RISKS.md](RISKS.md) for the full risk register.

## QuPath 0.7 API notes

Verified against QuPath 0.7.0:

- Extension class implements `qupath.lib.gui.extensions.QuPathExtension` — override `installExtension(QuPathGUI)`, `getName()`, `getDescription()`, `getQuPathVersion()`.
- Add menu items via `qupath.getMenu("Extensions>" + EXTENSION_NAME, true)` (returns/creates a `Menu`).
- All UI text comes from `ResourceBundle.getBundle("qupath.ext.celltune.ui.strings")`.
- Read cell measurements via `PathObject.getMeasurementList().get(name)` — returns `NaN` if missing (default to `0f`).
- Use `qupath.fx.dialogs.Dialogs` (not raw JavaFX `Alert`) for dialogs/notifications.
- Use `PathPrefs.createPersistentPreference(...)` for prefs that survive sessions.
- Non-modal windows: `stage.initOwner(qupath.getStage())` + `Modality.NONE` so QuPath stays usable.

## Key conventions

- **UI thread safety**: Background training/prediction threads must batch all QuPath object updates via `Platform.runLater()`. Never call `setPathClass()` or fire hierarchy events from a background thread directly.
- **QuPath public API only**: Use only `qupath.lib.*` public APIs. No internal or deprecated APIs. APIs to watch: `PathObjectSelectionModel`, `PathClass.fromString()`, `project.getEntry(imageData)`, `qupath.getAnalysisTabPane()`.
- **Null-check project entries**: `project.getEntry(imageData)` returns null when an image is open without a project — always null-check.
- **LabelStore is not thread-safe**: `LabelStore` uses a plain `LinkedHashMap`. Do not access it concurrently from training threads and UI threads.
- **Serialization**: Classifier state and per-image labels are saved as JSON+Base64 via Gson to `<project>/celltune/`. Binary classifiers are stored under `<project>/celltune/binary/` and tracked by `BinaryClassifierRegistry` via `binary-registry.json`.
- **Feature column ordering**: Feature vectors must use the same column ordering at training and inference time. `CellFeatureExtractor` handles this — do not bypass it.
- **Marker name sanitization**: `BinaryClassifierRegistry.sanitizeMarkerName()` is called internally on all marker names to prevent path traversal and ensure safe filesystem use. Do not write marker names to disk paths without going through this method.
- **Class Control background tasks**: Long-running operations in `ClassControlDialog` (delete/merge/undo) run on a single-threaded `bgExecutor` and must re-enable their trigger button in a `finally` block. Failing to do so leaves the button greyed out indefinitely. Catch `Exception` (not just `IOException`) so unchecked failures surface as an error dialog instead of silently disabling the UI.

## Known pitfalls

- **LightGBM SHAP crashes the JVM**: Calling `LGBMBooster.predictForMat(... C_API_PREDICT_CONTRIB)` causes a fatal SIGSEGV. `LightGBMModel.computeMeanAbsShap()` exists but is intentionally not called. Do not re-enable LightGBM SHAP without testing in isolation first.
- **XGBoost4J version lock**: The Java API changed significantly between 1.x, 2.x, and 3.x. The project uses `xgboost4j_2.13:2.1.4` (non-GPU). Do not upgrade without thorough testing — `predictContrib()` (TreeSHAP) behaviour and method signatures may change across major versions. `build.gradle.kts` is the authoritative source for the pinned version.
- **Binary classification SHAP**: For exactly 2 classes, feature importance bars are identical for both classes — this is expected (see [RISKS.md](RISKS.md#24-binary-classification-shap-display)).
- **CompositeClassificationRule validation**: Rules enforce a max of 128 conditions and a max rule name length of 120 characters. Marker names in rules are validated against the live `BinaryClassifierRegistry` at build time.
- **Smile native PCA/UMAP need `java.base/java.lang` opened**: Smile's `PCA` (`Matrix.svd` → OpenBLAS) and `UMAP` (spectral init → ARPACK) load native libraries through JavaCPP, which reflects into `java.lang.Runtime`. Under the Java module system this throws `InaccessibleObjectException` (wrapped in `ExceptionInInitializerError`, then `NoClassDefFoundError` on retries — both `LinkageError`, NOT `Exception`) unless the JVM was started with `--add-opens=java.base/java.lang=ALL-UNNAMED`. `JvmModuleOpener.ensureJavaLangOpen()` (called from `installExtension`) opens it programmatically at startup via `sun.misc.Unsafe` + `IMPL_LOOKUP` → `Module.implAddOpensToAllUnnamed`, so users need no launch flags. This MUST run before any Smile native class is first touched — once `arpack.<clinit>` fails it is poisoned for the JVM's lifetime. `ScatterPlotView` also catches `LinkageError` (not just `Exception`) around the UMAP/embedding call and falls back to PCA as defence-in-depth. The `Unsafe` accessors are deprecated-for-removal; fine through JDK 25, but if a future JDK removes them the opener returns `false` and the PCA fallback / `--add-opens` hint take over.
- **Same-class nearest-neighbour distances must stay O(n log n) AND must self-exclude**: `DistanceMeasurementsDialog.sameClassNearestNeighbourDistances()` uses a JTS `STRtree`. JTS's 3-arg `nearestNeighbour(env, item, ItemDistance)` does NOT automatically exclude the query item — the `ItemDistance` callback must compare item references and return `+Infinity` for a self-match (we use unique `Object` markers per index so reference equality is meaningful). Without this, every cell's "Distance to other X" comes back as 0. Do not replace the STRtree path with a brute-force pairwise loop — at 500k+ cells per image the O(n²) variant takes minutes to hours per class. Annotation and cross-class distances use QuPath's `DistanceTools`, which is already spatially indexed; only the same-class path is owned by this extension. Tests: `ui/DistanceMeasurementsDialogTest`.

## Tests

Unit tests live under `src/test/java/qupath/ext/celltune/`, mirroring the main package structure. Run all with `./gradlew test`.

| Test class | Covers |
|-----------|--------|
| `classifier/CompositeClassificationRuleTest` | Rule construction, polarity parsing, serialization |
| `classifier/ResamplerTest` | Resampling strategies |
| `classifier/TrainingMetricsTest` | Metrics calculation |
| `gating/GatingExpressionTest`, `GatingRuleTest` | AST parsing and gating evaluation |
| `io/ProjectStateManagerBinaryGroundTruthTest` | Binary ground truth export/import round-trip |
| `io/CellTableExporterTest` | Cell table CSV: optional polygon column (micron vs pixel), feature column selection, NA for missing measurements |
| `io/ProjectSummaryCsvExporterTest` | CSV output format |
| `model/CellPredictionTest`, `CellTypeTableTest`, `CohortAnomalyAnalyzerTest`, `FeatureNormalizerTest`, `IntensityHeatmapTest`, `LabelStoreTest`, `PopulationSetTest` | Core model logic |
| `ui/DistanceMeasurementsDialogTest` | STRtree-based same-class nearest-neighbour distances: self-exclusion, NaN handling, brute-force agreement on random clouds |

## Commits

Commit messages in this repo are freeform — no Conventional Commits enforcement and no pre-commit hooks.

## Clarifying questions

Always ask any questions required.
