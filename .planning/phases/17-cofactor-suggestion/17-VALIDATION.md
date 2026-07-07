---
phase: 17
slug: cofactor-suggestion
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-07
---

# Phase 17 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Seeded from `17-RESEARCH.md` §Validation Architecture. The per-task map below is
> requirement-level; the planner fills real Task IDs into the Per-Task Verification Map
> once `17-*-PLAN.md` files exist, then sets `nyquist_compliant: true`.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter), `org.junit.jupiter.api.Test` |
| **Config file** | `build.gradle.kts` (`test` task) |
| **Test location** | `src/test/java/qupath/ext/celltune/model/`, mirroring main; pure-array convention (no QuPath/JavaFX imports) — see `NeighborhoodModelTest`, `LeidenModelTest` |
| **Quick run command** | `./gradlew test --tests "*CofactorEstimator*"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | quick ~5–15s; full suite ~1–3 min |
| **Determinism** | `new Random(42)` (fixed seed); synthetic clouds; static assertions with an `EPS` tolerance |

---

## Sampling Rate

- **After every task commit:** Run `./gradlew test --tests "*CofactorEstimator*"` (fast; pure-array)
- **After every plan wave:** Run `./gradlew test` (full suite) + `./gradlew spotlessCheck` (blocking format gate)
- **Before `/gsd-verify-work`:** Full suite green + manual UI checklist (COF-01/02/05/07/08 wiring)
- **Max feedback latency:** ~15 seconds (quick) / ~3 minutes (full)

---

## Per-Task Verification Map

> Task IDs are assigned by the planner. Rows below are requirement-level and map to the
> `CofactorEstimator` estimator + `poolAllCellsRaw` primitive. Planner: replace `{plan}-{task}`
> with real IDs and confirm coverage.

| Task ID | Plan | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| {TBD} | {TBD} | — | COF-04 | N/A | unit | `./gradlew test --tests "*CofactorEstimator*recoversKnownBackground*"` | ❌ W0 | ⬜ pending |
| {TBD} | {TBD} | — | COF-04 (`BACKGROUND_PERCENTILE == 50.0`) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*percentile*"` | ❌ W0 | ⬜ pending |
| {TBD} | {TBD} | — | COF-06 (global = median of per-feature) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*globalIsMedian*"` | ❌ W0 | ⬜ pending |
| {TBD} | {TBD} | — | D-11 (dead/saturated flagged + excluded) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*excludesDeadSaturated*"` | ❌ W0 | ⬜ pending |
| {TBD} | {TBD} | — | Acceptance (raw-fluor → global in tens) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*rawFluorescenceRange*"` | ❌ W0 | ⬜ pending |
| {TBD} | {TBD} | — | Acceptance (MIBI-scale → global ≈0.05) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*mibiRange*"` | ❌ W0 | ⬜ pending |
| {TBD} | {TBD} | — | COF-06 (NaN/degenerate robustness) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*degenerate*"` | ❌ W0 | ⬜ pending |
| {TBD} | {TBD} | — | COF-08 (`poolAllCellsRaw` raw, not z-scored) | N/A | unit | `./gradlew test --tests "*CohortClusterModel*Raw*"` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/qupath/ext/celltune/model/CofactorEstimatorTest.java` — stubs for COF-04, COF-06, D-11, raw-fluor + MIBI range, degenerate handling. Follow `NeighborhoodModelTest`'s synthetic-cloud + `new Random(seed)` convention; NO QuPath/JavaFX imports.
- [ ] Extend `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` — add `poolAllCellsRaw` raw-vs-zscored assertion (or the `z*sd+mean` recovery equivalence test).
- [ ] Framework install: none — JUnit 5 already wired in `build.gradle.kts`.

---

## Manual-Only Verifications

> The JavaFX UI surface has no headless harness in this project (consistent with existing
> UI tests covering only non-JavaFX logic, e.g. `FeatureSelectionPaneTest`).

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| "Suggest…" button present+enabled for arcsinh, absent/disabled for sqrt | COF-01 | JavaFX stage; no headless harness | Launch Normalise Features in QuPath; toggle transform; confirm button visibility |
| Calibration picker independent of normalize set; grouped + searchable; default = marker means | COF-02 | UI wiring (`discoverMarkerFeatures` logic already unit-covered by `IntensityHeatmapTest`) | Open picker; change calibration selection; confirm normalize selection unchanged |
| In-memory only (no geojson/CSV) | COF-03 | code-review + runtime | Inspect: only `getMeasurementList()`/`ImageData` reads; no IO-parser calls |
| Table shows N rows with value-scale summary + per-feature cofactor | COF-05 | JavaFX rendering | Run over ≥2 features; confirm N rows + columns |
| Apply sets the spinner in one action; no mutation/normalize | COF-07 | UI wiring | Apply; confirm spinner value set, no measurement change until user confirms normalize |
| Scope selector; whole-project pools all cells; open-image uses open cells | COF-08 | UI wiring (pooling math is unit-tested) | Run both scopes on a real multi-image project; confirm rare markers represented |

---

## Validation Sign-Off

- [ ] All estimator/pooling tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references (`CofactorEstimatorTest`, `CohortClusterModelTest` raw extension)
- [ ] No watch-mode flags
- [ ] Feedback latency < 15s (quick)
- [ ] Manual-only UI checklist (COF-01/02/05/07/08) documented for `/gsd-verify-work`
- [ ] `nyquist_compliant: true` set once planner finalizes the per-task map with real Task IDs

**Approval:** pending
