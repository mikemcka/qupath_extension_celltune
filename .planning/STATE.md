---
gsd_state_version: 1.0
milestone: v1.1
milestone_name: - Reliability and Verification Hardening
status: unknown
last_updated: "2026-07-06T03:08:00.821Z"
progress:
  total_phases: 12
  completed_phases: 10
  total_plans: 24
  completed_plans: 21
  percent: 88
---

# Project State

## Current Position

- Milestone: v1.5 Graph-based Phenotype Clustering (Phases 15 and 16 — COMPLETE)
- Phase: 16 (PCA dimensionality reduction for clustering) RECORDED and COMPLETE — retroactive plan/summary filed for the conditional-PCA feature that was implemented inline after Phase 15's checkpoint approval; 15 (all-cells true-scanpy Leiden) SPEC + CONTEXT + RESEARCH + VALIDATION + 5 PLANs filed and checker-passed, all executed; 14 code complete (transfer path); 13 planned; v1.1 phases 6-9 still pending; 5/10/11 complete
- Plan: 16-01 (retroactive record) COMPLETE — documents ScatterMath.pcaReduce (conditional, deterministic Smile PCA reduction between z-scoring and the clustering kNN graph) wired into the single-image/preview fit, the all-cells cohort driver, and the Leiden kNN-transfer query path; UI checkbox + components spinner; dominance/determinism/marker-space-centroid tests; USER_GUIDE/CLAUDE.md docs closing scanpy fidelity gap #3. Delivered by commits 41415fa..decc05f (2026-07-06); real-data validation against the Nolan CODEX CRC set (56→50 comps, 98.4% variance, ARI 0.204→0.233 vs no-PCA) lives in the sibling nolan_paper_validation repo.
- Status: Phase 15 COMPLETE — 15-VERIFICATION.md passed (24/24 must-haves; LEI-06..10 all satisfied and marked Complete in REQUIREMENTS.md). One accepted non-blocking deviation: D-09 recall status line is graceful/non-fabricated because clusterViaAnn does not surface its internal recall (AllCellsResult.recall = -1.0 sentinel); the recall gate itself is fully implemented + tested. Phase 16 (conditional PCA, PCA-01..06) now RECORDED and marked Complete in ROADMAP.md/REQUIREMENTS.md/STATE.md — the feature was implemented and validated inline before this bookkeeping pass; no source code was modified while recording it.
- Last activity: 2026-07-06 - Phase 15 verified and marked complete (all 5 plans + human-verify approved on a full multi-image project); conditional PCA before the clustering kNN graph was then added inline (separate work, commits 41415fa..decc05f) and has now been recorded retroactively as Phase 16 (16-01-PLAN.md/16-01-SUMMARY.md, PCA-01..06 added to REQUIREMENTS.md, ROADMAP.md Phase 16 section + progress row); scanpy/Leiden/HNSW acknowledgements and a Leiden User-Guide figure were also added inline around the same time.

## Carry-Forward Context

- Previous milestone archived: v1.0 Binary Composite Classification.
- Verification evidence debt for phases 1-3 is normalized via formal VERIFICATION artifacts.
- Documentation and build guidance now include project prediction summary workflow and reproducible build steps.
- Remaining milestone scope: Nyquist validation coverage and reliability hardening phases 6-9.
- Additional completed phase: 11 (v1.2) cohort outlier analytics for project summary rare-type enrichment and anomaly triage.

## Deferred Items (Carried)

| Category | Item | Status |
|----------|------|--------|
| verification | phase-01-verification-md-missing | resolved in phase 5 |
| verification | phase-02-verification-md-missing | resolved in phase 5 |
| verification | phase-03-verification-md-missing | resolved in phase 5 |
| validation | nyquist-validation-phase-1-to-4-missing | active in v1.1 |

## Session Continuity

- Recommended next action: Phases 15 and 16 are both complete and recorded (v1.5 milestone: LEI-01..05 code-complete under Phase 14, LEI-06..10 + PCA-01..06 complete under Phases 15/16). Next: decide next milestone work (Phase 13 CN spatial clustering; Phase 12 binary ground-truth bundle export/import; or /gsd-plan-phase 6 to resume v1.1 hardening).
- Note for phase-15 verification: `AllCellsResult.recall` is still the documented `-1.0` sentinel (carried from 15-04) — 15-05's status line shows the measured ANN recall only when a real value is available and omits the clause otherwise, deliberately not fabricating a number. A future small `LeidenModel`/`CohortClusterModel` API change could fully satisfy D-09's exact "ANN recall 0.982 — passed" wording; this is a known, documented gap, not a defect.
- Note carried from 15-01: HnswKnnIndex's reproducible=true build is "best-effort deterministic" (documented caveat) — HnswIndex cannot be subclassed from external source in jelmerk 1.2.1 (bytecode-verified); do not attempt the seeded-assignLevel subclass approach again without new information
- Note: conditional PCA (`ScatterMath.pcaReduce`, commits `41415fa`..`decc05f`) was added inline after 15-05's checkpoint approval and has now been recorded as Phase 16 (16-01-PLAN.md/16-01-SUMMARY.md, PCA-01..06 complete in REQUIREMENTS.md) — no further recording action needed.
- Alternative: manually verify phase 14 Leiden flow in QuPath (build/libs/qupath-extension-celltune-0.2.0-all.jar); or /gsd-execute-phase 13 (CN spatial clustering); or /gsd-plan-phase 6 to resume v1.1 hardening
