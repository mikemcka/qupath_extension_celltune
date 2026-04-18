package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import qupath.ext.celltune.classifier.DualModelClassifier;
import qupath.ext.celltune.classifier.UncertaintySampler;
import qupath.ext.celltune.io.ProjectStateManager;
import qupath.ext.celltune.model.*;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;
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

    // ── Callbacks ──
    private Consumer<LabelStore> onLabelStoreChanged;
    private Consumer<PopulationSet> onPredAllChanged;
    private Consumer<double[]> onAgreementRatesChanged;
    private Consumer<List<String>> onSampledCellsChanged;
    private Consumer<DualModelClassifier> onClassifierChanged;

    // ── UI widgets ──
    private final Label statusLabel = new Label("Ready");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button trainButton = new Button();
    private final Button confusionsButton = new Button();
    private final Button sampleButton = new Button();
    private final Button reviewButton = new Button();
    private final Label labelCountLabel = new Label("Labels: 0");
    private final Label predCountLabel = new Label("Predictions: 0");
    private final Spinner<Integer> roundsSpinner;
    private final Spinner<Integer> depthSpinner;
    private final CheckBox poolImagesCheckBox = new CheckBox("Pool labels from all images");
    private final PopulationPanel populationPanel = new PopulationPanel();

    public ClassificationPanel(QuPathGUI qupath) {
        super(10);
        this.qupath = qupath;
        setPadding(new Insets(10));

        // ── Title ──
        Label title = new Label(STRINGS.getString("name"));
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

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

        // ── Status row ──
        HBox statsRow = new HBox(12, labelCountLabel, predCountLabel);
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
                paramRow,
                poolImagesCheckBox,
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

    // ── State setters (called by CellTuneExtension to share state) ──

    public void setLabelStore(LabelStore store) {
        this.labelStore = store;
        refreshStats();
    }
    public void setClassifier(DualModelClassifier cls) { this.classifier = cls; }
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

    // ── Callbacks ──

    public void setOnLabelStoreChanged(Consumer<LabelStore> cb) { this.onLabelStoreChanged = cb; }
    public void setOnPredAllChanged(Consumer<PopulationSet> cb) { this.onPredAllChanged = cb; }
    public void setOnAgreementRatesChanged(Consumer<double[]> cb) { this.onAgreementRatesChanged = cb; }
    public void setOnSampledCellsChanged(Consumer<List<String>> cb) { this.onSampledCellsChanged = cb; }
    public void setOnClassifierChanged(Consumer<DualModelClassifier> cb) { this.onClassifierChanged = cb; }

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

        if (labelStore.size() < 10) {
            Dialogs.showErrorMessage(STRINGS.getString("name"),
                    "Need at least 10 labelled cells. Found: " + labelStore.size());
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
        if (classifier == null) classifier = new DualModelClassifier();

        // Apply hyperparameters from spinners
        classifier.setNumRounds(roundsSpinner.getValue());
        classifier.setMaxDepth(depthSpinner.getValue());

        // Bind progress
        progressBar.progressProperty().bind(classifier.progressProperty());
        statusLabel.textProperty().bind(classifier.statusProperty());
        trainButton.setDisable(true);

        // Train in background
        final LabelStore storeCopy = labelStore;
        final boolean poolAllImages = poolImagesCheckBox.isSelected();
        final List<String> finalFeatureNames = featureNames;
        Thread trainThread = new Thread(() -> {
            try {
                // Auto-backup labels
                var project = qupath.getProject();
                if (project != null) {
                    ProjectStateManager.backupLabels(project, storeCopy);
                }

                // Collect supplementary training data from other project images
                List<float[]> supplementaryRows = null;
                List<String> supplementaryLabels = null;

                if (poolAllImages && project != null) {
                    supplementaryRows = new ArrayList<>();
                    supplementaryLabels = new ArrayList<>();

                    @SuppressWarnings("unchecked")
                    var allEntries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
                    var currentEntry = project.getEntry(imageData);

                    for (var entry : allEntries) {
                        if (currentEntry != null && entry.equals(currentEntry)) continue;

                        try {
                            var otherImageData = entry.readImageData();
                            var otherHierarchy = otherImageData.getHierarchy();
                            var otherDetections = otherHierarchy.getDetectionObjects();
                            if (otherDetections.isEmpty()) continue;

                            // Collect labels from annotations (landmarks)
                            LabelStore otherLabels = new LabelStore("temp");
                            collectLabelsFromHierarchy(otherHierarchy, otherLabels);

                            // Also load per-image saved labels which include
                            // reviewed cells and manually labelled cells
                            try {
                                var savedLabels = ProjectStateManager.loadImageLabels(
                                        project, entry.getImageName());
                                if (savedLabels != null) {
                                    otherLabels.mergeFrom(savedLabels);
                                }
                            } catch (Exception lsEx) {
                                // No saved labels for this image — that's fine
                            }

                            if (otherLabels.size() == 0) continue;

                            var otherExtractor = new CellFeatureExtractor(finalFeatureNames);
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

                classifier.trainAndPredict(detections, storeCopy, extractor,
                        supplementaryRows, supplementaryLabels,
                        msg -> {});

                predAll = classifier.getPredALL();

                // Save classifier state
                if (project != null) {
                    var state = classifier.toClassifierState("CellTune");
                    ProjectStateManager.saveState(project, state.getName(),
                            storeCopy, state.getFeatureNames(), state.getClassNames(),
                            state.getXgboostBytes(), state.getLightgbmBytes());

                    // Save per-image labels for cross-image pooling
                    var imgEntry = project.getEntry(imageData);
                    if (imgEntry != null) {
                        ProjectStateManager.saveImageLabels(
                                project, imgEntry.getImageName(), storeCopy);
                    }
                }

                Platform.runLater(() -> {
                    progressBar.progressProperty().unbind();
                    statusLabel.textProperty().unbind();
                    trainButton.setDisable(false);
                    confusionsButton.setDisable(false);
                    sampleButton.setDisable(false);

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

    private void doSampleAndReview() {
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

        Dialogs.showInfoNotification(STRINGS.getString("name"),
                "Sampled " + lastSampledCellIds.size() + " cells. Use 'Enter Review Mode' to start.");
    }

    private void doEnterReview() {
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

        stage.setOnHidden(e -> {
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
                            ProjectStateManager.saveImageLabels(
                                    project, imgEntry.getImageName(), labelStore);
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
        labelCountLabel.setText("Labels: " + labelCount);
        predCountLabel.setText("Predictions: " + predCount);
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
            String cls = anno.getPathClass().toString();

            List<PathObject> hits;
            if (anno.getROI().isPoint()) {
                hits = new java.util.ArrayList<>();
                for (var pt : anno.getROI().getAllPoints()) {
                    hits.addAll(PathObjectTools.getObjectsForLocation(
                            hierarchy, pt.getX(), pt.getY(),
                            anno.getROI().getZ(), anno.getROI().getT(), -1));
                }
            } else {
                hits = new java.util.ArrayList<>(hierarchy.getAllDetectionsForROI(anno.getROI()));
            }

            for (PathObject det : hits) {
                if (det.isDetection()) {
                    store.setLabel(det.getID().toString(), cls);
                }
            }
        }
    }
}
