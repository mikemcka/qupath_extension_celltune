# Documentation Ideas

## Documentation Strategy

Treat documentation like a product feature with clear goals, ownership, and update cadence.

## Audience-First Structure

- Primary audience: scientists running training and prediction in QuPath.
- Secondary audience: developers building, extending, or debugging the extension.
- Tertiary audience: collaborators reviewing exported outputs and summary metrics.

## Three-Layer Doc Model

- Layer 1: Quick start and key feature usage in `README.md`.
- Layer 2: Build and environment setup in `CLAUDE.md` (Build & test section).
- Layer 3: Concept deep-dives (for example, anomaly score and rare enrichment) in dedicated sections, then split into separate pages as complexity grows.

## Organize by User Tasks

Prefer task-based sections over code-package descriptions.

Suggested headings:

- Train and predict a project.
- Interpret Project Prediction Summary.
- Export and review results.
- Troubleshoot missing predictions.

## Metric Documentation Pattern

For each metric, include:

- Definition: what it means.
- Formula: how it is computed.
- Interpretation: how to read high/low values.
- Caveat: when it can be misleading.

This pattern is already a good fit for anomaly score and rare enrichment.

## Add a Troubleshooting Table

Create a compact symptom-to-fix table.

Example row:

- Symptom: Predicted = 0 for an image.
- Likely causes: image not predicted, no saved Pred_ALL, wrong project/image context.
- Fix: rerun prediction for image and reopen summary.

## Keep Docs in Sync with Code

For every UI behavior change:

- Update `README.md`.
- Refresh at least one screenshot if visuals changed.
- Add one release note line.

## Screenshot-Backed Examples

Add at least three examples:

- Normal summary state.
- Flagged outlier image state.
- Rare-enrichment highlighted state.

## Documentation Quality Checklist

Before merge, verify:

- A new user can run end-to-end from docs alone.
- Metric definitions are precise and unambiguous.
- Thresholds are explicitly stated.
- Common failure modes are covered.
- Menu paths and labels match the current UI.
