package qupath.ext.celltune;

import javafx.beans.property.BooleanProperty;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.DualModelClassifier;
import qupath.ext.celltune.classifier.ClassifierState;
import qupath.ext.celltune.classifier.ModelType;
import qupath.ext.celltune.classifier.ResamplingStrategy;
import qupath.ext.celltune.classifier.UncertaintySampler;
import qupath.ext.celltune.gating.AutoLandmarker;
import qupath.ext.celltune.gating.GatingRule;
import qupath.ext.celltune.io.AnnDataExporter;
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
import qupath.ext.celltune.ui.NormalizationPane;
import qupath.ext.celltune.model.FeatureNormalizer;
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
import qupath.ext.celltune.io.BinaryClassifierRegistry;
import qupath.ext.celltune.ui.BinaryClassifierPanel;
import qupath.ext.celltune.ui.CompositeClassificationDialog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

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

    /** Listener for image changes — stored so it can be removed if needed. */
    private javafx.beans.value.ChangeListener<qupath.lib.images.ImageData<java.awt.image.BufferedImage>> imageDataListener;

    /** Shared extension state — populated as the user works. */
    private CellTypeTable cellTypeTable;
    private LabelStore labelStore;
    private PopulationSet predAll;
    private DualModelClassifier classifier;
    /** User-selected feature subset; null means use all features. */
    private List<String> selectedFeatures;
    /** Per-feature normalization config; null means no transforms. */
    private FeatureNormalizer featureNormalizer;
    /** Per-class agreement rates from the last confusion matrix. */
    private double[] lastAgreementRates;
    /** Cell IDs sampled for review. */
    private List<String> lastSampledCellIds;
    /** Training rows imported from CSV (feature vectors + labels). */
    private List<GroundTruthIO.TrainingRow> importedTrainingRows;
    /** Feature names associated with importedTrainingRows. */
    private List<String> importedTrainingFeatureNames;
    /** Docked classification panel (Phase 7). */
    private ClassificationPanel classificationPanel;
    /** TitledPane wrapping the docked panel — stored so binary mode can expand it. */
    private javafx.scene.control.TitledPane dockPane;
    /** Tab holding the docked panel — stored so binary mode can select it. */
    private javafx.scene.control.Tab dockTab;

    // ── Binary classifier state ────────────────────────────────────────────────
    /** Registry of named binary classifiers: sanitizedMarkerName → relativeStatePath. */
    private Map<String, String> binaryRegistry = new LinkedHashMap<>();
    /** Name of the currently active binary classifier, or null if in multi-class mode. */
    private String activeBinaryMarker = null;
    /** Multi-class state saved before entering binary mode, restored on exit. */
    private LabelStore preBinaryLabelStore = null;
    private DualModelClassifier preBinaryClassifier = null;
    /** Images pre-selected via "Apply to Images..." button; used in next training run. */
    private List<String> binaryTargetImages = new ArrayList<>();

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
        classificationPanel.setImportedTrainingData(importedTrainingRows, importedTrainingFeatureNames);

        // Sync state back from panel to extension
        classificationPanel.setOnLabelStoreChanged(ls -> this.labelStore = ls);
        classificationPanel.setOnPredAllChanged(pa -> {
            this.predAll = pa;
            persistCurrentImagePredictions(qupath);
        });
        classificationPanel.setOnAgreementRatesChanged(ar -> this.lastAgreementRates = ar);
        classificationPanel.setOnSampledCellsChanged(ids -> this.lastSampledCellIds = ids);
        classificationPanel.setOnClassifierChanged(cls -> {
            this.classifier = cls;
            // Auto-save binary classifier state after training completes in binary mode
            if (activeBinaryMarker != null && cls != null && cls.isTrained()) {
                var proj = qupath.getProject();
                if (proj != null) {
                    try {
                        ClassifierState state = cls.toClassifierState(activeBinaryMarker);
                        ProjectStateManager.saveBinaryState(proj, activeBinaryMarker,
                                labelStore != null ? labelStore : new LabelStore(activeBinaryMarker),
                                state.getFeatureNames(), state.getClassNames(),
                                state.getXgboostBytes(), state.getLightgbmBytes(),
                                state.getRfModel1Bytes(), state.getRfModel2Bytes(),
                                state.getModel1Type(), state.getModel2Type());
                        logger.info("[CellTune] Auto-saved binary classifier state for '{}'", activeBinaryMarker);
                    } catch (Exception ex) {
                        logger.warn("Failed to auto-save binary state for '{}': {}", activeBinaryMarker, ex.getMessage());
                    }
                }
            }
        });
        classificationPanel.setAutoClassifyCallback(() -> autoClassifyCurrentImage(qupath));
        classificationPanel.setOnExitBinaryMode(() -> exitBinaryMode(qupath));
        classificationPanel.setOnApplyToImages(() -> applyBinaryClassifierToImages(qupath));

        // Listen for image changes so we can save/reset/load state per image.
        // Store the listener reference so it can be removed if the extension is ever reloaded.
        imageDataListener = (obs, oldData, newData) -> handleImageChange(qupath, oldData, newData);
        qupath.imageDataProperty().addListener(imageDataListener);

        // Dock into QuPath's analysis pane
        dockPane = new javafx.scene.control.TitledPane(EXTENSION_NAME, classificationPanel);
        dockPane.setCollapsible(true);
        dockPane.setExpanded(false);
        dockTab = new javafx.scene.control.Tab(EXTENSION_NAME, dockPane);
        dockTab.setClosable(false);
        qupath.getAnalysisTabPane().getTabs().add(dockTab);
    }

    /**
     * Called when the user switches images. Saves labels for the old image
     * (filtered to its detections), resets transient prediction state, and
     * loads any persisted labels for the new image.
     */
    private void handleImageChange(QuPathGUI qupath,
                                   qupath.lib.images.ImageData<BufferedImage> oldData,
                                   qupath.lib.images.ImageData<BufferedImage> newData) {
        var project = qupath.getProject();

        // ── Save labels for the OLD image (filtered) ──
        if (oldData != null && project != null) {
            var oldEntry = project.getEntry(oldData);
            if (oldEntry != null) {
                // Save labels
                if (labelStore != null && labelStore.size() > 0) {
                    var filteredStore = filterLabelStoreToImage(labelStore, oldData);
                    if (filteredStore.size() > 0) {
                        try {
                            ProjectStateManager.saveImageLabels(
                                    project, oldEntry.getImageName(), filteredStore);
                        } catch (IOException ex) {
                            logger.warn("Failed to save labels for {} on image switch: {}",
                                    oldEntry.getImageName(), ex.getMessage());
                        }
                    }
                }
                // Save sampled cell IDs (independent of labels)
                if (lastSampledCellIds != null && !lastSampledCellIds.isEmpty()) {
                    try {
                        ProjectStateManager.saveImageSampledCells(project, oldEntry.getImageName(), lastSampledCellIds);
                    } catch (IOException ex) {
                        logger.warn("Failed to save sampled cells for {} on image switch: {}",
                                oldEntry.getImageName(), ex.getMessage());
                    }
                }

                // Save predictions for the old image so confidence is available
                // immediately when returning to it.
                if (predAll != null && predAll.size() > 0) {
                    try {
                        ProjectStateManager.saveImagePredictions(project, oldEntry.getImageName(), predAll);
                    } catch (IOException ex) {
                        logger.warn("Failed to save predictions for {} on image switch: {}",
                                oldEntry.getImageName(), ex.getMessage());
                    }
                }
            }
        }

        // ── Reset transient state ──
        this.predAll = null;
        this.lastAgreementRates = null;
        this.lastSampledCellIds = null;

        // ── Load labels for the NEW image (if any) ──
        if (newData != null && project != null) {
            var newEntry = project.getEntry(newData);
            if (newEntry != null) {
                LabelStore loaded = null;
                try {
                    loaded = ProjectStateManager.loadImageLabels(
                            project, newEntry.getImageName());
                } catch (IOException ex) {
                    logger.warn("Failed to load labels for {}: {}",
                            newEntry.getImageName(), ex.getMessage());
                }
                this.labelStore = (loaded != null) ? loaded : new LabelStore("CellTune");
                // Also pick up any annotation-based labels on the new image
                collectLabelsFromAnnotations(qupath, this.labelStore);

                // Load sampled cell IDs for new image
                try {
                    List<String> sampledIds = ProjectStateManager.loadImageSampledCells(project, newEntry.getImageName());
                    this.lastSampledCellIds = sampledIds;
                } catch (IOException ex) {
                    logger.warn("Failed to load sampled cell IDs for {}: {}", newEntry.getImageName(), ex.getMessage());
                }

                // Load per-image predictions if available.
                try {
                    this.predAll = ProjectStateManager.loadImagePredictions(project, newEntry.getImageName());
                } catch (IOException ex) {
                    logger.warn("Failed to load predictions for {}: {}",
                            newEntry.getImageName(), ex.getMessage());
                }
            } else {
                this.labelStore = new LabelStore("CellTune");
                this.lastSampledCellIds = null;
                this.predAll = null;
            }
            logger.info("Switched to image '{}' — {} labels loaded",
                    newEntry != null ? newEntry.getImageName() : "unknown",
                    labelStore.size());
        } else {
            this.labelStore = new LabelStore("CellTune");
            this.lastSampledCellIds = null;
            this.predAll = null;
        }

        // ── Try to restore feature selection and normalization from project state ──
        if (project != null) {
            try {
                var state = ProjectStateManager.loadState(project);
                if (state != null) {
                    if (state.selectedFeatures != null) this.selectedFeatures = new ArrayList<>(state.selectedFeatures);
                    this.importedTrainingFeatureNames = ProjectStateManager.getImportedTrainingFeatureNames(state);
                    this.importedTrainingRows = ProjectStateManager.decodeImportedTrainingRows(state);
                    if (state.featureTransforms != null || state.arcsinhCofactor != null) {
                        FeatureNormalizer norm = new FeatureNormalizer();
                        if (state.featureTransforms != null) norm.fromTransformMap(state.featureTransforms);
                        if (state.arcsinhCofactor != null) norm.setArcsinhCofactor(state.arcsinhCofactor);
                        this.featureNormalizer = norm;
                    }
                    // Restore trained classifier from saved model bytes
                    if (classifier == null || !classifier.isTrained()) {
                        byte[] xgbBytes = ProjectStateManager.decodeXGBoostModel(state);
                        byte[] lgbBytes = ProjectStateManager.decodeLightGBMModel(state);
                        byte[] rf1Bytes = ProjectStateManager.decodeRFModel1(state);
                        byte[] rf2Bytes = ProjectStateManager.decodeRFModel2(state);
                        if (xgbBytes != null || lgbBytes != null || rf1Bytes != null || rf2Bytes != null) {
                            var classifierState = new ClassifierState(
                                    state.name != null ? state.name : "CellTune",
                                    state.featureNames, state.classNames,
                                    xgbBytes, lgbBytes, rf1Bytes, rf2Bytes,
                                    ProjectStateManager.getModel1Type(state),
                                    ProjectStateManager.getModel2Type(state));
                            classifier = new DualModelClassifier();
                            classifier.loadFromState(classifierState);
                            logger.info("[CellTune] Restored trained classifier from saved state.");
                        }
                    }
                }
            } catch (Exception ex) {
                logger.warn("Failed to restore feature selection/normalization: {}", ex.getMessage());
            }
        }
        // ── Sync labels with current QuPath class list ──
        syncLabelsToCurrentClasses(qupath);
        // ── Push reset state into panel ──
        syncPanelState();
    }

    /** Push current extension state into the docked panel. */
    private void syncPanelState() {
        if (classificationPanel == null) return;
        classificationPanel.setLabelStore(labelStore);
        classificationPanel.setClassifier(classifier);
        classificationPanel.setSelectedFeatures(selectedFeatures);
        classificationPanel.setFeatureNormalizer(featureNormalizer);
        classificationPanel.setCellTypeTable(cellTypeTable);
        classificationPanel.setPredAll(predAll);
        classificationPanel.setLastAgreementRates(lastAgreementRates);
        classificationPanel.setLastSampledCellIds(lastSampledCellIds);
        classificationPanel.setImportedTrainingData(importedTrainingRows, importedTrainingFeatureNames);
        classificationPanel.setActiveBinaryMarker(activeBinaryMarker);
    }

    /** Persist Pred_ALL for the current image so manual mode can show confidence after reload. */
    private void persistCurrentImagePredictions(QuPathGUI qupath) {
        if (qupath == null || qupath.getProject() == null || qupath.getImageData() == null
                || predAll == null || predAll.size() == 0) {
            return;
        }

        var project = qupath.getProject();
        var entry = project.getEntry(qupath.getImageData());
        if (entry == null) return;

        try {
            ProjectStateManager.saveImagePredictions(project, entry.getImageName(), predAll);
        } catch (IOException ex) {
            logger.warn("Failed to save predictions for {}: {}",
                    entry.getImageName(), ex.getMessage());
        }
    }

    /** Load Pred_ALL for the current image if it was previously saved. */
    private boolean loadCurrentImagePredictions(QuPathGUI qupath) {
        if (qupath == null || qupath.getProject() == null || qupath.getImageData() == null) {
            return false;
        }

        var project = qupath.getProject();
        var entry = project.getEntry(qupath.getImageData());
        if (entry == null) return false;

        try {
            var loaded = ProjectStateManager.loadImagePredictions(project, entry.getImageName());
            if (loaded != null && loaded.size() > 0) {
                this.predAll = loaded;
                syncPanelState();
                return true;
            }
        } catch (IOException ex) {
            logger.warn("Failed to load predictions for {}: {}",
                    entry.getImageName(), ex.getMessage());
        }
        return false;
    }

    /**
     * Synchronise the label store with the current QuPath class list.
     * Removes any labels whose class name no longer exists in the project's class list.
     * This ensures removed/renamed classes don't persist as stale labels.
     */
    private void syncLabelsToCurrentClasses(QuPathGUI qupath) {
        if (labelStore == null || labelStore.size() == 0) return;

        var project = qupath.getProject();
        if (project == null) return;

        Set<String> validClasses = new java.util.LinkedHashSet<>();
        for (var pc : project.getPathClasses()) {
            if (pc != null && pc.getName() != null && !pc.getName().isEmpty()) {
                validClasses.add(pc.getName());
            }
        }

        if (validClasses.isEmpty()) return; // No classes defined — don't purge everything

        int removed = labelStore.retainClasses(validClasses);
        if (removed > 0) {
            logger.info("Removed {} labels for classes no longer in QuPath class list", removed);
        }
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

        MenuItem featureImportanceItem = new MenuItem("Feature Importance...");
        featureImportanceItem.setOnAction(e -> showFeatureImportance(qupath));
        featureImportanceItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem exportItem = new MenuItem(resources.getString("menu.export"));
        exportItem.setOnAction(e -> exportCellTable(qupath));
        exportItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem exportAnnDataItem = new MenuItem("Export AnnData (CSV + H5AD script)");
        exportAnnDataItem.setOnAction(e -> exportAnnData(qupath));
        exportAnnDataItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem importMarkersItem = new MenuItem(resources.getString("menu.import.markers"));
        importMarkersItem.setOnAction(e -> importMarkerTable(qupath));
        importMarkersItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem autoLandmarkItem = new MenuItem("Auto Landmark (Gating)");
        autoLandmarkItem.setOnAction(e -> runAutoLandmarking(qupath));
        autoLandmarkItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem exportGtItem = new MenuItem(resources.getString("menu.export.groundtruth"));
        exportGtItem.setOnAction(e -> exportGroundTruth(qupath));
        exportGtItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem importGtItem = new MenuItem(resources.getString("menu.import.groundtruth"));
        importGtItem.setOnAction(e -> importGroundTruth(qupath));
        importGtItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem featuresItem = new MenuItem(resources.getString("menu.features"));
        featuresItem.setOnAction(e -> showFeatureSelection(qupath));
        featuresItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem normalizeItem = new MenuItem("Normalise Features");
        normalizeItem.setOnAction(e -> showNormalization(qupath));
        normalizeItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem binaryItem = new MenuItem("Binary Classifiers...");
        binaryItem.setOnAction(e -> showBinaryClassifiers(qupath));
        binaryItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem compositeItem = new MenuItem("Composite Classification...");
        compositeItem.setOnAction(e -> showCompositeClassification(qupath));
        compositeItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem compositeExportItem = new MenuItem("Composite Classification...");
        compositeExportItem.setOnAction(e -> showCompositeClassification(qupath));
        compositeExportItem.disableProperty().bind(enableExtensionProperty.not());

        menu.getItems().addAll(
                classifyItem,
                binaryItem,
                compositeItem,
                featuresItem,
                normalizeItem,
                new SeparatorMenuItem(),
                reviewItem,
                manualLabelItem,
                confusionsItem,
                featureImportanceItem,
                new SeparatorMenuItem(),
                importMarkersItem,
                autoLandmarkItem,
                exportItem,
                exportAnnDataItem,
                compositeExportItem,
                new SeparatorMenuItem(),
                exportGtItem,
                importGtItem
        );
    }

    // ── Placeholder actions (wired in later phases) ────────────────────────────

    private void showFeatureImportance(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }
        // Snapshot the classifier field now (on the FX thread) so the background
        // thread uses a stable reference even if retraining is triggered later.
        final DualModelClassifier snap = classifier;
        if (snap == null || !snap.isTrained()) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "No trained classifier found. Run CellTune Classification first.");
            return;
        }
        var detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No detections found.");
            return;
        }
        List<String> featureNames = snap.getFeatureNames();
        if (featureNames == null || featureNames.isEmpty()) return;

        CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
        extractor.setNormalizer(featureNormalizer);

        Thread worker = new Thread(() -> {
            try {
                var result = snap.computeFeatureImportance(detections, extractor);
                javafx.application.Platform.runLater(() ->
                        new qupath.ext.celltune.ui.FeatureImportanceView(
                                qupath.getStage(), result).show());
            } catch (Throwable ex) {
                logger.error("Feature importance failed", ex);
                javafx.application.Platform.runLater(() ->
                        Dialogs.showErrorMessage(EXTENSION_NAME,
                                "Feature importance failed: " + ex.getMessage()));
            }
        }, "CellTune-FeatureImportance");
        worker.setDaemon(true);
        worker.start();
    }

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

        // If not enough labels, try to load from persisted image labels
        if (labelStore.size() < 10) {
            var project = qupath.getProject();
            if (project != null) {
                var imgEntry = project.getEntry(imageData);
                if (imgEntry != null) {
                    try {
                        var savedLabels = ProjectStateManager.loadImageLabels(project, imgEntry.getImageName());
                        if (savedLabels != null && savedLabels.size() > labelStore.size()) {
                            labelStore.mergeFrom(savedLabels);
                            logger.info("Loaded {} saved labels for current image", labelStore.size());
                        }
                    } catch (IOException ex) {
                        logger.warn("Failed to load saved labels: {}", ex.getMessage());
                    }
                }
                // Also try the global classifier state labels
                if (labelStore.size() < 10) {
                    try {
                        var state = ProjectStateManager.loadState(project);
                        if (state != null && state.labels != null && state.labels.size() > labelStore.size()) {
                            labelStore.mergeFrom(new LabelStore("saved", state.labels));
                            logger.info("Loaded {} labels from classifier state", labelStore.size());
                        }
                    } catch (IOException ex) {
                        logger.warn("Failed to load classifier state labels: {}", ex.getMessage());
                    }
                }
            }
        }

        var currentImageLabelStore = filterLabelStoreToImage(labelStore, imageData);
        int currentImageLabelCount = currentImageLabelStore.size();
        int importedRowCount = importedTrainingRows != null ? importedTrainingRows.size() : 0;

        if (currentImageLabelCount < 10 && importedRowCount == 0) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                "Need at least 10 labelled cells to train, or imported training rows. Found "
                + currentImageLabelCount + " labelled cells in current image and "
                + importedRowCount + " imported rows."
                + "\nUse point annotations placed on detections to label cells, "
                + "or import training data.");
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

        var model1Combo = new javafx.scene.control.ComboBox<ModelType>();
        model1Combo.getItems().addAll(ModelType.values());
        model1Combo.setValue(ModelType.XGBOOST);
        model1Combo.setMaxWidth(Double.MAX_VALUE);
        var model2Combo = new javafx.scene.control.ComboBox<ModelType>();
        model2Combo.getItems().addAll(ModelType.values());
        model2Combo.setValue(ModelType.LIGHTGBM);
        model2Combo.setMaxWidth(Double.MAX_VALUE);
        var modelRow = new javafx.scene.layout.HBox(8,
                new javafx.scene.control.Label("Model 1:"), model1Combo,
                new javafx.scene.control.Label("Model 2:"), model2Combo);
        modelRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var resamplingCombo = new javafx.scene.control.ComboBox<ResamplingStrategy>();
        resamplingCombo.getItems().addAll(ResamplingStrategy.values());
        resamplingCombo.setValue(ResamplingStrategy.NONE);
        resamplingCombo.setMaxWidth(Double.MAX_VALUE);
        var resamplingLabel = new javafx.scene.control.Label("Resampling:");
        var resamplingRow = new javafx.scene.layout.HBox(6, resamplingLabel, resamplingCombo);
        resamplingRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        var autoTuneCheckBox = new javafx.scene.control.CheckBox(
                "Auto-tune hyperparameters (TPE Bayesian optimisation)");
        autoTuneCheckBox.setSelected(false);

        var earlyStopCheckBox = new javafx.scene.control.CheckBox(
                "Early stopping (find optimal boosting rounds)");
        earlyStopCheckBox.setSelected(false);

        var featureImportanceCheckBox = new javafx.scene.control.CheckBox(
                "Show top 10 feature importance after training");
        featureImportanceCheckBox.setSelected(false);
        featureImportanceCheckBox.setTooltip(new javafx.scene.control.Tooltip(
                "After training, compute mean |SHAP| values and display a per-class bar chart"));

        var confirmAlert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle(EXTENSION_NAME);
        confirmAlert.setHeaderText("Ready to Train");
        String confirmMsg = featureNames.size() + " features, "
            + currentImageLabelCount + " labelled cells in current image.";
        if (importedRowCount > 0) {
            confirmMsg += "\nImported training rows available: " + importedRowCount + ".";
        }
        if (hasMultipleImages) {
            confirmMsg += "\n\nThis project has " + allImageNames.size()
                    + " images. Check the box below to include labelled\n"
                    + "cells from all images in the training set.";
        }
        confirmAlert.setContentText(confirmMsg);
        if (hasMultipleImages) {
            confirmAlert.getDialogPane().setExpandableContent(null);
            var contentBox = new javafx.scene.layout.VBox(8,
                    new javafx.scene.control.Label(confirmMsg), poolCheckBox,
                    modelRow, resamplingRow, autoTuneCheckBox, earlyStopCheckBox,
                    featureImportanceCheckBox);
            contentBox.setPadding(new javafx.geometry.Insets(4));
            confirmAlert.getDialogPane().setContent(contentBox);
        } else {
            confirmAlert.getDialogPane().setExpandableContent(null);
            var contentBox = new javafx.scene.layout.VBox(8,
                    new javafx.scene.control.Label(confirmMsg),
                    modelRow, resamplingRow, autoTuneCheckBox, earlyStopCheckBox,
                    featureImportanceCheckBox);
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
        final boolean autoTuneHyperparams = autoTuneCheckBox.isSelected();
        final boolean earlyStopEnabled = earlyStopCheckBox.isSelected();
        final boolean showFeatureImportance = featureImportanceCheckBox.isSelected();
        final ModelType model1Type = model1Combo.getValue();
        final ModelType model2Type = model2Combo.getValue();

        // Check whether the JVM has enough heap for this dataset
        if (!checkTrainingMemory(detections.size(), featureNames.size())) return;

        CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
        extractor.setNormalizer(featureNormalizer);
        if (classifier == null) {
            classifier = new DualModelClassifier();
        }
        classifier.setModel1Type(model1Type);
        classifier.setModel2Type(model2Type);

        // Use pre-selected images (from "Apply to Images...") if set; otherwise prompt
        List<String> selectedImages;
        if (!binaryTargetImages.isEmpty()) {
            selectedImages = binaryTargetImages;
        } else if (allImageNames.size() > 1) {
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
        final LabelStore currentImageLabelStoreFinal = currentImageLabelStore;
        final List<GroundTruthIO.TrainingRow> importedRowsSnapshot =
            importedTrainingRows == null ? null : List.copyOf(importedTrainingRows);
        final List<String> importedFeatureNamesSnapshot =
            importedTrainingFeatureNames == null ? null : List.copyOf(importedTrainingFeatureNames);

        // ── Progress dialog ─────────────────────────────────────────────────
        var progressStage = new javafx.stage.Stage();
        progressStage.setTitle("CellTune — Training");
        progressStage.initOwner(qupath.getStage());
        progressStage.initModality(javafx.stage.Modality.NONE);
        progressStage.setResizable(false);
        progressStage.setAlwaysOnTop(true);

        var progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setPrefWidth(500);
        var statusLabel = new javafx.scene.control.Label("Initialising…");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(500);
        var logArea = new javafx.scene.control.TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(150);
        logArea.setPrefWidth(500);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        var progressBox = new javafx.scene.layout.VBox(8, statusLabel, progressBar, logArea);
        progressBox.setPadding(new javafx.geometry.Insets(15));
        progressStage.setScene(new javafx.scene.Scene(progressBox));
        progressStage.show();

        // Reset and bind progress bar and status label to classifier properties
        classifier.resetProgress();
        progressBar.progressProperty().bind(classifier.progressProperty());
        statusLabel.textProperty().bind(classifier.statusProperty());

        // Run training on a background daemon thread
        Thread trainThread = new Thread(() -> {
            try {
                // Auto-backup labels before training
                if (project != null) {
                    ProjectStateManager.backupLabels(project, currentImageLabelStoreFinal);
                }

                // Collect supplementary training data from other project images
                List<float[]> supplementaryRows = new java.util.ArrayList<>();
                List<String> supplementaryLabels = new java.util.ArrayList<>();

                // Include imported training rows (feature-name aligned).
                if (importedRowsSnapshot != null && !importedRowsSnapshot.isEmpty()
                        && importedFeatureNamesSnapshot != null && !importedFeatureNamesSnapshot.isEmpty()) {
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

                        if (added > 0) {
                            int addedFinal = added;
                            int mapped = mappedFeatureCount;
                            javafx.application.Platform.runLater(() ->
                                logArea.appendText("Merged " + addedFinal + " imported training rows ("
                                            + mapped + "/" + finalFeatureNames.size()
                                            + " features aligned).\n"));
                        }
                    }
                }

                if (poolAllImages && project != null) {
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

                        // Fast check: skip images with no saved label file to avoid
                        // the expensive readImageData() call for unlabelled images.
                        if (!ProjectStateManager.hasImageLabels(project, entry.getImageName())) continue;

                        try {
                            // Load saved labels first (no image I/O required)
                            LabelStore otherLabels = new LabelStore("temp");
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

                            // Only now open the image to extract features
                            var otherImageData = entry.readImageData();
                            var otherHierarchy = otherImageData.getHierarchy();
                            var otherDetections = otherHierarchy.getDetectionObjects();
                            if (otherDetections.isEmpty()) continue;

                            var otherExtractor = new CellFeatureExtractor(finalFeatureNames);
                            otherExtractor.setNormalizer(featureNormalizer);
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

                List<float[]> supplementaryRowsArg = supplementaryRows.isEmpty() ? null : supplementaryRows;
                List<String> supplementaryLabelsArg = supplementaryLabels.isEmpty() ? null : supplementaryLabels;

                classifier.trainAndPredict(detections, currentImageLabelStoreFinal, extractor,
                    supplementaryRowsArg, supplementaryLabelsArg,
                        resamplingStrategy,
                        autoTuneHyperparams,
                        earlyStopEnabled,
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
                            currentImageLabelStoreFinal, state.getFeatureNames(), state.getClassNames(),
                            state.getXgboostBytes(), state.getLightgbmBytes(),
                            state.getRfModel1Bytes(), state.getRfModel2Bytes(),
                            state.getModel1Type(), state.getModel2Type(),
                            importedTrainingFeatureNames, importedTrainingRows);

                    // Save per-image labels for cross-image pooling
                    if (currentImageNameFinal != null) {
                        ProjectStateManager.saveImageLabels(
                                project, currentImageNameFinal, currentImageLabelStoreFinal);
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
                            otherExtractor.setNormalizer(featureNormalizer);
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
                        if (showFeatureImportance) showFeatureImportance(qupath);
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
                        if (showFeatureImportance) showFeatureImportance(qupath);
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

        // Filter to only cell IDs that belong to this image's detections
        var filteredStore = filterLabelStoreToImage(labelStore, imageData);
        if (filteredStore.size() == 0) return;

        try {
            ProjectStateManager.saveImageLabels(project, entry.getImageName(), filteredStore);
        } catch (IOException ex) {
            logger.warn("Failed to save per-image labels for {}: {}",
                    entry.getImageName(), ex.getMessage());
        }
    }

    /**
     * Create a copy of the label store containing only cell IDs that exist
     * as detections in the given image. This prevents stale IDs from other
     * images leaking into per-image label files.
     */
    private static LabelStore filterLabelStoreToImage(
            LabelStore store, qupath.lib.images.ImageData<BufferedImage> imageData) {
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
        var sourceByName = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < sourceFeatureNames.size(); i++) {
            sourceByName.put(sourceFeatureNames.get(i).strip().toLowerCase(java.util.Locale.ROOT), i);
        }

        int[] map = new int[targetFeatureNames.size()];
        for (int i = 0; i < targetFeatureNames.size(); i++) {
            String key = targetFeatureNames.get(i).strip().toLowerCase(java.util.Locale.ROOT);
            map[i] = sourceByName.getOrDefault(key, -1);
        }
        return map;
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

        // First try to restore saved predictions for this image.
        if (predAll == null || predAll.size() == 0) {
            loadCurrentImagePredictions(qupath);
        }

        // If a trained classifier exists, ensure predictions are available for
        // the current image so manual mode can show per-cell confidence scores.
        if (predAll == null || predAll.size() == 0) {
            autoClassifyCurrentImage(qupath);
        }

        // Gather extra class names from CellTypeTable if loaded
        java.util.Set<String> extraClasses = null;
        if (cellTypeTable != null) {
            extraClasses = cellTypeTable.getCellTypes();
        }

        var toolbar = new ManualLabelToolbar(
            qupath, labelStore, extraClasses, qupath.getStage(), predAll);

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

    /**
     * If a trained classifier exists but predictions have not been run on the
     * current image (e.g. after an image switch), automatically classify all
     * cells so that review mode, confusion matrices and sampling work
     * immediately without retraining.
     *
     * @return true if predictions are now available (existing or freshly generated)
     */
    private boolean autoClassifyCurrentImage(QuPathGUI qupath) {
        if (predAll != null && predAll.size() > 0) {
            return true;  // already have predictions
        }
        if (classifier == null || !classifier.isTrained()) {
            return false;  // no trained classifier
        }
        var imageData = qupath.getImageData();
        if (imageData == null) return false;

        var detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) return false;

        // Use the feature columns from the trained classifier
        List<String> featureNames = classifier.getFeatureNames();
        if (featureNames == null || featureNames.isEmpty()) return false;

        try {
            var extractor = new CellFeatureExtractor(featureNames);
            if (featureNormalizer != null) {
                extractor.setNormalizer(featureNormalizer);
            }
            classifier.predictOnly(detections, extractor, true,
                    msg -> logger.info("[CellTune] Auto-classify: {}", msg));
            predAll = classifier.getPredALL();
                persistCurrentImagePredictions(qupath);
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
            syncPanelState();
            logger.info("[CellTune] Auto-classified {} cells on current image.",
                    predAll != null ? predAll.size() : 0);
            return predAll != null && predAll.size() > 0;
        } catch (Exception e) {
            logger.error("[CellTune] Auto-classify failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private void showReviewMode(QuPathGUI qupath) {
        if (qupath.getProject() == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("classify.no_project"));
            return;
        }

        // Try to load persisted sampled cell IDs if not in memory
        if ((lastSampledCellIds == null || lastSampledCellIds.isEmpty()) && qupath.getProject() != null) {
            var imgData = qupath.getImageData();
            if (imgData != null) {
                var entry = qupath.getProject().getEntry(imgData);
                if (entry != null) {
                    try {
                        var loaded = ProjectStateManager.loadImageSampledCells(qupath.getProject(), entry.getImageName());
                        if (loaded != null && !loaded.isEmpty()) {
                            this.lastSampledCellIds = loaded;
                            syncPanelState();
                        }
                    } catch (IOException ex) {
                        logger.warn("Failed to load sampled cells: {}", ex.getMessage());
                    }
                }
            }
        }

        // Auto-classify if we have a trained classifier but no predictions yet
        autoClassifyCurrentImage(qupath);

        // Always prompt the user to choose how many cells to review
        if (predAll != null && predAll.size() > 0 && classifier != null) {
            long disagreeCount = predAll.getDisagreementCount();
            if (disagreeCount == 0) {
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "Perfect agreement — no disagreement cells to review.");
                return;
            }
            // Compute agreement rates if not yet available
            if (lastAgreementRates == null) {
                var confView = new ConfusionMatrixView(qupath.getStage(), predAll, classifier.getClassNames());
                lastAgreementRates = confView.getAgreementRates();
            }
            String countStr = Dialogs.showInputDialog(
                    resources.getString("sample.dialog.title"),
                    "How many disagreement cells to review?"
                            + " (" + disagreeCount + " available)",
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
                    predAll, classifier.getClassNames(), lastAgreementRates, sampleSize);
            syncPanelState();

            // Persist sampled cell IDs
            if (qupath.getProject() != null && qupath.getImageData() != null) {
                var imgEntry = qupath.getProject().getEntry(qupath.getImageData());
                if (imgEntry != null) {
                    try {
                        ProjectStateManager.saveImageSampledCells(
                                qupath.getProject(), imgEntry.getImageName(), lastSampledCellIds);
                    } catch (Exception ex) {
                        logger.warn("Failed to save sampled cells: {}", ex.getMessage());
                    }
                }
            }
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
        stage.setMinWidth(750);

        // When the review window is closed, merge labels back
        stage.setOnHidden(e -> {
            reviewController.removeHighlight();
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
        // Auto-classify if we have a trained classifier but no predictions yet
        autoClassifyCurrentImage(qupath);

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
    }

    /** Export feature options chosen by the user. */
    private record ExportFeatureOptions(boolean includeRaw, boolean includeNorm) {}

    /**
     * Show a dialog letting the user choose which feature columns to include.
     * Only shown when a normaliser is active — otherwise raw-only is returned
     * without prompting.
     *
     * @return options, or null if the user cancelled
     */
    private ExportFeatureOptions askExportFeatureOptions() {
        if (featureNormalizer == null) {
            return new ExportFeatureOptions(true, false);
        }
        var rawCb = new javafx.scene.control.CheckBox("Include raw feature values");
        rawCb.setSelected(true);
        var normCb = new javafx.scene.control.CheckBox("Include normalised feature values");
        normCb.setSelected(true);
        var box = new javafx.scene.layout.VBox(8, rawCb, normCb);
        box.setPadding(new javafx.geometry.Insets(8));

        var alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle(EXTENSION_NAME);
        alert.setHeaderText("Export Feature Options");
        alert.setContentText("Choose which feature columns to include in the export.");
        alert.getDialogPane().setExpandableContent(null);
        alert.getDialogPane().setContent(box);
        var result = alert.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return null;
        }
        boolean raw = rawCb.isSelected();
        boolean norm = normCb.isSelected();
        if (!raw && !norm) {
            raw = true; // fall back to raw if neither selected
        }
        return new ExportFeatureOptions(raw, norm);
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

        ExportFeatureOptions opts = askExportFeatureOptions();
        if (opts == null) return;

        try {
            Collection<PathObject> cells = imageData.getHierarchy()
                    .getObjects(null, PathObject.class).stream()
                    .filter(PathObjectFilter.DETECTIONS_ALL)
                    .toList();
            // Build extractor so feature values (raw + normalised) are included
            CellFeatureExtractor extractor = null;
            List<String> feats = selectedFeatures != null && !selectedFeatures.isEmpty()
                    ? selectedFeatures
                    : CellFeatureExtractor.discoverFeatureNames(cells);
            if (!feats.isEmpty()) {
                extractor = new CellFeatureExtractor(feats);
                extractor.setNormalizer(featureNormalizer);
            }
            CellTableExporter.export(chosen.toPath(), cells, predAll, labelStore,
                    extractor, opts.includeRaw(), opts.includeNorm());
            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "Exported " + cells.size() + " cells to " + chosen.getName());
        } catch (IOException ex) {
            logger.error("Failed to export cell table", ex);
            Dialogs.showErrorMessage(EXTENSION_NAME, "Export failed: " + ex.getMessage());
        }
    }

    private void exportAnnData(QuPathGUI qupath) {
        if (qupath.getProject() == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("classify.no_project"));
            return;
        }
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }

        // Need feature names — use selected features or discover all
        List<String> feats = selectedFeatures;
        if (feats == null || feats.isEmpty()) {
            var detections = imageData.getHierarchy()
                    .getObjects(null, PathObject.class).stream()
                    .filter(PathObjectFilter.DETECTIONS_ALL)
                    .toList();
            feats = CellFeatureExtractor.discoverFeatureNames(detections);
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Export AnnData-compatible CSV");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        var project = qupath.getProject();
        if (project != null && project.getPath() != null) {
            File dir = project.getPath().getParent().toFile();
            if (dir.isDirectory()) fc.setInitialDirectory(dir);
        }
        fc.setInitialFileName("celltune_anndata.csv");
        File chosen = fc.showSaveDialog(qupath.getStage());
        if (chosen == null) return;

        ExportFeatureOptions opts = askExportFeatureOptions();
        if (opts == null) return;

        try {
            Collection<PathObject> cells = imageData.getHierarchy()
                    .getObjects(null, PathObject.class).stream()
                    .filter(PathObjectFilter.DETECTIONS_ALL)
                    .toList();
            CellFeatureExtractor extractor = new CellFeatureExtractor(feats);
            extractor.setNormalizer(featureNormalizer);
            String imageName = null;
            var entry = project.getEntry(imageData);
            if (entry != null) imageName = entry.getImageName();

            AnnDataExporter.export(chosen.toPath(), cells, extractor,
                    predAll, labelStore, imageName, opts.includeRaw(), opts.includeNorm());
            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "Exported " + cells.size() + " cells. "
                    + "Run convert_to_h5ad.py to generate H5AD file.");
        } catch (IOException ex) {
            logger.error("Failed to export AnnData", ex);
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

    // ── Auto-landmarking (gating) ──────────────────────────────────────────────

    private void runAutoLandmarking(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }

        Collection<PathObject> detections = imageData.getHierarchy().getDetectionObjects();
        if (detections.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "No detections found. Run cell detection first.");
            return;
        }

        if (cellTypeTable == null || !cellTypeTable.hasGatingRules()) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "No gating rules loaded. Import a CellTypeTable CSV with "
                    + "PrimaryMarker/SecondaryMarker/TertiaryMarker columns first.\n\n"
                    + "Use 'Import Marker Table' and select a rule-format CSV.");
            return;
        }

        // Discover available measurement names to match channels
        List<String> allMeasurements = CellFeatureExtractor.discoverFeatureNames(detections);

        // Get channels from the rule table
        List<String> ruleChannels = cellTypeTable.getAllRuleChannels();

        // Ask user for gating mode and measurement suffix
        var modeCombo = new javafx.scene.control.ComboBox<AutoLandmarker.Mode>();
        modeCombo.getItems().addAll(AutoLandmarker.Mode.values());
        modeCombo.setValue(AutoLandmarker.Mode.INTENSITY);
        modeCombo.setMaxWidth(Double.MAX_VALUE);

        // Try to auto-detect the measurement suffix
        String detectedSuffix = detectMeasurementSuffix(allMeasurements, ruleChannels);
        var suffixField = new javafx.scene.control.TextField(
                detectedSuffix != null ? detectedSuffix : ": Mean");
        suffixField.setPromptText("e.g. ': Mean' or '__Mean__Cell'");

        var probSuffixField = new javafx.scene.control.TextField("__Probability");
        probSuffixField.setPromptText("e.g. '__Probability'");

        var minCellsField = new javafx.scene.control.TextField(
                String.valueOf(AutoLandmarker.DEFAULT_MIN_CELLS));

        var grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new javafx.geometry.Insets(8));
        grid.add(new javafx.scene.control.Label("Gating mode:"), 0, 0);
        grid.add(modeCombo, 1, 0);
        grid.add(new javafx.scene.control.Label("Intensity suffix:"), 0, 1);
        grid.add(suffixField, 1, 1);
        grid.add(new javafx.scene.control.Label("Probability suffix:"), 0, 2);
        grid.add(probSuffixField, 1, 2);
        grid.add(new javafx.scene.control.Label("Min cells/type:"), 0, 3);
        grid.add(minCellsField, 1, 3);
        grid.add(new javafx.scene.control.Label(
                ruleChannels.size() + " channels, "
                + cellTypeTable.size() + " cell types"), 0, 4, 2, 1);

        var dialog = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        dialog.setTitle(EXTENSION_NAME + " — Auto Landmark");
        dialog.setHeaderText("Run automated gating to generate landmark cells");
        dialog.getDialogPane().setContent(grid);
        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }

        AutoLandmarker.Mode mode = modeCombo.getValue();
        String intensitySuffix = suffixField.getText().strip();
        String probabilitySuffix = probSuffixField.getText().strip();
        int minCells;
        try {
            minCells = Integer.parseInt(minCellsField.getText().strip());
        } catch (NumberFormatException e) {
            minCells = AutoLandmarker.DEFAULT_MIN_CELLS;
        }

        // Build gating rules from CellTypeTable
        List<GatingRule> rules = new ArrayList<>();
        for (String ct : cellTypeTable.getCellTypes()) {
            rules.add(new GatingRule(ct,
                    cellTypeTable.getPrimaryExpression(ct),
                    cellTypeTable.getSecondaryMarkers(ct),
                    cellTypeTable.getTertiaryMarkers(ct),
                    ruleChannels));
        }

        // Run soft-NOT promotion across all rule pairs (matching Python's convert_not_to_strict_not)
        for (int i = 0; i < rules.size(); i++) {
            for (int j = 0; j < rules.size(); j++) {
                if (i != j) {
                    rules.get(i).promoteOverlappingSoftNots(rules.get(j));
                }
            }
        }

        // Run landmarking on background thread
        final int finalMinCells = minCells;

        var progressStage = new javafx.stage.Stage();
        progressStage.setTitle("CellTune — Auto Landmark");
        progressStage.initOwner(qupath.getStage());
        progressStage.initModality(javafx.stage.Modality.NONE);
        progressStage.setResizable(false);
        progressStage.setAlwaysOnTop(true);

        var progressBar = new javafx.scene.control.ProgressBar(-1); // indeterminate
        progressBar.setPrefWidth(500);
        var statusLabel = new javafx.scene.control.Label("Starting auto-landmarking…");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(500);
        var logArea = new javafx.scene.control.TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(250);
        logArea.setPrefWidth(500);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        var progressBox = new javafx.scene.layout.VBox(8, statusLabel, progressBar, logArea);
        progressBox.setPadding(new javafx.geometry.Insets(15));
        progressStage.setScene(new javafx.scene.Scene(progressBox));
        progressStage.show();

        Thread landmarkThread = new Thread(() -> {
            try {
                var results = AutoLandmarker.computeLandmarks(
                        detections, rules, ruleChannels, mode,
                        intensitySuffix, probabilitySuffix, finalMinCells,
                        msg -> {
                            logger.info("[CellTune] {}", msg);
                            javafx.application.Platform.runLater(() ->
                                    logArea.appendText(msg + "\n"));
                        });

                // Convert landmarks to labels
                if (labelStore == null) {
                    labelStore = new LabelStore("CellTune");
                }

                int totalAdded = 0;
                for (var entry : results.entrySet()) {
                    String cellType = entry.getKey();
                    var landmark = entry.getValue();
                    for (PathObject cell : landmark.cells()) {
                        labelStore.setLabel(cell.getID().toString(), cellType);
                        cell.setPathClass(PathClass.fromString(cellType));
                        totalAdded++;
                    }
                }

                final int total = totalAdded;
                final int numTypes = results.size();
                javafx.application.Platform.runLater(() -> {
                    imageData.getHierarchy().fireHierarchyChangedEvent(this);
                    syncPanelState();
                    logArea.appendText("\nDone! " + total + " landmark cells across "
                            + numTypes + " cell types added to label store.\n");
                    statusLabel.setText("Complete — " + total + " landmarks generated.");
                    progressBar.setProgress(1.0);

                    // Save per-image labels
                    saveCurrentImageLabels(qupath);

                    Dialogs.showInfoNotification(EXTENSION_NAME,
                            "Auto-landmarking complete: " + total + " landmark cells across "
                            + numTypes + " cell types.");
                });

            } catch (Exception ex) {
                logger.error("Auto-landmarking failed", ex);
                javafx.application.Platform.runLater(() -> {
                    logArea.appendText("\nERROR: " + ex.getMessage() + "\n");
                    statusLabel.setText("Auto-landmarking failed!");
                    progressBar.setProgress(0);
                    Dialogs.showErrorMessage(EXTENSION_NAME,
                            "Auto-landmarking failed: " + ex.getMessage());
                });
            }
        }, "CellTune-AutoLandmark");
        landmarkThread.setDaemon(true);
        landmarkThread.start();
    }

    /**
     * Try to auto-detect the measurement suffix by checking which of common
     * suffixes produces matches for the rule channels.
     */
    private String detectMeasurementSuffix(List<String> measurements,
                                            List<String> ruleChannels) {
        String[] candidates = {": Mean", ": Cell: Mean", "__Mean__Cell", "__mean__cell", ""};
        Set<String> measSet = new HashSet<>(measurements);
        String bestSuffix = null;
        int bestCount = 0;
        for (String suffix : candidates) {
            int count = 0;
            for (String ch : ruleChannels) {
                if (measSet.contains(ch + suffix)) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                bestSuffix = suffix;
            }
        }
        return bestSuffix;
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

        ExportFeatureOptions opts = askExportFeatureOptions();
        if (opts == null) return;

        try {
            CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
            extractor.setNormalizer(featureNormalizer);
            String imgName = imageData.getServer().getMetadata().getName();
            GroundTruthIO.exportCSV(chosen.toPath(), detections, labelStore, extractor, imgName,
                    opts.includeRaw(), opts.includeNorm());
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
                var rows = GroundTruthIO.importCSVAsTrainingData(chosen.toPath());
                var featureNames = GroundTruthIO.readFeatureNames(chosen.toPath());

                if (featureNames.isEmpty()) {
                    Dialogs.showErrorMessage(EXTENSION_NAME,
                            "Imported file has no readable feature columns.");
                    return;
                }

                int loaded = rows.size();
                int total;
                String schemaNote = "";

                if (importedTrainingRows == null || importedTrainingRows.isEmpty()) {
                    importedTrainingRows = new ArrayList<>(rows);
                    importedTrainingFeatureNames = new ArrayList<>(featureNames);
                } else if (importedTrainingFeatureNames != null
                        && importedTrainingFeatureNames.equals(featureNames)) {
                    importedTrainingRows.addAll(rows);
                } else {
                    // Replace if schema differs; mixed schemas cannot be safely merged.
                    importedTrainingRows = new ArrayList<>(rows);
                    importedTrainingFeatureNames = new ArrayList<>(featureNames);
                    schemaNote = " Feature schema changed, so previous imported rows were replaced.";
                }

                total = importedTrainingRows.size();

                if (project != null) {
                    ProjectStateManager.saveImportedTrainingData(
                            project, importedTrainingFeatureNames, importedTrainingRows);
                }

                syncPanelState();

                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "Loaded " + loaded + " training rows ("
                        + featureNames.size() + " features). "
                        + "Imported rows available for training: " + total + "."
                        + schemaNote);
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

    // ── Normalization ──────────────────────────────────────────────────────────

    private void showNormalization(QuPathGUI qupath) {
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

        // Use selected features if available, otherwise discover all
        List<String> featureNames;
        if (selectedFeatures != null && !selectedFeatures.isEmpty()) {
            featureNames = selectedFeatures;
        } else {
            featureNames = CellFeatureExtractor.discoverFeatureNames(detections);
        }

        if (featureNames.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No cell measurements found.");
            return;
        }

        var pane = new NormalizationPane(qupath.getStage(), featureNames, featureNormalizer);
        FeatureNormalizer result = pane.showAndWait();
        if (result != null) {
            if (result.hasTransforms()) {
                featureNormalizer = result;
                long count = result.getAllTransforms().size();
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        count + " feature(s) will be normalised (cofactor="
                        + result.getArcsinhCofactor() + ").");
            } else {
                featureNormalizer = null;
                Dialogs.showInfoNotification(EXTENSION_NAME,
                        "Feature normalization cleared.");
            }
        }
    }

    // ── Binary classifier management ───────────────────────────────────────────

    /**
     * Show the Binary Classifiers management dialog.
     * Loads the registry from disk, wires callbacks, and shows the panel in a modal stage.
     */
    private void showBinaryClassifiers(QuPathGUI qupath) {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "Open a QuPath project first.");
            return;
        }
        try {
            binaryRegistry = BinaryClassifierRegistry.load(project);
        } catch (IOException ex) {
            logger.warn("Failed to load binary classifier registry: {}", ex.getMessage());
            binaryRegistry = new LinkedHashMap<>();
        }

        var panel = new BinaryClassifierPanel();
        panel.setMarkerNames(new ArrayList<>(binaryRegistry.keySet()));
        panel.setActiveBinaryMarker(activeBinaryMarker);

        panel.setOnRegisterMarker(markerName -> {
            try {
                BinaryClassifierRegistry.register(project, binaryRegistry, markerName);
            } catch (IOException ex) {
                logger.warn("Failed to register binary classifier '{}': {}", markerName, ex.getMessage());
            }
        });

        panel.setOnDeleteMarker(markerName -> {
            try {
                BinaryClassifierRegistry.remove(project, binaryRegistry, markerName);
                if (markerName.equals(activeBinaryMarker)) exitBinaryMode(qupath);
            } catch (IOException ex) {
                logger.warn("Failed to remove binary classifier '{}': {}", markerName, ex.getMessage());
            }
        });

        panel.setOnOpenMarker(markerName -> enterBinaryMode(qupath, markerName));
        panel.setOnExitBinaryMode(() -> exitBinaryMode(qupath));

        var stage = new javafx.stage.Stage();
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        stage.setTitle("Binary Classifiers \u2014 " + EXTENSION_NAME);
        stage.setScene(new javafx.scene.Scene(panel, 420, 340));
        stage.showAndWait();
    }

    private void applyBinaryClassifierToImages(QuPathGUI qupath) {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "Open a QuPath project first.");
            return;
        }

        @SuppressWarnings("unchecked")
        var entries = (java.util.List<ProjectImageEntry<java.awt.image.BufferedImage>>)
                (java.util.List<?>) project.getImageList();
        java.util.List<String> allImageNames = entries.stream()
                .map(ProjectImageEntry::getImageName)
                .collect(java.util.stream.Collectors.toList());
        if (allImageNames.isEmpty()) return;

        String currentImageName = null;
        var liveImageData = qupath.getImageData();
        if (liveImageData != null) {
            var curEntry = project.getEntry(liveImageData);
            if (curEntry != null) currentImageName = curEntry.getImageName();
        }

        // Pre-populate picker with any previously stored selection
        var imageSelector = new ImageSelectionPane(
                qupath.getStage(), allImageNames, currentImageName);
        java.util.List<String> selected = imageSelector.showAndWait();
        if (selected == null) return; // cancelled

        // Store selection — applied on next training run, no classification now
        binaryTargetImages = new java.util.ArrayList<>(selected);
        classificationPanel.setApplyToImagesCount(binaryTargetImages.size());
    }

    private void showCompositeClassification(QuPathGUI qupath) {
        new CompositeClassificationDialog(qupath).showAndWait();
    }

    /**
     * Enter binary mode for the given marker.
     * Saves the current multi-class state, loads the binary marker's labels and classifier,
     * swaps the panel state, and expands the docked panel.
     *
     * @param qupath     the QuPath GUI
     * @param markerName the sanitized marker name to activate
     */
    private void enterBinaryMode(QuPathGUI qupath, String markerName) {
        var project = qupath.getProject();
        if (project == null) return;

        // Preserve current multi-class state
        preBinaryLabelStore = labelStore;
        preBinaryClassifier = classifier;

        // Load binary marker's labels
        try {
            this.labelStore = ProjectStateManager.loadBinaryLabels(project, markerName);
        } catch (IOException ex) {
            logger.warn("Failed to load binary labels for '{}': {}", markerName, ex.getMessage());
            this.labelStore = new LabelStore(markerName);
        }

        // Load trained binary classifier if one exists
        this.classifier = null;
        try {
            ProjectStateManager.SavedState savedState = ProjectStateManager.loadBinaryState(project, markerName);
            if (savedState != null && (savedState.xgboostModelBase64 != null
                    || savedState.lightgbmModelBase64 != null
                    || savedState.rfModel1Base64 != null)) {
                var cs = new ClassifierState(
                        savedState.name,
                        savedState.featureNames,
                        savedState.classNames,
                        ProjectStateManager.decodeXGBoostModel(savedState),
                        ProjectStateManager.decodeLightGBMModel(savedState),
                        ProjectStateManager.decodeRFModel1(savedState),
                        ProjectStateManager.decodeRFModel2(savedState),
                        ProjectStateManager.getModel1Type(savedState),
                        ProjectStateManager.getModel2Type(savedState));
                this.classifier = new DualModelClassifier();
                this.classifier.loadFromState(cs);
            }
        } catch (Exception ex) {
            logger.warn("Failed to load binary classifier state for '{}': {}", markerName, ex.getMessage());
            this.classifier = null;
        }

        this.activeBinaryMarker = markerName;
        syncPanelState();
        logger.info("[CellTune] Entered binary mode for marker '{}'", markerName);

        // Select and expand the docked classification panel for immediate use
        javafx.application.Platform.runLater(() -> {
            if (dockTab != null) {
                qupath.getAnalysisTabPane().getSelectionModel().select(dockTab);
            }
            if (dockPane != null) {
                dockPane.setExpanded(true);
            }
        });
    }

    /**
     * Exit binary mode and restore the multi-class classifier state.
     * Saves the binary marker's current labels before restoring.
     *
     * @param qupath the QuPath GUI
     */
    private void exitBinaryMode(QuPathGUI qupath) {
        if (activeBinaryMarker == null) return;
        var project = qupath.getProject();

        // Save binary labels before exiting
        if (project != null && labelStore != null) {
            try {
                ProjectStateManager.saveBinaryLabels(project, activeBinaryMarker, labelStore);
            } catch (IOException ex) {
                logger.warn("Failed to save binary labels for '{}' on exit: {}", activeBinaryMarker, ex.getMessage());
            }
        }

        // Restore multi-class state
        this.labelStore = (preBinaryLabelStore != null) ? preBinaryLabelStore : new LabelStore("CellTune");
        this.classifier = preBinaryClassifier;
        this.activeBinaryMarker = null;
        this.preBinaryLabelStore = null;
        this.preBinaryClassifier = null;

        syncPanelState();
        logger.info("[CellTune] Exited binary mode \u2014 restored multi-class state");
    }
}
