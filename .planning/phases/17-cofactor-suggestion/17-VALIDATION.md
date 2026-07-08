---
phase: 17
slug: cofactor-suggestion
status: planned
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-07
planned: 2026-07-08
---

# Phase 17 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Seeded from `17-RESEARCH.md` §Validation Architecture. The Per-Task Verification Map
> is finalized against the real Task IDs in `17-01-PLAN.md`..`17-04-PLAN.md`.

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

> Task IDs use `{plan}-T{n}` where `{plan}` is the plan number in `17-{plan}-PLAN.md`.
> Estimator tests are AUTHORED in `17-01-T1` (RED) and made GREEN in `17-01-T2`; the raw-pooling
> test is authored in `17-02-T2` against the method added in `17-02-T1`.

| Task ID | Plan | Wave | Requirement | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------------|-----------|-------------------|-------------|--------|
| 17-01-T1 / 17-01-T2 | 17-01 | 1 | COF-04 (known-background recovery within factor-2, deterministic seed) | Guard degenerate input (T-17-01) | unit | `./gradlew test --tests "*CofactorEstimator*recoversKnownBackground*"` | ✅ authored 17-01-T1 | ⬜ pending |
| 17-01-T1 / 17-01-T2 | 17-01 | 1 | COF-04 (`BACKGROUND_PERCENTILE == 50.0`) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*percentile*"` | ✅ authored 17-01-T1 | ⬜ pending |
| 17-01-T1 / 17-01-T2 | 17-01 | 1 | COF-06 (global = median of per-feature) | Clamp to (0.01,10000] (T-17-02) | unit | `./gradlew test --tests "*CofactorEstimator*globalIsMedian*"` | ✅ authored 17-01-T1 | ⬜ pending |
| 17-01-T1 / 17-01-T2 | 17-01 | 1 | D-11 (dead/saturated flagged + excluded) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*excludesDeadSaturated*"` | ✅ authored 17-01-T1 | ⬜ pending |
| 17-01-T1 / 17-01-T2 | 17-01 | 1 | Acceptance (raw-fluor → global in tens) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*rawFluorescenceRange*"` | ✅ authored 17-01-T1 | ⬜ pending |
| 17-01-T1 / 17-01-T2 | 17-01 | 1 | Acceptance (MIBI-scale → global ≈0.05) | N/A | unit | `./gradlew test --tests "*CofactorEstimator*mibiRange*"` | ✅ authored 17-01-T1 | ⬜ pending |
| 17-01-T1 / 17-01-T2 | 17-01 | 1 | COF-06 (NaN/degenerate robustness) | Neutral fallback, no throw (T-17-01) | unit | `./gradlew test --tests "*CofactorEstimator*degenerate*"` | ✅ authored 17-01-T1 | ⬜ pending |
| 17-02-T1 / 17-02-T2 | 17-02 | 1 | COF-08 (`poolAllCellsRaw` raw, not z-scored) + COF-03 (in-memory only) | Memory-safe two-pass, cancellation (T-17-04/06) | unit | `./gradlew test --tests "*CohortClusterModel*Raw*"` | ✅ authored 17-02-T2 | ⬜ pending |
| 17-03-T1 / 17-03-T2 | 17-03 | 2 | COF-02/03/05/06/08 (UI wiring; math already unit-covered) | Off-FX + owned-modal + no-mutation (T-17-07/08/09/10) | compile + manual | `./gradlew compileJava && ./gradlew test` (compile/regression) | — (JavaFX; manual) | ⬜ pending |
| 17-04-T1 / 17-04-T2 | 17-04 | 3 | COF-01/07 (button + spinner write-back) | Clamp + owned window (T-17-11/12) | compile + manual | `./gradlew test && ./gradlew spotlessCheck` (regression + format) | — (JavaFX; manual) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `src/test/java/qupath/ext/celltune/model/CofactorEstimatorTest.java` — authored in **17-01-T1** (RED): covers COF-04 (recoversKnownBackground + percentile==50.0), COF-06 (globalIsMedian + degenerate), D-11 (excludesDeadSaturated), Acceptance (rawFluorescenceRange + mibiRange). `NeighborhoodModelTest` conventions; `new Random(42)`; NO QuPath/JavaFX imports.
- [ ] Extend `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` — authored in **17-02-T2**: `poolAllCellsRawReturnsRawRowsWhilePoolAllCellsZScores` raw-vs-zscored assertion using the existing `fakeProject`/`fakeEntry`/`fakeImageData` helpers.
- [ ] Framework install: none — JUnit 5 already wired in `build.gradle.kts`.

---

## Manual-Only Verifications

> The JavaFX UI surface has no headless harness in this project (consistent with existing
> UI tests covering only non-JavaFX logic, e.g. `FeatureSelectionPaneTest`).

| Behavior | Requirement | Plan/Task | Why Manual | Test Instructions |
|----------|-------------|-----------|------------|-------------------|
| "Suggest…" button present+enabled for arcsinh, absent for sqrt | COF-01 | 17-04-T2 | JavaFX stage; no headless harness | Launch Normalise Features in QuPath; toggle transform; confirm button visibility |
| Suggest window is interactive under the modal Normalise pane | D-05 / correction #2 | 17-03-T1, 17-04-T2 | JavaFX modality semantics | Open Suggest…; confirm the window accepts clicks while the pane stays open |
| Calibration picker independent of normalize set; grouped + searchable; default = marker means | COF-02 | 17-03-T1 | UI wiring (`discoverMarkerFeatures` unit-covered by `IntensityHeatmapTest`) | Open picker; change calibration selection; confirm normalize selection unchanged |
| In-memory only (no geojson/CSV) | COF-03 | 17-02, 17-03 | code-review + runtime | Inspect: only `getMeasurementList()`/`ImageData`/`extractMatrix`/`poolAllCellsRaw` reads; no IO-parser calls |
| Table shows N rows with value-scale summary + per-feature cofactor | COF-05 | 17-03-T2 | JavaFX rendering | Run over ≥2 features; confirm N rows + columns + prominent global |
| Apply sets the spinner in one action; no mutation/normalize | COF-07 | 17-04-T2 | UI wiring | Apply; confirm spinner value set, no measurement change until user confirms normalize |
| Scope selector; whole-project pools all cells; open-image uses open cells | COF-08 | 17-03-T2 | UI wiring (pooling math unit-tested in 17-02) | Run both scopes on a real multi-image project; confirm rare markers represented |

---

## Validation Sign-Off

- [x] All estimator/pooling tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify (every task carries a `<verify><automated>`)
- [x] Wave 0 covers all MISSING references (`CofactorEstimatorTest` in 17-01-T1, `CohortClusterModelTest` raw extension in 17-02-T2)
- [x] No watch-mode flags
- [x] Feedback latency < 15s (quick)
- [x] Manual-only UI checklist (COF-01/02/05/07/08 + D-05) documented for `/gsd-verify-work`
- [x] `nyquist_compliant: true` set — per-task map finalized with real Task IDs

**Approval:** planner-finalized 2026-07-08
