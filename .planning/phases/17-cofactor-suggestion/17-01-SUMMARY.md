---
phase: 17-cofactor-suggestion
plan: 01
subsystem: model
tags: [arcsinh, cofactor, normalization, percentile, robust-stats, tdd, junit5]

# Dependency graph
requires:
  - phase: 17-cofactor-suggestion (research)
    provides: D-03 calibration (BACKGROUND_PERCENTILE = 50.0), reuse map (percentileSorted, RobustStats.median)
provides:
  - Pure-array CofactorEstimator model class (BACKGROUND_PERCENTILE = 50.0)
  - Per-feature background-percentile cofactor + FeatureCofactor diagnostic record
  - Global recommended cofactor = median over non-excluded features (CofactorSuggestion)
  - Dead / saturated feature flagging + exclusion from the global median
  - Deterministic synthetic test suite (known-background recovery, range sanity, degenerate handling)
affects: [17-02 (poolAllCellsRaw whole-project pooling), 17-03 (CofactorSuggestWindow UI + results table), NormalizationPane apply-target wiring]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Pure-array model class (no QuPath/JavaFX) mirroring NeighborhoodModel/LeidenModel — static, JavaFX-free, unit-testable on synthetic clouds"
    - "Reuse ImagePixelStats.percentileSorted for interpolated percentiles and RobustStats.median for robust aggregation — no hand-rolled percentile/median"
    - "Clamp every returned cofactor to the arcsinh spinner range [0.01, 10000]; neutral 1.0 fallback when all features excluded"

key-files:
  created:
    - src/main/java/qupath/ext/celltune/model/CofactorEstimator.java
    - src/test/java/qupath/ext/celltune/model/CofactorEstimatorTest.java
  modified: []

key-decisions:
  - "BACKGROUND_PERCENTILE = 50.0 (p50/median) applied identically to every feature — hits both raw-fluor (tens) and MIBI (~0.05) targets by scale alone (D-01/D-02/D-03)"
  - "Dead = spread < DEAD_EPS (1e-9); saturated = background >= MAX_COFACTOR (10000); both excluded from the global median (D-11)"
  - "background and median columns intentionally share the same p50 statistic (Research A2); p99 is the signal-scale summary (D-09)"
  - "Degenerate inputs (empty/all-NaN) return a neutral excluded row and never throw; NaN dropped before sorting (T-17-01)"

patterns-established:
  - "Pattern: pure-array estimator + record-based diagnostic rows (FeatureCofactor / CofactorSuggestion) for a sortable per-feature table"
  - "Pattern: TDD RED (compile-failing spec) → GREEN (minimal implementation) with per-gate atomic commits"

requirements-completed: [COF-04, COF-05, COF-06]

# Metrics
duration: ~18min
completed: 2026-07-08
---

# Phase 17 Plan 01: CofactorEstimator (numeric core) Summary

**Pure-array `CofactorEstimator` that derives a per-feature arcsinh cofactor as the p50 (median) of each feature's raw per-cell distribution, aggregates them into one recommended global cofactor via `RobustStats.median` over non-excluded features, and flags/excludes dead and saturated markers — built strict-TDD, 7 deterministic tests green.**

## Performance

- **Duration:** ~18 min (RED commit 10:35 → full-suite gate ~10:53)
- **Started:** 2026-07-08T10:33:00+10:00 (approx)
- **Completed:** 2026-07-08T10:53:00+10:00 (approx)
- **Tasks:** 2 (RED spec, GREEN implementation)
- **Files created:** 2

## Accomplishments
- New pure-array model class `CofactorEstimator` (no QuPath/JavaFX imports), `BACKGROUND_PERCENTILE = 50.0`, mirroring the `NeighborhoodModel`/`LeidenModel` purity convention.
- Per-feature estimate: NaN-dropped → ascending sort → `background = ImagePixelStats.percentileSorted(finite, 50.0)`, `median = background`, `p99 = percentileSorted(finite, 99.0)`, `cofactor = clamp(background)`.
- Global recommendation = `RobustStats.median` over the cofactors of non-excluded features (COF-06/D-10), clamped to `[0.01, 10000]`, neutral `1.0` fallback when every feature is excluded.
- Dead (near-zero spread, `DEAD_EPS = 1e-9`) and saturated (`background >= MAX_COFACTOR = 10000`) features flagged with a reason string and excluded from the global median (D-11).
- Two record types for the diagnostic table: `FeatureCofactor(feature, nCells, background, median, p99, cofactor, excluded, reason)` and `CofactorSuggestion(perFeature, globalCofactor)` (COF-05/D-09).
- 7 deterministic JUnit 5 tests (`new Random(42)`): factor-2 known-background recovery, p50 constant, global-median, dead/saturated exclusion, raw-fluor + MIBI range sanity, degenerate robustness.

## Task Commits

Each TDD gate committed atomically:

1. **Task 1: Author failing CofactorEstimatorTest (RED)** — `b6545e8` (test)
2. **Task 2: Implement CofactorEstimator until green (GREEN)** — `5957a7a` (feat)

_REFACTOR gate: none required — implementation was already minimal and spotless-clean._

**Plan metadata:** committed separately as `docs(17-01): summary`.

## TDD Gate Compliance
- **RED** (`b6545e8`): test file authored first; ran and failed to **compile** (`cannot find symbol: CofactorEstimator`) — correct RED for the right reason (class absent), 22 compile errors, no false-pass.
- **GREEN** (`5957a7a`): minimal implementation; `./gradlew test --tests "*CofactorEstimator*"` → 7/7 pass. GREEN commit follows RED in git history. Gate sequence satisfied.

## Files Created/Modified
- `src/main/java/qupath/ext/celltune/model/CofactorEstimator.java` — pure-array estimator: `BACKGROUND_PERCENTILE = 50.0`, `estimateFeature`, `estimate`, `globalCofactor`, dead/saturated exclusion, clamp + neutral fallback; records `FeatureCofactor` / `CofactorSuggestion`.
- `src/test/java/qupath/ext/celltune/model/CofactorEstimatorTest.java` — 7 synthetic, deterministic tests; no QuPath/JavaFX imports; `EPS = 1e-9`, `Random(42)`, log-normal panel generators.

## Test Results
- New estimator suite: **7 tests, 0 failures, 0 errors, 0 skipped**.
- Full suite (`./gradlew clean compileJava test`): **427 tests, 0 failures, 0 errors, 0 skipped** across 43 suites (420 pre-existing + 7 new).
- `./gradlew spotlessCheck`: **passes** (palantir-java-format, 4-space, 120-col).

## Key Links (verified)
- `CofactorEstimator` → `ImagePixelStats.percentileSorted` (percentile on ascending-sorted `double[]`) — present.
- `CofactorEstimator` → `qupath.ext.celltune.util.RobustStats.median` (global aggregation over kept cofactors) — imported from `util` (not `model`).

## Decisions Made
- Locked `BACKGROUND_PERCENTILE = 50.0` per the D-03 research calibration; no majority-positive-specific handling (delegated to the robustness of the global median + advisory per-feature values), matching RESEARCH Open Question 1 resolution.
- `background` and `median` diagnostic columns share the p50 statistic (Research A2 / Open Question 3); `p99` provides the independent signal-scale summary.
- Saturation is detected on the p50 background (`>= 10000`) rather than a per-pixel-style gate, since per-cell means rarely clip — matches the plan's D-11 resolution.

## Deviations from Plan
None — plan executed exactly as written. The estimator API, constants, records, algorithm steps, and all 7 test names/assertions match the plan's Task 1/Task 2 specifications.

_Note: `spotlessApply` reformatted whitespace/line-wrapping in the test file after the RED commit (the RED file was not yet palantir-clean); that formatting-only change was folded into the GREEN commit so the tree stays spotless-clean. No semantic change to the tests._

## Issues Encountered
None. The GREEN implementation passed all 7 tests on the first run; no auto-fixes or iteration were needed.

## Next Phase Readiness
- Numeric core is complete and stable for the rest of Phase 17.
- **17-02** can add `CohortClusterModel.poolAllCellsRaw` (raw, non-z-scored whole-project pooling) to feed `estimate(String[], double[][])`.
- **17-03** can build the `CofactorSuggestWindow` UI + results `TableView` directly off `FeatureCofactor` / `CofactorSuggestion`, and wire the `globalCofactor()` write-back into `NormalizationPane`'s cofactor spinner.
- No blockers. No new dependencies introduced.

## Self-Check: PASSED
- `CofactorEstimator.java` — FOUND
- `CofactorEstimatorTest.java` — FOUND
- `17-01-SUMMARY.md` — FOUND
- RED commit `b6545e8` — FOUND
- GREEN commit `5957a7a` — FOUND

---
*Phase: 17-cofactor-suggestion*
*Completed: 2026-07-08*
