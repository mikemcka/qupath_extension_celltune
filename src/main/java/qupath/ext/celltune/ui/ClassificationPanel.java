package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import qupath.ext.celltune.classifier.DualModelClassifier;
import qupath.ext.celltune.classifier.ModelType;
import qupath.ext.celltune.classifier.ResamplingStrategy;
import qupath.ext.celltune.classifier.UncertaintySampler;
import qupath.ext.celltune.io.GroundTruthIO;
import qupath.ext.celltune.io.ProjectStateManager;
import qupath.ext.celltune.model.*;
import qupath.ext.celltune.model.FeatureNormalizer;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Main classifier sidebar panel that collects labels, trains both models,
 * and provides access to confusions, sampling, and review.
 * <p>
 * This panel is docked into QuPath's analysis pane and provides the
 * central hub for the entire CellTune workflow.
 */
public class ClassificationPanel extends VBox {

    private static final ResourceBundle STRINGS =
            ResourceBundle.getBundle("qupath.ext.celltune.ui.strings");

    private final QuPathGUI qupath;

    // ── State (references into extension state) ──
    private LabelStore labelStore;
    private DualModelClassifier classifier;
    private List<String> selectedFeatures;
    private CellTypeTable cellTypeTable;
    private PopulationSet predAll;
    private double[] lastAgreementRates;
    private List<String> lastSampledCellIds;
    private FeatureNormalizer featureNormalizer;
    private List<GroundTruthIO.TrainingRow> importedTrainingRows;
    private List<String> importedTrainingFeatureNames;

    // ── Callbacks ──
    private Consumer<LabelStore> onLabelStoreChanged;
    private Consumer<PopulationSet> onPredAllChanged;
    private Consumer<double[]> onAgreementRatesChanged;
    private Consumer<List<String>> onSampledCellsChanged;
    private Consumer<DualModelClassifier> onClassifierChanged;
    private Supplier<Boolean> autoClassifyCallback;

    // ── UI widgets ──
    private final Label statusLabel = new Label("Ready");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button trainButton = new Button();
    private final Button confusionsButton = new Button();
    private final Button sampleButton = new Button();
    private final Button reviewButton = new Button();
    private final CheckBox showFeatureImportanceCheckBox =
            new CheckBox("Show top 10 feature importance after training");
    private final Label labelCountLabel = new Label("Labels: 0");
    private final Label predCountLabel = new Label("Predictions: 0");
    private final Label importedCountLabel = new Label("Imported rows: 0");
    private final Spinner<Integer> roundsSpinner;
    private final Spinner<Integer> depthSpinner;
    private final CheckBox poolImagesCheckBox = new CheckBox("Pool labels from all images");
    private final ComboBox<ResamplingStrategy> resamplingCombo = new ComboBox<>();
    private final ComboBox<ModelType> model1Combo = new ComboBox<>();
    private final ComboBox<ModelType> model2Combo = new ComboBox<>();
    private final CheckBox autoTuneCheckBox = new CheckBox("Auto-tune hyperparameters");
    private final CheckBox earlyStopCheckBox = new CheckBox("Early stopping");
    private final PopulationPanel populationPanel = new PopulationPanel();

    // ── Binary mode banner (hidden by default) ──
    private final Label binaryBannerLabel = new Label();
    private final Button exitBinaryButton = new Button("Exit Binary Mode");
    private Runnable onExitBinaryMode;

    public ClassificationPanel(QuPathGUI qupath) {
        super(10);
        this.qupath = qupath;
        setPadding(new Insets(10));

        // ── Title ──
        Label title = new Label(STRINGS.getString("name"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        // ── Binary mode banner ──
        binaryBannerLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1976D2;");
        binaryBannerLabel.setVisible(false);
        binaryBannerLabel.setManaged(false);
        exitBinaryButton.setVisible(false);
        exitBinaryButton.setManaged(false);
        exitBinaryButton.setOnAction(e -> { if (onExitBinaryMode != null) onExitBinaryMode.run(); });

        // ── Hyperparameter controls ──
        roundsSpinner = new Spinner<>(50, 1000, 200, 50);
        roundsSpinner.setEditable(true);
        roundsSpinner.setPrefWidth(90);
        roundsSpinner.setTooltip(new Tooltip(STRINGS.getString("param.num_rounds.help")));

        depthSpinner = new Spinner<>(2, 15, 6, 1);
        depthSpinner.setEditable(true);
        depthSpinner.setPrefWidth(70);
        depthSpinner.setTooltip(new Tooltip(STRINGS.getString("param.max_depth.help")));

        HBox paramRow = new HBox(8,
                new Label(STRINGS.getString("param.num_rounds.label")), roundsSpinner,
                new Label(STRINGS.getString("param.max_depth.label")), depthSpinner);
        paramRow.setAlignment(Pos.CENTER_LEFT);

        // ── Pool images checkbox ──
        poolImagesCheckBox.setTooltip(new Tooltip(
                "Include labelled cells from all project images in the training set"));
        poolImagesCheckBox.setSelected(false);

        resamplingCombo.getItems().addAll(ResamplingStrategy.values());
        resamplingCombo.setValue(ResamplingStrategy.NONE);
        resamplingCombo.setMaxWidth(Double.MAX_VALUE);
        resamplingCombo.setTooltip(new Tooltip(
                "Resampling strategy to address class imbalance before training"));

        model1Combo.getItems().addAll(ModelType.values());
        model1Combo.setValue(ModelType.XGBOOST);
        model1Combo.setMaxWidth(Double.MAX_VALUE);
        model1Combo.setTooltip(new Tooltip("Model 1 type"));

        model2Combo.getItems().addAll(ModelType.values());
        model2Combo.setValue(ModelType.LIGHTGBM);
        model2Combo.setMaxWidth(Double.MAX_VALUE);
        model2Combo.setTooltip(new Tooltip("Model 2 type"));

        HBox modelRow = new HBox(8,
                new Label("Model 1:"), model1Combo,
                new Label("Model 2:"), model2Combo);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        autoTuneCheckBox.setSelected(false);
        autoTuneCheckBox.setTooltip(new Tooltip(
                "TPE Bayesian optimisation with cross-validation to find optimal hyperparameters independently per model"));

        earlyStopCheckBox.setSelected(false);
        earlyStopCheckBox.setTooltip(new Tooltip(
                "Find optimal boosting rounds via validation loss monitoring (patience=20)"));

        showFeatureImportanceCheckBox.setSelected(false);
        showFeatureImportanceCheckBox.setTooltip(new Tooltip(
                "After training, compute and display top 10 features by mean |SHAP| value per class"));

        // ── Status row ──
        HBox statsRow = new HBox(12, labelCountLabel, predCountLabel, importedCountLabel);
        statsRow.setAlignment(Pos.CENTER_LEFT);

        // ── Progress ──
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(18);
        HBox.setHgrow(progressBar, Priority.ALWAYS);
        statusLabel.setStyle("-fx-font-size: 11px;");

        // ── Buttons ──
        trainButton.setText(STRINGS.getString("classify.train.button"));
        trainButton.setMaxWidth(Double.MAX_VALUE);
        trainButton.setStyle("-fx-font-weight: bold;");
        trainButton.setOnAction(e -> doTrain());

        confusionsButton.setText(STRINGS.getString("classify.plot.button"));
        confusionsButton.setMaxWidth(Double.MAX_VALUE);
        confusionsButton.setDisable(true);
        confusionsButton.setOnAction(e -> doShowConfusions());

        sampleButton.setText(STRINGS.getString("classify.sample.button"));
        sampleButton.setMaxWidth(Double.MAX_VALUE);
        sampleButton.setDisable(true);
        sampleButton.setOnAction(e -> doSampleAndReview());

        reviewButton.setText(STRINGS.getString("menu.review").replace("...", ""));
        reviewButton.setMaxWidth(Double.MAX_VALUE);
        reviewButton.setDisable(true);
        reviewButton.setOnAction(e -> doEnterReview());

        HBox actionRow1 = new HBox(6, confusionsButton, sampleButton);
        HBox.setHgrow(confusionsButton, Priority.ALWAYS);
        HBox.setHgrow(sampleButton, Priority.ALWAYS);

        // ── Separator ──
        Separator sep = new Separator();

        // ── Layout ──
        getChildren().addAll(
                title,
                binaryBannerLabel,
                exitBinaryButton,
                paramRow,
                modelRow,
                poolImagesCheckBox,
                resamplingCombo,
                autoTuneCheckBox,
                earlyStopCheckBox,
                showFeatureImportanceCheckBox,
                new Separator(),
                statsRow,
                trainButton,
                progressBar,
                statusLabel,
                new Separator(),
                actionRow1,
                reviewButton,
                sep,
                populationPanel
        );
    }

    // ── Binary mode indicator ──

    /** Show or hide the binary mode banner in the docked panel. */
    public void setActiveBinaryMarker(String markerName) {
        boolean active = (markerName != null && !markerName.isBlank());
        binaryBannerLabel.setText(active ? "Active binary mode: " + markerName : "");
        binaryBannerLabel.setVisible(active);
        binaryBannerLabel.setManaged(active);
        exitBinaryButton.setVisible(active);
        exitBinaryButton.setManaged(active);
    }

    public void setOnExitBinaryMode(Runnable cb) {
        this.onExitBinaryMode = cb;
    }

    // ── State setters (called by CellTuneExtension to share state) ──

    public void setLabelStore(LabelStore store) {
        this.labelStore = store;
        refreshStats();
    }
    public void setClassifier(DualModelClassifier cls) { this.classifier = cls; }
    public void setFeatureNormalizer(FeatureNormalizer normalizer) { this.featureNormalizer = normalizer; }
    public void setSelectedFeatures(List<String> features) { this.selectedFeatures = features; }
    public void setCellTypeTable(CellTypeTable table) { this.cellTypeTable = table; }
    public void setPredAll(PopulationSet pa) {
        this.predAll = pa;
        refreshStats();
    }
    public void setLastAgreementRates(double[] rates) { this.lastAgreementRates = rates; }
    public void setLastSampledCellIds(List<String> ids) {
        this.lastSampledCellIds = ids;
        reviewButton.setDisable(ids == null || ids.isEmpty());
    }
    public void setImportedTrainingData(List<GroundTruthIO.TrainingRow> rows, List<String> featureNames) {
        this.importedTrainingRows = rows == null ? null : List.copyOf(rows);
        this.importedTrainingFeatureNames = featureNames == null ? null : List.copyOf(featureNames);
        refreshStats();
    }

    // ── Callbacks ──

    public void setOnLabelStoreChanged(Consumer<LabelStore> cb) { this.onLabelStoreChanged = cb; }
    public void setOnPredAllChanged(Consumer<PopulationSet> cb) { this.onPredAllChanged = cb; }
    public void setOnAgreementRatesChanged(Consumer<double[]> cb) { this.onAgreementRatesChanged = cb; }
    public void setOnSampledCellsChanged(Consumer<List<String>> cb) { this.onSampledCellsChanged = cb; }
    public void setOnClassifierChanged(Consumer<DualModelClassifier> cb) { this.onClassifierChanged = cb; }
    public void setAutoClassifyCallback(Supplier<Boolean> cb) { this.autoClassifyCallback = cb; }

    // ── Actions ──

    private void doTrain() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(STRINGS.getString("name"), "No image is open.");
            return;
        }

        Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) {
            Dialogs.showErrorMessage(STRINGS.getString("name"),
                    "No detections found. Run cell detection first.");
            return;
        }


        // Collect labels from annotations
        if (labelStore == null) labelStore = new LabelStore("CellTune");
        collectLabelsFromAnnotations(labelStore);

        // Sync labels with current QuPath class list — remove labels for deleted classes
        var project = qupath.getProject();
        Set<String> validClasses = new LinkedHashSet<>();
        if (project != null) {
            for (var pc : project.getPathClasses()) {
                if (pc != null && pc.getName() != null && !pc.getName().isEmpty()) {
                    validClasses.add(pc.getName());
                }
            }
            if (!validClasses.isEmpty()) {
                labelStore.retainClasses(validClasses);
            }
        }

        // If not enough labels, try to reload from saved state
        if (labelStore.size() < 10) {
            if (project != null && imageData != null) {
                var imgEntry = project.getEntry(imageData);
                if (imgEntry != null) {
                    try {
                        var savedLabels = ProjectStateManager.loadImageLabels(project, imgEntry.getImageName());
                        if (savedLabels != null && savedLabels.size() > labelStore.size()) {
                            labelStore.mergeFrom(savedLabels);
                            // Re-sync after merge
                            if (!validClasses.isEmpty()) {
                                labelStore.retainClasses(validClasses);
                            }
                        }
                    } catch (Exception ex) {
                        // Ignore errors
                    }
                }
                // Also try the global classifier state labels
                if (labelStore.size() < 10) {
                    try {
                        var state = ProjectStateManager.loadState(project);
                        if (state != null && state.labels != null && state.labels.size() > labelStore.size()) {
                            labelStore.mergeFrom(new LabelStore("saved", state.labels));
                            if (!validClasses.isEmpty()) {
                                labelStore.retainClasses(validClasses);
                            }
                        }
                    } catch (Exception ex) {
                        // Ignore errors
                    }
                }
            }
        }

        if (labelStore.size() < 10) {
            Dialogs.showErrorMessage(STRINGS.getString("name"),
                    "Need at least 10 labelled cells to train. Found: " + labelStore.size() + "\nUse point annotations placed on detections to label cells.");
            return;
        }

        // Discover features
        List<String> featureNames = CellFeatureExtractor.discoverFeatureNames(detections);
        if (featureNames.isEmpty()) {
            Dialogs.showErrorMessage(STRINGS.getString("name"), "No cell measurements found.");
            return;
        }

        // Apply user feature selection
        if (selectedFeatures != null && !selectedFeatures.isEmpty()) {
            featureNames = featureNames.stream()
                    .filter(selectedFeatures::contains)
                    .collect(Collectors.toList());
            if (featureNames.isEmpty()) {
                Dialogs.showErrorMessage(STRINGS.getString("name"),
                        "None of the selected features are present.");
                return;
            }
        }

        CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
        extractor.setNormalizer(featureNormalizer);
        if (classifier == null) classifier = new DualModelClassifier();

        // Apply model types from combos
        classifier.setModel1Type(model1Combo.getValue());
        classifier.setModel2Type(model2Combo.getValue());

        // Apply hyperparameters from spinners
        classifier.setNumRounds(roundsSpinner.getValue());
        classifier.setMaxDepth(depthSpinner.getValue());

        // Reset and bind progress
        progressBar.progressProperty().unbind();
        progressBar.setProgress(0);
        classifier.resetProgress();
        progressBar.progressProperty().bind(classifier.progressProperty());
        statusLabel.textProperty().bind(classifier.statusProperty());
        trainButton.setDisable(true);

        // Train in background
        final LabelStore storeCopy = labelStore;
        final boolean poolAllImages = poolImagesCheckBox.isSelected();
        final ResamplingStrategy resamplingStrategy = resamplingCombo.getValue();
        final boolean autoTuneSelected = autoTuneCheckBox.isSelected();
        final boolean earlyStopSelected = earlyStopCheckBox.isSelected();
        final List<String> finalFeatureNames = featureNames;
        final var projectRef = project;
        final List<GroundTruthIO.TrainingRow> importedRowsSnapshot =
            importedTrainingRows == null ? null : List.copyOf(importedTrainingRows);
        final List<String> importedFeatureNamesSnapshot =
            importedTrainingFeatureNames == null ? null : List.copyOf(importedTrainingFeatureNames);
        Thread trainThread = new Thread(() -> {
            try {
                // Auto-backup labels
                if (projectRef != null) {
                    ProjectStateManager.backupLabels(projectRef, storeCopy);
                }

                // Collect supplementary training data from other project images
                List<float[]> supplementaryRows = null;
                List<String> supplementaryLabels = null;

                if (poolAllImages && projectRef != null) {
                    supplementaryRows = new ArrayList<>();
                    supplementaryLabels = new ArrayList<>();

                    @SuppressWarnings("unchecked")
                    var allEntries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) projectRef.getImageList();
                    var currentEntry = projectRef.getEntry(imageData);

                    for (var entry : allEntries) {
                        if (currentEntry != null && entry.equals(currentEntry)) continue;

                        // Fast check: skip images with no saved label file to avoid
                        // the expensive readImageData() call for unlabelled images.
                        if (!ProjectStateManager.hasImageLabels(projectRef, entry.getImageName())) continue;

                        try {
                            // Load saved labels first (no image I/O required)
                            LabelStore otherLabels = new LabelStore("temp");
                            try {
                                var savedLabels = ProjectStateManager.loadImageLabels(
                                        projectRef, entry.getImageName());
                                if (savedLabels != null) {
                                    otherLabels.mergeFrom(savedLabels);
                                }
                            } catch (Exception lsEx) {
                                // No saved labels for this image — that's fine
                            }

                            if (otherLabels.size() == 0) continue;

                            // Only now open the image to extract features
                            var otherImageData = entry.readImageData();
                            var otherHierarchy = otherImageData.getHierarchy();
                            var otherDetections = otherHierarchy.getDetectionObjects();
                            if (otherDetections.isEmpty()) continue;

                            var otherExtractor = new CellFeatureExtractor(finalFeatureNames);
                            otherExtractor.setNormalizer(featureNormalizer);
                            Map<String, PathObject> otherCellById = new LinkedHashMap<>();
                            for (PathObject cell : otherDetections) {
                                otherCellById.put(cell.getID().toString(), cell);
                            }

                            int added = 0;
                            for (var labelEntry : otherLabels.getAllLabels().entrySet()) {
                                PathObject cell = otherCellById.get(labelEntry.getKey());
                                if (cell == null) continue;
                                supplementaryRows.add(otherExtractor.extractRow(cell));
                                supplementaryLabels.add(labelEntry.getValue());
                                added++;
                            }
                        } catch (Exception ex) {
                            // Skip images that can't be read
                        }
                    }
                }

                // Always include explicitly imported training rows (if any).
                if (importedRowsSnapshot != null && !importedRowsSnapshot.isEmpty()
                        && importedFeatureNamesSnapshot != null && !importedFeatureNamesSnapshot.isEmpty()) {

                    if (supplementaryRows == null) supplementaryRows = new ArrayList<>();
                    if (supplementaryLabels == null) supplementaryLabels = new ArrayList<>();

                    int[] featureMap = buildFeatureIndexMap(importedFeatureNamesSnapshot, finalFeatureNames);
                    int mappedFeatureCount = 0;
                    for (int idx : featureMap) {
                        if (idx >= 0) mappedFeatureCount++;
                    }

                    if (mappedFeatureCount > 0) {
                        int added = 0;
                        for (var row : importedRowsSnapshot) {
                            if (row == null || row.label() == null || row.label().isBlank()) continue;
                            float[] src = row.features();
                            if (src == null) continue;

                            float[] aligned = new float[finalFeatureNames.size()];
                            for (int f = 0; f < aligned.length; f++) {
                                int srcIdx = featureMap[f];
                                if (srcIdx >= 0 && srcIdx < src.length) {
                                    float val = src[srcIdx];
                                    aligned[f] = Float.isFinite(val) ? val : 0f;
                                }
                            }

                            supplementaryRows.add(aligned);
                            supplementaryLabels.add(row.label());
                            added++;
                        }
                    }

                    // Imported rows are now available as supplementary training data.
                }

                classifier.trainAndPredict(detections, storeCopy, extractor,
                        supplementaryRows, supplementaryLabels,
                        resamplingStrategy,
                        autoTuneSelected,
                        earlyStopSelected,
                        msg -> {});

                predAll = classifier.getPredALL();

                // Save classifier state
                if (projectRef != null) {
                    var state = classifier.toClassifierState("CellTune");
                    ProjectStateManager.saveState(projectRef, state.getName(),
                            storeCopy, state.getFeatureNames(), state.getClassNames(),
                            state.getXgboostBytes(), state.getLightgbmBytes(),
                            state.getRfModel1Bytes(), state.getRfModel2Bytes(),
                            state.getModel1Type(), state.getModel2Type(),
                            importedFeatureNamesSnapshot, importedRowsSnapshot);

                    // Save per-image labels for cross-image pooling
                    var imgEntry = projectRef.getEntry(imageData);
                    if (imgEntry != null) {
                        var filteredStore = filterLabelStoreToImage(storeCopy, imageData);
                        ProjectStateManager.saveImageLabels(
                                projectRef, imgEntry.getImageName(), filteredStore);
                    }
                }

                Platform.runLater(() -> {
                    progressBar.progressProperty().unbind();
                    statusLabel.textProperty().unbind();
                    trainButton.setDisable(false);
                    confusionsButton.setDisable(false);
                    sampleButton.setDisable(false);

                    // Auto-show feature importance if checkbox is selected
                    if (showFeatureImportanceCheckBox.isSelected()) {
                        doShowFeatureImportance();
                    }

                    // Update spinners if auto-tuned
                    // (Tuned params are now per-model; spinners retain user defaults)

                    imageData.getHierarchy().fireHierarchyChangedEvent(this);

                    // Update population panel
                    populationPanel.update(
                            classifier.getPredMDL1(), classifier.getPredMDL2(),
                            classifier.getPredAVG(), classifier.getPredALL(),
                            classifier.getClassNames());

                    refreshStats();

                    // Notify extension
                    if (onLabelStoreChanged != null) onLabelStoreChanged.accept(storeCopy);
                    if (onPredAllChanged != null) onPredAllChanged.accept(predAll);
                    if (onClassifierChanged != null) onClassifierChanged.accept(classifier);

                    Dialogs.showInfoNotification(STRINGS.getString("name"),
                            "Training complete. " + predAll.size() + " cells classified, "
                            + predAll.getDisagreementCount() + " disagreements.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    progressBar.progressProperty().unbind();
                    statusLabel.textProperty().unbind();
                    progressBar.setProgress(0);
                    statusLabel.setText("Training failed");
                    trainButton.setDisable(false);
                    Dialogs.showErrorMessage(STRINGS.getString("name"),
                            "Training failed: " + ex.getMessage());
                });
            }
        }, "CellTune-Training");
        trainThread.setDaemon(true);
        trainThread.start();
    }

    private void doShowConfusions() {
        // Auto-classify current image if a trained classifier exists
        if ((predAll == null || predAll.size() == 0) && autoClassifyCallback != null) {
            if (autoClassifyCallback.get()) {
                // predAll was updated via setPredAll callback
            }
        }
        if (predAll == null || predAll.size() == 0 || classifier == null) {
            Dialogs.showErrorMessage(STRINGS.getString("name"),
                    "No predictions available. Train first.");
            return;
        }

        List<String> classNames = classifier.getClassNames();
        var view = new ConfusionMatrixView(qupath.getStage(), predAll, classNames);
        lastAgreementRates = view.getAgreementRates();
        if (onAgreementRatesChanged != null) onAgreementRatesChanged.accept(lastAgreementRates);
        view.show();
    }

    private void doShowFeatureImportance() {
        var imageData = qupath.getImageData();
        if (imageData == null || classifier == null) return;

        Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) {
            Dialogs.showErrorMessage(STRINGS.getString("name"), "No detections found.");
            return;
        }

        List<String> featureNames = classifier.getFeatureNames();
        if (featureNames == null || featureNames.isEmpty()) return;

        CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
        extractor.setNormalizer(featureNormalizer);

        showFeatureImportanceCheckBox.setDisable(true);
        Thread worker = new Thread(() -> {
            try {
                var result = classifier.computeFeatureImportance(detections, extractor);
                Platform.runLater(() -> {
                    showFeatureImportanceCheckBox.setDisable(false);
                    new FeatureImportanceView(qupath.getStage(), result).show();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    showFeatureImportanceCheckBox.setDisable(false);
                    Dialogs.showErrorMessage(STRINGS.getString("name"),
                            "Feature importance failed: " + ex.getMessage());
                });
            }
        }, "CellTune-FeatureImportance");
        worker.setDaemon(true);
        worker.start();
    }

    private void doSampleAndReview() {
        // Auto-classify current image if a trained classifier exists
        if (predAll == null && autoClassifyCallback != null) {
            autoClassifyCallback.get();
        }
        if (predAll == null || classifier == null) return;

        long disagreeCount = predAll.getDisagreementCount();
        if (disagreeCount == 0) {
            Dialogs.showInfoNotification(STRINGS.getString("name"),
                    "Perfect agreement — no disagreement cells to sample.");
            return;
        }

        // If no confusion matrix has been shown yet, compute agreement rates now
        if (lastAgreementRates == null) {
            var view = new ConfusionMatrixView(qupath.getStage(), predAll, classifier.getClassNames());
            lastAgreementRates = view.getAgreementRates();
            if (onAgreementRatesChanged != null) onAgreementRatesChanged.accept(lastAgreementRates);
            view.show();
        }

        String countStr = Dialogs.showInputDialog(
                STRINGS.getString("sample.dialog.title"),
                STRINGS.getString("sample.count.label")
                        + " (" + disagreeCount + " disagreements available)",
                "200");
        if (countStr == null) return;

        int sampleSize;
        try {
            sampleSize = Integer.parseInt(countStr.strip());
        } catch (NumberFormatException e) {
            Dialogs.showErrorMessage(STRINGS.getString("name"), "Invalid number.");
            return;
        }
        if (sampleSize <= 0) return;

        lastSampledCellIds = UncertaintySampler.sample(
                predAll, classifier.getClassNames(), lastAgreementRates, sampleSize);

        reviewButton.setDisable(lastSampledCellIds.isEmpty());
        if (onSampledCellsChanged != null) onSampledCellsChanged.accept(lastSampledCellIds);

        // Persist sampled cell IDs for this image
        var project = qupath.getProject();
        var imageData = qupath.getImageData();
        if (project != null && imageData != null) {
            var imgEntry = project.getEntry(imageData);
            if (imgEntry != null && lastSampledCellIds != null && !lastSampledCellIds.isEmpty()) {
                try {
                    ProjectStateManager.saveImageSampledCells(project, imgEntry.getImageName(), lastSampledCellIds);
                } catch (Exception ex) {
                    // Ignore errors
                }
            }
        }

        Dialogs.showInfoNotification(STRINGS.getString("name"),
                "Sampled " + lastSampledCellIds.size() + " cells. Use 'Enter Review Mode' to start.");
    }

    private void doEnterReview() {
        // Auto-classify current image if a trained classifier exists
        if (predAll == null && autoClassifyCallback != null) {
            autoClassifyCallback.get();
        }

        // Always prompt the user to choose how many cells to review
        if (predAll != null && predAll.size() > 0 && classifier != null) {
            long disagreeCount = predAll.getDisagreementCount();
            if (disagreeCount == 0) {
                Dialogs.showInfoNotification(STRINGS.getString("name"),
                        "Perfect agreement — no disagreement cells to review.");
                return;
            }
            // Compute agreement rates if not yet available
            if (lastAgreementRates == null) {
                var confView = new ConfusionMatrixView(qupath.getStage(), predAll, classifier.getClassNames());
                lastAgreementRates = confView.getAgreementRates();
                if (onAgreementRatesChanged != null) onAgreementRatesChanged.accept(lastAgreementRates);
            }
            String countStr = Dialogs.showInputDialog(
                    STRINGS.getString("sample.dialog.title"),
                    "How many disagreement cells to review?"
                            + " (" + disagreeCount + " available)",
                    "200");
            if (countStr == null) return;
            int sampleSize;
            try {
                sampleSize = Integer.parseInt(countStr.strip());
            } catch (NumberFormatException e) {
                Dialogs.showErrorMessage(STRINGS.getString("name"), "Invalid number.");
                return;
            }
            if (sampleSize <= 0) return;
            lastSampledCellIds = UncertaintySampler.sample(
                    predAll, classifier.getClassNames(), lastAgreementRates, sampleSize);
            reviewButton.setDisable(lastSampledCellIds.isEmpty());
            if (onSampledCellsChanged != null) onSampledCellsChanged.accept(lastSampledCellIds);

            // Persist sampled cell IDs
            var project = qupath.getProject();
            var imageData = qupath.getImageData();
            if (project != null && imageData != null) {
                var imgEntry = project.getEntry(imageData);
                if (imgEntry != null) {
                    try {
                        ProjectStateManager.saveImageSampledCells(project, imgEntry.getImageName(), lastSampledCellIds);
                    } catch (Exception ex) {
                        // Ignore errors
                    }
                }
            }
        }

        if (lastSampledCellIds == null || lastSampledCellIds.isEmpty()) {
            Dialogs.showErrorMessage(STRINGS.getString("name"), STRINGS.getString("review.no_sample"));
            return;
        }
        if (predAll == null) return;

        var reviewController = new ReviewController(qupath, lastSampledCellIds, predAll);
        if (reviewController.size() == 0) {
            Dialogs.showErrorMessage(STRINGS.getString("name"),
                    "Could not resolve any sampled cells in the current image.");
            return;
        }

        var channelSelector = new ChannelSelector(qupath, cellTypeTable);
        var toolbar = new ReviewToolbar(reviewController, cellTypeTable, channelSelector);

        var vbox = new javafx.scene.layout.VBox(6);
        vbox.setPadding(new javafx.geometry.Insets(6));
        vbox.getChildren().addAll(toolbar, channelSelector.getCheckBox());

        var stage = new javafx.stage.Stage();
        stage.setTitle(STRINGS.getString("review.stage.title"));
        stage.initOwner(qupath.getStage());
        stage.setScene(new javafx.scene.Scene(vbox));
        stage.setAlwaysOnTop(true);
        stage.setResizable(true);
        stage.setMinWidth(750);

        stage.setOnHidden(e -> {
            reviewController.removeHighlight();
            var outputLabels = reviewController.getOutputLabels();
            if (outputLabels.size() > 0) {
                if (labelStore == null) labelStore = new LabelStore("CellTune");
                labelStore.mergeFrom(outputLabels);
                refreshStats();
                if (onLabelStoreChanged != null) onLabelStoreChanged.accept(labelStore);

                // Persist per-image labels for cross-image pooling
                var project = qupath.getProject();
                var imgData = qupath.getImageData();
                if (project != null && imgData != null) {
                    var imgEntry = project.getEntry(imgData);
                    if (imgEntry != null) {
                        try {
                            // Filter to only IDs belonging to this image
                            var filteredStore = filterLabelStoreToImage(labelStore, imgData);
                            ProjectStateManager.saveImageLabels(
                                    project, imgEntry.getImageName(), filteredStore);
                        } catch (Exception ignored) {}
                    }
                }

                Dialogs.showInfoNotification(STRINGS.getString("name"),
                        String.format("Review complete: %d labels merged.", outputLabels.size()));
            }
        });

        stage.show();
    }

    // ── Helpers ──

    private void refreshStats() {
        int labelCount = (labelStore != null) ? labelStore.size() : 0;
        int predCount = (predAll != null) ? predAll.size() : 0;
        int importedCount = (importedTrainingRows != null) ? importedTrainingRows.size() : 0;
        labelCountLabel.setText("Labels: " + labelCount);
        predCountLabel.setText("Predictions: " + predCount);
        importedCountLabel.setText("Imported rows: " + importedCount);
    }

    private void collectLabelsFromAnnotations(LabelStore store) {
        var imageData = qupath.getImageData();
        if (imageData == null) return;
        collectLabelsFromHierarchy(imageData.getHierarchy(), store);
    }

    private static void collectLabelsFromHierarchy(
            qupath.lib.objects.hierarchy.PathObjectHierarchy hierarchy, LabelStore store) {
        for (PathObject anno : hierarchy.getAnnotationObjects()) {
            if (anno.getPathClass() == null || anno.getROI() == null) continue;
            // Only point annotations count as ground truth — area/region annotations
            // describe tissue regions, not individual cell labels.
            if (!anno.getROI().isPoint()) continue;
            String cls = anno.getPathClass().toString();

            List<PathObject> hits = new java.util.ArrayList<>();
            for (var pt : anno.getROI().getAllPoints()) {
                hits.addAll(PathObjectTools.getObjectsForLocation(
                        hierarchy, pt.getX(), pt.getY(),
                        anno.getROI().getZ(), anno.getROI().getT(), -1));
            }

            for (PathObject det : hits) {
                if (det.isDetection()) {
                    store.setLabel(det.getID().toString(), cls);
                }
            }
        }
    }

    /**
     * Create a copy of the label store containing only cell IDs that exist
     * as detections in the given image.
     */
    private static LabelStore filterLabelStoreToImage(
            LabelStore store,
            qupath.lib.images.ImageData<java.awt.image.BufferedImage> imageData) {
        var detections = imageData.getHierarchy().getDetectionObjects();
        var validIds = new java.util.HashSet<String>(detections.size());
        for (var det : detections) {
            validIds.add(det.getID().toString());
        }
        var filtered = new LabelStore(store.getName());
        for (var entry : store.getAllLabels().entrySet()) {
            if (validIds.contains(entry.getKey())) {
                filtered.setLabel(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private static int[] buildFeatureIndexMap(List<String> sourceFeatureNames,
                                              List<String> targetFeatureNames) {
        Map<String, Integer> sourceByName = new HashMap<>();
        for (int i = 0; i < sourceFeatureNames.size(); i++) {
            sourceByName.put(sourceFeatureNames.get(i).strip().toLowerCase(Locale.ROOT), i);
        }

        int[] map = new int[targetFeatureNames.size()];
        for (int i = 0; i < targetFeatureNames.size(); i++) {
            String key = targetFeatureNames.get(i).strip().toLowerCase(Locale.ROOT);
            map[i] = sourceByName.getOrDefault(key, -1);
        }
        return map;
    }
}
