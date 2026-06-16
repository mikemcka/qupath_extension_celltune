# CellTune Classifier for QuPath

## This Java extension is an unofficial emulation of some functionality of CellTune by the Keren Lab into QuPath. If you use this tool for your analysis please also cite [the CellTune preprint](https://www.biorxiv.org/content/10.1101/2025.05.05.652215v1).

A [QuPath](https://qupath.github.io/) 0.7 extension that brings **CellTune-style active learning** to cell classification. It trains two gradient-boosted models (XGBoost + LightGBM) simultaneously, identifies cells where the models disagree, and presents those disputed cells for human review — creating an iterative loop that progressively improves classification accuracy.

No Python dependency is required. Everything runs inside QuPath using Java/JavaFX.

## Features

- **Dual-model disagreement detection** — two models are trained on the same labels; cells where they disagree are flagged for review. Model types are configurable: **XGBoost**, **LightGBM**, or **Random Forest**. The default pairing is XGBoost + LightGBM. Both boosted models attempt GPU (CUDA) training automatically and fall back to CPU if unavailable.
- **5-tier weighted uncertainty sampling** — the review sample is built in five priority tiers, each marking its cells as used so later tiers can't re-select them. **Tier 0 (FOV balance):** disagreement cells are grouped by field of view and sampled proportionally so that no single FOV dominates the review queue. **Tier 1 (cell-type disagreement):** remaining disagreement cells grouped by predicted class, prioritising the classes with the highest disagreement fraction. **Tier 2 (rare cell types):** disagreement cells grouped by class and sorted rarest-first, so under-represented types still get reviewed. **Tier 3 (preferred confusions):** user-specified confusion pairs (e.g. `CD4:CD8`) and/or preferred cell types that should be over-sampled for targeted review. **Tier 4 (random fill):** any remaining disagreement cells, shuffled, to use up the requested sample budget.
- **Interactive review mode** — navigate cell-by-cell through sampled disagreements; for each cell the toolbar shows the top prediction from each model (XGBoost & LightGBM) with confidence percentages as clickable buttons, plus an "All Classes" dropdown populated from the QuPath project class list for manual override. When the averaged probability prediction differs from both individual models, an additional "Avg" button appears. Entering review mode prompts for how many cells to sample each time, and if switching to a new image with a trained classifier, predictions are automatically applied before review begins. The toolbar also displays the **name(s) of the annotation region(s)** that the current cell falls inside (bold dark blue, e.g. `◆ Tumour, Stroma`), so the spatial context is visible without leaving review.


## The Active Learning Loop

```
① Seed labels via point annotations on detected cells
                    │
                    ▼
② Train dual classifiers (XGBoost + LightGBM)
                    │
                    ▼
③ View inter-model confusion matrix
   → per-class agreement rates
                    │
                    ▼
④ Enter review mode → choose sample size
   → step through disagreement cells
   → assign or correct labels
                    │
                    ▼
   ⟳ Merge new labels → retrain → repeat
     until satisfied with classification quality
```

## Requirements

| Component | Version |
|-----------|---------|
| QuPath | 0.7.0 or later |
| Java | 25 (required by QuPath 0.7) |

The extension JAR bundles XGBoost4J, LightGBM4J, and a pure-Java Random Forest — no separate ML library installation is needed.

## Installation

1. Download the latest `qupath-extension-celltune-*-all.jar` from the [Releases](../../releases) page (or build from source — see below)
2. Drag and drop the JAR onto the QuPath window, or copy it to your QuPath extensions directory:
   - **Windows:** `C:\Users\<you>\QuPath\v0.7\extensions\`
   - **Linux:** `~/.local/share/QuPath/v0.7/extensions/`
   - **macOS:** `~/Library/Application Support/QuPath/v0.7/extensions/`
3. Restart QuPath — the extension appears under **Extensions > CellTune Classifier**

## Quick Start

1. **Open a project** with an image that has cell detections (run *Analyze > Cell detection* first if needed)
2. **Label seed cells** — create point annotations on detected cells, then set the annotation's class (e.g. `CD4T`, `Bcell`, `Macrophage`). Aim for ≥10 cells per class.
3. **Import a marker table** (optional) — *Extensions > CellTune Classifier > Import Marker Table* — a CSV mapping cell types to up to 3 marker channel names, used for auto-channel switching during review
4. **Select features** (optional) — *Extensions > CellTune Classifier > Select Features* — choose which measurements to include in training
5. **Normalise features** (optional) — *Extensions > CellTune Classifier > Normalise Features* — apply arcsinh or sqrt transforms to selected features. Use cofactor=1 for fluorescence (COMET, CODEX) or cofactor=100 for mass spectrometry (MIBI, IMC).
6. **Train** — click *Train* in the CellTune panel (or *Extensions > CellTune Classifier > Run CellTune Classification…*). If features haven't been selected yet, you'll be prompted to select them or use all. A confirmation dialog shows the feature and label counts, **Model 1** and **Model 2** type selectors (default: XGBoost + LightGBM), resampling, auto-tune, and early stopping options. If the project has multiple images, a dual-list image selector lets you choose which images to apply the trained classifier to. A progress dialog shows real-time training status including GPU/CPU device info.
7. **Plot confusions** — click *Plot Confusions* to see the inter-model confusion matrix with per-class agreement rates and F1 scores
8. **Feature importance** (optional) — *Extensions > CellTune Classifier > Feature Importance…* — opens a bar chart of the top 10 features by mean |SHAP| value with a class selector. Alternatively tick **"Show top 10 feature importance after training"** in the training dialog to show it automatically.
9. **Review** — click *Enter Review Mode*. You'll be prompted for how many disagreement cells to review (default 200), then step through disputed cells one-by-one. Each cell shows coloured prediction buttons (e.g. `XGB: CD4 (87%)`, `LGB: Bcell (65%)`) — click to accept. Use the *All Classes* dropdown if neither prediction is correct. If you switch to a different image, the trained classifier is automatically applied so you can review that image immediately.
10. **Retrain** — after reviewing, click Train again. The confusion matrix should improve. Repeat until satisfied.
11. **Export** — *Export Cell Table* opens a column picker (same search/prefix/select-all controls as *Select Features*) so you can choose which measurement columns to export, with an optional tick-box to include cell polygons in micron or pixel coordinates, then saves all cells as CSV. *Export AnnData* exports AnnData-compatible CSV with a Python H5AD conversion script. *Export Ground Truth* saves labelled cells for transfer to other images.

After running predictions, use *Extensions > CellTune Classifier > Project Prediction Summary...* to compare per-image agreement/disagreement counts, jump to a selected image, or export the summary as CSV.

Use *Extensions > CellTune Classifier > Intensity Heatmaps...* to view a phenotype × marker mean-intensity heatmap (mean whole-cell intensity per predicted class, coloured by per-marker z-score across phenotypes). Switch between the current image, any individual project image, or a project-combined view, and export to PNG or CSV.

### Marker Table Format For Automated Channel Switching

A simple CSV with up to 5 marker columns. Trailing columns may be left blank.

```csv
CellType,Marker1,Marker2,Marker3,Marker4,Marker5
CD4T,CD4,CD3,,,
Bcell,CD20,,,,
Macrophage,CD68,CD163,CD11b,,
Treg,CD4,CD25,FOXP3,CD3,
```

## Building from Source

See [BUILD.md](BUILD.md) for prerequisites (JDK 25), platform-specific commands, and install steps. In short:

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew shadowJar
# → build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar
```

## Project Structure

Source lives under `src/main/java/qupath/ext/celltune/`, organised into `model/`, `classifier/`, `gating/`, `ui/`, and `io/` packages, with `CellTuneExtension.java` as the entry point. See [AGENTS.md](AGENTS.md#architecture) for the package-by-package architecture and key classes.


## Technology Stack

| Layer | Technology |
|-------|-----------|
| Build system | Gradle 9.2.1 (Kotlin DSL) + QuPath conventions plugin |
| Extension host | QuPath 0.7 |
| ML model 1 | XGBoost4J 3.2.0 |
| ML model 2 | LightGBM4J 4.6.0-2 |
| ML model 3 | Random Forest (pure Java, no external dependency) |
| UI framework | JavaFX (bundled with QuPath) |
| Serialisation | JSON (Gson, bundled with QuPath) |

## Default Hyperparameters

### XGBoost

| Parameter | Default | Range |
|-----------|---------|-------|
| Boosting rounds | 1000 | 50–2000 |
| Max tree depth | 6 | 2–15 |
| Learning rate (eta) | 0.1 | — |
| Subsample | 0.8 | — |

### LightGBM

| Parameter | Default | Range |
|-----------|---------|-------|
| Boosting rounds | 1000 | 50–2000 |
| Max tree depth | 6 | 2–15 |
| Learning rate (eta) | 0.05 | — |
| Subsample | 0.8 | — |
| min_split_gain | 10 | — |

### Random Forest

| Parameter | Default |
|-----------|---------|
| Number of trees | 100 |
| Max tree depth | 100 |
| Feature subset (mtry) | √(num_features) |
| Split criterion | Cross-entropy (log loss) |
| Bootstrap | Yes |

These can be adjusted in the Classification Panel before training.

## Feature Normalization

Optional per-feature transforms can be applied before training and inference:

| Transform | Formula | Use case |
|-----------|---------|----------|
| arcsinh | `arcsinh(x / cofactor)` | Variance-stabilising transform for intensity data |
| sqrt | `√max(0, x)` | Simple variance-stabilising transform |

## TODO / Future Exploration
- look at bug with using mac screenshot freezing extension on mac
- compile gpu librarys for xgboost, lightgbm ect 
- tab pfn model 

## License

[Add your license here]

## Acknowledgements

- **[CellTune](https://celltune.org/)** by the [Keren Lab](https://www.weizmann.ac.il/mcb/Keren/home) — the active learning cell classification workflow that this extension emulates. See [the CellTune preprint](https://www.biorxiv.org/content/10.1101/2025.05.05.652215v1).
- **[qupath-extension-xgboost](https://github.com/zindy/qupath-extension-xgboost)** by [Zindy](https://github.com/zindy) — a QuPath 0.7 XGBoost extension whose project structure, Gradle configuration, and XGBoost4J integration patterns served as a reference implementation for this extension.
- Built on the [QuPath extension template](https://github.com/qupath/qupath-extension-template).
