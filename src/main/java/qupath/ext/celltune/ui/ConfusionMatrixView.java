package qupath.ext.celltune.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.PopulationSet;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * JavaFX window that renders an inter-model confusion matrix.
 * <p>
 * Rows = Model 1 (XGBoost) predictions, Columns = Model 2 (LightGBM) predictions.
 * Diagonal cells show agreement counts; off-diagonal cells show disagreement counts.
 * Cells are colour-coded by magnitude (darker = more cells). Per-class agreement
 * rates are shown in the margins.
 * <p>
 * Also computes per-class agreement rates used by {@code UncertaintySampler} for
 * weighted disagreement sampling.
 */
public class ConfusionMatrixView {

    private static final Logger logger = LoggerFactory.getLogger(ConfusionMatrixView.class);

    private final Stage stage;
    private final Canvas canvas;
    private final Label summaryLabel;

    private final List<String> classNames;
    private final int[][] matrix;
    private final double[] agreementRates;
    private final double[] f1Scores;
    private final int totalCells;
    private final int totalAgreements;

    // Layout constants
    private static final int CELL_SIZE = 64;
    private static final int LABEL_MARGIN = 120;
    private static final int RATE_MARGIN = 110;
    private static final int HEADER_HEIGHT = 40;
    private static final Font CELL_FONT = Font.font("SansSerif", 13);
    private static final Font LABEL_FONT = Font.font("SansSerif", 12);
    private static final Font HEADER_FONT = Font.font("SansSerif", Font.getDefault().getSize() + 1);

    /**
     * Build and display the confusion matrix.
     *
     * @param owner      parent stage
     * @param predALL    the Pred_ALL population set (contains all cell predictions)
     * @param classNames ordered class name list
     */
    public ConfusionMatrixView(Stage owner, PopulationSet predALL, List<String> classNames) {
        this.classNames = List.copyOf(classNames);
        int n = classNames.size();
        stage = new Stage();

        // ── Build confusion matrix ──────────────────────────────────────────
        this.matrix = new int[n][n];
        int agreements = 0;
        int total = 0;

        for (CellPrediction pred : predALL.getAll().values()) {
            int row = classNames.indexOf(pred.getModel1Label());
            int col = classNames.indexOf(pred.getModel2Label());
            if (row < 0 || col < 0) continue;
            matrix[row][col]++;
            total++;
            if (row == col) agreements++;
        }
        this.totalCells = total;
        this.totalAgreements = agreements;

        // ── Per-class agreement rates ───────────────────────────────────────
        // Agreement rate for class i = diagonal[i] / (rowSum[i] + colSum[i] - diagonal[i])
        // This measures how often both models agree when either predicts class i.
        this.agreementRates = new double[n];
        this.f1Scores = new double[n];
        for (int i = 0; i < n; i++) {
            int rowSum = 0, colSum = 0;
            for (int j = 0; j < n; j++) {
                rowSum += matrix[i][j];
                colSum += matrix[j][i];
            }
            int denom = rowSum + colSum - matrix[i][i];
            agreementRates[i] = denom > 0 ? (double) matrix[i][i] / denom : 1.0;

            // F1: treat diagonal as TP, row off-diag as FN, col off-diag as FP
            int tp = matrix[i][i];
            int fp = colSum - tp;
            int fn = rowSum - tp;
            double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
            double recall    = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
            f1Scores[i] = (precision + recall) > 0
                    ? 2.0 * precision * recall / (precision + recall) : 0;
        }

        // ── Canvas ──────────────────────────────────────────────────────────
        int canvasWidth = LABEL_MARGIN + n * CELL_SIZE + RATE_MARGIN;
        int canvasHeight = HEADER_HEIGHT + LABEL_MARGIN + n * CELL_SIZE + RATE_MARGIN;
        canvas = new Canvas(canvasWidth, canvasHeight);
        drawMatrix();

        // ── Summary label ───────────────────────────────────────────────────
        double overallRate = totalCells > 0 ? (double) totalAgreements / totalCells * 100 : 0;
        double macroF1 = 0;
        for (double f : f1Scores) macroF1 += f;
        macroF1 = f1Scores.length > 0 ? macroF1 / f1Scores.length : 0;
        summaryLabel = new Label(String.format(
                "Total: %,d cells | Agreement: %,d (%.1f%%) | Disagreement: %,d (%.1f%%) | Macro F1: %.3f",
                totalCells, totalAgreements, overallRate,
                totalCells - totalAgreements, 100 - overallRate, macroF1));
        summaryLabel.setPadding(new Insets(4));

        // ── Export button ───────────────────────────────────────────────────
        Button exportBtn = new Button("Export as PNG…");
        exportBtn.setOnAction(e -> exportAsPng(owner));

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.close());

        HBox buttons = new HBox(8, exportBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8));

        VBox bottom = new VBox(4, summaryLabel, buttons);
        bottom.setAlignment(Pos.CENTER);

        BorderPane root = new BorderPane();
        root.setCenter(canvas);
        root.setBottom(bottom);
        root.setPadding(new Insets(8));

        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("CellTune — Inter-Model Confusion Matrix");
        stage.setScene(new Scene(root));
        stage.setResizable(false);
    }

    /** Show the confusion matrix window. */
    public void show() {
        stage.show();
        stage.toFront();
    }

    /**
     * Get the per-class agreement rate array.
     * Index i corresponds to classNames[i].
     * Values range from 0.0 (never agree) to 1.0 (always agree).
     */
    public double[] getAgreementRates() {
        return agreementRates.clone();
    }

    /**
     * Get the per-class F1 score array.
     * F1 treats diagonal as TP, row off-diagonal as FN, col off-diagonal as FP.
     */
    public double[] getF1Scores() {
        return f1Scores.clone();
    }

    /** @return the raw confusion matrix (rows = model 1, cols = model 2). */
    public int[][] getMatrix() {
        int n = matrix.length;
        int[][] copy = new int[n][n];
        for (int i = 0; i < n; i++) {
            copy[i] = matrix[i].clone();
        }
        return copy;
    }

    /** @return total cells used to build the matrix. */
    public int getTotalCells() { return totalCells; }

    /** @return total cells where both models agreed. */
    public int getTotalAgreements() { return totalAgreements; }

    // ── Drawing ─────────────────────────────────────────────────────────────────

    private void drawMatrix() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        int n = classNames.size();

        // Clear
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Find max value for colour scaling
        int maxVal = 1;
        for (int[] row : matrix) {
            for (int v : row) {
                if (v > maxVal) maxVal = v;
            }
        }

        double gridLeft = LABEL_MARGIN;
        double gridTop = HEADER_HEIGHT + LABEL_MARGIN;

        // ── Axis labels ─────────────────────────────────────────────────────
        gc.setFont(HEADER_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setFill(Color.BLACK);

        // X axis title
        gc.fillText("Model 2 (LightGBM)",
                gridLeft + n * CELL_SIZE / 2.0, HEADER_HEIGHT / 2.0 + 4);

        // Y axis title (rotated)
        gc.save();
        gc.translate(14, gridTop + n * CELL_SIZE / 2.0);
        gc.rotate(-90);
        gc.fillText("Model 1 (XGBoost)", 0, 0);
        gc.restore();

        gc.setFont(LABEL_FONT);

        // Column labels (top)
        for (int j = 0; j < n; j++) {
            double cx = gridLeft + j * CELL_SIZE + CELL_SIZE / 2.0;
            gc.save();
            gc.translate(cx, gridTop - 6);
            gc.rotate(-45);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setFill(Color.BLACK);
            gc.fillText(classNames.get(j), 0, 0);
            gc.restore();
        }

        // Row labels (left)
        gc.setTextAlign(TextAlignment.RIGHT);
        for (int i = 0; i < n; i++) {
            double cy = gridTop + i * CELL_SIZE + CELL_SIZE / 2.0 + 4;
            gc.setFill(Color.BLACK);
            gc.fillText(classNames.get(i), gridLeft - 6, cy);
        }

        // ── Cells ───────────────────────────────────────────────────────────
        gc.setFont(CELL_FONT);
        gc.setTextAlign(TextAlignment.CENTER);

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double x = gridLeft + j * CELL_SIZE;
                double y = gridTop + i * CELL_SIZE;
                int val = matrix[i][j];

                // Colour: diagonal = green scale, off-diagonal = red scale
                Color fill;
                if (i == j) {
                    double intensity = (double) val / maxVal;
                    fill = Color.color(0.85 - 0.55 * intensity,
                                       0.93 - 0.15 * intensity,
                                       0.85 - 0.55 * intensity);
                } else {
                    double intensity = (double) val / maxVal;
                    fill = Color.color(1.0 - 0.1 * intensity,
                                       0.92 - 0.62 * intensity,
                                       0.92 - 0.62 * intensity);
                }

                gc.setFill(fill);
                gc.fillRect(x, y, CELL_SIZE, CELL_SIZE);

                // Border
                gc.setStroke(Color.gray(0.7));
                gc.setLineWidth(0.5);
                gc.strokeRect(x, y, CELL_SIZE, CELL_SIZE);

                // Value text
                if (val > 0) {
                    gc.setFill(val > maxVal * 0.7 ? Color.WHITE : Color.BLACK);
                    gc.fillText(String.valueOf(val),
                            x + CELL_SIZE / 2.0,
                            y + CELL_SIZE / 2.0 + 5);
                }
            }
        }

        // ── Right margin: per-class agreement rates ─────────────────────────
        gc.setFont(LABEL_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < n; i++) {
            double y = gridTop + i * CELL_SIZE + CELL_SIZE / 2.0 + 4;
            double x = gridLeft + n * CELL_SIZE + 6;
            gc.setFill(Color.DARKBLUE);
            gc.fillText(String.format("%.0f%%", agreementRates[i] * 100), x, y);
        }

        // Rate column header
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setFill(Color.DARKBLUE);
        gc.setFont(LABEL_FONT);
        gc.fillText("Agr%", gridLeft + n * CELL_SIZE + 4, gridTop - 6);

        // ── Right margin: per-class F1 scores ───────────────────────────────
        double f1X = gridLeft + n * CELL_SIZE + 52;
        gc.setFont(LABEL_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < n; i++) {
            double y = gridTop + i * CELL_SIZE + CELL_SIZE / 2.0 + 4;
            gc.setFill(Color.DARKRED);
            gc.fillText(String.format("%.2f", f1Scores[i]), f1X, y);
        }

        // F1 column header
        gc.setFill(Color.DARKRED);
        gc.fillText("F1", f1X, gridTop - 6);
    }

    // ── PNG export ──────────────────────────────────────────────────────────────

    private void exportAsPng(Stage owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Confusion Matrix as PNG");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        fc.setInitialFileName("confusion_matrix.png");
        File file = fc.showSaveDialog(owner);
        if (file == null) return;

        try {
            var params = new SnapshotParameters();
            params.setFill(Color.WHITE);
            var image = canvas.snapshot(params, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
            logger.info("Exported confusion matrix to {}", file);
        } catch (IOException ex) {
            logger.error("Failed to export confusion matrix", ex);
        }
    }
}
