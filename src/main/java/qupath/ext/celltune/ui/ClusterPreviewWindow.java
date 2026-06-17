package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.util.JvmModuleOpener;

import smile.feature.extraction.PCA;
import smile.manifold.UMAP;

/**
 * Non-interactive 2D scatter preview of a clustered sample: each row of the
 * standardized feature matrix is projected with PCA or UMAP and drawn coloured
 * by its k-means cluster. Visualisation only — the clustering itself was done in
 * full marker space by {@link ProjectClusteringDialog}.
 */
class ClusterPreviewWindow {

    private static final Logger logger =
            LoggerFactory.getLogger(ClusterPreviewWindow.class);

    /** UMAP is capped here for a responsive preview (PCA always uses all rows). */
    private static final int MAX_UMAP_CELLS = 20_000;
    private static final int DOT_RADIUS = 2;
    private static final double MARGIN_LEFT = 48;
    private static final double MARGIN_RIGHT = 130;
    private static final double MARGIN_TOP = 24;
    private static final double MARGIN_BOTTOM = 36;
    private static final Font LEGEND_FONT = Font.font("SansSerif", 11);

    private enum Embedding { PCA, UMAP }

    private final double[][] std;   // sample feature matrix (standardized)
    private final int[] labels;     // cluster per row
    private final int k;

    private final Stage stage;
    private final Canvas canvas;
    private final ComboBox<Embedding> embeddingCombo;
    private final ProgressIndicator progress;
    private final Label statusLabel;

    // Plotted coordinates (may be a UMAP subsample), with aligned labels.
    private double[] px = new double[0];
    private double[] py = new double[0];
    private int[] plotLabels = new int[0];

    ClusterPreviewWindow(Window owner, double[][] std, int[] labels, int k) {
        this.std = std;
        this.labels = labels;
        this.k = k;

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("CellTune — Cohort Cluster Preview");

        canvas = new Canvas(720, 520);

        embeddingCombo = new ComboBox<>();
        embeddingCombo.getItems().addAll(Embedding.PCA, Embedding.UMAP);
        embeddingCombo.setValue(Embedding.PCA);

        Button recomputeBtn = new Button("Recompute");
        recomputeBtn.setOnAction(e -> recompute());

        progress = new ProgressIndicator();
        progress.setPrefSize(18, 18);
        progress.setVisible(false);

        statusLabel = new Label("");

        HBox top = new HBox(8, new Label("Embedding:"), embeddingCombo,
                recomputeBtn, progress);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));

        Pane holder = new Pane(canvas);
        canvas.widthProperty().bind(holder.widthProperty());
        canvas.heightProperty().bind(holder.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> draw());
        canvas.heightProperty().addListener((o, a, b) -> draw());

        Label status = statusLabel;
        status.setPadding(new Insets(6, 8, 6, 8));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(holder);
        root.setBottom(status);

        stage.setScene(new Scene(root, 880, 620));
    }

    void show() {
        stage.show();
        recompute();
    }

    private void recompute() {
        final Embedding embedding = embeddingCombo.getValue();
        progress.setVisible(true);
        statusLabel.setText("Computing " + embedding + " embedding…");
        embeddingCombo.setDisable(true);

        new Thread(() -> {
            try {
                JvmModuleOpener.ensureJavaLangOpen();
                int m = std.length;
                double[] nx;
                double[] ny;
                int[] lab;
                String note = "";

                if (embedding == Embedding.PCA) {
                    double[][] proj = PCA.fit(std).getProjection(2).apply(std);
                    nx = new double[m];
                    ny = new double[m];
                    for (int i = 0; i < m; i++) {
                        nx[i] = proj[i][0];
                        ny[i] = proj[i][1];
                    }
                    lab = labels;
                } else {
                    try {
                        int[] sub = subsample(m, MAX_UMAP_CELLS);
                        double[][] subMatrix = new double[sub.length][];
                        for (int s = 0; s < sub.length; s++) {
                            subMatrix[s] = std[sub[s]];
                        }
                        int neighbors = Math.min(15, subMatrix.length - 1);
                        UMAP umap = UMAP.of(subMatrix, neighbors);
                        int p = umap.coordinates.length;
                        nx = new double[p];
                        ny = new double[p];
                        lab = new int[p];
                        for (int j = 0; j < p; j++) {
                            nx[j] = umap.coordinates[j][0];
                            ny[j] = umap.coordinates[j][1];
                            lab[j] = labels[sub[umap.index[j]]];
                        }
                        if (m > MAX_UMAP_CELLS) {
                            note = String.format(
                                    " (UMAP on %,d of %,d sampled cells)",
                                    MAX_UMAP_CELLS, m);
                        }
                    } catch (LinkageError err) {
                        logger.warn("UMAP unavailable ({}); preview using PCA.",
                                err.toString());
                        double[][] proj = PCA.fit(std).getProjection(2).apply(std);
                        nx = new double[m];
                        ny = new double[m];
                        for (int i = 0; i < m; i++) {
                            nx[i] = proj[i][0];
                            ny[i] = proj[i][1];
                        }
                        lab = labels;
                        note = " (UMAP unavailable — showing PCA)";
                    }
                }

                final double[] fx = nx;
                final double[] fy = ny;
                final int[] fl = lab;
                final String fNote = note;
                Platform.runLater(() -> {
                    px = fx;
                    py = fy;
                    plotLabels = fl;
                    progress.setVisible(false);
                    embeddingCombo.setDisable(false);
                    statusLabel.setText(String.format(
                            "%s · %,d points · k=%d%s",
                            embeddingCombo.getValue(), fx.length, k, fNote));
                    draw();
                });
            } catch (Throwable t) {
                logger.error("Preview embedding failed", t);
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    embeddingCombo.setDisable(false);
                    statusLabel.setText("Preview failed: " + t.getMessage());
                });
            }
        }, "CellTune-ClusterPreview").start();
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, w, h);
        if (px.length == 0) {
            return;
        }

        double left = MARGIN_LEFT;
        double right = w - MARGIN_RIGHT;
        double top = MARGIN_TOP;
        double bottom = h - MARGIN_BOTTOM;

        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < px.length; i++) {
            minX = Math.min(minX, px[i]);
            maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]);
            maxY = Math.max(maxY, py[i]);
        }
        if (maxX - minX < 1e-9) {
            maxX = minX + 1;
        }
        if (maxY - minY < 1e-9) {
            maxY = minY + 1;
        }

        gc.setStroke(Color.gray(0.85));
        gc.strokeRect(left, top, right - left, bottom - top);

        for (int i = 0; i < px.length; i++) {
            double sx = left + (px[i] - minX) / (maxX - minX) * (right - left);
            double sy = bottom - (py[i] - minY) / (maxY - minY) * (bottom - top);
            gc.setFill(clusterColor(plotLabels[i]));
            gc.fillOval(sx - DOT_RADIUS, sy - DOT_RADIUS,
                    2 * DOT_RADIUS, 2 * DOT_RADIUS);
        }

        drawLegend(gc, right + 12, top);
    }

    private void drawLegend(GraphicsContext gc, double x, double y) {
        gc.setFont(LEGEND_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        double sw = 12;
        double avail = canvas.getHeight() - y - MARGIN_BOTTOM;
        double lineH = Math.min(18, Math.max(10, avail / Math.max(1, k)));
        double swatch = Math.min(sw, lineH - 2);
        gc.setFill(Color.BLACK);
        gc.fillText("Clusters", x, y - 6);
        for (int c = 0; c < k; c++) {
            double yy = y + c * lineH;
            gc.setFill(clusterColor(c));
            gc.fillRect(x, yy, swatch, swatch);
            gc.setFill(Color.gray(0.2));
            gc.fillText("Cluster " + c, x + swatch + 6, yy + swatch - 1);
        }
    }

    private Color clusterColor(int c) {
        if (c < 0) {
            return Color.gray(0.6);
        }
        return Color.hsb(360.0 * (c % k) / k, 0.72, 0.88);
    }

    /** Random distinct indices in [0, n) — partial Fisher–Yates (seeded). */
    private static int[] subsample(int n, int cap) {
        if (n <= cap) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) {
                all[i] = i;
            }
            return all;
        }
        int[] pool = new int[n];
        for (int i = 0; i < n; i++) {
            pool[i] = i;
        }
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < cap; i++) {
            int j = i + rng.nextInt(n - i);
            int tmp = pool[i];
            pool[i] = pool[j];
            pool[j] = tmp;
        }
        int[] out = new int[cap];
        System.arraycopy(pool, 0, out, 0, cap);
        return out;
    }
}
