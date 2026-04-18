# CellTune Classifier for QuPath

A [QuPath](https://qupath.github.io/) 0.7 extension that brings **CellTune-style active learning** to cell classification. It trains two gradient-boosted models (XGBoost + LightGBM) simultaneously, identifies cells where the models disagree, and presents those disputed cells for human review — creating an iterative loop that progressively improves classification accuracy.

No Python dependency is required. Everything runs inside QuPath using Java/JavaFX.

## Features

- **Dual-model disagreement detection** — XGBoost and LightGBM are trained on the same labels; cells where they disagree are flagged for review. Both models attempt GPU (CUDA) training automatically and fall back to CPU if unavailable.
- **Weighted uncertainty sampling with exploration** — the review sample is split into two parts. **90% disagreement cells:** cells where XGBoost and LightGBM predict different classes, weighted by confusion severity — class pairs with lower inter-model agreement (`w = 2 - agreementRate[i] - agreementRate[j]`) are sampled more heavily, focusing labelling time on the model's worst failure modes. **10% exploration cells:** agreement cells (both models agree) sampled uniformly at random. This exploration component guards against rare cell types that both models confidently misclassify — such cells never appear as disagreements and would otherwise be invisible to the active learning loop. Exploration cells appear at the end of the review queue so high-priority disagreements are reviewed first.
- **Interactive review mode** — navigate cell-by-cell through sampled disagreements; for each cell the toolbar shows the top prediction from each model (XGBoost & LightGBM) with confidence percentages as clickable buttons, plus an "All Classes" dropdown populated from the QuPath project class list for manual override
- **Confusion matrix visualisation** — Canvas-based inter-model confusion plot with per-class agreement rates, per-class F1 scores, macro F1, and PNG export
- **Manual Label Mode** — floating toolbar for direct cell labelling outside Review Mode. Click cells in the viewer and assign classes via buttons or an "All Classes" dropdown. Labels are written to the ground-truth LabelStore. Optional auto-advance selects the next detection automatically.
- **Docked sidebar panel** — Train, plot confusions, sample, and review all from a single panel docked in QuPath's analysis pane
- **Multi-image training data pooling** — optionally pool labelled cells from all project images into a single training set. A "Pool labels from all images" checkbox appears in the Classification Panel and training confirmation dialog. When enabled, the extension opens each project image, collects annotation-based labels (landmarks) plus persisted review and manual labels, extracts features using the same column ordering, and adds them as supplementary training rows. Per-image labels are automatically saved to `<project>/celltune/image-labels/` after training, review, and manual labelling sessions.
- **Resampling strategies** — address class imbalance before training with a selectable strategy: **SMOTE** (synthetic minority oversampling via k-NN interpolation), **ADASYN** (adaptive synthetic sampling weighted toward harder boundary examples), **Tomek links** (remove majority-class samples forming mutual nearest-neighbour pairs with minority samples), or combinations (**SMOTE + Tomek**, **ADASYN + Tomek**). A dropdown appears in both the Classification Panel and the training confirmation dialog. Training logs report the original and resampled class distributions.
- **Hyperparameter auto-tuning (TPE)** — optional Bayesian optimisation using Tree-structured Parzen Estimator (TPE) with stratified 5-fold cross-validation over boosting rounds, max depth, learning rate, and subsample ratio. Each model (XGBoost and LightGBM) is tuned independently — they may have different optimal settings. After an initial warm-up of random trials, subsequent trials are guided by kernel density estimates fitted to the best-performing observations. An "Auto-tune hyperparameters" checkbox appears in the Classification Panel and training dialog. When enabled, 20 TPE trials per model are tested before training, and the best-performing parameters are applied to each model independently.
- **Early stopping** — optional validation-loss-based early stopping to find the optimal number of boosting rounds. When enabled, 20% of the *original* (pre-resampling) training data is held out as a stratified validation set containing only real samples — resampling is applied only to the 80% training portion. Each model trains round-by-round while monitoring validation log-loss; if loss doesn't improve for 20 consecutive rounds (patience), training stops early. The optimal round count is then used when retraining on the full (resampled) dataset. An "Early stopping" checkbox appears alongside the auto-tune option.
- **Batch image classification** — after training, choose which project images to apply the trained classifier to via a dual-list image selector dialog
- **Training progress dialog** — real-time progress bar with scrollable log showing training phases, device info (GPU/CPU), and per-image classification status
- **Ground truth portability** — export/import labelled cells as CSV for cross-image or cross-project transfer (spatial matching or training-data-only modes)
- **Feature selection** — filterable, searchable feature selector handles panels with 2000+ measurements
- **Project state persistence** — classifier models, labels, and feature names are saved as JSON+Base64 in the QuPath project folder with timestamped backups. Per-image labels are saved separately for cross-image pooling.

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
5. **Train** — click *Train* in the CellTune panel (or *Extensions > CellTune Classifier > Run CellTune Classification…*). If features haven't been selected yet, you'll be prompted to select them or use all. A confirmation dialog shows the feature and label counts before training begins. If the project has multiple images, a dual-list image selector lets you choose which images to apply the trained classifier to. A progress dialog shows real-time training status including GPU/CPU device info.
6. **Plot confusions** — click *Plot Confusions* to see the inter-model confusion matrix with per-class agreement rates and F1 scores
7. **Sample & review** — click *Sample & Review*, choose a sample size (default 200), then *Enter Review Mode* to step through disputed cells. Each cell shows coloured prediction buttons (e.g. `XGB: CD4 (87%)`, `LGB: Bcell (65%)`) — click to accept. Use the *All Classes* dropdown if neither prediction is correct.
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
│   ├── XGBoostModel.java           # XGBoost4J wrapper (GPU/CUDA with CPU fallback)
│   ├── LightGBMModel.java          # LightGBM4J wrapper (GPU with CPU fallback)
│   ├── ClassifierState.java        # Serialisable model snapshot
│   ├── UncertaintySampler.java     # Weighted disagreement sampling
│   ├── Resampler.java              # SMOTE, ADASYN, Tomek links resampling
│   ├── ResamplingStrategy.java     # Enum of available resampling strategies
│   ├── HyperparameterTuner.java    # TPE Bayesian optimisation for auto-tuning
│
├── ui/                             # JavaFX panels and controls
│   ├── ClassificationPanel.java    # Main sidebar panel (train, confuse, sample, review)
│   ├── PopulationPanel.java        # Population set display with colour swatches
│   ├── ConfusionMatrixView.java    # Canvas confusion matrix with F1 scores + PNG export
│   ├── ReviewController.java       # Review queue logic + viewer navigation
│   ├── ReviewToolbar.java          # Nav buttons + dynamic prediction buttons + All Classes menu
│   ├── ChannelSelector.java        # Optional auto channel switching
│   ├── ImageSelectionPane.java     # Dual-list image selector for batch classification
│   ├── ManualLabelToolbar.java     # Floating toolbar for direct cell labelling
│   └── FeatureSelectionPane.java   # Filterable feature checkbox list
│
└── io/                             # Import / export
    ├── MarkerTableImporter.java    # CSV → CellTypeTable
    ├── CellTableExporter.java      # Predictions + labels → CSV
    ├── GroundTruthIO.java          # Portable ground truth CSV export/import
    └── ProjectStateManager.java    # JSON + Base64 model persistence + per-image label files
```

## Recommendations for HPC Deployment

When working with large images (500K+ cells, 2000+ features) on HPC or high-end GPU workstations, bear in mind the following.

### JVM Heap Memory (`-Xmx`)

The Java heap must be large enough to hold the feature matrix and native model copies in memory. Set **`-Xmx24g` or higher** in QuPath's launcher using one of these methods:

| Method | How |
|--------|-----|
| **QuPath Preferences** | Edit → Preferences → Max memory |
| **Setup options** | Help → Show setup options → edit max memory |
| **Config file** | Edit `QuPath.cfg` in the QuPath install directory — add `-Xmx24g` to the JVM options |
| **Command line** | Launch with `QuPath --Xmx=24g` |

The extension includes two built-in memory safeguards:

- **Startup check** — if the JVM heap is below 8 GiB, a notification warns the user to increase it via Edit → Preferences or Help → Show setup options
- **Pre-training check** — before training begins, the extension estimates the peak memory requirement from the cell count and feature count (matrix size × 3 for native copies + 0.3 GiB overhead). If the estimate exceeds 80% of the available heap, a confirmation dialog warns the user and offers to cancel.

### Resource Management

- **DMatrix cleanup** — `XGBoostModel` wraps all `DMatrix` objects in `try/finally` blocks with `dispose()` calls, preventing native memory leaks that would otherwise leave multi-GiB allocations lingering until garbage collection
- **Parallel feature extraction** — `CellFeatureExtractor.extractMatrix()` uses `IntStream.parallel()` to distribute measurement reads across all available CPU cores, significantly reducing extraction time on multi-core HPC nodes

### Scalability Limits

At 2000 features, the flat `float[]` matrix used by XGBoost4J and LightGBM4J imposes an array index limit of approximately **1.07 million cells** (Java arrays are indexed by `int`, max ~2.1 billion elements, so 2,147,483,647 / 2000 ≈ 1.07M rows). For datasets exceeding 1M cells, the feature matrix would need to be chunked into segments — a more involved change that is not yet implemented.

### GPU Notes

GPU acceleration depends on platform and the native libraries shipped in the Maven artifacts:

| Platform | XGBoost GPU | LightGBM GPU |
|----------|-------------|---------------|
| Linux x86_64 | ✔ CUDA | ✘ CPU-only |
| Windows x86_64 | ✘ CPU-only | ✘ CPU-only |
| macOS | ✘ CPU-only | ✘ CPU-only |

- **XGBoost** (`xgboost4j_2.13:3.2.0`): The Maven artifact ships CUDA-enabled native libs for **Linux only**. On Linux with a CUDA-capable GPU and the CUDA Toolkit 12.x installed, XGBoost trains on GPU automatically (`device=cuda`). On Windows and macOS, the bundled `xgboost4j.dll`/`libxgboost4j.dylib` is CPU-only — XGBoost silently falls back to CPU even if CUDA is installed. The extension detects this silent fallback and reports the actual device used. GPU training on Windows would require building XGBoost4J from source with CUDA support.
- **LightGBM** (`lightgbm4j:4.6.0-2`): The `lightgbm4j` Java binding ships a CPU-only native binary on **all platforms**. GPU training would require building LightGBM from source with `-DUSE_GPU=1` (OpenCL) and replacing the native library.

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

## TODO / Future Exploration

_(No outstanding items — all planned features have been implemented.)_

## License

[Add your license here]

## Acknowledgements

- **[CellTune](https://celltune.org/)** by the [Keren Lab](https://www.weizmann.ac.il/mcb/Keren/home) — the active learning cell classification workflow that this extension emulates. See [the CellTune preprint](https://www.biorxiv.org/content/10.1101/2025.05.05.652215v1).
- **[qupath-extension-xgboost](https://github.com/zindy/qupath-extension-xgboost)** by [Zindy](https://github.com/zindy) — a QuPath 0.7 XGBoost extension whose project structure, Gradle configuration, and XGBoost4J integration patterns served as a reference implementation for this extension.
- Built on the [QuPath extension template](https://github.com/qupath/qupath-extension-template).
