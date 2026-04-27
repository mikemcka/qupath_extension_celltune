# Codebase Concerns

**Analysis Date:** 2026-04-27

---

## 1. Critical: LightGBM SHAP SIGSEGV Crash

**Severity: HIGH**

**What happens:**
Calling `LGBMBooster.predictForMat(data, nSamples, nFeatures, true, PredictionType.C_API_PREDICT_CONTRIB)`
causes a fatal **SIGSEGV inside `lib_lightgbm.so`** at `LightGBM::Predictor`. This is a native crash
that kills the entire JVM process — no Java exception is thrown, no recovery is possible.

**Root cause:** Unknown. Likely a memory layout mismatch or an upstream bug in the `lightgbm4j 4.6.0-2`
JNI binding when the SHAP contribution prediction path is exercised. Normal classification (`C_API_PREDICT_NORMAL`)
works correctly. Only the SHAP path (`C_API_PREDICT_CONTRIB`) crashes.

**Disabled code location:**
- Method `LightGBMModel.computeMeanAbsShap()` at
  `src/main/java/qupath/ext/celltune/classifier/LightGBMModel.java` — the method is fully
  implemented (lines ~253–295) but is **never called**.
- The call site in `DualModelClassifier.computeFeatureImportance()` has been removed.
  LightGBM is therefore silently excluded from the Feature Importance chart.

**Impact:**
- Feature importance displays XGBoost TreeSHAP only (or Random Forest split counts).
- If both models are LightGBM, the Feature Importance menu item will produce no
  LightGBM-sourced bars. No crash occurs because the call is gated out.
- Users are not informed that LightGBM is absent from the importance chart.

**Future fix path:**
1. Test newer `io.github.metarank:lightgbm4j` releases (watch for a version > 4.6.0-2).
2. In isolation, call `predictForMat` with `C_API_PREDICT_CONTRIB` on a minimal dataset
   before wiring back into the pipeline.
3. If crash is resolved, re-enable the call in `DualModelClassifier.computeFeatureImportance()`
   and update the Feature Importance view to show LightGBM contributions alongside XGBoost.
4. Reference: RISKS.md §2.3 and `Dependencies to Monitor` table.

---

## 2. Tech Debt: LabelStore Not Thread-Safe

**Severity: MEDIUM**

**What happens:**
`LabelStore` uses an internal `LinkedHashMap` with no synchronisation.
File: `src/main/java/qupath/ext/celltune/model/LabelStore.java`.

**Race condition scenario:**
Three threads can access the same `LabelStore` instance concurrently:

| Thread | Operation | Entry point |
|--------|-----------|-------------|
| JavaFX Application Thread | `setLabel()`, `removeLabel()` via UI | `ManualLabelToolbar`, Review Mode |
| Training thread (`CellTune-Training`) | Iterates `getAllLabels()` to build matrix | `DualModelClassifier.trainAndPredict()` |
| Image-switch listener thread | `mergeFrom()`, `retainClasses()` | `handleImageChange()` in `CellTuneExtension.java` |

If a user assigns a label via the toolbar while training is running (the training progress
window is non-modal — `Modality.NONE`), a `ConcurrentModificationException` could be thrown
during the training matrix build.

**Current mitigation:** In practice these operations rarely overlap because training is
manually initiated. The progress dialog is non-modal by design (users can interact with
QuPath during training), which makes the race window reachable.

**Fix:**
- Wrap `labels` field in `Collections.synchronizedMap()`:
  `this.labels = Collections.synchronizedMap(new LinkedHashMap<>())`.
- Or add `synchronized` blocks around `mergeFrom()`, `retainClasses()`, and the
  iteration in `trainAndPredict()`.
- Reference: RISKS.md §2.5.

---

## 3. QuPath API Compatibility Risk

**Severity: MEDIUM**

The extension targets QuPath 0.7.0 (`EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0")` in
`src/main/java/qupath/ext/celltune/CellTuneExtension.java`). The following APIs have changed
between previous minor versions and require monitoring:

| API | Risk | File(s) |
|-----|------|---------|
| `PathObjectSelectionModel.addPathObjectSelectionListener()` | Changed between 0.4→0.7; interface may be removed or renamed in 0.8+ | `src/main/java/qupath/ext/celltune/ui/ReviewController.java`, `ManualLabelToolbar.java` |
| `PathClass.fromString()` | Hierarchical separator semantics (e.g. `"Tumour: CD8"`) may change | `DualModelClassifier.java` throughout; `CellTuneExtension.java` line ~609 |
| `qupath.getAnalysisTabPane()` | Changed in 0.5→0.6; verify sidebar docking on each major bump | `CellTuneExtension.java` `dockClassificationPanel()` |
| `project.getEntry(imageData)` | Returns `null` if image is open without a project — callers must null-check | `CellTuneExtension.java` lines 176, 221, 329, 347, 606, 710 |
| `imageData.getHierarchy().fireHierarchyChangedEvent()` | Preferred event-firing method may change | `CellTuneExtension.java` training completion block |

**Project-less image data (null project.getEntry()):**
Most call sites in `CellTuneExtension.java` guard `project != null` before calling
`project.getEntry()`. However, the path at line 329 (`persistCurrentImagePredictions`) and
line 347 (`loadCurrentImagePredictions`) calls `project.getEntry(qupath.getImageData())`
after a null-check on `project`, but does not guard against `qupath.getImageData()` returning
null concurrently during image switch on a slow machine. The returned `entry` is null-checked,
so the failure mode is a silent no-op (state not persisted) rather than a crash.

**Feature Selection and Normalization state not saved during `saveState()`:**
In `ProjectStateManager.saveState()`, the fields `state.selectedFeatures`,
`state.featureTransforms`, and `state.arcsinhCofactor` are explicitly set to `null` with
comments `// to be set by caller`, but the caller (`CellTuneExtension.java` training thread)
does not set them before calling `saveState()`. The feature selection and normaliser are
therefore **not persisted to `classifier-state.json`** during the standard training save path.
They are only restored from `state.selectedFeatures` during `handleImageChange()` if a
previous save somehow wrote them (which it cannot via this path). This is a silent data loss bug.
- File: `src/main/java/qupath/ext/celltune/io/ProjectStateManager.java` lines ~155–158.
- Caller: `CellTuneExtension.java` training thread, `ProjectStateManager.saveState(...)` call.

**Fix:** Set `state.selectedFeatures`, `state.featureTransforms`, and `state.arcsinhCofactor`
in the save call inside the training thread, or add a dedicated save method that includes them.

---

## 4. XGBoost4J Version Lock

**Severity: MEDIUM**

Dependency: `ml.dmlc:xgboost4j_2.13:3.2.0`

**Why upgrading is dangerous:**
- The Java API (`Booster`, `DMatrix`) changed significantly between 1.x, 2.x, and 3.x.
- `predictContrib()` (TreeSHAP) is only public in the 3.x series. Earlier versions had it
  as an internal API. Downgrading would silently break SHAP entirely.
- The `_2.13` Scala cross-compilation suffix is not significant at runtime but must be
  matched on upgrade — an incorrect suffix resolves to a different artifact and may not
  include the expected native libraries.
- CUDA 12.x is required for GPU support on Linux. Mismatched CUDA versions may fail
  silently (XGBoost falls back to CPU without error in some configurations).
- XGBoost probes CUDA with a small training run before the real fit
  (`XGBoostModel.java` `attemptGpuTraining()` ~line 188). If the probe succeeds but full
  training fails, the error surface is `XGBoost4JException` which is caught and demoted
  to a CPU fallback — so GPU failures are always non-fatal.

**Reference:** RISKS.md §2.2.

---

## 5. Binary Classification SHAP Display Ambiguity

**Severity: LOW**

For binary XGBoost models (2 classes), `predictContrib()` returns a single set of SHAP
values (positive-class margin contributions). The current code in
`src/main/java/qupath/ext/celltune/classifier/XGBoostModel.java` mirrors the same absolute
values for both classes: `result[0][f] = result[1][f]`.

The `FeatureImportanceView` shows a class selector. When exactly 2 classes are trained,
both class entries display identical bars, which may confuse users expecting class-specific
importance.

**Fix (LOW priority):** For binary models, display a single combined chart with a note
that feature importance is class-agnostic, rather than offering a class selector.

---

## 6. Feature Column Ordering Fragility

**Severity: MEDIUM**

`CellFeatureExtractor` locks in column ordering at construction time:
`src/main/java/qupath/ext/celltune/model/CellFeatureExtractor.java`.

The feature list is built from `PathObjectTools.getAvailableFeatures(detections)` at train
time in `CellTuneExtension.showClassifierPanel()`, then frozen into the `ClassifierState`
and saved to `classifier-state.json`.

**What breaks if order changes:**
- On `predictOnly()` (used for multi-image classification), `DualModelClassifier` guards with
  a count check (`featureNames.size() != nFeatures` throws `IllegalStateException`), but this
  only catches count mismatches. If QuPath reorders measurements without adding/removing them
  (e.g. a different detection algorithm returns the same feature set in different order), the
  trained model silently receives wrong input — producing garbage predictions with no error.
- Multi-image label pooling (`poolAllImages` path) creates a fresh `CellFeatureExtractor` with
  `finalFeatureNames` for each other image. If that image's detections have the same features
  in a different order, the name-indexed lookup in `extractRow()` still works correctly (it
  iterates `featureNames` by name, not by index). This is safe.
- The fragility applies only to the native model scoring path, which receives a raw float[]
  without name validation. XGBoost and LightGBM cannot detect wrong-ordered input.

**Fix:** After loading a classifier, validate `featureNames` against the live detection feature
list by name-comparison (not just count) in `predictOnly()`.

---

## 7. GPU/CUDA Availability Assumptions

**Severity: LOW**

Both `LightGBMModel.java` and `XGBoostModel.java` attempt GPU training and fall back to CPU
on failure. Key concerns:

- **LightGBM GPU probe:** `LGBMBooster.create(dataset, gpuParams)` is called directly without
  a pre-flight probe. If LightGBM's GPU initialisation is slow or partially succeeds before
  crashing, the first `updateOneIter()` may stall or throw an ambiguous error. The booster is
  closed on catch, which is correct.
  File: `src/main/java/qupath/ext/celltune/classifier/LightGBMModel.java` lines ~72–90.

- **XGBoost GPU probe:** Uses a dedicated `attemptGpuTraining()` helper with a tiny dataset
  (~line 188 in `XGBoostModel.java`). More robust.

- **HyperparameterTuner:** Forces CPU explicitly ("All training during tuning uses CPU to
  avoid GPU probe overhead" — `HyperparameterTuner.java` line 28). This is correct for
  tuning speed but means hyperparameters are tuned on CPU and may not generalise perfectly
  to GPU execution speeds.

- **CUDA version mismatch (XGBoost4J):** If the CUDA runtime version on the HPC node
  does not match what XGBoost4J was compiled against (CUDA 12.x), the GPU probe will fail
  and fall back to CPU silently. This is safe but may surprise users expecting GPU acceleration.

---

## 8. Project-less Image Data Handling

**Severity: LOW**

`project.getEntry(imageData)` returns `null` when an image is opened without a QuPath project.
Most call sites in `CellTuneExtension.java` guard correctly (`if (entry == null) return`), but:

- `showClassifierPanel()` calls `qupath.getProject() == null` as its first guard and shows
  an error — so the full training path is blocked cleanly without a project.
- Manual label and review modes do not enforce this guard. A user working on a project-less
  image can label cells, but labels will not be persisted (all save calls are no-ops behind
  `if (project != null)` guards). Labels accumulated in-session will be lost on image switch.
- The `syncLabelsToCurrentClasses()` method is also guarded with `if (project == null) return`,
  meaning stale class labels will never be purged from the label store in a project-less session.

**Recommendation:** Warn the user when they attempt labelling outside a project context.

---

## 9. Multi-Image Label Pooling Edge Cases

**Severity: MEDIUM**

The multi-image pooling path in `CellTuneExtension.java` (training thread, `poolAllImages` block)
loads every project image sequentially with `entry.readImageData()`. Edge cases:

- **Class set mismatch:** Labels from other images may include class names not present in the
  current image's label store. `DualModelClassifier` handles this — supplementary classes are
  unioned into `classNames` before training. However, if a supplementary image has a class that
  does not appear in the current image at all, the model trains on that class but cannot be
  reviewed on the current image.

- **Missing detections for saved label IDs:** If an image's cell detection was re-run after
  labels were saved, cell IDs will no longer match. The inner loop `otherCellById.get(labelEntry.getKey())`
  returns `null` and skips such cells silently. No warning is issued. Pooled sample count may
  silently be much lower than the user expects.

- **Sequential I/O bottleneck:** Each image is opened via `entry.readImageData()` synchronously
  on the training background thread. For a project with 50 large images, this blocks training
  start for potentially several minutes with no per-image progress feedback in the log.

- **Normalizer not applied to pooled rows:** The `otherExtractor` uses the same `finalFeatureNames`
  and `featureNormalizer`, so normalisation is correctly applied. This is fine as coded but would
  silently break if the normaliser were changed to per-image rather than global.

---

## 10. Memory Usage for Large Projects

**Severity: MEDIUM (HPC)**

**Feature matrix:**
`CellFeatureExtractor.extractMatrix()` allocates one flat `float[]` of size `nCells × nFeatures`
in the JVM. For 500K cells × 2000 features: `500K × 2000 × 4 = 4 GB`. Both models make a
native copy, so peak usage is `~3 × matrix size` for native models alone.

**FX-thread PathClass batch:**
After prediction, two `ArrayList<PathObject>` and `ArrayList<PathClass>` of size `totalCells`
are held in memory until the FX thread processes them (in `DualModelClassifier.trainAndPredict()`
and `predictOnly()`). For 2M cells: `~100 MB`. Acceptable but worth noting on memory-constrained nodes.

**SHAP sampling cap:**
`DualModelClassifier.MAX_SHAP_SAMPLES = 5_000` caps SHAP to 5K cells. This is good for memory
and speed, but SHAP estimates from 5K cells may be noisy on heterogeneous large slides.

**Memory warning mechanism:**
`CellTuneExtension.checkTrainingMemory()` estimates peak usage as `3 × matrix + 0.3 GiB` and
shows a confirmation dialog if this exceeds 80% of JVM heap. The 3× multiplier does not account
for the SHAP computation (an additional `nSamples × nClasses × (nFeatures + 1)` double[] matrix),
so the estimate is an undercount when SHAP is computed immediately after training.

**Reference:** RISKS.md §2.6.

---

## 11. Early Stopping with Extreme Class Imbalance

**Severity: LOW**

The 80/20 stratified split for early stopping validation is performed on the raw (un-resampled)
training set. For extreme class imbalances (e.g. 1% rare class with < 20 total samples), the
20% validation partition may contain 0 or 1 samples of the rare class. Log-loss computed over
zero-samples-per-class is numerically undefined and is guarded by `Math.max(p, 1e-15)` in
`LightGBMModel.computeLogloss()` — the guard prevents a NaN/Infinity but does not prevent
the loss estimate from being highly noisy.

The default patience of 20 rounds is conservative and will typically overshoot in these cases.
Early stopping is opt-in, so this only affects users who enable it.

**Reference:** RISKS.md §2.7.

---

## 12. Random Forest Feature Importance Approximation

**Severity: LOW**

`RandomForestModel` computes feature importance using **normalised split counts** (not SHAP).
Split counts are biased toward high-cardinality continuous features and do not account for
interaction effects. The same importance is shown for all classes.

The `FeatureImportanceView` subtitle reads "averaged across active models" which is accurate
but may not be obvious to users that Random Forest bars are split-count approximations while
XGBoost bars are mean absolute SHAP values. The two scales are not comparable.

**Reference:** RISKS.md §2.8.

---

## 13. Feature Name Sanitisation Mapping Not Persisted

**Severity: LOW**

`XGBoostModel.sanitiseFeatureName()` replaces Greek letters (α, β, γ, etc.) and special
characters to produce XGBoost-safe feature names. The mapping from original → sanitised names
is not stored in `ClassifierState` or `ProjectStateManager.SavedState`.

Current impact is minimal — `FeatureImportanceView` uses `featureNames` from
`DualModelClassifier` (the original names), not from the booster internals. The sanitised
names only appear if `booster.getFeatureNames()` is ever called directly (e.g. future export,
debugging, or if XGBoost uses them in error messages). The same concern applies to LightGBM's
`dataset.setFeatureNames(safeNames)` in `LightGBMModel.java`.

**Reference:** RISKS.md §2.9.

---

## 14. No TODO/FIXME Inline Annotations

A full source scan found **zero** `TODO`, `FIXME`, `HACK`, or `XXX` comments across all Java
source files. All identified debt is documented in RISKS.md. No inline debt markers to track.

---

## 15. Dependencies to Monitor

| Dependency | Version | Risk |
|------------|---------|------|
| `io.github.metarank:lightgbm4j` | 4.6.0-2 | Watch for fix to `C_API_PREDICT_CONTRIB` SIGSEGV (§1) |
| `ml.dmlc:xgboost4j_2.13` | 3.2.0 | API stable in 3.x; do not upgrade without full regression test |
| QuPath | 0.7.0 | Selection listener, `getAnalysisTabPane()`, `PathClass.fromString()` semantics (§3) |
| JavaFX | (bundled with QuPath) | Canvas and control APIs stable; watch `Skin` on QuPath major bumps |
| Gson | (bundled with QuPath) | No action needed |

---

*Concerns audit: 2026-04-27*
