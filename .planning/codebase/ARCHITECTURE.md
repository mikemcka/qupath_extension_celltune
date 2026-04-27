<!-- refreshed: 2026-04-27 -->
# Architecture

**Analysis Date:** 2026-04-27

## System Overview

```text
┌──────────────────────────────────────────────────────────────────────────┐
│                         QuPath 0.7 Host Process (JavaFX)                  │
│  QuPathGUI  ·  imageDataProperty  ·  AnalysisTabPane  ·  Viewer          │
└───────────────────────────┬──────────────────────────────────────────────┘
                             │  installExtension()
                             ▼
┌──────────────────────────────────────────────────────────────────────────┐
│              CellTuneExtension   (entry point / state owner)              │
│  Fields: labelStore · classifier · predAll · cellTypeTable               │
│           selectedFeatures · featureNormalizer · lastSampledCellIds       │
│  Installs: menu items · ClassificationPanel (docked tab) · image listener │
└──────┬──────────────────┬────────────────────┬────────────────────┬──────┘
       │                  │                    │                    │
       ▼                  ▼                    ▼                    ▼
┌─────────────┐  ┌──────────────────┐  ┌─────────────┐  ┌──────────────────┐
│   model/    │  │   classifier/    │  │    ui/      │  │      io/         │
│ Data layer  │  │   ML layer       │  │  JavaFX     │  │  Import/Export   │
│             │  │                  │  │  panels     │  │  State mgmt      │
│ LabelStore  │  │DualModelClass.   │  │ Classific.  │  │ ProjectStateMgr  │
│ CellPred.   │  │XGBoostModel      │  │ Panel       │  │ GroundTruthIO    │
│ CellFeature │  │LightGBMModel     │  │ ReviewCtrl  │  │ CellTableExp.    │
│  Extractor  │  │RandomForestModel │  │ ReviewTool  │  │ AnnDataExporter  │
│ PopulationSet│ │UncertaintySampler│  │ Confusion   │  │ MarkerTableImp.  │
│ CellTypeTable│ │ClassifierState   │  │ MatrixView  │  │                  │
│FeatureNorm. │  │HyperparamTuner   │  │ PopPanel    │  └──────────────────┘
└─────────────┘  └──────────────────┘  │ ChannelSel. │
                                        │FeatureImpVw │
       ┌────────────────────────────────┤ ManualLabel │
       │           gating/             │  Toolbar    │
       │  AutoLandmarker               └─────────────┘
       │  GatingExpression
       │  GatingRule
       └────────────────────────────────────────────────
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| `CellTuneExtension` | QuPath entry point; owns all shared state; installs menus and docked panel | `CellTuneExtension.java` |
| `DualModelClassifier` | Trains two gradient-boosted models, builds four `PopulationSet`s, exposes progress properties | `classifier/DualModelClassifier.java` |
| `XGBoostModel` | Wraps XGBoost4J train/predict with GPU→CPU fallback, serialises to `byte[]` | `classifier/XGBoostModel.java` |
| `LightGBMModel` | Wraps LightGBM4J train/predict with GPU→CPU fallback, serialises via `saveModelToString()` | `classifier/LightGBMModel.java` |
| `RandomForestModel` | Pure-Java CART random forest; no native dependency | `classifier/RandomForestModel.java` |
| `UncertaintySampler` | 6-tier weighted sampling that selects disagreement cells for review | `classifier/UncertaintySampler.java` |
| `ClassifierState` | Immutable snapshot: model bytes + feature/class lists + model type tags | `classifier/ClassifierState.java` |
| `HyperparameterTuner` | TPE Bayesian optimisation for per-model hyperparameter search | `classifier/HyperparameterTuner.java` |
| `Resampler` | Class-imbalance resampling strategies (SMOTE, oversample, undersample) | `classifier/Resampler.java` |
| `CellFeatureExtractor` | Reads `PathObject.getMeasurementList()` → `float[]`; enforces fixed column ordering; optional `FeatureNormalizer` | `model/CellFeatureExtractor.java` |
| `CellPrediction` | Value class: `model1Label`, `model2Label`, `model1Probs[]`, `model2Probs[]`, `isDisagreement()`, `avgLabel()` | `model/CellPrediction.java` |
| `LabelStore` | `LinkedHashMap<cellId → className>` ground-truth accumulator; supports `mergeFrom()`, `copy()` | `model/LabelStore.java` |
| `PopulationSet` | Named group of `CellPrediction`s: `Pred_MDL1`, `Pred_MDL2`, `Pred_AVG`, `Pred_ALL` | `model/PopulationSet.java` |
| `CellTypeTable` | Cell-type → marker-channel mapping (up to 3 channels/type); CSV round-trip | `model/CellTypeTable.java` |
| `FeatureNormalizer` | Per-feature arcsinh/sqrt transforms with configurable cofactor | `model/FeatureNormalizer.java` |
| `ClassificationPanel` | Main docked sidebar; orchestrates the train → confuse → sample → review workflow | `ui/ClassificationPanel.java` |
| `ReviewController` | Review queue: ordered `List<PathObject>`, viewer navigation, label assignment | `ui/ReviewController.java` |
| `ReviewToolbar` | JavaFX HBox: Previous/Next/Skip, dynamic prediction buttons, index indicator | `ui/ReviewToolbar.java` |
| `ConfusionMatrixView` | Canvas-based inter-model confusion matrix with per-class F1 and PNG export | `ui/ConfusionMatrixView.java` |
| `FeatureImportanceView` | Top-10 SHAP bar chart with per-class selector | `ui/FeatureImportanceView.java` |
| `PopulationPanel` | `ListView` of population sets with colour swatches and per-class counts | `ui/PopulationPanel.java` |
| `ChannelSelector` | Auto-switches QuPath image display channels per predicted cell type | `ui/ChannelSelector.java` |
| `ImageSelectionPane` | Dual-list dialog for selecting which project images to batch-classify | `ui/ImageSelectionPane.java` |
| `ManualLabelToolbar` | Floating toolbar for direct point-annotation-based cell labelling | `ui/ManualLabelToolbar.java` |
| `NormalizationPane` | Checkbox dialog to configure arcsinh/sqrt transforms per feature | `ui/NormalizationPane.java` |
| `FeatureSelectionPane` | Filterable checkbox list for selecting the feature subset | `ui/FeatureSelectionPane.java` |
| `AutoLandmarker` | Multi-threshold cascade that seeds labels from marker-positive cells | `gating/AutoLandmarker.java` |
| `GatingExpression` | AST-based boolean expression parser for gating rules | `gating/GatingExpression.java` |
| `GatingRule` | Numeric encoding of a single marker threshold rule | `gating/GatingRule.java` |
| `ProjectStateManager` | Saves/loads full classifier state as JSON+Base64 into `<project>/celltune/` | `io/ProjectStateManager.java` |
| `GroundTruthIO` | Portable ground-truth export/import as CSV (`TrainingRow` inner record) | `io/GroundTruthIO.java` |
| `CellTableExporter` | Exports all cells with `Pred_ALL` labels, confidence, and coordinates to CSV | `io/CellTableExporter.java` |
| `MarkerTableImporter` | Parses `CellType,Marker1,Marker2,Marker3` CSV → `CellTypeTable` | `io/MarkerTableImporter.java` |
| `AnnDataExporter` | Writes AnnData-compatible CSV + H5AD-ready output for Python interop | `io/AnnDataExporter.java` |

## Pattern Overview

**Overall:** QuPath Extension + Human-in-the-Loop Active Learning

The extension plugs into QuPath via the `QuPathExtension` service loader interface. `CellTuneExtension` acts as a God Object that owns all shared mutable state and wires the four layers (model, classifier, ui, io) together. The active learning loop runs inside this host: seed labels → dual-model training → confusion analysis → uncertainty sampling → human review → merge labels → retrain.

**Key Characteristics:**
- No Python dependency — XGBoost4J and LightGBM4J are bundled in the shadow JAR
- Dual-model disagreement drives the review queue rather than single-model uncertainty
- All ML training happens on daemon background threads; UI binding is done via JavaFX properties and `Platform.runLater()`
- State is persisted per-image inside the QuPath project folder (`<project>/celltune/`)

## Layers

**Data Model (`model/`):**
- Purpose: Plain Java data structures; no JavaFX, no ML, no I/O dependencies
- Location: `src/main/java/qupath/ext/celltune/model/`
- Contains: Feature extraction, prediction value objects, label store, population sets, type mapping, normalization
- Depends on: QuPath core (`PathObject`, `MeasurementList`)
- Used by: Every other layer

**ML / Classifier (`classifier/`):**
- Purpose: Training, inference, sampling, and state serialization
- Location: `src/main/java/qupath/ext/celltune/classifier/`
- Contains: Dual-model orchestrator, three model wrappers, uncertainty sampler, hyperparameter tuner, resampler, state snapshot
- Depends on: `model/`, XGBoost4J, LightGBM4J
- Used by: `ui/ClassificationPanel`, `CellTuneExtension`

**UI (`ui/`):**
- Purpose: All JavaFX panels and controls
- Location: `src/main/java/qupath/ext/celltune/ui/`
- Contains: Docked sidebar, review mode, confusion matrix, population list, channel selector, dialogs
- Depends on: `model/`, `classifier/`, `io/`, QuPath GUI APIs
- Used by: `CellTuneExtension`

**Gating (`gating/`):**
- Purpose: Marker-threshold-based seed labelling
- Location: `src/main/java/qupath/ext/celltune/gating/`
- Contains: Auto-landmarker, gating expression parser, rule encoder
- Depends on: `model/CellTypeTable`, QuPath `PathObject`
- Used by: `CellTuneExtension` (landmark menu item)

**I/O (`io/`):**
- Purpose: File import, export, and project-scoped persistence
- Location: `src/main/java/qupath/ext/celltune/io/`
- Contains: State manager, ground truth CSV, cell table exporter, AnnData exporter, marker table importer
- Depends on: `model/`, `classifier/ClassifierState`, QuPath `Project`
- Used by: `CellTuneExtension`, `ui/ClassificationPanel`

## Active Learning Data Flow

### Primary Request Path (one full training cycle)

```
① AutoLandmarker / ManualLabelToolbar / annotation-point overlap
     Seeds LabelStore with cellId → className entries
     (`CellTuneExtension.collectLabelsFromAnnotations()`)

② ClassificationPanel.doTrain()  [FX thread]
     Builds CellFeatureExtractor with selectedFeatures + featureNormalizer
     Opens ImageSelectionPane to choose batch images
     Spawns daemon Thread → DualModelClassifier.trainAndPredict()

③ DualModelClassifier.trainAndPredict()  [background thread]
     Collects float[][] training matrix from LabelStore cells
     Optionally pools supplementary rows from other images
     Optionally runs HyperparameterTuner (TPE cross-validation)
     Trains XGBoostModel (multi:softprob) and LightGBMModel (multiclass) in sequence
     Predicts all cells in 100K-cell chunks → List<CellPrediction>
     Builds Pred_MDL1, Pred_MDL2, Pred_AVG, Pred_ALL PopulationSets
     Sets PathClass on each PathDetectionObject to Pred_AVG label
     Fires Platform.runLater() for progress/status updates

④ Back on FX thread: ClassificationPanel receives onPredAllChanged callback
     Updates UI counters, enables confusionsButton and sampleButton
     CellTuneExtension.persistCurrentImagePredictions() saves predAll to project

⑤ ConfusionMatrixView  [FX thread, opened on demand]
     Builds int[nClasses][nClasses] from Pred_MDL1 vs Pred_MDL2
     Computes per-class agreement rates: diag[i] / rowSum[i]
     Renders Canvas with green diagonal / red off-diagonal colouring
     Stores double[] agreementRates in ClassificationPanel state

⑥ UncertaintySampler.sample()  [FX thread, called on "Sample & Review" click]
     6-tier priority: FOV balance → type disagreement → rare types →
       preferred confusions → random fill → exploration (10% agreement cells)
     Returns List<String> cellIds (size = user-specified sample size)

⑦ ReviewController + ReviewToolbar  [FX thread, Stage]
     Resolves cellIds → List<PathObject> from hierarchy
     next() / previous() → viewer.setCenterPixelLocation(x, y) + setMagnification(40)
     ReviewToolbar shows XGB/LGB prediction buttons for disagreement cells,
       single "Both" button for agreement cells
     ChannelSelector auto-switches channel visibility via CellTypeTable lookup
     On button click: outputLabels.setLabel(cellId, className) → next()

⑧ On review Stage close: outputLabels merged into main labelStore
     → back to ② (retrain with expanded ground truth)
```

**State Management:**
- `CellTuneExtension` owns the single authoritative `labelStore`, `classifier`, and `predAll` references
- `ClassificationPanel` holds references to these and propagates changes back via callback lambdas (`setOnLabelStoreChanged`, `setOnPredAllChanged`, etc.)
- On image switch, `handleImageChange()` saves the old image's labels/predictions/sampledCells to `ProjectStateManager` and loads the new image's persisted state

## Thread Model

**JavaFX Application Thread (FX thread):**
- All UI construction, button callbacks, property bindings
- `ClassificationPanel.doTrain()` launches the background thread from here
- `Platform.runLater()` is the only safe path back to FX thread from a background thread
- `DualModelClassifier.updateStatus()` always calls `Platform.runLater()` to update `progressProperty()` and `statusProperty()`

**Background daemon threads:**
- `DualModelClassifier.trainAndPredict()` — the entire train+predict pipeline
- `CellFeatureExtractor.extractMatrix()` — uses `IntStream.parallel()` internally
- Both XGBoost4J and LightGBM4J use their own native thread pools for tree training

**Contract:**
- Never call QuPath hierarchy or viewer APIs from a background thread — always wrap in `Platform.runLater()`
- Never touch JavaFX node properties directly from a background thread
- `DualModelClassifier` exposes `DoubleProperty progress` and `StringProperty status` which are safe to bind on the FX thread because all mutations go through `Platform.runLater()`

## ClassifierState Serialization / Restoration

```
Train completes
    ↓
DualModelClassifier → ClassifierState
    name, featureNames, classNames,
    xgboostBytes (XGBoost4J toByteArray),
    lightgbmBytes (LightGBM saveModelToString → bytes),
    rfModel1Bytes / rfModel2Bytes (Java serialization),
    model1Type, model2Type
    ↓
ProjectStateManager.saveState(project, ClassifierState)
    Writes <project>/celltune/classifier-state.json
    Model bytes encoded as Base64 strings
    Timestamped label backup also written

On image switch / QuPath restart:
    ProjectStateManager.loadState(project) → JSON → ClassifierState
    DualModelClassifier.loadFromState(ClassifierState)
    XGBoostModel.loadFromBytes() / LightGBMModel.loadFromString()
    classifier.isTrained() → true → predictions re-applied immediately
```

Separate per-image files:
- `<project>/celltune/<imageName>-labels.json` — `LabelStore` as JSON
- `<project>/celltune/<imageName>-predictions.json` — `PopulationSet` (Pred_ALL) as JSON
- `<project>/celltune/<imageName>-sampled-cells.json` — `List<String>` of last sampled IDs

## Key Design Decisions

**Why dual-model (XGBoost + LightGBM)?**
Disagreement between two independently-trained models is a stronger signal of genuine classification ambiguity than single-model uncertainty (entropy). Cells where both models confidently but differently classify are the highest-value examples for human review — they resolve conflicting evidence the models cannot reconcile alone. This mirrors the original CellTune Python approach.

**Why 6-tier uncertainty sampling?**
Flat disagreement sampling over-represents majority cell types and ignores spatial clustering. The 6-tier strategy explicitly corrects for: (1) spatial FOV bias, (2) high-disagreement cell types, (3) rare types with few training examples, (4) user-targeted confusion pairs, (5) random fill for coverage, (6) 10% agreement-cell exploration to catch co-misclassification blind spots.

**Why no Python dependency?**
All ML is via JVM-native bindings (XGBoost4J, LightGBM4J). This lets the extension run as a single shadow JAR inside QuPath without requiring a Conda environment or subprocess management. The RandomForestModel provides a pure-Java fallback when native libraries fail.

**Why `CellFeatureExtractor` is shared across both models?**
The feature column ordering must be identical for both models so that `CellPrediction.avgLabel()` (which averages probability arrays index-by-index) is mathematically valid. The extractor holds a fixed `List<String> featureNames` set once and never mutated.

## Anti-Patterns

### Calling hierarchy/viewer APIs off the FX thread

**What happens:** Background training thread directly calls `viewer.setCenterPixelLocation()` or `hierarchy.fireHierarchyChangedEvent()`
**Why it's wrong:** Causes race conditions and JavaFX illegal state exceptions; hierarchy events trigger UI repaints
**Do this instead:** Wrap any QuPath GUI/hierarchy call in `Platform.runLater(() -> ...)` from background threads

### Mutating `LabelStore` during active training

**What happens:** Review session writes to the same `LabelStore` instance that `DualModelClassifier` is reading
**Why it's wrong:** `DualModelClassifier.trainAndPredict()` iterates `labelStore.getAllLabels()` — concurrent modification causes `ConcurrentModificationException`
**Do this instead:** `ReviewController` writes to a separate `outputLabels` store; labels are merged into the main `labelStore` only after the review Stage closes and training is not running

### Constructing `CellFeatureExtractor` with different feature lists per model

**What happens:** XGBoost and LightGBM are trained with different feature orderings
**Why it's wrong:** `CellPrediction.avgLabel()` adds probability arrays index-by-index — misaligned orderings produce incorrect averages silently
**Do this instead:** Always create one `CellFeatureExtractor` instance and pass it to `DualModelClassifier.trainAndPredict()`; the classifier stores `featureNames` from this single instance

## Error Handling

**Strategy:** Checked exceptions propagate up from ML methods; `DualModelClassifier.trainAndPredict()` throws `Exception`; callers catch and display via `Dialogs.showErrorMessage()` on the FX thread.

**Patterns:**
- `CellFeatureExtractor.extractRow()`: replaces `NaN` with `0f` silently — missing measurements never throw
- `XGBoostModel` / `LightGBMModel`: attempt GPU training, catch `Exception`, fall back to CPU, log device via `getLastDevice()`
- `ProjectStateManager`: IOExceptions are caught per-operation and logged as warnings; the extension continues with in-memory state if a save/load fails

## Cross-Cutting Concerns

**Logging:** SLF4J (`LoggerFactory.getLogger(ClassName.class)`) throughout; routed to QuPath's log output
**Validation:** `DualModelClassifier` checks `nClasses >= 2` and `nSamples >= nClasses * 2` before training
**Authentication:** Not applicable — local desktop extension with no network calls

---

*Architecture analysis: 2026-04-27*
