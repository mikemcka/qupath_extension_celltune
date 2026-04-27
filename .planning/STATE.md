# Project State

**Reconstructed:** 2026-04-27 (no prior STATE.md found)

---

## Project Reference

**What This Is:** A QuPath 0.7 extension for human-in-the-loop cell classification using dual-model active learning (XGBoost + LightGBM + optional Random Forest). Pure Java/JavaFX — no Python dependency.

**Core Value:** Bioimaging researchers can iteratively improve cell classifiers by reviewing model disagreements without leaving QuPath.

**Current Focus:** GSD project setup. Codebase has been mapped; PROJECT.md and ROADMAP.md not yet created.

---

## Current Position

- **Phase:** Phase 1 of 2 — Binary Classifier Infrastructure
- **Plan:** None yet — ready to plan
- **Status:** ROADMAP.md created. Phase 1 ready to plan.

**Progress:** [░░░░░░░░░░] 0% — Milestone 1 not started

---

## Key Codebase Facts (from codebase map)

- Entry point: `CellTuneExtension.java` — registers menus, docks sidebar, manages state
- ML: XGBoost4J 3.2.0 + LightGBM4J 4.6.0-2, both with GPU→CPU fallback
- Build: `./gradlew shadowJar` → fat JAR at `build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar`

---

## Known Concerns (from CONCERNS.md)

| # | Severity | Issue |
|---|----------|-------|
| 1 | HIGH | LightGBM SHAP causes fatal SIGSEGV — disabled entirely |
| 2 | MEDIUM | LabelStore not thread-safe (ConcurrentModificationException risk) |
| 3 | MEDIUM | Feature selection/normalization not persisted in `saveState()` (silent data loss) |
| 4 | MEDIUM | Feature column ordering fragility in native scoring path |
| 5 | MEDIUM | Multi-image pooling edge cases (class mismatch, stale IDs) |
| 6 | MEDIUM | QuPath API compatibility risk (0.7 → 0.8+) |
| 7 | LOW | Binary classification SHAP displays identical bars for both classes |
| 8 | LOW | Project-less image labelling silently loses labels |

---

## Pending Todos

None captured yet.

---

## Session Continuity

Last session: 2026-04-27
Stopped at: Codebase mapped. Session resumed — awaiting project/roadmap creation.
Resume file: none
