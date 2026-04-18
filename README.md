# CellTune Classifier for QuPath

A [QuPath](https://qupath.github.io/) 0.7 extension that brings **CellTune-style active learning** to cell classification. It trains two gradient-boosted models (XGBoost + LightGBM) simultaneously, identifies cells where the models disagree, and presents those disputed cells for human review — creating an iterative loop that progressively improves classification accuracy.

No Python dependency is required. Everything runs inside QuPath using Java/JavaFX.

## Features

- **Dual-model disagreement detection** — XGBoost and LightGBM are trained on the same labels; cells where they disagree are flagged for review
- **Weighted uncertainty sampling** — cells involving confused class pairs are sampled more heavily, so your labelling time targets the model's worst failure modes first
- **Interactive review mode** — navigate cell-by-cell through sampled disagreements, assign labels with one click, and optionally auto-switch fluorescence channels based on predicted cell type
- **Confusion matrix visualisation** — Canvas-based inter-model confusion plot with per-class agreement rates and PNG export
- **Docked sidebar panel** — Train, plot confusions, sample, and review all from a single panel docked in QuPath's analysis pane
- **Ground truth portability** — export/import labelled cells as CSV for cross-image or cross-project transfer (spatial matching or training-data-only modes)
- **Feature selection** — filterable, searchable feature selector handles panels with 2000+ measurements
- **Project state persistence** — classifier models, labels, and feature names are saved as JSON+Base64 in the QuPath project folder with timestamped backups

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
④ Sample uncertain / disagreement cells
   → weighted toward confused class pairs
                    │
                    ▼
⑤ Review sampled cells one-by-one
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

The extension JAR bundles XGBoost4J and LightGBM4J — no separate ML library installation is needed.

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
5. **Train** — click *Train* in the CellTune panel (or *Extensions > CellTune Classifier > CellTune Classifier…*). Both models train in the background.
6. **Plot confusions** — click *Plot Confusions* to see the inter-model confusion matrix with agreement rates
7. **Sample & review** — click *Sample & Review*, choose a sample size (default 200), then *Enter Review Mode* to step through disputed cells and assign labels
8. **Retrain** — after reviewing, click Train again. The confusion matrix should improve. Repeat until satisfied.
9. **Export** — *Export Cell Table* saves all cells with predictions and confidence scores as CSV. *Export Ground Truth* saves labelled cells for transfer to other images.

### Marker Table Format

A simple CSV with up to 3 marker columns:

```csv
CellType,Marker1,Marker2,Marker3
CD4T,CD4,CD3,
Bcell,CD20,,
Macrophage,CD68,CD163,CD11b
```

## Building from Source

### Prerequisites

- **Java Development Kit 25** — download from [Adoptium](https://adoptium.net/temurin/releases/?version=25)
- **Git**

No separate Gradle installation is needed — the project includes the Gradle wrapper.

### Windows

```powershell
# Clone the repository
git clone https://github.com/your-org/qupath-extension-celltune.git
cd qupath-extension-celltune

# Set JAVA_HOME to your JDK 25 installation
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Build the shadow JAR
.\gradlew.bat shadowJar
```

The output JAR will be at:

```
build\libs\qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar
```

### Linux / macOS

```bash
# Clone the repository
git clone https://github.com/your-org/qupath-extension-celltune.git
cd qupath-extension-celltune

# Set JAVA_HOME to your JDK 25 installation
export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk   # adjust to your install path
export PATH="$JAVA_HOME/bin:$PATH"

# Make the wrapper executable and build
chmod +x gradlew
./gradlew shadowJar
```

The output JAR will be at:

```
build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar
```

### Verifying Java Version

```bash
java -version
# Should show: openjdk version "25..." or similar
```

If you have the `foojay-resolver-convention` plugin (already included in `settings.gradle.kts`), Gradle can auto-provision JDK 25 on first build — but setting `JAVA_HOME` explicitly is more reliable.

### Build Output

The `shadowJar` task produces a single fat JAR (~51 MB) that bundles XGBoost4J and LightGBM4J. Copy this JAR to your QuPath extensions directory.

## Project Structure

```
src/main/java/qupath/ext/celltune/
├── CellTuneExtension.java          # Entry point — menus, panel docking, state management
│
├── model/                          # Data model layer
│   ├── CellFeatureExtractor.java   # QuPath measurements → float[]
│   ├── CellPrediction.java         # Per-cell dual-model prediction + confidence
│   ├── LabelStore.java             # Ground-truth labels (cellId → class)
│   ├── PopulationSet.java          # Named prediction collection (MDL1/MDL2/AVG/ALL)
│   └── CellTypeTable.java          # Cell type → marker channel mapping
│
├── classifier/                     # ML training and inference
│   ├── DualModelClassifier.java    # Orchestrates XGBoost + LightGBM training
│   ├── XGBoostModel.java           # XGBoost4J wrapper
│   ├── LightGBMModel.java          # LightGBM4J wrapper
│   ├── ClassifierState.java        # Serialisable model snapshot
│   └── UncertaintySampler.java     # Weighted disagreement sampling
│
├── ui/                             # JavaFX panels and controls
│   ├── ClassificationPanel.java    # Main sidebar panel (train, confuse, sample, review)
│   ├── PopulationPanel.java        # Population set display with colour swatches
│   ├── ConfusionMatrixView.java    # Canvas confusion matrix with PNG export
│   ├── ReviewController.java       # Review queue logic + viewer navigation
│   ├── ReviewToolbar.java          # Next/Prev/Skip + cell type label buttons
│   ├── ChannelSelector.java        # Optional auto channel switching
│   └── FeatureSelectionPane.java   # Filterable feature checkbox list
│
└── io/                             # Import / export
    ├── MarkerTableImporter.java    # CSV → CellTypeTable
    ├── CellTableExporter.java      # Predictions + labels → CSV
    ├── GroundTruthIO.java          # Portable ground truth CSV export/import
    └── ProjectStateManager.java    # JSON + Base64 model persistence
```

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Build system | Gradle 9.2.1 (Kotlin DSL) + QuPath conventions plugin |
| Extension host | QuPath 0.7 |
| ML model 1 | XGBoost4J 3.2.0 |
| ML model 2 | LightGBM4J 4.6.0-2 |
| UI framework | JavaFX (bundled with QuPath) |
| Serialisation | JSON (Gson, bundled with QuPath) |

## Default Hyperparameters

| Parameter | Default | Range |
|-----------|---------|-------|
| Boosting rounds | 200 | 50–1000 |
| Max tree depth | 6 | 2–15 |
| Learning rate (eta) | 0.1 | — |
| Subsample | 0.8 | — |

These can be adjusted in the Classification Panel before training.

## License

[Add your license here]

## Acknowledgements

Inspired by [CellTune](https://doi.org/10.1101/2024.09.20.613828) by Zindy et al. Built on the [QuPath extension template](https://github.com/qupath/qupath-extension-template).
