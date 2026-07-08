# Phase 17: In-QuPath Cofactor Suggestion - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-07
**Phase:** 17-cofactor-suggestion
**Areas discussed:** Estimator method, Tool window & launch, Results table & edge cases, Picker & scope defaults

---

## Estimator method

| Option | Description | Selected |
|--------|-------------|----------|
| Robust percentile | Percentile of the per-cell distribution near the background/signal knee, reusing `percentileSorted`; deterministic, cheap, robust to rare-positive markers | ✓ |
| 2-population split (GMM/Otsu) | Fit negative-vs-positive per feature; cofactor = negative population's upper edge; more principled but heavier | |
| Median/MAD background | median (+k·MAD) via RobustStats; simplest but median ≈ background only when most cells are negative | |
| Researcher benchmarks | Don't pre-commit; researcher compares methods on the data | |

**User's choice:** Robust percentile.

| Option | Description | Selected |
|--------|-------------|----------|
| Adaptive per feature | Each feature derives its own background boundary | |
| Fixed rule for all | Same percentile/threshold applied to every selected feature | ✓ |
| You decide / researcher | Leave fixed-vs-adaptive to research + planning | |

**User's choice:** Fixed rule for all.
**Notes:** Exact percentile value left for the researcher to calibrate against SPEC acceptance targets (raw fluorescence → tens; MIBI ≈ 0.05).

---

## Tool window & launch

| Option | Description | Selected |
|--------|-------------|----------|
| Non-modal window + progress/cancel | Own non-modal window (house style), compute off FX thread, Normalise dialog stays open, Apply writes to spinner | ✓ |
| Modal sub-dialog | Blocks the Normalise dialog until Apply/Cancel; freezes parent while computing | |

**User's choice:** Non-modal window + progress/cancel.

| Option | Description | Selected |
|--------|-------------|----------|
| Next to the Cofactor spinner | "Suggest…" button beside the cofactor field; arcsinh-only | ✓ |
| In the dialog button bar | Button in the bottom OK/Cancel row | |
| You decide | Leave placement to planning | |

**User's choice:** Next to the Cofactor spinner.
**Notes:** Soft-ceiling confirm + per-image progress + CancellationToken carried forward from Phase 15 by convention (not re-asked). Run is user-triggered, not auto-on-open.

---

## Results table & edge cases

| Option | Description | Selected |
|--------|-------------|----------|
| Rich diagnostic | feature \| n cells \| background \| median \| p99 \| suggested cofactor | ✓ |
| Minimal | feature \| n cells \| suggested cofactor | |
| You decide | Leave columns to planning (SPEC minimum only) | |

**User's choice:** Rich diagnostic.

| Option | Description | Selected |
|--------|-------------|----------|
| Flag + exclude from global | Dead/saturated markers flagged and excluded from the global median (signal-gating like PixelCohortAnalyzer) | ✓ |
| Flag but include | Warning flag but still counted in the median | |
| No special handling | Treat all selected features equally | |

**User's choice:** Flag + exclude from global.

---

## Picker & scope defaults

| Option | Description | Selected |
|--------|-------------|----------|
| Pre-select marker intensities | Default-select per-marker mean-intensity features; user adjusts | ✓ |
| Start empty | No pre-selection | |
| You decide | Leave the default heuristic to planning | |

**User's choice:** Pre-select marker intensities.

| Option | Description | Selected |
|--------|-------------|----------|
| Open image | Fast, responsive default; switch to whole-project for final calibration | |
| Whole project (all cells) | Most representative (rare markers included); every open triggers the full all-cells pass with soft-ceiling confirm | ✓ |

**User's choice:** Whole project (all cells).
**Notes:** Because whole-project is the default and is expensive, the run stays user-triggered (Run button), not auto-on-open.

---

## Claude's Discretion

- Exact percentile constant (pending researcher calibration), the "background estimate" summary statistic shown in the table, column/number formatting, button micro-layout, and the estimator's internal home (new pure class vs static helper).
- Status-line / log phrasing for progress and the applied recommendation.

## Deferred Ideas

None — discussion stayed within phase scope. Adjacent nice-to-haves (transformed-histogram preview, selectable estimator menu, per-feature apply, alternative transforms) are already recorded as out of scope in 17-SPEC.md.
