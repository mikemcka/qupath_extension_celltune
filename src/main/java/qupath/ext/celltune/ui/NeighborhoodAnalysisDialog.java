package qupath.ext.celltune.ui;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.NeighborhoodCohort;
import qupath.ext.celltune.model.NeighborhoodModel;
import qupath.ext.celltune.model.NeighborhoodModel.ClusterResult;
import qupath.ext.celltune.model.ScatterMath;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.color.ColorMaps;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.MeasurementMapper;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.projects.Project;

/**
 * Non-modal control dialog for cellular-neighborhood (CN) spatial clustering on
 * the current image (Schürch/Nolan method). For each cell it builds the cell-type
 * composition of its local spatial window (kNN or radius), k-means those
 * composition vectors into CNs, and writes the CN id back as a non-destructive
 * numeric {@code "CN"} measurement — the cell's {@link PathClass} phenotype is
 * never modified.
 *
 * <p>Mirrors {@link DistanceMeasurementsDialog} for the non-modal stage, daemon
 * worker thread, and progress/log presentation, and {@code ScatterPlotView} for
 * the standardize-then-cluster call. A one-click "Color by" toggle flips the
 * viewer's cell colouring between the classification (phenotype) and the CN
 * measurement using QuPath's overlay measurement mapper — also non-destructive.
 */
public class NeighborhoodAnalysisDialog {

    private static final Logger logger = LoggerFactory.getLogger(NeighborhoodAnalysisDialog.class);

    /** Measurement name written per cell; also the value the color toggle maps. */
    public static final String CN_MEASUREMENT = "CN";

    /**
     * Numeric measurement holding the CN class <em>code</em> (1..m after naming/merge) — drives the
     * "Color by: CN class" overlay, which needs a number. The human-readable name goes to
     * {@link #CN_CLASS_METADATA} instead.
     */
    public static final String CN_CLASS_MEASUREMENT = "CN Class code";

    /**
     * Metadata key holding the user-assigned CN class <em>name</em> (e.g. "tumour") as a string —
     * QuPath measurements are numeric-only, so the name lives in the per-object metadata map, where it
     * shows as a text column in the detection table and in exports. Non-destructive.
     */
    public static final String CN_CLASS_METADATA = "CN Class";

    private enum Coloring {
        NONE,
        CN,
        CLASS,
        DIVERSITY
    }

    /**
     * Distinct qualitative palette (Glasbey/Tab-style) for categorical CN colouring —
     * adjacent CNs are assigned maximally-contrasting entries so regions next to each
     * other are easy to tell apart (continuous ramps like Viridis wash them together).
     */
    private static final Color[] CATEGORICAL = {
        Color.web("#e6194b"), Color.web("#3cb44b"), Color.web("#4363d8"), Color.web("#f58231"),
        Color.web("#911eb4"), Color.web("#42d4f4"), Color.web("#f032e6"), Color.web("#bfef45"),
        Color.web("#fabed4"), Color.web("#469990"), Color.web("#dcbeff"), Color.web("#9a6324"),
        Color.web("#800000"), Color.web("#aaffc3"), Color.web("#808000"), Color.web("#000075"),
        Color.web("#a9a9a9"), Color.web("#ffe119"), Color.web("#000000"), Color.web("#ffd8b1"),
    };

    private final QuPathGUI qupath;
    private final Stage stage;

    // Controls
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton knnRadio = new RadioButton("k nearest neighbours");
    private final RadioButton radiusRadio = new RadioButton("within radius");
    private final Spinner<Integer> kSpinner = new Spinner<>();
    private final Spinner<Double> radiusSpinner = new Spinner<>();
    private final Spinner<Integer> cnSpinner = new Spinner<>();
    private final CheckBox includeCenterBox = new CheckBox("Include centre cell in its own window");
    private final CheckBox standardizeBox = new CheckBox("Standardize compositions before clustering");
    private final CheckBox showHeatmapBox = new CheckBox("Show enrichment heatmap after run");
    private final TextField pixelSizeField = new TextField();
    private final Map<String, CheckBox> typeChecks = new LinkedHashMap<>();

    // Scope: current image vs whole project (cohort)
    private final ToggleGroup scopeGroup = new ToggleGroup();
    private final RadioButton currentScopeRadio = new RadioButton("Current image");
    private final RadioButton projectScopeRadio = new RadioButton("Whole project");
    private final List<String> allImageNames = new ArrayList<>();
    private List<String> selectedImages = new ArrayList<>(); // chosen project images (defaults to all)
    private final Label imagesCountLabel = new Label();
    private final Spinner<Integer> sampleSpinner = new Spinner<>();
    private final Spinner<Integer> workersSpinner = new Spinner<>();

    private final TextArea logArea = new TextArea();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Configure and click Run.");
    private final Button runBtn = new Button("Run");
    private final Button closeBtn = new Button("Close");
    private final Button toggleBtn = new Button("Color by: Neighborhood (CN)");
    private final Button classToggleBtn = new Button("Color by: CN Class");
    private final Button diversityToggleBtn = new Button("Color by: diversity");
    private final Button heatmapBtn = new Button("Show heatmap");

    private final String unit;
    private final ColorMaps.ColorMap diversityRamp =
            ColorMaps.getColorMaps().getOrDefault("Inferno", ColorMaps.getDefaultColorMap());
    private MeasurementMapper cnMapper; // active overlay mapper (CN / CN class / diversity)
    private Coloring coloring = Coloring.NONE;
    private List<Color> cnDisplayColors = List.of(); // categorical, adjacency-aware, per CN

    // Last run's results, cached so the heatmap can be reopened without rerunning.
    private double[][] lastCnMean;
    private long[] lastCnCounts;
    private List<String> lastTypeNames;
    private String lastTitle;
    private int lastKEff;
    private java.util.List<PathObject> lastCells;
    private final Map<Integer, String> lastNames = new LinkedHashMap<>(); // CN id -> name
    private int lastClassCount; // distinct CN classes after the last apply
    private boolean lastCohort; // was the last run whole-project scope?
    private List<String> lastCohortImages = List.of(); // images in the last cohort run (for cohort-wide naming)
    private int lastWorkers = 1; // worker count from the last cohort run (reused for cohort-wide naming)

    public NeighborhoodAnalysisDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        var imageData = qupath.getImageData();
        boolean calibrated =
                imageData != null && imageData.getServer().getPixelCalibration().hasPixelSizeMicrons();
        this.unit = calibrated ? "µm" : "px";
        this.stage = buildStage();
    }

    public void show() {
        stage.show();
    }

    // ── UI ─────────────────────────────────────────────────────────────────────

    private Stage buildStage() {
        var imageData = qupath.getImageData();

        // Prefill pixel size from the current image's calibration.
        Double defaultPixelSize = null;
        if (imageData != null) {
            try {
                var cal = imageData.getServer().getPixelCalibration();
                if (cal.hasPixelSizeMicrons()) {
                    defaultPixelSize = cal.getPixelWidthMicrons();
                }
            } catch (Exception ignored) {
            }
        }

        // ── Neighborhood mode ──
        knnRadio.setToggleGroup(modeGroup);
        radiusRadio.setToggleGroup(modeGroup);
        knnRadio.setSelected(true);

        kSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(2, 100, 10));
        kSpinner.setEditable(true);
        kSpinner.setPrefWidth(90);
        kSpinner.setTooltip(new Tooltip("Window size: each cell's k nearest neighbours (paper default 10)."));

        radiusSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(5, 500, 50, 5));
        radiusSpinner.setEditable(true);
        radiusSpinner.setPrefWidth(90);
        radiusSpinner.setTooltip(new Tooltip("Window size: all cells within this radius (" + unit + ")."));

        cnSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(2, 30, 10));
        cnSpinner.setEditable(true);
        cnSpinner.setPrefWidth(90);
        cnSpinner.setTooltip(new Tooltip("Number of cellular neighborhoods to cluster into (paper default 10)."));

        // Enable only the active mode's spinner.
        Runnable syncMode = () -> {
            boolean knn = knnRadio.isSelected();
            kSpinner.setDisable(!knn);
            radiusSpinner.setDisable(knn);
        };
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> syncMode.run());
        syncMode.run();

        HBox knnRow = new HBox(8, knnRadio, new Label("k ="), kSpinner);
        knnRow.setAlignment(Pos.CENTER_LEFT);
        HBox radiusRow = new HBox(8, radiusRadio, new Label("radius (" + unit + ") ="), radiusSpinner);
        radiusRow.setAlignment(Pos.CENTER_LEFT);
        HBox cnRow = new HBox(8, new Label("Number of CNs ="), cnSpinner);
        cnRow.setAlignment(Pos.CENTER_LEFT);

        // ── Cell-type checklist ── (live cell classifications only, all selected)
        VBox typeBox = new VBox(3);
        typeBox.setPadding(new Insets(4));
        for (String type : discoverTypes()) {
            CheckBox cb = new CheckBox(type);
            cb.setSelected(true);
            typeChecks.put(type, cb);
            typeBox.getChildren().add(cb);
        }
        if (typeChecks.isEmpty()) {
            typeBox.getChildren().add(new Label("No classified cell types found."));
        }
        ScrollPane typeScroll = new ScrollPane(typeBox);
        typeScroll.setFitToWidth(true);
        typeScroll.setPrefHeight(140);
        typeScroll.setStyle("-fx-border-color: #ccc;");
        Button allTypes = new Button("All");
        Button noneTypes = new Button("None");
        allTypes.setOnAction(e -> typeChecks.values().forEach(cb -> cb.setSelected(true)));
        noneTypes.setOnAction(e -> typeChecks.values().forEach(cb -> cb.setSelected(false)));
        HBox typeButtons = new HBox(6, new Label("Cell types:"), allTypes, noneTypes);
        typeButtons.setAlignment(Pos.CENTER_LEFT);

        // ── Options ──
        includeCenterBox.setSelected(true);
        standardizeBox.setSelected(false); // paper clusters raw frequency vectors
        standardizeBox.setTooltip(new Tooltip("Off matches the paper (cluster raw fractions). "
                + "On z-scores composition columns, up-weighting rare cell types."));
        showHeatmapBox.setSelected(true);

        pixelSizeField.setPromptText("e.g. 0.5");
        pixelSizeField.setPrefColumnCount(8);
        if (defaultPixelSize != null) {
            pixelSizeField.setText(formatPixelSize(defaultPixelSize));
        }
        HBox pxRow = new HBox(6, new Label("Pixel size:"), pixelSizeField, new Label("µm/pixel (optional)"));
        pxRow.setAlignment(Pos.CENTER_LEFT);

        // ── Scope (current image vs whole project) ──
        var project = qupath.getProject();
        currentScopeRadio.setToggleGroup(scopeGroup);
        projectScopeRadio.setToggleGroup(scopeGroup);
        currentScopeRadio.setSelected(true);
        HBox scopeRow = new HBox(12, new Label("Scope:"), currentScopeRadio, projectScopeRadio);
        scopeRow.setAlignment(Pos.CENTER_LEFT);

        if (project != null) {
            for (var entry : project.getImageList()) {
                String name = entry.getImageName();
                if (name != null) {
                    allImageNames.add(name);
                }
            }
        }
        selectedImages = new ArrayList<>(allImageNames); // default: all images
        Button imagesBtn = new Button("Choose images…");
        imagesBtn.setOnAction(e -> chooseImages());
        imagesCountLabel.setText(imageCountText());
        HBox imgButtons = new HBox(8, new Label("Images:"), imagesBtn, imagesCountLabel);
        imgButtons.setAlignment(Pos.CENTER_LEFT);

        sampleSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1000, 5_000_000, 50_000, 10_000));
        sampleSpinner.setEditable(true);
        sampleSpinner.setPrefWidth(120);
        sampleSpinner.setTooltip(new Tooltip("Composition windows pooled across images to FIT the CN clusters once. "
                + "Every cell is still assigned afterwards — 50k is plenty for stable centroids."));
        HBox sampleRow = new HBox(8, new Label("Sample windows for fit:"), sampleSpinner);
        sampleRow.setAlignment(Pos.CENTER_LEFT);

        int cpu = Runtime.getRuntime().availableProcessors();
        int defaultWorkers = Math.max(1, Math.min(8, cpu - 1));
        workersSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, Math.max(1, cpu), defaultWorkers, 1));
        workersSpinner.setEditable(true);
        workersSpinner.setPrefWidth(80);
        workersSpinner.setTooltip(new Tooltip("Images processed in parallel during the sample and assign passes. "
                + "Each worker loads a full image, so higher = faster but more memory (" + cpu + " CPUs detected)."));
        HBox workersRow = new HBox(8, new Label("Parallel workers:"), workersSpinner);
        workersRow.setAlignment(Pos.CENTER_LEFT);

        Label projectHint =
                new Label("Project scope pools windows from the selected images, clusters once, then writes a "
                        + "consistent CN to every selected image (each image is saved).");
        projectHint.setWrapText(true);
        projectHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        VBox projectBox = new VBox(6, imgButtons, sampleRow, workersRow, projectHint);

        projectScopeRadio.setDisable(project == null);
        Runnable syncScope = () -> projectBox.setDisable(!projectScopeRadio.isSelected());
        scopeGroup.selectedToggleProperty().addListener((o, a, b) -> syncScope.run());
        syncScope.run();

        VBox scopeSection = new VBox(6, scopeRow, projectBox);

        // ── Log / progress ──
        logArea.setEditable(false);
        logArea.setPrefHeight(150);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        progressBar.setPrefWidth(Double.MAX_VALUE);

        // ── Buttons ──
        runBtn.setDefaultButton(true);
        runBtn.setPrefWidth(90);
        runBtn.setOnAction(e -> run());
        closeBtn.setPrefWidth(90);
        closeBtn.setOnAction(e -> stage.close());
        toggleBtn.setDisable(true);
        toggleBtn.setTooltip(new Tooltip("Flip viewer colouring between phenotype and CN. Non-destructive."));
        toggleBtn.setOnAction(e -> setColoring(coloring == Coloring.CN ? Coloring.NONE : Coloring.CN));
        classToggleBtn.setDisable(true);
        classToggleBtn.setTooltip(
                new Tooltip("Colour by the assigned CN Class (after naming/merge in the heatmap). Non-destructive."));
        classToggleBtn.setOnAction(e -> setColoring(coloring == Coloring.CLASS ? Coloring.NONE : Coloring.CLASS));
        diversityToggleBtn.setDisable(true);
        diversityToggleBtn.setTooltip(new Tooltip(
                "Colour cells by their neighborhood's cell-type diversity (Shannon, 0–1). Non-destructive."));
        diversityToggleBtn.setOnAction(
                e -> setColoring(coloring == Coloring.DIVERSITY ? Coloring.NONE : Coloring.DIVERSITY));
        heatmapBtn.setDisable(true);
        heatmapBtn.setTooltip(new Tooltip("Reopen the CN enrichment heatmap + colour key from the last run."));
        heatmapBtn.setOnAction(e -> showHeatmap());
        // Colour/heatmap buttons wrap to as many rows as needed so they never clip.
        FlowPane colorButtons = new FlowPane(8, 6, toggleBtn, classToggleBtn, diversityToggleBtn, heatmapBtn);
        // Run/Close get their own right-aligned row so the primary actions are always visible.
        HBox actionButtons = new HBox(10, runBtn, closeBtn);
        actionButtons.setAlignment(Pos.CENTER_RIGHT);
        VBox buttons = new VBox(8, colorButtons, actionButtons);
        buttons.setPadding(new Insets(4, 0, 0, 0));

        VBox root = new VBox(
                10,
                scopeSection,
                new Separator(),
                new Label("Neighborhood window:"),
                knnRow,
                radiusRow,
                cnRow,
                new Separator(),
                typeButtons,
                typeScroll,
                new Separator(),
                includeCenterBox,
                standardizeBox,
                showHeatmapBox,
                pxRow,
                new Separator(),
                statusLabel,
                progressBar,
                logArea,
                buttons);
        root.setPadding(new Insets(14));

        ScrollPane rootScroll = new ScrollPane(root);
        rootScroll.setFitToWidth(true);

        Stage s = new Stage();
        s.setTitle("Cellular Neighborhoods");
        s.initOwner(qupath.getStage());
        s.initModality(Modality.NONE);
        s.setScene(new Scene(rootScroll, 540, 840));
        return s;
    }

    /**
     * Distinct non-ignored cell classifications actually assigned to cells on the
     * current image — the live {@code PathClass}es, NOT the project Class list (which
     * may still hold old/composite gating classes that no cell carries). In project
     * scope the open image is assumed representative of the cohort's phenotypes.
     */
    private List<String> discoverTypes() {
        Set<String> types = new LinkedHashSet<>();
        var imageData = qupath.getImageData();
        if (imageData != null) {
            for (PathObject cell : cells(imageData)) {
                PathClass pc = cell.getPathClass();
                if (pc != null && pc.isValid() && !PathClassTools.isIgnoredClass(pc)) {
                    types.add(pc.toString());
                }
            }
        }
        return new ArrayList<>(types);
    }

    /** Open the dual-list image picker (reuses {@link ImageSelectionPane}) for project scope. */
    private void chooseImages() {
        if (allImageNames.isEmpty()) {
            Dialogs.showInfoNotification("CellTune", "No project images found.");
            return;
        }
        List<String> chosen =
                new ImageSelectionPane(qupath.getStage(), allImageNames, currentImageName()).showAndWait();
        if (chosen != null) {
            selectedImages = chosen;
            imagesCountLabel.setText(imageCountText());
        }
    }

    private String imageCountText() {
        int n = selectedImages.size();
        int total = allImageNames.size();
        return n == total ? ("All " + total + " images") : (n + " of " + total + " images");
    }

    /** Display name of the currently-open image (or null). */
    private String currentImageName() {
        var open = qupath.getImageData();
        var project = qupath.getProject();
        if (open != null && project != null) {
            var entry = project.getEntry(open);
            if (entry != null) {
                return entry.getImageName();
            }
        }
        return null;
    }

    private static java.util.Collection<PathObject> cells(qupath.lib.images.ImageData<?> imageData) {
        var hierarchy = imageData.getHierarchy();
        var cells = hierarchy.getCellObjects();
        return cells.isEmpty() ? hierarchy.getDetectionObjects() : cells;
    }

    private static String formatPixelSize(double v) {
        String s = String.format(Locale.US, "%.6f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    // ── Worker ─────────────────────────────────────────────────────────────────

    private void run() {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            log("ERROR: No image is open.");
            return;
        }
        commitSpinner(kSpinner);
        commitSpinner(cnSpinner);
        commitDoubleSpinner(radiusSpinner);

        // Selected types → index map.
        List<String> selectedTypes = new ArrayList<>();
        for (var e : typeChecks.entrySet()) {
            if (e.getValue().isSelected()) {
                selectedTypes.add(e.getKey());
            }
        }
        if (selectedTypes.size() < 2) {
            log("ERROR: Select at least two cell types.");
            return;
        }

        final boolean knn = knnRadio.isSelected();
        final int k = kSpinner.getValue();
        final double radius = radiusSpinner.getValue();
        final int nCN = cnSpinner.getValue();
        final boolean includeCenter = includeCenterBox.isSelected();
        final boolean standardize = standardizeBox.isSelected();
        final boolean showHeatmap = showHeatmapBox.isSelected();

        Double pxOverride = null;
        String pxText =
                pixelSizeField.getText() == null ? "" : pixelSizeField.getText().trim();
        if (!pxText.isEmpty()) {
            try {
                pxOverride = Double.parseDouble(pxText);
                if (!(pxOverride > 0)) {
                    log("ERROR: Pixel size must be a positive number.");
                    return;
                }
            } catch (NumberFormatException ex) {
                log("ERROR: Pixel size must be a number (e.g. 0.5).");
                return;
            }
        }
        final Double pxSize = pxOverride;

        if (projectScopeRadio.isSelected()) {
            commitSpinner(sampleSpinner);
            commitSpinner(workersSpinner);
            runCohort(
                    knn,
                    k,
                    radius,
                    nCN,
                    includeCenter,
                    standardize,
                    showHeatmap,
                    pxSize,
                    selectedTypes,
                    sampleSpinner.getValue(),
                    workersSpinner.getValue());
            return;
        }

        runBtn.setDisable(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        logArea.clear();
        log("Collecting cells…");

        Thread worker = new Thread(
                () -> {
                    try {
                        runPipeline(
                                imageData,
                                knn,
                                k,
                                radius,
                                nCN,
                                includeCenter,
                                standardize,
                                showHeatmap,
                                pxSize,
                                selectedTypes);
                    } catch (Exception ex) {
                        logger.warn("Cellular neighborhood run failed", ex);
                        log("ERROR: " + ex.getMessage());
                    } finally {
                        Platform.runLater(() -> {
                            runBtn.setDisable(false);
                            progressBar.setProgress(0);
                        });
                    }
                },
                "CellTune-Neighborhoods");
        worker.setDaemon(true);
        worker.start();
    }

    // ── Project-wide (cohort) CN ────────────────────────────────────────────────

    private void runCohort(
            boolean knn,
            int k,
            double radius,
            int nCN,
            boolean includeCenter,
            boolean standardize,
            boolean showHeatmap,
            Double pxSize,
            List<String> selectedTypes,
            int sampleCap,
            int workers) {
        if (qupath.getProject() == null) {
            log("ERROR: No project is open.");
            return;
        }
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) (Object) qupath.getProject();

        List<String> images = new ArrayList<>(selectedImages);
        if (images.isEmpty()) {
            log("ERROR: Select at least one image (use \"Choose images…\").");
            return;
        }

        ImageData<BufferedImage> openData = qupath.getImageData();
        String openName = null;
        if (openData != null) {
            var entry = project.getEntry(openData);
            if (entry != null) {
                openName = entry.getImageName();
            }
        }
        final String openNameF = openName;
        final ImageData<BufferedImage> openDataF = openData;
        final List<String> typeNames = new ArrayList<>(selectedTypes);
        var params = new NeighborhoodCohort.Params(knn, k, radius, includeCenter, pxSize);

        runBtn.setDisable(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        logArea.clear();
        log("Project CN over " + images.size() + " image(s); sampling up to " + sampleCap + " windows using "
                + Math.max(1, Math.min(workers, images.size())) + " worker(s)…");

        Thread worker = new Thread(
                () -> {
                    try {
                        var sample = NeighborhoodCohort.sample(
                                project, images, typeNames, params, sampleCap, workers, this::log);
                        if (sample.sampled() < 2) {
                            log("ERROR: Too few non-empty windows across the project to cluster.");
                            return;
                        }
                        log(String.format(
                                Locale.US,
                                "Pooled %,d windows from %d image(s); fitting %d CNs…",
                                sample.sampled(),
                                sample.imageCount(),
                                nCN));

                        int nTypes = typeNames.size();
                        double[] mean = null;
                        double[] sd = null;
                        double[][] forFit = sample.rows();
                        if (standardize) {
                            mean = new double[nTypes];
                            sd = new double[nTypes];
                            forFit = ScatterMath.standardizeColumns(sample.rows(), mean, sd);
                        }
                        ClusterResult fit = NeighborhoodModel.clusterCompositions(forFit, nCN);
                        int kEff = fit.kEffective();

                        log("Assigning CN across the project (writing + saving each image)…");
                        var ar = NeighborhoodCohort.assignAcrossProject(
                                project,
                                images,
                                typeNames,
                                params,
                                mean,
                                sd,
                                fit.centroids(),
                                openDataF,
                                openNameF,
                                workers,
                                this::log,
                                frac -> Platform.runLater(() -> progressBar.setProgress(frac)));

                        String title = "Project (" + sample.imageCount() + " images)";
                        Pruned pruned = pruneEmptyTypes(typeNames, ar.cnMean());
                        long[] cnCounts = ar.cnCounts();
                        List<PathObject> openCells =
                                openDataF != null ? new ArrayList<>(cells(openDataF)) : new ArrayList<>();
                        Platform.runLater(() -> {
                            lastCnMean = pruned.cnMean();
                            lastCnCounts = cnCounts;
                            lastTypeNames = pruned.types();
                            lastTitle = title;
                            lastKEff = kEff;
                            lastCells = openCells;
                            lastCohort = true;
                            lastCohortImages = new ArrayList<>(images);
                            lastWorkers = workers;
                            cnDisplayColors = categoricalColors(kEff);
                            lastNames.clear();
                            lastClassCount = 0;
                            classToggleBtn.setDisable(true);
                            toggleBtn.setDisable(false);
                            diversityToggleBtn.setDisable(false);
                            heatmapBtn.setDisable(false);
                            statusLabel.setText(String.format(
                                    Locale.US,
                                    "%d CNs written across %d images (%,d cells, %,d empty).",
                                    kEff,
                                    sample.imageCount(),
                                    ar.assigned(),
                                    ar.empty()));
                            if (showHeatmap) {
                                openHeatmap();
                            }
                        });
                    } catch (Exception ex) {
                        logger.warn("Project CN run failed", ex);
                        log("ERROR: " + ex.getMessage());
                    } finally {
                        Platform.runLater(() -> {
                            runBtn.setDisable(false);
                            progressBar.setProgress(0);
                        });
                    }
                },
                "CellTune-Neighborhoods-Cohort");
        worker.setDaemon(true);
        worker.start();
    }

    private void runPipeline(
            qupath.lib.images.ImageData<?> imageData,
            boolean knn,
            int k,
            double radius,
            int nCN,
            boolean includeCenter,
            boolean standardize,
            boolean showHeatmap,
            Double pxSize,
            List<String> selectedTypes) {

        var cal = imageData.getServer().getPixelCalibration();
        boolean calibrated = cal.hasPixelSizeMicrons();
        double pw = pxSize != null ? pxSize : (calibrated ? cal.getPixelWidthMicrons() : 1.0);
        double ph = pxSize != null ? pxSize : (calibrated ? cal.getPixelHeightMicrons() : 1.0);

        List<PathObject> cellList = new ArrayList<>(cells(imageData));
        int n = cellList.size();
        if (n == 0) {
            log("No cells found.");
            return;
        }

        Map<String, Integer> typeIndex = new LinkedHashMap<>();
        for (String t : selectedTypes) {
            typeIndex.put(t, typeIndex.size());
        }
        int nTypes = selectedTypes.size();

        double[] xs = new double[n];
        double[] ys = new double[n];
        int[] typeId = new int[n];
        for (int i = 0; i < n; i++) {
            PathObject cell = cellList.get(i);
            var roi = cell.getROI();
            if (roi == null) {
                xs[i] = Double.NaN;
                ys[i] = Double.NaN;
            } else {
                xs[i] = roi.getCentroidX() * pw;
                ys[i] = roi.getCentroidY() * ph;
            }
            PathClass pc = cell.getPathClass();
            Integer idx = (pc != null && pc.isValid() && !PathClassTools.isIgnoredClass(pc))
                    ? typeIndex.get(pc.toString())
                    : null;
            typeId[i] = idx != null ? idx : -1;
        }

        log(String.format(
                Locale.US,
                "Building windows for %,d cells (%s, %s)…",
                n,
                knn ? "kNN k=" + k : "radius=" + radius + unit,
                "types=" + nTypes));
        int[][] neighbors = knn
                ? NeighborhoodModel.kNearestNeighborIndices(xs, ys, k)
                : NeighborhoodModel.radiusNeighborIndices(xs, ys, radius);

        double[][] comp = NeighborhoodModel.compositionMatrix(neighbors, typeId, nTypes, includeCenter);

        // Active rows = non-empty windows; empty/all-ignored windows get CN = -1.
        List<Integer> activeIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (double v : comp[i]) {
                sum += v;
            }
            if (sum > 0) {
                activeIdx.add(i);
            }
        }
        int nActive = activeIdx.size();
        int empty = n - nActive;
        if (nActive < 2) {
            log("ERROR: Fewer than two cells have a non-empty neighbourhood — nothing to cluster.");
            return;
        }

        double[][] activeComp = new double[nActive][nTypes];
        for (int a = 0; a < nActive; a++) {
            activeComp[a] = comp[activeIdx.get(a)];
        }

        double[][] toCluster = activeComp;
        if (standardize) {
            toCluster = ScatterMath.standardizeColumns(activeComp, new double[nTypes], new double[nTypes]);
            log("Standardized composition columns before clustering.");
        }

        log("Clustering into " + nCN + " neighborhoods…");
        ClusterResult res = NeighborhoodModel.clusterCompositions(toCluster, nCN);
        int kEff = res.kEffective();
        int[] activeLabels = res.labels();

        // Mean composition per CN on RAW fractions (interpretability / naming).
        double[][] cnMean = NeighborhoodModel.cnMeanComposition(activeLabels, activeComp, kEff);
        long[] cnCounts = new long[kEff];
        for (int lab : activeLabels) {
            if (lab >= 0 && lab < kEff) {
                cnCounts[lab]++;
            }
        }

        // Write CN back (1-based ids; empty windows = -1). Non-destructive.
        double[] cnValue = new double[n];
        java.util.Arrays.fill(cnValue, -1.0);
        for (int a = 0; a < nActive; a++) {
            cnValue[activeIdx.get(a)] = activeLabels[a] + 1.0;
        }
        for (int i = 0; i < n; i++) {
            cellList.get(i).getMeasurementList().put(CN_MEASUREMENT, cnValue[i]);
        }

        // Distinct, adjacency-aware CN colours so spatially-touching CNs contrast.
        int[] cnInt = new int[n];
        for (int i = 0; i < n; i++) {
            cnInt[i] = (int) cnValue[i];
        }
        double[][] adjacency = NeighborhoodModel.cnAdjacency(neighbors, cnInt, kEff);
        List<Color> displayColors = assignByAdjacency(kEff, adjacency);

        log(String.format(
                Locale.US, "Done: %d CNs over %,d cells (%,d with empty window → CN=-1).", kEff, nActive, empty));
        for (int c = 0; c < kEff; c++) {
            log(String.format(Locale.US, "  CN %d: %,d cells", c + 1, cnCounts[c]));
        }

        String title = imageTitle(imageData);
        Pruned pruned = pruneEmptyTypes(new ArrayList<>(selectedTypes), cnMean);
        Platform.runLater(() -> {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
            lastCnMean = pruned.cnMean();
            lastCnCounts = cnCounts;
            lastTypeNames = pruned.types();
            lastTitle = title;
            lastKEff = kEff;
            lastCells = cellList;
            lastCohort = false;
            lastCohortImages = List.of();
            cnDisplayColors = displayColors;
            lastNames.clear();
            lastClassCount = 0;
            classToggleBtn.setDisable(true);
            toggleBtn.setDisable(false);
            diversityToggleBtn.setDisable(false);
            heatmapBtn.setDisable(false);
            statusLabel.setText(kEff + " neighborhoods written to the \"CN\" measurement.");
            if (showHeatmap) {
                openHeatmap();
            }
        });
    }

    private String imageTitle(qupath.lib.images.ImageData<?> imageData) {
        var project = qupath.getProject();
        var live = qupath.getImageData(); // typed ImageData<BufferedImage> for getEntry
        if (project != null && live != null) {
            var entry = project.getEntry(live);
            if (entry != null && entry.getImageName() != null) {
                return entry.getImageName();
            }
        }
        return imageData.getServer().getMetadata().getName();
    }

    // ── Heatmap + CN colour key ─────────────────────────────────────────────────

    private void showHeatmap() {
        if (lastCnMean == null) {
            Dialogs.showInfoNotification("CellTune", "Run the analysis first.");
            return;
        }
        openHeatmap();
    }

    private void openHeatmap() {
        new NeighborhoodHeatmapView(
                        stage,
                        lastTitle,
                        lastTypeNames,
                        lastCnMean,
                        lastCnCounts,
                        cnDisplayColors,
                        new LinkedHashMap<>(lastNames),
                        this::applyCnClasses,
                        (code, total) -> categoricalColor(code - 1),
                        this::diversityColor)
                .show();
    }

    /** A type list + per-CN mean composition with all-zero (unused) type columns removed. */
    private record Pruned(List<String> types, double[][] cnMean) {}

    /**
     * Drop type columns that are zero in every CN — these are selected classes that
     * no cell is actually classified as (e.g. marker/gating/ROI entries from the
     * project Class list). Keeps the heatmap readable and lets the diversity score
     * normalise over the types that are genuinely in play.
     */
    private static Pruned pruneEmptyTypes(List<String> types, double[][] cnMean) {
        int nTypes = types.size();
        boolean[] used = new boolean[nTypes];
        for (double[] row : cnMean) {
            for (int j = 0; j < nTypes; j++) {
                if (row[j] > 0) {
                    used[j] = true;
                }
            }
        }
        List<Integer> keep = new ArrayList<>();
        for (int j = 0; j < nTypes; j++) {
            if (used[j]) {
                keep.add(j);
            }
        }
        if (keep.size() == nTypes) {
            return new Pruned(types, cnMean);
        }
        List<String> reducedTypes = new ArrayList<>(keep.size());
        for (int j : keep) {
            reducedTypes.add(types.get(j));
        }
        double[][] reduced = new double[cnMean.length][keep.size()];
        for (int c = 0; c < cnMean.length; c++) {
            for (int k = 0; k < keep.size(); k++) {
                reduced[c][k] = cnMean[c][keep.get(k)];
            }
        }
        return new Pruned(reducedTypes, reduced);
    }

    // ── CN colour palettes ──────────────────────────────────────────────────────

    /** A distinct qualitative palette colour for index {@code idx} (cycles if needed). */
    private static Color categoricalColor(int idx) {
        int m = CATEGORICAL.length;
        return CATEGORICAL[((idx % m) + m) % m];
    }

    private static List<Color> categoricalColors(int n) {
        List<Color> list = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            list.add(categoricalColor(i));
        }
        return list;
    }

    /** Sequential colour for a diversity value in {@code [0, 1]} (the diversity ramp). */
    private Color diversityColor(double d) {
        Integer argb = diversityRamp.getColor(d, 0, 1);
        int v = argb == null ? 0 : argb;
        return Color.rgb((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
    }

    /**
     * Assign palette colours to CNs so spatially-adjacent CNs get maximally
     * contrasting colours. Greedy: process CNs most-connected first; each picks the
     * palette colour (preferring still-unused entries to stay distinct) that is
     * farthest in RGB from its already-coloured spatial neighbours.
     */
    private static List<Color> assignByAdjacency(int k, double[][] adj) {
        double[][] w = new double[k][k];
        double[] degree = new double[k];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                double s = adj[i][j] + adj[j][i];
                w[i][j] = s;
                degree[i] += s;
            }
        }
        Integer[] order = new Integer[k];
        for (int i = 0; i < k; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(degree[b], degree[a]));

        Color[] chosen = new Color[k];
        boolean[] used = new boolean[CATEGORICAL.length];
        int unusedLeft = CATEGORICAL.length;
        for (Integer cn : order) {
            int bestP = 0;
            double bestMin = -1;
            for (int p = 0; p < CATEGORICAL.length; p++) {
                if (unusedLeft > 0 && used[p]) {
                    continue; // keep colours distinct while the palette has spares
                }
                double minDist = Double.MAX_VALUE;
                for (int other = 0; other < k; other++) {
                    if (other == cn || chosen[other] == null || w[cn][other] <= 0) {
                        continue;
                    }
                    minDist = Math.min(minDist, colorDist(CATEGORICAL[p], chosen[other]));
                }
                if (minDist > bestMin) {
                    bestMin = minDist;
                    bestP = p;
                }
            }
            chosen[cn] = CATEGORICAL[bestP];
            if (!used[bestP]) {
                used[bestP] = true;
                unusedLeft--;
            }
        }
        return java.util.Arrays.asList(chosen);
    }

    private static double colorDist(Color a, Color b) {
        double dr = a.getRed() - b.getRed();
        double dg = a.getGreen() - b.getGreen();
        double db = a.getBlue() - b.getBlue();
        return dr * dr + dg * dg + db * db;
    }

    // ── CN class assignment (naming / merge) ────────────────────────────────────

    /** Metadata name given to empty-window cells (CN = -1) so the column is never blank. */
    private static final String UNASSIGNED_CN_NAME = "Unassigned";

    /**
     * Record the user's CN naming/merge onto each cell, non-destructively (phenotype
     * {@code getPathClass()} is untouched). Two representations are written:
     * <ul>
     *   <li>the <b>name</b> as a {@code "CN Class"} <em>metadata string</em> (e.g. "tumour") — the
     *       human-readable label shown in QuPath's detection table and exports; and</li>
     *   <li>a {@code "CN Class code"} <em>numeric measurement</em> (1..m) that powers the colour
     *       overlay (measurements can't hold text).</li>
     * </ul>
     * Distinct names (in CN-id order) share a code, so naming two CNs the same merges them under one
     * name and one code. Empty-window cells (CN = -1) get name {@value #UNASSIGNED_CN_NAME} / code -1.
     */
    private void applyCnClasses(Map<Integer, String> names) {
        if (lastCells == null) {
            return;
        }
        lastNames.clear();
        lastNames.putAll(names);

        // Distinct effective names → codes, in CN-id order.
        Map<String, Integer> codes = new LinkedHashMap<>();
        for (int id = 1; id <= lastKEff; id++) {
            String nm = names.get(id);
            if (nm == null || nm.isBlank()) {
                nm = "CN " + id;
            }
            codes.computeIfAbsent(nm, k -> codes.size() + 1);
        }
        lastClassCount = codes.size();

        // CN id (1-based) → effective name / code, reused to name every cell (open image here,
        // and the rest of the cohort in applyNamesAcrossCohort).
        Map<Integer, String> nameByCn = new LinkedHashMap<>();
        Map<Integer, Integer> codeByCn = new LinkedHashMap<>();
        for (int id = 1; id <= lastKEff; id++) {
            String nm = names.get(id);
            if (nm == null || nm.isBlank()) {
                nm = "CN " + id;
            }
            nameByCn.put(id, nm);
            codeByCn.put(id, codes.get(nm));
        }

        for (PathObject cell : lastCells) {
            double cnVal = cell.getMeasurementList().get(CN_MEASUREMENT);
            int code = -1;
            String name = UNASSIGNED_CN_NAME;
            if (!Double.isNaN(cnVal) && cnVal >= 1) {
                int id = (int) Math.round(cnVal);
                String nm = names.get(id);
                if (nm == null || nm.isBlank()) {
                    nm = "CN " + id;
                }
                name = nm;
                Integer c = codes.get(nm);
                code = c != null ? c : -1;
            }
            cell.getMeasurementList().put(CN_CLASS_MEASUREMENT, code);
            cell.getMetadata().put(CN_CLASS_METADATA, name);
        }

        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
        }
        classToggleBtn.setDisable(false);
        if (coloring == Coloring.CLASS) {
            setColoring(Coloring.CLASS); // refresh mapper range for the new class count
        }
        log(lastClassCount + " CN Class(es) — name → \"" + CN_CLASS_METADATA + "\" metadata, code → \""
                + CN_CLASS_MEASUREMENT + "\" measurement:");
        for (var e : codes.entrySet()) {
            log("  " + e.getValue() + " = " + e.getKey());
        }

        if (lastCohort) {
            // Whole-project run: name every image in the cohort, not just the open one.
            applyNamesAcrossCohort(nameByCn, codeByCn);
        } else {
            statusLabel.setText(lastClassCount + " CN Classes written to the open image (name → \"" + CN_CLASS_METADATA
                    + "\", code → \"" + CN_CLASS_MEASUREMENT + "\"). Ctrl+S to save.");
        }
    }

    /**
     * Apply the naming/merge to <b>every image</b> in the last whole-project run. The open image was
     * just named in memory (above), so here we persist it and then stream all the other cohort images
     * in parallel — reading each cell's saved {@code CN} id and writing {@code CN Class} /
     * {@code CN Class code} — reusing the run's worker count. Runs off the FX thread.
     */
    private void applyNamesAcrossCohort(Map<Integer, String> nameByCn, Map<Integer, Integer> codeByCn) {
        if (qupath.getProject() == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = (Project<BufferedImage>) (Object) qupath.getProject();
        final ImageData<BufferedImage> openData = qupath.getImageData();
        final String openName = currentImageName();

        // Every cohort image except the open one (open was named in memory; we only save it).
        List<String> others = new ArrayList<>();
        for (String name : lastCohortImages) {
            if (openName == null || !openName.equals(name)) {
                others.add(name);
            }
        }
        final int workers = lastWorkers;
        final int totalImages = lastCohortImages.size();

        runBtn.setDisable(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        log("Applying CN Classes across " + totalImages + " project image(s) using "
                + Math.max(1, Math.min(workers, Math.max(1, others.size()))) + " worker(s)…");

        Thread worker = new Thread(
                () -> {
                    try {
                        // Persist the open image (its cells were named in memory above).
                        if (openData != null) {
                            var entry = project.getEntry(openData);
                            if (entry != null) {
                                entry.saveImageData(openData);
                            }
                        }
                        long updated = NeighborhoodCohort.applyNamesAcrossProject(
                                project,
                                others,
                                nameByCn,
                                codeByCn,
                                UNASSIGNED_CN_NAME,
                                CN_CLASS_MEASUREMENT,
                                CN_CLASS_METADATA,
                                workers,
                                this::log,
                                frac -> Platform.runLater(() -> progressBar.setProgress(frac)));
                        log(String.format(
                                Locale.US,
                                "Done — CN Classes written to %,d cells across %d other image(s).",
                                updated,
                                others.size()));
                    } catch (Exception ex) {
                        logger.warn("Cohort CN Class apply failed", ex);
                        log("ERROR: " + ex.getMessage());
                    } finally {
                        Platform.runLater(() -> {
                            runBtn.setDisable(false);
                            progressBar.setProgress(0);
                            statusLabel.setText(String.format(
                                    Locale.US,
                                    "%d CN Classes written across %d image(s) (name → \"%s\", code → \"%s\").",
                                    lastClassCount,
                                    totalImages,
                                    CN_CLASS_METADATA,
                                    CN_CLASS_MEASUREMENT));
                        });
                    }
                },
                "CellTune-Neighborhoods-Naming");
        worker.setDaemon(true);
        worker.start();
    }

    // ── Non-destructive overlay colouring (CN / CN class / diversity) ────────────

    private void setColoring(Coloring target) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() == null) {
            Dialogs.showWarningNotification("CellTune", "No active viewer to recolour.");
            return;
        }
        OverlayOptions opts = viewer.getOverlayOptions();
        opts.resetMeasurementMapper();
        cnMapper = null;
        Coloring applied = Coloring.NONE;
        if (target == Coloring.CN && !cnDisplayColors.isEmpty()) {
            cnMapper = buildMapperFromColors(viewer, CN_MEASUREMENT, cnDisplayColors);
            opts.setMeasurementMapper(cnMapper);
            applied = Coloring.CN;
        } else if (target == Coloring.CLASS && lastClassCount > 0) {
            cnMapper = buildMapperFromColors(viewer, CN_CLASS_MEASUREMENT, categoricalColors(lastClassCount));
            opts.setMeasurementMapper(cnMapper);
            applied = Coloring.CLASS;
        } else if (target == Coloring.DIVERSITY && lastCnMean != null) {
            cnMapper = buildMapperFromColors(viewer, CN_MEASUREMENT, diversityColors());
            opts.setMeasurementMapper(cnMapper);
            applied = Coloring.DIVERSITY;
        }
        coloring = applied;
        toggleBtn.setText(coloring == Coloring.CN ? "Color by: Classification" : "Color by: Neighborhood (CN)");
        classToggleBtn.setText(coloring == Coloring.CLASS ? "Color by: Classification" : "Color by: CN Class");
        diversityToggleBtn.setText(coloring == Coloring.DIVERSITY ? "Color by: Classification" : "Color by: diversity");
        viewer.repaintEntireImage();
    }

    /** Per-CN diversity colours (Shannon diversity of each CN's mean composition). */
    private List<Color> diversityColors() {
        List<Color> out = new ArrayList<>(Math.max(0, lastKEff));
        for (int c = 0; c < lastKEff && c < lastCnMean.length; c++) {
            out.add(diversityColor(NeighborhoodModel.compositionDiversity(lastCnMean[c])));
        }
        return out;
    }

    /**
     * Mapper colouring detections by {@code measurement} over {@code [1, n]} using a
     * custom colormap built from {@code colors} (one per id), so each CN/class id
     * paints exactly its key colour and empty (-1) cells keep their phenotype.
     */
    private MeasurementMapper buildMapperFromColors(QuPathViewer viewer, String measurement, List<Color> colors) {
        var dets = viewer.getImageData().getHierarchy().getDetectionObjects();
        int n = Math.max(2, colors.size());
        int[] r = new int[n];
        int[] g = new int[n];
        int[] b = new int[n];
        for (int i = 0; i < n; i++) {
            Color c = colors.get(Math.min(i, colors.size() - 1));
            r[i] = (int) Math.round(c.getRed() * 255);
            g[i] = (int) Math.round(c.getGreen() * 255);
            b[i] = (int) Math.round(c.getBlue() * 255);
        }
        ColorMaps.ColorMap cm = ColorMaps.createColorMap(measurement, r, g, b);
        MeasurementMapper mm = new MeasurementMapper(cm, measurement, dets);
        mm.setDisplayMinValue(1);
        mm.setDisplayMaxValue(Math.max(2, colors.size()));
        mm.setExcludeOutsideRange(true); // empty (-1) cells keep their phenotype colour
        return mm;
    }

    // ── Spinner helpers ─────────────────────────────────────────────────────────

    private static void commitSpinner(Spinner<Integer> spinner) {
        if (!spinner.isEditable() || spinner.getValueFactory() == null) {
            return;
        }
        try {
            Integer v = spinner.getValueFactory()
                    .getConverter()
                    .fromString(spinner.getEditor().getText());
            if (v != null) {
                spinner.getValueFactory().setValue(v);
            }
        } catch (Exception ignored) {
        }
    }

    private static void commitDoubleSpinner(Spinner<Double> spinner) {
        if (!spinner.isEditable() || spinner.getValueFactory() == null) {
            return;
        }
        try {
            Double v = spinner.getValueFactory()
                    .getConverter()
                    .fromString(spinner.getEditor().getText());
            if (v != null) {
                spinner.getValueFactory().setValue(v);
            }
        } catch (Exception ignored) {
        }
    }

    private void log(String msg) {
        Platform.runLater(() -> logArea.appendText(msg + "\n"));
    }
}
