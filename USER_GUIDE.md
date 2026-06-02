# CellTune User Guide

A human-in-the-loop cell classifier for QuPath 0.7. CellTune trains two ML models in parallel (XGBoost + LightGBM by default) and uses their **disagreement** to surface the cells that need your attention. Everything runs in-process — no Python.

> **Conventions in this guide**
> - **Bold** = exact UI label.
> - `Monospace` = file path or code.
> - "Multi-class mode" = the standard sidebar (any number of cell-type classes).
> - "Binary mode" = a per-marker positive/negative classifier (CD4+/CD4−, etc.).
> - Add your own screenshots next to each section heading after install.

---

## Table of Contents

1. [Install & launch](#1-install--launch)
2. [Quick start — multi-class workflow](#2-quick-start--multi-class-workflow)
3. [Quick start — binary + composite workflow](#3-quick-start--binary--composite-workflow)
4. [Setup steps (shared by both workflows)](#4-setup-steps-shared-by-both-workflows)
   - [4.1 Select features](#41-select-features)
   - [4.2 Normalise features](#42-normalise-features)
   - [4.3 Create classes & Class Control](#43-create-classes--class-control)
   - [4.4 Import a marker table (auto channel switching)](#44-import-a-marker-table-auto-channel-switching)
5. [Multi-class workflow in detail](#5-multi-class-workflow-in-detail)
6. [Binary + composite workflow in detail](#6-binary--composite-workflow-in-detail)
7. [After training — Review mode](#7-after-training--review-mode)
8. [Project Prediction Summary](#8-project-prediction-summary)
9. [Exporting results](#9-exporting-results)
   - [9.1 Cell table export](#91-cell-table-export)
   - [9.2 Ground truth export & import](#92-ground-truth-export--import)
10. [Reference: every setting in the sidebar](#10-reference-every-setting-in-the-sidebar)
11. [Reference: every CellTune menu item](#11-reference-every-celltune-menu-item)
12. [Project directory layout](#12-project-directory-layout)
13. [Tips, gotchas, and known limitations](#13-tips-gotchas-and-known-limitations)

---

## 1. Install & launch

1. Build (or download) `qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar`. See [BUILD.md](BUILD.md) for build instructions.
2. Drop the JAR into QuPath's `extensions/` folder, or drag-and-drop it onto the running QuPath window.
3. Restart QuPath. The **CellTune Classifier** panel docks into the analysis tab pane on the right.
4. All commands also live under the **Extensions → CellTune Classifier** menu.

Disable the extension at any time from **Edit → Preferences → CellTune Classifier → Enable**.

---

## 2. Quick start — multi-class workflow

Build one classifier that distinguishes any number of cell types (e.g. T-cell / B-cell / Macrophage / Tumour / Stroma).

```
Select Features  →  Normalise Features  →  Create classes (Class Control)
       ↓
Import Marker Table (optional, for auto channel switching)
       ↓
Manual Label Mode  → label ~20–50 cells across the open image
       ↓
Apply to which images... → choose images to predict on
       ↓
Set Workers, pick settings (Pool labels, Balancing, Early stopping, etc.)
       ↓
Train  →  inspect Training Metrics + Confusion Matrix
       ↓
Enter Review Mode  →  correct cells the models disagree on
       ↓
(repeat: more labels → Train → Review)
       ↓
Project Prediction Summary  →  flag outlier slides → re-label as needed
       ↓
Export Cell Table  or  Export Ground Truth
```

Detail per step is in §[4](#4-setup-steps-shared-by-both-workflows), §[5](#5-multi-class-workflow-in-detail), §[7](#7-after-training--review-mode).

---

## 3. Quick start — binary + composite workflow

Build one **positive/negative classifier per marker** (CD3, CD4, CD8, CD20…), then combine them into composite cell types (`CD3+:CD4+:CD8-`, etc.).

```
Select Features  →  Normalise Features
       ↓
Binary Classifiers... → Create "CD3" → Open (enters Binary Mode)
       ↓
Manual Label Mode → label CD3-positive vs CD3-negative cells
       ↓
Apply to which images... → Train → Review Mode (correct disagreements)
       ↓
Exit Binary Mode → repeat for CD4, CD8, CD20, etc.
       ↓
Composite Classification... → tick markers, tick images
       ↓
(optional) tick "Prepend current primary classification" to keep
multiclass colouring
       ↓
Apply → composite labels appear in viewer (Tumour:CD3+:CD8-, …)
       ↓
Export Cell Table
```

Detail per step is in §[6](#6-binary--composite-workflow-in-detail).

---

## 4. Setup steps (shared by both workflows)

### 4.1 Select features

**Menu:** *Extensions → CellTune Classifier → Select Features...*

QuPath cell-detection panels (COMET, MIBI, IMC, CODEX) often produce 1000–2000 measurement columns per cell. CellTune lets you pick a subset for training; the rest are ignored.

- **Search** box — case-insensitive substring filter.
- **Prefix** dropdown — auto-populated with prefixes it finds in your feature names (`Cell:`, `Nucleus:`, `Membrane:`). Pick one, then **Select Prefix** / **Clear Prefix** to bulk-toggle just that group.
- **Select All** / **Clear All** — operate on whatever's currently visible after filtering.
- Checkbox per row to toggle individual features.
- Counter at the bottom: `X / Y selected`.

**When to limit features:** for big panels, restricting to e.g. `Cell: Mean` only (one channel-mean per marker) often gives near-identical accuracy with 10–20× less training time and much cleaner SHAP plots. The full set on disk is never touched — only the training column list.

Your selection is saved in `<project>/celltune/classifier-state.json` and persists across QuPath sessions.

### 4.2 Normalise features

**Menu:** *Extensions → CellTune Classifier → Normalise Features*

Per-feature transforms applied during feature extraction. Same prefix/search/select-all UI as Select Features, plus:

- **Transform** dropdown:
  - **arcsinh** — `arcsinh(x / cofactor)`. Recommended default.
    - **Cofactor = 1** for fluorescence (COMET, CODEX, IF).
    - **Cofactor = 100** for mass cytometry (MIBI, IMC).
  - **sqrt** — `sqrt(max(0, x))`. Simple variance stabilisation, no cofactor.

You pick **which** features to transform and **one** transform/cofactor applied to all of them. Untouched features stay raw.

**When to skip normalisation:** you probably shouldn't. Tree models are scale-invariant in theory but arcsinh massively helps SMOTE/ADASYN (which interpolate in feature space) and makes the SHAP plots interpretable across markers.

### 4.3 Create classes & Class Control

**Menu:** *Extensions → CellTune Classifier → Class Control...*

A 4-tab dialog for managing the QuPath class panel **and** the labels saved on disk under `<project>/celltune/image-labels/`.

#### Add tab
Type a class name, click **Add Class**. Just adds it to QuPath's class panel — no label files touched.

#### Delete tab
- Pick a class from the list.
- Tick **Also remove labels with this class from all image-label files** to scrub it from every saved per-image label JSON. Leave unticked to only remove it from the class panel (labels stay on disk, invisible).
- **Delete Selected Class** (red) — asks for confirmation.

#### Merge tab
- Multi-select source classes (Ctrl/Cmd+click), then either type a target name or pick one from **Existing**.
- **Merge Selected → Target** rewrites every matching label across all images. The original name is preserved inside the label string: `test1` merged into `myType` is stored on disk as `test1-mergedInto(myType)`. Training sees only the effective class (`myType`); the audit trail makes the merge fully reversible.

#### Undo Merge tab
- Pick a class that was previously the merge target (the combo scans label files for `-mergedInto(...)` patterns).
- **Undo Merge for Selected Class** — restores every label to its original name and re-adds the source `PathClass` to QuPath's class panel. The target class is **not** deleted; you can drop it from the Delete tab if you no longer want it.

### 4.4 Import a marker table (auto channel switching)

**Menu:** *Extensions → CellTune Classifier → Import Marker Table...*

Optional. Maps cell types to marker channels so review mode can auto-switch channel visibility to the markers relevant to each predicted cell.

**Simple format:**

```csv
CellType,Marker1,Marker2,Marker3
T-Cell,CD3,,
B-Cell,CD20,,
Macrophage,CD68,CD163,
Dendritic,CD11c,,
NK-Cell,CD56,,
```

**Rule format** (uses gating expressions — supports `|` OR, `&` AND, `!` NOT):

```csv
CellType,PrimaryMarker,SecondaryMarker,TertiaryMarker
CD8T,CD8,CD3,CD103|CD45|CD45RA
Plasma_CD38,CD38&!IgA,,CD45|VIM
```

Channel-name matching is robust (alphanumeric-normalised), so `CD3_S2 - Cy5_AF` matches the channel `CD3_S2-Cy5_AF` automatically.

In review mode, ticking the **Auto-switch channels** checkbox makes QuPath show only the relevant markers for the cell currently under review (with auto display range). Untick to navigate channels manually.

> The marker table lives in memory only. It is **not** saved to the project — re-import after restarting QuPath.

---

## 5. Multi-class workflow in detail

### 5.1 Initial manual labelling

Click **Manual Label Mode** in the sidebar. A floating toolbar appears:

- Click a cell in the QuPath viewer → its ID and current class show at the top, the status dot turns lime if labelled, white if not.
- A **magenta ring** marks the selected cell (a lightweight overlay — won't slow down 50k+ cell images).
- Up to 12 quick-access class buttons appear inline; the rest live under **All Classes ▼**.
- **Auto-advance to next detection** — when ticked, assigning a label automatically jumps to the next cell.

**How many to label?** Aim for **at least 20–30 cells per class** before your first training run. CellTune will refuse to train with fewer than 10 labelled cells total. You can — and should — add more after each review cycle.

> The **Model 1** / **Model 2** buttons only appear once you've trained at least once. They let you accept a prediction with one click. Background colour: blue = M1, pink = M2.

### 5.2 Choose images to apply the classifier to

Click **Apply to which images... (N)** above the train button.

- Dual-list selector. Left = images the classifier will predict on, right = excluded.
- Per-list search and Move-all/Move-selected arrows.
- The **currently open image is always included** and can't be moved out.
- Click **OK**; the button label now shows the count, e.g. `Apply to which images... (12)`.

### 5.3 Crank up workers (if you have RAM)

**Workers** spinner (1–8). One worker = one full slide loaded into memory at a time. On a 32 GB workstation with COMET-sized slides, 2–3 workers is a sweet spot. On 16 GB, leave at 1.

### 5.4 Pick the right settings

The defaults are tuned for typical multiplex panels. Adjust as follows:

| Setting | Default | Turn ON when… | Turn OFF when… |
|---|---|---|---|
| **Pool labels from all images** | ✅ | (always; auto-on in binary mode) | training a per-image model intentionally |
| **Enable data balancing** + `SMOTE + Tomek` | ✅ | one class << others (typical multiplex) | classes are already balanced or you want raw counts |
| **Auto-tune hyperparameters** | ❌ | first build of a new panel; willing to wait | iterating fast; defaults known to work |
| **Early stopping** | ✅ | (always — no downside) | reproducing a paper with a fixed round count |
| **Show top 10 feature importance** | ✅ | (always — cheap) | reducing UI clutter |
| **Auto-prune features** | ✅ | (always — non-destructive, faster training) | running a reproducible benchmark |
| **Restrict to features shared with imported data** | ❌ | merging labels imported from a different panel | training on this project only |
| **Sample current image only** | ❌ | drilling into one tricky FOV | (default — covers whole project) |

**Resampling strategies** (visible when Enable data balancing is on):

| Strategy | Effect |
|---|---|
| `NONE` | No resampling |
| `SMOTE` | Synthetic minority oversampling (k=5 nearest same-class neighbours) |
| `ADASYN` | Like SMOTE but concentrates synthetics on hard-to-classify minorities |
| `TOMEK` | Removes majority-class members of mutual nearest-neighbour pairs (cleans boundary) |
| `SMOTE + Tomek` (default) | SMOTE, then Tomek cleanup |
| `ADASYN + Tomek` | ADASYN, then Tomek cleanup |

Defaults work for ~90% of cases. Switch to `SMOTE` alone if Tomek is removing too much real signal; switch to `ADASYN` if a minority class lives in a hard region of feature space.

**Models 1 & 2.** Default pair is **XGBoost + LightGBM**. Random Forest is also available. Keep the two model types **different** — that's the whole point of dual-model disagreement. Auto-tune runs independently per model.

**Rounds / Max depth.** Default 200 rounds, depth 6. Together with early stopping, this is almost always enough. Increase rounds to 500 if early stopping is firing very late.

### 5.5 Train

Click **Train**. A progress dialog shows the current step (feature extraction, balancing, fold training, etc.). Before training starts, a timestamped backup of the label store is written to `<project>/celltune/labels_backup_*.json`.

Status bar after success: `Training complete — 523 cells classified, 47 disagreements.`

### 5.6 Inspecting the result

Two views are unlocked after a successful run:

#### Confusion Matrix (button)

The **inter-model agreement** matrix — rows = XGBoost prediction, columns = LightGBM prediction.

- **Diagonal cells (blue)** — both models agreed on this class.
- **Off-diagonal cells (orange/red)** — the two models disagreed; these are the cells that go into Review Mode.
- **Right column** — per-class recall-style %.
- **Bottom row** — per-class precision-style %.
- **Far right** — per-class Dice (inter-model F1).
- **Summary line:** `Total: X | Agreement: Y (Z%) | Disagreement: A (B%) | Macro Dice: D`.

A diagonal-dominant matrix means the two models broadly agree; large off-diagonal hotspots show systematic confusion pairs (e.g. CD4/CD8 cross-talk) — those are your priority for the next labelling round.

#### Training Metrics (button)

Per-class **precision / recall / F1 / support** for each model, computed on a held-out **20% stratified validation split**:

```
class            precision   recall      f1   support
─────────────────────────────────────────────────────
CD4                  0.925    0.887    0.906       145
CD8                  0.891    0.923    0.907       198
…
─────────────────────────────────────────────────────
accuracy                              0.905       500
macro F1                              0.894       500
weighted F1                           0.903       500
```

There's also a **Validation Confusion Matrix** view (true class × predicted class on the same 20% fold), with both absolute counts and row-normalised recall heatmaps, plus a per-row diagonal = recall.

**Exports:**
- **CSV** — long format `split,model,class,precision,recall,f1,support`, with summary rows tagged `__accuracy__`, `__macro_f1__`, `__weighted_f1__` so they're easy to filter in pandas/R.
- **PNG** — side-by-side validation confusion-matrix heatmaps.

> **Don't trust an F1 of 0.95 on its own.** A 20% stratified split from the same image (or even from a tight cluster of similar images) overstates how well the model will generalise. The honest test is: **open a different slide, predict, and visually scan the results**, then check the Project Prediction Summary (§8). If a slide has predictions that look wrong by eye, the F1 lied — go label some of its cells.

#### Feature Importance (button)

Top-N (up to 10) features by **mean |SHAP|** per class. Horizontal bars, one colour per class, dropdown to switch classes. SHAP is averaged across whichever models are active (TreeSHAP for XGBoost/LightGBM, normalised split counts for Random Forest).

Use it to spot features the model is over-relying on (e.g. if `Cell: DAPI Mean` dominates every class, your normalisation cofactor is probably wrong).

### 5.7 Optional — restrict sampling to specific annotations

Two controls above the buttons:

- **Sample current image only** — limits review/sampling to the open image.
- **Filter by annotation keywords** — comma-separated, case-insensitive substring match against annotation names. Example: `Tumour, Margin` → only cells whose centroid falls inside an annotation whose name contains "Tumour" or "Margin" are eligible.

Leave both blank to sample across every cell in every project image (recommended default).

---

## 6. Binary + composite workflow in detail

Use this when you want **per-marker** classifiers (one for CD3 positive/negative, another for CD8 positive/negative, etc.) and then combine them into composite cell types.

### 6.1 Create a binary classifier

**Menu:** *Extensions → CellTune Classifier → Binary Classifiers...*

- **Create...** → enter a marker name (e.g. `CD3`). Marker names are sanitised to safe filesystem characters.
- The marker is registered in `<project>/celltune/binary-registry.json` and a state file `<project>/celltune/binary/CD3.json` is created when you first train.

Select the marker in the list and click **Open**. The dialog closes; the main sidebar switches into **Binary Mode** with a blue banner: `Active binary mode: CD3`.

In binary mode:
- The class buttons in Manual Label Mode are restricted to `CD3_pos` and `CD3_neg` (so you can't accidentally label across markers).
- **Pool labels from all images** is auto-enabled and locked — each marker classifier always trains on its full pooled label set.
- Settings, sampling, review, and metrics work exactly the same as multi-class.

Train, review, and iterate until you're happy. Then click **Exit Binary Mode** to return to multi-class.

Repeat for every marker you want in the composite.

### 6.2 Composite classification

**Menu:** *Extensions → CellTune Classifier → Composite Classification...*

- **Markers** — checkbox per trained binary classifier. **All** / **None** buttons above. Only markers that have been trained (have a saved XGBoost model) appear.
- **Images** — checkbox per project image. **All** / **None** / **Current only** buttons above.
- **Prepend current primary classification (colour follows primary)** — see below.
- **Apply** — runs the classifiers.

**How it works:**
- The currently open image is classified **in-memory** — viewer updates immediately, no save/reload.
- Every other selected image is read from disk, classified, and written back (logged in the panel's text area).
- Each cell gets a composite `PathClass` named by joining the marker results alphabetically:
  - `CD3+:CD8-:CD45+` etc.
  - `+` if the binary classifier's positive probability ≥ 0.5, else `-`.

**Prepend current primary classification:**
- When **off** (default): composite name is markers only (`CD3+:CD8-`), QuPath auto-assigns the class colour.
- When **on**: each cell's current primary `PathClass` is captured **before** any reassignment and prepended (`Tumour:CD3+:CD8-`). The composite class's colour is set to the primary class's colour, so the viewer keeps your existing multi-class colouring. Cells with no current primary fall back to binary-only naming.

> Use the merge mode after you've already run a multi-class classifier — the multi-class result becomes the cell-type "backbone" and the binary classifiers add functional state.

---

## 7. After training — Review mode

**Button:** **Enter Review Mode** in the sidebar.

Review mode samples the **disagreement** cells (where Model 1 ≠ Model 2) using a 5-tier strategy so you don't waste labelling effort on cells that are easy or already represented:

| Tier | Goal | Default budget @ 256 cells |
|---|---|---|
| **0 — FOV balance** | Stop one slide dominating | ~84 cells, prioritising FOVs with high disagreement rate |
| **1 — Cell-type disagreement** | Cover the most confused classes | ~16 per class |
| **2 — Rare cell types** | Don't ignore small populations | ~10 per rare type |
| **3 — Preferred confusions** | User-specified pair (e.g. `CD4:CD8`) | ~8 per pair |
| **4 — Random fill** | Use any remaining budget | up to 256 |

**Toolbar buttons during review:**
- **Previous / Next / Skip** — navigate the queue.
- **XGB: ClassName (89%)** — accept Model 1's top prediction; blue background.
- **LGB: ClassName (76%)** — accept Model 2's top prediction; pink background.
- **Both: ClassName (XX%)** — single combined button if M1 and M2 agree.
- **All Classes ▼** — pick a different class if both models are wrong.
- **Done** — exit; labels are merged back into the label store and saved per-image to `<project>/celltune/image-labels/`.

The selection ring is the same magenta overlay used in Manual Label Mode (no expensive PathObject selection events fired).

If you imported a marker table (§4.4), tick **Auto-switch channels** and the viewer will display only the markers relevant to whatever class the current cell was predicted as.

After review, click **Train** again — the new labels feed into the next cycle.

---

## 8. Project Prediction Summary

**Menu:** *Extensions → CellTune Classifier → Project Prediction Summary...*

Cohort-level QC across every image in your project. Loads the saved `Pred_ALL` results from `<project>/celltune/image-predictions/` and runs an anomaly analysis. See [HOW_IT_WORKS_PREDICTION_SUMMARY](#anatomy-of-the-anomaly-score) below for the maths.

**Table columns:** Image, Predicted, Agreements, Disagreements, Agreement %, Anomaly, Flagged.

**Filters:**
- **Flagged only** — hide rows with no flag.
- **Target class** — restrict to images where a specific rare class is enriched.
- **Threshold preset** — *strict* (anomaly ≥ 1.5), *balanced* (≥ 0.5), *sensitive* (≥ 0.0, default — shows everything). This is a **display** filter; the analysis is not re-run.

**Buttons:**
- **Open Selected Image** — jumps QuPath to that image without saving the current one (deliberately fast for navigation).
- **Export CSV** — flattened table of currently-visible rows.

**Details pane** (below the table) for the selected row: anomaly score, flag reasons, rare-enrichment summary, per-class counts.

### Anatomy of the anomaly score

For each image:

1. **Composition distance** — Jensen-Shannon distance between this image's class-fraction distribution and the project-wide baseline (with Laplace smoothing).
2. **Disagreement rate** — `disagreements / predicted`.
3. Both signals are converted to **robust z-scores** (median + MAD, so one extreme image can't suppress the scale) across the cohort.
4. `Anomaly score = 0.65 × max(0, z_composition) + 0.35 × max(0, z_disagreement)`.

**Flag reasons:**
- `RARE_ENRICHMENT` — a class that is <1% of the cohort, has ≥20 cells in this image, and is ≥3× enriched vs the baseline.
- `OUTLIER_COMPOSITION` — composition robust z ≥ 3.
- `OUTLIER_DISAGREEMENT` — disagreement robust z ≥ 3.

**How to use it:**
- Sort by Anomaly (default). Top rows = look at these first.
- Flagged + high disagreement → the classifier doesn't understand this slide. **Open it, label 10–20 cells, re-train.**
- Flagged + composition outlier but low disagreement → real biology that's atypical for the cohort, or staining drift / segmentation artefact. Visual check.
- Rare enrichment → check whether the rare class is real (good, you've found something) or a per-slide artefact masquerading as it.

> Robust z is noisy on tiny projects (< ~5 images). Don't overinterpret on small cohorts.

---

## 9. Exporting results

### 9.1 Cell table export

**Menu:** *Extensions → CellTune Classifier → Export Cell Table...*

For each selected image, writes `<ImageName>.csv` to your chosen folder with one row per detection:

| Column | Notes |
|---|---|
| `Image` | Source image name |
| `CellID` | QuPath cell UUID |
| `CentroidX` / `CentroidY` | Pixel coordinates, 2 decimals |
| `Area` | Pixels², 2 decimals |
| `Classification` | Current `PathClass` (empty if unclassified) |
| `ParentAnnotations` | All ancestor annotations, joined with `; ` |
| `Geometry` | WKT `POLYGON` of the ROI outline |
| feature columns | `Cell: Area` plus all columns containing "mean" (case-insensitive); falls back to all features if no "mean" columns exist |

RFC-4180 compliant (quotes escaped). The dialog asks which images to include if the project has more than one.

### 9.2 Ground truth export & import

CellTune ground-truth files are a portable representation of your labelled cells **and** their feature vectors — they let you reuse labels across projects/workstations.

#### Export

**Menu:** *Extensions → CellTune Classifier → Export Ground Truth...*

Header (commented):
```
# CellTune Ground Truth Export
# Image: my_image.lif
# Exported: 2026-06-02T14:30:45
Image,Label,CentroidX,CentroidY,Feature1,Feature2,...[,Feature1__norm,...]
```

Dialog asks whether to include raw features, normalised features (`__norm` suffix), or both. Only labelled cells are exported.

In multi-class mode the export pools labels from the current image plus all other project images. In **binary mode** use the dedicated menu item **Export Active Binary Ground Truth...** — it scopes to the active marker and includes previously-imported training rows from prior projects (so you can losslessly round-trip between projects).

#### Import

**Menu:** *Extensions → CellTune Classifier → Import Ground Truth...*

After picking the CSV you choose one of two modes:

1. **Spatial Match** (per-image) — each imported row is matched to the nearest detection by centroid distance (you set the max threshold, default 20 px). Rows outside the threshold are skipped. Use this when you're re-importing labels onto the **same** image they were exported from.
2. **Training Data Only** (cross-project) — imports the feature vectors + labels without mapping back to cells. Use this when the source image isn't open in the current project; the rows feed straight into the next training run as if they were locally-labelled cells. The sidebar shows the count as `Imported rows: N`.

The binary equivalents are **Import Active Binary Ground Truth...** — same modes, but scoped to the active marker.

> **There is no "ground truth bundle" (ZIP)** currently — only the per-CSV import/export described here. The `.planning/phases/12` document scopes a bundle format as a future feature.

---

## 10. Reference: every setting in the sidebar

| Control | Default | What it does |
|---|---|---|
| **Rounds** | 200 | Boosting iterations (50–1000). Increase for complex data; decrease for fast trials. |
| **Max depth** | 6 | Tree depth (2–15). Higher = more complex interactions, more overfit risk. |
| **Workers** | 1 | Parallel image-prediction workers (1–8). Each loads a full slide; RAM-bound. |
| **Model 1** | XGBoost | First ensemble model. |
| **Model 2** | LightGBM | Second ensemble model. **Pick a different type** for meaningful disagreement. |
| **Pool labels from all images** | ✅ | Train on labels from every image; auto-on/locked in binary mode. |
| **Enable data balancing** | ✅ | Apply resampling. Hides the strategy dropdown when off. |
| **Strategy** | SMOTE + Tomek | Resampling algorithm — see §5.4 table. |
| **Auto-tune hyperparameters** | ❌ | TPE Bayesian search per model. Slow but explores rounds/depth/eta/subsample. |
| **Early stopping** | ✅ | Stop boosting when val loss plateaus (patience 20). |
| **Show top 10 feature importance after training** | ✅ | Auto-open SHAP plot after training. |
| **Auto-prune features** | ✅ | Drop near-constant & redundant features before training. Non-destructive. |
| **Restrict to features shared with imported data** | ❌ | Case-insensitive intersection with imported ground-truth columns. |
| **Sample current image only** | ❌ | Restrict sampling/review to the open image. |
| **Filter by annotation keywords** | (blank) | Comma-separated substring filter on annotation names. |
| **Apply to which images...** | (all) | Open dual-list selector. Button label updates with count. |
| **Manual Label Mode** | — | Open floating labelling toolbar. |
| **Train** | — | Start training. Requires ≥10 labelled cells. |
| **Plot Confusion...** | (disabled) | Inter-model agreement matrix. Unlocks after training. |
| **Training Metrics** | (disabled) | Per-class precision/recall/F1 on 20% held-out split (≥20 labelled cells). |
| **Feature Importance...** | (disabled) | SHAP top-N per class. Unlocks after training. |
| **Enter Review Mode** | (disabled) | Sample disagreement cells for human review. Unlocks after predictions exist. |

---

## 11. Reference: every CellTune menu item

All under *Extensions → CellTune Classifier*.

| Item | Requires | Action |
|---|---|---|
| Binary Classifiers... | Project | Open the binary classifier manager (create/open/delete per-marker classifiers). |
| Composite Classification... | Project + ≥1 trained binary | Apply trained binary classifiers and assign composite labels. |
| Class Control... | Project | Add/Delete/Merge/Undo Merge classes. |
| Select Features... | Project | Pick which measurement columns are used for training. |
| Normalise Features | Project | Per-feature arcsinh/sqrt with shared cofactor. |
| Project Prediction Summary... | Project | Cohort QC, anomaly scoring, per-image flags. |
| Import Marker Table... | Open image | Load cell-type → markers mapping for review channel switching. |
| Export Cell Table... | Open image with detections | One CSV per selected image. |
| Export Ground Truth... | Open image with labels (multi-class) | Portable labels + feature vectors CSV. |
| Import Ground Truth... | Open image (multi-class) | Spatial-match or training-data-only mode. |
| Export Active Binary Ground Truth... | Binary mode active + open image with labels | Same as above, scoped to active marker. |
| Import Active Binary Ground Truth... | Binary mode active + open image | Same as above, scoped to active marker. |

---

## 12. Project directory layout

Everything CellTune writes is under `<project>/celltune/`:

```
celltune/
├── classifier-state.json         # Multi-class model (features, classes, model bytes, labels, normalisation)
├── composite-rules.json          # Saved CompositeClassificationRule objects (advanced/programmatic)
├── binary-registry.json          # markerName → state file path
├── labels_backup_YYYYMMDD_HHMMSS.json   # Auto-snapshot before each Train
│
├── image-labels/                 # Multi-class labels, one JSON per image
│   ├── slide1.json               #   { "<cellId>": "T-Cell", ... }
│   └── ...
│
├── binary-image-labels/<marker>/ # Same per-image JSON, scoped per binary marker
│   ├── CD3/slide1.json
│   └── ...
│
├── binary/                       # Binary classifier state files
│   ├── CD3.json
│   ├── CD8.json
│   └── ...
│
├── image-sampled/                # Cell IDs already sampled for review
├── image-predictions/            # Per-image Pred_ALL — consumed by Project Prediction Summary
```

JSON throughout. Model bytes are Base64-encoded inside the state files. Safe to commit `celltune/` to git if you want shared review history.

---

## 13. Tips, gotchas, and known limitations

- **Label at least 20–30 cells per class** before the first Train, then trust the disagreement-driven Review Mode to grow your label set efficiently.
- **F1 scores can lie.** A held-out 20% split is honest within an image but optimistic across the project. Always sanity-check on a few unseen slides before believing the metrics.
- **Pick different model types for Model 1 and Model 2.** Two XGBoosts won't disagree much, which kills the whole point.
- **LightGBM SHAP is disabled** (the native call crashes the JVM — see [RISKS.md](RISKS.md)). Feature importance for LightGBM uses XGBoost's TreeSHAP as the comparison baseline when both are active. Pure-LightGBM SHAP plots are not available.
- **Binary classification SHAP** shows identical bars for both classes (positive/negative). This is mathematically correct, not a bug — see [RISKS.md §2.4](RISKS.md#24-binary-classification-shap-display).
- **Workers spinner caps at 8.** Each worker loads a full slide; expect ~2–4 GB RAM per worker on COMET data.
- **The marker table is in-memory only.** Re-import after a QuPath restart if you want auto-channel-switching during review.
- **Project Prediction Summary needs ≥5 images** to give meaningful robust z-scores. On 2–3 image projects, treat the Anomaly column as decorative.
- **Composite class colours.** Without "Prepend primary", QuPath generates a colour per unique composite name — you can end up with hundreds. Tick "Prepend primary" and your existing multi-class palette is preserved.
- **No `.qpdata` save** when navigating from Project Prediction Summary — this is deliberate (saving large slides is slow and pointless for navigation). Manually save the image after editing it.

---

*Spot a missing step or an inaccurate label? Open an issue on the GitHub repo. Screenshots welcome.*
