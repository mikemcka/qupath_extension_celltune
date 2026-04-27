---
title: Binary Composite Classification — Design Decisions
date: 2026-04-27
context: Exploration session before Phase 1 planning
---

# Binary Composite Classification Design

## Problem

Current CellTune only supports multi-class classification (e.g. "is this a B cell or a CD4 T cell?").
Many users instead want a series of **per-marker binary decisions** that can be combined:
e.g. `CD4+:CD3+:CD20-` rather than a named cell type label.

Manual seeding is required (not auto-threshold from intensity) because of:
- Spillover between channels in multiplexed imaging (COMET, CODEX, MIBI, IMC)
- Variable marker intensity between samples that makes fixed thresholds unreliable

---

## Locked Design Decisions

### 1. Independent classifiers per marker

Each binary marker (`CD4`, `CD3`, `CD20`, ...) gets its own:
- `ClassifierState` saved at `<project>/celltune/binary/<MarkerName>.json`
- `LabelStore` with exactly two classes: `<MarkerName>_pos` and `<MarkerName>_neg`
- Full dual-model training + review cycle (reuses existing system unchanged)

Classifiers can be trained, updated, and saved independently. Updating one marker
does not affect any other.

### 2. Seeding: manual only

Users click cells and assign `CD4_pos` or `CD4_neg` via the existing Manual Label Toolbar
or review mode. No auto-threshold seeding. Rationale: auto-threshold is too sensitive to
spillover and inter-sample intensity variation.

### 3. Binary training loop = existing loop with 2 classes

`DualModelClassifier`, `LabelStore`, `UncertaintySampler`, `ReviewController`,
`ClassifierState`, `ProjectStateManager` — all reused as-is. No changes needed to
core ML or review infrastructure.

### 4. Composite combination: hard threshold + AND/OR logic

When combining:
1. Each binary classifier assigns `pos` or `neg` using a **hard 50% threshold** on the
   averaged model probability
2. User defines a composite rule as a list of `(MarkerName, polarity)` pairs:
   e.g. `[(CD4, pos), (CD3, pos), (CD20, neg)]`
3. A cell matches the rule if **all** conditions are satisfied (AND logic)
4. The composite label is written as a QuPath `PathClass` on the cell:
   e.g. `CD4+:CD3+:CD20-`

Rationale: by the time users combine classifiers they should have trained accurate
classifiers with quality labels. Uncertainty at combination time is acceptable.
No confidence-gated or probability-product approaches needed.

### 5. Output format

Composite result written as `PathClass` on each `PathDetectionObject` — same as
current multi-class output. Users see coloured cell populations in the QuPath viewer.
Compatible with existing Cell Table, AnnData, and Ground Truth exports.

---

## New Components Required

| Component | Purpose |
|-----------|---------|
| `BinaryClassifierRegistry` | Tracks all trained binary classifiers in the project; maps marker name → state file path |
| `CompositeClassificationRule` | Stores a list of `(markerName, polarity)` pairs defining one composite population |
| `CompositeClassifier` | Loads N binary `ClassifierState`s, applies 50% threshold to each, ANDs results, writes `PathClass` |
| Binary marker management UI | Panel to create/rename/delete binary classifiers and launch their training session |
| Composite builder dialog | UI to define composite rules from trained markers and apply them |

---

## Reused Components (no changes needed)

- `DualModelClassifier` — used per binary classifier
- `LabelStore` — used per binary classifier with 2 classes
- `ClassifierState` / `ProjectStateManager` — save/load per marker
- `ReviewController` + `ReviewToolbar` — review loop per binary classifier
- `UncertaintySampler` — uncertainty sampling per binary classifier
- `CellTableExporter`, `AnnDataExporter` — composite `PathClass` exports automatically

---

## Open Questions

- Should composite rules be saveable/nameable (e.g. "CD4 T cell" = `CD4+:CD3+:CD20-`)?
  Likely yes — captures institutional knowledge about cell type definitions.
- Should users be able to apply composites to batch images (as with current batch classification)?
  Yes — but all constituent binary classifiers must be trained first.
- Multi-image pooling for binary classifiers: inherit from existing pooling or separate setting?
