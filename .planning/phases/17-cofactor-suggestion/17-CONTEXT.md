# Phase 17: In-QuPath Cofactor Suggestion - Context

**Gathered:** 2026-07-07
**Status:** Ready for planning

<domain>
## Phase Boundary

Add a "Suggest cofactor…" tool to the existing Normalise Features (arcsinh) workflow that estimates a good global arcsinh cofactor from the project's in-memory cell measurements. The user picks calibration features in an independent grouped/searchable picker; the tool estimates each feature's background/negative intensity level, presents a per-feature results table plus one recommended global cofactor (median of the per-feature values), and applies that single value into the existing cofactor spinner. Scope is either the open image or the whole project pooled over **all** cells.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**8 requirements are locked.** See `17-SPEC.md` for full requirements, boundaries, and acceptance criteria.

Downstream agents MUST read `17-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):**
- "Suggest cofactor…" entry point in the Normalise Features (arcsinh) workflow
- Independent grouped/searchable calibration-feature picker (reuse FeatureSelectionPane)
- Per-feature background/negative-level cofactor estimator, computed from in-memory measurements
- Per-feature results table with a value-scale summary column
- One recommended global cofactor = median of the per-feature cofactors
- Apply action that sets the existing cofactor spinner (single action, no data mutation)
- Scope selector: open image, or whole project pooled over all cells (memory-safe two-pass)
- Deterministic estimator with synthetic background-recovery tests + a raw-fluorescence range sanity check

**Out of scope (from SPEC.md):**
- Per-feature normalization / applying per-feature cofactors (arcsinh uses one shared cofactor)
- Auto-running or persisting normalization from the tool (Apply only sets the spinner)
- Suggesting a cofactor for non-cofactor transforms (sqrt)
- Changing the arcsinh formula or the FeatureNormalizer contract
- Bounded/sampled project pooling (rejected — sampling misses rare markers)
- A selectable menu of multiple estimators
- Alternative normalization transforms (CLR/z-score/min-max/reference-channel/ComBAT)
- Reading measurements from external geojson/CSV/file streams (in-memory only)
- Transformed-histogram preview / interactive refinement
- Persisting suggestions across sessions

</spec_lock>

<decisions>
## Implementation Decisions

### Estimator method
- **D-01:** **Robust-percentile background estimator.** The cofactor for each feature is a percentile of that feature's per-cell distribution near the background/signal knee, reusing `ImagePixelStats.percentileSorted` (interpolated). No model fitting — deterministic, cheap, and robust when a marker is positive only in a rare subset. (Chosen over a 2-population GMM/Otsu split and over median/MAD.)
- **D-02:** **Fixed rule for all features** — the SAME percentile is applied identically to every selected feature (not adaptive per-feature). Predictable and easy to unit-test.
- **D-03:** The **exact percentile value is NOT locked here** — the phase researcher calibrates it against the SPEC acceptance targets (raw-fluorescence-scale panel → global cofactor in the **tens**; MIBI reference ≈ 0.05) using the USER_GUIDE §4.2 heuristic, then it becomes a fixed constant. (Method family is locked; only the numeric knob is researcher-tunable, mirroring how Phase 15 D-01 left the ANN lib to research.)
- **D-04:** The estimator is a **pure-array model class** (no QuPath/JavaFX types), unit-tested on synthetic distributions with a known injected background — satisfies the SPEC deterministic-recovery test and follows the `LeidenModel`/`NeighborhoodModel` convention.

### Tool window & launch
- **D-05:** The tool is a **non-modal window** (house style, like `NeighborhoodAnalysisDialog`) with a **progress bar + Cancel**; computation runs off the JavaFX thread (UI updates via `Platform.runLater`). The Normalise Features dialog stays open; Apply writes the recommended global cofactor into its cofactor spinner.
- **D-06:** Launched from a **"Suggest…" button beside the cofactor spinner** in `NormalizationPane`, visible/enabled **only for the arcsinh transform** (mirrors the spinner's own arcsinh-only visibility).
- **D-07:** The run is **user-triggered** via a Run/Compute button — the window does NOT auto-compute on open (the whole-project default would otherwise start a long all-cells pass on every open).
- **D-08:** Reuse Phase 15's **configurable soft-ceiling confirm** (~50M pooled cells) before a whole-project run; per-phase/per-image progress and a `CancellationToken`-backed Cancel mirror the cohort-write UX (Phase 15 D-10/D-11/D-12).

### Results table & edge cases
- **D-09:** **Rich diagnostic table** — one row per selected feature: `feature | n cells | background estimate | median | p99 | suggested cofactor`. Per-feature values are **advisory/display only** (SPEC: only the global cofactor is ever applied).
- **D-10:** One recommended **global cofactor = median of the per-feature cofactors** (SPEC COF-06), shown prominently.
- **D-11:** **Dead** (all-background / near-zero variance) and **saturated** markers are **flagged in the table and excluded from the global-median** aggregation so they don't skew the recommendation — reuse the signal-gated / dead-channel exclusion idea from `PixelCohortAnalyzer`.

### Picker & scope defaults
- **D-12:** The calibration picker is a **reused `FeatureSelectionPane`** (grouped/searchable, selection **independent** of the normalize set, no hardcoded measurement name), **pre-selecting the per-marker mean-intensity features** by default (its grouping already separates markers from morphology/embeddings/other); the user adjusts.
- **D-13:** **Default scope = whole project (all cells)** in the scope selector; open image also available. Whole-project pooling reuses `CohortClusterModel.poolAllCells`' memory-safe two-pass pattern (SPEC-locked, no sampling — rare markers preserved).

### Claude's Discretion
- The exact percentile constant (pending researcher calibration per D-03); the precise "background estimate" summary statistic shown in the table (it may differ from the percentile used as the cofactor); number/column formatting; button micro-layout; and the estimator's internal home (a new pure class such as `CofactorEstimator` vs a static helper) — all planner/researcher territory, constrained by the SPEC's memory-safety/determinism and the decisions above.
- Status-line / log phrasing for progress and for the applied recommendation.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked requirements (read first)
- `.planning/phases/17-cofactor-suggestion/17-SPEC.md` — Locked requirements, boundaries, acceptance criteria. MUST read before planning.

### Prior-phase decisions & patterns
- `.planning/phases/15-all-cells-leiden-clustering/15-CONTEXT.md` — cohort all-cells UX conventions reused here: soft-ceiling confirm, per-image progress, cancellation semantics, and the memory-safe two-pass pooling principle.

### Implementation touchpoints (code)
- `src/main/java/qupath/ext/celltune/ui/NormalizationPane.java` — cofactor spinner (~line 76; arcsinh-only visibility ~line 204); the "Suggest…" button host and write-back target.
- `src/main/java/qupath/ext/celltune/model/FeatureNormalizer.java` — `arcsinh(x/cofactor)` formula (~line 132); single global cofactor getter/setter (~lines 99-107); range > 0. Reused unchanged.
- `src/main/java/qupath/ext/celltune/ui/FeatureSelectionPane.java` — grouped/searchable multi-select picker; `showAndWait()` returns the selection; `groupOf`/`discoverPrefixes` classify markers vs morphology/embeddings/other. Reused for the independent calibration picker.
- `src/main/java/qupath/ext/celltune/model/CohortClusterModel.java` — `poolAllCells` (memory-safe two-pass all-cells pooling, ~line 849) + `CancellationToken`; the whole-project scope source.
- `src/main/java/qupath/ext/celltune/model/CellFeatureExtractor.java` — in-memory per-cell measurement reads (open-image scope).
- `src/main/java/qupath/ext/celltune/model/ImagePixelStats.java` — `percentileSorted` (p1/p99, interpolated, ~line 429) reused by the percentile estimator and the table's value-scale summary.
- `src/main/java/qupath/ext/celltune/model/RobustStats.java` — median/MAD (`MAD_TO_SIGMA = 0.6745`); robust helpers for summary stats / degenerate detection.
- `src/main/java/qupath/ext/celltune/model/PixelCohortAnalyzer.java` — dead-channel exclusion / signal-gating pattern to mirror for D-11.
- `src/main/java/qupath/ext/celltune/ui/NeighborhoodAnalysisDialog.java` — non-modal window + progress + Cancel pattern (D-05).
- `src/main/java/qupath/ext/celltune/ui/ClusterAssignmentPane.java` — per-row results-grid pattern (D-09).
- `src/test/java/qupath/ext/celltune/model/` — pure-array synthetic-cloud JUnit 5 test convention the estimator must follow.

### External / domain references
- `USER_GUIDE.md` §4.2 (Normalise features) — arcsinh cofactor guidance (background/signal-boundary heuristic; raw fluorescence → tens ~25-50; MIBI 0.05). The acceptance target for calibrating the fixed percentile (D-03).
- https://github.com/SchapiroLabor/norm_methods — normalization-methods catalog; the "background identification" family grounds the estimand (also added to the README references).

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `FeatureSelectionPane` (independent instantiation, `showAndWait`, grouped/searchable) → the calibration picker (D-12).
- `CohortClusterModel.poolAllCells` + `CancellationToken` → whole-project all-cells scope, memory-safe (D-13).
- `CellFeatureExtractor` → open-image per-cell measurement reads.
- `ImagePixelStats.percentileSorted` + `RobustStats` (median/MAD) → the estimator (D-01) and the value-scale summary + degenerate detection (D-11).
- `NeighborhoodAnalysisDialog` (non-modal + progress + cancel) and `ClusterAssignmentPane` (per-row grid) → UI patterns (D-05/D-09).
- `NormalizationPane` cofactor spinner + arcsinh-only visibility → launch host + apply target; `FeatureNormalizer` reused unchanged.

### Established Patterns
- Pure-array, JavaFX-free model classes unit-tested on synthetic data (`LeidenModel`/`NeighborhoodModel`) → the estimator (e.g. `CofactorEstimator`) must follow this for the SPEC deterministic-recovery test.
- Background compute off the FX thread; batch UI updates via `Platform.runLater`.
- Two-pass memory-safe cohort streaming (never hold all image hierarchies) → whole-project scope.
- Soft-ceiling confirm + per-image progress + `CancellationToken` for long cohort ops (Phase 15).
- Non-destructive: the tool never mutates measurements or runs normalization — Apply only sets the spinner value (SPEC).

### Integration Points
- New "Suggest…" button in `NormalizationPane` beside the cofactor spinner (arcsinh-only) opens the non-modal suggest window. **Modality nuance:** if the Normalise Features dialog is `APPLICATION_MODAL`, the suggest window must be an owned/child window (or ownership made explicit) so its modality doesn't block interaction — planner to verify.
- Suggest window: `FeatureSelectionPane`-backed calibration picker + scope selector (open image / whole project) + Run button → estimator over the chosen scope → results table + global cofactor → Apply writes into the cofactor spinner.
- Whole-project scope calls `poolAllCells` (all cells, two-pass); open-image reads via `CellFeatureExtractor` from the live `ImageData`.

</code_context>

<specifics>
## Specific Ideas

- Calibration target for the fixed percentile: raw-fluorescence-scale panel → global cofactor in the tens (~25-50); MIBI → ~0.05 (USER_GUIDE §4.2).
- Results table is "rich diagnostic": `feature | n cells | background | median | p99 | suggested cofactor`.
- Global cofactor = median of the per-feature values; dead/saturated markers flagged + excluded.
- Non-modal window mirrors `NeighborhoodAnalysisDialog`; Run-triggered, not auto-on-open.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. (Adjacent nice-to-haves are already recorded as OUT of scope in `17-SPEC.md`: transformed-histogram preview, a selectable estimator menu, per-feature apply, and alternative normalization transforms.)

</deferred>

---

*Phase: 17-cofactor-suggestion*
*Context gathered: 2026-07-07*
