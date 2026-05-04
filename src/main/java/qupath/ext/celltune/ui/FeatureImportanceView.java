package qupath.ext.celltune.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.ext.celltune.classifier.DualModelClassifier.ShapResult;

import java.util.*;

/**
 * JavaFX dialog that renders per-class feature importance as a horizontal bar chart.
 * <p>
 * For XGBoost and LightGBM the values are true mean |SHAP| values computed with
 * each model's native TreeSHAP implementation. For Random Forest, normalised
 * split counts are shown (same across classes).
 * <p>
 * A class selector allows switching between cell types. The top
 * {@value #MAX_BARS} features are displayed sorted by importance.
 */
public class FeatureImportanceView {

    private static final int MAX_BARS     = 10;
    private static final int BAR_HEIGHT   = 22;
    private static final int BAR_GAP      = 4;
    private static final int LEFT_MARGIN  = 215;   // space for feature names
    private static final int RIGHT_MARGIN = 70;    // space for value labels
    private static final int TOP_MARGIN   = 20;
    private static final int BOTTOM_MARGIN = 32;
    private static final int CHART_WIDTH  = 370;

    private static final int CANVAS_WIDTH = LEFT_MARGIN + CHART_WIDTH + RIGHT_MARGIN;

    private static final Font BAR_FONT  = Font.font("SansSerif", 11);
    private static final Font AXIS_FONT = Font.font("SansSerif", 10);

    // One colour per class (wraps if more than 8 classes)
    private static final Color[] CLASS_COLORS = {
            Color.rgb( 70, 130, 180),  // steel blue
            Color.rgb(210,  70,  70),  // red
            Color.rgb( 55, 155,  55),  // green
            Color.rgb(210, 140,  25),  // amber
            Color.rgb(145,  75, 195),  // purple
            Color.rgb( 30, 175, 175),  // teal
            Color.rgb(215, 115,  45),  // orange
            Color.rgb(110, 110, 110),  // gray
    };

    private final Stage stage;
    private final ShapResult shapResult;
    private final Canvas canvas;
    private int selectedClassIdx = 0;

    /**
     * Construct and prepare the view. Call {@link #show()} to display it.
     *
     * @param owner      parent stage
     * @param shapResult computed SHAP importances from {@link qupath.ext.celltune.classifier.DualModelClassifier}
     */
    public FeatureImportanceView(Stage owner, ShapResult shapResult) {
        this.shapResult = shapResult;

        int nBars       = Math.min(MAX_BARS, shapResult.featureNames().size());
        int canvasHeight = TOP_MARGIN + nBars * (BAR_HEIGHT + BAR_GAP) + BOTTOM_MARGIN;

        canvas = new Canvas(CANVAS_WIDTH, canvasHeight);

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("Feature Importance (SHAP)");
        stage.setResizable(false);

        // ── Class selector ──────────────────────────────────────────────────
        Label classLabel = new Label("Class:");
        ComboBox<String> classCombo = new ComboBox<>();
        classCombo.getItems().addAll(shapResult.classNames());
        classCombo.setValue(shapResult.classNames().get(0));
        // Conservative starting cap; refined dynamically when the popup opens
        // so the dropdown never extends past the bottom of the screen.
        classCombo.setVisibleRowCount(Math.min(8, Math.max(3, shapResult.classNames().size())));
        classCombo.showingProperty().addListener((obs, was, isNow) -> {
            if (!isNow) return;
            try {
                var screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
                var comboBounds = classCombo.localToScreen(classCombo.getBoundsInLocal());
                if (comboBounds == null) return;
                double availablePx = screenBounds.getMaxY() - comboBounds.getMaxY() - 24;
                int rowPx = 26; // approx ComboBox cell height incl. padding
                int maxRows = Math.max(3, (int) (availablePx / rowPx));
                int desired = Math.min(maxRows, shapResult.classNames().size());
                if (desired != classCombo.getVisibleRowCount()) {
                    classCombo.setVisibleRowCount(desired);
                }
            } catch (Exception ignored) {
            }
        });
        classCombo.setOnAction(e -> {
            selectedClassIdx = shapResult.classNames().indexOf(classCombo.getValue());
            drawChart();
        });

        HBox topRow = new HBox(8, classLabel, classCombo);
        topRow.setAlignment(Pos.CENTER_LEFT);
        topRow.setPadding(new Insets(8, 10, 4, 10));

        // ── Subtitle ────────────────────────────────────────────────────────
        Label subtitle = new Label(
                "Top features by mean |SHAP| value  ·  averaged across active models");
        subtitle.setStyle("-fx-font-size: 10px; -fx-text-fill: #666;");
        HBox subtitleRow = new HBox(subtitle);
        subtitleRow.setPadding(new Insets(0, 10, 6, 10));

        // ── Canvas padding wrapper ───────────────────────────────────────────
        HBox canvasWrapper = new HBox(canvas);
        canvasWrapper.setPadding(new Insets(0, 6, 4, 6));

        // ── Root layout ─────────────────────────────────────────────────────
        VBox root = new VBox(topRow, subtitleRow, canvasWrapper);
        root.setStyle("-fx-background-color: white;");

        Scene scene = new Scene(root);
        stage.setScene(scene);

        drawChart();
    }

    /** Display the window. */
    public void show() {
        stage.show();
    }

    // ── Rendering ───────────────────────────────────────────────────────────────

    private void drawChart() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        int c = selectedClassIdx;

        // Background
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, w, h);

        double[] importance  = shapResult.meanAbsShap()[c];
        List<String> names   = shapResult.featureNames();
        int nFeatures        = names.size();

        // Sort by importance descending
        Integer[] order = new Integer[nFeatures];
        for (int i = 0; i < nFeatures; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(importance[b], importance[a]));

        int    nBars  = Math.min(MAX_BARS, nFeatures);
        double maxVal = importance[order[0]];
        if (maxVal <= 0) maxVal = 1.0;

        Color barColor = CLASS_COLORS[c % CLASS_COLORS.length];

        gc.setFont(BAR_FONT);

        for (int i = 0; i < nBars; i++) {
            int    fIdx  = order[i];
            String name  = names.get(fIdx);
            double val   = importance[fIdx];
            double ratio = val / maxVal;

            double y      = TOP_MARGIN + i * (BAR_HEIGHT + BAR_GAP);
            double barLen = ratio * CHART_WIDTH;

            // Alternating row background
            if (i % 2 == 1) {
                gc.setFill(Color.rgb(246, 246, 250));
                gc.fillRect(0, y, w, BAR_HEIGHT);
            }

            // Bar — opacity scales with relative importance
            Color fill = barColor.deriveColor(0, 1, 1, 0.30 + 0.70 * ratio);
            gc.setFill(fill);
            gc.fillRect(LEFT_MARGIN, y + 2, barLen, BAR_HEIGHT - 4);

            // Feature name (right-aligned, truncated to fit)
            String display = name.length() > 35 ? name.substring(0, 32) + "\u2026" : name;
            gc.setFill(Color.BLACK);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(display, LEFT_MARGIN - 6, y + BAR_HEIGHT - 6);

            // Value label — inside bar if wide enough, otherwise outside
            String valStr = String.format("%.4f", val);
            gc.setTextAlign(TextAlignment.LEFT);
            if (barLen > 48) {
                gc.setFill(Color.WHITE);
                gc.fillText(valStr, LEFT_MARGIN + barLen - 46, y + BAR_HEIGHT - 6);
            } else {
                gc.setFill(Color.rgb(80, 80, 80));
                gc.fillText(valStr, LEFT_MARGIN + barLen + 4, y + BAR_HEIGHT - 6);
            }
        }

        // Y-axis rule
        gc.setStroke(Color.rgb(180, 180, 180));
        gc.setLineWidth(1);
        double axisTop    = TOP_MARGIN;
        double axisBottom = TOP_MARGIN + nBars * (BAR_HEIGHT + BAR_GAP);
        gc.strokeLine(LEFT_MARGIN, axisTop, LEFT_MARGIN, axisBottom);

        // X-axis label
        gc.setFill(Color.rgb(120, 120, 120));
        gc.setFont(AXIS_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("mean |SHAP value|  (average impact on model output)",
                LEFT_MARGIN + CHART_WIDTH / 2.0,
                axisBottom + 20);
    }
}
