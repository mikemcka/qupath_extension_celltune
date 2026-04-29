# Requirements: CellTune QuPath Extension

Defined: 2026-04-29
Core Value: Bioimaging researchers can iteratively improve cell classifiers by reviewing model disagreements without leaving QuPath.

## v1 Requirements

Requirements for milestone v1.1 Reliability and Verification Hardening.

### Verification Evidence

- [ ] EVID-01: Phase 1 has a formal VERIFICATION artifact migrated from existing UAT evidence.
- [ ] EVID-02: Phase 2 has a formal VERIFICATION artifact proving composite apply and batch workflows.
- [ ] EVID-03: Phase 3 has a formal VERIFICATION artifact proving named-rule save, reload, and apply behavior.

### Nyquist Validation

- [ ] NYQ-01: Phase 1 has VALIDATION coverage with nyquist_compliant status and explicit sampling evidence.
- [ ] NYQ-02: Phase 2 has VALIDATION coverage with nyquist_compliant status and explicit sampling evidence.
- [ ] NYQ-03: Phase 3 has VALIDATION coverage with nyquist_compliant status and explicit sampling evidence.
- [ ] NYQ-04: Phase 4 has VALIDATION coverage with nyquist_compliant status and explicit sampling evidence.

### Reliability Hardening

- [ ] REL-01: Feature selection and normalization settings persist through saveState and reload correctly.
- [ ] REL-02: LabelStore operations are thread-safe during concurrent training and labeling.
- [ ] REL-03: Prediction-only classifier load validates feature column names, not only feature count.

### UX Safety

- [ ] UX-01: Labelling attempts outside a project context show a clear warning instead of silent data loss.
- [ ] UX-02: Binary classification feature-importance view uses a single shared chart mode instead of duplicate class views.

### Regression Tests

- [ ] TST-01: Automated tests cover new reliability and validation paths introduced in this milestone.

## v2 Requirements

### Composite Evolution

- COMP-06: Support OR-groups and nested composite definitions for advanced phenotype logic.
- COMP-07: Add confidence-gated composite assignment controls beyond fixed threshold decisions.

## Out of Scope

| Feature | Reason |
|---------|--------|
| New model families beyond existing stack | v1.1 focuses on reliability and verification hardening only. |
| New UI workflow families unrelated to current Active concerns | Scope is constrained to safety and correctness improvements. |
| Migration off QuPath 0.7 APIs | Compatibility changes are deferred to a future milestone. |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| EVID-01 | Phase 5 | Pending |
| EVID-02 | Phase 5 | Pending |
| EVID-03 | Phase 5 | Pending |
| NYQ-01 | Phase 6 | Pending |
| NYQ-02 | Phase 6 | Pending |
| NYQ-03 | Phase 6 | Pending |
| NYQ-04 | Phase 6 | Pending |
| REL-01 | Phase 7 | Pending |
| REL-02 | Phase 7 | Pending |
| REL-03 | Phase 7 | Pending |
| UX-01 | Phase 8 | Pending |
| UX-02 | Phase 8 | Pending |
| TST-01 | Phase 9 | Pending |

Coverage:
- v1 requirements: 13 total
- Mapped to phases: 13
- Unmapped: 0

---
Requirements defined: 2026-04-29
Last updated: 2026-04-29 after v1.1 milestone initialization
