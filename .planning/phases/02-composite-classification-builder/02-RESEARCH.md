# Phase 2 Research: Composite Classification Builder

**Phase:** 02-composite-classification-builder
**Researcher:** gsd-planner (resumed from prior session)
**Date:** 2026-04-28

---

## Standard Stack

| Layer | Library / Version |
|-------|------------------|
| Language | Java 25 |
| UI Framework | JavaFX (bundled with QuPath 0.7) |
| ML — Model 1 | XGBoost4J 3.2.0 (`xgboost4j-gpu_2.13`) |
| ML — Model 2 | LightGBM4J 4.6.0-2 |
| Serialization | Gson (GsonBuilder.setPrettyPrinting) |
| QuPath API | `qupath.lib.*` public APIs only (v0.7.0) |
| Dialogs | `qupath.fx.dialogs.Dialogs` |

---

## Architecture Patterns

### Background Thread + Platform.runLater

`DualModelClassifier` is the canonical pattern:
- `trainAndPredict()` runs on a caller-supplied background thread.
- All `PathObject.setPathClass()` calls are batched into a single `Platform.runLater()` invocation after the prediction loop.
- Progress/status are two `SimpleDoubleProperty` / `SimpleStringProperty` — updated via `updateStatus()` which itself calls `Platform.runLater()`.

`CompositeClassifier` must follow the same pattern exactly.

### Progress Dialog (Modality.NONE)

Pattern from `ClassificationPanel` (existing):
```java
Stage progressStage = new Stage();
progressStage.initModality(Modality.NONE);
progressStage.initOwner(qupath.getStage());
progressStage.setTitle("Composite Classification");
// ... ProgressBar + TextArea
```
Non-modal so QuPath remains responsive. Closed programmatically after task completes.

### Inference from ClassifierState

Load flow (from `DualModelClassifier.loadFromState()`):
1. Call `ProjectStateManager.loadBinaryState(project, markerName)` → `SavedState`
2. Decode bytes: `ProjectStateManager.decodeXGBoostModel(state)`, `decodeLightGBMModel(state)`
3. Create `XGBoostModel.fromBytes(bytes)` / `LightGBMModel.fromBytes(bytes)`
4. Get `classNames` and `featureNames` from `state.classNames`, `state.featureNames`
5. Call `model.predict(float[] flatData, int nSamples, int nFeatures)` → `float[][]` (nSamples × nClasses)
6. For binary: class index 0 = negative, class index 1 = positive (by convention of binary LabelStore)
7. Averaged prob = `(xgbProbs[i][1] + lgbProbs[i][1]) / 2f`; threshold at `>= 0.5` for positive

### CellFeatureExtractor

- `CellFeatureExtractor.extractMatrix(Collection<PathObject>)` → `float[]` row-major, size `nCells × nFeatures`
- Feature column ordering is fixed at construction time via `discoverFeatureNames(PathObject)` — CompositeClassifier must call `new CellFeatureExtractor(someCellForDiscovery)` using the same cell that will be classified, **or** create one extractor per binary classifier if their feature sets differ (they won't in practice — all use the same measurement list).
- Binary classifiers trained against the same measurement list, so one `CellFeatureExtractor` instance suffices per apply operation.

### PathClass Naming

`PathClass.fromString(String name)` — creates or returns cached PathClass.
- Composite name: sort marker names alphabetically, join with `+/-` suffix and `:` separator.
- Example: markers `["CD45", "CD3", "CD4"]`, cell positive for CD3 and CD4, negative for CD45:
  - Sorted: `CD3+`, `CD4+`, `CD45-`
  - Combined: `PathClass.fromString("CD3+:CD4+:CD45-")`
- `PathClass.NULL` for cells where all selected classifiers are untrained/skipped.

### ImageSelectionPane

Constructor: `new ImageSelectionPane(Window owner, List<String> allImageNames, String currentImageName)`
- `dialog.showAndWait()` — blocks until OK/Cancel.
- `getResult()` → `List<String>` of included image names, or `null` if cancelled.

### Menu Item Registration

Pattern from `CellTuneExtension.addMenuItems()`:
```java
MenuItem compositeItem = new MenuItem("Composite Classification...");
compositeItem.setOnAction(e -> showCompositeClassification(qupath));
compositeItem.disableProperty().bind(enableExtensionProperty.not());
menu.getItems().add(compositeItem);  // insert after binaryItem
```

### ProjectStateManager Composite Config

New methods to add, following `getBinaryDir()` pattern:
```java
public static void saveCompositeConfig(Project<?> project, List<String> selectedMarkers) throws IOException {
    Path dir = getCellTuneDir(project);
    Path path = dir.resolve("composite-config.json");
    // { "selectedMarkers": [...] }
    Files.writeString(path, GSON.toJson(Map.of("selectedMarkers", selectedMarkers)), UTF_8);
}

public static List<String> loadCompositeConfig(Project<?> project) throws IOException {
    Path path = getCellTuneDir(project).resolve("composite-config.json");
    if (!Files.exists(path)) return List.of();
    // deserialize, return selectedMarkers list
}
```

---

## Don't Hand-Roll

| Capability | Reuse |
|------------|-------|
| Feature extraction | `CellFeatureExtractor.extractMatrix()` |
| Image selection dialog | `ImageSelectionPane` |
| Binary state load | `ProjectStateManager.loadBinaryState()` |
| Binary registry listing | `BinaryClassifierRegistry.load(project)` → `LinkedHashMap<String,String>` |
| Cell detections | `imageData.getHierarchy().getDetectionObjects()` |
| Hierarchy refresh | `imageData.getHierarchy().fireObjectClassificationsChangedEvent(...)` |
| Progress/status binding | `DoubleProperty` / `StringProperty` → reuse DualModelClassifier pattern |
| PathClass creation | `PathClass.fromString(name)` — do NOT construct directly |

---

## Common Pitfalls

| Pitfall | Mitigation |
|---------|-----------|
| **LightGBM SHAP causes JVM SIGSEGV** | Never call `C_API_PREDICT_CONTRIB` or `predictContrib()` on LightGBM. Disabled permanently. |
| **XGBoost4J version lock** | Do NOT upgrade `xgboost4j-gpu_2.13` from 3.2.0. `predictContrib()` is 3.x-only. |
| **LabelStore not thread-safe** | Reads in background thread are safe (reads only), but never write from background thread. |
| **setPathClass from background thread** | Always batch into `Platform.runLater()`. |
| **project.getEntry() returns null** | Null-check before loading binary state per image in batch mode. |
| **Binary classifier classNames** | Binary classifiers use exactly 2 classes. Index 1 = positive class name. Validate before threshold. |
| **Feature column mismatch** | Create `CellFeatureExtractor` against a cell from the same imageData. Re-create per image in batch mode. |
| **Empty detection list** | Check before extractMatrix — zero detections → skip image (D-23). |

---

## Architectural Responsibility Map

| Tier | Component | Responsibility |
|------|-----------|---------------|
| Classifier | `CompositeClassifier` (new) | Load N binary states, extract features once, predict per marker, threshold, build PathClass names, batch-apply via Platform.runLater |
| IO | `ProjectStateManager` (extend) | `saveCompositeConfig` / `loadCompositeConfig` for `composite-config.json` |
| UI | `CompositeClassificationDialog` (new) | Modal dialog: checkbox list, Apply/Batch buttons, config persistence, progress dialog launch |
| Extension | `CellTuneExtension` (extend) | Add menu item + export entry, instantiate dialog on action |

---

## New Files

| File | Package | Description |
|------|---------|-------------|
| `src/main/java/qupath/ext/celltune/classifier/CompositeClassifier.java` | `classifier/` | Core inference: loads binary states, extracts features, applies threshold, builds PathClass |
| `src/main/java/qupath/ext/celltune/ui/CompositeClassificationDialog.java` | `ui/` | JavaFX dialog: checkbox list, Apply/Batch buttons, config persistence |

## Modified Files

| File | Change |
|------|--------|
| `src/main/java/qupath/ext/celltune/io/ProjectStateManager.java` | Add `saveCompositeConfig` / `loadCompositeConfig` |
| `src/main/java/qupath/ext/celltune/CellTuneExtension.java` | Add menu item + export entry + `showCompositeClassification()` method |
