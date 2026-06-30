package qupath.ext.celltune.ui;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.IntensityHeatmap;
import qupath.fx.dialogs.Dialogs;

/**
 * Renders the cellular-neighborhood (CN) × cell-type enrichment heatmap — the
 * paper's Fig 4B. Rows are CNs, columns are cell types, each cell is the mean
 * composition fraction of that type within the CN, coloured by per-column z-score
 * (reusing {@link IntensityHeatmap#zScoreByColumn}) so red = enriched, blue =
 * depleted. This is how the user reads off and names each CN. Exports the heatmap
 * to PNG and the per-CN frequency + mean-composition table to CSV.
 *
 * <p>Canvas/colourbar/snapshot presentation mirrors {@link IntensityHeatmapView}.
 */
public class NeighborhoodHeatmapView {

    private static final Logger logger = LoggerFactory.getLogger(NeighborhoodHeatmapView.class);

    private final Stage stage;
    private final Canvas canvas;
    private final CheckBox showValuesBox;
    private final Label summaryLabel;

    private final String title;
    private final List<String> typeNames;
    private final List<String> cnLabels;
    private final List<Color> cnColors; // viewer CN colour per row (may be empty)
    private final double[][] meanComposition; // [nCN][nTypes]
    private final double[][] zScores; // [nCN][nTypes]
    private final long[] cnCounts;
    private final long totalCells;

    private static final int CELL_W = 78;
    private static final int CELL_H = 34;
    private static final int LEFT_MARGIN_MIN = 150;
    private static final int LEFT_MARGIN_MAX = 380;
    private static final int LABEL_PAD = 16;
    private static final int SWATCH = 14; // CN colour key swatch
    private static final int SWATCH_GAP = 6;
    private static final int TOP_MARGIN = 64;
    private static final int BOTTOM_MARGIN = 170;
    private static final int COLORBAR_W = 110;
    private static final Font TITLE_FONT =
            Font.font("SansSerif", Font.getDefault().getSize() + 3);
    private static final Font LABEL_FONT = Font.font("SansSerif", 12);
    private static final Font VALUE_FONT = Font.font("SansSerif", 10);

    /**
     * @param owner           parent stage
     * @param title           image title shown in the heatmap header
     * @param typeNames       ordered cell-type column labels
     * @param meanComposition {@code [nCN][nTypes]} mean composition fraction per CN
     * @param cnCounts        number of cells assigned to each CN (row order)
     * @param cnColors        viewer colour per CN row (the colour key); may be null/empty
     */
    public NeighborhoodHeatmapView(
            Stage owner,
            String title,
            List<String> typeNames,
            double[][] meanComposition,
            long[] cnCounts,
            List<Color> cnColors) {
        this.title = title != null ? title : "Current Image";
        this.typeNames = List.copyOf(typeNames);
        this.cnColors = cnColors == null ? List.of() : List.copyOf(cnColors);
        this.meanComposition = meanComposition;
        this.zScores = IntensityHeatmap.zScoreByColumn(meanComposition);
        this.cnCounts = cnCounts.clone();
        long total = 0;
        for (long c : cnCounts) {
            total += c;
        }
        this.totalCells = total;
        this.cnLabels = new ArrayList<>(meanComposition.length);
        for (int i = 0; i < meanComposition.length; i++) {
            cnLabels.add("CN " + (i + 1));
        }

        stage = new Stage();
        canvas = new Canvas(10, 10);
        summaryLabel = new Label("");
        summaryLabel.setPadding(new Insets(4));
        showValuesBox = new CheckBox("Show mean fractions");
        showValuesBox.setSelected(true);
        showValuesBox.setOnAction(e -> redraw());

        Button exportPngBtn = new Button("Export as PNG…");
        exportPngBtn.setOnAction(e -> exportAsPng());
        Button exportCsvBtn = new Button("Export CN frequencies CSV…");
        exportCsvBtn.setOnAction(e -> exportCsv());
        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.close());

        HBox buttons = new HBox(8, showValuesBox, new Region(), exportPngBtn, exportCsvBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(8));
        HBox.setHgrow(buttons.getChildren().get(1), Priority.ALWAYS);

        VBox bottom = new VBox(4, summaryLabel, buttons);
        ScrollPane scroll = new ScrollPane(canvas);
        scroll.setPannable(true);

        BorderPane root = new BorderPane();
        root.setCenter(scroll);
        root.setBottom(bottom);
        root.setPadding(new Insets(8));

        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("CellTune — Cellular Neighborhoods");

        redraw();

        var screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double sceneW = Math.min(screen.getWidth() * 0.85, canvas.getWidth() + 40);
        double sceneH = Math.min(screen.getHeight() * 0.85, canvas.getHeight() + 110);
        stage.setScene(new Scene(root, Math.max(640, sceneW), Math.max(420, sceneH)));
        stage.setResizable(true);
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private String rowLabel(int i) {
        double pct = totalCells > 0 ? 100.0 * cnCounts[i] / totalCells : 0;
        return String.format(Locale.US, "%s  (n=%,d, %.1f%%)", cnLabels.get(i), cnCounts[i], pct);
    }

    private int computeLeftMargin() {
        double maxW = 0;
        Text probe = new Text();
        probe.setFont(LABEL_FONT);
        for (int i = 0; i < cnLabels.size(); i++) {
            probe.setText(rowLabel(i));
            maxW = Math.max(maxW, probe.getLayoutBounds().getWidth());
        }
        int needed = (int) Math.ceil(maxW) + SWATCH + SWATCH_GAP + LABEL_PAD + 8;
        return Math.max(LEFT_MARGIN_MIN, Math.min(LEFT_MARGIN_MAX, needed));
    }

    private void redraw() {
        int nCN = meanComposition.length;
        int nTypes = typeNames.size();

        if (nCN == 0 || nTypes == 0) {
            canvas.setWidth(640);
            canvas.setHeight(200);
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            gc.setFill(Color.gray(0.3));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("No cellular neighborhoods to display.", canvas.getWidth() / 2.0, canvas.getHeight() / 2.0);
            return;
        }

        int leftMargin = computeLeftMargin();
        double width = leftMargin + nTypes * CELL_W + COLORBAR_W;
        double height = TOP_MARGIN + nCN * CELL_H + BOTTOM_MARGIN;
        canvas.setWidth(width);
        canvas.setHeight(height);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, width, height);

        double scale = 0.0;
        for (double[] row : zScores) {
            for (double z : row) {
                if (!Double.isNaN(z)) {
                    scale = Math.max(scale, Math.abs(z));
                }
            }
        }
        if (scale < 1e-9) {
            scale = 1.0;
        }

        double gridLeft = leftMargin;
        double gridTop = TOP_MARGIN;

        gc.setFont(TITLE_FONT);
        gc.setFill(Color.BLACK);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(
                "Cellular neighborhood enrichment  (colour = z-score across CNs)",
                gridLeft + nTypes * CELL_W / 2.0,
                24);
        gc.setFont(LABEL_FONT);
        gc.fillText(title, gridLeft + nTypes * CELL_W / 2.0, 44);

        boolean showValues = showValuesBox.isSelected();
        for (int i = 0; i < nCN; i++) {
            for (int j = 0; j < nTypes; j++) {
                double x = gridLeft + j * CELL_W;
                double y = gridTop + i * CELL_H;
                double z = zScores[i][j];
                double frac = meanComposition[i][j];

                gc.setFill(zToColor(z, scale));
                gc.fillRect(x, y, CELL_W, CELL_H);
                gc.setStroke(Color.gray(0.8));
                gc.setLineWidth(0.5);
                gc.strokeRect(x, y, CELL_W, CELL_H);

                if (showValues && !Double.isNaN(frac)) {
                    double t = Double.isNaN(z) ? 0 : Math.min(1.0, Math.abs(z) / scale);
                    gc.setFill(t > 0.6 ? Color.WHITE : Color.gray(0.15));
                    gc.setFont(VALUE_FONT);
                    gc.setTextAlign(TextAlignment.CENTER);
                    gc.fillText(String.format(Locale.US, "%.2f", frac), x + CELL_W / 2.0, y + CELL_H / 2.0 + 4);
                }
            }
        }

        // Row labels with a CN colour-key swatch matching the viewer colouring.
        gc.setFont(LABEL_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < nCN; i++) {
            double cy = gridTop + i * CELL_H + CELL_H / 2.0 + 4;
            double sy = gridTop + i * CELL_H + (CELL_H - SWATCH) / 2.0;
            if (i < cnColors.size()) {
                gc.setFill(cnColors.get(i));
                gc.fillRect(4, sy, SWATCH, SWATCH);
                gc.setStroke(Color.gray(0.5));
                gc.setLineWidth(0.5);
                gc.strokeRect(4, sy, SWATCH, SWATCH);
            }
            gc.setFill(Color.BLACK);
            gc.fillText(rowLabel(i), 4 + SWATCH + SWATCH_GAP, cy);
        }

        double bottomY = gridTop + nCN * CELL_H + 8;
        gc.setFont(LABEL_FONT);
        for (int j = 0; j < nTypes; j++) {
            double cx = gridLeft + j * CELL_W + CELL_W / 2.0;
            gc.save();
            gc.translate(cx, bottomY);
            gc.rotate(-45);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.setFill(Color.BLACK);
            gc.fillText(typeNames.get(j), 0, 0);
            gc.restore();
        }

        drawColorbar(gc, gridLeft + nTypes * CELL_W + 24, gridTop, nCN * CELL_H, scale);
        summaryLabel.setText(
                String.format(Locale.US, "%s | %d CNs × %d types | %,d cells", title, nCN, nTypes, totalCells));
    }

    private void drawColorbar(GraphicsContext gc, double x, double y, double height, double scale) {
        double barW = 22;
        int steps = 128;
        double stepH = height / steps;
        for (int s = 0; s < steps; s++) {
            double z = scale - (2.0 * scale) * (s / (double) (steps - 1));
            gc.setFill(zToColor(z, scale));
            gc.fillRect(x, y + s * stepH, barW, stepH + 1);
        }
        gc.setStroke(Color.gray(0.5));
        gc.setLineWidth(0.5);
        gc.strokeRect(x, y, barW, height);
        gc.setFill(Color.BLACK);
        gc.setFont(VALUE_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        double labelX = x + barW + 5;
        gc.fillText(String.format(Locale.US, "+%.1f", scale), labelX, y + 9);
        gc.fillText("0", labelX, y + height / 2.0 + 3);
        gc.fillText(String.format(Locale.US, "-%.1f", scale), labelX, y + height);
    }

    /** Diverging colour map: blue (low) → white (0) → red (high). */
    private static Color zToColor(double z, double scale) {
        if (Double.isNaN(z)) {
            return Color.gray(0.9);
        }
        double t = Math.max(-1.0, Math.min(1.0, z / scale));
        if (t >= 0) {
            return Color.color(1.0, 1.0 - 0.85 * t, 1.0 - 0.85 * t);
        }
        double a = -t;
        return Color.color(1.0 - 0.85 * a, 1.0 - 0.85 * a, 1.0);
    }

    // ── Export ───────────────────────────────────────────────────────────────

    private void exportAsPng() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save CN Heatmap as PNG");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        fc.setInitialFileName("cn_enrichment_heatmap.png");
        File file = fc.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            var params = new SnapshotParameters();
            params.setFill(Color.WHITE);
            var image = canvas.snapshot(params, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
            Dialogs.showInfoNotification("CellTune", "Exported heatmap: " + file.getName());
        } catch (IOException ex) {
            logger.error("Failed to export CN heatmap", ex);
            Dialogs.showErrorMessage("CellTune", "Failed to export PNG: " + ex.getMessage());
        }
    }

    private void exportCsv() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export CN Frequencies CSV");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        fc.setInitialFileName("cn_frequencies.csv");
        File file = fc.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            List<String> header = new ArrayList<>();
            header.add("CN");
            header.add("Count");
            header.add("Fraction");
            for (String t : typeNames) {
                header.add(csvQuote("mean_" + t));
            }
            w.write(String.join(",", header));
            w.newLine();
            for (int i = 0; i < cnLabels.size(); i++) {
                double frac = totalCells > 0 ? (double) cnCounts[i] / totalCells : 0;
                StringBuilder row = new StringBuilder();
                row.append(csvQuote(cnLabels.get(i)))
                        .append(',')
                        .append(cnCounts[i])
                        .append(',')
                        .append(frac);
                for (int j = 0; j < typeNames.size(); j++) {
                    row.append(',').append(meanComposition[i][j]);
                }
                w.write(row.toString());
                w.newLine();
            }
            Dialogs.showInfoNotification("CellTune", "Exported CSV: " + file.getName());
        } catch (IOException ex) {
            logger.error("Failed to export CN frequencies CSV", ex);
            Dialogs.showErrorMessage("CellTune", "Failed to export CSV: " + ex.getMessage());
        }
    }

    private static String csvQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
