package qupath.ext.celltune.ui;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Dialog that summarizes per-image prediction agreement/disagreement counts
 * and class-count breakdowns from saved Pred_ALL results.
 */
public class ProjectPredictionSummaryView {

    /** Immutable table row model. */
    public record Row(
            String imageName,
            long predictedCells,
            long agreements,
            long disagreements,
            String agreementRate,
            String classCountsText) {
    }

    private static final String TITLE = "Project Prediction Summary";

    private final Stage stage;
    private final QuPathGUI qupath;
    private final List<Row> rows;
    private final TableView<Row> table;

    public ProjectPredictionSummaryView(QuPathGUI qupath, Stage owner, List<Row> rows) {
        this.qupath = qupath;
        this.rows = rows == null ? List.of() : List.copyOf(rows);

        table = new TableView<>(FXCollections.observableArrayList(this.rows));

        TableColumn<Row, String> imageCol = new TableColumn<>("Image");
        imageCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().imageName()));
        imageCol.setMinWidth(240);

        TableColumn<Row, Number> predictedCol = new TableColumn<>("Predicted");
        predictedCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>(cd.getValue().predictedCells()));
        predictedCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Row, Number> agreementsCol = new TableColumn<>("Agreements");
        agreementsCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>(cd.getValue().agreements()));
        agreementsCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Row, Number> disagreementsCol = new TableColumn<>("Disagreements");
        disagreementsCol.setCellValueFactory(cd ->
                new SimpleObjectProperty<>(cd.getValue().disagreements()));
        disagreementsCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Row, String> agreementRateCol = new TableColumn<>("Agreement %");
        agreementRateCol.setCellValueFactory(cd ->
                new SimpleStringProperty(cd.getValue().agreementRate()));
        agreementRateCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        table.getColumns().add(imageCol);
        table.getColumns().add(predictedCol);
        table.getColumns().add(agreementsCol);
        table.getColumns().add(disagreementsCol);
        table.getColumns().add(agreementRateCol);

        disagreementsCol.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().add(disagreementsCol);
        table.sort();

        long withPredictions = 0L;
        for (Row row : this.rows) {
            if (row.predictedCells() > 0) {
                withPredictions++;
            }
        }

        Label summaryLabel = new Label("Images with predictions: "
                + withPredictions + " / " + this.rows.size());
        summaryLabel.setStyle("-fx-font-weight: bold;");

        Label detailsLabel = new Label("Predicted class counts (Pred_AVG):");
        TextArea detailsArea = new TextArea();
        detailsArea.setEditable(false);
        detailsArea.setWrapText(true);
        detailsArea.setPrefRowCount(5);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            if (newRow == null) {
                detailsArea.setText("Select an image to view class counts.");
                return;
            }
            detailsArea.setText(newRow.classCountsText());
        });

        if (!this.rows.isEmpty()) {
            table.getSelectionModel().selectFirst();
        } else {
            detailsArea.setText("No project images found.");
        }

        Button openImageButton = new Button("Open Selected Image");
        openImageButton.setOnAction(e -> openSelectedImage());

        Button exportCsvButton = new Button("Export CSV");
        exportCsvButton.setOnAction(e -> exportCsv());

        Button closeButton = new Button("Close");
        closeButton.setOnAction(e -> closeButton.getScene().getWindow().hide());

        HBox buttonRow = new HBox(8, openImageButton, exportCsvButton, closeButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, summaryLabel, table, detailsLabel, detailsArea, buttonRow);
        root.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle(TITLE);
        stage.setScene(new Scene(root, 980, 560));
    }

    public void show() {
        stage.show();
    }

    private void openSelectedImage() {
        Row selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Dialogs.showWarningNotification(TITLE, "Select an image row first.");
            return;
        }
        if (qupath == null || qupath.getProject() == null) {
            Dialogs.showErrorMessage(TITLE, "Open a QuPath project first.");
            return;
        }

        @SuppressWarnings("unchecked")
        var entries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) qupath.getProject().getImageList();
        var entryOpt = entries.stream()
                .filter(e -> e != null && selected.imageName().equals(e.getImageName()))
                .findFirst();

        if (entryOpt.isEmpty()) {
            Dialogs.showErrorMessage(TITLE,
                    "Could not find image in project: " + selected.imageName());
            return;
        }

        boolean opened = qupath.openImageEntry(entryOpt.get());
        if (!opened) {
            Dialogs.showErrorMessage(TITLE,
                    "QuPath could not open image: " + selected.imageName());
        }
    }

    private void exportCsv() {
        if (rows.isEmpty()) {
            Dialogs.showInfoNotification(TITLE, "No rows available to export.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Project Prediction Summary CSV");
        chooser.setInitialFileName("celltune_project_prediction_summary.csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"));

        File out = chooser.showSaveDialog(stage);
        if (out == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Image,Predicted,Agreements,Disagreements,AgreementPercent,ClassCounts\n");
        for (Row row : rows) {
            sb.append(csvEscape(row.imageName())).append(',')
                    .append(row.predictedCells()).append(',')
                    .append(row.agreements()).append(',')
                    .append(row.disagreements()).append(',')
                    .append(csvEscape(row.agreementRate())).append(',')
                    .append(csvEscape(row.classCountsText()))
                    .append('\n');
        }

        try {
            Files.writeString(out.toPath(), sb.toString(), StandardCharsets.UTF_8);
            Dialogs.showInfoNotification(TITLE, "Exported CSV: " + out.getAbsolutePath());
        } catch (IOException ex) {
            Dialogs.showErrorMessage(TITLE, "Failed to export CSV: " + ex.getMessage());
        }
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}





