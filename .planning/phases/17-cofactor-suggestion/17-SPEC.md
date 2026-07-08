# Phase 17: In-QuPath Cofactor Suggestion — Specification

**Created:** 2026-07-07
**Ambiguity score:** 0.16 (gate: ≤ 0.20)
**Requirements:** 8 locked

## Goal

Add a "Suggest cofactor…" tool to the Normalise Features (arcsinh) workflow that estimates a good global arcsinh cofactor from the project's in-memory cell measurements — the user picks calibration features in an independent grouped/searchable picker, the tool estimates each feature's background/negative intensity level, presents a per-feature table plus one recommended global cofactor (median of the per-feature values), and applies that single value into the existing cofactor spinner. Scope is either the open image or the whole project pooled over **all** cells.

## Background

The **Normalise Features** workflow exists today ([NormalizationPane.java](src/main/java/qupath/ext/celltune/ui/NormalizationPane.java), launched from *Extensions → CellTune Classifier → Normalise Features* via [MenuItemFactory.java:102](src/main/java/qupath/ext/celltune/ui/MenuItemFactory.java#L102), handler [CellTuneExtension.java:1697](src/main/java/qupath/ext/celltune/CellTuneExtension.java#L1697)). It applies `arcsinh(x / cofactor)` ([FeatureNormalizer.java:132](src/main/java/qupath/ext/celltune/model/FeatureNormalizer.java#L132)) with a **single global cofactor** entered **manually** via a spinner (range 0.01–10 000, step 1.0, default 1.0; visible only for the arcsinh transform — [NormalizationPane.java:76](src/main/java/qupath/ext/celltune/ui/NormalizationPane.java#L76), [204](src/main/java/qupath/ext/celltune/ui/NormalizationPane.java#L204)).

**No code estimates a cofactor today** — the user guesses it, guided only by prose in [USER_GUIDE.md §4.2](USER_GUIDE.md) ("pick the value near the background/signal boundary — where background collapses but the positive population stays resolved"; raw fluorescence → tens ~25–50; MIBI → 0.05). This phase builds the estimator and its UI.

Reusable machinery already exists: [FeatureSelectionPane.java](src/main/java/qupath/ext/celltune/ui/FeatureSelectionPane.java) (grouped/searchable multi-select picker, independently instantiable, `showAndWait()` returns the selection), [CohortClusterModel.poolAllCells](src/main/java/qupath/ext/celltune/model/CohortClusterModel.java#L849) (memory-safe two-pass all-cells project pooling), [CellFeatureExtractor](src/main/java/qupath/ext/celltune/model/CellFeatureExtractor.java) (in-memory per-cell measurement reads), [RobustStats.java](src/main/java/qupath/ext/celltune/model/RobustStats.java) (median/MAD/robust-z, `MAD_TO_SIGMA = 0.6745`) and `ImagePixelStats.percentileSorted` (p1/p99 with interpolation). UI apply-a-result-back patterns exist in [ClusterAssignmentPane.java](src/main/java/qupath/ext/celltune/ui/ClusterAssignmentPane.java) and [NeighborhoodAnalysisDialog.java](src/main/java/qupath/ext/celltune/ui/NeighborhoodAnalysisDialog.java).

**Method grounding:** background/negative-level estimation is a recognized normalization family for imaging marker intensities — the Schapiro Lab `norm_methods` catalog (https://github.com/SchapiroLabor/norm_methods) lists manual and semi-automated (ilastik) *background identification* alongside arcsinh for IMC/MIBI/CODEX. That catalog provides no single canonical arcsinh-cofactor formula, so Phase 17 defines its own **automated** background estimator within this family (the exact statistic is a plan-phase decision). The catalog's other entries (CLR, z-score, min-max, reference-channel, ComBAT) are alternative *transforms*, not cofactor estimators, and are out of scope here.

## Requirements

1. **Launch control**: A "Suggest cofactor…" control is reachable from the Normalise Features arcsinh workflow.
   - Current: `NormalizationPane` has a manual cofactor spinner; no suggestion entry point exists
   - Target: A "Suggest cofactor…" button/control in the Normalise Features UI (shown when the arcsinh transform is selected) opens the cofactor-suggestion tool
   - Acceptance: With arcsinh selected, a "Suggest cofactor…" control is present and opens the tool; it is absent/disabled for a transform with no cofactor (sqrt)

2. **Independent calibration picker**: The user selects calibration features via a grouped, searchable picker that is independent of the normalize selection.
   - Current: `FeatureSelectionPane` provides grouped/searchable multi-select and is independently instantiable; the normalize workflow keeps its own separate feature selection
   - Target: The tool opens its own picker (FeatureSelectionPane-style); its selection neither reads from nor writes to the normalize feature set, and no measurement name is hardcoded
   - Acceptance: Changing the calibration selection leaves the normalize feature selection unchanged (and vice versa); the picker exposes the same groups (markers/morphology/neighbors/embeddings/other) and supports search

3. **In-memory computation only**: Suggestions are computed from in-memory cell measurements with no external geojson/file streaming.
   - Current: `CellFeatureExtractor`/`CohortClusterModel` read measurements from live in-memory `PathObject`s; there is no measurement file streaming
   - Target: The estimator reads per-cell values via the in-memory hierarchy (open image) or by reading each project image's `ImageData` into memory; it never parses external geojson or streams measurement files
   - Acceptance: The suggestion code path uses `PathObject.getMeasurementList()` / `ImageData` reads only; no geojson/CSV measurement parsing is invoked

4. **Per-feature background-level estimator**: For each selected feature, derive a suggested cofactor that tracks that feature's background/negative intensity level.
   - Current: No cofactor estimation exists; `RobustStats` (median/MAD/robust-z) and percentile helpers exist but are unused for this
   - Target: For each selected feature, compute a suggested cofactor equal to a robust estimate of the background/negative-population intensity level (the value near the background/signal boundary), so `arcsinh(x/cofactor)` collapses background while preserving the positive population. This is a **single automated** estimator in the recognized "background identification" family (see Background / `norm_methods`); the exact statistic (e.g. a robust background percentile or a negative-vs-positive split) is a plan-phase decision
   - Acceptance: On synthetic per-cell data with a known background level and a separated positive population, the per-feature estimate lands within a defined tolerance (target: within a factor of ~2) of the injected background scale, deterministically under a fixed seed

5. **Per-feature results table**: The tool presents a per-feature results table (feature → value-scale summary → suggested cofactor).
   - Current: No such table; per-row result grids exist as a pattern in `ClusterAssignmentPane`
   - Target: One row per selected feature showing the feature name, a value-scale summary (e.g. n cells and a background/median plus a high percentile such as p99), and its suggested per-feature cofactor
   - Acceptance: For N selected features the table shows N rows, each with a feature name, ≥1 value-scale summary statistic, and a suggested per-feature cofactor (display/advisory only)

6. **Single recommended global cofactor (median)**: The tool reports one recommended global cofactor aggregated as the median of the per-feature cofactors.
   - Current: No aggregation exists
   - Target: The tool computes and prominently displays one recommended global cofactor equal to the median of the selected features' per-feature cofactors
   - Acceptance: For a known set of per-feature cofactors, the reported global equals their median; changing the calibration selection recomputes it

7. **Apply into existing spinner (single action, no mutation)**: Applying the recommendation sets the existing Normalise Features cofactor spinner in one action, without mutating data.
   - Current: The cofactor spinner is set manually; nothing writes to it programmatically
   - Target: An Apply action writes the recommended global cofactor into `NormalizationPane`'s cofactor spinner (respecting its 0.01–10 000 range) and returns to the normalize workflow; it does NOT run normalization, alter measurements, or persist state on its own
   - Acceptance: After Apply, the cofactor spinner shows the recommended value; no measurement/normalizer state changes until the user separately confirms normalization; per-feature values are never applied separately

8. **Scope selector (open image vs whole project, all cells)**: The user chooses estimation scope — the open image, or the whole project pooled over every cell.
   - Current: `CohortClusterModel.poolAllCells` provides memory-safe all-cells project pooling; `sample()` provides bounded pooling; neither is wired to cofactor estimation
   - Target: A scope selector offers "Open image" (live `ImageData`) and "Whole project (all cells)". Whole-project pools **every** cell across the selected images (reusing the memory-safe two-pass `poolAllCells` pattern — **not** a bounded sample), so rare markers/populations are not missed
   - Acceptance: Whole-project scope reads all cells from all selected images (no per-image sampling cap); on a project where a marker is positive only in a rare subset, those cells are present in that marker's distribution; open-image scope uses only the open image's cells

## Boundaries

**In scope:**
- A "Suggest cofactor…" entry point in the Normalise Features (arcsinh) workflow
- An independent grouped/searchable calibration-feature picker (reusing FeatureSelectionPane)
- A per-feature background/negative-level cofactor estimator, computed from in-memory measurements
- A per-feature results table with a value-scale summary column
- A single recommended global cofactor = median of the per-feature cofactors
- An Apply action that sets the existing cofactor spinner (single action, no data mutation)
- A scope selector: open image, or whole project pooled over all cells (memory-safe two-pass)
- A deterministic estimator with synthetic background-recovery tests and a raw-fluorescence range sanity check

**Out of scope:**
- Applying per-feature cofactors separately / per-feature normalization — arcsinh uses one shared cofactor; per-feature values are advisory only
- Auto-running or persisting normalization from the suggest tool — Apply only sets the spinner; the user still confirms normalization as today
- Suggesting a cofactor for non-cofactor transforms (sqrt) — there is no cofactor to estimate
- Changing the arcsinh formula or the `FeatureNormalizer` contract — the existing normalizer is reused unchanged
- Bounded/sampled project pooling for the estimate — explicitly rejected: sampling can miss rare markers/populations, so all cells are required
- A selectable menu of multiple cofactor estimators — Phase 17 ships one automated background-level estimator; comparing estimator variants is deferred
- Alternative normalization transforms from the `norm_methods` catalog (CLR, z-score, min-max, reference-channel, ComBAT) — Phase 17 only suggests the arcsinh cofactor for the existing workflow; other transforms are a separate future milestone
- Reading measurements from external geojson/CSV/file streams — in-memory only (COF-03)
- A transformed-histogram preview or interactive refinement UI — a possible future nice-to-have, not required this phase
- Persisting suggestions across sessions — the suggestion is transient advisory output

## Constraints

- **In-memory only**: no external geojson/CSV/file streaming of measurements (COF-03).
- **Memory-safe at cohort scale**: whole-project scope must handle tens of millions of pooled cells without holding redundant copies — reuse the established two-pass `poolAllCells` memory-management pattern (pool/record, release hierarchies, re-read as needed).
- **Deterministic + robust**: the estimator must be deterministic under a fixed seed (for reproducible tests) and NaN/degenerate-robust, reusing `RobustStats` conventions (median/MAD, `0.6745`) rather than ad-hoc statistics.
- **Valid cofactor range**: the recommended value must stay within the existing spinner's valid range (0.01–10 000, strictly > 0).
- **QuPath 0.7 + UI-thread safety**: heavy computation runs off the JavaFX thread; UI updates go through `Platform.runLater`. Reuse `FeatureSelectionPane`, `RobustStats`, and `CohortClusterModel` patterns; add no new heavy dependencies.

## Acceptance Criteria

- [ ] A "Suggest cofactor…" control opens the tool from the Normalise Features (arcsinh) workflow; it is absent/disabled for the sqrt transform.
- [ ] The calibration picker is grouped + searchable and its selection is independent of the normalize feature selection (no hardcoded measurement name).
- [ ] Suggestions are computed only from in-memory cell measurements (no geojson/file streaming).
- [ ] Each selected feature gets a per-feature cofactor tracking its background/negative level; on synthetic data with a known background it is recovered within the defined tolerance, deterministically under a fixed seed.
- [ ] The results table shows one row per selected feature with a value-scale summary and the suggested per-feature cofactor (advisory).
- [ ] One recommended global cofactor is displayed and equals the median of the per-feature cofactors.
- [ ] Apply sets the Normalise Features cofactor spinner to the recommended global in one action and does not run normalization or mutate measurements.
- [ ] The scope selector offers open-image and whole-project (all cells) modes; whole-project pools every cell (no sampling) memory-safely, so rare markers are represented.
- [ ] On a raw-fluorescence-scale panel, the recommended global cofactor lands in the tens (not the platform default of 1), consistent with the MIBI/COMET cohort assessments.

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                        |
|--------------------|-------|------|--------|--------------------------------------------------------------|
| Goal Clarity       | 0.88  | 0.75 | ✓      | "Suggest cofactor" tool, background-level estimand, median global |
| Boundary Clarity   | 0.85  | 0.70 | ✓      | Apply=set spinner only; per-feature advisory; arcsinh-only; other transforms excluded |
| Constraint Clarity | 0.82  | 0.65 | ✓      | All-cells memory-safe pooling; in-memory; deterministic/robust; background-family estimand |
| Acceptance Criteria| 0.80  | 0.70 | ✓      | Synthetic background-recovery + raw-fluorescence range (tens) |
| **Ambiguity**      | 0.16  | ≤0.20| ✓      |                                                              |

Status: ✓ = met minimum, ⚠ = below minimum (planner treats as assumption)

## Interview Log

| Round | Perspective     | Question summary                          | Decision locked                                                     |
|-------|-----------------|-------------------------------------------|--------------------------------------------------------------------|
| 1     | Researcher      | What quantity does the cofactor track?    | Background/negative intensity level (near the background/signal boundary) |
| 1     | Researcher      | How to aggregate per-feature → global?    | Median of the per-feature cofactors                                |
| 1     | Researcher      | How to pool cells for project scope?      | (Initially bounded sample — later revised, see below)              |
| 2     | Boundary Keeper | What does Apply do?                        | Sets the existing cofactor spinner only; no data mutation, no auto-run |
| 2     | Boundary Keeper | How are per-feature cofactors used?        | Advisory/QC only; a single global cofactor is applied              |
| 2     | Failure Analyst | Falsifiable check for the estimator?       | Synthetic known-background recovery within tolerance + raw-fluorescence range (tens) |
| —     | User correction | Sampling can miss rare markers             | Whole-project scope must pool ALL cells (memory-safe two-pass), not a bounded sample |
| —     | User steer (norm_methods) | Should the estimator adopt catalog methods? | Single automated background-level estimator (recognized "background identification" family); other transforms out of scope; exact formula → plan-phase |

---

*Phase: 17-cofactor-suggestion*
*Spec created: 2026-07-07*
*Next step: /gsd-discuss-phase 17 — implementation decisions (exact background estimator, results-table layout, scope UI, wiring into NormalizationPane)*
