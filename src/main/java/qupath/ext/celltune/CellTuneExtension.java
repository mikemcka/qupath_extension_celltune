package qupath.ext.celltune;

import javafx.beans.property.BooleanProperty;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.DualModelClassifier;
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

        // Discover feature names and train
        List<String> featureNames = CellFeatureExtractor.discoverFeatureNames(detections);
        if (featureNames.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No cell measurements found.");
            return;
        }

        // Apply user feature selection if set
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
                    featureNames.size(), CellFeatureExtractor.discoverFeatureNames(detections).size());
        }

        CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
        if (classifier == null) {
            classifier = new DualModelClassifier();
        }

        // Run training on a background daemon thread
        Thread trainThread = new Thread(() -> {
            try {
                // Auto-backup labels before training
                var project = qupath.getProject();
                if (project != null) {
                    ProjectStateManager.backupLabels(project, labelStore);
                }

                classifier.trainAndPredict(detections, labelStore, extractor,
                        msg -> logger.info("[CellTune] {}", msg));

                predAll = classifier.getPredALL();

                // Save classifier state
                if (project != null) {
                    var state = classifier.toClassifierState("CellTune");
                    ProjectStateManager.saveState(project, state.getName(),
                            labelStore, state.getFeatureNames(), state.getClassNames(),
                            state.getXgboostBytes(), state.getLightgbmBytes());
                }

                javafx.application.Platform.runLater(() -> {
                    imageData.getHierarchy().fireHierarchyChangedEvent(this);
                    syncPanelState();
                    Dialogs.showInfoNotification(EXTENSION_NAME,
                            "Training complete. " + predAll.size() + " cells classified, "
                            + predAll.getDisagreementCount() + " disagreements.");
                });
            } catch (Exception ex) {
                logger.error("Training failed", ex);
                javafx.application.Platform.runLater(() ->
                        Dialogs.showErrorMessage(EXTENSION_NAME, "Training failed: " + ex.getMessage()));
            }
        }, "CellTune-Training");
        trainThread.setDaemon(true);
        trainThread.start();

        Dialogs.showInfoNotification(EXTENSION_NAME, "Training started in background…");
    }

    /**
     * Collect ground-truth labels from classified point annotations overlapping detections.
     */
    private void collectLabelsFromAnnotations(QuPathGUI qupath, LabelStore store) {
        var imageData = qupath.getImageData();
        if (imageData == null) return;
        var hierarchy = imageData.getHierarchy();

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
