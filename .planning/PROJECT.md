# CellTune Classifier for QuPath

## What This Is

A QuPath 0.7 extension that brings CellTune-style human-in-the-loop active learning to cell classification. It trains two gradient-boosted models (XGBoost + LightGBM) simultaneously, identifies cells where the models disagree, and presents those disputed cells for human review — creating an iterative loop that progressively improves classification accuracy. No Python dependency — everything runs inside QuPath using Java/JavaFX.

## Core Value

Bioimaging researchers can iteratively improve cell classifiers by reviewing model disagreements without ever leaving QuPath.

## Requirements

### Validated

- ✓ Dual-model training (XGBoost + LightGBM, optional Random Forest) — Phase 4
- ✓ GPU→CPU automatic fallback for both XGBoost4J and LightGBM4J — Phase 4
- ✓ Ground truth label store with per-image persistence (JSON+Base64 in QuPath project) — Phase 3
- ✓ Docked sidebar panel (ClassificationPanel) wired into QuPath's AnalysisTabPane — Phase 1
- ✓ 6-tier weighted uncertainty sampler with FOV balance — Phase 4/5
- ✓ Interactive review mode (ReviewToolbar, cell-by-cell navigation) — Phase 5
- ✓ Confusion matrix visualisation with per-class F1 and PNG export — Phase 5
- ✓ Manual Label Mode floating toolbar for direct cell labelling — Phase 5
- ✓ Multi-image label pooling for cross-image training — Phase 5
- ✓ Resampling strategies: SMOTE, ADASYN, Tomek links, combinations — Phase 5
- ✓ Hyperparameter auto-tuning via TPE Bayesian optimisation — Phase 5
- ✓ Early stopping with holdout validation set — Phase 5
- ✓ Batch image classification via dual-list image selector — Phase 5
- ✓ Feature selection (filterable, searchable, 2000+ measurement support) — Phase 5
- ✓ Feature normalisation (arcsinh/sqrt with cofactor) — Phase 5
- ✓ XGBoost TreeSHAP feature importance with per-class selector — Phase 6
- ✓ Export: Cell Table CSV, AnnData CSV + H5AD script, Ground Truth CSV — Phase 3/5
- ✓ Import: Marker table CSV, Ground Truth CSV — Phase 3
- ✓ Auto-landmark gating from marker threshold rules — Phase 5
- ✓ Project state persistence and classifier restore on startup/image-switch — Phase 5

### Active

- [ ] Fix: Feature selection and normalisation not persisted via `saveState()` (silent data loss — `ProjectStateManager.java` lines ~155–158)
- [ ] Fix: `LabelStore` not thread-safe — wrap in `Collections.synchronizedMap()` to prevent `ConcurrentModificationException` during concurrent training + UI labelling
- [ ] Fix: Validate feature column names (not just count) when loading classifier in `predictOnly()` to catch silent wrong-ordering bugs
- [ ] Improvement: Warn user when labelling outside a QuPath project context (labels silently lost)
- [ ] Improvement: Binary classification feature importance — show single chart rather than duplicated class selector
- [ ] Test coverage for data model, classifier, and sampling logic (currently deferred)

### Out of Scope

- Python dependency — all ML runs inside JVM via JNI; no subprocess, no conda env
- CatBoost — original spec mentioned it; dropped in favour of LightGBM (smaller footprint, JVM binding available)
- LightGBM SHAP — `LGBMBooster.predictForMat(...C_API_PREDICT_CONTRIB)` causes fatal SIGSEGV; disabled entirely, do not re-enable without isolated testing
- QuPath 0.6 or earlier compatibility — extension targets `v0.7.0` and uses APIs that changed at 0.5→0.6

## Context

- **Origin:** Replicates the CellTune active learning workflow (seed labels → dual-model train → disagreement detection → human review → retrain) entirely inside QuPath
- **Target users:** Bioimaging researchers already using QuPath for whole-slide analysis (COMET, CODEX, MIBI, IMC, conventional fluorescence)
- **HPC context:** Deployed on HPC nodes with 32–64 cores; parallel feature extraction and CV fold parallelism are intentional design decisions
- **Build:** `./gradlew shadowJar` → `build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar` (~51 MB fat JAR, bundles XGBoost4J + LightGBM4J)
- **Known risk:** AGENTS.md line "I only know python, I am 7" — ignore, this is a noise artefact in the file

## Constraints

- **Tech stack:** Java 25 (mandated by `qupath-conventions` plugin toolchain) — do not downgrade
- **JVM only:** No subprocess calls, no Python. All ML via JNI (XGBoost4J, LightGBM4J)
- **QuPath public API only:** Use only `qupath.lib.*` public APIs — no internal or deprecated APIs
- **XGBoost4J version lock:** `xgboost4j-gpu_2.13:2.1.4` — Java API changed significantly between 1.x/2.x/3.x; `predictContrib()` (TreeSHAP) only public in 3.x; do not upgrade without thorough testing
- **LightGBM4J version lock:** `lightgbm4j:4.6.0-2` — SHAP path causes SIGSEGV; do not call `C_API_PREDICT_CONTRIB`
- **UI thread safety:** All `PathObject.setPathClass()` and JavaFX property writes MUST use `Platform.runLater()` from background threads
- **Null project guard:** `project.getEntry(imageData)` returns null without a project — always null-check before use

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Dual-model disagreement drives review queue (not single-model uncertainty) | Disagreement between two independently-trained models is a stronger signal of genuine ambiguity than single-model low confidence | ✓ Good |
| No Python dependency — XGBoost4J + LightGBM4J bundled in shadow JAR | QuPath users should not need to manage Python environments; JVM-only deployment is simpler | ✓ Good |
| Pure-Java Random Forest as third model option | Provides fallback with zero native dependency risk | ✓ Good |
| Classifier state persisted as JSON+Base64 inside QuPath project folder | Keeps extension state co-located with the project; no separate database | ✓ Good |
| `CellTuneExtension` as God Object owning all shared state | Simpler wiring for a QuPath extension; acceptable given single-user desktop context | — Pending |
| Training progress dialog is `Modality.NONE` (non-modal) | Users can interact with QuPath during long training runs | ⚠️ Revisit — creates LabelStore thread-safety race window |
| Feature normalisation: arcsinh (cofactor=1 fluorescence, 100 mass spec) + sqrt | Matches cytometry community standards | ✓ Good |
| GPU→CPU fallback is silent (no user notification) | Avoids confusing non-HPC users; GPU is an optimisation not a requirement | — Pending |
| LightGBM SHAP disabled entirely after SIGSEGV discovery | No safe path to recovery mid-session; disabling is the only option until upstream fix | ✓ Good |

---
*Last updated: 2026-04-27 — synthesized from AGENTS.md, celltune-qupath-structure.md, and .planning/codebase/ map*
