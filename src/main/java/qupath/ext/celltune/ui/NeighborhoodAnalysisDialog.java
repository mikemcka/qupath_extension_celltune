package qupath.ext.celltune.ui;

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
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.NeighborhoodModel;
import qupath.ext.celltune.model.NeighborhoodModel.ClusterResult;
import qupath.ext.celltune.model.ScatterMath;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.color.ColorMaps;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.MeasurementMapper;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;

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

    private final TextArea logArea = new TextArea();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Configure and click Run.");
    private final Button runBtn = new Button("Run");
    private final Button closeBtn = new Button("Close");
    private final Button toggleBtn = new Button("Color by: Neighborhood (CN)");
    private final Button heatmapBtn = new Button("Show heatmap");

    private final String unit;
    private final ColorMaps.ColorMap cnColorMap =
            ColorMaps.getColorMaps().getOrDefault("Viridis", ColorMaps.getDefaultColorMap());
    private MeasurementMapper cnMapper; // cached toggle state

    // Last run's results, cached so the heatmap can be reopened without rerunning.
    private double[][] lastCnMean;
    private long[] lastCnCounts;
    private List<String> lastTypeNames;
    private String lastTitle;
    private int lastKEff;

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

        // ── Cell-type checklist ──
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
        toggleBtn.setOnAction(e -> toggleCnColoring());
        heatmapBtn.setDisable(true);
        heatmapBtn.setTooltip(new Tooltip("Reopen the CN enrichment heatmap + colour key from the last run."));
        heatmapBtn.setOnAction(e -> showHeatmap());
        HBox buttons = new HBox(10, toggleBtn, heatmapBtn, new javafx.scene.layout.Region(), runBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(buttons.getChildren().get(2), javafx.scene.layout.Priority.ALWAYS);
        buttons.setPadding(new Insets(4, 0, 0, 0));

        VBox root = new VBox(
                10,
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

        Stage s = new Stage();
        s.setTitle("Cellular Neighborhoods");
        s.initOwner(qupath.getStage());
        s.initModality(Modality.NONE);
        s.setScene(new Scene(root, 460, 760));
        return s;
    }

    /** Distinct non-ignored cell-type labels on the current image, in first-seen order. */
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

        log(String.format(
                Locale.US, "Done: %d CNs over %,d cells (%,d with empty window → CN=-1).", kEff, nActive, empty));
        for (int c = 0; c < kEff; c++) {
            log(String.format(Locale.US, "  CN %d: %,d cells", c + 1, cnCounts[c]));
        }

        String title = imageTitle(imageData);
        List<String> typeNames = new ArrayList<>(selectedTypes);
        Platform.runLater(() -> {
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
            lastCnMean = cnMean;
            lastCnCounts = cnCounts;
            lastTypeNames = typeNames;
            lastTitle = title;
            lastKEff = kEff;
            toggleBtn.setDisable(false);
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
        new NeighborhoodHeatmapView(stage, lastTitle, lastTypeNames, lastCnMean, lastCnCounts, cnColors(lastKEff))
                .show();
    }

    /**
     * Colour key for CN ids {@code 1..kEff}, computed from the same colormap and
     * {@code [1, kEff]} display range the viewer toggle uses, so the heatmap
     * swatches match the on-image CN colouring exactly.
     */
    private List<Color> cnColors(int kEff) {
        List<Color> colors = new ArrayList<>(Math.max(0, kEff));
        int max = Math.max(2, kEff);
        for (int c = 1; c <= kEff; c++) {
            Integer argb = cnColorMap.getColor(c, 1, max);
            int v = argb == null ? 0 : argb;
            colors.add(Color.rgb((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF));
        }
        return colors;
    }

    // ── CN color toggle (non-destructive overlay measurement mapper) ────────────

    private void toggleCnColoring() {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || viewer.getImageData() == null) {
            Dialogs.showWarningNotification("CellTune", "No active viewer to recolour.");
            return;
        }
        OverlayOptions opts = viewer.getOverlayOptions();
        if (cnMapper != null && opts.getMeasurementMapper() == cnMapper) {
            opts.resetMeasurementMapper(); // revert to classification colouring
            cnMapper = null;
            toggleBtn.setText("Color by: Neighborhood (CN)");
        } else {
            var dets = viewer.getImageData().getHierarchy().getDetectionObjects();
            cnMapper = new MeasurementMapper(cnColorMap, CN_MEASUREMENT, dets);
            // Map CN ids 1..kEff across the full ramp; empty windows (CN = -1) fall
            // outside the range and keep their phenotype colour. Matches the key.
            cnMapper.setDisplayMinValue(1);
            cnMapper.setDisplayMaxValue(Math.max(2, lastKEff));
            cnMapper.setExcludeOutsideRange(true);
            opts.setMeasurementMapper(cnMapper);
            toggleBtn.setText("Color by: Classification");
        }
        viewer.repaintEntireImage();
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
