# CellTune Classifier for QuPath

## What This Is

A QuPath 0.7 extension that brings CellTune-style human-in-the-loop active learning to cell classification. It trains two gradient-boosted models (XGBoost + LightGBM) simultaneously, identifies cells where the models disagree, and presents those disputed cells for human review â€” creating an iterative loop that progressively improves classification accuracy. No Python dependency â€” everything runs inside QuPath using Java/JavaFX.

## Core Value

Bioimaging researchers can iteratively improve cell classifiers by reviewing model disagreements without ever leaving QuPath.

## Requirements

### Validated

- âœ“ Dual-model training (XGBoost + LightGBM, optional Random Forest) â€” Phase 4
- âœ“ GPUâ†’CPU automatic fallback for both XGBoost4J and LightGBM4J â€” Phase 4
- âœ“ Ground truth label store with per-image persistence (JSON+Base64 in QuPath project) â€” Phase 3
- âœ“ Docked sidebar panel (ClassificationPanel) wired into QuPath's AnalysisTabPane â€” Phase 1
- âœ“ 6-tier weighted uncertainty sampler with FOV balance â€” Phase 4/5
- âœ“ Interactive review mode (ReviewToolbar, cell-by-cell navigation) â€” Phase 5
- âœ“ Confusion matrix visualisation with per-class F1 and PNG export â€” Phase 5
- âœ“ Manual Label Mode floating toolbar for direct cell labelling â€” Phase 5
- âœ“ Multi-image label pooling for cross-image training â€” Phase 5
- âœ“ Resampling strategies: SMOTE, ADASYN, Tomek links, combinations â€” Phase 5
- âœ“ Hyperparameter auto-tuning via TPE Bayesian optimisation â€” Phase 5
- âœ“ Early stopping with holdout validation set â€” Phase 5
- âœ“ Batch image classification via dual-list image selector â€” Phase 5
- âœ“ Feature selection (filterable, searchable, 2000+ measurement support) â€” Phase 5
- âœ“ Feature normalisation (arcsinh/sqrt with cofactor) â€” Phase 5
- âœ“ XGBoost TreeSHAP feature importance with per-class selector â€” Phase 6
- âœ“ Export: Cell Table CSV, AnnData CSV + H5AD script, Ground Truth CSV â€” Phase 3/5
- âœ“ Import: Marker table CSV, Ground Truth CSV â€” Phase 3
- âœ“ Auto-landmark gating from marker threshold rules â€” Phase 5
- âœ“ Project state persistence and classifier restore on startup/image-switch â€” Phase 5

### Active

- [ ] Fix: Feature selection and normalisation not persisted via `saveState()` (silent data loss â€” `ProjectStateManager.java` lines ~155â€“158)
- [ ] Fix: `LabelStore` not thread-safe â€” wrap in `Collections.synchronizedMap()` to prevent `ConcurrentModificationException` during concurrent training + UI labelling
- [ ] Fix: Validate feature column names (not just count) when loading classifier in `predictOnly()` to catch silent wrong-ordering bugs
- [ ] Improvement: Warn user when labelling outside a QuPath project context (labels silently lost)
- [ ] Improvement: Binary classification feature importance â€” show single chart rather than duplicated class selector
- [ ] Test coverage for data model, classifier, and sampling logic (currently deferred)

### Out of Scope

- Python dependency â€” all ML runs inside JVM via JNI; no subprocess, no conda env
- CatBoost â€” original spec mentioned it; dropped in favour of LightGBM (smaller footprint, JVM binding available)
- LightGBM SHAP â€” `LGBMBooster.predictForMat(...C_API_PREDICT_CONTRIB)` causes fatal SIGSEGV; disabled entirely, do not re-enable without isolated testing
- QuPath 0.6 or earlier compatibility â€” extension targets `v0.7.0` and uses APIs that changed at 0.5â†’0.6

## Context

- **Origin:** Replicates the CellTune active learning workflow (seed labels â†’ dual-model train â†’ disagreement detection â†’ human review â†’ retrain) entirely inside QuPath
- **Target users:** Bioimaging researchers already using QuPath for whole-slide analysis (COMET, CODEX, MIBI, IMC, conventional fluorescence)
- **HPC context:** Deployed on HPC nodes with 32â€“64 cores; parallel feature extraction and CV fold parallelism are intentional design decisions
- **Build:** `./gradlew shadowJar` â†’ `build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar` (~51 MB fat JAR, bundles XGBoost4J + LightGBM4J)
- **Known risk:** AGENTS.md line "I only know python, I am 7" â€” ignore, this is a noise artefact in the file

## Constraints

- **Tech stack:** Java 25 (mandated by `qupath-conventions` plugin toolchain) â€” do not downgrade
- **JVM only:** No subprocess calls, no Python. All ML via JNI (XGBoost4J, LightGBM4J)
- **QuPath public API only:** Use only `qupath.lib.*` public APIs â€” no internal or deprecated APIs
- **XGBoost4J version lock:** `xgboost4j-gpu_2.13:2.1.4` â€” Java API changed significantly between 1.x/2.x/3.x; `predictContrib()` (TreeSHAP) only public in 3.x; do not upgrade without thorough testing
- **LightGBM4J version lock:** `lightgbm4j:4.6.0-2` â€” SHAP path causes SIGSEGV; do not call `C_API_PREDICT_CONTRIB`
- **UI thread safety:** All `PathObject.setPathClass()` and JavaFX property writes MUST use `Platform.runLater()` from background threads
- **Null project guard:** `project.getEntry(imageData)` returns null without a project â€” always null-check before use

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Dual-model disagreement drives review queue (not single-model uncertainty) | Disagreement between two independently-trained models is a stronger signal of genuine ambiguity than single-model low confidence | âœ“ Good |
| No Python dependency â€” XGBoost4J + LightGBM4J bundled in shadow JAR | QuPath users should not need to manage Python environments; JVM-only deployment is simpler | âœ“ Good |
| Pure-Java Random Forest as third model option | Provides fallback with zero native dependency risk | âœ“ Good |
| Classifier state persisted as JSON+Base64 inside QuPath project folder | Keeps extension state co-located with the project; no separate database | âœ“ Good |
| `CellTuneExtension` as God Object owning all shared state | Simpler wiring for a QuPath extension; acceptable given single-user desktop context | â€” Pending |
| Training progress dialog is `Modality.NONE` (non-modal) | Users can interact with QuPath during long training runs | âš ï¸ Revisit â€” creates LabelStore thread-safety race window |
| Feature normalisation: arcsinh (cofactor=1 fluorescence, 100 mass spec) + sqrt | Matches cytometry community standards | âœ“ Good |
| GPUâ†’CPU fallback is silent (no user notification) | Avoids confusing non-HPC users; GPU is an optimisation not a requirement | â€” Pending |
| LightGBM SHAP disabled entirely after SIGSEGV discovery | No safe path to recovery mid-session; disabling is the only option until upstream fix | âœ“ Good |


## Current State

- v1.0 shipped on 2026-04-29 with binary classifier infrastructure, composite apply and batch flows, named composite rule persistence, and row-based + / - / ignore rule authoring.
- Milestone execution footprint: 4 phases, 9 plans, 28 planned tasks.
- Archived milestone artifacts live under .planning/milestones/.

## Next Milestone Goals

- Start v1.1 requirements definition and roadmap planning.
- Normalize verification artifacts for older phases to align with current audit schema.
- Address active technical concerns tracked in this document (thread safety, persistence coverage, and validation hardening).
---
*Last updated: 2026-04-29 after v1.0 milestone completion*
