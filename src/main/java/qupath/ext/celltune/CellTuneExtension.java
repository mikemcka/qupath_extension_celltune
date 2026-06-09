package qupath.ext.celltune;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.DualModelClassifier;
import qupath.ext.celltune.classifier.ClassifierState;
import qupath.ext.celltune.classifier.FeaturePruner;
import qupath.ext.celltune.classifier.ModelType;
import qupath.ext.celltune.classifier.ResamplingStrategy;
import qupath.ext.celltune.classifier.UncertaintySampler;
import qupath.ext.celltune.gating.GatingRule;
import qupath.ext.celltune.io.CellTableExporter;
import qupath.ext.celltune.io.GroundTruthIO;
import qupath.ext.celltune.io.MarkerTableImporter;
import qupath.ext.celltune.io.ProjectStateManager;
import qupath.ext.celltune.model.CellFeatureExtractor;
import qupath.ext.celltune.model.CellTypeTable;
import qupath.ext.celltune.model.CohortAnomalyAnalyzer;
import qupath.ext.celltune.model.CohortAnomalyReport;
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
import qupath.ext.celltune.ui.TrainingTileExtractor;
import qupath.ext.celltune.ui.ProjectPredictionSummaryView;
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
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import qupath.ext.celltune.io.BinaryClassifierRegistry;
import qupath.ext.celltune.ui.BinaryClassifierPanel;
import qupath.ext.celltune.ui.ClassControlDialog;
import qupath.ext.celltune.ui.CompositeClassificationDialog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** Sampled cell ID to image name map for cross-image review. */
    private Map<String, String> lastSampledCellImageMap = Map.of();
    /** Prediction pool used to generate lastSampledCellIds. */
    private PopulationSet lastSampledPredictions;
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
    /** Class names resolved from saved state or label store for the active binary classifier. */
    private List<String> activeBinaryClassNames = null;
    /** Multi-class state saved before entering binary mode, restored on exit. */
    private LabelStore preBinaryLabelStore = null;
    private DualModelClassifier preBinaryClassifier = null;
    private List<GroundTruthIO.TrainingRow> preBinaryImportedTrainingRows = null;
    private List<String> preBinaryImportedTrainingFeatureNames = null;
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
        suppressImageTypePromptOnce();
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
        classificationPanel.setOnSampledCellsChanged(ids -> {
            this.lastSampledCellIds = ids;
            this.lastSampledCellImageMap = Map.of();
            this.lastSampledPredictions = null;
        });
        classificationPanel.setOnClassifierChanged(cls -> {
            this.classifier = cls;
            // Auto-save binary classifier state after training completes in binary mode
            if (activeBinaryMarker != null && cls != null && cls.isTrained()) {
                var proj = qupath.getProject();
                if (proj != null) {
                    try {
                        ClassifierState state = cls.toClassifierState(activeBinaryMarker);
                        // Defence-in-depth: drop labels for classes outside this binary
                        // classifier before writing the canonical state file.
                        LabelStore safeStore = labelStore != null
                                ? labelStore : new LabelStore(activeBinaryMarker);
                        if (activeBinaryClassNames != null && !activeBinaryClassNames.isEmpty()) {
                            safeStore.retainClasses(new java.util.LinkedHashSet<>(activeBinaryClassNames));
                        }
                        ProjectStateManager.saveBinaryState(proj, activeBinaryMarker,
                                safeStore,
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
        classificationPanel.setOnManualLabelMode(() -> showManualLabelMode(qupath));
        classificationPanel.setOnFeatureImportance(() -> showFeatureImportance(qupath));
        classificationPanel.setBinaryTargetImagesSupplier(() ->
                binaryTargetImages == null ? List.of() : new ArrayList<>(binaryTargetImages));

        // Listen for image changes so we can save/reset/load state per image.
        // Store the listener reference so it can be removed if the extension is ever reloaded.
        imageDataListener = (obs, oldData, newData) -> handleImageChange(qupath, oldData, newData);
        qupath.imageDataProperty().addListener(imageDataListener);

        // Dock into QuPath's analysis pane. Wrap the panel in a ScrollPane so
        // the user can reach widgets that exceed the tab's visible height.
        javafx.scene.control.ScrollPane scrollPane =
                new javafx.scene.control.ScrollPane(classificationPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(false);
        scrollPane.getStyleClass().add("celltune-dock-scroll");

        dockPane = new javafx.scene.control.TitledPane(EXTENSION_NAME, scrollPane);
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
        if (ReviewController.isReviewSessionActive()) {
            logger.debug("Skipping image-change state sync during active review navigation");
            return;
        }

        var project = qupath.getProject();

        // ── Save labels for the OLD image (filtered) ──
        if (oldData != null && project != null) {
            var oldEntry = project.getEntry(oldData);
            if (oldEntry != null) {
                // Save labels
                if (labelStore != null && labelStore.size() > 0) {
                    var filteredStore = filterLabelStoreToImage(labelStore, oldData);
                    // In binary mode, drop any labels for classes that don't belong
                    // to this binary classifier so foreign labels (e.g. PD-1+/- in a
                    // GrB store) don't get persisted to per-image label files.
                    if (activeBinaryMarker != null
                            && activeBinaryClassNames != null
                            && !activeBinaryClassNames.isEmpty()) {
                        filteredStore.retainClasses(new java.util.LinkedHashSet<>(activeBinaryClassNames));
                    }
                    if (filteredStore.size() > 0) {
                        try {
                            ProjectStateManager.saveImageLabels(
                                    project, activeBinaryMarker, oldEntry.getImageName(), filteredStore);
                        } catch (IOException ex) {
                            logger.warn("Failed to save labels for {} on image switch: {}",
                                    oldEntry.getImageName(), ex.getMessage());
                        }
                    }
                }
                // Save sampled cell IDs (independent of labels)
                if (lastSampledCellIds != null && !lastSampledCellIds.isEmpty()) {
                    List<String> oldImageOnlyIds = new ArrayList<>();
                    for (String id : lastSampledCellIds) {
                        String imageName = lastSampledCellImageMap.get(id);
                        if (imageName == null || imageName.equals(oldEntry.getImageName())) {
                            oldImageOnlyIds.add(id);
                        }
                    }
                    if (!oldImageOnlyIds.isEmpty()) {
                        try {
                            ProjectStateManager.saveImageSampledCells(project, oldEntry.getImageName(), oldImageOnlyIds);
                        } catch (IOException ex) {
                            logger.warn("Failed to save sampled cells for {} on image switch: {}",
                                    oldEntry.getImageName(), ex.getMessage());
                        }
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
        this.lastSampledCellImageMap = Map.of();
        this.lastSampledPredictions = null;

        // ── Load labels for the NEW image (if any) ──
        if (newData != null && project != null) {
            var newEntry = project.getEntry(newData);
            if (newEntry != null) {
                LabelStore loaded = null;
                try {
                    loaded = ProjectStateManager.loadImageLabels(
                            project, activeBinaryMarker, newEntry.getImageName());
                } catch (IOException ex) {
                    logger.warn("Failed to load labels for {}: {}",
                            newEntry.getImageName(), ex.getMessage());
                }
                this.labelStore = (loaded != null) ? loaded : new LabelStore("CellTune");
                // Also pick up any annotation-based labels on the new image
                collectLabelsFromAnnotations(qupath, this.labelStore);
                // In binary mode, drop any labels loaded from disk that belong to
                // other classifiers (self-healing for previously-contaminated files).
                if (activeBinaryMarker != null
                        && activeBinaryClassNames != null
                        && !activeBinaryClassNames.isEmpty()) {
                    this.labelStore.retainClasses(
                            new java.util.LinkedHashSet<>(activeBinaryClassNames));
                }

                // Load sampled cell IDs for new image
                try {
                    List<String> sampledIds = ProjectStateManager.loadImageSampledCells(project, newEntry.getImageName());
                    this.lastSampledCellIds = sampledIds;
                    this.lastSampledCellImageMap = Map.of();
                    this.lastSampledPredictions = null;
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
                            classifier.setTrainingMetrics(
                                    state.model1TrainMetrics, state.model1ValMetrics,
                                    state.model2TrainMetrics, state.model2ValMetrics);
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
        classificationPanel.setActiveBinaryClassNames(activeBinaryClassNames);
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

    /**
     * Stop QuPath from popping the "Set image type" dialog the first time each image
     * is opened. CellTune's workflow doesn't depend on the image type being set, and
     * the prompt is disruptive for users with large multi-image projects. We switch
     * the global preference to AUTO_ESTIMATE so QuPath quietly guesses (RGB →
     * brightfield, multichannel → fluorescence). Users can change this back via
     * Edit → Preferences → Set image type if they want.
     */
    private void suppressImageTypePromptOnce() {
        try {
            var prop = PathPrefs.imageTypeSettingProperty();
            var current = prop.getValue();
            if (current == null || "PROMPT".equals(current.name())) {
                // Resolve AUTO_ESTIMATE reflectively so we don't have to import the
                // nested enum type at compile time.
                @SuppressWarnings({ "unchecked", "rawtypes" })
                Class<? extends Enum> enumCls =
                        (Class<? extends Enum>) Class.forName(
                                "qupath.lib.gui.prefs.PathPrefs$ImageTypeSetting");
                @SuppressWarnings({ "unchecked", "rawtypes" })
                Enum auto = Enum.valueOf(enumCls, "AUTO_ESTIMATE");
                @SuppressWarnings("unchecked")
                javafx.beans.property.ObjectProperty<Enum> typed =
                        (javafx.beans.property.ObjectProperty<Enum>) (Object) prop;
                typed.setValue(auto);
                logger.debug("Set QuPath imageTypeSetting to AUTO_ESTIMATE to suppress first-open prompt");
            }
        } catch (Throwable t) {
            logger.debug("Could not adjust imageTypeSetting preference: {}", t.toString());
        }
    }

    private void addMenuItems(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        MenuItem projectSummaryItem = new MenuItem(resources.getString("menu.prediction.summary"));
        projectSummaryItem.setOnAction(e -> showProjectPredictionSummary(qupath));
        projectSummaryItem.disableProperty().bind(enableExtensionProperty.not());

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

        MenuItem exportBinaryGroundTruthItem = new MenuItem(resources.getString("menu.export.binary.groundtruth"));
        exportBinaryGroundTruthItem.setOnAction(e -> exportActiveBinaryGroundTruth(qupath));
        exportBinaryGroundTruthItem.disableProperty().bind(enableExtensionProperty.not());

        MenuItem importBinaryGroundTruthItem = new MenuItem(resources.getString("menu.import.binary.groundtruth"));
        importBinaryGroundTruthItem.setOnAction(e -> importActiveBinaryGroundTruth(qupath));
        importBinaryGroundTruthItem.disableProperty().bind(enableExtensionProperty.not());

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

        MenuItem classControlItem = new MenuItem("Class Control...");
        classControlItem.setOnAction(e -> showClassControl(qupath));
        classControlItem.disableProperty().bind(enableExtensionProperty.not());

        menu.getItems().addAll(
                binaryItem,
                compositeItem,
                classControlItem,
                featuresItem,
                normalizeItem,
                new SeparatorMenuItem(),
                projectSummaryItem,
                new SeparatorMenuItem(),
                importMarkersItem,
                exportItem,
                new SeparatorMenuItem(),
                exportGtItem,
                importGtItem,
                exportBinaryGroundTruthItem,
                importBinaryGroundTruthItem
        );
    }

    // ── Placeholder actions (wired in later phases) ────────────────────────────

    private void showProjectPredictionSummary(QuPathGUI qupath) {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("classify.no_project"));
            return;
        }

        // Ensure latest in-memory predictions for current image are included in the summary.
        persistCurrentImagePredictions(qupath);

        String currentImageName = null;
        var currentImageData = qupath.getImageData();
        if (currentImageData != null) {
            var currentEntry = project.getEntry(currentImageData);
            if (currentEntry != null) {
                currentImageName = currentEntry.getImageName();
            }
        }
        final String currentImageNameFinal = currentImageName;
        final PopulationSet liveCurrent = predAll;

        @SuppressWarnings("unchecked")
        var entries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
        var imageNames = new ArrayList<String>(entries.size());
        for (var entry : entries) {
            if (entry == null || entry.getImageName() == null) continue;
            imageNames.add(entry.getImageName());
        }

        if (imageNames.isEmpty()) {
            Dialogs.showInfoNotification(EXTENSION_NAME, "No project images found.");
            return;
        }

        // ── Show a small progress dialog and load all images in parallel
        // off the FX thread so the UI stays responsive on large projects.
        var stage = new javafx.stage.Stage();
        stage.setTitle(EXTENSION_NAME + " \u2014 Loading prediction summary");
        stage.initOwner(qupath.getStage());
        stage.initModality(javafx.stage.Modality.WINDOW_MODAL);
        stage.setResizable(false);
        var bar = new javafx.scene.control.ProgressBar(0);
        bar.setPrefWidth(360);
        var status = new javafx.scene.control.Label("Scanning project images\u2026");
        status.setMaxWidth(360);
        status.setWrapText(true);
        var box = new javafx.scene.layout.VBox(8, status, bar);
        box.setPadding(new javafx.geometry.Insets(15));
        stage.setScene(new javafx.scene.Scene(box));
        stage.setOnCloseRequest(e -> e.consume());
        stage.show();

        Thread t = new Thread(() -> {
            int total = imageNames.size();
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
            // Capture avgCounts per image alongside the row (rows themselves are immutable).
            java.util.Map<String, Map<String, Long>> avgCountsByImage =
                    new java.util.concurrent.ConcurrentHashMap<>();

            // Parallel JSON load — independent files per image, IO+CPU bound.
            List<SummaryInputRow> sourceRows = imageNames.parallelStream()
                    .map(imageName -> {
                        PopulationSet predictions = null;
                        if (currentImageNameFinal != null && currentImageNameFinal.equals(imageName)
                                && liveCurrent != null && liveCurrent.size() > 0) {
                            predictions = liveCurrent;
                        } else {
                            try {
                                predictions = ProjectStateManager.loadImagePredictions(project, imageName);
                            } catch (IOException ex) {
                                logger.warn("Failed to load predictions for {} in project summary: {}",
                                        imageName, ex.getMessage());
                            }
                        }
                        SummaryInputRow row;
                        if (predictions == null || predictions.size() == 0) {
                            row = new SummaryInputRow(imageName, 0L, 0L,
                                    "No saved predictions for this image.");
                        } else {
                            long predicted = predictions.size();
                            long disagreements = predictions.getDisagreementCount();
                            Map<String, Long> avgCounts = predictions.getAvgCounts();
                            avgCountsByImage.put(imageName, avgCounts);
                            row = new SummaryInputRow(imageName, predicted, disagreements,
                                    formatClassCounts(avgCounts));
                        }
                        int c = done.incrementAndGet();
                        javafx.application.Platform.runLater(() -> {
                            bar.setProgress((double) c / total);
                            status.setText(String.format("Loading predictions: %d / %d images\u2026",
                                    c, total));
                        });
                        return row;
                    })
                    .collect(java.util.stream.Collectors.toList());

            var analyzerInputs = new ArrayList<CohortAnomalyReport.ImageInput>(sourceRows.size());
            for (var src : sourceRows) {
                Map<String, Long> avg = avgCountsByImage.get(src.imageName());
                if (src.predictedCells() > 0 && avg != null) {
                    analyzerInputs.add(new CohortAnomalyReport.ImageInput(
                            src.imageName(),
                            src.predictedCells(),
                            src.disagreements(),
                            avg));
                }
            }
            var anomalyReport = CohortAnomalyAnalyzer.analyze(analyzerInputs);
            var anomalyByImage = anomalyReport.byImageName();

            var rows = new ArrayList<ProjectPredictionSummaryView.Row>(sourceRows.size());
            for (var source : sourceRows) {
                rows.add(buildPredictionSummaryRow(source, anomalyByImage.get(source.imageName())));
            }

            javafx.application.Platform.runLater(() -> {
                stage.close();
                new ProjectPredictionSummaryView(qupath, qupath.getStage(), rows).show();
            });
        }, "celltune-summary-load");
        t.setDaemon(true);
        t.start();
    }

    private ProjectPredictionSummaryView.Row buildPredictionSummaryRow(
            SummaryInputRow source,
            CohortAnomalyReport.ImageAnomaly anomaly) {
        if (source.predictedCells() == 0L) {
            return new ProjectPredictionSummaryView.Row(
                    source.imageName(),
                    0L,
                    0L,
                    0L,
                    "-",
                    0.0,
                    false,
                    "-",
                    "No highlighted rare classes.",
                    List.of(),
                    source.classCountsText()
            );
        }

        long agreements = Math.max(0L, source.predictedCells() - source.disagreements());
        double agreementPct = source.predictedCells() > 0
                ? (100.0 * agreements) / source.predictedCells()
                : 0.0;

        if (anomaly == null) {
            return new ProjectPredictionSummaryView.Row(
                    source.imageName(),
                    source.predictedCells(),
                    agreements,
                    source.disagreements(),
                    String.format("%.1f%%", agreementPct),
                    0.0,
                    false,
                    "-",
                    "No highlighted rare classes.",
                    List.of(),
                    source.classCountsText()
            );
        }

        return new ProjectPredictionSummaryView.Row(
                source.imageName(),
                source.predictedCells(),
                agreements,
                source.disagreements(),
                String.format("%.1f%%", agreementPct),
                anomaly.anomalyScore(),
                anomaly.flagged(),
                formatFlagReasons(anomaly.flagReasons()),
                formatHighlightedRareClasses(anomaly.highlightedClasses(), anomaly.enrichmentByClass()),
                anomaly.highlightedClasses(),
                source.classCountsText()
        );
    }

    private String formatClassCounts(Map<String, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return "No predicted classes.";
        }

        var parts = new ArrayList<String>(counts.size());
        for (var entry : counts.entrySet()) {
            String className = entry.getKey() == null ? "(unknown)" : entry.getKey();
            long count = entry.getValue() == null ? 0L : entry.getValue();
            parts.add(className + ": " + count);
        }
        return String.join(", ", parts);
    }

    private String formatFlagReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return "-";
        }
        return String.join(", ", reasons);
    }

    private String formatHighlightedRareClasses(
            List<String> highlightedClasses,
            Map<String, CohortAnomalyReport.ClassEnrichment> enrichmentByClass) {
        if (highlightedClasses == null || highlightedClasses.isEmpty()) {
            return "No highlighted rare classes.";
        }

        var parts = new ArrayList<String>(highlightedClasses.size());
        for (String className : highlightedClasses) {
            var enrichment = enrichmentByClass == null ? null : enrichmentByClass.get(className);
            if (enrichment == null) {
                parts.add(className);
                continue;
            }
            parts.add(String.format(
                    "%s (count=%d, fold=%.2fx)",
                    className,
                    enrichment.count(),
                    enrichment.enrichmentFold()
            ));
        }
        return String.join("; ", parts);
    }

    private record SummaryInputRow(
            String imageName,
            long predictedCells,
            long disagreements,
            String classCountsText) {
    }

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

    /**
     * Collect ground-truth labels from classified point annotations overlapping detections.
     */
    private void collectLabelsFromAnnotations(QuPathGUI qupath, LabelStore store) {
        var imageData = qupath.getImageData();
        if (imageData == null) return;
        collectLabelsFromHierarchy(imageData.getHierarchy(), store, activeBinaryClassFilter());
    }

    /** Returns the allowed binary class names when in binary mode, or null otherwise. */
    private java.util.Set<String> activeBinaryClassFilter() {
        if (activeBinaryMarker == null) return null;
        if (activeBinaryClassNames == null || activeBinaryClassNames.isEmpty()) return null;
        return new java.util.LinkedHashSet<>(activeBinaryClassNames);
    }

    /**
     * Rewrite every per-image label file under the given binary marker scope,
     * stripping any entries whose class is not in {@code allowedClasses}. Files
     * that become empty are deleted. Errors on individual files are logged and
     * do not abort the scrub.
     */
    private static void scrubBinaryPerImageLabels(qupath.lib.projects.Project<?> project,
                                                  String markerName,
                                                  java.util.Set<String> allowedClasses) {
        if (project == null || markerName == null || allowedClasses == null || allowedClasses.isEmpty()) return;
        try {
            var files = ProjectStateManager.listImageLabelFiles(project, markerName);
            int filesScrubbed = 0;
            int totalRemoved = 0;
            for (var file : files) {
                try {
                    var labels = ProjectStateManager.readImageLabelsRaw(file);
                    if (labels.isEmpty()) continue;
                    int before = labels.size();
                    labels.entrySet().removeIf(e -> !allowedClasses.contains(e.getValue()));
                    int removed = before - labels.size();
                    if (removed == 0) continue;
                    totalRemoved += removed;
                    filesScrubbed++;
                    if (labels.isEmpty()) {
                        java.nio.file.Files.deleteIfExists(file);
                    } else {
                        ProjectStateManager.writeImageLabelsRaw(file, labels);
                    }
                } catch (Exception ex) {
                    logger.warn("Failed to scrub per-image labels file {}: {}", file, ex.getMessage());
                }
            }
            if (totalRemoved > 0) {
                logger.info("[CellTune] Scrubbed {} foreign-class labels from {} per-image file(s) for binary marker '{}'",
                        totalRemoved, filesScrubbed, markerName);
            }
        } catch (Exception ex) {
            logger.warn("Failed to enumerate per-image label files for binary marker '{}': {}",
                    markerName, ex.getMessage());
        }
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
        // In binary mode, also drop labels for classes outside this binary classifier
        // (defence-in-depth — collection paths already filter, but stale memory could
        // otherwise reintroduce foreign labels into the per-image file on save).
        if (activeBinaryMarker != null
                && activeBinaryClassNames != null
                && !activeBinaryClassNames.isEmpty()) {
            filteredStore.retainClasses(new java.util.LinkedHashSet<>(activeBinaryClassNames));
        }
        if (filteredStore.size() == 0) return;

        try {
            ProjectStateManager.saveImageLabels(project, activeBinaryMarker, entry.getImageName(), filteredStore);
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

    private static final class SamplingContext {
        private final PopulationSet predictions;
        private final Map<String, String> cellToImage;

        private SamplingContext(PopulationSet predictions, Map<String, String> cellToImage) {
            this.predictions = predictions;
            this.cellToImage = cellToImage;
        }

        private PopulationSet predictions() {
            return predictions;
        }

        private Map<String, String> cellToImage() {
            return cellToImage;
        }
    }

    private SamplingContext buildSamplingContext(QuPathGUI qupath) {
        return buildSamplingContext(qupath, false);
    }

    private SamplingContext buildSamplingContext(QuPathGUI qupath, boolean currentImageOnly) {
        PopulationSet pooled = new PopulationSet("Pred_ALL");
        Map<String, String> cellToImage = new LinkedHashMap<>();

        var project = qupath.getProject();
        var imageData = qupath.getImageData();
        String currentImageName = null;
        if (project != null && imageData != null) {
            var entry = project.getEntry(imageData);
            if (entry != null) {
                currentImageName = entry.getImageName();
            }
        }

        if (predAll != null && predAll.size() > 0) {
            addPredictionsToSamplingPool(pooled, predAll, currentImageName, cellToImage);
        } else if (project != null && currentImageName != null) {
            try {
                var loadedCurrent = ProjectStateManager.loadImagePredictions(project, currentImageName);
                if (loadedCurrent != null && loadedCurrent.size() > 0) {
                    addPredictionsToSamplingPool(pooled, loadedCurrent, currentImageName, cellToImage);
                }
            } catch (Exception ignored) {
            }
        }

        if (project != null && !currentImageOnly) {
            @SuppressWarnings("unchecked")
            var entries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
            for (var entry : entries) {
                if (entry == null || entry.getImageName() == null) continue;
                if (currentImageName != null && currentImageName.equals(entry.getImageName())) continue;

                try {
                    var loaded = ProjectStateManager.loadImagePredictions(project, entry.getImageName());
                    if (loaded != null && loaded.size() > 0) {
                        addPredictionsToSamplingPool(pooled, loaded, entry.getImageName(), cellToImage);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return new SamplingContext(pooled, cellToImage);
    }

    private static void addPredictionsToSamplingPool(PopulationSet pooled,
                                                     PopulationSet source,
                                                     String imageName,
                                                     Map<String, String> cellToImage) {
        if (source == null || source.size() == 0) return;
        String safeImageName = (imageName == null || imageName.isBlank()) ? "image" : imageName;

        for (var entry : source.getAll().entrySet()) {
            String cellId = entry.getKey();
            if (cellId == null || cellId.isBlank()) continue;
            if (pooled.get(cellId) != null) continue;

            pooled.put(cellId, entry.getValue());
            cellToImage.put(cellId, safeImageName);
        }
    }

    private boolean sampleForReviewBatch(QuPathGUI qupath,
                                         SamplingContext samplingContext,
                                         int sampleSize) {
        if (samplingContext == null || samplingContext.predictions() == null
                || samplingContext.predictions().size() == 0 || sampleSize <= 0) {
            return false;
        }

        Set<String> reviewedCellIds = buildReviewedCellIdsForSampling(qupath, activeBinaryMarker, labelStore, lastSampledCellIds);
        lastSampledCellIds = UncertaintySampler.sample(
                samplingContext.predictions(),
                classifier.getClassNames(),
                lastAgreementRates,
                sampleSize,
                List.of(),
                List.of(),
                samplingContext.cellToImage(),
                reviewedCellIds,
                new Random());

        if (lastSampledCellIds == null) {
            lastSampledCellIds = List.of();
        }

        lastSampledCellImageMap = new LinkedHashMap<>();
        for (String id : lastSampledCellIds) {
            String imageName = samplingContext.cellToImage().get(id);
            if (imageName != null) {
                lastSampledCellImageMap.put(id, imageName);
            }
        }
        lastSampledPredictions = samplingContext.predictions();

        syncPanelState();
        persistCurrentImageSampledIds(qupath);
        return !lastSampledCellIds.isEmpty();
    }

    private void persistCurrentImageSampledIds(QuPathGUI qupath) {
        var project = qupath.getProject();
        var imageData = qupath.getImageData();
        if (project == null || imageData == null || lastSampledCellIds == null || lastSampledCellIds.isEmpty()) {
            return;
        }

        var imgEntry = project.getEntry(imageData);
        if (imgEntry == null) return;

        String currentImageName = imgEntry.getImageName();
        List<String> currentImageOnlyIds = new ArrayList<>();
        for (String id : lastSampledCellIds) {
            String imageName = lastSampledCellImageMap.get(id);
            if (imageName == null || imageName.equals(currentImageName)) {
                currentImageOnlyIds.add(id);
            }
        }

        if (currentImageOnlyIds.isEmpty()) return;

        try {
            ProjectStateManager.saveImageSampledCells(project, currentImageName, currentImageOnlyIds);
        } catch (Exception ex) {
            logger.warn("Failed to save sampled cells: {}", ex.getMessage());
        }
    }

    private void persistReviewedLabelsByImage(QuPathGUI qupath, LabelStore reviewedLabels) {
        if (reviewedLabels == null || reviewedLabels.size() == 0) return;
        var project = qupath.getProject();
        if (project == null) return;

        String currentImageName = null;
        var currentImageData = qupath.getImageData();
        if (currentImageData != null) {
            var currentEntry = project.getEntry(currentImageData);
            if (currentEntry != null) {
                currentImageName = currentEntry.getImageName();
            }
        }

        Map<String, LabelStore> labelsByImage = new LinkedHashMap<>();
        for (var entry : reviewedLabels.getAllLabels().entrySet()) {
            String cellId = entry.getKey();
            String label = entry.getValue();
            if (cellId == null || label == null) continue;

            String imageName = lastSampledCellImageMap.get(cellId);
            if ((imageName == null || imageName.isBlank()) && currentImageName != null) {
                imageName = currentImageName;
            }
            if (imageName == null || imageName.isBlank()) continue;

            labelsByImage
                    .computeIfAbsent(imageName, ignored -> new LabelStore("CellTune"))
                    .setLabel(cellId, label);
        }

        java.util.Set<String> binaryFilter = activeBinaryClassFilter();

        for (var entry : labelsByImage.entrySet()) {
            String imageName = entry.getKey();
            LabelStore delta = entry.getValue();

            try {
                // In binary mode, strip foreign-class labels from the delta before
                // it touches disk, and from the merged on-disk store after merge.
                // The post-merge filter is self-healing: any historical contamination
                // (foreign labels written by older buggy code) gets cleaned up on
                // the next save.
                if (binaryFilter != null) {
                    delta.retainClasses(binaryFilter);
                    if (delta.size() == 0) continue;
                }

                LabelStore merged = ProjectStateManager.loadImageLabels(project, activeBinaryMarker, imageName);
                if (merged == null) {
                    merged = new LabelStore("CellTune");
                }
                merged.mergeFrom(delta);
                if (binaryFilter != null) {
                    merged.retainClasses(binaryFilter);
                }
                ProjectStateManager.saveImageLabels(project, activeBinaryMarker, imageName, merged);
            } catch (Exception ex) {
                logger.warn("Failed to save reviewed labels for {}: {}", imageName, ex.getMessage());
            }
        }
    }

    private static Set<String> buildReviewedCellIdsForSampling(
            QuPathGUI qupath,
            String scope,
            LabelStore labels,
            List<String> previouslySampledIds) {
        Set<String> reviewed = new LinkedHashSet<>();
        if (labels != null) {
            reviewed.addAll(labels.getAllLabels().keySet());
        }
        if (previouslySampledIds != null) {
            reviewed.addAll(previouslySampledIds);
        }

        if (qupath != null && qupath.getProject() != null) {
            @SuppressWarnings("unchecked")
            var entries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) qupath.getProject().getImageList();
            for (var entry : entries) {
                if (entry == null || entry.getImageName() == null) continue;
                try {
                    LabelStore imageLabels = ProjectStateManager.loadImageLabels(qupath.getProject(), scope, entry.getImageName());
                    if (imageLabels != null) {
                        reviewed.addAll(imageLabels.getAllLabels().keySet());
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return reviewed;
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
        collectLabelsFromHierarchy(hierarchy, store, null);
    }

    private static void collectLabelsFromHierarchy(
            qupath.lib.objects.hierarchy.PathObjectHierarchy hierarchy,
            LabelStore store,
            java.util.Set<String> allowedClasses) {
        for (PathObject anno : hierarchy.getAnnotationObjects()) {
            if (anno.getPathClass() == null || anno.getROI() == null) continue;
            // Only point annotations count as ground truth — area/region annotations
            // describe tissue regions, not individual cell labels.
            if (!anno.getROI().isPoint()) continue;
            String cls = anno.getPathClass().toString();
            if (allowedClasses != null && !allowedClasses.contains(cls)) continue;

            List<PathObject> hits = new java.util.ArrayList<>();
            for (var pt : anno.getROI().getAllPoints()) {
                hits.addAll(PathObjectTools.getObjectsForLocation(
                        hierarchy, pt.getX(), pt.getY(),
                        anno.getROI().getZ(), anno.getROI().getT(), -1));
            }

            for (PathObject det : hits) {
                if (det.isDetection()) {
                    String id = det.getID().toString();
                    String existing = store.getLabel(id);
                    // Preserve merge-history encoding: if the existing stored value is
                    // "<cls>-mergedInto(target)" (or a chained merge whose innermost
                    // original equals cls), don't overwrite it with the bare PathClass
                    // — the annotation has no knowledge of the merge so doing so would
                    // silently destroy the merge result.
                    if (existing != null && cls.equals(LabelStore.innermostOriginal(existing))) {
                        continue;
                    }
                    store.setLabel(id, cls);
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
            qupath, labelStore, extraClasses, qupath.getStage(), predAll, activeBinaryClassNames);

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
                            this.lastSampledCellImageMap = Map.of();
                            this.lastSampledPredictions = null;
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

        if (classifier == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "No trained classifier available. Train first.");
            return;
        }

        // Ask the user whether to pool predictions across the project or to
        // restrict sampling to the currently open image.
        boolean currentImageOnly = false;
        {
            var imgData = qupath.getImageData();
            String currentName = null;
            if (imgData != null && qupath.getProject() != null) {
                var entry = qupath.getProject().getEntry(imgData);
                if (entry != null) currentName = entry.getImageName();
            }
            if (currentName != null) {
                var allBtn = new javafx.scene.control.ButtonType("All project images",
                        javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                var currentBtn = new javafx.scene.control.ButtonType("Current image only",
                        javafx.scene.control.ButtonBar.ButtonData.OTHER);
                var cancelBtn = new javafx.scene.control.ButtonType("Cancel",
                        javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
                var alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.CONFIRMATION,
                        "Sample disagreement cells from which images?",
                        allBtn, currentBtn, cancelBtn);
                alert.setTitle("Enter Review Mode");
                alert.setHeaderText("Current image: " + currentName);
                alert.initOwner(qupath.getStage());
                var choice = alert.showAndWait();
                if (choice.isEmpty() || choice.get() == cancelBtn) return;
                currentImageOnly = (choice.get() == currentBtn);
            }
        }

        SamplingContext samplingContext = buildSamplingContext(qupath, currentImageOnly);
        long disagreeCount = samplingContext.predictions().getDisagreementCount();
        if (disagreeCount == 0) {
            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "No disagreement cells available across saved project predictions.");
            return;
        }

        // Compute agreement rates if not yet available (for current image)
        if (predAll != null && predAll.size() > 0 && lastAgreementRates == null) {
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

        if (!sampleForReviewBatch(qupath, samplingContext, sampleSize)) {
            Dialogs.showInfoNotification(EXTENSION_NAME,
                    "No eligible disagreement cells remained after excluding reviewed cells.");
            return;
        }

        if (lastSampledCellIds == null || lastSampledCellIds.isEmpty()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("review.no_sample"));
            return;
        }

        PopulationSet reviewPredictions = (lastSampledPredictions != null && lastSampledPredictions.size() > 0)
                ? lastSampledPredictions : predAll;
        if (reviewPredictions == null || reviewPredictions.size() == 0) {
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "No predictions available. Train and sample first.");
            return;
        }

        // Build controller and UI components
        // Pre-extract training tiles around each sampled cell so review avoids
        // costly switches between large project images. Cell IDs round-trip via
        // lastSampledCellImageMap (see persistReviewedLabelsByImage).
        final PopulationSet finalReviewPredictions = reviewPredictions;
        launchTrainingTileReview(qupath, finalReviewPredictions);
    }

    private void launchTrainingTileReview(QuPathGUI qupath, PopulationSet reviewPredictions) {
        var progressStage = new javafx.stage.Stage();
        progressStage.setTitle(EXTENSION_NAME + " \u2014 Preparing review tiles");
        progressStage.initOwner(qupath.getStage());
        progressStage.initModality(javafx.stage.Modality.WINDOW_MODAL);
        progressStage.setResizable(false);
        var bar = new javafx.scene.control.ProgressBar(0);
        bar.setPrefWidth(360);
        var status = new javafx.scene.control.Label("Extracting cell tiles\u2026");
        status.setMaxWidth(360);
        status.setWrapText(true);
        var box = new javafx.scene.layout.VBox(8, status, bar);
        box.setPadding(new javafx.geometry.Insets(15));
        progressStage.setScene(new javafx.scene.Scene(box));
        progressStage.setOnCloseRequest(ev -> ev.consume());
        progressStage.show();

        final int total = lastSampledCellIds == null ? 0 : lastSampledCellIds.size();
        final List<String> cellIds = lastSampledCellIds;
        final Map<String, String> imageMap = lastSampledCellImageMap;
        Thread t = new Thread(() -> {
            TrainingTileExtractor extractor;
            try {
                extractor = TrainingTileExtractor.extract(qupath, cellIds, imageMap,
                        done -> javafx.application.Platform.runLater(() -> {
                            if (total > 0) {
                                bar.setProgress((double) done / total);
                                status.setText(String.format("Extracting tiles: %d / %d cells\u2026", done, total));
                            }
                        }));
            } catch (Exception e) {
                final Exception err = e;
                javafx.application.Platform.runLater(() -> {
                    progressStage.close();
                    Dialogs.showErrorMessage(EXTENSION_NAME,
                            "Failed to prepare review tiles: " + err.getMessage());
                });
                return;
            }
            final TrainingTileExtractor finalExtractor = extractor;
            javafx.application.Platform.runLater(() -> {
                progressStage.close();
                showTileReviewStage(qupath, reviewPredictions, finalExtractor);
            });
        }, "celltune-tile-extract");
        t.setDaemon(true);
        t.start();
    }

    private void showTileReviewStage(QuPathGUI qupath,
                                     PopulationSet reviewPredictions,
                                     TrainingTileExtractor extractor) {
        var reviewController = new ReviewController(qupath, lastSampledCellIds,
                reviewPredictions, lastSampledCellImageMap, Map.of(), extractor.getPreps());
        if (reviewController.size() == 0) {
            extractor.close();
            Dialogs.showErrorMessage(EXTENSION_NAME,
                    "Could not resolve sampled cells in project images.");
            return;
        }

        var channelSelector = new ChannelSelector(qupath, cellTypeTable);
        var toolbar = new ReviewToolbar(reviewController, cellTypeTable, channelSelector);
        toolbar.setBinaryMarker(activeBinaryMarker, activeBinaryClassNames);

        // Build the review stage
        var vbox = new javafx.scene.layout.VBox(6);
        vbox.setPadding(new javafx.geometry.Insets(6));
        vbox.getChildren().addAll(toolbar, channelSelector.getCheckBox());

        double reviewScreenH = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
        var reviewScroll = new javafx.scene.control.ScrollPane(vbox);
        reviewScroll.setFitToWidth(true);
        reviewScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        reviewScroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        reviewScroll.setMaxHeight(reviewScreenH * 0.9);

        var stage = new javafx.stage.Stage();
        stage.setTitle(resources.getString("review.stage.title"));
        stage.initOwner(qupath.getStage());
        stage.setScene(new javafx.scene.Scene(reviewScroll));
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
                logger.info("Review complete - merged {} labels into main label store",
                        outputLabels.size());

                persistReviewedLabelsByImage(qupath, outputLabels);

                Dialogs.showInfoNotification(EXTENSION_NAME,
                        String.format("Review complete: %d labels merged.", outputLabels.size()));
            }
            extractor.close();
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
        String imgName = null;
        var project = qupath.getProject();
        var imageData = qupath.getImageData();
        if (project != null && imageData != null) {
            var entry = project.getEntry(imageData);
            if (entry != null) imgName = entry.getImageName();
        }
        var view = new ConfusionMatrixView(qupath.getStage(), predAll, classNames, labelStore, qupath, imgName);
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
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(EXTENSION_NAME, "No image is open.");
            return;
        }
        var project = qupath.getProject();

        // Resolve current image name
        String currentImageName = null;
        if (project != null) {
            var currentEntry = project.getEntry(imageData);
            if (currentEntry != null) currentImageName = currentEntry.getImageName();
        }
        if (currentImageName == null || currentImageName.isBlank()) {
            currentImageName = imageData.getServer().getMetadata().getName();
        }
        final String currentImageNameFinal = currentImageName;

        // Build full list of project image names
        List<String> allImageNames;
        if (project != null) {
            @SuppressWarnings("unchecked")
            var entries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
            allImageNames = entries.stream()
                    .map(ProjectImageEntry::getImageName)
                    .filter(n -> n != null && !n.isBlank())
                    .collect(java.util.stream.Collectors.toList());
        } else {
            allImageNames = List.of(currentImageNameFinal);
        }

        // Show image selection dialog when the project has more than one image
        List<String> selectedImages;
        if (allImageNames.size() > 1) {
            var pane = new ImageSelectionPane(qupath.getStage(), allImageNames, currentImageNameFinal);
            selectedImages = pane.showAndWait();
            if (selectedImages == null || selectedImages.isEmpty()) return;
        } else {
            selectedImages = new ArrayList<>(allImageNames);
        }

        // Output directory
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select Output Folder for Cell Table(s)");
        if (project != null && project.getPath() != null) {
            File dir = project.getPath().getParent().toFile();
            if (dir.isDirectory()) dc.setInitialDirectory(dir);
        }
        File outDir = dc.showDialog(qupath.getStage());
        if (outDir == null) return;

        final var finalImageData = imageData;
        final var finalProject   = project;
        final var finalSelected  = selectedImages;
        final int total          = selectedImages.size();

        // Progress dialog — built and shown on the FX thread before the worker starts
        Stage progressStage = new Stage();
        progressStage.setTitle("Exporting Cell Tables");
        progressStage.initOwner(qupath.getStage());
        progressStage.initModality(Modality.NONE);
        progressStage.setResizable(true);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        Label statusLabel = new Label("Starting export…");
        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(130);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        Button closeBtn = new Button("Close");
        closeBtn.setDisable(true);
        closeBtn.setOnAction(e -> progressStage.close());

        VBox progressRoot = new VBox(8, statusLabel, progressBar, logArea, closeBtn);
        progressRoot.setPadding(new Insets(14));
        progressStage.setScene(new Scene(progressRoot, 440, 270));
        progressStage.show();

        Thread worker = new Thread(() -> {
            AtomicInteger exported = new AtomicInteger();
            AtomicInteger done     = new AtomicInteger();
            List<String> errors = Collections.synchronizedList(new ArrayList<>());

            // One task per image; cap at 4 threads (I/O-bound work)
            int nThreads = Math.min(total, 4);
            ExecutorService pool = Executors.newFixedThreadPool(nThreads);

            List<Callable<Void>> tasks = new ArrayList<>();
            for (String imgName : finalSelected) {
                tasks.add(() -> {
                    try {
                        qupath.lib.images.ImageData<BufferedImage> data;
                        if (imgName.equals(currentImageNameFinal)) {
                            // Use the already-open image data directly (read-only)
                            data = finalImageData;
                        } else {
                            // Load from disk — independent per thread
                            @SuppressWarnings("unchecked")
                            var typedProject = (Project<BufferedImage>) (Object) finalProject;
                            var entryOpt = typedProject.getImageList().stream()
                                    .filter(e -> imgName.equals(e.getImageName()))
                                    .findFirst();
                            if (entryOpt.isEmpty()) {
                                errors.add(imgName + ": not found in project");
                                int d = done.incrementAndGet();
                                Platform.runLater(() -> {
                                    progressBar.setProgress((double) d / total);
                                    statusLabel.setText("Processing " + d + " / " + total);
                                    logArea.appendText("✗ " + imgName + ": not found in project\n");
                                });
                                return null;
                            }
                            data = entryOpt.get().readImageData();
                            if (data == null) {
                                errors.add(imgName + ": could not read image data");
                                int d = done.incrementAndGet();
                                Platform.runLater(() -> {
                                    progressBar.setProgress((double) d / total);
                                    statusLabel.setText("Processing " + d + " / " + total);
                                    logArea.appendText("✗ " + imgName + ": could not read image data\n");
                                });
                                return null;
                            }
                        }

                        Collection<PathObject> cells = data.getHierarchy()
                                .getObjects(null, PathObject.class).stream()
                                .filter(PathObjectFilter.DETECTIONS_ALL)
                                .toList();

                        List<String> allFeats = CellFeatureExtractor.discoverFeatureNames(cells);
                        List<String> feats = allFeats.stream()
                                .filter(f -> f.equalsIgnoreCase("Cell: Area")
                                        || f.toLowerCase(java.util.Locale.ROOT).contains("mean"))
                                .collect(java.util.stream.Collectors.toList());
                        if (feats.isEmpty()) feats = allFeats;

                        // Sanitise the image name to produce a safe file name
                        String safeFileName = imgName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".csv";
                        Path outputPath = outDir.toPath().resolve(safeFileName);

                        CellTableExporter.export(outputPath, cells, imgName, feats);
                        exported.incrementAndGet();
                        int d = done.incrementAndGet();
                        Platform.runLater(() -> {
                            progressBar.setProgress((double) d / total);
                            statusLabel.setText("Processing " + d + " / " + total);
                            logArea.appendText("✓ " + imgName + "\n");
                        });

                    } catch (Exception ex) {
                        String errMsg = ex.getMessage();
                        logger.error("Failed to export cell table for '{}'", imgName, ex);
                        errors.add(imgName + ": " + errMsg);
                        int d = done.incrementAndGet();
                        Platform.runLater(() -> {
                            progressBar.setProgress((double) d / total);
                            statusLabel.setText("Processing " + d + " / " + total);
                            logArea.appendText("✗ " + imgName + ": " + errMsg + "\n");
                        });
                    }
                    return null;
                });
            }

            try {
                pool.invokeAll(tasks);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pool.shutdown();
            }

            final int finalExported = exported.get();
            final List<String> finalErrors = new ArrayList<>(errors);
            Platform.runLater(() -> {
                progressBar.setProgress(1.0);
                closeBtn.setDisable(false);
                if (finalErrors.isEmpty()) {
                    statusLabel.setText("Done — exported " + finalExported + " image(s) to " + outDir.getName());
                } else {
                    statusLabel.setText("Done — " + finalExported + " exported, " + finalErrors.size() + " error(s).");
                }
            });
        }, "CellTune-ExportCellTable");
        worker.setDaemon(true);
        worker.start();
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
        boolean inBinaryMode = activeBinaryMarker != null && !activeBinaryMarker.isBlank();
        int importedRowCount = (inBinaryMode && importedTrainingRows != null) ? importedTrainingRows.size() : 0;
        int localLabelCount = (labelStore == null) ? 0 : labelStore.size();
        if (localLabelCount == 0 && importedRowCount == 0) {
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
        fc.setInitialFileName(activeBinaryMarker != null && !activeBinaryMarker.isBlank()
                ? activeBinaryMarker + "_ground_truth.csv"
                : "ground_truth.csv");
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

            // Pool labels from every other project image so the export reflects the
            // full training set used (matches the auto-pool behaviour during training).
            // Per-image labels are scoped by activeBinaryMarker so binary classifiers
            // don't pull labels from each other.
            int appendedFromOtherImages = 0;
            if (project != null) {
                appendedFromOtherImages = appendOtherImageLabelsToCsv(
                        chosen.toPath(),
                        project,
                        imageData,
                        activeBinaryMarker,
                        featureNames,
                        featureNormalizer,
                        opts.includeRaw(),
                        opts.includeNorm());
            }

            // In binary mode, also append previously-imported training rows so the exported
            // CSV is the union of (this project's labels) + (rows imported from prior projects).
            // This keeps round-trips (project1 -> project2 -> project3) lossless for a single
            // binary marker, since each export carries the full accumulated training set.
            int appendedImported = 0;
            if (inBinaryMode && importedRowCount > 0
                    && importedTrainingFeatureNames != null && !importedTrainingFeatureNames.isEmpty()) {
                appendedImported = appendImportedRowsToCsv(
                        chosen.toPath(),
                        featureNames,
                        opts.includeRaw(),
                        opts.includeNorm() && featureNormalizer != null,
                        importedTrainingFeatureNames,
                        importedTrainingRows);
            }

            String msg = "Exported " + localLabelCount + " labelled cells to " + chosen.getName();
            if (appendedFromOtherImages > 0) {
                msg += " (+" + appendedFromOtherImages + " from other project images)";
            }
            if (appendedImported > 0) {
                msg += " (+" + appendedImported + " imported training rows from prior projects)";
            }
            Dialogs.showInfoNotification(EXTENSION_NAME, msg);
        } catch (IOException ex) {
            logger.error("Failed to export ground truth", ex);
            Dialogs.showErrorMessage(EXTENSION_NAME, "Export failed: " + ex.getMessage());
        }
    }

    /**
     * Append previously-imported training rows to a ground-truth CSV that was just written
     * by {@link GroundTruthIO#exportCSV}. Imported rows are aligned to the export's column
     * layout by feature name; columns absent from the imported schema are written as 0.
     *
     * @return number of rows appended
     */
    private static int appendImportedRowsToCsv(Path csvPath,
                                               List<String> featureNames,
                                               boolean includeRaw,
                                               boolean hasNorm,
                                               List<String> importedFeatureNames,
                                               List<GroundTruthIO.TrainingRow> importedRows) throws IOException {
        if (importedRows == null || importedRows.isEmpty()) return 0;

        // Build the export column ordering: raw featureNames first (if includeRaw), then
        // featureNames with __norm suffix (if hasNorm). Mirrors GroundTruthIO.exportCSV.
        List<String> exportCols = new ArrayList<>();
        if (includeRaw) exportCols.addAll(featureNames);
        if (hasNorm) {
            for (String f : featureNames) exportCols.add(f + "__norm");
        }
        if (exportCols.isEmpty()) return 0;

        // name -> index lookup into the imported feature vector
        Map<String, Integer> importedNameToIdx = new LinkedHashMap<>();
        for (int i = 0; i < importedFeatureNames.size(); i++) {
            importedNameToIdx.putIfAbsent(importedFeatureNames.get(i), i);
        }

        List<String> lines = new ArrayList<>();
        for (GroundTruthIO.TrainingRow row : importedRows) {
            if (row == null || row.label() == null || row.label().isBlank() || row.features() == null) continue;
            float[] src = row.features();
            StringBuilder sb = new StringBuilder();
            // Image, Label, CentroidX, CentroidY (centroid 0,0 - row originates from another project)
            sb.append("imported").append(',').append(row.label()).append(",0.00,0.00");
            for (String col : exportCols) {
                Integer idx = importedNameToIdx.get(col);
                float v = (idx != null && idx < src.length) ? src[idx] : 0f;
                sb.append(',').append(v);
            }
            lines.add(sb.toString());
        }

        if (lines.isEmpty()) return 0;
        Files.write(csvPath, lines, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return lines.size();
    }

    /**
     * Append rows for labels saved on every project image OTHER than the currently
     * open one. Each non-current image is opened off-screen, its detections are matched
     * against its persisted labels, and a feature row is written per matched cell.
     * Schema must match {@link GroundTruthIO#exportCSV} so the resulting CSV is one
     * coherent file.
     *
     * @param scope sanitized binary marker name, or null in multi-class mode
     * @return number of rows appended (across all other images)
     */
    private static int appendOtherImageLabelsToCsv(Path csvPath,
                                                   qupath.lib.projects.Project<?> project,
                                                   qupath.lib.images.ImageData<BufferedImage> currentImageData,
                                                   String scope,
                                                   List<String> featureNames,
                                                   FeatureNormalizer normalizer,
                                                   boolean includeRaw,
                                                   boolean includeNorm) throws IOException {
        if (project == null) return 0;
        boolean hasNorm = includeNorm && normalizer != null;
        if (!includeRaw && !hasNorm) return 0;

        @SuppressWarnings("unchecked")
        var typedProject = (qupath.lib.projects.Project<BufferedImage>) (qupath.lib.projects.Project<?>) project;
        @SuppressWarnings("unchecked")
        var allEntries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
        var currentEntry = currentImageData != null ? typedProject.getEntry(currentImageData) : null;

        List<String> linesOut = new ArrayList<>();
        for (var entry : allEntries) {
            if (entry == null) continue;
            if (currentEntry != null && entry.equals(currentEntry)) continue;

            String otherImageName = entry.getImageName();
            if (otherImageName == null || otherImageName.isBlank()) continue;

            // Skip images with no saved labels — avoids the expensive readImageData() call.
            if (!ProjectStateManager.hasImageLabels(project, scope, otherImageName)) continue;

            LabelStore otherLabels;
            try {
                otherLabels = ProjectStateManager.loadImageLabels(project, scope, otherImageName);
            } catch (Exception ex) {
                logger.warn("Failed to load labels for {}: {}", otherImageName, ex.getMessage());
                continue;
            }
            if (otherLabels == null || otherLabels.size() == 0) continue;

            try {
                var otherImageData = entry.readImageData();
                var otherDetections = otherImageData.getHierarchy().getDetectionObjects();
                if (otherDetections.isEmpty()) continue;

                Map<String, PathObject> cellById = new LinkedHashMap<>();
                for (PathObject cell : otherDetections) {
                    cellById.put(cell.getID().toString(), cell);
                }

                CellFeatureExtractor extractor = new CellFeatureExtractor(featureNames);
                extractor.setNormalizer(normalizer);

                for (var labelEntry : otherLabels.getAllLabels().entrySet()) {
                    PathObject cell = cellById.get(labelEntry.getKey());
                    if (cell == null) continue;
                    String label = labelEntry.getValue();
                    if (label == null || label.isBlank()) continue;

                    var roi = cell.getROI();
                    double cx = roi != null ? roi.getCentroidX() : 0;
                    double cy = roi != null ? roi.getCentroidY() : 0;

                    StringBuilder sb = new StringBuilder();
                    sb.append(otherImageName).append(',').append(label);
                    sb.append(',').append(String.format("%.2f", cx));
                    sb.append(',').append(String.format("%.2f", cy));
                    if (includeRaw) {
                        float[] raw = extractor.extractRowRaw(cell);
                        for (float v : raw) sb.append(',').append(v);
                    }
                    if (hasNorm) {
                        float[] norm = extractor.extractRow(cell);
                        for (float v : norm) sb.append(',').append(v);
                    }
                    linesOut.add(sb.toString());
                }
            } catch (Exception ex) {
                logger.warn("Failed to extract labelled features from {}: {}",
                        otherImageName, ex.getMessage());
            }
        }

        if (linesOut.isEmpty()) return 0;
        Files.write(csvPath, linesOut, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return linesOut.size();
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
                    if (activeBinaryMarker != null && !activeBinaryMarker.isBlank()) {
                        ProjectStateManager.saveBinaryImportedTrainingData(
                                project, activeBinaryMarker,
                                importedTrainingFeatureNames, importedTrainingRows);
                    } else {
                        ProjectStateManager.saveImportedTrainingData(
                                project, importedTrainingFeatureNames, importedTrainingRows);
                    }
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


    private boolean ensureActiveBinaryMarker() {
        if (activeBinaryMarker == null || activeBinaryMarker.isBlank()) {
            Dialogs.showErrorMessage(EXTENSION_NAME, resources.getString("binary.marker.required"));
            return false;
        }
        return true;
    }

    private void exportActiveBinaryGroundTruth(QuPathGUI qupath) {
        if (!ensureActiveBinaryMarker()) return;
        exportGroundTruth(qupath);
    }

    private void importActiveBinaryGroundTruth(QuPathGUI qupath) {
        if (!ensureActiveBinaryMarker()) return;
        importGroundTruth(qupath);
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
            persistSelectedFeatures(qupath);
            syncPanelState();
        }
    }

    /**
     * Save the current {@link #selectedFeatures} list to the active project so
     * it survives QuPath restarts. No-op when no project is open.
     */
    private void persistSelectedFeatures(QuPathGUI qupath) {
        var project = qupath.getProject();
        if (project == null) {
            return;
        }
        try {
            ProjectStateManager.saveSelectedFeatures(project, selectedFeatures);
        } catch (Exception ex) {
            logger.warn("Failed to persist selected features: {}", ex.getMessage());
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

    /** Singleton dialog instance — reuse across menu invocations. */
    private ClassControlDialog classControlDialog;

    private void showClassControl(QuPathGUI qupath) {
        if (classControlDialog == null) {
            classControlDialog = new ClassControlDialog(
                    qupath,
                    () -> labelStore,
                    ls -> this.labelStore = ls,
                    // Pre-op: flush active image's in-memory labels to disk so a class
                    // operation (delete/merge/undo) doesn't get clobbered later by a
                    // save that reflects stale memory. Only runs in multi-class mode.
                    () -> {
                        if (activeBinaryMarker != null) return;
                        var project = qupath.getProject();
                        var imageData = qupath.getImageData();
                        if (project == null || imageData == null || labelStore == null
                                || labelStore.size() == 0) return;
                        var entry = project.getEntry(imageData);
                        if (entry == null) return;
                        try {
                            var filtered = filterLabelStoreToImage(labelStore, imageData);
                            if (filtered.size() > 0) {
                                ProjectStateManager.saveImageLabels(
                                        project, entry.getImageName(), filtered);
                            }
                        } catch (IOException ex) {
                            logger.warn("Pre-op label flush failed for {}: {}",
                                    entry.getImageName(), ex.getMessage());
                        }
                    },
                    // Post-op: reload active image's labels from disk so memory matches
                    // the freshly-rewritten on-disk state. Disk is the source of truth.
                    () -> {
                        if (activeBinaryMarker != null) return;
                        var project = qupath.getProject();
                        var imageData = qupath.getImageData();
                        if (project == null || imageData == null) return;
                        var entry = project.getEntry(imageData);
                        if (entry == null) return;
                        try {
                            LabelStore reloaded = ProjectStateManager.loadImageLabels(
                                    project, entry.getImageName());
                            this.labelStore = (reloaded != null) ? reloaded
                                    : new LabelStore("CellTune");
                            collectLabelsFromAnnotations(qupath, this.labelStore);
                        } catch (IOException ex) {
                            logger.warn("Post-op label reload failed for {}: {}",
                                    entry.getImageName(), ex.getMessage());
                        }
                    }
            );
        }
        classControlDialog.show();
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

        // Preserve current multi-class state — but only when we're actually transitioning
        // INTO binary mode. If we're already in binary mode (user switched markers without
        // exiting first), the existing pre-binary snapshot must be left alone, otherwise we
        // would clobber it with the previous marker's binary state and lose the multi-class
        // labels/classifier on exit.
        if (activeBinaryMarker == null) {
            preBinaryLabelStore = labelStore;
            preBinaryClassifier = classifier;
            preBinaryImportedTrainingRows =
                    importedTrainingRows == null ? null : new ArrayList<>(importedTrainingRows);
            preBinaryImportedTrainingFeatureNames =
                    importedTrainingFeatureNames == null ? null : new ArrayList<>(importedTrainingFeatureNames);
        }

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
                this.classifier.setTrainingMetrics(
                        savedState.model1TrainMetrics, savedState.model1ValMetrics,
                        savedState.model2TrainMetrics, savedState.model2ValMetrics);
            }
        } catch (Exception ex) {
            logger.warn("Failed to load binary classifier state for '{}': {}", markerName, ex.getMessage());
            this.classifier = null;
        }

        try {
            var imported = ProjectStateManager.loadBinaryImportedTrainingData(project, markerName);
            if (imported != null) {
                this.importedTrainingFeatureNames = new ArrayList<>(imported.featureNames());
                this.importedTrainingRows = new ArrayList<>(imported.rows());
                logger.info("[CellTune] Loaded binary imported training rows for '{}' ({} rows)",
                        markerName, imported.rows().size());
            } else {
                this.importedTrainingFeatureNames = null;
                this.importedTrainingRows = null;
            }
        } catch (Exception ex) {
            logger.warn("Failed to load binary imported rows for '{}': {}", markerName, ex.getMessage());
            this.importedTrainingFeatureNames = null;
            this.importedTrainingRows = null;
        }

        this.activeBinaryMarker = markerName;

        // Resolve the allowed class names for UI restriction.
        //
        // CRITICAL: A binary classifier for marker X is ALWAYS exactly two classes
        // {X+, X-}. We must NEVER derive this set from the on-disk label store,
        // because if that file is contaminated with foreign classes (e.g. PD-1+/-
        // labels in a GrB store) we would then "validate" those foreign classes
        // and every subsequent retainClasses() filter would become a no-op,
        // permanently preserving the contamination on every save.
        //
        // The trained classifier is allowed to override the canonical pair only
        // when its class list is a subset of {markerName+, markerName-} — this
        // covers degenerate single-class classifiers but never widens the set.
        java.util.LinkedHashSet<String> canonical = new java.util.LinkedHashSet<>();
        canonical.add(markerName + "+");
        canonical.add(markerName + "-");
        if (this.classifier != null
                && this.classifier.getClassNames() != null
                && !this.classifier.getClassNames().isEmpty()
                && canonical.containsAll(this.classifier.getClassNames())) {
            this.activeBinaryClassNames = List.copyOf(this.classifier.getClassNames());
        } else {
            this.activeBinaryClassNames = List.copyOf(canonical);
        }

        // Self-heal: unconditionally drop any labels whose class is not in the
        // canonical set for this marker. This evicts cross-contamination from
        // earlier sessions where labels from other classifiers bled into this
        // marker's store (e.g. via collectLabelsFromAnnotations or older buggy
        // save paths). Runs every time the user enters binary mode so a single
        // open of the panel is enough to clean a contaminated file.
        if (this.labelStore != null && this.labelStore.size() > 0) {
            int removed = this.labelStore.retainClasses(canonical);
            if (removed > 0) {
                logger.info("[CellTune] Pruned {} foreign-class labels from binary store '{}' on entry",
                        removed, markerName);
                try {
                    ProjectStateManager.saveBinaryLabels(project, markerName, this.labelStore);
                } catch (IOException ex) {
                    logger.warn("Failed to persist self-healed binary labels for '{}': {}",
                            markerName, ex.getMessage());
                }
                // Also scrub every per-image label file for this marker so that
                // disk state matches the now-cleaned in-memory state. Without
                // this, a stale per-image file would be re-loaded on the next
                // image switch and reintroduce foreign labels.
                scrubBinaryPerImageLabels(project, markerName, canonical);
            }
        }

        syncPanelState();
        logger.info("[CellTune] Entered binary mode for marker '{}' (classes: {})",
                markerName, activeBinaryClassNames);

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
            // Defence-in-depth: filter out any labels that don't belong to this binary
            // classifier's classes before persisting the canonical state.
            if (activeBinaryClassNames != null && !activeBinaryClassNames.isEmpty()) {
                labelStore.retainClasses(new java.util.LinkedHashSet<>(activeBinaryClassNames));
            }
            try {
                ProjectStateManager.saveBinaryLabels(project, activeBinaryMarker, labelStore);
            } catch (IOException ex) {
                logger.warn("Failed to save binary labels for '{}' on exit: {}", activeBinaryMarker, ex.getMessage());
            }
        }

        // Restore multi-class state
        this.labelStore = (preBinaryLabelStore != null) ? preBinaryLabelStore : new LabelStore("CellTune");
        this.classifier = preBinaryClassifier;
        this.importedTrainingRows = preBinaryImportedTrainingRows;
        this.importedTrainingFeatureNames = preBinaryImportedTrainingFeatureNames;
        this.activeBinaryMarker = null;
        this.activeBinaryClassNames = null;
        this.preBinaryLabelStore = null;
        this.preBinaryClassifier = null;
        this.preBinaryImportedTrainingRows = null;
        this.preBinaryImportedTrainingFeatureNames = null;

        syncPanelState();
        logger.info("[CellTune] Exited binary mode \u2014 restored multi-class state");
    }
}

