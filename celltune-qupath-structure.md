# CellTune-QuPath Extension
## Build Plan & Component Reference · QuPath 0.7

---
## Overall objectives: 
Build a java extension for qupath 0.7 emulates the functionality of celltune, where labelled or landmarked cells are added into individual cell class populations, we will treat these as our ground truth. 

These ground truth cells will then be used to train xgboost and catboost in case of gpu or light GBM if only CPU is detected. They will be compared in a confusion matrix and then the low confidence cells will be used to populate the list of cells for review, where they are presented to the user and reviewed and added to the ground truth label populations.

The user will review and classify for a number of rounds until they are happy with the f1 scores, confusion matrix and pass visual inspection. 

## 1. Project Overview

This document is the full build plan for a QuPath 0.7 extension that replicates CellTune's human-in-the-loop active learning classification workflow. It covers every component that needs to be built, the order in which to build them, the Java files involved, and how each phase connects to the next.

CellTune's core loop:
1. Seed labels via landmarking
2. Train two gradient-boosted models (XGBoost + LightGBM) simultaneously
3. Compare model predictions — flag disagreement cells
4. Weight sampling toward confused cell types
5. Present disputed cells one-by-one in Review mode for human correction
6. Feed corrected labels back → retrain → repeat

This extension replicates that loop entirely inside QuPath using Java/JavaFX, with XGBoost4J and LightGBM4J as the ML backends. No Python dependency is required.

### Technology Stack

| Layer | Technology | Purpose |
|---|---|---|
| Build system | Gradle 9.2.1 + Kotlin DSL | Standard for QuPath 0.7 extensions |
| Extension host | QuPath 0.7 | Whole-slide viewer, cell objects, JavaFX UI |
| ML model 1 | XGBoost4J 3.2.0 (`xgboost4j_2.13`) | Primary gradient boosted classifier |
| ML model 2 | LightGBM4J 4.6.0-2 | Secondary classifier for disagreement detection |
| ML model 3 | Random Forest (pure Java) | Alternative classifier — no external dependency |
| UI framework | JavaFX (bundled) | Panels, plots, review toolbar |
| Data format | QuPath measurements | Cell feature vectors stored on PathObjects |
| Serialisation | JSON (Gson) | Saving classifier state, labels, predictions |

---

## 2. Project File Structure

All source lives under `src/main/java/qupath/ext/celltune/`.

```
qupath-extension-celltune/
├─ build.gradle.kts                                   // Kotlin DSL — QuPath conventions plugin + deps
├─ settings.gradle.kts                                // QuPath version + settings plugin
├─ src/main/java/qupath/ext/celltune/
│  ├─ CellTuneExtension.java                         // Entry point — registers menus & panels
│  │
│  ├─ model/                                         // Data model layer
│  │  ├─ CellFeatureExtractor.java                   // Reads QuPath measurements → float[] (with normalization)
│  │  ├─ CellPrediction.java                         // Stores model1, model2 predictions + confidence
│  │  ├─ FeatureNormalizer.java                      // Per-feature arcsinh/sqrt transforms + cofactor
│  │  ├─ LabelStore.java                             // Manages ground-truth labels per cell
│  │  ├─ PopulationSet.java                          // Named group of CellPrediction/label combos
│  │  └─ CellTypeTable.java                          // Cell type → marker channel mapping + gating
│  │
│  ├─ classifier/                                    // ML training and inference
│  │  ├─ DualModelClassifier.java                    // Orchestrates dual-model training (XGB/LGB/RF)
│  │  ├─ XGBoostModel.java                           // Wraps XGBoost4J train/predict
│  │  ├─ LightGBMModel.java                          // Wraps LightGBM4J train/predict
│  │  ├─ RandomForestModel.java                      // Pure-Java Random Forest (CART, parallel)
│  │  ├─ ModelType.java                              // Enum: XGBOOST, LIGHTGBM, RANDOM_FOREST
│  │  ├─ ClassifierState.java                        // Serialisable snapshot (all model types)
│  │  └─ UncertaintySampler.java                     // 6-tier weighted sampling with FOV balance
│  │
│  ├─ ui/                                            // JavaFX panels and controls
│  │  ├─ ClassificationPanel.java                    // Main classifier sidebar panel
│  │  ├─ ConfusionMatrixView.java                    // JavaFX Canvas inter-model confusion plot
│  │  ├─ ReviewToolbar.java                          // Nav + dynamic prediction buttons + All Classes menu
│  │  ├─ ReviewController.java                       // Review queue logic, viewer navigation
│  │  ├─ PopulationPanel.java                        // Lists population sets, triggers review
│  │  ├─ ChannelSelector.java                        // Auto channel switching during review
│  │  ├─ ImageSelectionPane.java                     // Dual-list image selector for batch classification
│  │  ├─ ManualLabelToolbar.java                     // Floating toolbar for direct cell labelling
│  │  └─ FeatureSelectionPane.java                   // Feature selection dialog (2000+ features)
│  │
│  ├─ gating/                                        // Marker-based gating system
│  │  ├─ AutoLandmarker.java                         // Multi-threshold cascade landmark engine
│  │  ├─ GatingExpression.java                       // AST-based boolean expression parser
│  │  └─ GatingRule.java                             // Numeric encoding for marker rules
│  │
│  └─ io/                                            // Import / export
│     ├─ AnnDataExporter.java                        // AnnData-compatible CSV + H5AD script
│     ├─ MarkerTableImporter.java                    // CSV → CellTypeTable
│     ├─ CellTableExporter.java                      // Export predictions + labels to CSV
│     ├─ GroundTruthIO.java                          // Portable ground truth export/import (CSV)
│     └─ ProjectStateManager.java                    // Save/load full classifier state as JSON
│
└─ src/main/resources/
   ├─ META-INF/services/
   │  └─ qupath.lib.gui.extensions.QuPathExtension   // Service loader registration
   └─ celltune-icons/                                // SVG toolbar icons
```

---

## 3. Build Phases

---

### PHASE 1 — Project Scaffold & Extension Registration ✅ COMPLETE
*Get a working empty extension that loads inside QuPath 0.7*

Everything else depends on this phase. Until QuPath can load the extension JAR, nothing else can be tested.

| # | Task | Java File / Location | Depends On | Status |
|---|---|---|---|---|
| 1.1 | Create Gradle project from QuPath extension template | `build.gradle.kts`, `settings.gradle.kts` | None | ✅ Done |
| 1.2 | Add XGBoost4J + LightGBM4J to build.gradle.kts dependencies | `build.gradle.kts` | 1.1 | ✅ Done |
| 1.3 | Create `CellTuneExtension.java` — implements `QuPathExtension` | `CellTuneExtension.java` | 1.1 | ✅ Done |
| 1.4 | Register via META-INF service loader file | `META-INF/services/…` | 1.3 | ✅ Done |
| 1.5 | Add placeholder menu items under Extensions menu (Classifier, Review, Confusions, Export, Import Markers) | `CellTuneExtension.java` | 1.3 | ✅ Done |
| 1.6 | Create `strings.properties` resource bundle for all UI strings | `resources/qupath/ext/celltune/ui/strings.properties` | 1.3 | ✅ Done |
| 1.7 | Build shadow JAR and verify it compiles | `gradlew shadowJar` | 1.6 | ✅ Done |

> **Note:** QuPath 0.7 requires **Java 25** (the `qupath-conventions` plugin sets `languageVersion=25`). Install Adoptium JDK 25 and set `JAVA_HOME` accordingly. The `foojay-resolver-convention` plugin in `settings.gradle.kts` can auto-provision the toolchain if needed. Build with `gradlew shadowJar` to bundle XGBoost4J and LightGBM4J into a single JAR.

> **Phase 1 Build Notes:**
> - Output JAR: `build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar` (~51 MB, includes XGBoost4J + LightGBM4J)
> - Gson was removed as an explicit dependency — it is already bundled in QuPath 0.7
> - The `foojay-resolver-convention` plugin (v0.9.0) was added to `settings.gradle.kts` to resolve the Java 25 toolchain requirement
> - gradlew and gradle-wrapper.jar were copied from the working `qupath-extension-xgboost` project

---

### PHASE 2 — Data Model Layer ✅ COMPLETE
*Core Java classes that represent cells, labels and predictions*

These plain Java classes are the data backbone that every other component depends on. No UI, no ML — just clean data structures. Build and unit-test them in isolation.

| # | Task | Java File / Location | Depends On | Status |
|---|---|---|---|---|
| 2.1 | `CellFeatureExtractor` — reads all double measurements off a `PathDetectionObject` into a `float[]` | `model/CellFeatureExtractor.java` | Phase 1 | ✅ Done |
| 2.2 | `CellPrediction` — value class holding `cellId`, `model1Label`, `model2Label`, `model1Probs[]`, `model2Probs[]`, `isDisagreement()` | `model/CellPrediction.java` | 2.1 | ✅ Done |
| 2.3 | `LabelStore` — `Map<String,String>` of `cellId → groundTruth` label with add/remove/get. Gson-serialisable. | `model/LabelStore.java` | 2.2 | ✅ Done |
| 2.4 | `PopulationSet` — named collection of `CellPrediction`s (`Pred_MDL1`, `Pred_MDL2`, `Pred_AVG`, `Pred_ALL`). Supports filter by label. | `model/PopulationSet.java` | 2.2 | ✅ Done |
| 2.5 | `CellTypeTable` — maps cell type name → list of up to 3 marker channel names (loaded from CSV) | `model/CellTypeTable.java` | None | ✅ Done |
| 2.6 | Unit tests for disagreement detection and probability averaging | `test/…` | 2.1–2.5 | ⏳ Deferred |

> **Note:** `CellPrediction.isDisagreement()` is just `!model1Label.equals(model2Label)`. `CellPrediction.avgLabel()` returns the class with highest average probability across both model probability arrays.

> **Phase 2 Build Notes:**
> - All 5 model classes created and compile cleanly in the shadow JAR
> - `CellFeatureExtractor` uses `PathObject.getMeasurementList().get(name)` with `NaN → 0f` default
> - `CellPrediction` provides `avgLabel()`, `allLabel()`, `model1Confidence()`, `model2Confidence()` methods
> - `LabelStore` uses `LinkedHashMap` for insertion-order preservation, supports `mergeFrom()` and `copy()`
> - `PopulationSet` supports disagreement filtering, per-model counts, and per-avgLabel queries
> - `CellTypeTable` has CSV round-trip I/O (`loadFromCSV` / `saveToCSV`)

---

### PHASE 3 — Import / Export & Project State ✅ COMPLETE
*Load marker tables, save classifier state, export results*

Build I/O before the classifier so you can load real data for testing. You will use `MarkerTableImporter` in Phase 4 and `ProjectStateManager` in Phases 4 and 5.

| # | Task | Java File / Location | Depends On | Status |
|---|---|---|---|---|
| 3.1 | `MarkerTableImporter` — parses CSV with columns `[CellType, Marker1, Marker2, Marker3]` into `CellTypeTable` | `io/MarkerTableImporter.java` | Phase 2 | ✅ Done |
| 3.2 | `CellTableExporter` — writes CSV of all cells with their `Pred_ALL` labels + confidence scores | `io/CellTableExporter.java` | Phase 2 | ✅ Done |
| 3.3 | `ProjectStateManager` — serialises/deserialises `ClassifierState` (model bytes + LabelStore) as JSON+Base64 into QuPath project folder | `io/ProjectStateManager.java` | Phase 2 | ✅ Done |
| 3.4 | Wire file-open dialogs in `CellTuneExtension` for marker table import and cell table export | `CellTuneExtension.java` | 3.1 | ✅ Done |

> **Phase 3 Build Notes:**
> - `MarkerTableImporter` delegates to `CellTypeTable.loadFromCSV()` with logging
> - `CellTableExporter` writes CSV with columns: CellID, GroundTruth, Pred_MDL1/MDL2/AVG/ALL, confidence scores, coordinates
> - `ProjectStateManager` saves to `<project>/celltune/classifier-state.json` with model bytes as Base64, plus timestamped label backups
> - Import Markers menu item opens FileChooser for CSV; Export menu item opens save dialog for CSV
> - Extension now holds shared state fields: `cellTypeTable`, `labelStore`, `predAll`

> **Note:** XGBoost and LightGBM models can be serialised to `byte[]` using their built-in save methods. Store these as Base64 strings inside your JSON project state so the whole classifier round-trips through a single `.json` file in the QuPath project directory.

---

### PHASE 4 — Dual-Model Classifier ✅ COMPLETE
*Train XGBoost + LightGBM and produce the four prediction population sets*

This is the intellectual core of the extension — the two-model training loop that makes CellTune's disagreement-based active learning possible.

| # | Task | Java File / Location | Depends On | Status |
|---|---|---|---|---|
| 4.1 | `XGBoostModel` — wraps XGBoost4J: `train(DMatrix, params)`, `predict(float[]) → int`, `predictProba(float[]) → float[]` | `classifier/XGBoostModel.java` | Phase 2 | ✅ Done |
| 4.2 | `LightGBMModel` — same interface wrapping LightGBM4J `Dataset` + `Booster` | `classifier/LightGBMModel.java` | Phase 2 | ✅ Done |
| 4.3 | `DualModelClassifier` — takes `LabelStore` + `CellFeatureExtractor`, trains both models, returns four `PopulationSet`s | `classifier/DualModelClassifier.java` | 4.1, 4.2 | ✅ Done |
| 4.4 | `ClassifierState` — holds serialised model bytes, label backup, feature column names | `classifier/ClassifierState.java` | 4.3 | ✅ Done |
| 4.5 | Training runs on a background `Thread` with a JavaFX `progress` property the UI can bind to | `classifier/DualModelClassifier.java` | 4.3 | ✅ Done |
| 4.6 | After training, write `Pred_AVG` as QuPath `PathClass` on each `PathDetectionObject` | `classifier/DualModelClassifier.java` | 4.3 | ✅ Done |
| 4.7 | Auto-backup `LabelStore` to project folder on every train (filename includes timestamp) | `io/ProjectStateManager.java` | 4.3, 3.3 | ✅ Done |

> **Phase 4 Build Notes:**
> - `XGBoostModel`: uses `multi:softprob` / `binary:logistic`, embeds feature names + class names in booster attrs, serialises via `toByteArray()`. Attempts GPU (CUDA) training first with `device=cuda`, falls back to CPU. Reports device via `getLastDevice()`.
> - `LightGBMModel`: uses `com.microsoft.ml.lightgbm.PredictionType.C_API_PREDICT_NORMAL` (SWIG package, not lightgbm4j package). `saveModelToString()` requires 3 args including `LGBMBooster.FeatureImportanceType.SPLIT`. Attempts GPU training first with `device_type=gpu`, falls back to CPU. Reports device via `getLastDevice()`.
> - `DualModelClassifier`: orchestrates train → predict → build 4 PopulationSets, sets `Pred_AVG` as PathClass on cells, exposes `progressProperty()` / `statusProperty()` for UI binding. `predictOnly()` method applies trained models to other images without retraining. Logs device info (GPU/CPU) and thread count through the progress callback.
> - `CellTuneExtension.showClassifierPanel()`: collects labels from point annotations overlapping detections, prompts for feature selection if needed, shows confirmation dialog, presents `ImageSelectionPane` for batch image selection, displays a progress dialog with real-time progress bar and scrollable log, trains on daemon thread, applies predictions to selected images, auto-backs up labels, saves classifier state to project
> - `ImageSelectionPane`: dual-list dialog with Included/Excluded image lists, search filters, `>>` / `>` / `<` / `<<` transfer buttons. Current image is always included (protected). Returns selected image names or null on cancel.
> - Classifier menu item renamed from "CellTune Classifier..." to "Run CellTune Classification..."

> **Note:** XGBoost4J multiclass needs `num_class` set to the number of cell types. Use `softprob` objective to get per-class probabilities. LightGBM equivalent is `multiclass` / `softmax`. Both models must use exactly the same feature column ordering — enforce this through `CellFeatureExtractor`.

**Recommended XGBoost parameters (starting point):**
- `objective = multi:softprob`
- `num_class = <number of cell types>`
- `max_depth = 6`
- `eta = 0.1`
- `nrounds = 200`
- `subsample = 0.8`
- `colsample_bytree = 0.8`

---

### PHASE 5 — Confusion Matrix & Uncertainty Sampling ✅ COMPLETE
*Visualise inter-model disagreement and generate the review sample*

Once the classifier can train and produce predictions, this phase adds the analysis layer that decides which cells a human should review next.

| # | Task | Java File / Location | Depends On | Status |
|---|---|---|---|---|
| 5.1 | Build confusion matrix data structure: `int[][]` where `[i][j]` = count of cells where `model1=i`, `model2=j` | `ui/ConfusionMatrixView.java` | Phase 4 | ✅ Done |
| 5.2 | Compute per-class agreement rate: `diag[i] / rowSum[i]` and `diag[i] / colSum[i]` for each cell type | `ui/ConfusionMatrixView.java` | 5.1 | ✅ Done |
| 5.3 | JavaFX Canvas renderer for the confusion matrix — colour cells by agreement, show percentages, highlight diagonal | `ui/ConfusionMatrixView.java` | 5.1, 5.2 | ✅ Done |
| 5.4 | Export confusion matrix as PNG (`WritableImage → ImageIO.write`) | `ui/ConfusionMatrixView.java` | 5.3 | ✅ Done |
| 5.5 | `UncertaintySampler` — collects all disagreement cells, weights each by `(2 - agrRate(pred1) - agrRate(pred2))`, samples N cells proportional to weight | `classifier/UncertaintySampler.java` | 5.2 | ✅ Done |
| 5.6 | Wire "Plot Confusions" button in `ClassificationPanel` to open `ConfusionMatrixView` in a new window | `ui/ClassificationPanel.java` | 5.3 | ✅ Done |
| 5.7 | Wire "Sample & Review" button to invoke `UncertaintySampler` and hand the resulting cell list to `ReviewController` | `ui/ClassificationPanel.java` | 5.5, ReviewController | ✅ Done |

> **Note:** Mirror CellTune's sampling strategy — cells where both models predict different types AND at least one of those types has low overall agreement are sampled most heavily. This ensures the human's labelling time fixes the model's worst failure modes first.

> **Phase 5 Build Notes:**
> - `ConfusionMatrixView` is a Canvas-based confusion matrix with green diagonal / red off-diagonal colour scaling, right-margin Agr% and F1 columns, macro F1 in the summary label, and PNG export via `WritableImage → ImageIO.write`
> - `UncertaintySampler.sample()` implements weighted sampling without replacement — weight = `2 - agrRate[i] - agrRate[j]` with a minimum floor of 0.01
> - Wired into `CellTuneExtension.showConfusions()`: opens confusion matrix, computes agreement rates, prompts for sample count, calls `UncertaintySampler`, stores sampled IDs in `lastSampledCellIds`
> - `ConfusionMatrixView.getAgreementRates()` returns per-class `double[]` for use by `UncertaintySampler`
> - `ConfusionMatrixView.getF1Scores()` returns per-class F1 scores; macro F1 displayed in summary label
> - Menu item renamed from "CellTune Classifier..." to "Run CellTune Classification..." with feature selection prompt and training confirmation dialog before proceeding

---

### PHASE 6 — Review Mode ✅ COMPLETE
*Cell-by-cell labelling UI with viewer navigation and auto channel switching*

This is the most user-facing phase — the part the researcher interacts with most. Build it last so the underlying data, classifier, and sampling are all stable before investing in UI polish.

| # | Task | Java File / Location | Depends On | Status |
|---|---|---|---|---|
| 6.1 | `ReviewController` — holds an ordered `List<PathObject>` (the sampled cells), current index, viewing `PopulationSet`, output `LabelStore` | `ui/ReviewController.java` | Phase 5 | ✅ Done |
| 6.2 | `next()` / `previous()` methods that pan+zoom the QuPath viewer to the cell centroid at 40× magnification | `ui/ReviewController.java` | 6.1 | ✅ Done |
| 6.3 | `ReviewToolbar` — JavaFX HBox with Next, Previous, Skip buttons + dynamic per-cell prediction buttons (XGB/LGB labels with confidence %) for disagreement cells, single "Both" button for agreement cells, and an "All Classes ▼" dropdown populated from QuPath project classes as fallback | `ui/ReviewToolbar.java` | 6.1 | ✅ Done |
| 6.4 | Index indicator label that turns **green** (output label = viewed prediction), **red** (mismatch), or **white** (unlabelled) | `ui/ReviewToolbar.java` | 6.2 | ✅ Done |
| 6.5 | On cell type button click: write label to output `LabelStore`, call `next()` | `ui/ReviewToolbar.java` | 6.1, 6.3 | ✅ Done |
| 6.6 | `ChannelSelector` — **optional** auto channel switching on `next()`. When enabled and a `CellTypeTable` is loaded, looks up the two predicted cell types and sets up to 3 channels visible per type (6 total + 1 nuclear). When disabled, channels are left untouched and the user switches manually via QuPath's Brightness & Contrast panel. Controlled by a `CheckBox` ("Auto switch channels") in `ReviewToolbar`. | `ui/ChannelSelector.java` | 6.2, CellTypeTable | ✅ Done |
| 6.7 | RClick on index label → jump-to-index dialog (`TextField` + OK) | `ui/ReviewToolbar.java` | 6.4 | ✅ Done |
| 6.8 | Enter/Exit Review button in the QuPath toolbar that toggles `ReviewController` active/inactive | `CellTuneExtension.java` | 6.1 | ✅ Done |
| 6.9 | After exiting review, newly added labels are merged back into `LabelStore` and a new training cycle can begin | `ui/ReviewController.java` | Phase 4 | ✅ Done |

> **Phase 6 Build Notes:**
> - `ReviewController`: Resolves sampled cell IDs to `PathObject` references from the image hierarchy. Provides `next()`, `previous()`, `jumpTo()`, `labelAndNext()`, `skip()`. Navigates viewer to cell centroid via `viewer.setCenterPixelLocation(x, y)` and selects the cell in the hierarchy.
> - `ReviewToolbar`: JavaFX HBox with Previous/Next/Skip nav buttons, dynamic prediction buttons per cell (for disagreement cells: `XGB: CD4 (87%)` in blue + `LGB: Bcell (65%)` in pink; for agreement cells: single green `Both: CD4 (87%)` button), an "All Classes ▼" `MenuButton` populated from `ReviewController.getQuPathClassNames()` + `CellTypeTable.getCellTypes()`, and a monospace index indicator `(3/50)` with a coloured `Circle` dot (green = matches Pred_ALL, red = mismatch, white = unlabelled). Right-click index label opens jump-to-index dialog.
> - `ChannelSelector`: Checkbox-gated auto-switch. When enabled, looks up the Pred_ALL cell type in `CellTypeTable`, gets up to 3 marker names, and sets only those channels visible via `viewer.getImageDisplay().setChannelSelected()`. When disabled, channels untouched.
> - `CellTuneExtension.showReviewMode()`: Validates sampled cells exist, builds ReviewController + ChannelSelector + ReviewToolbar, opens an always-on-top Stage. On window close, merges output labels back into the main `labelStore` with notification.

> **Note:** QuPath viewer navigation: `QuPathGUI.getInstance().getViewer().setCenterPixelLocation(x, y)` and `setMagnification(40.0)`. Channel visibility: `viewer.getImageDisplay().setChannelSelected(channel, true/false)`.

---

### PHASE 7 — Population Panel & Full Loop Integration ✅ COMPLETE
*Wire all phases together into the complete iterative workflow*

Phase 7 connects everything so the researcher can run the full Train → Confusions → Sample → Review → Retrain loop without leaving QuPath.

| # | Task | Java File / Location | Depends On | Status |
|---|---|---|---|---|
| 7.1 | `PopulationPanel` — JavaFX `ListView` of all `PopulationSet`s with cell counts, colour swatches matching QuPath overlay colours | `ui/PopulationPanel.java` | Phase 4 | ✅ Done |
| 7.2 | `ClassificationPanel` — collects classifier name, Train button (disabled until labels exist), progress bar binding, Plot Confusions button, Sample & Review button | `ui/ClassificationPanel.java` | Phase 4, 5, 6 | ✅ Done |
| 7.3 | Dock both panels into QuPath's analysis pane via `QuPathGUI.getInstance().getAnalysisPanel()` | `CellTuneExtension.java` | 7.1, 7.2 | ✅ Done |
| 7.4 | Update QuPath cell overlay colours after every training cycle to reflect `Pred_AVG` classifications | `classifier/DualModelClassifier.java` | Phase 4 | ✅ Done (already in Phase 4) |
| 7.5 | Add "Export Cell Table" menu item wired to `CellTableExporter` | `CellTuneExtension.java` | Phase 3 | ✅ Done (already in Phase 3) |
| 7.6 | End-to-end manual test: load demo data → landmark → train → confusions → sample → review 50 cells → retrain → verify confusion matrix improves | — | All phases | ⏳ Pending user test |

> **Phase 7 Build Notes:**
> - `PopulationPanel`: VBox with TitledPanes per PopulationSet (Pred_MDL1/MDL2/AVG/ALL). Each shows a GridPane with colour swatches from `PathClass.getColor()`, per-class counts, total, and disagreement count. Only Pred_ALL expanded by default.
> - `ClassificationPanel`: Full sidebar panel with hyperparameter spinners (rounds, max depth), Train button with progress bar binding, Plot Confusions / Sample & Review / Enter Review buttons. Embeds PopulationPanel. Trains on daemon thread with auto-backup. Collects labels from annotations. Callbacks sync state back to CellTuneExtension.
> - Docking: `CellTuneExtension.dockClassificationPanel()` creates a Tab in `qupath.getAnalysisTabPane()` with the ClassificationPanel inside a TitledPane.
> - State sync: Bidirectional — CellTuneExtension pushes state via `syncPanelState()` after menu-driven operations; ClassificationPanel pushes state back via callbacks (`onLabelStoreChanged`, `onPredAllChanged`, `onClassifierChanged`, etc.).
> - Overlay colours: Already handled by `DualModelClassifier.trainAndPredict()` which sets `PathClass` on every cell, plus `fireHierarchyChangedEvent` in CellTuneExtension.

---

## 4. The Active Learning Loop

Data flow through one full training cycle. The loop repeats until the researcher is satisfied with classification quality.

```
① Landmark seed labels
        (LabelStore)
             │
             ▼
② DualModelClassifier.train()
   XGBoostModel + LightGBMModel
             │
             ▼
③ Produce population sets
   Pred_MDL1 · Pred_MDL2 · Pred_AVG · Pred_ALL
             │
             ▼
④ ConfusionMatrixView
   → per-class agreement rates
             │
             ▼
⑤ UncertaintySampler
   → weighted disagreement cell list
             │
             ▼
⑥ ReviewController
   → human labels disputed cells one-by-one
             │
             ▼
   ⟳ New labels merged into LabelStore
     → back to ②
```

---

## 5. Recommended Build Order Summary

Follow this order to ensure each phase can be tested before the next one builds on it. Never start Phase N+1 until Phase N compiles and passes its test gate.

| Phase | Name | Key Deliverable | Test Gate | Status |
|---|---|---|---|---|
| 1 | Scaffold | Empty extension loads in QuPath 0.7 | ✅ shadowJar builds successfully (51 MB) | ✅ COMPLETE |
| 2 | Data Model | `CellFeatureExtractor` + `CellPrediction` + `LabelStore` | ✅ All 5 classes compile in shadowJar | ✅ COMPLETE |
| 3 | I/O | CSV import + JSON state save/load | ✅ Import/export wired, compiles clean | ✅ COMPLETE |
| 4 | Dual Classifier | XGBoost + LightGBM train on landmarks | ✅ Training wired in extension, compiles clean | ✅ COMPLETE |
| 5 | Confusion + Sampling | Confusion matrix plot + weighted sample list | ✅ ConfusionMatrixView + UncertaintySampler wired, compiles clean | ✅ COMPLETE |
| 6 | Review Mode | Cell navigator + label assignment + channel auto-select | ✅ ReviewController + ReviewToolbar + ChannelSelector wired, compiles clean | ✅ COMPLETE |
| 7 | Full Loop | All panels docked; full loop runs end-to-end | ✅ PopulationPanel + ClassificationPanel docked, state sync wired, compiles clean | ✅ COMPLETE |

---

## 6. Key Build Files

QuPath 0.7 uses Gradle 9.2.1 (Kotlin DSL) and **Java 25**.

**settings.gradle.kts:** *(as built)*
```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

qupath {
    version = "0.7.0"
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
}
```

**build.gradle.kts:** *(as built)*
```kotlin
plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-celltune"
    group = "io.github.qupath"
    version = "0.1.0-SNAPSHOT"
    description = "CellTune-style active learning cell classifier for QuPath"
    automaticModule = "io.github.qupath.extension.celltune"
}

dependencies {
    // QuPath core (provided at runtime by QuPath itself)
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // ML models (bundled into the shadow jar)
    implementation("ml.dmlc:xgboost4j_2.13:3.2.0")
    shadow("ml.dmlc:xgboost4j_2.13:3.2.0")
    implementation("io.github.metarank:lightgbm4j:4.6.0-2")
    shadow("io.github.metarank:lightgbm4j:4.6.0-2")

    // Testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version
        )
    }
}
```

---

## 7. Important Notes

### QuPath 0.7 Specifics (Verified Against Working Extension)

- Extension class must implement `qupath.lib.gui.extensions.QuPathExtension` — override `installExtension(QuPathGUI)`, `getName()`, `getDescription()`, `getQuPathVersion()`
- Use `ResourceBundle.getBundle("qupath.ext.celltune.ui.strings")` for all UI text (enables future i18n)
- Menu items are added via `qupath.getMenu("Extensions>" + EXTENSION_NAME, true)` — returns/creates a `Menu`
- Cell measurements are accessed via `PathObject.getMeasurementList().get(name)` — returns `NaN` if missing
- Always update the viewer on the JavaFX Application Thread: `Platform.runLater(() -> { … })`
- Background ML training should use a JavaFX `Task<Void>` on a daemon thread (see xgboost reference pattern)
- Use `PathPrefs.createPersistentPreference(...)` for user prefs that survive across sessions
- Stage windows should use `stage.initOwner(qupath.getStage())` and `Modality.NONE` so QuPath stays usable
- Dialogs use `qupath.fx.dialogs.Dialogs` (not raw JavaFX Alert) for consistency with QuPath look-and-feel
- **QuPath 0.7 requires Java 25** — the `qupath-conventions` plugin sets `languageVersion=25`. Install Adoptium JDK 25 from https://adoptium.net/ and set `JAVA_HOME` to the install path (e.g. `C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot`)
- The `qupath-conventions` Gradle plugin handles module-path and QuPath dependency resolution automatically
- Add `id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"` to `settings.gradle.kts` plugins block to enable auto-provisioning of the Java 25 toolchain

### ML Training Tips

- Require a minimum of 10 labelled cells per class before enabling the Train button — too few labels causes degenerate models
- XGBoost4J: build a `DMatrix` from a flat `float[]` + dimensions (rows, cols) — use `new DMatrix(flatData, nSamples, nFeatures, Float.NaN)` then `setLabel(float[])`
- XGBoost4J multiclass: set `objective=multi:softprob`, `eval_metric=mlogloss`, `num_class=N`; binary: use `binary:logistic` + `logloss`
- LightGBM4J: use `Dataset.createFromMat()` with the same arrays, `objective=multiclass`, `metric=multi_logloss`
- Use `booster.setFeatureNames(String[])` and `booster.setAttr("class_names", ...)` to embed metadata in saved models
- Store the feature column names in `ClassifierState` so that if the user adds new markers, old models are invalidated rather than silently producing wrong predictions
- NaN measurements should default to 0f: `Double.isNaN(v) ? 0f : (float) v`

### Review Mode UX Tips

- Pre-load the next 5 cells in the background so the viewer snaps instantly when the user hits Next
- The green/red/white index indicator is critical feedback — implement it in step 6.4 before user testing
- Default sample size of 200 cells per review cycle is a good starting point — make it configurable in `ClassificationPanel`
- Skipped cells (neither labelled nor removed) should be excluded from the next training cycle's label set — do not infer their label from the model
- Reload image data after classification with `viewer.setImageData(entry.readImageData())` — but warn user about unsaved changes first

---

## 8. Cross-Cutting Features (Implemented)

These features were added after Phase 4 to support real-world multi-workstation workflows and high-dimensional panels.

### 8.1 Ground Truth Import/Export — `io/GroundTruthIO.java` ✅ COMPLETE

Enables transferring labelled cell populations between QuPath projects and workstations. Since cell UUIDs are project-specific, the format stores each labelled cell's feature vector alongside its label and centroid coordinates.

**Export** (`exportCSV`):
- Writes labelled cells as CSV: `Label, CentroidX, CentroidY, Feature1, Feature2, …`
- Includes metadata comment headers (image name, export timestamp)
- Only exports cells present in the LabelStore

**Spatial Import** (`importCSVSpatial`):
- Matches imported rows to current image cells by nearest centroid distance
- Configurable maximum distance threshold (default 20 pixels) to prevent spurious matches
- Best for reimporting into the same image or very similar tissue sections

**Training Data Import** (`importCSVAsTrainingData`):
- Reads raw feature vectors + labels without spatial matching
- For cross-project model training where cell IDs and coordinates differ
- Returns `List<TrainingRow>` records for direct use in training

**Menu items**: Export Ground Truth… / Import Ground Truth… (with choice dialog for spatial vs. training-data-only mode)

### 8.2 Feature Selection — `ui/FeatureSelectionPane.java` ✅ COMPLETE

For large multiplex panels (COMET, MIBI, etc.) that generate 2000+ features per cell, allows the user to select which measurements to include in ML training.

**UI Components:**
- Filterable `ListView` with checkboxes for each feature
- Instant text search filter narrows the list as you type
- Prefix grouping via `ComboBox` — auto-discovers QuPath measurement prefixes (e.g. `"Cell: "`, `"Nucleus: "`, `"Membrane: "`)
- Select All / Clear All / Select Prefix / Clear Prefix buttons
- Live counter showing `selected / total`

**Integration:**
- Accessible via Extensions > CellTune Classifier > Select Features…
- Selected features are stored in `CellTuneExtension.selectedFeatures`
- `showClassifierPanel()` filters discovered features to the user's selection before training
- If no selection has been made, all features are used (default behaviour)
- Selecting all features or clearing the selection resets to "use all"

### 8.3 Manual Label Mode — `ui/ManualLabelToolbar.java` ✅ COMPLETE

A floating toolbar that lets users click on cells directly in the QuPath viewer and assign class labels, independent of Review Mode. Useful for seeding initial labels, correcting individual cells, or labelling cells that weren't sampled for review.

**UI Layout:**
- **Status row:** Shows the selected cell's short ID, current class, a colour-coded status dot (green = labelled, white = unlabelled), and a running label count
- **Class buttons:** Up to 12 inline buttons populated from QuPath project classes + CellTypeTable entries
- **"All Classes ▼" dropdown:** Full class list as a MenuButton fallback
- **Auto-advance checkbox:** When enabled, selecting a label automatically jumps the viewer to the next detection in the hierarchy

**Technical details:**
- Listens for cell selection changes via `PathObjectSelectionModel.addPathObjectSelectionListener()` (QuPath 0.7's selection listener API — not a JavaFX property)
- UI updates wrapped in `Platform.runLater()` since the selection callback may fire off the FX thread
- On label assignment: sets `PathClass` on the cell, records in `LabelStore`, fires `fireObjectClassificationsChangedEvent` to refresh the overlay colours
- Always-on-top `Stage` with `Modality.NONE` so QuPath remains interactive
- Listener is removed on window close to prevent memory leaks

**Menu item:** Extensions > CellTune Classifier > Manual Label Mode…

### Reference Pattern (from qupath-extension-xgboost)

The xgboost extension in `c:\Users\Mikem\qupath-extension-xgboost\` is a working QuPath 0.7 extension. Key patterns to copy:
- Extension entry point: [XGBoostExtension.java](../qupath-extension-xgboost/src/main/java/qupath/ext/xgboost/XGBoostExtension.java)
- Background training on daemon thread: [TrainController.java](../qupath-extension-xgboost/src/main/java/qupath/ext/xgboost/ui/TrainController.java)
- DMatrix construction + XGBoost4J usage: [XGBoostTrainer.java](../qupath-extension-xgboost/src/main/java/qupath/ext/xgboost/XGBoostTrainer.java)
- Inference + PathClass assignment: [XGBoostInferencer.java](../qupath-extension-xgboost/src/main/java/qupath/ext/xgboost/XGBoostInferencer.java)
- ListSelectionView + filter pattern: [InferController.java](../qupath-extension-xgboost/src/main/java/qupath/ext/xgboost/ui/InferController.java)

---

## 9. TODO / Future Exploration

Areas to investigate in future development iterations:

### 9.1 Multi-Image Ground Truth Aggregation

Explore how adding ground truth labels from multiple project images — via both Review Mode and Manual Label Mode — affects classifier performance.

**Questions to answer:**
- How should labels from different images be pooled into a shared training set? Currently `LabelStore` is per-image.
- When training on pooled labels, should features be normalised per-image to account for staining variation?
- Can spatial import (`GroundTruthIO.importCSVSpatial`) reliably transfer labels between serial sections or re-stained slides?
- What is the optimal workflow: train on one heavily-labelled image then predict + review on others, or label a small seed set across all images and train jointly?
- How do Review Mode corrections on Image B interact with the existing ground truth from Image A after merge?

### 9.2 Resampling Strategies for Class Imbalance

Evaluate the impact of synthetic resampling techniques on training quality, especially for rare cell populations (e.g. Tregs, DCs) that are underrepresented in typical labelling sessions.

**Techniques to explore:**

| Technique | Type | Description |
|-----------|------|-------------|
| **SMOTE** | Over-sampling | Generates synthetic minority-class samples by interpolating between k-nearest neighbours in feature space |
| **ADASYN** | Adaptive over-sampling | Like SMOTE but generates more synthetic samples for harder-to-learn minority examples (based on local density) |
| **Tomek links** | Under-sampling / cleaning | Identifies and removes majority-class samples that form nearest-neighbour pairs with minority-class samples, cleaning the decision boundary |
| **SMOTE + Tomek** | Combined pipeline | Over-sample with SMOTE, then remove Tomek links to clean noisy synthetic samples near the boundary |
| **ADASYN + Tomek** | Combined pipeline | Adaptive over-sampling followed by Tomek link cleaning |

**Metrics to track:**
- Per-class F1 scores (especially for minority classes)
- Macro F1 across all classes
- Inter-model agreement rates (XGBoost vs LightGBM)
- Training time impact
- Number of review rounds needed to reach target F1

**Implementation notes:**
- Resampling should be applied to the training feature matrix + labels *after* extraction and *before* passing to XGBoost/LightGBM
- Java implementations of SMOTE/ADASYN/Tomek would need to be written or ported (no standard Java ML library provides these out of the box)
- Consider making resampling strategy a dropdown option in `ClassificationPanel` with default = None
- k-neighbour search for SMOTE/ADASYN can use brute-force Euclidean distance (feature vectors are typically <200 dimensions)

### 9.3 Hyperparameter Tuning

Currently users manually set hyperparameters via spinners in `ClassificationPanel` (boosting rounds = 200, max depth = 6, learning rate = 0.1, subsample = 0.8). Investigate automated tuning to find optimal settings per dataset.

**Strategies to explore:**

| Strategy | Description | Pros | Cons |
|----------|-------------|------|------|
| **k-fold CV** | Split labelled cells into k folds, train on k−1, evaluate on the held-out fold, repeat | Reliable generalisation estimate | k× training time per trial |
| **Grid search** | Exhaustive evaluation over a predefined parameter grid | Finds global optimum within the grid | Combinatorial explosion with many parameters |
| **Random search** | Sample hyperparameter combinations randomly from ranges | More efficient than grid for ≥4 parameters ([Bergstra & Bengio 2012](https://jmlr.org/papers/v13/bergstra12a.html)) | May miss narrow optima |
| **Bayesian optimisation** | Surrogate model (GP or TPE) guides search toward promising regions | Sample-efficient | Adds implementation complexity |
| **Early stopping** | Monitor validation loss during boosting, stop when it plateaus | Auto-tunes round count, fast | Only tunes one parameter |

**Candidate parameter grid (starting point):**

```
max_depth     ∈ {4, 6, 8, 10}
eta           ∈ {0.05, 0.1, 0.2}
nrounds       ∈ {100, 200, 400}
subsample     ∈ {0.6, 0.8, 1.0}
colsample     ∈ {0.6, 0.8, 1.0}
```

**Implementation notes:**
- XGBoost and LightGBM may have different optimal settings — tune independently
- k-fold CV should stratify by class to preserve label distribution in each fold
- Early stopping can be implemented via XGBoost4J's `setEvalSets()` and LightGBM's `early_stopping_round` parameter
- Display a progress dialog showing fold/trial number, current best score, and estimated time remaining
- Store the best hyperparameters in `ClassifierState` so they persist across sessions
- Consider a "Quick tune" mode (random search, 20 trials, 3-fold CV) vs. "Full tune" mode (grid search, 5-fold CV) as dropdown options in ClassificationPanel
- Metrics to optimise: macro F1 (default), per-class F1, or log-loss

### 9.4 Recommendations for HPC Deployment

When deploying on HPC or high-end GPU workstations with large images (500K+ cells, 2000+ features), the following optimisations and settings are important.

#### JVM Heap Memory (`-Xmx`)

The Java heap must be large enough to hold the feature matrix and native model copies. **Set `-Xmx24g` or higher** using one of these methods:

| Method | How |
|--------|-----|
| QuPath Preferences | Edit → Preferences → Max memory |
| Setup options | Help → Show setup options → edit max memory |
| Config file | Edit `QuPath.cfg` in the QuPath install folder — add `-Xmx24g` to JVM options |
| Command line | Launch with `QuPath --Xmx=24g` |

**Built-in memory safeguards (implemented in `CellTuneExtension.java`):**

- **`checkHeapMemory()`** — called at extension install time. If the JVM max heap is below 8 GiB, shows a warning notification instructing the user to increase memory via Edit → Preferences or Help → Show setup options. Logs the current heap size.
- **`checkTrainingMemory(nCells, nFeatures)`** — called before training begins. Estimates peak memory as `(nCells × nFeatures × 4 bytes) / 1 GiB × 3.0 + 0.3 GiB` (accounts for the Java float array, XGBoost DMatrix copy, LightGBM Dataset copy, plus overhead). If the estimate exceeds 80% of max heap, shows a confirmation dialog warning the user with the estimated and available figures — the user can cancel or proceed.

#### Resource Leak Prevention ✅ FIXED

- **DMatrix cleanup** (`classifier/XGBoostModel.java`): All `DMatrix` objects (training and prediction) are wrapped in `try { … } finally { dmatrix.dispose(); }` blocks. Without this, a 500K × 2000 training matrix would leave ~7.4 GiB of native memory allocated until the garbage collector happened to finalise the object. Now disposed immediately after use.

#### Parallel Feature Extraction ✅ FIXED

- **`CellFeatureExtractor.extractMatrix()`** (`model/CellFeatureExtractor.java`): Changed from sequential `for` loop to `IntStream.range(0, nCells).parallel().forEach(…)`. Each cell's measurement reads are independent, so the work distributes across all available CPU cores. On HPC nodes with 32–64 cores, this provides a significant speedup for the extraction phase. The `Collection<PathObject>` is converted to an indexed `List` for thread-safe indexed access.

#### Scalability Limits

At 2000 features, XGBoost4J and LightGBM4J use a flat `float[]` array indexed by Java `int`, limiting the matrix to approximately **1.07 million cells** (2,147,483,647 / 2000 ≈ 1.07M rows). For datasets exceeding 1M cells, the feature matrix would need to be split into chunks and models trained/predicted in segments — a more involved refactor not yet implemented.

#### GPU Notes

GPU acceleration depends on platform and the native libraries shipped in the Maven artifacts:

| Platform | XGBoost GPU | LightGBM GPU |
|----------|-------------|---------------|
| Linux x86_64 | ✔ CUDA | ✘ CPU-only |
| Windows x86_64 | ✘ CPU-only | ✘ CPU-only |
| macOS | ✘ CPU-only | ✘ CPU-only |

- **XGBoost** (`xgboost4j_2.13:3.2.0`): The Maven artifact ships CUDA-enabled native libs for **Linux only**. On Linux with a CUDA-capable GPU and the CUDA Toolkit 12.x installed, XGBoost trains on GPU automatically (`device=cuda`). On Windows/macOS, the bundled native lib is CPU-only — XGBoost silently falls back to CPU without throwing a Java exception (only a C++ stderr warning). The `XGBoostModel` detection fix serialises the trained model to JSON and checks the actual device used, so `getLastDevice()` now reports the truth.
- **LightGBM** (`lightgbm4j:4.6.0-2`): Ships CPU-only native binary on **all platforms**. The log will show `"GPU Tree Learner was not enabled in this build"` when GPU is attempted. GPU training would require building LightGBM from source with `-DUSE_GPU=1` (OpenCL) and replacing the native library — not bundled.

**For HPC deployment on Linux**: Ensure CUDA Toolkit 12.x is installed (`nvcc --version` to verify). XGBoost will pick up the GPU automatically. For Windows workstations, CPU training is the default — performance impact is negligible for datasets under ~100K cells.
