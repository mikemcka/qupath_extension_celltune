package qupath.ext.celltune.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.ext.celltune.classifier.TrainingMetrics;

/**
 * Dialog showing per-class precision/recall/F1 (sklearn-style classification
 * report) for both models on the 80% train and 20% validation portions of a
 * stratified split, computed during the most recent training run.
 */
public class TrainingMetricsView {

    private final Stage stage = new Stage();

    public TrainingMetricsView(Stage owner,
                               TrainingMetrics m1Train,
                               TrainingMetrics m1Val,
                               TrainingMetrics m2Train,
                               TrainingMetrics m2Val) {
        StringBuilder sb = new StringBuilder();
        sb.append("Train/validation metrics from 80/20 stratified split on labelled data.\n");
        sb.append("Evaluation models trained on the 80% only; reported \u201ctrain\u201d row is\n");
        sb.append("the 80% itself (look for over-fitting if train F1 \u226b val F1).\n\n");
        appendBlock(sb, m1Train);
        appendBlock(sb, m1Val);
        appendBlock(sb, m2Train);
        appendBlock(sb, m2Val);

        TextArea text = new TextArea(sb.toString());
        text.setEditable(false);
        text.setWrapText(false);
        text.setFont(Font.font("Monospaced", 12));
        text.setPrefRowCount(28);
        text.setPrefColumnCount(80);

        Label header = new Label("Training Metrics");
        header.setFont(Font.font("SansSerif", 14));

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8));

        VBox root = new VBox(6, header, text, buttons);
        root.setPadding(new Insets(10));

        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("CellTune \u2014 Training Metrics");
        stage.setScene(new Scene(root));
        stage.setResizable(true);
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    private static void appendBlock(StringBuilder sb, TrainingMetrics m) {
        if (m == null) return;
        sb.append(m.toFormattedReport());
        sb.append('\n');
    }
}
