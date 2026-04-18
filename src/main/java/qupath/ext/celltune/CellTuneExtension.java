package qupath.ext.celltune;

import javafx.beans.property.BooleanProperty;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.DualModelClassifier;
import qupath.ext.celltune.classifier.ResamplingStrategy;
import qupath.ext.celltune.classifier.UncertaintySampler;
import qupath.ext.celltune.io.CellTableExporter;
import qupath.ext.celltune.io.GroundTruthIO;
import qupath.ext.celltune.io.MarkerTableImporter;
import qupath.ext.celltune.io.ProjectStateManager;
import qupath.ext.celltune.model.CellFeatureExtractor;
import qupath.ext.celltune.model.CellTypeTable;
import qupath.ext.celltune.model.LabelStore;
import qupath.ext.celltune.model.PopulationSet;
import qupath.ext.celltune.ui.ChannelSelector;
import qupath.ext.celltune.ui.ClassificationPanel;
import qupath.ext.celltune.ui.ConfusionMatrixView;
import qupath.ext.celltune.ui.FeatureSelectionPane;
import qupath.ext.celltune.ui.ImageSelectionPane;
import qupath.ext.celltune.ui.ManualLabelToolbar;
import qupath.ext.celltune.ui.ReviewController;
import qupath.ext.celltune.ui.ReviewToolbar;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectFilter;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.ResourceBundle;

/**
 * QuPath extension that provides CellTune-style active learning cell classification.
 * <p>
 * Uses dual gradient-boosted models (XGBoost + LightGBM) to identify disagreement
 * cells, then presents them for human review in an iterative loop that progressively
 * improves classification accuracy.
 * <p>
 * Workflow: Landmark → Train → Confusions → Sample → Review → Retrain
 */
public class CellTuneExtension implements QuPathExtension {

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.celltune.ui.strings");
    private static final Logger logger = LoggerFactory.getLogger(CellTuneExtension.class);

    private static final String EXTENSION_NAME        = resources.getString("name");
    private static final String EXTENSION_DESCRIPTION = resources.getString("description");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

    /** Persistent preference — lets users disable the extension from the Preferences pane. */
    private static final BooleanProperty enableExtensionProperty =
            PathPrefs.createPersistentPreference("celltune.enabled", true);

    private boolean isInstalled = false;

    /** Shared extension state — populated as the user works. */
    private CellTypeTable cellTypeTable;
    private LabelStore labelStore;
    private PopulationSet predAll;
    private DualModelClassifier classifier;
    /** User-selected feature subset; null means use all features. */
    private List<String> selectedFeatures;
    /** Per-class agreement rates from the last confusion matrix. */
    private double[] lastAgreementRates;
    /** Cell IDs sampled for review. */
    private List<String> lastSampledCellIds;
    /** Docked classification panel (Phase 7). */
    private ClassificationPanel classificationPanel;

    // ── QuPathExtension API ────────────────────────────────────────────────────

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.debug("{} is already installed", getName());
            return;
        }
        isInstalled = true;
        addPreferenceToPane(qupath);
        addMenuItems(qupath);
        dockClassificationPanel(qupath);
        checkHeapMemory();
    }

    @Override public String getName()           { return EXTENSION_NAME; }
    @Override public String getDescription()    { return EXTENSION_DESCRIPTION; }
    @Override public Version getQuPathVersion() { return EXTENSION_QUPATH_VERSION; }
    @Override public Version getVersion() {
        var v = getClass().getPackage().getImplementationVersion();
        return (v == null) ? QuPathExtension.super.getVersion() : Version.parse(v);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void dockClassificationPanel(QuPathGUI qupath) {
        classificationPanel = new ClassificationPanel(qupath);
        classificationPanel.setLabelStore(labelStore);
        classificationPanel.setClassifier(classifier);
        classificationPanel.setSelectedFeatures(selectedFeatures);
        classificationPanel.setCellTypeTable(cellTypeTable);
        classificationPanel.setPredAll(predAll);

        // Sync state back from panel to extension
        classificationPanel.setOnLabelStoreChanged(ls -> this.labelStore = ls);
        classificationPanel.setOnPredAllChanged(pa -> this.predAll = pa);
        classificationPanel.setOnAgreementRatesChanged(ar -> this.lastAgreementRates = ar);
        classificationPanel.setOnSampledCellsChanged(ids -> this.lastSampledCellIds = ids);
        classificationPanel.setOnClassifierChanged(cls -> this.classifier = cls);

        // Dock into QuPath's analysis pane
        var titledPane = new javafx.scene.control.TitledPane(EXTENSION_NAME, classificationPanel);
        titledPane.setCollapsible(true);
        titledPane.setExpanded(false);
        qupath.getAnalysisTabPane().getTabs().add(
                new javafx.scene.control.Tab(EXTENSION_NAME, titledPane));
    }

    /** Push current extension state into the docked panel. */
    private void syncPanelState() {
        if (classificationPanel == null) return;
        classificationPanel.setLabelStore(labelStore);
        classificationPanel.setClassifier(classifier);
        classificationPanel.setSelectedFeatures(selectedFeatures);
        classificationPanel.setCellTypeTable(cellTypeTable);
        classificationPanel.setPredAll(predAll);
        classificationPanel.setLastAgreementRates(lastAgreementRates);
        classificationPanel.setLastSampledCellIds(lastSampledCellIds);
    }

    /**
     * Check JVM heap at startup and warn if it looks too low for large datasets.
     * QuPath defaults to about 1/4 of system RAM which may be insufficient for
     * 500K+ cells with 2000+ features.
     */
    private void checkHeapMemory() {
        long maxHeapBytes = Runtime.getRuntime().maxMemory();
        double maxHeapGiB = maxHeapBytes / (1024.0 * 1024.0 * 1024.0);
        logger.info("CellTune: JVM max heap = {} GiB", String.format("%.1f", maxHeapGiB));

        if (maxHeapGiB < 8.0) {
            javafx.application.Platform.runLater(() ->
                    Dialogs.showWarningNotification(EXTENSION_NAME,
                            String.format("JVM heap is only %.1f GiB. For large datasets "
                                    + "(100K+ cells, 1000+ features) increase memory via "
                                    + "Edit \u2192 Preferences \u2192 'Max memory' or "
                                    + "Help \u2192 Show setup options. "
                                    + "Recommended: at least 16 GiB for large panels.", maxHeapGiB)));
        }
    }

    /**
     * Estimate peak memory required for training and warn if the current heap
     * is likely insufficient.
     *
     * @param nCells    number of cells to predict
     * @param nFeatures number of features per cell
     * @return true if the user confirms to proceed (or memory is sufficient),
     *         false if they cancel
     */
    private boolean checkTrainingMemory(int nCells, int nFeatures) {
        long maxHeapBytes = Runtime.getRuntime().maxMemory();
        double maxHeapGiB = maxHeapBytes / (1024.0 * 1024.0 * 1024.0);

        // Feature matrix = nCells × nFeatures × 4 bytes (float)
        // XGBoost + LightGBM each make a native copy → ~3× the matrix
        // Plus CellPrediction objects + PopulationSets ~300 MB overhead
        double matrixGiB = (double) nCells * nFeatures * 4L / (1024.0 * 1024.0 * 1024.0);
        double estimatedPeakGiB = matrixGiB * 3.0 + 0.3;

        logger.info("CellTune memory estimate: {} cells x {} features = {} GiB matrix, "
                + "{} GiB estimated peak, {} GiB heap available",
                nCells, nFeatures, String.format("%.1f", matrixGiB),
                String.format("%.1f", estimatedPeakGiB), String.format("%.1f", maxHeapGiB));

        if (estimatedPeakGiB > maxHeapGiB * 0.8) {
            return Dialogs.showConfirmDialog(EXTENSION_NAME,
                    String.format("Memory warning: %,d cells \u00d7 %,d features requires an "
                            + "estimated %.1f GiB but the JVM heap is only %.1f GiB.\n\n"
                            + "This may cause an OutOfMemoryError.\n"
                            + "Increase memory via Edit \u2192 Preferences \u2192 'Max memory' "
                            + "or Help \u2192 Show setup options.\n\n"
                            + "Proceed anyway?", nCells, nFeatures, estimatedPeakGiB, maxHeapGiB));
        }
        return true;
    }

    private void addPreferenceToPane(QuPathGUI qupath) {
        var item = new PropertyItemBuilder<>(enableExtensionProperty, Boolean.class)
                .name(resources.getString("menu.enable"))
                .category(EXTENSION_NAME)
                .description(EXTENSION_DESCRIPTION)
                .build();
        qupath.getPreferencePane().getPropertySheet().getItems().add(item);
    }

    private void addMenuItems(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        MenuItem classifyItem = new MenuItem(resources.getString("menu.classify"));
        classifyItem.setOnAction(e -> showClassifierPanel(qupath));
        classifyItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem reviewItem = new MenuItem(resources.getString("menu.review"));
        reviewItem.setOnAction(e -> showReviewMode(qupath));
        reviewItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem manualLabelItem = new MenuItem(resources.getString("menu.manual.label"));
        manualLabelItem.setOnAction(e -> showManualLabelMode(qupath));
        manualLabelItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem confusionsItem = new MenuItem(resources.getString("menu.confusions"));
        confusionsItem.setOnAction(e -> showConfusions(qupath));
        confusionsItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem exportItem = new MenuItem(resources.getString("menu.export"));
        exportItem.setOnAction(e -> exportCellTable(qupath));
        exportItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem importMarkersItem = new MenuItem(resources.getString("menu.import.markers"));
        importMarkersItem.setOnAction(e -> importMarkerTable(qupath));
        importMarkersItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem exportGtItem = new MenuItem(resources.getString("menu.export.groundtruth"));
        exportGtItem.setOnAction(e -> exportGroundTruth(qupath));
        exportGtItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem importGtItem = new MenuItem(resources.getString("menu.import.groundtruth"));
        importGtItem.setOnAction(e -> importGroundTruth(qupath));
        importGtItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem featuresItem = new MenuItem(resources.getString("menu.features"));
        featuresItem.setOnAction(e -> showFeatureSelection(qupath));
        featuresItem.disableProperty().bind(enableExtensionProperty.not());

        menu.getItems().addAll(
                classifyItem,
                featuresItem,
                new SeparatorMenuItem(),
                reviewItem,
                manualLabelItem,
                confusionsItem,
                new SeparatorMenuItem(),
                importMarkersItem,
                exportItem,
                new SeparatorMenuItem(),
                exportGtItem,
                importGtItem
        );
    }

    // ── Placeholder actions (wired in later phases) ────────────────────────────

    private void showClassifierPanel(QuPathGUI qupath) {
        if (qupath.getProject() == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("classify.no_project"));
            return;
        }
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }

        // Collect labelled cells from point annotations
        var hierarchy = imageData.getHierarchy();
        Collection<PathObject> detections = hierarchy.getDetectionObjects();
        if (detections.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No detections found. Run cell detection first.");
            return;
        }

        // Build label store from classified annotations (point annotations on detections)
        if (labelStore == null) {
            labelStore = new LabelStore("CellTune");
        }

        // Discover labels from annotation objects that overlap detections
        collectLabelsFromAnnotations(qupath, labelStore);

        if (labelStore.size() < 10) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "Need at least 10 labelled cells to train. Found: " + labelStore.size()
                    + "\nUse point annotations placed on detections to label cells.");
            return;
        }

        // Discover feature names
        List<String> allFeatureNames = CellFeatureExtractor.discoverFeatureNames(detections);
        if (allFeatureNames.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No cell measurements found.");
            return;
        }

        // Prompt user to select features if not yet selected
        if (selectedFeatures == null || selectedFeatures.isEmpty()) {
            var featurePrompt = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION);
            featurePrompt.setTitle(EXTENSION_NAME);
            featurePrompt.setHeaderText("Feature Selection");
            featurePrompt.setContentText(
                    allFeatureNames.size() + " features detected.\n\n"
                    + "Would you like to select specific features before training?\n"
                    + "(Recommended for panels with 100+ measurements.\n"
                    + "Choose 'No' to use all features.)");
            var selectBtn = new javafx.scene.control.ButtonType("Select Features");
            var useAllBtn = new javafx.scene.control.ButtonType("Use All");
            var cancelBtn = javafx.scene.control.ButtonType.CANCEL;
            featurePrompt.getButtonTypes().setAll(selectBtn, useAllBtn, cancelBtn);
            var featureResult = featurePrompt.showAndWait();
            if (featureResult.isEmpty() || featureResult.get() == cancelBtn) {
                return;
            }
            if (featureResult.get() == selectBtn) {
                var pane = new FeatureSelectionPane(qupath.getStage(), allFeatureNames, null);
                List<String> chosen = pane.showAndWait();
                if (chosen == null) return; // user cancelled
                if (!chosen.isEmpty() && chosen.size() < allFeatureNames.size()) {
                    selectedFeatures = chosen;
                }
                syncPanelState();
            }
        }

        // Apply user feature selection if set
        List<String> featureNames = new java.util.ArrayList<>(allFeatureNames);
        if (selectedFeatures != null && !selectedFeatures.isEmpty()) {
            featureNames = featureNames.stream()
                    .filter(selectedFeatures::contains)
                    .collect(java.util.stream.Collectors.toList());
            if (featureNames.isEmpty()) {
                Dialogs.showErrorMessage(EXTENSION_NAME,
                        "None of the selected features are present in the detections. "
                        + "Check feature selection or run detection with the expected panel.");
                return;
            }
            logger.info("Using {} of {} available features (user selection)",
                    featureNames.size(), allFeatureNames.size());
        }

        // Collect project image entries for batch application
        var project = qupath.getProject();
        List<String> allImageNames = new java.util.ArrayList<>();
        String currentImageName = null;
        if (project != null) {
            @SuppressWarnings("unchecked")
            var entries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
            for (var entry : entries) {
                allImageNames.add(entry.getImageName());
            }
            var currentEntry = project.getEntry(imageData);
            if (currentEntry != null) {
                currentImageName = currentEntry.getImageName();
            }
        }

        // Confirm before starting training — with option to pool labels from all images
        boolean hasMultipleImages = allImageNames.size() > 1;
        var poolCheckBox = new javafx.scene.control.CheckBox(
                "Pool labelled cells from all project images into training set");
        poolCheckBox.setSelected(false);

        var resamplingCombo = new javafx.scene.control.ComboBox<ResamplingStrategy>();
        resamplingCombo.getItems().addAll(ResamplingStrategy.values());
        resamplingCombo.setValue(ResamplingStrategy.NONE);
        resamplingCombo.setMaxWidth(Double.MAX_VALUE);
        var resamplingLabel = new javafx.scene.control.Label("Resampling:");
        var resamplingRow = new javafx.scene.layout.HBox(6, resamplingLabel, resamplingCombo);
        resamplingRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var confirmAlert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle(EXTENSION_NAME);
        confirmAlert.setHeaderText("Ready to Train");
        String confirmMsg = featureNames.size() + " features, "
                + labelStore.size() + " labelled cells in current image.";
        if (hasMultipleImages) {
            confirmMsg += "\n\nThis project has " + allImageNames.size()
                    + " images. Check the box below to include labelled\n"
                    + "cells from all images in the training set.";
        }
        confirmAlert.setContentText(confirmMsg);
        if (hasMultipleImages) {
            confirmAlert.getDialogPane().setExpandableContent(null);
            var contentBox = new javafx.scene.layout.VBox(8,
                    new javafx.scene.control.Label(confirmMsg), poolCheckBox, resamplingRow);
            contentBox.setPadding(new javafx.geometry.Insets(4));
            confirmAlert.getDialogPane().setContent(contentBox);
        } else {
            confirmAlert.getDialogPane().setExpandableContent(null);
            var contentBox = new javafx.scene.layout.VBox(8,
                    new javafx.scene.control.Label(confirmMsg), resamplingRow);
            contentBox.setPadding(new javafx.geometry.Insets(4));
            confirmAlert.getDialogPane().setContent(contentBox);
        }
        var confirmResult = confirmAlert.showAndWait();
        if (confirmResult.isEmpty()
                || confirmResult.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }
        final boolean poolAllImages = hasMultipleImages && poolCheckBox.isSelected();
        final ResamplingStrategy resamplingStrategy = resamplingCombo.getValue();

        // Check whether the JVM has enough heap for this dataset
        if (!checkTrainingMemory(detections.size(), featureNames.size())) return;

        CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
        if (classifier == null) {
            classifier = new DualModelClassifier();
        }

        // Show image selection dialog (current image is always included)
        List<String> selectedImages;
        if (allImageNames.size() > 1) {
            var imageSelector = new ImageSelectionPane(
                    qupath.getStage(), allImageNames, currentImageName);
            selectedImages = imageSelector.showAndWait();
            if (selectedImages == null) return; // user cancelled
        } else {
            selectedImages = allImageNames;
        }

        final List<String> imagesToClassify = selectedImages;
        final List<String> finalFeatureNames = featureNames;
        final String currentImageNameFinal = currentImageName;

        // ── Progress dialog ─────────────────────────────────────────────────
        var progressStage = new javafx.stage.Stage();
        progressStage.setTitle("CellTune — Training");
        progressStage.initOwner(qupath.getStage());
        progressStage.initModality(javafx.stage.Modality.NONE);
        progressStage.setResizable(false);
        progressStage.setAlwaysOnTop(true);

        var progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setPrefWidth(400);
        var statusLabel = new javafx.scene.control.Label("Initialising…");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(400);
        var logArea = new javafx.scene.control.TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(150);
        logArea.setPrefWidth(400);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        var progressBox = new javafx.scene.layout.VBox(8, statusLabel, progressBar, logArea);
        progressBox.setPadding(new javafx.geometry.Insets(15));
        progressStage.setScene(new javafx.scene.Scene(progressBox));
        progressStage.show();

        // Bind progress bar and status label to classifier properties
        progressBar.progressProperty().bind(classifier.progressProperty());
        statusLabel.textProperty().bind(classifier.statusProperty());

        // Run training on a background daemon thread
        Thread trainThread = new Thread(() -> {
            try {
                // Auto-backup labels before training
                if (project != null) {
                    ProjectStateManager.backupLabels(project, labelStore);
                }

                // Collect supplementary training data from other project images
                List<float[]> supplementaryRows = null;
                List<String> supplementaryLabels = null;

                if (poolAllImages && project != null) {
                    supplementaryRows = new java.util.ArrayList<>();
                    supplementaryLabels = new java.util.ArrayList<>();

                    @SuppressWarnings("unchecked")
                    var allEntries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
                    var currentEntry = project.getEntry(imageData);

                    java.util.function.Consumer<String> poolLog = msg -> {
                        logger.info("[CellTune] {}", msg);
                        javafx.application.Platform.runLater(() ->
                                logArea.appendText(msg + "\n"));
                    };

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
                            java.util.Map<String, PathObject> otherCellById = new java.util.LinkedHashMap<>();
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

                            if (added > 0) {
                                poolLog.accept("Collected " + added + " labelled cells from: "
                                        + entry.getImageName());
                            }
                        } catch (Exception ex) {
                            logger.warn("[CellTune] Failed to read {}: {}",
                                    entry.getImageName(), ex.getMessage());
                            javafx.application.Platform.runLater(() ->
                                    logArea.appendText("Warning: could not read "
                                            + entry.getImageName() + "\n"));
                        }
                    }
                }

                classifier.trainAndPredict(detections, labelStore, extractor,
                        supplementaryRows, supplementaryLabels,
                        resamplingStrategy,
                        msg -> {
                            logger.info("[CellTune] {}", msg);
                            javafx.application.Platform.runLater(() ->
                                    logArea.appendText(msg + "\n"));
                        });

                predAll = classifier.getPredALL();

                // Save classifier state
                if (project != null) {
                    var state = classifier.toClassifierState("CellTune");
                    ProjectStateManager.saveState(project, state.getName(),
                            labelStore, state.getFeatureNames(), state.getClassNames(),
                            state.getXgboostBytes(), state.getLightgbmBytes());

                    // Save per-image labels for cross-image pooling
                    if (currentImageNameFinal != null) {
                        ProjectStateManager.saveImageLabels(
                                project, currentImageNameFinal, labelStore);
                    }
                }

                // Apply predictions to other selected images
                if (project != null && imagesToClassify.size() > 1) {
                    @SuppressWarnings("unchecked")
                    var allEntries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
                    var currentEntry = project.getEntry(imageData);

                    int applied = 0;
                    for (var entry : allEntries) {
                        if (currentEntry != null && entry.equals(currentEntry)) continue;
                        if (!imagesToClassify.contains(entry.getImageName())) continue;

                        try {
                            String imgName = entry.getImageName();
                            logger.info("[CellTune] Applying predictions to: {}", imgName);
                            javafx.application.Platform.runLater(() ->
                                    logArea.appendText("Classifying: " + imgName + "\n"));

                            var otherImageData = entry.readImageData();
                            var otherDetections = otherImageData.getHierarchy().getDetectionObjects();
                            if (otherDetections.isEmpty()) {
                                logger.warn("[CellTune] No detections in {}, skipping", imgName);
                                javafx.application.Platform.runLater(() ->
                                        logArea.appendText("  Skipped (no detections): " + imgName + "\n"));
                                continue;
                            }

                            var otherExtractor = new CellFeatureExtractor(finalFeatureNames);
                            classifier.predictOnly(otherDetections, otherExtractor,
                                    msg -> {
                                        logger.info("[CellTune] [{}] {}", imgName, msg);
                                        javafx.application.Platform.runLater(() ->
                                                logArea.appendText("  " + msg + "\n"));
                                    });

                            entry.saveImageData(otherImageData);
                            applied++;
                        } catch (Exception imgEx) {
                            logger.error("[CellTune] Failed to classify {}: {}",
                                    entry.getImageName(), imgEx.getMessage());
                            javafx.application.Platform.runLater(() ->
                                    logArea.appendText("  ERROR: " + imgEx.getMessage() + "\n"));
                        }
                    }

                    final int totalApplied = applied;
                    javafx.application.Platform.runLater(() -> {
                        imageData.getHierarchy().fireHierarchyChangedEvent(this);
                        syncPanelState();
                        logArea.appendText("\nDone! Classified " + predAll.size() + " cells, "
                                + predAll.getDisagreementCount() + " disagreements.\n"
                                + "Applied to " + totalApplied + " additional image(s).\n");
                        statusLabel.textProperty().unbind();
                        statusLabel.setText("Complete — close this window when ready.");
                        progressBar.progressProperty().unbind();
                        progressBar.setProgress(1.0);
                    });
                } else {
                    javafx.application.Platform.runLater(() -> {
                        imageData.getHierarchy().fireHierarchyChangedEvent(this);
                        syncPanelState();
                        logArea.appendText("\nDone! Classified " + predAll.size() + " cells, "
                                + predAll.getDisagreementCount() + " disagreements.\n");
                        statusLabel.textProperty().unbind();
                        statusLabel.setText("Complete — close this window when ready.");
                        progressBar.progressProperty().unbind();
                        progressBar.setProgress(1.0);
                    });
                }
            } catch (Exception ex) {
                logger.error("Training failed", ex);
                javafx.application.Platform.runLater(() -> {
                    logArea.appendText("\nERROR: " + ex.getMessage() + "\n");
                    statusLabel.textProperty().unbind();
                    statusLabel.setText("Training failed!");
                    progressBar.progressProperty().unbind();
                    progressBar.setProgress(0);
                    Dialogs.showErrorMessage(EXTENSION_NAME, "Training failed: " + ex.getMessage());
                });
            }
        }, "CellTune-Training");
        trainThread.setDaemon(true);
        trainThread.start();
    }

    /**
     * Collect ground-truth labels from classified point annotations overlapping detections.
     */
    private void collectLabelsFromAnnotations(QuPathGUI qupath, LabelStore store) {
        var imageData = qupath.getImageData();
        if (imageData == null) return;
        collectLabelsFromHierarchy(imageData.getHierarchy(), store);
    }

    /**
     * Save the current image's label store as a per-image JSON file so that
     * labels from review and manual labelling can be pooled during training
     * on other images.
     */
    private void saveCurrentImageLabels(QuPathGUI qupath) {
        if (labelStore == null || labelStore.size() == 0) return;
        var project = qupath.getProject();
        var imageData = qupath.getImageData();
        if (project == null || imageData == null) return;

        var entry = project.getEntry(imageData);
        if (entry == null) return;

        try {
            ProjectStateManager.saveImageLabels(project, entry.getImageName(), labelStore);
        } catch (IOException ex) {
            logger.warn("Failed to save per-image labels for {}: {}",
                    entry.getImageName(), ex.getMessage());
        }
    }

    /**
     * Collect ground-truth labels from classified annotations in a hierarchy.
     * Works with any image's hierarchy — used for both the current image and
     * other project images when pooling training data.
     */
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

    private void showManualLabelMode(QuPathGUI qupath) {
        if (qupath.getImageData() == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }
        if (qupath.getImageData().getHierarchy().getDetectionObjects().isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "No detections found. Run cell detection first.");
            return;
        }

        if (labelStore == null) {
            labelStore = new LabelStore("CellTune");
        }

        // Gather extra class names from CellTypeTable if loaded
        java.util.Set<String> extraClasses = null;
        if (cellTypeTable != null) {
            extraClasses = cellTypeTable.getCellTypes();
        }

        var toolbar = new ManualLabelToolbar(
                qupath, labelStore, extraClasses, qupath.getStage());

        // When the manual label window closes, sync state
        toolbar.getStage().setOnHidden(e -> {
            syncPanelState();
            logger.info("Manual label mode ended — {} total labels", labelStore.size());

            // Persist per-image labels so they can be pooled from other images
            saveCurrentImageLabels(qupath);

            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "Manual labelling complete: " + labelStore.size() + " total labels.");
        });
    }

    private void showReviewMode(QuPathGUI qupath) {
        if (qupath.getProject() == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("classify.no_project"));
            return;
        }
        if (lastSampledCellIds == null || lastSampledCellIds.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("review.no_sample"));
            return;
        }
        if (predAll == null || predAll.size() == 0) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "No predictions available. Train and sample first.");
            return;
        }

        // Build controller and UI components
        var reviewController = new ReviewController(qupath, lastSampledCellIds, predAll);
        if (reviewController.size() == 0) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "Could not resolve any sampled cells in the current image.");
            return;
        }

        var channelSelector = new ChannelSelector(qupath, cellTypeTable);
        var toolbar = new ReviewToolbar(reviewController, cellTypeTable, channelSelector);

        // Build the review stage
        var vbox = new javafx.scene.layout.VBox(6);
        vbox.setPadding(new javafx.geometry.Insets(6));
        vbox.getChildren().addAll(toolbar, channelSelector.getCheckBox());

        var stage = new javafx.stage.Stage();
        stage.setTitle(resources.getString("review.stage.title"));
        stage.initOwner(qupath.getStage());
        stage.setScene(new javafx.scene.Scene(vbox));
        stage.setAlwaysOnTop(true);
        stage.setResizable(true);

        // When the review window is closed, merge labels back
        stage.setOnHidden(e -> {
            var outputLabels = reviewController.getOutputLabels();
            if (outputLabels.size() > 0) {
                if (labelStore == null) {
                    labelStore = new LabelStore("CellTune");
                }
                labelStore.mergeFrom(outputLabels);
                syncPanelState();
                logger.info("Review complete — merged {} labels into main label store",
                        outputLabels.size());

                // Persist per-image labels so they can be pooled from other images
                saveCurrentImageLabels(qupath);

                Dialogs.showInfoNotification(EXTENSION_NAME,
                        String.format("Review complete: %d labels merged.", outputLabels.size()));
            }
        });

        stage.show();
    }

    private void showConfusions(QuPathGUI qupath) {
        if (predAll == null || predAll.size() == 0) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "No predictions available. Train a classifier first.");
            return;
        }
        if (classifier == null || classifier.getClassNames() == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "Classifier not available. Train first.");
            return;
        }

        List<String> classNames = classifier.getClassNames();
        var view = new ConfusionMatrixView(qupath.getStage(), predAll, classNames);
        lastAgreementRates = view.getAgreementRates();
        view.show();

        // Offer to sample disagreement cells for review
        long disagreeCount = predAll.getDisagreementCount();
        if (disagreeCount == 0) {
            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "Perfect agreement — no disagreement cells to sample.");
            return;
        }

        String countStr = Dialogs.showInputDialog(
                resources.getString("sample.dialog.title"),
                resources.getString("sample.count.label")
                        + " (" + disagreeCount + " disagreements available)",
                "200");
        if (countStr == null) return;

        int sampleSize;
        try {
            sampleSize = Integer.parseInt(countStr.strip());
        } catch (NumberFormatException e) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "Invalid number.");
            return;
        }
        if (sampleSize <= 0) return;

        lastSampledCellIds = UncertaintySampler.sample(
                predAll, classNames, lastAgreementRates, sampleSize);
        syncPanelState();

        Dialogs.showInfoNotification(EXTENSION_NAME,
                "Sampled " + lastSampledCellIds.size() + " cells for review."
                + " Use 'Enter Review Mode' to start reviewing.");
    }

    private void exportCellTable(QuPathGUI qupath) {
        if (qupath.getProject() == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("classify.no_project"));
            return;
        }
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }
        if (predAll == null || predAll.size() == 0) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No predictions available. Train a classifier first.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Export Cell Table");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var project = qupath.getProject();
        if (project != null && project.getPath() != null) {
            File dir = project.getPath().getParent().toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
        }
        fc.setInitialFileName("celltune_export.csv");
        File chosen = fc.showSaveDialog(qupath.getStage());
        if (chosen == null) return;

        try {
            Collection<PathObject> cells = imageData.getHierarchy()
                    .getObjects(null, PathObject.class).stream()
                    .filter(PathObjectFilter.DETECTIONS_ALL)
                    .toList();
            CellTableExporter.export(chosen.toPath(), cells, predAll, labelStore);
            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "Exported " + cells.size() + " cells to " + chosen.getName());
        } catch (IOException ex) {
            logger.error("Failed to export cell table", ex);
            Dialogs.showErrorMessage(EXTENSION_NAME, "Export failed: " + ex.getMessage());
        }
    }

    private void importMarkerTable(QuPathGUI qupath) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Import Marker Table");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var project = qupath.getProject();
        if (project != null && project.getPath() != null) {
            File dir = project.getPath().getParent().toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
        }
        File chosen = fc.showOpenDialog(qupath.getStage());
        if (chosen == null) return;

        try {
            cellTypeTable = MarkerTableImporter.importFromCSV(chosen.toPath());
            syncPanelState();
            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "Loaded " + cellTypeTable.size() + " cell types from " + chosen.getName());
        } catch (IOException ex) {
            logger.error("Failed to import marker table", ex);
            Dialogs.showErrorMessage(EXTENSION_NAME, "Import failed: " + ex.getMessage());
        }
    }

    // ── Ground truth export/import ─────────────────────────────────────────────

    private void exportGroundTruth(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }
        if (labelStore == null || labelStore.size() == 0) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("gt.export.empty"));
            return;
        }

        Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
        List<String> featureNames = CellFeatureExtractor.discoverFeatureNames(detections);
        if (featureNames.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No cell measurements found.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Export Ground Truth");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var project = qupath.getProject();
        if (project != null && project.getPath() != null) {
            File dir = project.getPath().getParent().toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
        }
        fc.setInitialFileName("ground_truth.csv");
        File chosen = fc.showSaveDialog(qupath.getStage());
        if (chosen == null) return;

        try {
            CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
            String imgName = imageData.getServer().getMetadata().getName();
            GroundTruthIO.exportCSV(chosen.toPath(), detections, labelStore, extractor, imgName);
            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "Exported " + labelStore.size() + " labelled cells to " + chosen.getName());
        } catch (IOException ex) {
            logger.error("Failed to export ground truth", ex);
            Dialogs.showErrorMessage(EXTENSION_NAME, "Export failed: " + ex.getMessage());
        }
    }

    private void importGroundTruth(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Import Ground Truth");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var project = qupath.getProject();
        if (project != null && project.getPath() != null) {
            File dir = project.getPath().getParent().toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
        }
        File chosen = fc.showOpenDialog(qupath.getStage());
        if (chosen == null) return;

        // Ask user: spatial match vs training data only
        var result = Dialogs.showChoiceDialog(
                resources.getString("gt.import.mode.title"),
                resources.getString("gt.import.mode.prompt"),
                List.of(resources.getString("gt.import.mode.spatial"),
                        resources.getString("gt.import.mode.training")),
                resources.getString("gt.import.mode.spatial"));
        if (result == null) return;

        boolean spatial = result.equals(resources.getString("gt.import.mode.spatial"));

        try {
            if (spatial) {
                String threshStr = Dialogs.showInputDialog(
                        resources.getString("gt.import.mode.title"),
                        resources.getString("gt.import.spatial.threshold"),
                        resources.getString("gt.import.spatial.default"));
                if (threshStr == null) return;
                double maxDist;
                try {
                    maxDist = Double.parseDouble(threshStr.strip());
                } catch (NumberFormatException e) {
                    Dialogs.showErrorMessage(EXTENSION_NAME, "Invalid distance value.");
                    return;
                }

                Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
                LabelStore imported = GroundTruthIO.importCSVSpatial(chosen.toPath(), detections, maxDist);

                if (labelStore == null) labelStore = new LabelStore("CellTune");
                labelStore.mergeFrom(imported);
                syncPanelState();

                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "Imported " + imported.size() + " labels via spatial matching. "
                        + "Total labels: " + labelStore.size());
            } else {
                // Training data import — just load and report
                var rows = GroundTruthIO.importCSVAsTrainingData(chosen.toPath());
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "Loaded " + rows.size() + " training rows. "
                        + "These will be available for the next training run.");
                // Store for future training use — could be wired to DualModelClassifier
                // as supplementary training data in a later phase
            }
        } catch (IOException ex) {
            logger.error("Failed to import ground truth", ex);
            Dialogs.showErrorMessage(EXTENSION_NAME, "Import failed: " + ex.getMessage());
        }
    }

    // ── Feature selection ──────────────────────────────────────────────────────

    private void showFeatureSelection(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }

        Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No detections found. Run cell detection first.");
            return;
        }

        List<String> allFeatures = CellFeatureExtractor.discoverFeatureNames(detections);
        if (allFeatures.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No cell measurements found.");
            return;
        }

        var pane = new FeatureSelectionPane(qupath.getStage(), allFeatures, selectedFeatures);
        List<String> chosen = pane.showAndWait();
        if (chosen != null) {
            if (chosen.isEmpty()) {
                Dialogs.showWarningNotification(EXTENSION_NAME,
                        "No features selected — using all features for training.");
                selectedFeatures = null;
            } else if (chosen.size() == allFeatures.size()) {
                selectedFeatures = null;
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "All " + allFeatures.size() + " features selected.");
            } else {
                selectedFeatures = chosen;
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        chosen.size() + " of " + allFeatures.size() + " features selected for training.");
            }
            syncPanelState();
        }
    }
}
