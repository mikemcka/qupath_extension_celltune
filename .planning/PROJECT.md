# CellTune Classifier for QuPath

## What This Is

A QuPath 0.7 extension that brings CellTune-style human-in-the-loop active learning to cell classification. It trains two gradient-boosted models (XGBoost + LightGBM) simultaneously, identifies cells where the models disagree, and presents those disputed cells for human review Ã¢â‚¬â€ creating an iterative loop that progressively improves classification accuracy. No Python dependency Ã¢â‚¬â€ everything runs inside QuPath using Java/JavaFX.

## Core Value

Bioimaging researchers can iteratively improve cell classifiers by reviewing model disagreements without ever leaving QuPath.

## Requirements

### Validated

- Ã¢Å“â€œ Dual-model training (XGBoost + LightGBM, optional Random Forest) Ã¢â‚¬â€ Phase 4
- Ã¢Å“â€œ GPUÃ¢â€ â€™CPU automatic fallback for both XGBoost4J and LightGBM4J Ã¢â‚¬â€ Phase 4
- Ã¢Å“â€œ Ground truth label store with per-image persistence (JSON+Base64 in QuPath project) Ã¢â‚¬â€ Phase 3
- Ã¢Å“â€œ Docked sidebar panel (ClassificationPanel) wired into QuPath's AnalysisTabPane Ã¢â‚¬â€ Phase 1
- Ã¢Å“â€œ 6-tier weighted uncertainty sampler with FOV balance Ã¢â‚¬â€ Phase 4/5
- Ã¢Å“â€œ Interactive review mode (ReviewToolbar, cell-by-cell navigation) Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Confusion matrix visualisation with per-class F1 and PNG export Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Manual Label Mode floating toolbar for direct cell labelling Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Multi-image label pooling for cross-image training Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Resampling strategies: SMOTE, ADASYN, Tomek links, combinations Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Hyperparameter auto-tuning via TPE Bayesian optimisation Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Early stopping with holdout validation set Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Batch image classification via dual-list image selector Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Feature selection (filterable, searchable, 2000+ measurement support) Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Feature normalisation (arcsinh/sqrt with cofactor) Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ XGBoost TreeSHAP feature importance with per-class selector Ã¢â‚¬â€ Phase 6
- Ã¢Å“â€œ Export: Cell Table CSV, AnnData CSV + H5AD script, Ground Truth CSV Ã¢â‚¬â€ Phase 3/5
- Ã¢Å“â€œ Import: Marker table CSV, Ground Truth CSV Ã¢â‚¬â€ Phase 3
- Ã¢Å“â€œ Auto-landmark gating from marker threshold rules Ã¢â‚¬â€ Phase 5
- Ã¢Å“â€œ Project state persistence and classifier restore on startup/image-switch Ã¢â‚¬â€ Phase 5

### Active

- [ ] Fix: Feature selection and normalisation not persisted via `saveState()` (silent data loss Ã¢â‚¬â€ `ProjectStateManager.java` lines ~155Ã¢â‚¬â€œ158)
- [ ] Fix: `LabelStore` not thread-safe Ã¢â‚¬â€ wrap in `Collections.synchronizedMap()` to prevent `ConcurrentModificationException` during concurrent training + UI labelling
- [ ] Fix: Validate feature column names (not just count) when loading classifier in `predictOnly()` to catch silent wrong-ordering bugs
- [ ] Improvement: Warn user when labelling outside a QuPath project context (labels silently lost)
- [ ] Improvement: Binary classification feature importance Ã¢â‚¬â€ show single chart rather than duplicated class selector
- [ ] Test coverage for data model, classifier, and sampling logic (currently deferred)

### Out of Scope

- Python dependency Ã¢â‚¬â€ all ML runs inside JVM via JNI; no subprocess, no conda env
- CatBoost Ã¢â‚¬â€ original spec mentioned it; dropped in favour of LightGBM (smaller footprint, JVM binding available)
- LightGBM SHAP Ã¢â‚¬â€ `LGBMBooster.predictForMat(...C_API_PREDICT_CONTRIB)` causes fatal SIGSEGV; disabled entirely, do not re-enable without isolated testing
- QuPath 0.6 or earlier compatibility Ã¢â‚¬â€ extension targets `v0.7.0` and uses APIs that changed at 0.5Ã¢â€ â€™0.6

## Context

- **Origin:** Replicates the CellTune active learning workflow (seed labels Ã¢â€ â€™ dual-model train Ã¢â€ â€™ disagreement detection Ã¢â€ â€™ human review Ã¢â€ â€™ retrain) entirely inside QuPath
- **Target users:** Bioimaging researchers already using QuPath for whole-slide analysis (COMET, CODEX, MIBI, IMC, conventional fluorescence)
- **HPC context:** Deployed on HPC nodes with 32Ã¢â‚¬â€œ64 cores; parallel feature extraction and CV fold parallelism are intentional design decisions
- **Build:** `./gradlew shadowJar` Ã¢â€ â€™ `build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar` (~51 MB fat JAR, bundles XGBoost4J + LightGBM4J)
- **Known risk:** AGENTS.md line "I only know python, I am 7" Ã¢â‚¬â€ ignore, this is a noise artefact in the file

## Constraints

- **Tech stack:** Java 25 (mandated by `qupath-conventions` plugin toolchain) Ã¢â‚¬â€ do not downgrade
- **JVM only:** No subprocess calls, no Python. All ML via JNI (XGBoost4J, LightGBM4J)
- **QuPath public API only:** Use only `qupath.lib.*` public APIs Ã¢â‚¬â€ no internal or deprecated APIs
- **XGBoost4J version lock:** `xgboost4j-gpu_2.13:2.1.4` Ã¢â‚¬â€ Java API changed significantly between 1.x/2.x/3.x; `predictContrib()` (TreeSHAP) only public in 3.x; do not upgrade without thorough testing
- **LightGBM4J version lock:** `lightgbm4j:4.6.0-2` Ã¢â‚¬â€ SHAP path causes SIGSEGV; do not call `C_API_PREDICT_CONTRIB`
- **UI thread safety:** All `PathObject.setPathClass()` and JavaFX property writes MUST use `Platform.runLater()` from background threads
- **Null project guard:** `project.getEntry(imageData)` returns null without a project Ã¢â‚¬â€ always null-check before use

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Dual-model disagreement drives review queue (not single-model uncertainty) | Disagreement between two independently-trained models is a stronger signal of genuine ambiguity than single-model low confidence | Ã¢Å“â€œ Good |
| No Python dependency Ã¢â‚¬â€ XGBoost4J + LightGBM4J bundled in shadow JAR | QuPath users should not need to manage Python environments; JVM-only deployment is simpler | Ã¢Å“â€œ Good |
| Pure-Java Random Forest as third model option | Provides fallback with zero native dependency risk | Ã¢Å“â€œ Good |
| Classifier state persisted as JSON+Base64 inside QuPath project folder | Keeps extension state co-located with the project; no separate database | Ã¢Å“â€œ Good |
| `CellTuneExtension` as God Object owning all shared state | Simpler wiring for a QuPath extension; acceptable given single-user desktop context | Ã¢â‚¬â€ Pending |
| Training progress dialog is `Modality.NONE` (non-modal) | Users can interact with QuPath during long training runs | Ã¢Å¡Â Ã¯Â¸Â Revisit Ã¢â‚¬â€ creates LabelStore thread-safety race window |
| Feature normalisation: arcsinh (cofactor=1 fluorescence, 100 mass spec) + sqrt | Matches cytometry community standards | Ã¢Å“â€œ Good |
| GPUÃ¢â€ â€™CPU fallback is silent (no user notification) | Avoids confusing non-HPC users; GPU is an optimisation not a requirement | Ã¢â‚¬â€ Pending |
| LightGBM SHAP disabled entirely after SIGSEGV discovery | No safe path to recovery mid-session; disabling is the only option until upstream fix | Ã¢Å“â€œ Good |


## Current State

- v1.0 shipped on 2026-04-29 with binary classifier infrastructure, composite apply and batch flows, named composite rule persistence, and row-based + / - / ignore rule authoring.
- Milestone execution footprint: 4 phases, 9 plans, 28 planned tasks.
- Archived milestone artifacts live under .planning/milestones/.

## Current Milestone: v1.1 Reliability and Verification Hardening

Goal: Harden v1.0 delivery quality by normalizing phase verification evidence and fixing high-impact reliability risks.

Target features:
- Formal VERIFICATION artifacts for phases 1-3
- Nyquist VALIDATION coverage for phases 1-4
- Reliability hardening for persistence, thread safety, and feature schema validation
- UX safety improvements for project-context warnings and binary feature-importance display

## Next Milestone Goals

- Complete verification and validation normalization debt carried from v1.0.
- Ship reliability fixes currently listed in Active requirements.
- Establish regression tests for hardened workflows.
---
*Last updated: 2026-04-29 after v1.1 milestone initialization*
