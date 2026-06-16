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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
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
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final Spinner<Integer> kSpinner;
    private final ComboBox<ColorMode> colorCombo;
    private final ComboBox<String> markerCombo;
    private final ToggleButton boxToggle;
    private final ToggleButton lassoToggle;
    private final ProgressIndicator progress;
    private final Label statusLabel;

    private PathObjectSelectionListener selectionListener;
    private boolean updatingSelection = false; // guard against self-triggered redraws

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

        kSpinner = new Spinner<>(2, 20, 8);
        kSpinner.setEditable(true);
        kSpinner.setPrefWidth(70);

        Button recomputeBtn = new Button("Recompute");
        recomputeBtn.setOnAction(e -> recompute());

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

        Button exportBtn = new Button("Export PNG…");
        exportBtn.setOnAction(e -> exportAsPng());

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.close());

        HBox row1 = new HBox(8,
                new Label("Embedding:"), embeddingCombo,
                new Label("Clusters (k):"), kSpinner,
                recomputeBtn, progress);
        row1.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row2 = new HBox(8,
                new Label("Colour by:"), colorCombo,
                new Label("Marker:"), markerCombo,
                spacer,
                new Label("Select:"), boxToggle, lassoToggle,
                exportBtn, closeBtn);
        row2.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(6, row1, row2);
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
        if (n == 0) {
            statusLabel.setText("No cells to plot.");
            return;
        }

        progress.setVisible(true);
        statusLabel.setText("Computing " + embedding + " embedding…");
        setControlsDisabled(true);

        new Thread(() -> {
            String notice = "";
            try {
                // ── k-means on all cells (cluster identity independent of axes) ──
                int kEff = Math.min(k, n);
                int[] newCluster = new int[n];
                if (kEff >= 2) {
                    KMeans km = KMeans.fit(std, kEff);
                    System.arraycopy(km.y, 0, newCluster, 0, n);
                } else {
                    java.util.Arrays.fill(newCluster, 0);
                }

                // ── Embedding ────────────────────────────────────────────────────
                double[] nx = new double[n];
                double[] ny = new double[n];
                java.util.Arrays.fill(nx, Double.NaN);
                java.util.Arrays.fill(ny, Double.NaN);

                if (embedding == Embedding.PCA) {
                    double[][] proj = PCA.fit(std).getProjection(2).apply(std);
                    for (int i = 0; i < n; i++) {
                        nx[i] = proj[i][0];
                        ny[i] = proj[i][1];
                    }
                } else {
                    // UMAP: subsample if very large; embed a connected subset.
                    int[] sub = (n > MAX_UMAP_CELLS)
                            ? randomSubsample(n, MAX_UMAP_CELLS)
                            : identity(n);
                    if (n > MAX_UMAP_CELLS) {
                        notice = String.format(
                                " (UMAP on %,d of %,d cells)", MAX_UMAP_CELLS, n);
                    }
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
            } catch (Exception ex) {
                logger.error("Failed to compute scatter embedding", ex);
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    setControlsDisabled(false);
                    statusLabel.setText("Embedding failed: " + ex.getMessage());
                });
            }
        }, "CellTune-ScatterEmbedding").start();
    }

    private void setControlsDisabled(boolean disabled) {
        embeddingCombo.setDisable(disabled);
        kSpinner.setDisable(disabled);
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
        if (mode == ColorMode.CLUSTER) {
            int k = kSpinner.getValue();
            gc.setFill(Color.BLACK);
            gc.fillText("Clusters", x, y - 6);
            for (int c = 0; c < k; c++) {
                double yy = y + c * lineH;
                gc.setFill(clusterColor(c));
                gc.fillRect(x, yy, sw, sw);
                gc.setFill(Color.gray(0.2));
                gc.fillText("Cluster " + c, x + sw + 6, yy + sw - 2);
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

    private void onMousePressed(MouseEvent e) {
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
        for (int i = 0; i < cells.length; i++) {
            if (!Double.isNaN(ex[i])) {
                plotted++;
            }
            if (selected[i]) {
                sel++;
            }
        }
        statusLabel.setText(String.format(
                "%s  ·  %s  ·  %,d cells plotted  ·  k=%d  ·  %,d selected",
                imageName, embeddingCombo.getValue(), plotted, kSpinner.getValue(), sel));
    }

    private void appendStatusNotice(String notice) {
        if (notice != null && !notice.isBlank()) {
            statusLabel.setText(statusLabel.getText() + notice);
        }
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
