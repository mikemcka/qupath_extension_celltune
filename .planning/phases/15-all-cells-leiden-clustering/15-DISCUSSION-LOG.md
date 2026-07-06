# Phase 15: All-Cells Leiden Clustering (True-Scanpy) - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-06
**Phase:** 15-all-cells-leiden-clustering
**Areas discussed:** HNSW library & fallback, Cohort mode UI, Recall-gate mechanics, Batch UX (ceiling/progress/cancel)

---

## HNSW library & fallback

### Primary ANN approach

| Option | Description | Selected |
|--------|-------------|----------|
| jelmerk primary + in-repo fallback | Commit to jelmerk/hnswlib-core; in-repo fallback if unsuitable | |
| Researcher evaluates, then decide | Compare candidates (jelmerk, Lucene HNSW, others) on maintenance/licence/API/perf; recommend before locking | ✓ |
| In-repo NN-descent only | Implement NN-descent in pure-array LeidenModel; no third-party dep | |

**User's choice:** Researcher evaluates, then decide.
**Notes:** In-repo NN-descent remains the fallback. Resolves the SPEC's flagged planning blocker (confirm a maintained artifact exists) by handing it to the phase researcher.

### Distance metric

| Option | Description | Selected |
|--------|-------------|----------|
| Euclidean (match featureKnn) | Euclidean on z-scored markers, identical to exact featureKnn squaredDistance | ✓ |
| Cosine | Cosine similarity; would require exact baseline to switch too | |

**User's choice:** Euclidean.
**Notes:** Keeps the recall gate a like-for-like comparison and downstream Leiden unchanged.

---

## Cohort mode UI

### Mode control

| Option | Description | Selected |
|--------|-------------|----------|
| Radio pair, project scope only | Two radios shown only in project scope | ✓ |
| Dropdown near scope toggle | ComboBox<CohortMode> next to Project toggle | |
| Checkbox on the write action | Single "Cluster all cells (exact, slower)" checkbox | |

**User's choice:** Radio pair, project scope only.

### Default mode & framing

| Option | Description | Selected |
|--------|-------------|----------|
| All-cells default; transfer = 'fast/approx' | All-cells default and framed exact; transfer framed fast/approximate | ✓ |
| Transfer default; all-cells = 'exact/slower' | Keep Phase 14 transfer default; all-cells opt-in | |

**User's choice:** All-cells default; transfer framed fast/approx.
**Notes:** Transfer is retired from nothing — stays fully functional as the alternative mode.

---

## Recall-gate mechanics

### Recall subsample size

| Option | Description | Selected |
|--------|-------------|----------|
| Fixed ~2,000 query cells | Cheap, stable | |
| Fixed ~5,000 query cells | Tighter estimate, ~2.5× cost | |
| Proportional, capped | ~0.1% of cells capped at ~10k | ✓ |

**User's choice:** Proportional, capped.

### Auto-tune strategy

| Option | Description | Selected |
|--------|-------------|----------|
| Escalate efSearch, then abort | Bump query-time efSearch geometrically, no rebuild; abort if still <95% | ✓ |
| Rebuild with higher efConstruction/M | Full rebuild before aborting; expensive at 30M | |
| No auto-tune — single check | Measure once, abort immediately if <95% | |

**User's choice:** Escalate efSearch, then abort.

### Parameter exposure

| Option | Description | Selected |
|--------|-------------|----------|
| Hidden, report measured recall | Internal defaults; log measured recall to status line | ✓ |
| Expose as advanced controls | Surface efSearch + threshold for power users | |

**User's choice:** Hidden, report measured recall.

---

## Batch UX (ceiling/progress/cancel)

### Soft ceiling

| Option | Description | Selected |
|--------|-------------|----------|
| 50M, configurable | Confirm above ~50M pooled cells; overridable | ✓ |
| 25M, configurable | Lower trigger, fires more often | |
| No ceiling | Never prompt on size | |

**User's choice:** 50M, configurable.

### Progress granularity

| Option | Description | Selected |
|--------|-------------|----------|
| Per-phase + per-image counts | "Pooling 12/40", "Building graph…", "Running Leiden…", "Writing 12/40" | ✓ |
| Coarse phase labels only | Four phase names, indeterminate bar | |

**User's choice:** Per-phase + per-image counts.

### Cancellation semantics

| Option | Description | Selected |
|--------|-------------|----------|
| Leave written intact, report | Stop before next image; keep written Cluster values; report which images updated | ✓ |
| Roll back all writes | Clear Cluster from written images; all-or-nothing | |
| Point of no return at pass 2 | Cancel allowed only before write pass | |

**User's choice:** Leave written intact, report.
**Notes:** No rollback pass — too costly at 30M; Cluster is a non-destructive numeric column.

---

## Claude's Discretion

- Graph/edge memory representation feeding the CWTS Network (LargeIntArray/LargeDoubleArray).
- Pooled feature-matrix internal layout; exact efSearch escalation cap.
- Where the two-pass driver lives (new CohortClusterModel method vs helper).
- UUID key storage form (two-long vs string), using PathObject.getID().
- Determinism seeded via the existing reproducibility toggle (bit-identity caveat under parallel ANN build noted).

## Deferred Ideas

- Literal pynndescent / UMAP fuzzy-simplicial connectivities / modularity (RBConfiguration) quality function — full scanpy fidelity beyond CPM + SNN/Jaccard + HNSW.
- PCA reduction before neighbors — near-identity for a ~20-marker panel.
- Spatially-aware Leiden (SpatialLeiden) — belongs to spatial-neighborhood work, not marker phenotyping.
