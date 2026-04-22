# CellTune Extension — Known Risks & Weaknesses

This document records identified risks, limitations, and known weaknesses in the
CellTune QuPath extension. It is maintained alongside the codebase so that future
developers understand where care is needed, especially when updating for new QuPath
versions.

---

## 1. Fixed Issues (Resolved)

| # | Issue | Fixed In |
|---|-------|----------|
| 1 | `classifier` field used un-snapshotted inside background thread — race condition if retraining begins during SHAP computation | `showFeatureImportance()` in `CellTuneExtension.java` |
| 2 | Feature count not validated before `predictOnly()` — silent garbage output if feature set differs from training | `DualModelClassifier.predictOnly()` |
| 3 | `imageDataProperty` listener stored as anonymous lambda with no reference — could not be removed if extension reloaded | `dockClassificationPanel()` in `CellTuneExtension.java` |
| 4 | `setPathClass()` called from background training thread — potential issue if QuPath uses JavaFX `ObjectProperty` internally in future | `DualModelClassifier.trainAndPredict()` and `predictOnly()` — now batched in `Platform.runLater` |
| 5 | LightGBM `C_API_PREDICT_CONTRIB` causes SIGSEGV inside `lib_lightgbm.so` — crashes JVM with no recoverable exception | LightGBM SHAP block removed from `computeFeatureImportance()` |

---

## 2. Open Risks

### 2.1 QuPath API Compatibility

**Risk level: MEDIUM**

The extension uses only public `qupath.lib.*` APIs — no internal or deprecated APIs were
detected at time of writing (QuPath 0.7.0). However, the following APIs have changed
between QuPath minor versions in the past and should be monitored:

| API | Risk | Where Used |
|-----|------|-----------|
| `PathObjectSelectionModel.addPathObjectSelectionListener()` | Selection listener API has changed between 0.4 and 0.7; may change again | `ReviewController.java`, `ManualLabelToolbar.java` |
| `PathClass.fromString()` | Name resolution semantics (hierarchy separator, etc.) may change | `DualModelClassifier.java` throughout |
| `project.getEntry(imageData)` | Returns null if the image is open without a project — callers must null-check | `CellTuneExtension.java` |
| `qupath.getAnalysisTabPane()` | Tab pane API changed in 0.5→0.6; check on major version bumps | `dockClassificationPanel()` |
| `imageData.getHierarchy().fireHierarchyChangedEvent()` | Event firing API; preferred method may change | `CellTuneExtension.java` |

**Action for future QuPath updates:** Run the extension against the new QuPath version
with a test project and confirm that (a) the sidebar panel docks correctly, (b) training
completes without errors, (c) overlay colours update after prediction, and (d) Review
Mode navigation works.

---

### 2.2 XGBoost4J Native Library Compatibility

**Risk level: MEDIUM**

`ml.dmlc:xgboost4j_2.13:3.2.0` bundles pre-compiled native libraries for Linux, Windows,
and macOS. GPU (CUDA) support is only available for Linux. Known constraints:

- The artifact uses **Scala 2.13 cross-compilation naming** (`_2.13`). This naming is
  a Maven convention and does not require Scala at runtime, but it means Scala version
  compatibility must be verified on major upgrades.
- CUDA support requires the **CUDA Toolkit 12.x** on the host. Older or newer CUDA
  versions may fail silently (XGBoost falls back to CPU without error in some cases).
- The `xgboost4j` artifact version **must not be upgraded without testing** because
  the Java API (`Booster`, `DMatrix`) changed significantly between 1.x, 2.x, and 3.x.
  In particular, `predictContrib()` (TreeSHAP) is only public in 3.x.
- Building XGBoost4J with GPU support on Windows requires compiling from source.

---

### 2.3 LightGBM Native SHAP — Unresolvable Crash

**Risk level: HIGH (for SHAP only; classification is unaffected)**

Calling `LGBMBooster.predictForMat(data, nSamples, nFeatures, true,
PredictionType.C_API_PREDICT_CONTRIB)` causes a **SIGSEGV** inside `lib_lightgbm.so`
at `LightGBM::Predictor` — a fatal JVM crash with no Java exception.

- **Root cause**: Unknown — likely a memory layout mismatch or a bug in the
  `lightgbm4j 4.6.0-2` binding when SHAP contributions are requested. LightGBM
  classification prediction works correctly; only the SHAP contribution path crashes.
- **Current workaround**: LightGBM is excluded from `computeFeatureImportance()`.
  Feature importance only reflects XGBoost TreeSHAP (and Random Forest split counts).
- **Future fix**: Test with newer `lightgbm4j` releases. The crash may be fixed in a
  later binding version. When testing, call `predictForMat` with `C_API_PREDICT_CONTRIB`
  in isolation before wiring it back into the importance pipeline.
- **Code location**: `LightGBMModel.computeMeanAbsShap()` — method exists but is not
  called. The `DualModelClassifier.computeFeatureImportance()` block that called it has
  been removed.

---

### 2.4 Binary Classification SHAP Display

**Risk level: LOW**

For binary XGBoost models (2 classes), `predictContrib` returns a single set of SHAP
values (margin contributions for the positive class). The current code mirrors the same
absolute values for both classes (`result[0][f] = result[1][f]`).

This means the **Feature Importance bar chart shows identical bars for both classes**
when exactly 2 classes are trained. This is technically correct (the magnitude of
feature importance is the same regardless of which class you're "predicting"), but
it may confuse users who expect the charts to differ.

**Future improvement**: For binary classification, show a single combined chart with a
note that feature importance is class-agnostic (equal for both classes), rather than
offering a class selector.

---

### 2.5 `LabelStore` Not Thread-Safe

**Risk level: MEDIUM**

`LabelStore` uses a `LinkedHashMap` internally with no synchronisation. It is accessed
from:
- The JavaFX thread (UI updates, manual labelling)
- The training thread (reading labels to build training matrix)
- The image-switch listener thread (saving/loading per-image labels)

In practice, these operations do not overlap because training is a user-initiated
sequential action. However, if a user triggers manual labelling while training is running
(possible if the toolbar is left open), a `ConcurrentModificationException` could occur.

**Mitigation**: Use `Collections.synchronizedMap()` or `CopyOnWriteArrayList` in
`LabelStore`, or add `synchronized` blocks around the critical sections.

---

### 2.6 Heap Memory Risk with Very Large Datasets

**Risk level: MEDIUM on HPC**

The prediction loop allocates two `float[][]` matrices per chunk (`mdl1Probs`,
`mdl2Probs`) plus the chunk feature matrix. For 500K cells at 2,000 features,
chunk size 500K: `500K × 2000 × 4 bytes = 4 GB` per matrix (this is why chunking is
needed). Even chunked, peak memory per chunk is `~3 × chunk × features × 4 bytes`.

Additionally, the new FX-thread batch (fixing the setPathClass threading issue) holds
two `ArrayList`s of size `totalCells` in memory until the FX thread processes them.
For 2M cells this is `2 × 2M × (8 bytes ref + overhead) ≈ ~100 MB` — acceptable but
worth noting for very large slides.

**Mitigation already in place**: The pre-training memory estimate check in
`CellTuneExtension` warns if predicted peak usage exceeds 80% of available heap.

---

### 2.7 Early Stopping Validation Set Class Distribution

**Risk level: LOW**

When early stopping is enabled with class-imbalanced data, the 80/20 stratified
split is performed on the raw (un-resampled) training set. Resampling is then applied
only to the 80% training portion. This means the validation set reflects the true
(imbalanced) class distribution, while the training distribution is augmented.

This is actually the **correct** approach — the validation loss should reflect real-world
class frequencies. However, for extreme imbalances (e.g., 1% rare class), the validation
set may have too few minority-class samples to reliably measure early stopping, causing
the model to stop early based on noisy estimates.

**Mitigation**: The default patience of 20 rounds is conservative. For very imbalanced
datasets, disable early stopping and set rounds manually.

---

### 2.8 Random Forest SHAP Approximation

**Risk level: LOW**

Random Forest feature importance uses **normalised split counts** (how often each
feature is used in a split across all trees), not true SHAP values. This is a less
accurate measure than XGBoost TreeSHAP:

- Split counts are biased toward high-cardinality continuous features (features with
  many unique values get selected more often).
- They do not account for interaction effects between features.
- The same importance is shown for all classes (RF importance is class-agnostic in
  this implementation).

This limitation is noted in the UI subtitle ("averaged across active models"). If
both models are Random Forest, the feature importance chart should be interpreted with
caution.

---

### 2.9 Feature Name Sanitisation Mapping Not Persisted

**Risk level: LOW**

`XGBoostModel.sanitiseFeatureName()` replaces Greek letters (α, β, γ, …) and special
characters to produce XGBoost-safe feature names. These sanitised names are passed to
`booster.setFeatureNames()`. However, the mapping from original → sanitised names is
not stored in `ClassifierState`.

**Impact**: After loading a saved classifier, any API that returns feature names by
index (including SHAP output) will return sanitised names. The `FeatureImportanceView`
uses `featureNames` from `DualModelClassifier` (not from the booster), so the bar
chart labels are currently correct. This only becomes an issue if XGBoost's internal
feature name list is ever used directly (e.g., if `booster.getFeatureNames()` is
called for export or debugging).

---

## 3. Future QuPath Version Upgrade Checklist

When updating the extension for a new QuPath version, verify each item:

- [ ] `settings.gradle.kts`: update `qupath = "x.y.z"` version string
- [ ] `build.gradle.kts`: check `qupath-conventions` plugin compatibility
- [ ] Recompile and check for deprecation warnings — QuPath typically deprecates APIs
      one major version before removal
- [ ] Test `PathObjectSelectionModel.addPathObjectSelectionListener()` — if the
      interface no longer exists, update `ReviewController` and `ManualLabelToolbar`
- [ ] Test `qupath.getAnalysisTabPane()` — verify the sidebar still docks correctly
- [ ] Test `PathClass.fromString()` with hierarchical class names (e.g. `"Tumour: CD8"`)
- [ ] Test full training → prediction → review cycle on a real project
- [ ] Test save/load classifier state across QuPath versions (state files are backwards
      compatible as long as the JSON schema in `ClassifierState` is not changed)
- [ ] Test `Feature Importance...` menu item — XGBoost4J API may need updating if
      XGBoost version is bumped

---

## 4. Dependencies to Monitor

| Dependency | Current Version | Watch For |
|------------|----------------|-----------|
| `ml.dmlc:xgboost4j_2.13` | 3.2.0 | API changes in 4.x; GPU CUDA version compatibility |
| `io.github.metarank:lightgbm4j` | 4.6.0-2 | Fix for SHAP `C_API_PREDICT_CONTRIB` crash |
| QuPath conventions plugin | (bundled with QuPath) | Java language version changes |
| JavaFX | (bundled with QuPath) | Canvas API is stable; watch `Skin` and `Control` APIs |
| Gson | (bundled with QuPath) | No action needed — QuPath maintains this |
