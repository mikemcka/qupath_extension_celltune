package qupath.ext.celltune.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.CellTypeTable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * A toolbar for the Review Mode: navigation buttons, cell type assignment
 * buttons, and a status/index indicator.
 *
 * <p>Layout:
 * <pre>
 *  [Previous] [Next] [Skip]   | CellType1 | CellType2 | ... |   (3/50) ●
 * </pre>
 *
 * <p>The indicator dot is green when the current cell's existing label matches
 * prediction, red on mismatch, white when unlabelled.
 */
public class ReviewToolbar extends HBox {

    private static final ResourceBundle STRINGS =
            ResourceBundle.getBundle("qupath.ext.celltune.ui.strings");

    private final ReviewController controller;
    private final ChannelSelector channelSelector;
    private final Map<String, Button> typeButtons = new LinkedHashMap<>();

    // Status widgets
    private final Label indexLabel = new Label();
    private final Circle statusDot = new Circle(6);

    public ReviewToolbar(ReviewController controller,
                         CellTypeTable cellTypeTable,
                         ChannelSelector channelSelector) {
        super(8);
        this.controller = controller;
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

        // ── Cell type buttons ───────────────────────────────────────────
        HBox typeBox = new HBox(4);
        typeBox.setAlignment(Pos.CENTER_LEFT);
        if (cellTypeTable != null) {
            for (String typeName : cellTypeTable.getCellTypes()) {
                Button btn = new Button(typeName);
                btn.setStyle("-fx-font-weight: bold;");
                btn.setOnAction(e -> {
                    boolean more = controller.labelAndNext(typeName);
                    refreshStatus();
                    channelSelector.applyForCurrentCell(controller);
                    if (!more) showCompleteAlert();
                });
                typeButtons.put(typeName, btn);
                typeBox.getChildren().add(btn);
            }
        }

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

        getChildren().addAll(prevBtn, nextBtn, skipBtn, sep1, typeBox, sep2, spacer, statusBox);

        // ── Auto-advance on first show ──────────────────────────────────
        // Start at the first cell
        controller.next();
        channelSelector.applyForCurrentCell(controller);
        refreshStatus();
    }

    /** Update the index label and status dot colour. */
    public void refreshStatus() {
        int idx = controller.getCurrentIndex();
        int total = controller.size();

        if (controller.isFinished()) {
            indexLabel.setText(String.format("(%d/%d)", total, total));
            statusDot.setFill(Color.WHITE);
            return;
        }

        indexLabel.setText(String.format("(%d/%d)", idx + 1, total));

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
