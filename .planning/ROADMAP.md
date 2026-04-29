# Roadmap - CellTune QuPath Extension

## Milestones

- [x] v1.0 Binary Composite Classification (shipped 2026-04-29) - see [v1.0 archive](milestones/v1.0-ROADMAP.md)
- [ ] v1.1 Reliability and Verification Hardening (in progress)

## Milestone v1.1 - Reliability and Verification Hardening

Goal: Close verification-evidence debt and harden reliability risks found after v1.0 shipment.

### Phase 5 - Verification Artifact Backfill

Goal: Normalize legacy milestone evidence by creating formal phase verification artifacts for phases 1-3.
Depends on: v1.0 archives and existing phase summaries/UAT evidence.
Requirements: EVID-01, EVID-02, EVID-03
Success Criteria:
1. Phase 1 has VERIFICATION artifact mapped to its shipped behaviors.
2. Phase 2 has VERIFICATION artifact covering single-image and batch composite flows.
3. Phase 3 has VERIFICATION artifact for named-rule persistence and apply flows.
4. Milestone audit no longer flags orphaned requirements caused by missing phase verification files.
Plans: 2 plans

Plans:
- [x] 05-01-PLAN.md - Create formal verification artifacts for phases 1 and 2 (completed 2026-04-29)
- [x] 05-02-PLAN.md - Create phase 3 verification artifact and refresh v1.0 audit evidence (completed 2026-04-29)

### Phase 6 - Nyquist Validation Coverage

Goal: Add Nyquist VALIDATION coverage for all milestone phases and produce explicit compliance status.
Depends on: Phase 5 verification artifacts.
Requirements: NYQ-01, NYQ-02, NYQ-03, NYQ-04
Success Criteria:
1. Each phase 1-4 has VALIDATION artifact with nyquist_compliant field.
2. Validation files include reproducible checks and sampling rationale.
3. Missing-phase warnings in audit are removed for Nyquist coverage.
Plans: To be planned

### Phase 7 - Reliability Core Hardening

Goal: Fix core reliability defects in persistence, concurrency safety, and feature-schema validation.
Depends on: Phase 6.
Requirements: REL-01, REL-02, REL-03
Success Criteria:
1. saveState/reload preserves selected features and normalization settings.
2. LabelStore operations are safe under concurrent training and labeling usage.
3. predictOnly rejects mismatched feature name schemas and surfaces actionable errors.
Plans: To be planned

### Phase 8 - UX Safety and Explainability Polish

Goal: Improve user-facing safety feedback and binary explainability display behavior.
Depends on: Phase 7.
Requirements: UX-01, UX-02
Success Criteria:
1. Out-of-project labeling attempts show explicit warning.
2. Binary SHAP display avoids duplicate class views and uses a single coherent mode.
Plans: To be planned

### Phase 9 - Regression Tests for Hardening

Goal: Add automated tests covering reliability and verification hardening paths.
Depends on: Phases 7-8.
Requirements: TST-01
Success Criteria:
1. New test suite covers persistence, schema validation, and key verification-path regressions.
2. CI or local test command demonstrates stable pass for new hardening tests.
Plans: To be planned

## Progress

| Phase | Milestone | Requirements | Plans Complete | Status |
|-------|-----------|--------------|----------------|--------|
| 5. Verification Artifact Backfill | v1.1 | EVID-01,EVID-02,EVID-03 | 2/2 | Complete (2026-04-29) |
| 6. Nyquist Validation Coverage | v1.1 | NYQ-01,NYQ-02,NYQ-03,NYQ-04 | 0/0 | Not started |
| 7. Reliability Core Hardening | v1.1 | REL-01,REL-02,REL-03 | 0/0 | Not started |
| 8. UX Safety and Explainability Polish | v1.1 | UX-01,UX-02 | 0/0 | Not started |
| 9. Regression Tests for Hardening | v1.1 | TST-01 | 0/0 | Not started |
