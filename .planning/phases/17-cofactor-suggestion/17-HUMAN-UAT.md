---
status: partial
phase: 17-cofactor-suggestion
source: [17-VERIFICATION.md]
started: "2026-07-08T00:00:00.000Z"
updated: "2026-07-08T00:00:00.000Z"
---

## Current Test

[awaiting human testing in a live QuPath v0.7 session]

Build/QA JAR: `build/libs/qupath-extension-celltune-0.2.1-all.jar` (delete any older CellTune JAR from the extensions folder first, copy this one in, restart QuPath fully). Reach the tool via **Extensions → CellTune → Normalise Features**, choose the **arcsinh** transform, then click **Suggest…** beside the cofactor spinner.

## Tests

### 1. Suggest… button is arcsinh-only
expected: A "Suggest…" button is visible/enabled beside the cofactor spinner when the transform is **arcsinh**, and is absent (not just disabled) when the transform is **sqrt**. (COF-01, D-06)
result: [pending]

### 2. Non-modal window stays interactive under the modal Normalise pane
expected: Clicking Suggest… opens the Cofactor Suggestion window and it remains fully interactive while the APPLICATION_MODAL Normalise Features pane is open — you can click the picker, scope radios, Run and Cancel. (Correction #2 / RESEARCH §A4, MEDIUM-confidence JavaFX semantic. If the window is BLOCKED/greyed, the documented fallback is `Modality.WINDOW_MODAL` owned by the pane — report this so it can be applied.)
result: [pending]

### 3. Grouped/searchable picker, independent of the normalize selection
expected: The calibration picker is the grouped, searchable FeatureSelectionPane, defaults to per-marker mean-intensity features, and changing it does NOT change the Normalise Features selection (and vice-versa). (COF-02, D-12)
result: [pending]

### 4. Per-feature table + prominent global over both scopes
expected: Run (off the UI thread, UI stays responsive) renders a per-feature table (feature | n | background | median | p99 | cofactor | flag) plus one prominent recommended global cofactor, for BOTH "Open image" and "Whole project (all cells)" scope (the latter with a soft-ceiling confirm and a working Cancel). On a raw-fluorescence panel the global lands in the tens, not the platform default. (COF-05, COF-06, COF-08, D-07..D-10, D-13)
result: [pending]

### 5. Apply sets the spinner once, with no mutation
expected: Apply writes the clamped global cofactor into the existing cofactor spinner in a single action and does NOT alter any cell measurements and does NOT trigger a normalization run. (COF-07)
result: [pending]

## Summary

total: 5
passed: 0
issues: 0
pending: 5
skipped: 0
blocked: 0

## Gaps
