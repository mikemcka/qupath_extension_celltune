# CellTune QuPath Extension — Agent Instructions

A QuPath 0.7 extension for human-in-the-loop cell classification using dual-model active learning (XGBoost + LightGBM + optional Random Forest). No Python dependency — everything runs in Java/JavaFX.

## Build

**Requires:** JDK 25 (see [README.md](README.md#prerequisites))

```bash
export JAVA_HOME=/path/to/jdk-25   # adjust to your install
./gradlew shadowJar
# Output: build/libs/qupath-extension-celltune-0.1.0-SNAPSHOT-all.jar
```

- The `shadowJar` task produces a single fat JAR (~51 MB) bundling XGBoost4J and LightGBM4J.
- The Gradle wrapper (`gradlew`) is included — no separate Gradle install needed.
- `settings.gradle.kts` pins QuPath version (`0.7.0`) and uses the `foojay-resolver-convention` plugin for JDK toolchain resolution.

## Architecture

Entry point: `src/main/java/qupath/ext/celltune/CellTuneExtension.java` — registers menus, docks the sidebar panel, and manages project-level state.

| Package | Purpose |
|---------|---------|
| `model/` | Feature extraction, label storage, normalization, cell predictions |
| `classifier/` | ML training/inference (XGBoost, LightGBM, Random Forest), sampling, resampling, hyperparameter tuning |
| `gating/` | Marker-based landmark gating (AST expression parser, multi-threshold cascade) |
| `ui/` | All JavaFX panels, dialogs, and toolbars |
| `io/` | Export (cell table, AnnData, ground truth CSV) and import (marker table, project state) |

See [celltune-qupath-structure.md](celltune-qupath-structure.md) for the full component-level build plan.

## Key Conventions

- **UI thread safety**: Background training/prediction threads must batch all QuPath object updates via `Platform.runLater()`. Never call `setPathClass()` or fire hierarchy events from a background thread directly.
- **QuPath public API only**: Use only `qupath.lib.*` public APIs. No internal or deprecated APIs. APIs to watch: `PathObjectSelectionModel`, `PathClass.fromString()`, `project.getEntry(imageData)`, `qupath.getAnalysisTabPane()`.
- **Null-check project entries**: `project.getEntry(imageData)` returns null when an image is open without a project — always null-check.
- **LabelStore is not thread-safe**: `LabelStore` uses a plain `LinkedHashMap`. Do not access it concurrently from training threads and UI threads.
- **Serialization**: Classifier state and per-image labels are saved as JSON+Base64 via Gson to `<project>/celltune/`. Class: `io/ProjectStateManager.java`.
- **Feature column ordering**: Feature vectors must use the same column ordering at training and inference time. `CellFeatureExtractor` handles this — do not bypass it.

## Known Pitfalls

- **LightGBM SHAP crashes the JVM**: Calling `LGBMBooster.predictForMat(... C_API_PREDICT_CONTRIB)` causes a fatal SIGSEGV. `LightGBMModel.computeMeanAbsShap()` exists but is intentionally not called. Do not re-enable LightGBM SHAP without testing in isolation first.
- **XGBoost4J version lock**: The Java API changed significantly between 1.x, 2.x, and 3.x. Do not upgrade `xgboost4j-gpu_2.13` without thorough testing. `predictContrib()` (TreeSHAP) is only public in 3.x.
- **Binary classification SHAP**: For exactly 2 classes, feature importance bars are identical for both classes — this is expected (see [RISKS.md](RISKS.md#24-binary-classification-shap-display)).

Full risk register: [RISKS.md](RISKS.md)

## how to talk to me
I only know python, I am 7
