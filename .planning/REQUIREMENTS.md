# Requirements: CellTune QuPath Extension

Defined: 2026-04-29
Core Value: Bioimaging researchers can iteratively improve cell classifiers by reviewing model disagreements without leaving QuPath.

## v1 Requirements

Requirements for milestone v1.1 Reliability and Verification Hardening.

### Verification Evidence

- [x] EVID-01: Phase 1 has a formal VERIFICATION artifact migrated from existing UAT evidence.
- [x] EVID-02: Phase 2 has a formal VERIFICATION artifact proving composite apply and batch workflows.
- [x] EVID-03: Phase 3 has a formal VERIFICATION artifact proving named-rule save, reload, and apply behavior.

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

### Documentation and Governance

- [x] GOV-01: Phase 10 has a formal code review artifact for project prediction summary implementation.
- [x] DOC-01: README documents Project Prediction Summary workflow including open-image and CSV export actions.
- [x] DOC-02: Standalone build documentation exists with JDK 25 prerequisites and compile/test/shadowJar commands.

## v2 Requirements

### Composite Evolution

- COMP-06: Support OR-groups and nested composite definitions for advanced phenotype logic.
- COMP-07: Add confidence-gated composite assignment controls beyond fixed threshold decisions.

### Cohort Analytics

- COH-01: Project summary computes cohort baseline proportions and per-image rare-type enrichment scores for predicted classes.
- COH-02: Project summary computes robust outlier metrics (composition distance and disagreement anomaly) and exposes a ranked anomaly score per image.
- COH-03: Project summary UI supports flagged-image filtering and class-targeted review workflows for unusual cell-type distributions.
- COH-04: Automated tests cover cohort scoring math and summary CSV export schema for anomaly analytics fields.

### Binary Ground Truth Portability

- [ ] XFER-01: Export all registered binary classifiers with persisted marker-specific ground-truth measurement rows into a single portable bundle.
- [ ] XFER-02: Importing a bundle into another project registers missing binary classifiers and restores per-marker training rows for immediate reuse.
- [ ] XFER-03: Import supports explicit merge/replace behavior per marker and reports schema mismatches without mutating unaffected markers.
- [ ] XFER-04: Automated tests verify bundle round-trip fidelity plus malformed-bundle and schema-mismatch handling.

### Cellular Neighborhood Analytics

- [ ] CN-01: Each cell on the current image gets a local-neighborhood cell-type composition vector (kNN or radius) built from its live PathClass classification.
- [ ] CN-02: Composition vectors are k-means clustered into cellular neighborhoods stored as a non-destructive numeric CN measurement, leaving getPathClass() unchanged.
- [ ] CN-03: A CN × cell-type enrichment heatmap, a per-image CN-frequency CSV, and a non-destructive Classification ⟷ CN viewer color toggle are available from the dialog.
- [ ] CN-04: Automated tests cover kNN/radius neighbor finding (self-excluded), composition normalization, clustering, and per-CN mean composition.

### Graph-based Phenotype Clustering (Leiden)

- [ ] LEI-01: The Scatter Plots & Clustering dialog offers a Method selector {k-means, Leiden}; choosing Leiden replaces the "Clusters (k)" control with a resolution control plus a reproducibility toggle, and lets Leiden decide the cluster count.
- [ ] LEI-02: Leiden clusters the same z-scored active marker matrix k-means uses — via a feature-space kNN graph, Jaccard/SNN edge weights, and the CWTS Leiden algorithm — returning a label array that drives plot colouring, legend, and cluster→class assignment identically to k-means.
- [ ] LEI-03: Project/cohort scope supports Leiden by fitting on the pooled sample and assigning every cell across images via kNN label transfer (majority vote of nearest sampled cells), leaving the k-means centroid path unchanged.
- [ ] LEI-04: A pure-array LeidenModel (feature kNN, edge weighting, community detection, kNN label transfer) is covered by JUnit tests on synthetic graphs (community recovery by purity/modularity, self-exclusion, resolution behaviour, label transfer), with no QuPath/JavaFX types.
- [ ] LEI-05: Leiden runs are reproducible run-to-run via a seeded RNG + random-starts, exposed as a reproducibility toggle mirroring the k-means multi-restart option.

## Out of Scope

| Feature | Reason |
|---------|--------|
| New model families beyond existing stack | v1.1 focuses on reliability and verification hardening only. |
| New UI workflow families unrelated to current Active concerns | Scope is constrained to safety and correctness improvements. |
| Migration off QuPath 0.7 APIs | Compatibility changes are deferred to a future milestone. |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| EVID-01 | Phase 5 | Complete |
| EVID-02 | Phase 5 | Complete |
| EVID-03 | Phase 5 | Complete |
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
| GOV-01 | Phase 10 | Complete |
| DOC-01 | Phase 10 | Complete |
| DOC-02 | Phase 10 | Complete |
| COH-01 | Phase 11 | Complete |
| COH-02 | Phase 11 | Complete |
| COH-03 | Phase 11 | Complete |
| COH-04 | Phase 11 | Complete |
| XFER-01 | Phase 12 | Pending |
| XFER-02 | Phase 12 | Pending |
| XFER-03 | Phase 12 | Pending |
| XFER-04 | Phase 12 | Pending |
| CN-01 | Phase 13 | Pending |
| CN-02 | Phase 13 | Pending |
| CN-03 | Phase 13 | Pending |
| CN-04 | Phase 13 | Pending |
| LEI-01 | Phase 14 | Pending |
| LEI-02 | Phase 14 | Pending |
| LEI-03 | Phase 14 | Pending |
| LEI-04 | Phase 14 | Pending |
| LEI-05 | Phase 14 | Pending |

Coverage:
- requirements listed in this file: 35
- mapped to phases: 33
- unmapped: 2 (COMP-06, COMP-07)

---
Requirements defined: 2026-04-29
Last updated: 2026-04-29 after phase 10 documentation and governance refresh
