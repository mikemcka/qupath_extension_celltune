# Summary: Plan 02-02 — CompositeClassificationDialog

**Phase:** 02-composite-classification-builder
**Plan:** 02
**Status:** COMPLETE
**Commit:** bab840c

## What Was Built

### `CompositeClassificationDialog.java` (new)
JavaFX modal dialog for composite classification workflow.

**Constructor:** `CompositeClassificationDialog(QuPathGUI qupath)` — takes QuPath instance for project/image access.

**`showAndWait()`** — builds and shows the dialog. Loads prior saved selection from `ProjectStateManager.loadCompositeConfig()`. On close, always saves current checkbox state.

**Dialog layout:**
- Header label explaining purpose
- `ListView<CheckBox>` showing one row per marker name from `BinaryClassifierRegistry`
- Trained (loadable) classifiers: checkbox enabled; untrained: checkbox disabled + tooltip `"No trained classifier"`
- **Apply** button — classifies current open image with selected markers, shows inline progress via `Task<Integer>` + `ProgressBar`
- **Batch...** button — opens `ImageSelectionPane`, then classifies each selected image via `CompositeClassifier.batch()`, shows per-image results table

**Fix applied:**
- `selectionPane.showAndWait()` returns `List<String>` directly — removed erroneous separate `getResult()` call

## Key Design Choices
- Config persistence: saved/loaded via `ProjectStateManager` on every open/close
- Progress feedback: inline `ProgressBar` + `Label` driven by `CompositeClassifier`'s `statusProperty()` / `progressProperty()`
- Background thread: JavaFX `Task` for `apply()` and `batch()` to keep UI responsive
- Error display: `Alert.AlertType.ERROR` for exceptions, inline label for counts

## Files Modified
- `src/main/java/qupath/ext/celltune/ui/CompositeClassificationDialog.java` (created)
