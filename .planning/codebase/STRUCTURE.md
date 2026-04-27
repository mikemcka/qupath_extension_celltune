# Codebase Structure

**Analysis Date:** 2026-04-27

## Directory Layout

```
qupath_extension_celltune/
├── build.gradle.kts                          # Kotlin DSL build: shadow plugin + qupath-conventions + ML deps
├── settings.gradle.kts                       # QuPath version pin (0.7.0) + toolchain resolver
├── gradlew / gradlew.bat                     # Gradle wrapper scripts
├── gradle/                                   # Gradle wrapper jar + properties
├── celltune-qupath-structure.md              # Full build plan and phase reference
├── AGENTS.md                                 # AI agent instructions for this project
├── README.md                                 # Project overview
├── RISKS.md                                  # Known risks and mitigations
├── TESTER_FEEDBACK_FORM.md                   # QA feedback template
└── src/
    ├── main/
    │   ├── java/qupath/ext/celltune/
    │   │   ├── CellTuneExtension.java         # Entry point — see below
    │   │   │
    │   │   ├── model/                         # Data model layer (no ML, no UI)
    │   │   │   ├── CellFeatureExtractor.java
    │   │   │   ├── CellPrediction.java
    │   │   │   ├── CellTypeTable.java
    │   │   │   ├── FeatureNormalizer.java
    │   │   │   ├── LabelStore.java
    │   │   │   └── PopulationSet.java
    │   │   │
    │   │   ├── classifier/                    # ML training and inference
    │   │   │   ├── DualModelClassifier.java
    │   │   │   ├── XGBoostModel.java
    │   │   │   ├── LightGBMModel.java
    │   │   │   ├── RandomForestModel.java
    │   │   │   ├── ClassifierState.java
    │   │   │   ├── ModelType.java
    │   │   │   ├── ResamplingStrategy.java
    │   │   │   ├── Resampler.java
    │   │   │   ├── HyperparameterTuner.java
    │   │   │   └── UncertaintySampler.java
    │   │   │
    │   │   ├── ui/                            # JavaFX panels and controls
    │   │   │   ├── ClassificationPanel.java
    │   │   │   ├── ConfusionMatrixView.java
    │   │   │   ├── FeatureImportanceView.java
    │   │   │   ├── ReviewController.java
    │   │   │   ├── ReviewToolbar.java
    │   │   │   ├── PopulationPanel.java
    │   │   │   ├── ChannelSelector.java
    │   │   │   ├── ImageSelectionPane.java
    │   │   │   ├── ManualLabelToolbar.java
    │   │   │   ├── NormalizationPane.java
    │   │   │   └── FeatureSelectionPane.java
    │   │   │
    │   │   ├── gating/                        # Marker-threshold seed labelling
    │   │   │   ├── AutoLandmarker.java
    │   │   │   ├── GatingExpression.java
    │   │   │   └── GatingRule.java
    │   │   │
    │   │   └── io/                            # File import, export, project state
    │   │       ├── ProjectStateManager.java
    │   │       ├── GroundTruthIO.java
    │   │       ├── CellTableExporter.java
    │   │       ├── MarkerTableImporter.java
    │   │       └── AnnDataExporter.java
    │   │
    │   └── resources/
    │       ├── META-INF/services/
    │       │   └── qupath.lib.gui.extensions.QuPathExtension   # Service loader registration
    │       └── qupath/ext/celltune/ui/
    │           └── strings.properties                          # All UI strings (i18n bundle)
    │
    └── test/                                  # Unit tests (deferred — not yet written)
```

## Entry Point: `CellTuneExtension.java`

**Location:** `src/main/java/qupath/ext/celltune/CellTuneExtension.java`

**What it registers:**

| Registration | Where |
|---|---|
| Preference toggle `celltune.enabled` | QuPath Preferences pane via `PathPrefs.createPersistentPreference()` |
| Menu items under Extensions menu | "Run CellTune Classification…", "Show Confusions…", "Enter Review Mode", "Import Marker Table…", "Export Cell Table…", "Export AnnData…", "Auto-Landmark Cells…" |
| `ClassificationPanel` as docked tab | `qupath.getAnalysisTabPane().getTabs().add(new Tab(...))` |
| Image-change listener | `qupath.imageDataProperty().addListener(imageDataListener)` |

**State it owns (all fields on the instance):**

| Field | Type | Purpose |
|---|---|---|
| `labelStore` | `LabelStore` | Ground-truth labels for the current image |
| `classifier` | `DualModelClassifier` | Trained dual model (persisted across sessions) |
| `predAll` | `PopulationSet` | Pred_ALL predictions for the current image |
| `cellTypeTable` | `CellTypeTable` | Cell-type → marker-channel mapping |
| `selectedFeatures` | `List<String>` | User-chosen feature subset (null = all features) |
| `featureNormalizer` | `FeatureNormalizer` | Per-feature arcsinh/sqrt config (null = no transform) |
| `lastAgreementRates` | `double[]` | Per-class agreement rates from last confusion matrix |
| `lastSampledCellIds` | `List<String>` | Cell IDs from last `UncertaintySampler` call |
| `importedTrainingRows` | `List<GroundTruthIO.TrainingRow>` | Feature rows imported from external CSV |
| `importedTrainingFeatureNames` | `List<String>` | Feature names matching imported rows |
| `classificationPanel` | `ClassificationPanel` | Reference to the docked panel |

**Image change handling (`handleImageChange`):**
1. Saves old image's `LabelStore`, `PopulationSet`, and sampled cell IDs via `ProjectStateManager`
2. Resets `predAll`, `lastAgreementRates`, `lastSampledCellIds` to null
3. Loads persisted labels and predictions for the new image
4. Attempts to restore `ClassifierState` (trained model bytes) from project JSON
5. Syncs all restored state into `ClassificationPanel` via `syncPanelState()`

## Class-by-Class Reference

### `model/`

**`CellFeatureExtractor`** — `src/main/java/qupath/ext/celltune/model/CellFeatureExtractor.java`
Holds a fixed ordered `List<String> featureNames`. `extractRow(PathObject)` reads `getMeasurementList().get(name)` for each feature (NaN → 0f), applies optional `FeatureNormalizer`, returns `float[]`. `extractMatrix(Collection<PathObject>)` extracts all cells in parallel via `IntStream.parallel()` into a flat row-major `float[]`. `discoverFeatureNames(Collection)` discovers all available measurements via `PathObjectTools.getAvailableFeatures()`.

**`CellPrediction`** — `src/main/java/qupath/ext/celltune/model/CellPrediction.java`
Value class: `cellId`, `model1Label`, `model2Label`, `model1Probs[]`, `model2Probs[]`. Key derived methods: `isDisagreement()` (labels differ), `avgLabel()` (highest average probability), `allLabel()` (agreed label or combined when disagreeing), `model1Confidence()`, `model2Confidence()`.

**`LabelStore`** — `src/main/java/qupath/ext/celltune/model/LabelStore.java`
`LinkedHashMap<String, String>` (cellId → className) with insertion-order preservation. Supports `setLabel()`, `removeLabel()`, `mergeFrom()`, `copy()`, `retainClasses()`, `renameClass()`. Gson-serialisable (plain fields). Used as the accumulator for all ground-truth labels across landmarking, review, and CSV import.

**`PopulationSet`** — `src/main/java/qupath/ext/celltune/model/PopulationSet.java`
Named collection of `CellPrediction`s. Produced in four variants: `Pred_MDL1` (model 1 only), `Pred_MDL2` (model 2 only), `Pred_AVG` (averaged probabilities), `Pred_ALL` (agreed or combined). Supports filtering by label, disagreement-only views, and per-class counts.

**`CellTypeTable`** — `src/main/java/qupath/ext/celltune/model/CellTypeTable.java`
Maps cell-type name → up to 3 marker channel names. CSV round-trip via `loadFromCSV()` / `saveToCSV()`. Used by `ChannelSelector` to determine which channels to show during review, and by `AutoLandmarker` to drive gating thresholds.

**`FeatureNormalizer`** — `src/main/java/qupath/ext/celltune/model/FeatureNormalizer.java`
Per-feature transform map: `arcsinh(value / cofactor)` or `sqrt(value)`. Applied inside `CellFeatureExtractor.extractRow()`. Configured via `NormalizationPane` and serialised in `ProjectStateManager` as `featureTransforms` + `arcsinhCofactor`.

### `classifier/`

**`DualModelClassifier`** — `src/main/java/qupath/ext/celltune/classifier/DualModelClassifier.java`
Central ML orchestrator. `trainAndPredict(allCells, labelStore, extractor, ...)` collects training data, optionally runs `HyperparameterTuner`, trains both models, predicts all cells in 100K-cell chunks (avoids int-index overflow), builds four `PopulationSet`s, sets `PathClass` on each detection object. `predictOnly()` applies trained models to other images without retraining. Exposes `DoubleProperty progressProperty()` and `StringProperty statusProperty()` updated via `Platform.runLater()`. Default model pair: XGBoost (model 1) + LightGBM (model 2).

**`XGBoostModel`** — `src/main/java/qupath/ext/celltune/classifier/XGBoostModel.java`
Wraps XGBoost4J. Objective: `multi:softprob` (multiclass) or `binary:logistic`. Parameters include `max_depth`, `eta`, `subsample`, `colsample_bytree`, `nrounds`. Attempts `device=cuda` first; falls back to CPU. `toByteArray()` / `loadFromBytes()` for serialization. `getLastDevice()` reports actual device used.

**`LightGBMModel`** — `src/main/java/qupath/ext/celltune/classifier/LightGBMModel.java`
Wraps LightGBM4J (SWIG bindings, `com.microsoft.ml.lightgbm` package). Objective: `multiclass` / `softmax`. Attempts `device_type=gpu`; falls back to CPU. `saveModelToString()` / `loadFromString()` for serialization.

**`RandomForestModel`** — `src/main/java/qupath/ext/celltune/classifier/RandomForestModel.java`
Pure-Java CART random forest (no native library). Serializable via Java object serialization. Used when native libraries are unavailable or as either model slot via `ModelType.RANDOM_FOREST`.

**`ClassifierState`** — `src/main/java/qupath/ext/celltune/classifier/ClassifierState.java`
Immutable snapshot: `name`, `featureNames`, `classNames`, `xgboostBytes`, `lightgbmBytes`, `rfModel1Bytes`, `rfModel2Bytes`, `model1Type`, `model2Type`. `isComplete()` checks non-null bytes for the active model types. `isFeatureCompatible(List<String>)` validates feature list equality before applying to a new image.

**`ModelType`** — `src/main/java/qupath/ext/celltune/classifier/ModelType.java`
Enum: `XGBOOST`, `LIGHTGBM`, `RANDOM_FOREST`. Displayed in `ClassificationPanel` combo boxes.

**`ResamplingStrategy`** — `src/main/java/qupath/ext/celltune/classifier/ResamplingStrategy.java`
Enum: `NONE`, plus oversampling/undersampling variants. Displayed in `ClassificationPanel` resampling combo.

**`Resampler`** — `src/main/java/qupath/ext/celltune/classifier/Resampler.java`
Implements class-imbalance correction (SMOTE, random oversample, random undersample) applied to training arrays before model fitting.

**`HyperparameterTuner`** — `src/main/java/qupath/ext/celltune/classifier/HyperparameterTuner.java`
TPE Bayesian optimisation with cross-validation. Invoked by `DualModelClassifier` when `autoTune=true`. Searches per-model hyperparameter space independently.

**`UncertaintySampler`** — `src/main/java/qupath/ext/celltune/classifier/UncertaintySampler.java`
Static utility. `sample(predALL, classNames, agreementRates, sampleSize, preferredConfusions, preferredTypes, fovMap, rng)` implements 6-tier weighted sampling: Tier 0 FOV balance (84 base budget), Tier 1 cell-type disagreement (112 base budget, 16 cells/type), Tier 2 rare cell types (60 base budget, 10 cells/type), Tier 3 preferred confusion pairs (40 base budget, 8 cells/pair), Tier 4 random disagreement fill, Tier 5 agreement exploration (10% of total). All budgets scale linearly with `sampleSize / 256`. Each tier marks used cells so later tiers cannot re-select.

### `ui/`

**`ClassificationPanel`** — `src/main/java/qupath/ext/celltune/ui/ClassificationPanel.java`
`VBox` docked into QuPath's analysis tab pane. Contains: hyperparameter `Spinner`s (rounds, depth), model type `ComboBox`es, resampling `ComboBox`, pool-images `CheckBox`, auto-tune/early-stop `CheckBox`es, Train button with `ProgressBar` binding, Plot Confusions / Sample & Review / Enter Review buttons, `PopulationPanel`. Trains on a daemon thread. Provides callback setters (`setOnLabelStoreChanged`, `setOnPredAllChanged`, `setOnClassifierChanged`, etc.) to sync state back to `CellTuneExtension`.

**`ReviewController`** — `src/main/java/qupath/ext/celltune/ui/ReviewController.java`
Holds ordered `List<PathObject> reviewCells` (resolved from sampled cell IDs at construction), `int currentIndex`, and `LabelStore outputLabels`. Navigation: `next()` / `previous()` / `jumpTo(index)` call `viewer.setCenterPixelLocation(x, y)` and `setMagnification(40)`. `labelAndNext(className)` writes to `outputLabels` and advances. `getOutputLabels()` returns the per-session label store, merged into the main store on session close.

**`ReviewToolbar`** — `src/main/java/qupath/ext/celltune/ui/ReviewToolbar.java`
`HBox` shown in the review Stage. Per-cell: disagreement cells get two prediction buttons (`XGB: CD4 (87%)` in blue, `LGB: Bcell (65%)` in pink); agreement cells get one green "Both: CD4 (87%)" button. "All Classes ▼" `MenuButton` populated from QuPath project classes + `CellTypeTable` entries. Index indicator `(3/50)` with `Circle` dot: green = label matches `Pred_ALL`, red = mismatch, white = unlabelled. Right-click on index → jump-to dialog.

**`ConfusionMatrixView`** — `src/main/java/qupath/ext/celltune/ui/ConfusionMatrixView.java`
Canvas-based inter-model confusion matrix. Rows = model 1 predictions, columns = model 2 predictions. Diagonal is green-scaled; off-diagonal is red-scaled by count. Right margin shows per-class `Agr%` and `F1`. Summary label shows macro F1. `getAgreementRates()` returns `double[]` for `UncertaintySampler`. PNG export via `WritableImage → ImageIO.write`.

**`FeatureImportanceView`** — `src/main/java/qupath/ext/celltune/ui/FeatureImportanceView.java`
Bar chart of top-10 features by mean |SHAP| value. Per-class selector lets users inspect feature importance per cell type. Data comes from `DualModelClassifier.computeFeatureImportance()` which returns a `ShapResult` record.

**`PopulationPanel`** — `src/main/java/qupath/ext/celltune/ui/PopulationPanel.java`
`VBox` with `TitledPane`s for each `PopulationSet`. Each pane shows a `GridPane` with colour swatches (from `PathClass.getColor()`), per-class counts, total count, and disagreement count. Only `Pred_ALL` is expanded by default.

**`ChannelSelector`** — `src/main/java/qupath/ext/celltune/ui/ChannelSelector.java`
Checkbox-gated auto-switch. When enabled: looks up both predicted cell types in `CellTypeTable`, gets up to 3 marker names each (6 channels + nuclear), calls `viewer.getImageDisplay().setChannelSelected(channel, true/false)`. When disabled: channels are left as-is.

**`ImageSelectionPane`** — `src/main/java/qupath/ext/celltune/ui/ImageSelectionPane.java`
Dual-list dialog: Included / Excluded image lists. `>>` / `>` / `<` / `<<` transfer buttons. Search filter. Current image is always in Included (protected). Returns `List<String>` of selected image names, or null on cancel. Used by `ClassificationPanel.doTrain()` for batch prediction.

**`ManualLabelToolbar`** — `src/main/java/qupath/ext/celltune/ui/ManualLabelToolbar.java`
Floating toolbar for direct cell labelling outside the review queue. Allows assigning a class to the currently selected cell in the viewer by clicking class buttons.

**`NormalizationPane`** — `src/main/java/qupath/ext/celltune/ui/NormalizationPane.java`
Dialog for configuring per-feature transforms. Checkbox list of features with arcsinh/sqrt selectors and cofactor input. Produces a `FeatureNormalizer` instance.

**`FeatureSelectionPane`** — `src/main/java/qupath/ext/celltune/ui/FeatureSelectionPane.java`
Filterable checkbox list of all available measurement names. Returns `List<String>` of selected feature names. Shown before training when `selectedFeatures` is null.

### `gating/`

**`AutoLandmarker`** — `src/main/java/qupath/ext/celltune/gating/AutoLandmarker.java`
Multi-threshold cascade that seeds `LabelStore` from marker-positive cells. Reads thresholds from `GatingRule`s derived from `CellTypeTable`. Assigns cells that pass all rules for a type to that type's label.

**`GatingExpression`** — `src/main/java/qupath/ext/celltune/gating/GatingExpression.java`
AST-based boolean expression parser for compound gating rules (e.g., `CD3+ && CD8+`). Evaluates against a `PathObject`'s measurement list.

**`GatingRule`** — `src/main/java/qupath/ext/celltune/gating/GatingRule.java`
Numeric encoding of a single marker threshold rule: marker name, comparison operator, threshold value.

### `io/`

**`ProjectStateManager`** — `src/main/java/qupath/ext/celltune/io/ProjectStateManager.java`
Saves/loads all durable state into `<QuPath project folder>/celltune/`. Methods: `saveState(project, ClassifierState)` → `classifier-state.json` (model bytes as Base64). `loadState(project)` → JSON → `ClassifierState`. `saveImageLabels(project, imageName, LabelStore)` / `loadImageLabels()`. `saveImagePredictions()` / `loadImagePredictions()`. `saveImageSampledCells()` / `loadImageSampledCells()`. Timestamped label backups on every training cycle.

**`GroundTruthIO`** — `src/main/java/qupath/ext/celltune/io/GroundTruthIO.java`
Portable ground-truth CSV export/import. Inner record `TrainingRow` holds a `float[]` feature vector and `String` class label. Used by `CellTuneExtension` to import pre-labelled cells from other tools (the `importedTrainingRows` field).

**`CellTableExporter`** — `src/main/java/qupath/ext/celltune/io/CellTableExporter.java`
Writes CSV with columns: `CellID`, `GroundTruth`, `Pred_MDL1`, `Pred_MDL2`, `Pred_AVG`, `Pred_ALL`, model confidence scores, centroid coordinates. One row per `PathDetectionObject`.

**`MarkerTableImporter`** — `src/main/java/qupath/ext/celltune/io/MarkerTableImporter.java`
Delegates to `CellTypeTable.loadFromCSV()`. Parses `CellType,Marker1,Marker2,Marker3` CSV format. Triggered via the "Import Marker Table…" menu item.

**`AnnDataExporter`** — `src/main/java/qupath/ext/celltune/io/AnnDataExporter.java`
Writes AnnData-compatible CSV (observations × features) plus an optional H5AD conversion script for Python interop downstream analysis.

## Resources

**Service loader registration:**
- `src/main/resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension`
- Contains: `qupath.ext.celltune.CellTuneExtension`
- This single-line file is what causes QuPath to discover and load the extension

**UI strings bundle:**
- `src/main/resources/qupath/ext/celltune/ui/strings.properties`
- All user-visible strings (button labels, tooltip text, dialog titles, extension name/description)
- Loaded by `ResourceBundle.getBundle("qupath.ext.celltune.ui.strings")`
- Referenced in both `CellTuneExtension` (constants) and `ClassificationPanel` (per-widget text)

## Build Outputs

**Shadow JAR command:**
```bash
./gradlew shadowJar
```

**Output location:** `build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar`

**JAR size:** ~51 MB (bundles XGBoost4J ~35 MB + LightGBM4J ~10 MB + extension classes)

**Install:** Copy the shadow JAR into QuPath's extensions directory (typically `~/.qupath/extensions/` or the directory shown in QuPath → Help → System info)

**Java version requirement:** Java 25 (set via `qupath-conventions` Gradle plugin; Adoptium JDK 25 recommended)

## Naming Conventions

**Files:**
- Java classes: `PascalCase.java` matching class name exactly
- Resources: `kebab-case` for markdown docs; `camelCase.properties` for bundles

**Packages:**
- All under `qupath.ext.celltune.<layer>` where layer is `model`, `classifier`, `ui`, `gating`, or `io`

**Classes:**
- Model layer: noun-based (`LabelStore`, `CellPrediction`, `PopulationSet`)
- Classifier layer: `*Model` suffix for ML wrappers, `*State` for snapshots, `*Sampler` for sampling
- UI layer: `*Panel`, `*View`, `*Pane`, `*Toolbar`, `*Controller` suffixes matching JavaFX idioms
- IO layer: `*Exporter` for write-only, `*Importer` for read-only, `*Manager` for read+write state

## Where to Add New Code

**New cell feature:**
- Add measurement name to the feature list used when constructing `CellFeatureExtractor` — no other model changes needed

**New ML model type:**
- Add enum value to `classifier/ModelType.java`
- Implement wrapper class in `classifier/` following `XGBoostModel` interface pattern
- Add serialization support in `classifier/ClassifierState.java`
- Handle in `DualModelClassifier` model selection switch

**New UI panel:**
- Implement as a JavaFX `Node` subclass in `ui/`
- Wire into `CellTuneExtension.installExtension()` or `dockClassificationPanel()`

**New export format:**
- Add a new `*Exporter.java` in `io/`
- Wire a menu item in `CellTuneExtension.addMenuItems()`

**New gating rule type:**
- Extend `GatingExpression.java` to support new AST node types
- Add test cases against known marker combinations

**Tests:**
- Location: `src/test/java/qupath/ext/celltune/` (directory exists but unpopulated)
- Mirror the `src/main/java/` package structure

## Special Directories

**`.planning/`:**
- Purpose: GSD workflow planning artifacts (roadmap, phase plans, codebase docs)
- Generated: No (hand-written and AI-generated)
- Committed: Yes

**`build/`:**
- Purpose: Gradle build outputs (class files, shadow JAR)
- Generated: Yes
- Committed: No (in `.gitignore`)

**`gradle/`:**
- Purpose: Gradle wrapper JAR and `gradle-wrapper.properties`
- Generated: No (copied from reference project)
- Committed: Yes

---

*Structure analysis: 2026-04-27*
