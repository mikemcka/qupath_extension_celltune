package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellFeatureExtractor;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.PopulationSet;
import qupath.ext.celltune.util.JvmModuleOpener;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import smile.clustering.KMeans;
import smile.feature.extraction.PCA;
import smile.manifold.UMAP;

/**
 * Interactive 2D embedding scatter plot of cell detections.
 * <p>
 * Each cell is projected into a 2D dimensionality-reduction embedding (PCA or
 * UMAP) of its selected marker-mean measurements and drawn as a dot, coloured by
 * k-means cluster, predicted class, or a single marker's intensity. The user can
 * box- or lasso-select a region of the plot to select those cells in the QuPath
 * viewer; conversely, selecting cells in the viewer outlines them on the plot.
 * <p>
 * Rendering follows the canvas-based idiom of {@link IntensityHeatmapView} /
 * {@link ConfusionMatrixView}. Embedding and clustering run on a background
 * thread (they can take tens of seconds on large images) and marshal back to the
 * FX thread via {@link Platform#runLater}. SMILE supplies PCA, UMAP and k-means.
 */
public class ScatterPlotView {

    private static final Logger logger = LoggerFactory.getLogger(ScatterPlotView.class);

    /**
     * UMAP builds a k-NN graph and optimises a layout — expensive on very large
     * point sets. Above this count we embed a random subsample (other cells are
     * left unplotted, with a notice). PCA and k-means always run on all cells.
     */
    private static final int MAX_UMAP_CELLS = 20_000;

    private static final int DOT_RADIUS = 3;
    private static final double PLOT_MARGIN_LEFT = 56;
    private static final double PLOT_MARGIN_RIGHT = 150;  // room for legend
    private static final double PLOT_MARGIN_TOP = 30;
    private static final double PLOT_MARGIN_BOTTOM = 48;
    private static final Font AXIS_FONT = Font.font("SansSerif", 12);
    private static final Font LEGEND_FONT = Font.font("SansSerif", 11);

    private enum Embedding { PCA, UMAP }

    private enum ColorMode { CLUSTER, CLASS, MARKER }

    // ── Per-cell state (all arrays aligned by index 0..n-1) ────────────────────
    private final PathObject[] cells;
    private final List<String> markerFeatures;
    private final double[][] raw;        // [n][nFeatures] raw marker values
    private final double[][] std;        // [n][nFeatures] z-scored columns
    private final double[] ex;           // embedding x (NaN = not embedded)
    private final double[] ey;           // embedding y
    private int[] cluster;               // k-means label per cell (-1 = none)
    private final boolean[] selected;    // mirrors the viewer selection
    private final IdentityHashMap<PathObject, Integer> indexOf;

    private final QuPathGUI qupath;
    private final String imageName;
    private final PopulationSet predictions; // may be null
    private final PathObjectHierarchy hierarchy;

    // ── UI ─────────────────────────────────────────────────────────────────────
    private final Stage stage;
    private final Canvas canvas;
    private final ComboBox<Embedding> embeddingCombo;
    private final CheckBox fullUmapCheck;
    private final Spinner<Integer> kSpinner;
    private final ComboBox<ColorMode> colorCombo;
    private final ComboBox<String> markerCombo;
    private final TextField annotationField;
    private final ComboBox<String> classField;
    private final MenuButton clusterMarkersBtn;
    private final List<CheckMenuItem> clusterMarkerItems = new ArrayList<>();
    private final ToggleButton boxToggle;
    private final ToggleButton lassoToggle;
    private final Button applyClustersBtn;
    private final ProgressIndicator progress;
    private final Label statusLabel;

    private PathObjectSelectionListener selectionListener;
    private boolean updatingSelection = false; // guard against self-triggered redraws
    private volatile boolean applying = false; // guard against concurrent apply runs
    private String statusNotice = ""; // scope/filter suffix, persisted across redraws

    // ── Cluster-legend hit-testing geometry (updated each drawLegend) ──────────
    private double legendClusterX, legendClusterY, legendClusterLineH;
    private int legendClusterCount = 0;

    // ── Current view geometry (recomputed each redraw) ─────────────────────────
    private double minX, maxX, minY, maxY;
    // Cached marker range for MARKER colour mode (recomputed per redraw).
    private int markerColIdx = -1;
    private double markerLo, markerHi;

    // ── Drag selection state ───────────────────────────────────────────────────
    private WritableImage dragCache;
    private double dragStartX, dragStartY, dragCurX, dragCurY;
    private boolean dragging = false;
    private final List<double[]> lassoPoints = new ArrayList<>();

    /**
     * @param owner          parent stage
     * @param qupath         QuPath GUI (for viewer access + selection)
     * @param imageName      display name of the current image
     * @param markerFeatures ordered marker-mean measurement names to embed/cluster
     * @param cellList       detection objects to plot
     * @param predictions    population set for "colour by predicted class" (nullable)
     */
    public ScatterPlotView(Stage owner, QuPathGUI qupath, String imageName,
                           List<String> markerFeatures, List<PathObject> cellList,
                           PopulationSet predictions) {
        this.qupath = qupath;
        this.imageName = imageName != null ? imageName : "Current Image";
        this.markerFeatures = List.copyOf(markerFeatures);
        this.predictions = predictions;
        this.hierarchy = qupath.getImageData() != null
                ? qupath.getImageData().getHierarchy() : null;

        int n = cellList.size();
        int nFeat = this.markerFeatures.size();
        this.cells = cellList.toArray(new PathObject[0]);
        this.ex = new double[n];
        this.ey = new double[n];
        this.cluster = new int[n];
        this.selected = new boolean[n];
        this.indexOf = new IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            indexOf.put(cells[i], i);
            cluster[i] = -1;
        }

        // Extract the raw feature matrix (no normaliser) and z-score columns.
        var extractor = new CellFeatureExtractor(this.markerFeatures);
        float[] flat = extractor.extractMatrix(cellList);
        this.raw = new double[n][nFeat];
        for (int i = 0; i < n; i++) {
            int off = i * nFeat;
            for (int j = 0; j < nFeat; j++) {
                raw[i][j] = flat[off + j];
            }
        }
        this.std = standardizeColumns(raw);

        // ── Build controls ─────────────────────────────────────────────────────
        stage = new Stage();
        canvas = new Canvas(800, 600);

        embeddingCombo = new ComboBox<>();
        embeddingCombo.getItems().addAll(Embedding.PCA, Embedding.UMAP);
        embeddingCombo.setValue(Embedding.PCA);

        fullUmapCheck = new CheckBox("Full UMAP");
        fullUmapCheck.setTooltip(new javafx.scene.control.Tooltip(
                "Embed ALL cells in UMAP instead of a "
                + String.format("%,d", MAX_UMAP_CELLS)
                + "-cell sample. Much slower and more memory-hungry on large "
                + "images, but plots every cell. Only affects UMAP — k-means "
                + "already clusters all cells regardless."));
        fullUmapCheck.setDisable(embeddingCombo.getValue() != Embedding.UMAP);
        embeddingCombo.valueProperty().addListener((o, a, b) ->
                fullUmapCheck.setDisable(b != Embedding.UMAP));

        kSpinner = new Spinner<>(2, 50, 8);
        kSpinner.setEditable(true);
        kSpinner.setPrefWidth(70);

        annotationField = new TextField();
        annotationField.setPromptText("name (blank = all cells)");
        annotationField.setPrefWidth(150);
        annotationField.setTooltip(new javafx.scene.control.Tooltip(
                "Only cluster cells whose centroid falls inside an annotation "
                + "whose name (or classification) contains this text. "
                + "Leave blank to use all cells."));
        annotationField.setOnAction(e -> recompute()); // Enter re-runs

        classField = new ComboBox<>();
        classField.setEditable(true);
        classField.setPromptText("class (blank = all)");
        classField.setPrefWidth(150);
        classField.getItems().add("");
        for (PathClass pc : qupath.getAvailablePathClasses()) {
            if (pc != null && pc.getName() != null) {
                classField.getItems().add(pc.toString());
            }
        }
        classField.setValue("");
        classField.setTooltip(new javafx.scene.control.Tooltip(
                "Only cluster cells whose current QuPath classification contains "
                + "this text — e.g. assign Immune/Tumour/Other via Apply Clusters, "
                + "then drill in with \"Immune\". Combines with the annotation "
                + "filter. Leave blank for all classes."));
        classField.setOnAction(e -> recompute());

        clusterMarkersBtn = new MenuButton("Cluster markers (all)");
        clusterMarkersBtn.setTooltip(new javafx.scene.control.Tooltip(
                "Markers used for k-means and the embedding. Uncheck markers to "
                + "sub-cluster on a focused panel (e.g. immune markers only). "
                + "Values are re-standardized over the active cells each run."));
        for (String marker : this.markerFeatures) {
            CheckMenuItem item = new CheckMenuItem(marker);
            item.setSelected(true);
            item.selectedProperty().addListener((o, a, b) -> updateClusterMarkersLabel());
            clusterMarkerItems.add(item);
            clusterMarkersBtn.getItems().add(item);
        }

        Button recomputeBtn = new Button("Recompute");
        recomputeBtn.setOnAction(e -> recompute());

        Button projectBtn = new Button("Project Clustering…");
        projectBtn.setTooltip(new javafx.scene.control.Tooltip(
                "Cluster the whole project (or selected images) consistently, "
                + "using the currently-checked cluster markers."));
        projectBtn.setOnAction(e -> openProjectClustering());

        progress = new ProgressIndicator();
        progress.setPrefSize(20, 20);
        progress.setVisible(false);

        markerCombo = new ComboBox<>();
        markerCombo.getItems().addAll(this.markerFeatures);
        if (!this.markerFeatures.isEmpty()) {
            markerCombo.setValue(this.markerFeatures.get(0));
        }
        markerCombo.setDisable(true);

        colorCombo = new ComboBox<>();
        colorCombo.getItems().addAll(ColorMode.CLUSTER, ColorMode.CLASS, ColorMode.MARKER);
        colorCombo.setValue(ColorMode.CLUSTER);
        colorCombo.setOnAction(e -> {
            markerCombo.setDisable(colorCombo.getValue() != ColorMode.MARKER);
            redraw();
        });

        markerCombo.setOnAction(e -> {
            if (colorCombo.getValue() == ColorMode.MARKER) {
                redraw();
            }
        });

        ToggleGroup selGroup = new ToggleGroup();
        boxToggle = new ToggleButton("Box");
        lassoToggle = new ToggleButton("Lasso");
        boxToggle.setToggleGroup(selGroup);
        lassoToggle.setToggleGroup(selGroup);
        boxToggle.setSelected(true);
        // Keep one always selected.
        selGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            if (sel == null && old != null) {
                old.setSelected(true);
            }
        });

        applyClustersBtn = new Button("Apply Clusters…");
        applyClustersBtn.setOnAction(e -> applyClustersToClasses());

        Button exportBtn = new Button("Export PNG…");
        exportBtn.setOnAction(e -> exportAsPng());

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.close());

        HBox row1 = new HBox(8,
                new Label("Embedding:"), embeddingCombo, fullUmapCheck,
                new Label("Clusters (k):"), kSpinner,
                recomputeBtn, projectBtn, progress);
        row1.setAlignment(Pos.CENTER_LEFT);

        HBox rowFilter = new HBox(8,
                new Label("Annotation:"), annotationField,
                new Label("Within class:"), classField,
                clusterMarkersBtn);
        rowFilter.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row2 = new HBox(8,
                new Label("Colour by:"), colorCombo,
                new Label("Marker:"), markerCombo,
                spacer,
                new Label("Select:"), boxToggle, lassoToggle,
                applyClustersBtn, exportBtn, closeBtn);
        row2.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(6, row1, rowFilter, row2);
        top.setPadding(new Insets(8));

        statusLabel = new Label("");
        statusLabel.setPadding(new Insets(6, 8, 6, 8));

        // Canvas fills the centre and resizes with the window.
        Pane canvasHolder = new Pane(canvas);
        canvas.widthProperty().bind(canvasHolder.widthProperty());
        canvas.heightProperty().bind(canvasHolder.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> redraw());
        canvas.heightProperty().addListener((o, a, b) -> redraw());

        canvas.setOnMousePressed(this::onMousePressed);
        canvas.setOnMouseDragged(this::onMouseDragged);
        canvas.setOnMouseReleased(this::onMouseReleased);
        canvas.setOnMouseMoved(this::onMouseMoved);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(canvasHolder);
        root.setBottom(statusLabel);

        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("CellTune — Cell Scatter Plot");
        stage.setScene(new Scene(root, 1000, 720));
        stage.setResizable(true);
        stage.setOnHidden(e -> removeSelectionListener());

        installSelectionListener();

        // Initial embedding + clustering.
        recompute();
    }

    /** Show the scatter plot window. */
    public void show() {
        stage.show();
        stage.toFront();
    }

    // ── Standardisation ─────────────────────────────────────────────────────────

    private static double[][] standardizeColumns(double[][] data) {
        int n = data.length;
        int p = n == 0 ? 0 : data[0].length;
        double[][] out = new double[n][p];
        for (int j = 0; j < p; j++) {
            double mean = 0;
            for (double[] row : data) {
                mean += row[j];
            }
            mean /= Math.max(1, n);
            double var = 0;
            for (double[] row : data) {
                double d = row[j] - mean;
                var += d * d;
            }
            double sd = Math.sqrt(var / Math.max(1, n));
            double inv = sd < 1e-9 ? 0.0 : 1.0 / sd;
            for (int i = 0; i < n; i++) {
                out[i][j] = (data[i][j] - mean) * inv;
            }
        }
        return out;
    }

    // ── Embedding + clustering (background thread) ──────────────────────────────

    private void recompute() {
        final Embedding embedding = embeddingCombo.getValue();
        final int k = kSpinner.getValue();
        final int n = cells.length;
        final String keyword = annotationField.getText();
        final String classKeyword = classField.getValue();
        final int[] selCols = selectedMarkerColumns();
        final int umapCap = fullUmapCheck.isSelected()
                ? Integer.MAX_VALUE : MAX_UMAP_CELLS;
        if (n == 0) {
            statusLabel.setText("No cells to plot.");
            return;
        }
        if (selCols.length < 2) {
            statusLabel.setText(
                    "Select at least 2 cluster markers (see “Cluster markers”).");
            return;
        }

        progress.setVisible(true);
        statusLabel.setText("Computing " + embedding + " embedding…");
        setControlsDisabled(true);

        new Thread(() -> {
            String notice = "";
            try {
                // Smile's PCA/UMAP load native libs via JavaCPP, which needs
                // java.base/java.lang opened. The extension opens it at startup;
                // this is a defensive no-op if already done.
                JvmModuleOpener.ensureJavaLangOpen();

                // Restrict clustering/embedding to cells inside matching
                // annotation(s) AND of the matching class (all cells when blank).
                int[] activeIdx = computeActiveIndices(keyword, classKeyword);
                final int m = activeIdx.length;

                // Full-length outputs; non-active cells stay unclustered/unplotted.
                int[] newCluster = new int[n];
                java.util.Arrays.fill(newCluster, -1);
                double[] nx = new double[n];
                double[] ny = new double[n];
                java.util.Arrays.fill(nx, Double.NaN);
                java.util.Arrays.fill(ny, Double.NaN);

                boolean annoFilter = keyword != null && !keyword.isBlank();
                boolean classFilter = classKeyword != null && !classKeyword.isBlank();
                boolean filtered = annoFilter || classFilter;
                String scopeDesc = describeScope(keyword, classKeyword);
                if (m == 0) {
                    final String fNotice = filtered
                            ? " (no cells matching " + scopeDesc + ")"
                            : "";
                    Platform.runLater(() -> {
                        System.arraycopy(nx, 0, ex, 0, n);
                        System.arraycopy(ny, 0, ey, 0, n);
                        cluster = newCluster;
                        progress.setVisible(false);
                        setControlsDisabled(false);
                        redraw();
                        appendStatusNotice(fNotice);
                    });
                    return;
                }

                // Active feature matrix: the selected marker columns of the active
                // cells, re-standardized over that subset so sub-clustering scales
                // to the subpopulation (not the whole image).
                double[][] activeRaw = new double[m][selCols.length];
                for (int j = 0; j < m; j++) {
                    double[] src = raw[activeIdx[j]];
                    for (int c = 0; c < selCols.length; c++) {
                        activeRaw[j][c] = src[selCols[c]];
                    }
                }
                double[][] active = standardizeColumns(activeRaw);

                // ── k-means on the active subset ─────────────────────────────────
                int kEff = Math.min(k, m);
                int[] subCluster = new int[m];
                if (kEff >= 2) {
                    KMeans km = KMeans.fit(active, kEff);
                    System.arraycopy(km.y, 0, subCluster, 0, m);
                }
                for (int j = 0; j < m; j++) {
                    newCluster[activeIdx[j]] = subCluster[j];
                }

                // ── Embedding on the active subset ───────────────────────────────
                double[] subX = new double[m];
                double[] subY = new double[m];
                java.util.Arrays.fill(subX, Double.NaN);
                java.util.Arrays.fill(subY, Double.NaN);

                if (embedding == Embedding.PCA) {
                    fillPca(active, m, subX, subY);
                } else {
                    try {
                        notice = fillUmap(active, m, subX, subY, umapCap);
                    } catch (LinkageError err) {
                        // UMAP's spectral layout loads the native ARPACK library
                        // through JavaCPP, which uses reflection into java.base.
                        // On JVMs started without
                        // --add-opens=java.base/java.lang=ALL-UNNAMED that load
                        // fails with an Error (ExceptionInInitializerError, then
                        // NoClassDefFoundError on retries, or UnsatisfiedLinkError)
                        // — all LinkageError, not Exception. Fall back to PCA so the
                        // plot still renders on any system.
                        logger.warn(
                                "UMAP unavailable ({}); falling back to PCA. Launch "
                                + "QuPath with "
                                + "--add-opens=java.base/java.lang=ALL-UNNAMED to "
                                + "enable UMAP.",
                                err.toString());
                        logger.debug("UMAP native load failure detail", err);
                        java.util.Arrays.fill(subX, Double.NaN);
                        java.util.Arrays.fill(subY, Double.NaN);
                        fillPca(active, m, subX, subY);
                        notice = " (UMAP unavailable — showing PCA)";
                    }
                }
                for (int j = 0; j < m; j++) {
                    nx[activeIdx[j]] = subX[j];
                    ny[activeIdx[j]] = subY[j];
                }

                if (filtered) {
                    notice = notice + String.format(
                            " (%,d cells in %s)", m, scopeDesc);
                }
                if (selCols.length < markerFeatures.size()) {
                    notice = notice + String.format(
                            " · %d/%d markers", selCols.length, markerFeatures.size());
                }

                final String fNotice = notice;
                Platform.runLater(() -> {
                    System.arraycopy(nx, 0, ex, 0, n);
                    System.arraycopy(ny, 0, ey, 0, n);
                    cluster = newCluster;
                    progress.setVisible(false);
                    setControlsDisabled(false);
                    redraw();
                    appendStatusNotice(fNotice);
                });
            } catch (Throwable ex) {
                // Throwable, not Exception: Smile's native loaders fail with
                // Errors (e.g. ExceptionInInitializerError) when java.lang is not
                // open. Catch them here so they never reach the uncaught-exception
                // dialog, and give the user an actionable message.
                logger.error("Failed to compute scatter embedding", ex);
                boolean nativeIssue = ex instanceof LinkageError;
                final String msg = nativeIssue
                        ? "Embedding failed: native math libraries unavailable. "
                                + "Launch QuPath with "
                                + "--add-opens=java.base/java.lang=ALL-UNNAMED."
                        : "Embedding failed: " + ex.getMessage();
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    setControlsDisabled(false);
                    statusLabel.setText(msg);
                });
            }
        }, "CellTune-ScatterEmbedding").start();
    }

    /**
     * Indices of cells eligible for clustering/embedding, intersecting two
     * optional filters: inside an annotation whose label contains
     * {@code annoKeyword}, and whose current classification contains
     * {@code classKeyword} (both case-insensitive; blank = no restriction). The
     * annotation test mirrors Review-mode's membership check.
     */
    private int[] computeActiveIndices(String annoKeyword, String classKeyword) {
        int n = cells.length;
        boolean annoFilter = annoKeyword != null && !annoKeyword.isBlank();
        boolean classFilter = classKeyword != null && !classKeyword.isBlank();
        if (!annoFilter && !classFilter) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) {
                all[i] = i;
            }
            return all;
        }

        // Matching annotation ROIs (only needed for the annotation filter).
        List<ROI> rois = new ArrayList<>();
        if (annoFilter && hierarchy != null) {
            String kw = annoKeyword.trim().toLowerCase();
            for (PathObject anno : hierarchy.getAnnotationObjects()) {
                ROI roi = anno.getROI();
                if (roi == null) {
                    continue;
                }
                String label = annotationLabel(anno);
                if (label != null && label.toLowerCase().contains(kw)) {
                    rois.add(roi);
                }
            }
            if (rois.isEmpty()) {
                return new int[0];
            }
        }
        String classKw = classFilter ? classKeyword.trim().toLowerCase() : null;

        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (classFilter) {
                PathClass pc = cells[i].getPathClass();
                if (pc == null || !pc.toString().toLowerCase().contains(classKw)) {
                    continue;
                }
            }
            if (annoFilter) {
                ROI cr = cells[i].getROI();
                if (cr == null) {
                    continue;
                }
                double cx = cr.getCentroidX();
                double cy = cr.getCentroidY();
                boolean inside = false;
                for (ROI r : rois) {
                    if (r.contains(cx, cy)) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) {
                    continue;
                }
            }
            idx.add(i);
        }
        int[] out = new int[idx.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = idx.get(i);
        }
        return out;
    }

    /** Human-readable description of the active-subset filters for the status bar. */
    private static String describeScope(String annoKeyword, String classKeyword) {
        boolean anno = annoKeyword != null && !annoKeyword.isBlank();
        boolean cls = classKeyword != null && !classKeyword.isBlank();
        if (anno && cls) {
            return String.format("class “%s” inside “%s”",
                    classKeyword.trim(), annoKeyword.trim());
        }
        if (cls) {
            return "class “" + classKeyword.trim() + "”";
        }
        if (anno) {
            return "annotation “" + annoKeyword.trim() + "”";
        }
        return "all cells";
    }

    /** Annotation display label: explicit name, else PathClass name, else null. */
    private static String annotationLabel(PathObject anno) {
        String name = anno.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        PathClass pc = anno.getPathClass();
        if (pc != null) {
            String pcName = pc.getName();
            if (pcName != null && !pcName.isBlank()) {
                return pcName;
            }
        }
        return null;
    }

    /**
     * Opens the project-wide clustering dialog, seeded with the currently-checked
     * cluster markers (or all markers if fewer than 2 are checked).
     */
    private void openProjectClustering() {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("CellTune",
                    "Open a project to cluster across multiple images.");
            return;
        }
        int[] cols = selectedMarkerColumns();
        List<String> markers = new ArrayList<>();
        if (cols.length >= 2) {
            for (int c : cols) {
                markers.add(markerFeatures.get(c));
            }
        } else {
            markers.addAll(markerFeatures);
        }
        List<String> allImageNames = new ArrayList<>();
        for (var entry : project.getImageList()) {
            allImageNames.add(entry.getImageName());
        }
        new ProjectClusteringDialog(qupath, markers, allImageNames, imageName).show();
    }

    // ── Apply clusters → QuPath classifications ─────────────────────────────────

    private static final String SKIP_CLASS = "— skip —";

    /**
     * Opens a dialog mapping each non-empty k-means cluster to an existing (or
     * newly typed) QuPath class, then writes those classes onto the cells'
     * {@link PathClass} (the displayed classification — not the CellTune
     * ground-truth label store). Clusters left as "skip" are untouched.
     */
    private void applyClustersToClasses() {
        if (applying) {
            return;
        }
        final int n = cells.length;
        final int k = kSpinner.getValue();

        // Tally cells per cluster; only non-empty clusters get a row.
        int[] counts = new int[k];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int c = cluster[i];
            if (c >= 0 && c < k) {
                counts[c]++;
                any = true;
            }
        }
        if (!any) {
            Dialogs.showWarningNotification(
                    "CellTune", "No clusters available yet — run Recompute first.");
            return;
        }
        if (hierarchy == null) {
            Dialogs.showWarningNotification(
                    "CellTune", "No image is open to classify.");
            return;
        }

        // Existing project class names to seed the (editable) dropdowns.
        List<String> classNames = new ArrayList<>();
        for (PathClass pc : qupath.getAvailablePathClasses()) {
            if (pc != null && pc.getName() != null) {
                classNames.add(pc.toString());
            }
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(4, 4, 4, 4));
        grid.add(boldLabel("Cluster"), 0, 0);
        grid.add(boldLabel("Cells"), 2, 0);
        grid.add(boldLabel("Assign to class"), 3, 0);

        Map<Integer, ComboBox<String>> selectors = new LinkedHashMap<>();
        int row = 1;
        for (int c = 0; c < k; c++) {
            if (counts[c] == 0) {
                continue;
            }
            Rectangle swatch = new Rectangle(14, 14, clusterColor(c));
            swatch.setStroke(Color.gray(0.4));

            ComboBox<String> combo = new ComboBox<>();
            combo.setEditable(true);
            combo.getItems().add(SKIP_CLASS);
            combo.getItems().addAll(classNames);
            combo.setValue(SKIP_CLASS);
            combo.setPrefWidth(190);
            selectors.put(c, combo);

            HBox label = new HBox(6, swatch, new Label("Cluster " + c));
            label.setAlignment(Pos.CENTER_LEFT);
            grid.add(label, 0, row);
            grid.add(new Label(String.format("%,d", counts[c])), 2, row);
            grid.add(combo, 3, row);
            row++;
        }

        Label warn = new Label(
                "Apply overwrites the QuPath classification of every cell in the "
                + "mapped clusters. Clusters left as “" + SKIP_CLASS
                + "” are left unchanged. This does not affect CellTune "
                + "training labels.");
        warn.setWrapText(true);
        warn.setMaxWidth(440);

        VBox content = new VBox(10, grid, new Separator(), warn);
        content.setPadding(new Insets(8));

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(stage);
        dlg.setTitle("Assign Clusters to Classes");
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.APPLY) {
            return;
        }

        // Resolve the chosen mappings (skip blanks / "skip").
        Map<Integer, PathClass> mapping = new LinkedHashMap<>();
        for (var e : selectors.entrySet()) {
            String v = e.getValue().getValue();
            if (v == null) {
                continue;
            }
            v = v.trim();
            if (v.isEmpty() || v.equals(SKIP_CLASS)) {
                continue;
            }
            mapping.put(e.getKey(), PathClass.fromString(v));
        }
        if (mapping.isEmpty()) {
            statusLabel.setText("No clusters mapped — nothing changed.");
            return;
        }

        int affected = 0;
        for (int i = 0; i < n; i++) {
            if (mapping.containsKey(cluster[i])) {
                affected++;
            }
        }

        boolean confirmed = Dialogs.showConfirmDialog(
                "Replace classifications",
                String.format(
                        "Set the QuPath classification of %,d cell(s) across "
                        + "%d cluster(s)? This replaces any existing class on "
                        + "those cells.",
                        affected, mapping.size()));
        if (!confirmed) {
            return;
        }

        applyClusterMapping(mapping);
    }

    /**
     * Writes the cluster→class mapping onto the cells on a background thread,
     * applying {@code setPathClass} in chunks marshalled to the FX thread so the
     * UI stays responsive (and shows progress) for large cell counts.
     */
    private void applyClusterMapping(Map<Integer, PathClass> mapping) {
        // Register any newly typed classes so they appear in the project list
        // (we are on the FX thread here, before the worker starts).
        var available = qupath.getAvailablePathClasses();
        for (PathClass pc : mapping.values()) {
            if (pc != null && !available.contains(pc)) {
                available.add(pc);
            }
        }

        applying = true;
        setControlsDisabled(true);
        progress.setProgress(0);
        progress.setVisible(true);
        statusLabel.setText("Applying cluster classifications…");

        new Thread(() -> {
            final int n = cells.length;
            final int chunk = 5000;
            final List<PathObject> changed = new ArrayList<>();
            try {
                for (int start = 0; start < n; start += chunk) {
                    final int end = Math.min(n, start + chunk);
                    final List<PathObject> objs = new ArrayList<>();
                    final List<PathClass> classes = new ArrayList<>();
                    for (int i = start; i < end; i++) {
                        PathClass pc = mapping.get(cluster[i]);
                        if (pc != null) {
                            objs.add(cells[i]);
                            classes.add(pc);
                        }
                    }

                    final java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(1);
                    Platform.runLater(() -> {
                        try {
                            for (int j = 0; j < objs.size(); j++) {
                                objs.get(j).setPathClass(classes.get(j));
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                    latch.await();

                    changed.addAll(objs);
                    final double frac = end / (double) n;
                    final int done = changed.size();
                    Platform.runLater(() -> {
                        progress.setProgress(frac);
                        statusLabel.setText(
                                String.format("Applying classifications… %,d cells", done));
                    });
                }

                Platform.runLater(() -> {
                    hierarchy.fireObjectClassificationsChangedEvent(this, changed);
                    QuPathViewer viewer = qupath.getViewer();
                    if (viewer != null) {
                        viewer.repaint();
                    }
                    redraw(); // refresh CLASS colouring on the plot
                    statusLabel.setText(String.format(
                            "Applied %d cluster→class mapping(s) to %,d cell(s).",
                            mapping.size(), changed.size()));
                });
                logger.info("Applied cluster→class mapping to {} cells ({} clusters)",
                        changed.size(), mapping.size());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Platform.runLater(() ->
                        statusLabel.setText("Apply cancelled."));
            } finally {
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                    setControlsDisabled(false);
                    applying = false;
                });
            }
        }, "CellTune-ApplyClusters").start();
    }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private void setControlsDisabled(boolean disabled) {
        embeddingCombo.setDisable(disabled);
        fullUmapCheck.setDisable(
                disabled || embeddingCombo.getValue() != Embedding.UMAP);
        kSpinner.setDisable(disabled);
        annotationField.setDisable(disabled);
        classField.setDisable(disabled);
        clusterMarkersBtn.setDisable(disabled);
        applyClustersBtn.setDisable(disabled);
    }

    private void updateClusterMarkersLabel() {
        int total = clusterMarkerItems.size();
        int sel = 0;
        for (CheckMenuItem it : clusterMarkerItems) {
            if (it.isSelected()) {
                sel++;
            }
        }
        clusterMarkersBtn.setText(sel == total
                ? "Cluster markers (all)"
                : String.format("Cluster markers (%d/%d)", sel, total));
    }

    /** Indices into {@link #markerFeatures} of the checked cluster markers. */
    private int[] selectedMarkerColumns() {
        List<Integer> cols = new ArrayList<>();
        for (int j = 0; j < clusterMarkerItems.size(); j++) {
            if (clusterMarkerItems.get(j).isSelected()) {
                cols.add(j);
            }
        }
        int[] out = new int[cols.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = cols.get(i);
        }
        return out;
    }

    /** Projects all rows onto their first two principal components. */
    private static void fillPca(double[][] std, int n, double[] nx, double[] ny) {
        double[][] proj = PCA.fit(std).getProjection(2).apply(std);
        for (int i = 0; i < n; i++) {
            nx[i] = proj[i][0];
            ny[i] = proj[i][1];
        }
    }

    /**
     * Embeds the rows with UMAP, subsampling to {@code maxCells} when the input is
     * larger (pass {@link Integer#MAX_VALUE} to embed everything). Returns a
     * status notice (currently empty).
     *
     * @throws LinkageError if the native ARPACK library cannot be loaded (caller
     *     falls back to PCA)
     */
    private static String fillUmap(double[][] std, int n, double[] nx, double[] ny,
                                   int maxCells) {
        // UMAP: subsample if larger than the cap; embed a connected subset.
        int[] sub = (n > maxCells)
                ? randomSubsample(n, maxCells)
                : identity(n);
        // The status bar's "clustered · plotted" counts convey the subsample, so
        // no extra notice is emitted here.
        String notice = "";
        double[][] subMatrix = new double[sub.length][];
        for (int s = 0; s < sub.length; s++) {
            subMatrix[s] = std[sub[s]];
        }
        int neighbors = Math.min(15, subMatrix.length - 1);
        if (neighbors < 2) {
            throw new IllegalStateException(
                    "Too few cells for UMAP (need at least 3).");
        }
        UMAP umap = UMAP.of(subMatrix, neighbors);
        // coordinates[j] corresponds to subMatrix row umap.index[j].
        for (int j = 0; j < umap.coordinates.length; j++) {
            int orig = sub[umap.index[j]];
            nx[orig] = umap.coordinates[j][0];
            ny[orig] = umap.coordinates[j][1];
        }
        return notice;
    }

    private static int[] identity(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    /** Deterministic (seeded) reservoir-free subsample of {@code count} indices. */
    private static int[] randomSubsample(int n, int count) {
        int[] all = identity(n);
        java.util.Random rng = new java.util.Random(42);
        // Partial Fisher-Yates: first `count` slots become the sample.
        for (int i = 0; i < count; i++) {
            int j = i + rng.nextInt(n - i);
            int tmp = all[i];
            all[i] = all[j];
            all[j] = tmp;
        }
        int[] out = new int[count];
        System.arraycopy(all, 0, out, 0, count);
        java.util.Arrays.sort(out);
        return out;
    }

    // ── Drawing ──────────────────────────────────────────────────────────────────

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, w, h);

        // Compute embedding bounds over plotted (non-NaN) cells.
        boolean any = computeBounds();
        if (!any) {
            gc.setFill(Color.gray(0.3));
            gc.setFont(AXIS_FONT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("No embedding to display — press Recompute.", w / 2, h / 2);
            return;
        }

        ColorMode mode = colorCombo.getValue();
        if (mode == ColorMode.MARKER) {
            computeMarkerRange();
        }

        // Axis frame.
        double left = PLOT_MARGIN_LEFT;
        double right = w - PLOT_MARGIN_RIGHT;
        double top = PLOT_MARGIN_TOP;
        double bottom = h - PLOT_MARGIN_BOTTOM;
        gc.setStroke(Color.gray(0.75));
        gc.setLineWidth(1);
        gc.strokeRect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));

        String axisName = embeddingCombo.getValue().name();
        gc.setFill(Color.gray(0.25));
        gc.setFont(AXIS_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(axisName + "-1", (left + right) / 2, h - 14);
        gc.save();
        gc.translate(16, (top + bottom) / 2);
        gc.rotate(-90);
        gc.fillText(axisName + "-2", 0, 0);
        gc.restore();

        // Dots.
        for (int i = 0; i < cells.length; i++) {
            if (Double.isNaN(ex[i])) {
                continue;
            }
            double px = sx(ex[i], left, right);
            double py = sy(ey[i], top, bottom);
            gc.setFill(colorFor(i, mode));
            gc.fillOval(px - DOT_RADIUS, py - DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2);
        }

        // Selection outlines (drawn on top).
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2.2);
        for (int i = 0; i < cells.length; i++) {
            if (!selected[i] || Double.isNaN(ex[i])) {
                continue;
            }
            double px = sx(ex[i], left, right);
            double py = sy(ey[i], top, bottom);
            gc.strokeOval(px - DOT_RADIUS - 1, py - DOT_RADIUS - 1,
                    (DOT_RADIUS + 1) * 2, (DOT_RADIUS + 1) * 2);
        }

        drawLegend(gc, right + 12, top, mode);
        updateStatus();
    }

    private boolean computeBounds() {
        minX = Double.POSITIVE_INFINITY;
        maxX = Double.NEGATIVE_INFINITY;
        minY = Double.POSITIVE_INFINITY;
        maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (int i = 0; i < cells.length; i++) {
            if (Double.isNaN(ex[i])) {
                continue;
            }
            any = true;
            minX = Math.min(minX, ex[i]);
            maxX = Math.max(maxX, ex[i]);
            minY = Math.min(minY, ey[i]);
            maxY = Math.max(maxY, ey[i]);
        }
        if (any) {
            // Avoid zero-width ranges.
            if (maxX - minX < 1e-9) {
                maxX = minX + 1;
            }
            if (maxY - minY < 1e-9) {
                maxY = minY + 1;
            }
        }
        return any;
    }

    private double sx(double dx, double left, double right) {
        return left + (dx - minX) / (maxX - minX) * (right - left);
    }

    private double sy(double dy, double top, double bottom) {
        // Invert: larger y plots higher on screen.
        return bottom - (dy - minY) / (maxY - minY) * (bottom - top);
    }

    private Color colorFor(int i, ColorMode mode) {
        switch (mode) {
            case CLASS:
                return classColor(i);
            case MARKER:
                return markerColor(i);
            case CLUSTER:
            default:
                return clusterColor(cluster[i]);
        }
    }

    private Color clusterColor(int c) {
        if (c < 0) {
            return Color.gray(0.6);
        }
        int k = Math.max(1, kSpinner.getValue());
        return Color.hsb(360.0 * (c % k) / k, 0.72, 0.88);
    }

    private Color classColor(int i) {
        String label = null;
        if (predictions != null) {
            CellPrediction pred = predictions.get(cells[i].getID().toString());
            if (pred != null) {
                label = pred.avgLabel();
            }
        }
        PathClass pc = null;
        if (label != null) {
            pc = PathClass.fromString(label);
        } else if (cells[i].getPathClass() != null) {
            pc = cells[i].getPathClass();
        }
        if (pc == null) {
            return Color.gray(0.6);
        }
        return toFxColor(pc.getColor());
    }

    /** Precompute the selected marker's min/max over plotted cells (once per redraw). */
    private void computeMarkerRange() {
        markerColIdx = markerFeatures.indexOf(markerCombo.getValue());
        markerLo = Double.POSITIVE_INFINITY;
        markerHi = Double.NEGATIVE_INFINITY;
        if (markerColIdx < 0) {
            return;
        }
        for (int r = 0; r < cells.length; r++) {
            if (Double.isNaN(ex[r])) {
                continue;
            }
            markerLo = Math.min(markerLo, raw[r][markerColIdx]);
            markerHi = Math.max(markerHi, raw[r][markerColIdx]);
        }
    }

    private Color markerColor(int i) {
        if (markerColIdx < 0) {
            return Color.gray(0.6);
        }
        double t = (markerHi - markerLo < 1e-9)
                ? 0.5 : (raw[i][markerColIdx] - markerLo) / (markerHi - markerLo);
        return gradient(t);
    }

    /** Blue (low) → red (high) gradient. */
    private static Color gradient(double t) {
        t = Math.max(0, Math.min(1, t));
        return Color.color(t, 0.15 + 0.2 * (1 - Math.abs(0.5 - t) * 2), 1 - t);
    }

    private static Color toFxColor(Integer packedRgb) {
        if (packedRgb == null) {
            return Color.gray(0.6);
        }
        int c = packedRgb;
        return Color.rgb((c >> 16) & 0xff, (c >> 8) & 0xff, c & 0xff);
    }

    private void drawLegend(GraphicsContext gc, double x, double y, ColorMode mode) {
        gc.setFont(LEGEND_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        double sw = 12;
        double lineH = 18;
        legendClusterCount = 0; // cleared unless CLUSTER legend is drawn below
        if (mode == ColorMode.CLUSTER) {
            int k = kSpinner.getValue();
            // Shrink the row height (and swatch) so many clusters still fit the
            // canvas and every row stays clickable for click-to-select.
            double avail = canvas.getHeight() - y - PLOT_MARGIN_BOTTOM;
            double clusterLineH = Math.min(lineH, Math.max(10, avail / Math.max(1, k)));
            double clusterSw = Math.min(sw, clusterLineH - 2);
            // Record geometry so legend rows can be hit-tested for click-to-select.
            legendClusterX = x;
            legendClusterY = y;
            legendClusterLineH = clusterLineH;
            legendClusterCount = k;
            gc.setFill(Color.BLACK);
            gc.fillText("Clusters", x, y - 6);
            for (int c = 0; c < k; c++) {
                double yy = y + c * clusterLineH;
                gc.setFill(clusterColor(c));
                gc.fillRect(x, yy, clusterSw, clusterSw);
                gc.setFill(Color.gray(0.2));
                gc.fillText("Cluster " + c, x + clusterSw + 6, yy + clusterSw - 1);
            }
        } else if (mode == ColorMode.CLASS) {
            // Distinct classes present among plotted cells (capped).
            Set<String> classes = new LinkedHashSet<>();
            for (int i = 0; i < cells.length && classes.size() < 24; i++) {
                if (Double.isNaN(ex[i])) {
                    continue;
                }
                classes.add(classLabel(i));
            }
            gc.setFill(Color.BLACK);
            gc.fillText("Classes", x, y - 6);
            int row = 0;
            for (String cls : classes) {
                double yy = y + row * lineH;
                gc.setFill(classColorForLabel(cls));
                gc.fillRect(x, yy, sw, sw);
                gc.setFill(Color.gray(0.2));
                gc.fillText(cls, x + sw + 6, yy + sw - 2);
                row++;
            }
        } else {
            // Marker gradient colourbar.
            gc.setFill(Color.BLACK);
            gc.fillText(markerCombo.getValue() == null ? "Marker" : markerCombo.getValue(),
                    x, y - 6);
            int steps = 80;
            double barH = 140;
            double stepH = barH / steps;
            for (int s = 0; s < steps; s++) {
                double t = 1 - s / (double) (steps - 1);
                gc.setFill(gradient(t));
                gc.fillRect(x, y + s * stepH, sw, stepH + 1);
            }
            gc.setFill(Color.gray(0.2));
            gc.fillText("high", x + sw + 6, y + 8);
            gc.fillText("low", x + sw + 6, y + barH);
        }
    }

    private String classLabel(int i) {
        if (predictions != null) {
            CellPrediction pred = predictions.get(cells[i].getID().toString());
            if (pred != null) {
                return pred.avgLabel();
            }
        }
        return cells[i].getPathClass() != null
                ? cells[i].getPathClass().getName() : "unlabelled";
    }

    private Color classColorForLabel(String label) {
        if ("unlabelled".equals(label)) {
            return Color.gray(0.6);
        }
        return toFxColor(PathClass.fromString(label).getColor());
    }

    // ── Mouse selection ──────────────────────────────────────────────────────────

    private void onMouseMoved(MouseEvent e) {
        // Hint that cluster legend entries are clickable.
        boolean overLegend = clusterAtPoint(e.getX(), e.getY()) >= 0;
        canvas.setCursor(overLegend ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
    }

    private void onMousePressed(MouseEvent e) {
        // Clicking a cluster in the legend selects that cluster's cells in the
        // viewer instead of starting a drag-selection gesture.
        int legendCluster = clusterAtPoint(e.getX(), e.getY());
        if (legendCluster >= 0) {
            dragging = false;
            selectCluster(legendCluster);
            return;
        }
        dragging = true;
        dragStartX = e.getX();
        dragStartY = e.getY();
        dragCurX = e.getX();
        dragCurY = e.getY();
        lassoPoints.clear();
        lassoPoints.add(new double[]{e.getX(), e.getY()});
        // Cache the rendered scene so drag frames are cheap.
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.WHITE);
        dragCache = canvas.snapshot(params, null);
    }

    private void onMouseDragged(MouseEvent e) {
        if (!dragging) {
            return;
        }
        dragCurX = e.getX();
        dragCurY = e.getY();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        if (dragCache != null) {
            gc.drawImage(dragCache, 0, 0);
        }
        gc.setStroke(Color.web("#1565c0"));
        gc.setLineWidth(1.5);
        gc.setFill(Color.web("#1565c0", 0.12));
        if (lassoToggle.isSelected()) {
            lassoPoints.add(new double[]{e.getX(), e.getY()});
            gc.beginPath();
            gc.moveTo(lassoPoints.get(0)[0], lassoPoints.get(0)[1]);
            for (int p = 1; p < lassoPoints.size(); p++) {
                gc.lineTo(lassoPoints.get(p)[0], lassoPoints.get(p)[1]);
            }
            gc.stroke();
        } else {
            double x = Math.min(dragStartX, dragCurX);
            double y = Math.min(dragStartY, dragCurY);
            double rw = Math.abs(dragCurX - dragStartX);
            double rh = Math.abs(dragCurY - dragStartY);
            gc.fillRect(x, y, rw, rh);
            gc.strokeRect(x, y, rw, rh);
        }
    }

    private void onMouseReleased(MouseEvent e) {
        if (!dragging) {
            return;
        }
        dragging = false;
        dragCache = null;

        double left = PLOT_MARGIN_LEFT;
        double right = canvas.getWidth() - PLOT_MARGIN_RIGHT;
        double top = PLOT_MARGIN_TOP;
        double bottom = canvas.getHeight() - PLOT_MARGIN_BOTTOM;

        boolean lasso = lassoToggle.isSelected();
        double rx = Math.min(dragStartX, dragCurX);
        double ry = Math.min(dragStartY, dragCurY);
        double rw = Math.abs(dragCurX - dragStartX);
        double rh = Math.abs(dragCurY - dragStartY);
        boolean tinyGesture = !lasso && rw < 3 && rh < 3;

        List<PathObject> hits = new ArrayList<>();
        if (!tinyGesture) {
            for (int i = 0; i < cells.length; i++) {
                if (Double.isNaN(ex[i])) {
                    continue;
                }
                double px = sx(ex[i], left, right);
                double py = sy(ey[i], top, bottom);
                boolean inside = lasso
                        ? pointInPolygon(px, py, lassoPoints)
                        : (px >= rx && px <= rx + rw && py >= ry && py <= ry + rh);
                if (inside) {
                    hits.add(cells[i]);
                }
            }
        }

        // Push to the QuPath viewer selection. Our own selection listener will
        // mirror it back into selected[] and repaint.
        if (hierarchy != null) {
            updatingSelection = true;
            try {
                if (hits.isEmpty()) {
                    hierarchy.getSelectionModel().clearSelection();
                } else {
                    hierarchy.getSelectionModel().setSelectedObjects(hits, null);
                }
            } finally {
                updatingSelection = false;
            }
            QuPathViewer viewer = qupath.getViewer();
            if (viewer != null) {
                viewer.repaint();
            }
        }
        // Reflect locally even if hierarchy is null.
        applySelection(hits);
        redraw();
    }

    private void applySelection(List<PathObject> objs) {
        java.util.Arrays.fill(selected, false);
        for (PathObject o : objs) {
            Integer idx = indexOf.get(o);
            if (idx != null) {
                selected[idx] = true;
            }
        }
    }

    /**
     * Returns the cluster index whose legend row contains the canvas point, or
     * -1 if the point is not on a clickable cluster legend entry. Only valid in
     * CLUSTER colour mode (geometry is recorded by {@link #drawLegend}).
     */
    private int clusterAtPoint(double mx, double my) {
        if (colorCombo.getValue() != ColorMode.CLUSTER || legendClusterCount <= 0) {
            return -1;
        }
        if (mx < legendClusterX - 4 || mx > canvas.getWidth()) {
            return -1;
        }
        double rel = my - (legendClusterY - 3);
        if (rel < 0) {
            return -1;
        }
        int c = (int) (rel / legendClusterLineH);
        return (c >= 0 && c < legendClusterCount) ? c : -1;
    }

    /** Selects every cell assigned to the given k-means cluster in the viewer. */
    private void selectCluster(int c) {
        List<PathObject> hits = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            if (cluster[i] == c) {
                hits.add(cells[i]);
            }
        }
        if (hierarchy != null) {
            updatingSelection = true;
            try {
                if (hits.isEmpty()) {
                    hierarchy.getSelectionModel().clearSelection();
                } else {
                    hierarchy.getSelectionModel().setSelectedObjects(hits, null);
                }
            } finally {
                updatingSelection = false;
            }
            QuPathViewer viewer = qupath.getViewer();
            if (viewer != null) {
                viewer.repaint();
            }
        }
        applySelection(hits);
        redraw();
        statusLabel.setText(
                String.format("Selected cluster %d — %,d cell(s).", c, hits.size()));
    }

    private static boolean pointInPolygon(double x, double y, List<double[]> poly) {
        boolean in = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly.get(i)[0], yi = poly.get(i)[1];
            double xj = poly.get(j)[0], yj = poly.get(j)[1];
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi);
            if (intersect) {
                in = !in;
            }
        }
        return in;
    }

    // ── Viewer → plot selection sync ─────────────────────────────────────────────

    private void installSelectionListener() {
        if (hierarchy == null) {
            return;
        }
        selectionListener = (pathObjectSelected, previousObject, allSelected) ->
                Platform.runLater(() -> {
                    if (updatingSelection) {
                        return; // our own change; release handler already redraws
                    }
                    java.util.Arrays.fill(selected, false);
                    if (allSelected != null) {
                        for (PathObject o : allSelected) {
                            Integer idx = indexOf.get(o);
                            if (idx != null) {
                                selected[idx] = true;
                            }
                        }
                    }
                    redraw();
                });
        hierarchy.getSelectionModel().addPathObjectSelectionListener(selectionListener);
    }

    private void removeSelectionListener() {
        if (hierarchy != null && selectionListener != null) {
            hierarchy.getSelectionModel().removePathObjectSelectionListener(selectionListener);
        }
        selectionListener = null;
    }

    // ── Status + export ──────────────────────────────────────────────────────────

    private void updateStatus() {
        int plotted = 0;
        int sel = 0;
        int clustered = 0;
        for (int i = 0; i < cells.length; i++) {
            if (!Double.isNaN(ex[i])) {
                plotted++;
            }
            if (selected[i]) {
                sel++;
            }
            if (cluster[i] >= 0) {
                clustered++;
            }
        }
        // When UMAP is subsampled, k-means still clusters every (active) cell but
        // only a subset is drawn — show both so the gap is never mistaken for
        // "only N cells were clustered".
        String counts = (plotted == clustered)
                ? String.format("%,d cells", clustered)
                : String.format("%,d clustered · %,d plotted", clustered, plotted);
        statusLabel.setText(String.format(
                "%s  ·  %s  ·  %s  ·  k=%d  ·  %,d selected%s",
                imageName, embeddingCombo.getValue(), counts,
                kSpinner.getValue(), sel, statusNotice));
    }

    private void appendStatusNotice(String notice) {
        // Persisted across redraws via updateStatus() (redraw() rebuilds the label).
        statusNotice = (notice == null) ? "" : notice;
        updateStatus();
    }

    private void exportAsPng() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Scatter Plot as PNG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        fc.setInitialFileName("cell_scatter.png");
        File file = fc.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.WHITE);
            WritableImage image = canvas.snapshot(params, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
            Dialogs.showInfoNotification("CellTune", "Exported scatter plot: " + file.getName());
            logger.info("Exported cell scatter plot to {}", file);
        } catch (IOException ex) {
            logger.error("Failed to export scatter plot", ex);
            Dialogs.showErrorMessage("CellTune", "Failed to export PNG: " + ex.getMessage());
        }
    }
}
