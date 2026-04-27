# Coding Conventions

**Analysis Date:** 2026-04-27

## Naming Patterns

**Classes:**
- `PascalCase` throughout: `DualModelClassifier`, `CellFeatureExtractor`, `LabelStore`
- UI classes named by role + widget type: `ReviewToolbar`, `ClassificationPanel`, `FeatureSelectionPane`
- Model wrappers named by library + `Model`: `XGBoostModel`, `LightGBMModel`, `RandomForestModel`
- Data holders use descriptive nouns: `CellPrediction`, `PopulationSet`, `ClassifierState`
- `record` used for immutable result types: `ShapResult`, `TrainingRow`

**Methods:**
- `camelCase` following Java conventions
- Boolean accessors: `isTrained()`, `hasLabel()`, `hasTransforms()`
- Getters: `getLabel()`, `getFeatureNames()`, `getClassNames()` — no Lombok
- Mutators: `setLabel()`, `setTransform()`, `setNumRounds()`
- Factory-style: `toClassifierState()`, `toTransformMap()`, `fromTransformMap()`
- Background tasks: `trainAndPredict()`, `predictOnly()` — named to make thread requirements obvious

**Variables:**
- `camelCase` throughout
- Local variables use `var` only for obvious types (e.g., `var entry = project.getEntry(...)`)
- ML primitive arrays use short descriptive names: `flatData`, `labelArray`, `chunkData`
- Loop counters follow pattern: `nSamples`, `nFeatures`, `nClasses` for ML dimension names

**Constants:**
- `UPPER_SNAKE_CASE` for `static final` fields:
  ```java
  private static final int PREDICT_CHUNK_SIZE = 100_000;
  private static final int MAX_SHAP_SAMPLES   = 5_000;
  private static final Logger logger = LoggerFactory.getLogger(DualModelClassifier.class);
  private static final ResourceBundle resources =
          ResourceBundle.getBundle("qupath.ext.celltune.ui.strings");
  ```
- Numeric literals use `_` separators for readability: `100_000`, `5_000`

## Package Structure Conventions

All classes live under `qupath.ext.celltune`:

| Package | Contents |
|---------|----------|
| `qupath.ext.celltune` | Extension entry point only (`CellTuneExtension`) |
| `qupath.ext.celltune.classifier` | ML models, training orchestration, hyperparameter tuning |
| `qupath.ext.celltune.model` | Domain data types: feature extraction, normalization, label storage |
| `qupath.ext.celltune.io` | Persistence: project state, ground truth I/O, export |
| `qupath.ext.celltune.ui` | JavaFX UI components only — no ML logic |
| `qupath.ext.celltune.gating` | Auto-landmarking and gating rules |

**Rule:** UI packages must not import from `classifier` directly. Communication goes through callbacks set on `ClassificationPanel`.

## JavaFX Threading Rules

**Rule:** All JavaFX property reads/writes and `PathObject.setPathClass()` calls MUST run on the FX Application Thread.

**Correct pattern — batch and defer:**
```java
// In background thread: collect assignments into plain lists
List<PathObject> classifyObjects = new ArrayList<>(totalCells);
List<PathClass>  classifyClasses = new ArrayList<>(totalCells);

for (int i = 0; i < chunkSize; i++) {
    classifyObjects.add(cell);
    classifyClasses.add(PathClass.fromString(pred.avgLabel()));
}

// Apply on FX thread
Platform.runLater(() -> {
    for (int i = 0; i < classifyObjects.size(); i++) {
        classifyObjects.get(i).setPathClass(classifyClasses.get(i));
    }
});
```
Source: `DualModelClassifier.java` lines 372–424, 531, and `CellTuneExtension.java` lines 401, 561, 906, 921.

**Progress/status updates** from background threads:
```java
// DualModelClassifier — updates observable properties on FX thread:
private void updateStatus(String msg, double pct) {
    Platform.runLater(() -> {
        status.set(msg);
        progress.set(pct);
    });
}
```

**Dialogs** always on FX thread. Use `Dialogs.showErrorMessage(title, msg)` from `qupath.fx.dialogs`.

**Hierarchy events** fired after PathClass assignment — must also be on FX thread:
```java
Platform.runLater(() -> {
    // setPathClass calls here
    imageData.getHierarchy().fireHierarchyChangedEvent(this);
});
```

## QuPath API Usage Rules

**Only use public QuPath API.** Do not call internal methods in `qupath.lib.gui.scripting` or access private fields via reflection.

**Null-check `project.getEntry()` on every call** — it returns `null` when the image data was opened outside the project:
```java
var entry = project.getEntry(qupath.getImageData());
if (entry == null) return;   // or return false;
```
This pattern appears at `CellTuneExtension.java` lines 329–330, 347–348, 1123–1124.

**Always null-check the project itself** before dereferencing:
```java
if (project == null) return;
if (project == null || imageData == null) return;
```

**Guard before using the project path:**
```java
if (project != null && project.getPath() != null) { ... }
```

**`PathClass.fromString(name)`** is the correct factory — do not construct `PathClass` instances directly.

**Cell IDs:** Always obtained via `cell.getID().toString()`. Do not assume ID type.

## Error Handling Patterns

**Standard try/catch with SLF4J warn on recoverable errors:**
```java
try {
    ProjectStateManager.saveLabels(entry, labelStore);
} catch (IOException ex) {
    logger.warn("Failed to save labels for {} on image switch: {}",
            entry.getImageName(), ex.getMessage());
}
```
Log the image name and `ex.getMessage()` — not the full stack trace — for expected I/O failures.

**Full stack trace for unexpected errors (training failure, feature importance):**
```java
} catch (Exception ex) {
    logger.error("Training failed", ex);
    Platform.runLater(() -> Dialogs.showErrorMessage(EXTENSION_NAME,
            resources.getString("error.training-failed")));
}
```

**Validation at entry points** with `IllegalStateException`:
```java
if (nClasses < 2) {
    throw new IllegalStateException(
            "Need at least 2 classes to train, found " + nClasses);
}
```

**Native resource cleanup** (LightGBM/XGBoost objects) in `finally` or explicit `close()`:
```java
try {
    booster = LGBMBooster.create(dataset, params);
    // ... use booster
} finally {
    booster.close();
    dataset.close();
}
```

**GPU fallback pattern** — attempt GPU, catch any exception, retry with CPU:
```java
try {
    booster = LGBMBooster.create(dataset, gpuParams);
    // GPU training loop
} catch (Exception gpuEx) {
    logger.info("LightGBM GPU not available ({}), falling back to CPU", gpuEx.getMessage());
    booster = LGBMBooster.create(dataset, params); // CPU params
    // CPU training loop
}
```

## Logging Conventions

**Logger declaration** — one per class, `private static final`:
```java
private static final Logger logger = LoggerFactory.getLogger(DualModelClassifier.class);
```
Source: `CellTuneExtension.java:71`, `DualModelClassifier.java:42`, `LightGBMModel.java:23`, `RandomForestModel.java:22`.

**Log levels:**
- `logger.debug(...)` — extension already installed, minor state
- `logger.info(...)` — training complete, predictions applied, image switch, label counts
- `logger.warn(...)` — recoverable I/O failures (save/load labels, predictions); use `ex.getMessage()` not full trace
- `logger.error(...)` — unexpected failures (training, feature importance); pass exception object for stack trace

**SLF4J parameterized logging** — always use `{}` placeholders, never string concatenation:
```java
logger.info("Switched to image '{}' — {} labels loaded", imageName, labelStore.size());
logger.warn("Failed to load predictions for {}: {}", entry.getImageName(), ex.getMessage());
logger.error("Feature importance failed", ex);  // exception as last arg for stack trace
```

**Extension prefix** in info-level messages for multi-line training output:
```java
logger.info("[CellTune] {}", msg);
logger.info("[CellTune] [{}] {}", imgName, msg);
```

## Serialization Conventions

**Gson** is the only JSON library used. Always create with `GsonBuilder.setPrettyPrinting()` and store as a `private static final` constant:
```java
private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
```
Source: `ProjectStateManager.java:42`, `GroundTruthIO.java:39`.

**Model bytes** are serialized as Base64 strings within JSON:
```java
state.xgboostModelBase64  = Base64.getEncoder().encodeToString(xgboostBytes);
state.lightgbmModelBase64 = Base64.getEncoder().encodeToString(lightgbmBytes);
// ...
return Base64.getDecoder().decode(state.xgboostModelBase64);
```
Source: `ProjectStateManager.java` lines 137–146, 267–292.

**LabelStore** serializes to `Map<String, String>` (cellId → className) for simplicity:
```java
String json = GSON.toJson(labelStore.getAllLabels());
Map<String, String> labels = GSON.fromJson(json, Map.class);
```

**`SavedState`** POJO in `ProjectStateManager` uses public fields — Gson field-based serialization, no annotations needed.

**`FeatureNormalizer`** serialize/deserialize via `toTransformMap()` / `fromTransformMap(Map<String,String>)` — returns `{featureName: transformName}` maps for JSON storage.

**Write files as UTF-8:** `Files.writeString(path, json, StandardCharsets.UTF_8)`.

## UI String Externalization

**All user-visible strings must come from `strings.properties`**, never hardcoded in Java.

**ResourceBundle** loaded as a class-level static constant:
```java
private static final ResourceBundle STRINGS =
        ResourceBundle.getBundle("qupath.ext.celltune.ui.strings");
```
Location: `src/main/resources/qupath/ext/celltune/ui/strings.properties`

**Lookup pattern:**
```java
String title = STRINGS.getString("review.stage.title");
Button btn = new Button(STRINGS.getString("review.next"));
Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("error.training-failed"));
```

**Key naming convention:** `component.element` or `component.sub.element`:
- `menu.classify`, `menu.import.markers`
- `review.next`, `review.stage.title`
- `classify.train.button`, `classify.min_labels`
- `error`, `error.training-failed`, `error.no_detections`

**Multi-line values** use `\` continuation for help text:
```properties
sample.count.help = How many disagreement cells to sample.\n\n\
    200-300 is typically optimal.
```

## Feature Vector Column Ordering Rules

**CellFeatureExtractor contract:** A single `CellFeatureExtractor` instance fixes the ordered feature name list at construction time. Both XGBoost and LightGBM MUST share the same extractor instance to guarantee identical column ordering.

```java
// Correct — both models use the same extractor
CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames, normalizer);
classifier.trainAndPredict(allCells, labelStore, extractor, ...);

// Later for prediction — reuse same extractor; never construct with different featureNames
classifier.predictOnly(cells, extractor, log);
```

**Column order is locked at `trainAndPredict()` time:** `DualModelClassifier` calls `extractor.getFeatureNames()` once and stores it as `this.featureNames`. On `predictOnly()`, it validates `extractor.getNumFeatures() == this.featureNames.size()` and throws `IllegalStateException` if they differ.

**Feature name sanitization:** XGBoost and LightGBM reject special characters and non-ASCII chars in feature names. Both `XGBoostModel` and `LightGBMModel` call `XGBoostModel.sanitiseFeatureName()` before passing names to the native library.

**Class names** are sorted alphabetically at training time for deterministic class indices:
```java
Collections.sort(this.classNames); // deterministic ordering
```

## Known Anti-Patterns to Avoid

### Anti-Pattern 1: LightGBM SHAP in `computeFeatureImportance`

**What NOT to do:** Call `lgbModel.computeMeanAbsShap()` inside `DualModelClassifier.computeFeatureImportance()`.

**Why:** The method intentionally omits LightGBM SHAP and averages only XGBoost SHAP + Random Forest split importance. LightGBM SHAP is available on `LightGBMModel` directly (via `C_API_PREDICT_CONTRIB`) but is not included in the averaging because it can inflate or distort combined scores in multiclass settings when mixed with XGBoost TreeSHAP. Adding it would break the existing averaging contract.

**Correct approach:** If LightGBM SHAP is needed for a new use case, compute it separately and display independently — do not add it to the existing averaging loop in `computeFeatureImportance()`.

### Anti-Pattern 2: Calling `setPathClass()` Directly From a Background Thread

**What NOT to do:**
```java
// Background thread
for (PathObject cell : cells) {
    cell.setPathClass(PathClass.fromString(label));  // WRONG on background thread
}
```

**Why:** `PathObject.setPathClass()` will use a JavaFX property in future QuPath versions. Calling it off the FX thread causes race conditions and may silently drop updates or corrupt state.

**Correct approach:** Batch the assignments into plain lists on the background thread, then apply via `Platform.runLater()`:
```java
// Background thread — collect only
List<PathObject> objs = new ArrayList<>();
List<PathClass>  pcs  = new ArrayList<>();
for (PathObject cell : cells) {
    objs.add(cell);
    pcs.add(PathClass.fromString(label));
}
// Apply on FX thread
Platform.runLater(() -> {
    for (int i = 0; i < objs.size(); i++) objs.get(i).setPathClass(pcs.get(i));
    imageData.getHierarchy().fireHierarchyChangedEvent(this);
});
```

### Anti-Pattern 3: Using Hardcoded UI Strings

**What NOT to do:**
```java
Button btn = new Button("Train");  // WRONG — hardcoded string
Dialogs.showErrorMessage("Error", "Training failed."); // WRONG
```

**Correct approach:** Always look up from `ResourceBundle`:
```java
Button btn = new Button(STRINGS.getString("classify.train.button"));
Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("error.training-failed"));
```

### Anti-Pattern 4: Constructing Multiple `CellFeatureExtractor` Instances for the Same Training/Predict Pair

**What NOT to do:**
```java
classifier.trainAndPredict(cells, labels, new CellFeatureExtractor(featureNames), ...);
// ... later ...
classifier.predictOnly(cells, new CellFeatureExtractor(differentFeatureNames), log);
```

**Why:** Different instances can have different feature lists, causing silent column mismatch or an `IllegalStateException` that is harder to debug.

**Correct approach:** Create once and reuse, or ensure both use the same `featureNames` list.

---

*Convention analysis: 2026-04-27*
