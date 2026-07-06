---
phase: 15
slug: all-cells-leiden-clustering
status: approved
nyquist_compliant: true
wave_0_complete: false
created: 2026-07-06
---

# Phase 15 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Detailed Validation Architecture (per-requirement checks + sampling rationale) is in 15-RESEARCH.md.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 (Jupiter) |
| **Config file** | build.gradle.kts (`testImplementation(libs.junit)`) |
| **Quick run command** | `./gradlew test --tests "qupath.ext.celltune.model.LeidenModelTest"` |
| **Full suite command** | `./gradlew test` |
| **Estimated runtime** | ~30–90 seconds (model tests); shadowJar adds minutes |

---

## Sampling Rate

- **After every task commit:** Run the quick command for the touched model test class
- **After every plan wave:** Run `./gradlew test`
- **Before `/gsd-verify-work`:** Full suite + `./gradlew shadowJar` must be green
- **Max feedback latency:** ~90 seconds (model unit tests)

---

## Per-Task Verification Map

> Reconciled with the finalized plans (Plans 01–05). Task IDs are `15-{plan}-{task}`.
> Pure-array model logic (ANN graph build, SNN/Jaccard rewrite, recall gate, all-cells
> community recovery, UUID mapping) is unit-testable at small synthetic scale; QuPath I/O +
> JavaFX UI wiring and large-scale (30M) behavior are manual. Wave-0 test files are not yet
> written (`wave_0_complete: false`); tasks whose verify depends on a not-yet-created test file
> are marked `❌ W0` in File Exists — those files are enumerated in Wave 0 Requirements below.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 15-01-01 | 01 | 1 | LEI-07,LEI-10 | T-15-02 | pinned dependency, no native binary | build | `.\gradlew.bat compileJava` | ✅ build.gradle.kts | ⬜ pending |
| 15-01-02 | 01 | 1 | LEI-07,LEI-10 | T-15-01 | seeded-deterministic ANN build (byte-identical) | unit (tdd) | `.\gradlew.bat test --tests "qupath.ext.celltune.model.HnswKnnIndexTest"` | ❌ W0 | ⬜ pending |
| 15-02-01 | 02 | 2 | LEI-07,LEI-10 | T-15-03,T-15-04 | recall-gate abort writes nothing; O(sample×n) not O(n²) | unit (tdd) | `.\gradlew.bat test --tests "qupath.ext.celltune.model.LeidenModelTest"` | ❌ W0 | ⬜ pending |
| 15-02-02 | 02 | 2 | LEI-07,LEI-10 | T-15-03 | ANN-routed Leiden gated by recall (ARI vs exact) | unit (tdd) | `.\gradlew.bat test --tests "qupath.ext.celltune.model.LeidenModelTest"` | ❌ W0 | ⬜ pending |
| 15-03-01 | 03 | 3 | LEI-06,LEI-10 | T-15-05,T-15-06 | primitive rewrite byte-identical to boxed (no semantic drift) | unit (tdd) | `.\gradlew.bat test --tests "qupath.ext.celltune.model.LeidenModelTest"` | ❌ W0 | ⬜ pending |
| 15-03-02 | 03 | 3 | LEI-06,LEI-10 | T-15-06 | single-partition recovery; reproducible up to permutation | unit (tdd) | `.\gradlew.bat test --tests "qupath.ext.celltune.model.LeidenModelTest"` | ❌ W0 | ⬜ pending |
| 15-04-01 | 04 | 4 | LEI-06,LEI-08,LEI-10 | T-15-09 | hierarchies released each pass; packed-UUID (no String) | build | `.\gradlew.bat compileJava` | ✅ CohortClusterModel.java | ⬜ pending |
| 15-04-02 | 04 | 4 | LEI-06,LEI-08,LEI-10 | T-15-07,T-15-10 | abort before pass 2 → zero images written; cancel keeps written intact | build | `.\gradlew.bat compileJava` | ✅ CohortClusterModel.java | ⬜ pending |
| 15-04-03 | 04 | 4 | LEI-06,LEI-08,LEI-10 | T-15-08 | reorder-safe UUID write-back; unknown UUID skipped (Cluster=-1) | unit (tdd) | `.\gradlew.bat test --tests "qupath.ext.celltune.model.CohortClusterModelTest"` | ❌ W0 | ⬜ pending |
| 15-05-01 | 05 | 5 | LEI-06,LEI-07,LEI-09 | — | single-image fit routed through ANN; radio scoped to project+Leiden | build | `.\gradlew.bat compileJava` | ✅ ScatterPlotView.java | ⬜ pending |
| 15-05-02 | 05 | 5 | LEI-06,LEI-09 | T-15-11,T-15-12,T-15-13 | soft-ceiling confirm; mid-run cancel; recall surfaced to status | build | `.\gradlew.bat test` | ✅ ScatterPlotView.java | ⬜ pending |
| 15-05-03 | 05 | 5 | LEI-06 (docs) | — | fidelity gaps + packed-UUID/determinism documented | build | `.\gradlew.bat spotlessCheck` | ✅ USER_GUIDE.md, CLAUDE.md | ⬜ pending |
| 15-05-04 | 05 | 5 | LEI-09 | T-15-11,T-15-13 | GUI: radio visibility, progress, cancel summary, legend re-sync | manual | in-QuPath GUI check (shadowJar install) | — | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky. `❌ W0` in File Exists = test file created in Wave 0 (see below).*

---

## Wave 0 Requirements

- [ ] New `src/test/java/qupath/ext/celltune/model/HnswKnnIndexTest.java` (Plan 01 Task 2) — HNSW recall vs exact `featureKnn`, self-exclusion/length, byte-identical determinism across two reproducible builds, degenerate n==0/1, and setEf-escalation-raises-recall (Test E).
- [ ] Extend `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` (Plans 02–03) — recall-gate pass/escalate/abort (`AnnRecallException`), HNSW-vs-exact Leiden ARI, primitive-array SNN/Jaccard **byte-identical** equivalence vs boxed, all-cells single-partition recovery, reproducibility up to permutation (ARI==1.0).
- [ ] New `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` (Plan 04 Task 3) — pooling identity + UUID label mapping under reordered second read, cancel-leaves-written-intact reporting, recall-gate abort path (no measurement written).
- [ ] No new test framework needed — JUnit 5 already present.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Radio-pair mode selection + preview re-sync | LEI-09 | JavaFX dialog interaction | In QuPath: open Scatter dialog in project scope, confirm radio pair present, run all-cells, confirm legend count == cells written |
| Recall status message + abort dialog | LEI-07 | GUI status line / dialog | Force low recall (degraded ef in a test build) and confirm abort surfaces with no Cluster written |
| Cancel mid-write leaves written images intact | LEI-08 | Requires multi-image project + interaction | Cancel during write pass; confirm summary reports written/unwritten images |
| Large-scale (tens of millions) timing/memory | LEI-06 | Cannot run in CI | Manual run on a real cohort; OPTIONAL ad-hoc 1–5M synthetic-row timing smoke test (RESEARCH Open Question 2 — KNOWINGLY DEFERRED, non-gating) |

---

## Validation Sign-Off

- [x] All model-logic tasks have automated JUnit verify or a Wave 0 dependency
- [x] Sampling continuity: no 3 consecutive tasks without automated verify (UI/I-O tasks flagged manual)
- [x] Wave 0 covers all MISSING test references (HnswKnnIndexTest, LeidenModelTest extensions, CohortClusterModelTest enumerated)
- [x] No watch-mode flags
- [x] Feedback latency < 90s for model tests
- [x] `nyquist_compliant: true` set once planner finalizes the per-task map

**Approval:** approved 2026-07-06
