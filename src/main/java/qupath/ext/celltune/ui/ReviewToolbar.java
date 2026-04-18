package qupath.ext.celltune.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.CellTypeTable;

import java.util.*;

/**
 * A toolbar for the Review Mode: navigation buttons, dynamic per-cell
 * prediction buttons, an "All Classes" dropdown, and a status/index indicator.
 *
 * <p>For <b>disagreement</b> cells the toolbar shows the top prediction from
 * each model (XGBoost &amp; LightGBM) as colour-coded buttons so the user can
 * quickly accept one.  For <b>agreement</b> cells a single "Both" button is
 * shown.  An "All Classes ▼" menu provides every class defined in the QuPath
 * project as a fallback.
 *
 * <p>Layout:
 * <pre>
 *  [Previous] [Next] [Skip]  | [XGB: CD4 (87%)] [LGB: Bcell (65%)] | [All Classes ▼] |  (3/50) ●
 * </pre>
 */
public class ReviewToolbar extends HBox {

    private static final ResourceBundle STRINGS =
            ResourceBundle.getBundle("qupath.ext.celltune.ui.strings");

    private final ReviewController controller;
    private final ChannelSelector channelSelector;
    private final CellTypeTable cellTypeTable;

    /** Dynamically-populated prediction buttons for the current cell. */
    private final HBox predictionBox = new HBox(4);
    /** Dropdown listing every PathClass in the QuPath project. */
    private final MenuButton allClassesMenu = new MenuButton("All Classes \u25BC");

    // Status widgets
    private final Label indexLabel = new Label();
    private final Circle statusDot = new Circle(6);

    public ReviewToolbar(ReviewController controller,
                         CellTypeTable cellTypeTable,
                         ChannelSelector channelSelector) {
        super(8);
        this.controller = controller;
        this.cellTypeTable = cellTypeTable;
        this.channelSelector = channelSelector;
        setPadding(new Insets(6, 10, 6, 10));
        setAlignment(Pos.CENTER_LEFT);

        // ── Navigation buttons ──────────────────────────────────────────
        Button prevBtn = new Button(STRINGS.getString("review.previous"));
        prevBtn.setOnAction(e -> {
            controller.previous();
            refreshStatus();
            channelSelector.applyForCurrentCell(controller);
        });

        Button nextBtn = new Button(STRINGS.getString("review.next"));
        nextBtn.setOnAction(e -> {
            boolean more = controller.next();
            refreshStatus();
            channelSelector.applyForCurrentCell(controller);
            if (!more) showCompleteAlert();
        });

        Button skipBtn = new Button(STRINGS.getString("review.skip"));
        skipBtn.setOnAction(e -> {
            boolean more = controller.skip();
            refreshStatus();
            channelSelector.applyForCurrentCell(controller);
            if (!more) showCompleteAlert();
        });

        // ── Separator ──
        Separator sep1 = new Separator(javafx.geometry.Orientation.VERTICAL);

        // ── Prediction buttons — populated per-cell in refreshStatus() ──
        predictionBox.setAlignment(Pos.CENTER_LEFT);

        // ── All Classes menu — fallback for any QuPath class ──
        allClassesMenu.setStyle("-fx-font-size: 11px;");
        populateAllClassesMenu();

        // ── Separator ──
        Separator sep2 = new Separator(javafx.geometry.Orientation.VERTICAL);

        // ── Index indicator ─────────────────────────────────────────────
        HBox statusBox = new HBox(4);
        statusBox.setAlignment(Pos.CENTER);
        statusDot.setFill(Color.WHITE);
        statusDot.setStroke(Color.GRAY);
        indexLabel.setStyle("-fx-font-family: monospace;");
        statusBox.getChildren().addAll(indexLabel, statusDot);

        // Right-click on index → jump-to-index dialog
        indexLabel.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                showJumpDialog();
            }
        });

        // ── Spacer to push status to the right ─────────────────────────
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(prevBtn, nextBtn, skipBtn, sep1,
                predictionBox, allClassesMenu, sep2, spacer, statusBox);

        // ── Auto-advance on first show ──────────────────────────────────
        controller.next();
        channelSelector.applyForCurrentCell(controller);
        refreshStatus();
    }

    // ── Status refresh ──────────────────────────────────────────────────

    /** Update prediction buttons, index label, and status dot colour. */
    public void refreshStatus() {
        int idx = controller.getCurrentIndex();
        int total = controller.size();

        if (controller.isFinished()) {
            indexLabel.setText(String.format("(%d/%d)", total, total));
            statusDot.setFill(Color.WHITE);
            predictionBox.getChildren().clear();
            return;
        }

        indexLabel.setText(String.format("(%d/%d)", idx + 1, total));

        // Rebuild the prediction buttons for the current cell
        updatePredictionButtons();

        // Determine dot colour
        CellPrediction pred = controller.getCurrentPrediction();
        String existingLabel = controller.getOutputLabels()
                .getLabel(controller.getCurrentCell().getID().toString());

        if (existingLabel == null) {
            statusDot.setFill(Color.WHITE);   // unlabelled
        } else if (pred != null && existingLabel.equals(pred.allLabel())) {
            statusDot.setFill(Color.LIMEGREEN); // matches prediction
        } else {
            statusDot.setFill(Color.TOMATO);    // mismatch
        }
    }

    // ── Dynamic prediction buttons ──────────────────────────────────────

    private void updatePredictionButtons() {
        predictionBox.getChildren().clear();
        CellPrediction pred = controller.getCurrentPrediction();
        if (pred == null) return;

        if (pred.isDisagreement()) {
            // XGBoost top prediction
            Button xgbBtn = new Button(String.format("XGB: %s (%.0f%%)",
                    pred.getModel1Label(), pred.model1Confidence() * 100));
            xgbBtn.setStyle("-fx-background-color: #bbdefb; -fx-font-weight: bold; -fx-font-size: 11px;");
            xgbBtn.setOnAction(e -> assignAndAdvance(pred.getModel1Label()));

            // LightGBM top prediction
            Button lgbBtn = new Button(String.format("LGB: %s (%.0f%%)",
                    pred.getModel2Label(), pred.model2Confidence() * 100));
            lgbBtn.setStyle("-fx-background-color: #f8bbd0; -fx-font-weight: bold; -fx-font-size: 11px;");
            lgbBtn.setOnAction(e -> assignAndAdvance(pred.getModel2Label()));

            predictionBox.getChildren().addAll(xgbBtn, lgbBtn);

            // If the averaged prediction differs from both, show it too
            String avgLabel = pred.avgLabel();
            if (!avgLabel.equals(pred.getModel1Label()) && !avgLabel.equals(pred.getModel2Label())) {
                Button avgBtn = new Button(String.format("Avg: %s", avgLabel));
                avgBtn.setStyle("-fx-background-color: #c8e6c9; -fx-font-weight: bold; -fx-font-size: 11px;");
                avgBtn.setOnAction(e -> assignAndAdvance(avgLabel));
                predictionBox.getChildren().add(avgBtn);
            }
        } else {
            // Both models agree — single button
            float avgConf = (pred.model1Confidence() + pred.model2Confidence()) / 2f;
            Button agreedBtn = new Button(String.format("Both: %s (%.0f%%)",
                    pred.getModel1Label(), avgConf * 100));
            agreedBtn.setStyle("-fx-background-color: #c8e6c9; -fx-font-weight: bold; -fx-font-size: 11px;");
            agreedBtn.setOnAction(e -> assignAndAdvance(pred.getModel1Label()));
            predictionBox.getChildren().add(agreedBtn);
        }
    }

    /** Assign a class label to the current cell and move to the next one. */
    private void assignAndAdvance(String className) {
        boolean more = controller.labelAndNext(className);
        refreshStatus();
        channelSelector.applyForCurrentCell(controller);
        if (!more) showCompleteAlert();
    }

    // ── All Classes menu ────────────────────────────────────────────────

    private void populateAllClassesMenu() {
        allClassesMenu.getItems().clear();

        // Gather class names: QuPath project classes first, then CellTypeTable
        Set<String> seen = new LinkedHashSet<>();
        for (String name : controller.getQuPathClassNames()) {
            seen.add(name);
        }
        if (cellTypeTable != null) {
            for (String ct : cellTypeTable.getCellTypes()) {
                seen.add(ct);
            }
        }

        for (String name : seen) {
            MenuItem item = new MenuItem(name);
            item.setOnAction(e -> assignAndAdvance(name));
            allClassesMenu.getItems().add(item);
        }

        if (allClassesMenu.getItems().isEmpty()) {
            allClassesMenu.setDisable(true);
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────

    private void showJumpDialog() {
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle(STRINGS.getString("review.jump.title"));
        dlg.setHeaderText(null);
        dlg.setContentText(STRINGS.getString("review.jump.prompt"));
        dlg.showAndWait().ifPresent(text -> {
            try {
                int target = Integer.parseInt(text.trim()) - 1; // user enters 1-based
                if (controller.jumpTo(target)) {
                    refreshStatus();
                    channelSelector.applyForCurrentCell(controller);
                }
            } catch (NumberFormatException ignored) {
                // silently ignore bad input
            }
        });
    }

    private void showCompleteAlert() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(STRINGS.getString("review.complete"));
        alert.setHeaderText(null);
        alert.setContentText(String.format(
                "Review complete. %d / %d cells labelled.",
                controller.getLabelledCount(), controller.size()));
        alert.showAndWait();
    }
}
