---
phase: 15
slug: all-cells-leiden-clustering
status: draft
nyquist_compliant: false
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

> Filled/refined by the planner per task. Pure-array model logic (ANN graph build, SNN/Jaccard
> rewrite, recall gate, all-cells community recovery, UUID mapping) is unit-testable at small
> synthetic scale; QuPath I/O + JavaFX UI wiring and large-scale (30M) behavior are manual.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 15-01-01 | 01 | 1 | LEI-07 | — | N/A | unit | `./gradlew test --tests "*LeidenModelTest*"` | ❌ W0 | ⬜ pending |
| 15-01-02 | 01 | 1 | LEI-07 | — | recall gate abort writes nothing | unit | `./gradlew test --tests "*LeidenModelTest*"` | ❌ W0 | ⬜ pending |
| 15-02-01 | 02 | 2 | LEI-06,LEI-08 | — | non-destructive Cluster write | unit | `./gradlew test --tests "*CohortClusterModelTest*"` | ❌ W0 | ⬜ pending |
| 15-03-01 | 03 | 3 | LEI-09 | — | N/A (JavaFX) | manual | in-QuPath GUI check | — | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky — table is indicative; planner sets the authoritative map.*

---

## Wave 0 Requirements

- [ ] Extend `src/test/java/qupath/ext/celltune/model/LeidenModelTest.java` — ANN kNN graph build vs exact featureKnn (recall), primitive-array SNN/Jaccard equivalence, all-cells community recovery on synthetic cliques, HNSW-vs-exact Leiden ARI.
- [ ] New `src/test/java/qupath/ext/celltune/model/CohortClusterModelTest.java` (or extend existing) — pooling + UUID label mapping under reordered second read, recall-gate abort path (no measurement written).
- [ ] No new test framework needed — JUnit 5 already present.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Radio-pair mode selection + preview re-sync | LEI-09 | JavaFX dialog interaction | In QuPath: open Scatter dialog in project scope, confirm radio pair present, run all-cells, confirm legend count == cells written |
| Recall status message + abort dialog | LEI-07 | GUI status line / dialog | Force low recall (degraded ef in a test build) and confirm abort surfaces with no Cluster written |
| Cancel mid-write leaves written images intact | LEI-08 | Requires multi-image project + interaction | Cancel during write pass; confirm summary reports written/unwritten images |
| Large-scale (tens of millions) timing/memory | LEI-06 | Cannot run in CI | Manual run on a real cohort; smoke-test 1–5M synthetic rows early per RESEARCH open question |

---

## Validation Sign-Off

- [ ] All model-logic tasks have automated JUnit verify or a Wave 0 dependency
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify (UI/I-O tasks flagged manual)
- [ ] Wave 0 covers all MISSING test references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s for model tests
- [ ] `nyquist_compliant: true` set once planner finalizes the per-task map

**Approval:** pending
