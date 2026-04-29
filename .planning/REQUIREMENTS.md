# Requirements: CellTune QuPath Extension

**Defined:** 2026-04-29
**Core Value:** Bioimaging researchers can iteratively improve cell classifiers by reviewing model disagreements without leaving QuPath.

## v1 Requirements

Requirements for Milestone 1 (Binary Composite Classification). These map to Phases 1-4 in ROADMAP (including gap-closure phases).

### Binary Classifier Infrastructure

- [x] **BIN-01**: User can create, open, and delete named binary marker classifiers from Extensions > CellTune > Binary Classifiers.
- [x] **BIN-02**: Each marker classifier persists independently with registry mapping and per-marker state in project storage.
- [x] **BIN-03**: Opening a marker enters binary mode and exiting binary mode restores the prior multi-class state.
- [x] **BIN-04**: Binary classifier registry and marker sessions survive QuPath restart.

### Composite Classification Builder

- [ ] **COMP-01**: User can define a composite rule as marker-polarity pairs, for example CD4+:CD3+:CD20-.
- [ ] **COMP-02**: Composite builder UI supports per-marker + / - / ignore selection and user-specified composite naming.
- [x] **COMP-03**: Applying composite classification writes PathClass labels on detections and those labels flow to export outputs.
- [ ] **COMP-04**: Named composite rules persist in project storage and can be reused across sessions.
- [x] **COMP-05**: Composite classification can run across selected batch images in a project.

## v2 Requirements

### Composite Enhancements

- **COMP-06**: Support OR-groups and nested composite definitions for advanced phenotype logic.
- **COMP-07**: Add confidence-gated composite assignment controls beyond fixed threshold decisions.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Auto-threshold binary seeding from marker intensity | Locked decision is manual-only seeding due spillover and sample variability. |
| Probability-product composite scoring | Milestone decision uses hard threshold and deterministic logic. |
| Python runtime dependency | Extension is JVM-only and runs inside QuPath without Python. |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| BIN-01 | Phase 1 | Complete |
| BIN-02 | Phase 1 | Complete |
| BIN-03 | Phase 1 | Complete |
| BIN-04 | Phase 1 | Complete |
| COMP-01 | Phase 3 | Pending |
| COMP-02 | Phase 4 | Pending |
| COMP-03 | Phase 2 | Complete |
| COMP-04 | Phase 3 | Pending |
| COMP-05 | Phase 2 | Complete |

**Coverage:**
- v1 requirements: 9 total
- Mapped to phases: 9
- Unmapped: 0

---
*Requirements defined: 2026-04-29*
*Last updated: 2026-04-29 after gap-closure phase creation from milestone audit*


