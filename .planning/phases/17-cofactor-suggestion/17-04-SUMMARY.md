---
phase: 17-cofactor-suggestion
plan: 04
subsystem: ui
tags: [javafx, normalization, arcsinh, cofactor, modality]

# Dependency graph
requires:
  - phase: 17-cofactor-suggestion (plan 03)
    provides: CofactorSuggestionDialog non-modal window + Run/estimate pipeline
provides:
  - NormalizationPane.getStage() accessor so the Suggest window can be owned by the pane's own APPLICATION_MODAL stage (correction #2)
  - arcsinh-only "Suggest…" button beside the cofactor spinner (COF-01, D-06)
  - Suggest handler that opens CofactorSuggestionDialog owned by the pane's stage and applies the clamped global into the existing spinner in one action (COF-07)
  - Updated CellTuneExtension launch site passing qupath into NormalizationPane
affects: [normalization, cofactor-suggestion, phase-17-validation]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Non-modal child window owned by an APPLICATION_MODAL parent stage stays interactive (Modality.NONE under APPLICATION_MODAL)"
    - "Cross-window write-back via a clamped DoubleConsumer callback with no data mutation"

key-files:
  created: []
  modified:
    - src/main/java/qupath/ext/celltune/ui/NormalizationPane.java
    - src/main/java/qupath/ext/celltune/CellTuneExtension.java

key-decisions:
  - "Suggest window owned by NormalizationPane.getStage() (its own modal stage), NOT qupath.getStage() — correction #2, so the child window is not blocked by the modal pane"
  - "Apply callback clamps to [0.01, 10000] and only writes the spinner value; the user still confirms normalization via the existing OK button (COF-07, no auto-run, no mutation)"

patterns-established:
  - "Suggest… button visibility/managed is bound to the SAME arcsinh-only pairing as the cofactor spinner in updateCofactorVisibility() (D-06 — absent for sqrt)"

requirements-completed: [COF-01, COF-07]

# Metrics
duration: ~6min
completed: 2026-07-08
---

# Phase 17 Plan 04: Wire Cofactor Suggestion into the Normalise workflow Summary

**Arcsinh-only "Suggest…" button in NormalizationPane opens the CofactorSuggestionDialog (owned by the pane's own modal stage) and applies the recommended global cofactor straight into the existing spinner in one non-mutating action.**

## Performance

- **Duration:** ~6 min
- **Started:** 2026-07-08T11:02Z (approx)
- **Completed:** 2026-07-08T11:08Z
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- Added `public Stage getStage()` accessor to `NormalizationPane` so the non-modal suggest window can be owned by the pane's own `APPLICATION_MODAL` stage (correction #2 — a `Modality.NONE` child of the modal owner stays interactive, whereas a window owned by the QuPath main stage would be blocked).
- Changed the `NormalizationPane` constructor to take `QuPathGUI qupath` as the first argument and retain both `qupath` and `featureNames` as fields; updated the single `CellTuneExtension` launch site accordingly.
- Added an arcsinh-only "Suggest…" button beside the cofactor spinner, inside `transformRow`, with `setVisible/setManaged` bound to the existing arcsinh-only pairing in `updateCofactorVisibility()` (COF-01, D-06 — absent for sqrt).
- Wired the button handler to open `CofactorSuggestionDialog(getStage(), qupath, featureNames, applyCallback)`; the apply callback clamps the recommended global to `[0.01, 10000]` and writes it into the existing cofactor spinner via `getValueFactory().setValue(...)` in one action — no measurement/normalizer mutation and no normalization run (COF-07).
- Full `clean compileJava test` + `spotlessCheck` green: **428 tests, 0 failures, 0 errors, 0 skipped.**

## Task Commits

Each task was committed atomically:

1. **Task 1: Plumb qupath + featureNames + getStage() through NormalizationPane and its call site** — `b3754b8` (feat)
2. **Task 2: Add the arcsinh-only Suggest… button + CofactorSuggestionDialog wiring + spinner write-back** — `e6f867b` (feat)

**Plan metadata:** this SUMMARY committed separately (docs).

## Files Created/Modified
- `src/main/java/qupath/ext/celltune/ui/NormalizationPane.java` — added `QuPathGUI` import + `qupath`/`featureNames` fields; constructor now takes `qupath`; added `getStage()`; added `suggestBtn` (arcsinh-only, in `transformRow`); extended `updateCofactorVisibility()`; added `openSuggestTool()` handler that opens the dialog and applies the clamped global into the spinner.
- `src/main/java/qupath/ext/celltune/CellTuneExtension.java` — updated the single `new NormalizationPane(...)` call site (line 1698) to pass `qupath` as the first argument.

## Decisions Made
- Owner of the suggest window is `getStage()` (the pane's own modal stage), not `qupath.getStage()` — required so the child window is interactive under the `APPLICATION_MODAL` pane (correction #2 / T-17-12).
- Apply is a single spinner write only; the user confirms normalization via the existing OK button exactly as before. No mutation path exists in the handler (T-17-13).

## Deviations from Plan

Plan executed as written for all substantive requirements. Two literal grep-based acceptance criteria in the plan were authoring imprecisions that could not return the exact literal match without either breaking pre-existing behaviour or the mandatory formatter; the underlying substantive requirements are fully satisfied and verified:

**1. [Plan acceptance-grep imprecision] Whole-file mutation-guard grep expects `0` but returns `1`**
- **Found during:** Task 2 verification.
- **Issue:** The criterion `grep -Ec 'setArcsinhCofactor|getMeasurementList().put|saveImageData|setPathClass' NormalizationPane.java` returns `0`. It actually returns `1` — but that single match is the **pre-existing** `normalizer.setArcsinhCofactor(cofactorSpinner.getValue())` at line 207 inside `showAndWait()` (the legitimate OK-path that builds the `FeatureNormalizer`), which existed before this plan and is unrelated to the Suggest handler.
- **Resolution:** The substantive requirement ("no mutation **in the handler**", must_haves truth #4, threat T-17-13) is met — `openSuggestTool()` contains none of those patterns; it only calls `cofactorSpinner.getValueFactory().setValue`. No code change made; the pre-existing normalize path must be preserved.
- **Files modified:** none (documentation only).

**2. [Plan acceptance-grep imprecision] Single-line `new CofactorSuggestionDialog(\s*stage` grep**
- **Found during:** Task 2 verification.
- **Issue:** palantir-java-format wraps the constructor call across multiple lines (the single-line form exceeds the 120-col limit), so a line-based `grep -q 'new CofactorSuggestionDialog(\s*stage'` does not match on one line.
- **Resolution:** `stage` **is** the first positional argument passed to the dialog (owner = the pane's own stage, correction #2), verified by reading the handler. Forcing it onto one line would fail the mandatory `spotlessCheck` gate, so the wrapped form is retained.
- **Files modified:** none (documentation only).

---

**Total deviations:** 0 code deviations; 2 documented acceptance-grep imprecisions (substantive requirements fully met).
**Impact on plan:** None. All must_haves, success criteria, threat mitigations (T-17-11 clamp, T-17-12 ownership, T-17-13 no-mutation), and build/test/format gates satisfied.

## Issues Encountered
None.

## User Setup Required
None — no external service configuration required.

## Manual Validation Gate
Per `17-VALIDATION.md`, a manual QuPath UI check remains for this phase (cannot be automated in a headless build):
- Suggest… present + enabled for the arcsinh transform, absent for sqrt (COF-01/D-06).
- The suggest window is interactive (not blocked) under the `APPLICATION_MODAL` Normalise pane — i.e. `Modality.NONE`-under-`APPLICATION_MODAL` behaviour (T-17-12 / correction #2).
- Apply sets the spinner value with no measurement change and no normalization run (COF-07).

## Next Phase Readiness
- Phase 17 wave-3 (final plan) complete: COF-01 and COF-07 closed. The cofactor-suggestion tool is fully wired into the Normalise Features workflow.
- Remaining phase-completion item is the manual UI validation gate above.

## Self-Check: PASSED
- FOUND: `.planning/phases/17-cofactor-suggestion/17-04-SUMMARY.md`
- FOUND: `src/main/java/qupath/ext/celltune/ui/NormalizationPane.java` (364 lines ≥ 350 min)
- FOUND: `src/main/java/qupath/ext/celltune/CellTuneExtension.java`
- FOUND commit `b3754b8` (Task 1)
- FOUND commit `e6f867b` (Task 2)

---
*Phase: 17-cofactor-suggestion*
*Completed: 2026-07-08*
