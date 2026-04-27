# Phase 2: Composite Classification Builder - Context

**Gathered:** 2026-04-28
**Status:** Ready for planning

<domain>
## Phase Boundary

Build a composite classification dialog that lets users select any subset of
their trained binary classifiers and apply them to all cell detections in the
current image or a batch of images. Each cell receives a single composite
QuPath PathClass (e.g. `CD4+:CD3-:CD45+`) derived automatically from the
results of all selected binary classifiers — no user-defined polarity rules
needed.

</domain>

<decisions>
## Implementation Decisions

### Classification Approach
- **D-01:** Auto-generate mode — no user-defined polarity rules. User selects
  which trained binary classifiers to include; each cell gets a PathClass
  constructed from ALL selected results combined.
- **D-02:** Scores from `Pred_AVG` (averaged XGBoost + LightGBM probability)
  are used. Hard threshold: avg score >= 0.5 → positive.
- **D-03:** Run inference fresh at apply time — re-extract features from
  `PathObject.getMeasurementList()` via `CellFeatureExtractor` (same as
  multi-class workflow). Do NOT cache binary predictions.
- **D-04:** Apply to `PathDetectionObject` only (cell detections), consistent
  with the existing multi-class classification workflow.

### PathClass Naming
- **D-05:** Format: `+/-` notation per marker (e.g. `CD4+`, `CD3-`).
- **D-06:** Separator: colon (e.g. `CD4+:CD3-:CD45+`) — standard QuPath
  composite PathClass notation.
- **D-07:** Marker order in PathClass name: alphabetical (deterministic,
  independent of selection order or registry insertion order).

### Untrained / Missing Classifier Handling
- **D-08:** Untrained classifiers (no model bytes in state file) are silently
  skipped — cell receives a partial composite PathClass from the trained
  classifiers only.
- **D-09:** If ALL selected classifiers are skipped (none trained), the cell
  is set to `PathClass.NULL` (Unclassified).
- **D-10:** Cells with no result from any classifier → set to Unclassified
  (PathClass.NULL), not retain-previous.

### Dialog Design
- **D-11:** Launched from *Extensions > CellTune > Composite Classification...*
  (new menu item, same location as *Binary Classifiers...*).
- **D-12:** Modal dialog (blocks QuPath interaction while open).
- **D-13:** Checkbox list showing all binary classifiers from the registry.
  Each row: classifier name + trained/untrained status indicator. Untrained
  classifiers are shown but their checkboxes are disabled (greyed out).
- **D-14:** No live preview of the composite PathClass name.
- **D-15:** Two action buttons:
  - **Apply** — classifies current image only.
  - **Batch...** — opens `ImageSelectionPane` for multi-image selection,
    then runs batch classification.
- **D-16:** If no binary classifiers have been trained: Apply button and
  Batch... button are disabled; show explanatory label
  "No trained classifiers available."

### Persistence
- **D-17:** The user's checkbox selection (which markers are active) is
  persisted to `<project>/celltune/composite-config.json` and reloaded when
  the dialog next opens. Structure: `{ "selectedMarkers": ["CD3", "CD4", "CD45"] }`.
- **D-18:** Written via `ProjectStateManager` (adds two new methods:
  `saveCompositeConfig` / `loadCompositeConfig`), consistent with existing
  binary state persistence pattern.

### Progress & Results
- **D-19:** Application progress shown in a non-modal progress dialog,
  same pattern as the existing training progress dialog (`Modality.NONE`).
- **D-20:** After single-image apply: dialog shows total cells classified
  (e.g. "Classified 12,450 cells.").
- **D-21:** After batch: progress dialog shows per-image result summary
  (success / skip / fail per image) when complete.

### Batch Edge Cases
- **D-22:** Use the globally saved trained model file for each binary
  classifier (`<project>/celltune/binary/<marker>.json`) — do NOT re-train
  per image.
- **D-23:** If a batch image has no cell detections: skip that image, log a
  warning, continue with remaining images.

### Export
- **D-24:** Add a *Composite Classification...* export entry to the CellTune
  export menu. (Composite PathClass is already present in the PathClass column
  of Cell Table CSV and AnnData exports — this entry provides a dedicated
  shortcut.)

### Agent's Discretion
- Architecture of `CompositeClassifier` (class layout, threading model)
  follows the pattern of `DualModelClassifier` — background thread,
  `Platform.runLater()` for all PathObject writes, progress property.
- Exact wording/styling of dialogs follows QuPath/CellTune UI conventions.
- `composite-config.json` schema may be extended in a future phase to support
  named subsets if needed; keep the structure flat and easily extensible.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Existing State Persistence Pattern
- `src/main/java/qupath/ext/celltune/io/ProjectStateManager.java` — all
  save/load patterns; new `saveCompositeConfig` / `loadCompositeConfig`
  methods must follow existing conventions (Gson, null project guard,
  `getBinaryDir()` helper pattern).

### Binary Classifier Infrastructure (Phase 1 — completed)
- `.planning/phases/01-binary-classifier-infrastructure/01-01-SUMMARY.md` —
  what `BinaryClassifierRegistry` and `ProjectStateManager` binary methods do.
- `.planning/phases/01-binary-classifier-infrastructure/01-02-SUMMARY.md` —
  what `BinaryClassifierPanel` does and its UI patterns.
- `src/main/java/qupath/ext/celltune/io/BinaryClassifierRegistry.java` —
  registry structure; `load()` / `save()` / `sanitizeMarkerName()`.

### Classifier State & Inference
- `src/main/java/qupath/ext/celltune/classifier/ClassifierState.java` —
  immutable snapshot (model bytes + feature list + class list); must be loaded
  to run inference.
- `src/main/java/qupath/ext/celltune/classifier/XGBoostModel.java` —
  `predict(float[][])` returns `float[][]` per-class probabilities.
- `src/main/java/qupath/ext/celltune/classifier/LightGBMModel.java` —
  same interface.
- `src/main/java/qupath/ext/celltune/model/CellFeatureExtractor.java` —
  extract feature vectors from `PathObject`s; enforces fixed column ordering.
- `src/main/java/qupath/ext/celltune/model/CellPrediction.java` —
  `avgLabel()` gives the `Pred_AVG` result used for thresholding.

### UI Patterns
- `src/main/java/qupath/ext/celltune/ui/BinaryClassifierPanel.java` —
  dialog/panel patterns (modal dialog, window.hide(), dockPane/dockTab refs).
- `src/main/java/qupath/ext/celltune/ui/ImageSelectionPane.java` —
  dual-list image selector; reuse for Batch... flow.
- `src/main/java/qupath/ext/celltune/CellTuneExtension.java` —
  menu item registration; how to wire a new menu item alongside existing ones.

### Project Conventions
- `AGENTS.md` — UI thread safety, null-check project entries, QuPath public
  API only, LabelStore thread-safety notes.
- `RISKS.md` — full risk register including LightGBM SHAP SIGSEGV (do not
  re-enable), XGBoost4J version lock.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `BinaryClassifierRegistry.load(project)` — returns `LinkedHashMap<String, String>`
  (marker name → state file path); use this to populate the checkbox list.
- `ProjectStateManager.loadBinaryState(project, markerName)` — loads a
  trained `ClassifierState` for a binary classifier; returns null if absent.
- `CellFeatureExtractor` — run against `imageData.getHierarchy().getDetectionObjects()`
  filtered to `PathDetectionObject`; same pattern as `DualModelClassifier`.
- `ImageSelectionPane` — reuse directly for Batch... image selection.
- Training progress dialog pattern in `ClassificationPanel` — reuse
  `Modality.NONE` ProgressIndicator/Label dialog for apply progress.

### Established Patterns
- All `PathObject.setPathClass()` calls must be wrapped in
  `Platform.runLater()` — never call from background thread directly.
- Null-check `project.getEntry(imageData)` before any IO operation.
- Menu items registered in `CellTuneExtension.installExtension()` via
  `qupath.getMenu("Extensions>CellTune", true)`.
- `PathClass.fromString("CD4+:CD3-:CD45+")` constructs composite PathClass;
  `PathClass.NULL` for Unclassified.

### Integration Points
- New `CompositeClassifier` class in `classifier/` package; takes
  `List<ClassifierState>` (for the selected trained markers) + `List<String>`
  (their marker names, alphabetically sorted) and runs inference.
- New `CompositeClassificationDialog` in `ui/` package; wired into
  `CellTuneExtension` menu.
- `ProjectStateManager` extended with `saveCompositeConfig` /
  `loadCompositeConfig` that read/write
  `<project>/celltune/composite-config.json`.

</code_context>

<specifics>
## Specific Ideas

- The composite PathClass name is constructed by sorting selected trained
  marker names alphabetically, running inference for each, then joining with
  colons: `String.join(":", sortedMarkers.stream().map(m -> m + (isPos ? "+" : "-")).toList())`.
- Untrained marker check: `ProjectStateManager.loadBinaryState(project, marker) == null`
  → skip silently (no error shown to user for individual skips).

</specifics>

<deferred>
## Deferred Ideas

- **Named saved configurations** (e.g. "T cell panel", "B cell panel") — user
  wanted simple active-selection persistence only for now. Named subsets
  can be added in a future phase.
- **Threshold configurability** — per-classifier threshold adjustment was
  discussed but not requested; hardcoded 50% for now.
- **Live PathClass name preview** in dialog — user declined; could be added
  later as a minor UX improvement.
- **User-defined polarity rules** (the original ROADMAP spec) — superseded by
  the auto-generate approach confirmed in this discussion.

</deferred>

---

*Phase: 2 — Composite Classification Builder*
*Context gathered: 2026-04-28*
