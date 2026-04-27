# External Integrations

**Analysis Date:** 2026-04-27

## QuPath Extension Integration

**Service Loader Registration:**
- File: `src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension`
- Content: `qupath.ext.celltune.CellTuneExtension`
- QuPath discovers the extension at startup via `java.util.ServiceLoader`; no manual registration needed.

**Extension Entry Point:**
- Class: `qupath.ext.celltune.CellTuneExtension` implements `qupath.lib.gui.extensions.QuPathExtension`
- Method: `installExtension(QuPathGUI qupath)` — called once by QuPath at load time
- Guards against double-install with `private boolean isInstalled`
- Reports `Version.parse("v0.7.0")` via `getQuPathVersion()` for compatibility checking
- Registers a persistent preference (`celltune.enabled`) via `PathPrefs.createPersistentPreference()`

**Menu Registration:**
- `qupath.getMenu("Extensions>" + EXTENSION_NAME, true)` — creates or retrieves the `Extensions > CellTune` submenu
- Menu items registered:
  - `Classify...` — opens `ClassificationPanel` workflow
  - `Review...` — enters review mode (`ReviewController`)
  - `Manual Label...` — shows `ManualLabelToolbar`
  - `Confusions...` — shows `ConfusionMatrixView`
  - `Feature Importance...` — shows SHAP bar chart
  - `Normalise Features` — opens `NormalizationPane`
  - `Feature Selection...` — opens `FeatureSelectionPane`
  - `Export Cell Table...` — triggers `CellTableExporter`
  - `Export AnnData (CSV + H5AD script)` — triggers `AnnDataExporter`
  - `Import Marker Table...` — triggers `MarkerTableImporter`
  - `Auto Landmark (Gating)` — runs `AutoLandmarker`
  - `Export Ground Truth...` / `Import Ground Truth...` — triggers `GroundTruthIO`
- All items have `disableProperty().bind(enableExtensionProperty.not())` — disabled when extension is toggled off in Preferences

**Docked Panel:**
- `ClassificationPanel extends javafx.scene.layout.VBox`
- Docked via `qupath.getAnalysisTabPane().getTabs().add(new Tab(EXTENSION_NAME, titledPane))`
- Wrapped in a `TitledPane` (collapsible, initially collapsed)
- Callback wiring: `classificationPanel.setOnLabelStoreChanged()`, `setOnPredAllChanged()`, `setOnAgreementRatesChanged()`, `setOnSampledCellsChanged()`, `setOnClassifierChanged()`, `setAutoClassifyCallback()`

**Image Change Listener:**
- `qupath.imageDataProperty().addListener(imageDataListener)` — saves labels for the old image, resets transient prediction state, loads persisted labels for the new image
- Listener reference stored in `CellTuneExtension.imageDataListener` (type: `ChangeListener<ImageData<BufferedImage>>`)

---

## XGBoost4J Integration

**Artifact:** `ml.dmlc:xgboost4j-gpu_2.13:2.1.4`
**Source file:** `src/main/java/qupath/ext/celltune/classifier/XGBoostModel.java`

**Training API:**
```java
DMatrix trainMat = new DMatrix(float[] flatData, int nSamples, int nFeatures, Float.NaN);
trainMat.setLabel(float[] labels);
Map<String, Object> params = new LinkedHashMap<>();  // objective, eval_metric, max_depth, eta, ...
Booster booster = XGBoost.train(trainMat, params, numRounds, watches, null, null);
trainMat.dispose();  // must dispose to free native memory
```
- Binary: `objective=binary:logistic, eval_metric=logloss`
- Multiclass: `objective=multi:softprob, num_class=N, eval_metric=mlogloss`
- Feature names set post-training: `booster.setFeatureNames(String[])`, `booster.setAttr("class_names", ...)`

**Prediction API:**
```java
DMatrix predMat = new DMatrix(float[] flatData, nSamples, nFeatures, Float.NaN);
float[][] raw = booster.predict(predMat);   // [nSamples][1] for binary, [nSamples][nClasses] for multiclass
predMat.dispose();
```
- Binary result shape `[n][1]` is expanded to `[n][2]` in `XGBoostModel.predictProba()`

**SHAP / TreeSHAP:**
```java
// booster.predictContrib(DMatrix, 0) — public API in XGBoost4J 3.x
float[][] raw = booster.predictContrib(dmat, 0);
// Binary:     raw[nSamples][nFeatures + 1]  (last column = bias)
// Multiclass: raw[nSamples][nClasses * (nFeatures + 1)]
```
- Called from `XGBoostModel.computeMeanAbsShap()` and dispatched from `DualModelClassifier.computeFeatureImportance()`
- Capped at `MAX_SHAP_SAMPLES = 5_000` cells (random sample with seed 42) for performance

**GPU / CUDA Support:**
- First probes CUDA with a 2-sample training run using `device=cuda, tree_method=hist`
- If `XGBoostError` is thrown, falls back to `device=cpu, tree_method=hist`
- Real training params set in `XGBoostModel.buildParams()` and overridden by `attemptGpuTraining()`

**Serialization:**
```java
byte[] bytes = booster.toByteArray();                            // serialize
Booster b = XGBoost.loadModel(new ByteArrayInputStream(bytes)); // deserialize
```
- Bytes stored as Base64 in `ProjectStateManager.SavedState.xgboostModelBase64`

**Feature Name Sanitization:**
- `XGBoostModel.sanitiseFeatureName(String)` — replaces characters XGBoost4J rejects: µ, μ, `^`, `:`, `/`, spaces, `[`, `]`, `<`, `>`

---

## LightGBM4J Integration

**Artifact:** `io.github.metarank:lightgbm4j:4.6.0-2`
**Source file:** `src/main/java/qupath/ext/celltune/classifier/LightGBMModel.java`

**Training API:**
```java
LGBMDataset dataset = LGBMDataset.createFromMat(float[] flatData, nSamples, nFeatures, true, "", null);
dataset.setFeatureNames(String[] safeNames);
dataset.setField("label", float[] labels);
String params = "objective=binary metric=binary_logloss max_depth=... ...";
LGBMBooster booster = LGBMBooster.create(dataset, params);
for (int i = 0; i < numRounds; i++) booster.updateOneIter();
dataset.close();  // booster retains its own copy
```
- Binary: `objective=binary, metric=binary_logloss`
- Multiclass: `objective=multiclass, metric=multi_logloss, num_class=N`
- Other fixed params: `bagging_freq=1, feature_fraction=0.8, min_gain_to_split=10, seed=42, verbosity=-1`
- `num_threads` set to `Runtime.getRuntime().availableProcessors()`

**Prediction API:**
```java
double[] raw = booster.predictForMat(float[] flatData, nSamples, nFeatures, true,
                                     PredictionType.C_API_PREDICT_NORMAL);
// Binary:     raw.length == nSamples  (P(class=1) per sample)
// Multiclass: raw.length == nSamples * nClasses  (row-major flat)
```
- Result converted to `float[][]` in `LightGBMModel.predictProba()`

**SHAP / TreeSHAP:**
```java
double[] raw = booster.predictForMat(flatData, nSamples, nFeatures, true,
                                     PredictionType.C_API_PREDICT_CONTRIB);
// Binary:     length = nSamples * (nFeatures + 1)
// Multiclass: length = nSamples * nClasses * (nFeatures + 1)  (class-major)
```
- Implemented in `LightGBMModel.computeMeanAbsShap()`.
- **IMPORTANT: `LGBMModel.computeMeanAbsShap()` is intentionally NOT called from `DualModelClassifier.computeFeatureImportance()`.** Only XGBoost TreeSHAP is used for feature importance display. The LightGBM SHAP path (`C_API_PREDICT_CONTRIB`) is known to cause a JVM crash (SIGSEGV in the native LightGBM library) in certain versions/configurations; callers should avoid invoking it until the native issue is resolved.

**GPU Support:**
- `device_type=gpu` appended to params string on first attempt
- Any `Exception` during GPU `create()` or `updateOneIter()` triggers CPU retry (booster closed and recreated)

**Resource Management:**
- `LightGBMModel.close()` calls `booster.close()` to release native JNI resources
- Called in `DualModelClassifier` when models are replaced or classifier is reset

**Serialization:**
```java
String modelStr = booster.saveModelToString(0, 0, LGBMBooster.FeatureImportanceType.SPLIT);
byte[] bytes = modelStr.getBytes(StandardCharsets.UTF_8);   // serialize

LGBMBooster b = LGBMBooster.loadModelFromString(modelStr); // deserialize
```
- Bytes stored as Base64 in `ProjectStateManager.SavedState.lightgbmModelBase64`

---

## JavaFX Integration

**Thread Contract:**
- ML training and prediction execute on background threads (not the JavaFX Application Thread)
- All QuPath object mutations (`PathObject.setPathClass()`, hierarchy updates) and all UI updates must be dispatched via `Platform.runLater(Runnable)`
- Violations will cause `IllegalStateException: Not on FX application thread`

**Usage sites for `Platform.runLater()`:**
- `ClassificationPanel.java` lines 504, 539, 594, 599 — progress bar updates, label refresh after training
- `DualModelClassifier.java` lines 421, 422, 529, 743 — classification result application, hierarchy notification
- `CellTuneExtension.java` lines 401, 561, 566, 906, 921, 976, 993, 1030, 1037, 1047, 1056, 1062 — dialog display, state sync after async ops
- `ManualLabelToolbar.java` line 152 — label overlay refresh

**UI Components:**
- `ClassificationPanel extends VBox` — main sidebar panel (`src/main/java/qupath/ext/celltune/ui/ClassificationPanel.java`)
- `ConfusionMatrixView` — agreement rate display (`src/main/java/qupath/ext/celltune/ui/ConfusionMatrixView.java`)
- `FeatureSelectionPane` — feature subset chooser (`src/main/java/qupath/ext/celltune/ui/FeatureSelectionPane.java`)
- `NormalizationPane` — arcsinh/min-max transform config (`src/main/java/qupath/ext/celltune/ui/NormalizationPane.java`)
- `ReviewController` / `ReviewToolbar` — active learning review mode (`src/main/java/qupath/ext/celltune/ui/`)
- `ManualLabelToolbar` — manual ground truth labeling toolbar (`src/main/java/qupath/ext/celltune/ui/ManualLabelToolbar.java`)
- `ImageSelectionPane` — multi-image selection (`src/main/java/qupath/ext/celltune/ui/ImageSelectionPane.java`)
- Dialogs via `qupath.fx.dialogs.Dialogs` (from `qupath-fxtras`)

---

## Gradle Shadow JAR Bundling

**Plugin:** `com.gradleup.shadow:8.3.5`

**Bundled into fat JAR (`implementation` + `shadow` scope):**
- `ml.dmlc:xgboost4j-gpu_2.13:2.1.4` — includes CUDA and CPU native shared libraries
- `io.github.metarank:lightgbm4j:4.6.0-2` — includes LightGBM native shared library

**Provided by QuPath at runtime (`shadow` scope only — NOT re-bundled):**
- `libs.bundles.qupath` — QuPath core, GUI, analysis APIs
- `libs.bundles.logging` — SLF4J + Logback
- `libs.qupath.fxtras` — QuPath JavaFX utilities

**Manifest entry:**
```
Implementation-Version: <project.version>
```
- `CellTuneExtension.getVersion()` reads this via `getClass().getPackage().getImplementationVersion()`

**Deployment:** Drop the shadow JAR into QuPath's `extensions/` directory. No separate native library installation needed.

---

## Gson Serialization (Project State Persistence)

**Library:** Gson (transitive dependency through QuPath)
**Source file:** `src/main/java/qupath/ext/celltune/io/ProjectStateManager.java`

**Instance:**
```java
private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
```

**Serialized structure:** `ProjectStateManager.SavedState` (public static inner class)

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | User-given classifier name |
| `timestamp` | `String` | ISO-like timestamp (`yyyyMMdd_HHmmss`) |
| `featureNames` | `List<String>` | Feature column order used at training time |
| `classNames` | `List<String>` | Class name order (0-indexed) |
| `labels` | `Map<String,String>` | Cell ID → class name ground truth |
| `xgboostModelBase64` | `String` | Base64-encoded XGBoost model bytes (nullable) |
| `lightgbmModelBase64` | `String` | Base64-encoded LightGBM model bytes (nullable) |
| `rfModel1Base64` / `rfModel2Base64` | `String` | Base64-encoded Random Forest model bytes (nullable) |
| `model1Type` / `model2Type` | `String` | `ModelType` enum name |
| `selectedFeatures` | `List<String>` | User-selected feature subset (nullable = all features) |
| `featureTransforms` | `Map<String,String>` | Feature name → transform name (nullable) |
| `arcsinhCofactor` | `Double` | Arcsinh cofactor for normalization (nullable) |
| `importedTrainingFeatureNames` | `List<String>` | Feature names for imported CSV training data |
| `importedTrainingRows` | `List<SavedState.SavedTrainingRow>` | Imported training vectors |

**File location:** `<QuPath project dir>/celltune/classifier-state.json`

**Key methods:**
- `ProjectStateManager.saveState(Project, ...)` — saves full state, preserves existing imported training data if not re-provided
- `ProjectStateManager.loadState(Project)` — returns `SavedState` or `null` if no file exists
- `ProjectStateManager.saveImportedTrainingData(Project, ...)` — persists imported training vectors before model training
- `ProjectStateManager.getCellTuneDir(Project)` — resolves `<project>/celltune/`, creating it if absent
- Decoder helpers: `decodeXGBoostModel()`, `decodeLightGBMModel()`, `decodeRFModel1()`, `decodeRFModel2()`

---

## File I/O

**AnnData CSV Export:**
- Class: `qupath.ext.celltune.io.AnnDataExporter`
- Source: `src/main/java/qupath/ext/celltune/io/AnnDataExporter.java`
- Output: AnnData-compatible CSV readable by Python `anndata.read_csv()` or convertible to H5AD
- Also generates a companion Python conversion script alongside the CSV
- Triggered from menu: `Export AnnData (CSV + H5AD script)`

**Cell Table CSV Export:**
- Class: `qupath.ext.celltune.io.CellTableExporter`
- Source: `src/main/java/qupath/ext/celltune/io/CellTableExporter.java`
- Output: CSV of cell measurements and classifications
- Triggered from menu: `Export Cell Table...`

**Ground Truth CSV:**
- Class: `qupath.ext.celltune.io.GroundTruthIO`
- Source: `src/main/java/qupath/ext/celltune/io/GroundTruthIO.java`
- Reads/writes `GroundTruthIO.TrainingRow` records (label + float[] features)
- Used in import CSV training data workflow and `importedTrainingRows` persistence in `SavedState`
- Triggered from menus: `Export Ground Truth...` / `Import Ground Truth...`

**Marker Channel Table CSV Import:**
- Class: `qupath.ext.celltune.io.MarkerTableImporter`
- Source: `src/main/java/qupath/ext/celltune/io/MarkerTableImporter.java`
- Static method: `MarkerTableImporter.importFromCSV(Path)` → delegates to `CellTypeTable.loadFromCSV(Path)`
- Populates `CellTypeTable` (cell type → marker channel mapping)
- Triggered from menu: `Import Marker Table...`

---

## Authentication & Identity

- No external authentication. All data is local to the QuPath project directory.
- No API keys or credentials required.

## Monitoring & Observability

**Logging:**
- SLF4J via `LoggerFactory.getLogger(ClassName.class)` in all classes
- Log level follows QuPath's Logback configuration
- Key log points: GPU/CPU training device selection, training completion, state save/load paths

---

*Integration audit: 2026-04-27*
