# Summary: Plan 02-01 — CompositeClassifier + ProjectStateManager Persistence

**Phase:** 02-composite-classification-builder
**Plan:** 01
**Status:** COMPLETE
**Commit:** bc8b49b

## What Was Built

### `CompositeClassifier.java` (new)
- Core inference engine for composite classification
- `apply(ImageData<?>, List<String>, Project<?>, Consumer<String>)` — classifies all detections in one image, assigns PathClass like `CD3+:CD4+:CD45-`
- `batch(Project<?>, List<String>, List<String>, Consumer<String>)` — iterates project images, reads/saves image data for each
- Loads binary classifiers via `BinaryClassifierRegistry`, averages XGBoost + LightGBM probabilities (threshold 0.5)
- Thread-safe via `SimpleStringProperty` (status) and `SimpleDoubleProperty` (progress) for UI binding
- Unclassified cells (no markers in run) get `null` PathClass

### `ProjectStateManager.java` (extended)
- `saveCompositeConfig(Project<?>, List<String>)` — saves selected marker names as JSON to `<project>/celltune/composite-config.json`
- `loadCompositeConfig(Project<?>) → List<String>` — loads saved marker selection; returns empty list if not found

## Fixes Applied (post-commit)
- `PathClass.NULL` → `null` (QuPath 0.7 has no `PathClass.NULL` constant)
- Wildcard capture: added `@SuppressWarnings("unchecked")` + `(Project<BufferedImage>)(Project<?>)project` cast in `batch()` for `saveImageData()` type safety
- Em-dash bytes (0x97): replaced with ASCII hyphens at byte level in Javadoc comments

## Key Decisions
- Marker positive threshold: 0.5 (averaged probability across XGBoost + LightGBM)
- PathClass format: `{marker}+` / `{marker}-` joined with `:` (e.g. `CD3+:CD4-`)
- Null PathClass for cells where no markers were selected

## Files Modified
- `src/main/java/qupath/ext/celltune/classifier/CompositeClassifier.java` (created)
- `src/main/java/qupath/ext/celltune/io/ProjectStateManager.java` (extended)
