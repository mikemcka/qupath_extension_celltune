---
phase: 17-cofactor-suggestion
verified: 2026-07-08T01:16:33Z
status: human_needed
score: 5/5 must-haves verified (code + tests); 8/8 COF requirements satisfied
overrides_applied: 0
human_verification:
  - test: "Open Extensions → CellTune Classifier → Normalise Features; with the arcsinh transform selected confirm a 'Suggest…' button sits beside the cofactor spinner, then switch the transform to sqrt and confirm the button disappears."
    expected: "Suggest… present + managed for arcsinh, absent/unmanaged for sqrt."
    why_human: "JavaFX setVisible/setManaged rendering has no headless harness in this project (COF-01/D-06)."
  - test: "Click 'Suggest…' to open the cofactor window while the (APPLICATION_MODAL) Normalise Features pane is open; click controls in the suggest window and confirm it is interactive."
    expected: "The Modality.NONE window, owned by the pane's own APPLICATION_MODAL stage, accepts clicks and does not lock up; the Normalise pane stays open."
    why_human: "JavaFX modality semantics (Modality.NONE child of an APPLICATION_MODAL owner) can only be confirmed in a live session — RESEARCH §A4 MEDIUM confidence, documented fallback Modality.WINDOW_MODAL (correction #2 / T-17-12)."
  - test: "In the suggest window open the calibration picker; confirm it shows grouped (markers/morphology/neighbors/embeddings/other) + searchable feature groups, change the calibration selection, then return to Normalise Features and confirm the normalize feature selection is unchanged (and vice versa)."
    expected: "Grouped + searchable picker; calibration selection independent of the normalize selection."
    why_human: "FeatureSelectionPane tree rendering + cross-window independence is a UI behaviour with no headless harness (COF-02/D-12)."
  - test: "Run the tool over ≥2 calibration features in both scopes (open image and whole project); confirm the per-feature table shows one row per feature with a value-scale summary and a suggested cofactor, plus one prominent recommended global cofactor."
    expected: "N rows for N features + a prominent global cofactor label; whole-project run pools all cells (rare markers represented)."
    why_human: "JavaFX table rendering + real multi-image project pooling behaviour (COF-05/COF-08); the underlying estimator/pooling math is unit-tested."
  - test: "Click Apply in the suggest window; confirm the Normalise Features cofactor spinner is set to the recommended value in one action, that no cell measurements change, and that normalization does not run until you press OK separately."
    expected: "Spinner value set once; no measurement/normalizer mutation and no normalization run from Apply."
    why_human: "Live UI write-back + no-mutation behaviour (COF-07); the handler is grep-verified mutation-free but the end-to-end action wants a live check."
---

# Phase 17: In-QuPath Cofactor Suggestion Verification Report

**Phase Goal:** Add a "Suggest cofactor…" tool to the Normalise Features workflow that estimates a good arcsinh cofactor from the project's in-memory cell measurements. User picks a calibration feature-set from a grouped, searchable picker (independent of the normalize set, no hardcoded measurement name); the tool estimates each selected feature's background/signal structure, presents a per-feature table plus one recommended global cofactor, and applies it into the existing cofactor spinner. Open-image or whole-project (pooled) scope.
**Verified:** 2026-07-08T01:16:33Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

Every automatically-verifiable must-have is VERIFIED against the actual codebase, and the falsifiable heart of the phase (Success Criterion 5) was confirmed by **executing** the unit tests, not by trusting SUMMARY claims. The remaining open items are all live-JavaFX behaviours that this project has no headless harness for — they are surfaced as human_verification (per the phase's documented manual-only gate), which is why the status is `human_needed` rather than `passed`.

### Observable Truths

| # | Truth (ROADMAP Success Criterion) | Status | Evidence |
|---|-----------------------------------|--------|----------|
| 1 | A "Suggest cofactor…" control reachable from Normalise Features opens a grouped, searchable feature picker whose calibration selection is independent of the normalize selection (COF-01/COF-02) | ✓ VERIFIED (wiring); UI render → human | Menu `MenuItemFactory.java:102` "Normalise Features" → `CellTuneExtension.showNormalization()` (1672) → `new NormalizationPane(qupath, qupath.getStage(), …)` (1698). Arcsinh-only `suggestBtn` (`NormalizationPane.java:92,227-228`) → `openSuggestTool()` (241) → `new CofactorSuggestionDialog(stage, …)`. Dialog holds its own `selectedFeatures` (default `IntensityHeatmap.discoverMarkerFeatures`, a suffix filter — no hardcoded marker name) and never reads/writes the normalize `allItems`; picker is a reused `FeatureSelectionPane`. Grouped/searchable rendering + arcsinh-only visibility → human item #1,#3. |
| 2 | Suggestions computed from in-memory cell measurements (no geojson/file streaming) for the chosen scope — open image or whole project pooled (COF-03/COF-08) | ✓ VERIFIED | Open-image: `poolOpenImage` uses `new CellFeatureExtractor(features, null)` + `extractMatrix(getDetectionObjects())` (CofactorSuggestionDialog.java:283-307). Whole-project: `poolWholeProject` → `CohortClusterModel.poolAllCellsRaw` (351) which reads only `entry.readImageData()`/live `ImageData`. `grep -Ec 'geojson\|GeoJson\|FileReader\|BufferedReader\|CSVParser'` on CohortClusterModel = 0. Scope `ToggleGroup` present, whole-project default (`wholeProjectScope.setSelected(true)`, line 134). |
| 3 | Per-feature results table (feature → value-scale summary → suggested cofactor) plus one recommended global cofactor aggregated across features (COF-04/COF-05/COF-06) | ✓ VERIFIED (model + render); UI render → human | `renderResults` builds a 7-column table `feature \| n cells \| background \| median \| p99 \| suggested cofactor \| flag` (CofactorSuggestionDialog.java:440-468) and a prominent `globalLabel`. `CofactorEstimator` computes per-feature p50 background + p99 signal-scale summary and `globalCofactor = clamp(RobustStats.median(non-excluded))`. Test `globalIsMedianOfPerFeatureCofactors` PASSED (median{10,20,30}=20). Table N-row rendering → human item #4. |
| 4 | Applying the recommendation sets the existing Normalise Features cofactor input to that value in a single action (COF-07) | ✓ VERIFIED (code); UI action → human | Apply handler → `applyGlobalCofactor.accept(clamp(globalCofactor))` (CofactorSuggestionDialog.java:162-169); NormalizationPane callback `cofactorSpinner.getValueFactory().setValue(v)` (248). No `setPathClass/getMeasurementList().put/saveImageData/setArcsinhCofactor` in `openSuggestTool()` (the one `setArcsinhCofactor` in the file is the pre-existing OK path at line 207). No-mutation live behaviour → human item #5. |
| 5 | On a raw-fluorescence-scale panel the recommended global cofactor lands in a data-driven range (tens, not default 1), asserted by CofactorEstimator unit tests (synthetic recovery within ~factor 2) — confirm tests exist and pass | ✓ VERIFIED (executed) | Ran `./gradlew test --tests "*CofactorEstimator*"` → `CofactorEstimatorTest` **7 tests, 0 failures, 0 errors**. `rawFluorescenceRangeLandsInTens` (asserts 10<global<80), `mibiRangeLandsNearPointZeroFive` (0.02<global<0.15), `recoversKnownBackgroundWithinFactorTwo` (within [B/2,2B]) all present and green — confirmed via `build/test-results/test/TEST-…CofactorEstimatorTest.xml`. |

**Score:** 5/5 truths verified (code + executed tests). Status is `human_needed` (not `passed`) because live-JavaFX-only behaviours remain for a human to confirm.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `model/CofactorEstimator.java` | Pure-array background-percentile estimator + global median + dead/saturated exclusion; `BACKGROUND_PERCENTILE = 50.0` | ✓ VERIFIED | 184 lines. `public static final double BACKGROUND_PERCENTILE = 50.0` (line 36). Uses `ImagePixelStats.percentileSorted` + `RobustStats.median` (imported from `util`). No javafx/qupath.lib imports. WIRED — imported+called by CofactorSuggestionDialog. |
| `test/…/CofactorEstimatorTest.java` | 7 deterministic tests (recovery, p50, median, dead/saturated, raw-fluor, MIBI, degenerate) | ✓ VERIFIED | 183 lines; 7 `@Test`; all named substrings present; executed 7/7 green. |
| `model/CohortClusterModel.java#poolAllCellsRaw` | Raw all-cells two-pass sibling, no sampling, skips standardizeColumns | ✓ VERIFIED | Method at line 993; body clones the two-pass loop; returns `raw` (line 1084), comment+no `standardizeColumns` call in body. `standardizeColumns` still called exactly once, at line 947 inside untouched `poolAllCells`. |
| `test/…/CohortClusterModelTest.java` raw case | Proves raw ≠ z-scored on same input | ✓ VERIFIED | `poolAllCellsRawReturnsRawRowsWhilePoolAllCellsZScores` (line 635); executed 1/1 green (raw mean 45 vs z mean ≈0). |
| `ui/CofactorSuggestionDialog.java` | Non-modal owned window: picker + scope + Run/Cancel + progress + table + global + Apply | ✓ VERIFIED | 486 lines. `initModality(Modality.NONE)` (198), `initOwner(ownerStage)` (197), 0 `qupath.getStage()` calls. Off-FX daemon worker + `CancellationToken` + soft-ceiling confirm (`celltune.allCellsSoftCeiling`). WIRED — constructed by NormalizationPane. |
| `ui/NormalizationPane.java` | `getStage()`, arcsinh-only Suggest… button, handler, spinner write-back | ✓ VERIFIED | 364 lines. `public Stage getStage()` (190); `suggestBtn` (46) in `transformRow` (96); toggled in `updateCofactorVisibility()` (227-228); `openSuggestTool()` (241) passes `stage` (its own APPLICATION_MODAL stage, line 180). |
| `CellTuneExtension.java` launch site | Passes qupath into NormalizationPane | ✓ VERIFIED | Line 1698 `new NormalizationPane(qupath, qupath.getStage(), featureNames, featureNormalizer)`. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| CofactorSuggestionDialog | CofactorEstimator.estimate | per-feature columns → CofactorSuggestion | ✓ WIRED | line 268-269 |
| CofactorSuggestionDialog | CohortClusterModel.poolAllCellsRaw | whole-project raw pooling | ✓ WIRED | line 351 (2 refs; 0 calls to z-scored `poolAllCells(`) |
| CofactorSuggestionDialog | FeatureSelectionPane | independent calibration picker, discoverMarkerFeatures default | ✓ WIRED | lines 114, 120 |
| Apply button | applyGlobalCofactor DoubleConsumer | single-action write-back, no mutation | ✓ WIRED | lines 162-169 → NormalizationPane 246-248 |
| NormalizationPane | CofactorSuggestionDialog | Suggest… handler owned by getStage() | ✓ WIRED | lines 242-243 (owner = `stage`, not qupath.getStage()) |
| Suggest… visibility | updateCofactorVisibility() | arcsinh-only setVisible/setManaged | ✓ WIRED | lines 227-228 |
| apply callback | cofactorSpinner.getValueFactory().setValue | write recommended global | ✓ WIRED | line 248 |
| Menu | showNormalization | "Normalise Features" item → handler | ✓ WIRED | MenuItemFactory.java:102 |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| CofactorSuggestionDialog results table/global | `lastResult` (CofactorSuggestion) | `CofactorEstimator.estimate(features, columns)` where `columns` come from `extractMatrix` (open image) or `poolAllCellsRaw.rows()` (whole project) — both in-memory reads | ✓ Yes — real per-cell measurements, RAW (null normalizer / no standardizeColumns) | ✓ FLOWING |
| NormalizationPane cofactor spinner | spinner value | Apply callback `global -> setValue(clamp(global))` fed from `lastResult.globalCofactor()` | ✓ Yes — driven by the estimator's median | ✓ FLOWING |

Correction #3 confirmed at the data-flow level: both scope paths feed the estimator RAW intensities (open image `new CellFeatureExtractor(features, null)`; whole project `poolAllCellsRaw`), never the z-scored `poolAllCells`.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| Estimator tests exist and pass (SC5 falsifiable heart) | `./gradlew test --tests "*CofactorEstimator*"` | CofactorEstimatorTest 7 tests, 0 failures/errors | ✓ PASS |
| Raw pooling ≠ z-scored proven | `./gradlew test --tests "*CohortClusterModel*Raw*"` | poolAllCellsRaw…ZScores 1 test, 0 failures/errors | ✓ PASS |
| javac toolchain is JDK 25 (compile validity) | `javac -version` | `javac 25.0.2` | ✓ PASS |
| Live JavaFX UI behaviours (button visibility, modality, picker independence, table render, Apply no-mutation) | n/a — no headless FX harness | routed to human verification | ? SKIP → human |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| COF-01 | 17-04 | Launch tool from Normalise Features workflow | ✓ SATISFIED (code); button visibility → human | Menu → NormalizationPane → arcsinh-only Suggest… → dialog (wiring verified). |
| COF-02 | 17-03 | Grouped/searchable picker, independent of normalize set, no hardcoded name | ✓ SATISFIED (code); render → human | Reused FeatureSelectionPane; dialog owns `selectedFeatures`; default via `discoverMarkerFeatures` suffix filter (no literal marker). |
| COF-03 | 17-02, 17-03 | In-memory measurements only, no geojson/file streaming | ✓ SATISFIED | `extractMatrix` + `poolAllCellsRaw`; 0 geojson/file-parser references. |
| COF-04 | 17-01 | Per-feature cofactor from background-vs-signal structure | ✓ SATISFIED | p50 background estimand; `recoversKnownBackgroundWithinFactorTwo` green. |
| COF-05 | 17-01, 17-03 | Per-feature results table | ✓ SATISFIED (model); render → human | `FeatureCofactor` record + 7-column `renderResults`. |
| COF-06 | 17-01, 17-03 | One recommended global cofactor aggregated (median) | ✓ SATISFIED | `globalCofactor = median(non-excluded)`; `globalIsMedianOfPerFeatureCofactors` green. |
| COF-07 | 17-04 | Apply into existing spinner, one action, no mutation | ✓ SATISFIED (code); action → human | DoubleConsumer → `setValue`; no mutation APIs in handler. |
| COF-08 | 17-02, 17-03 | Scope: open image or whole project pooled (all cells) | ✓ SATISFIED | Scope toggle; `poolAllCellsRaw` pools every cell (no sampling), memory-safe two-pass + soft-ceiling confirm. |

No orphaned requirements: REQUIREMENTS.md maps COF-01..08 to Phase 17; the union of plan `requirements:` frontmatter (01→{04,05,06}, 02→{03,08}, 03→{02,03,05,06,08}, 04→{01,07}) covers all 8.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| CofactorSuggestionDialog.java | 287,292,318,332,346,355,362 | `return null` | ℹ️ Info | Each is a documented guard/cancellation early-return (no image/project, empty detections, cancelled, soft-ceiling declined, zero pooled) preceded by a `log(...)`; caller null-checks and aborts gracefully. Not stubs. |
| NormalizationPane.java | 202 | `return null` | ℹ️ Info | Pre-existing `showAndWait()` cancel path — unrelated to Phase 17. |
| CofactorEstimator.java | 157 | grep matched "cofactor" token | ℹ️ Info | False positive — line is `.mapToDouble(FeatureCofactor::cofactor)`, legitimate stream code, no TODO/stub. |

No blocker or warning anti-patterns. No TODO/FIXME/placeholder/"not implemented" markers in the phase-17 files.

### Human Verification Required

Per `17-VALIDATION.md` (Manual-Only Verifications) — this project has no headless JavaFX harness, so the following runtime behaviours require a live QuPath session. These are documented expectations, not discovered gaps.

1. **Arcsinh-only Suggest… visibility** — With arcsinh selected the "Suggest…" button is present beside the cofactor spinner; switching to sqrt removes it (COF-01/D-06).
2. **Modality.NONE-under-APPLICATION_MODAL interactivity** — The suggest window (owned by the pane's own APPLICATION_MODAL stage) stays clickable while the Normalise pane is open. RESEARCH §A4 rates this MEDIUM confidence; documented fallback is `Modality.WINDOW_MODAL` (correction #2 / T-17-12).
3. **Independent grouped/searchable picker** — Picker shows the standard groups + search; changing calibration selection leaves the normalize selection unchanged and vice versa (COF-02/D-12).
4. **Table + global render over ≥2 features, both scopes** — N rows for N features + prominent global; whole-project pools all cells so rare markers are represented (COF-05/COF-08).
5. **Apply is a single non-mutating spinner write** — Apply sets the spinner once; no measurement change and no normalization run until the user presses OK (COF-07).

### Gaps Summary

No gaps. All observable truths, artifacts, key links, and data flows are VERIFIED against the actual codebase, and the falsifiable estimator tests were executed green (7/7 CofactorEstimator + 1/1 raw-pooling). Both cross-cutting corrections hold in code:
- **Correction #2:** `CofactorSuggestionDialog` takes the owner Stage via constructor and makes **zero** `qupath.getStage()` calls; `NormalizationPane.openSuggestTool()` passes its own APPLICATION_MODAL `stage`; the child is `Modality.NONE`.
- **Correction #3:** the estimator is fed RAW values via `poolAllCellsRaw` (whole project) and a null-normalizer `CellFeatureExtractor` (open image); the dialog makes **zero** calls to the z-scored `poolAllCells(`.

The phase goal is achieved at the code + unit-test level. Status is `human_needed` solely because the five live-JavaFX behaviours above cannot be exercised by any automated check in this repository — exactly the manual gate the plan/validation documents anticipated.

---

_Verified: 2026-07-08T01:16:33Z_
_Verifier: Claude (gsd-verifier)_
