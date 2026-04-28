package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.CompositeClassifier;
import qupath.ext.celltune.io.BinaryClassifierRegistry;
import qupath.ext.celltune.io.ProjectStateManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Modal dialog for composite cell classification.
 *
 * <p>Shows a checkbox list of binary classifiers from the project registry.
 * Trained classifiers have enabled checkboxes; untrained are shown but disabled.
 * The user can apply classification to the current image or batch-classify multiple images.
 *
 * <p>Checkbox selection is persisted to {@code composite-config.json} and restored on re-open.
 */
public class CompositeClassificationDialog {

    private static final Logger logger = LoggerFactory.getLogger(CompositeClassificationDialog.class);

    private final QuPathGUI qupath;

    public CompositeClassificationDialog(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /**
     * Build and show the dialog modally. Blocks until the dialog is closed.
     */
    public void showAndWait() {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("Composite Classification", "No project is open.");
            return;
        }

        // ── Build marker list ───────────────────────────────────────────────
        Map<String, String> registry;
        try {
            registry = BinaryClassifierRegistry.load(project);
        } catch (IOException ex) {
            logger.warn("Failed to load binary classifier registry: {}", ex.getMessage());
            registry = new java.util.LinkedHashMap<>();
        }

        List<String> savedSelected;
        try {
            savedSelected = ProjectStateManager.loadCompositeConfig(project);
        } catch (IOException ex) {
            logger.warn("Failed to load composite config: {}", ex.getMessage());
            savedSelected = new ArrayList<>();
        }

        List<CheckBox> checkBoxes = new ArrayList<>();
        boolean anyTrained = false;

        for (Map.Entry<String, String> entry : registry.entrySet()) {
            String markerName = entry.getKey();
            boolean trained = isMarkerTrained(project, markerName);
            if (trained) anyTrained = true;

            CheckBox cb = new CheckBox(markerName);
            cb.setDisable(!trained);
            if (trained) {
                cb.setSelected(savedSelected.contains(markerName));
                cb.setStyle("-fx-text-fill: #2a7a2a;");
            } else {
                cb.setSelected(false);
                cb.setStyle("-fx-text-fill: #888888;");
            }
            checkBoxes.add(cb);
        }

        // ── Layout ──────────────────────────────────────────────────────────
        Label titleLabel = new Label("Composite Classification");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label subtitleLabel = new Label(
                "Select trained binary classifiers to combine into a composite PathClass.");
        subtitleLabel.setWrapText(true);

        VBox cbBox = new VBox(4);
        cbBox.getChildren().addAll(checkBoxes);
        cbBox.setPadding(new Insets(4));

        ScrollPane scrollPane = new ScrollPane(cbBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setMaxHeight(250);
        scrollPane.setMinHeight(60);

        Label noTrainedLabel = new Label(
                "No trained classifiers available. Train binary classifiers first.");
        noTrainedLabel.setStyle("-fx-text-fill: #CC0000;");
        noTrainedLabel.setWrapText(true);
        noTrainedLabel.setVisible(!anyTrained);
        noTrainedLabel.setManaged(!anyTrained);

        Label resultLabel = new Label("");
        resultLabel.setStyle("-fx-text-fill: #2a7a2a;");

        Button applyButton = new Button("Apply");
        Button batchButton = new Button("Batch...");
        Button closeButton = new Button("Close");

        applyButton.setDisable(!anyTrained);
        batchButton.setDisable(!anyTrained);

        HBox buttonBar = new HBox(8, applyButton, batchButton, closeButton);
        buttonBar.setPadding(new Insets(4, 0, 0, 0));

        VBox root = new VBox(8,
                titleLabel,
                subtitleLabel,
                scrollPane,
                noTrainedLabel,
                resultLabel,
                buttonBar);
        root.setPadding(new Insets(12));

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(qupath.getStage());
        stage.setTitle("Composite Classification");
        stage.setScene(new Scene(root, 420, 380));
        stage.setResizable(true);


        // batchImageNames: images pre-selected via "Batch..." button for next Apply
        final List<String>[] batchHolder = new List[]{new ArrayList<>()};

        // ── Button actions ───────────────────────────────────────────────────
        closeButton.setOnAction(e -> stage.close());

        applyButton.setOnAction(e -> {
            var imageData = qupath.getImageData();
            if (imageData == null) {
                Dialogs.showErrorMessage("Composite Classification", "No image is open.");
                return;
            }
            List<String> selected = getSelectedMarkers(checkBoxes);
            if (selected.isEmpty()) {
                Dialogs.showErrorMessage("Composite Classification",
                        "No classifiers selected. Check at least one checkbox.");
                return;
            }
            saveConfig(project, selected);

            // Determine current image name for live-update handling
            String currentImgName = null;
            var curEntry = project.getEntry(imageData);
            if (curEntry != null) currentImgName = curEntry.getImageName();
            final String finalCurrentImgName = currentImgName;
            final var finalImageData = imageData;
            final List<String> finalSelected = selected;
            final List<String> batchImages = new ArrayList<>(batchHolder[0]);

            resultLabel.setText("Classifying...");
            applyButton.setDisable(true);
            batchButton.setDisable(true);

            CompositeClassifier cc = new CompositeClassifier();
            Stage progressStage = buildProgressStage(stage, cc);
            progressStage.show();

            Task<Integer> task = new Task<>() {
                @Override
                protected Integer call() throws Exception {
                    int total = 0;
                    // Always apply to the current live image
                    total += cc.apply(finalImageData, finalSelected, project,
                            msg -> Platform.runLater(() -> {}));
                    // Apply to batch images (skip current — already done above)
                    @SuppressWarnings("unchecked")
                    var typedProject = (qupath.lib.projects.Project<java.awt.image.BufferedImage>)
                            (qupath.lib.projects.Project<?>) project;
                    for (String imgName : batchImages) {
                        if (imgName.equals(finalCurrentImgName)) continue;
                        var entryOpt = typedProject.getImageList().stream()
                                .filter(en -> en.getImageName().equals(imgName))
                                .findFirst();
                        if (entryOpt.isEmpty()) continue;
                        var imgEntry = entryOpt.get();
                        var otherData = imgEntry.readImageData();
                        if (otherData == null) continue;
                        total += cc.apply(otherData, finalSelected, project,
                                msg -> Platform.runLater(() -> {}));
                        imgEntry.saveImageData(otherData);
                    }
                    return total;
                }
            };
            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                progressStage.close();
                resultLabel.setText("Classified " + task.getValue() + " cells"
                        + (batchImages.size() > 1 ? " across " + batchImages.size() + " images" : "") + ".");
                applyButton.setDisable(false);
                batchButton.setDisable(false);
            }));
            task.setOnFailed(ev -> Platform.runLater(() -> {
                progressStage.close();
                applyButton.setDisable(false);
                batchButton.setDisable(false);
                String msg = task.getException() != null
                        ? task.getException().getMessage() : "Unknown error";
                Dialogs.showErrorMessage("Composite Classification", msg);
                resultLabel.setText("Error: " + msg);
            }));
            new Thread(task, "composite-classify").start();
        });

        // "Batch..." only stores the image selection — Apply runs the actual classification
        batchButton.setOnAction(e -> {
            List<String> allImageNames = new ArrayList<>();
            for (var entry : project.getImageList()) allImageNames.add(entry.getImageName());
            if (allImageNames.isEmpty()) return;

            String currentImgName = null;
            if (qupath.getImageData() != null) {
                var curEntry2 = project.getEntry(qupath.getImageData());
                if (curEntry2 != null) currentImgName = curEntry2.getImageName();
            }

            ImageSelectionPane selectionPane = new ImageSelectionPane(
                    stage, allImageNames, currentImgName);
            List<String> picked = selectionPane.showAndWait();
            if (picked == null) return; // cancelled

            batchHolder[0] = new ArrayList<>(picked);
            batchButton.setText(picked.isEmpty() ? "Batch..." : "Batch (" + picked.size() + ")...");
        });

        stage.showAndWait();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isMarkerTrained(qupath.lib.projects.Project<?> project, String markerName) {
        try {
            String sanitized = BinaryClassifierRegistry.sanitizeMarkerName(markerName);
            ProjectStateManager.SavedState state =
                    ProjectStateManager.loadBinaryState(project, sanitized);
            return state != null && state.xgboostModelBase64 != null;
        } catch (Exception ex) {
            logger.debug("isMarkerTrained check failed for '{}': {}", markerName, ex.getMessage());
            return false;
        }
    }

    private List<String> getSelectedMarkers(List<CheckBox> checkBoxes) {
        List<String> selected = new ArrayList<>();
        for (CheckBox cb : checkBoxes) {
            if (!cb.isDisabled() && cb.isSelected()) {
                selected.add(cb.getText());
            }
        }
        return selected;
    }

    private void saveConfig(qupath.lib.projects.Project<?> project, List<String> selected) {
        try {
            ProjectStateManager.saveCompositeConfig(project, selected);
        } catch (IOException ex) {
            logger.warn("Failed to save composite config: {}", ex.getMessage());
        }
    }

    private Stage buildProgressStage(Stage owner, CompositeClassifier cc) {
        Stage ps = new Stage();
        ps.initModality(Modality.NONE);
        ps.initOwner(owner);
        ps.setTitle("Composite Classification");

        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(350);
        bar.progressProperty().bind(cc.progressProperty());

        Label statusLabel = new Label("Starting...");
        statusLabel.textProperty().bind(cc.statusProperty());

        TextArea log = new TextArea();
        log.setEditable(false);
        log.setPrefHeight(100);
        log.setWrapText(true);

        VBox content = new VBox(8, statusLabel, bar, log);
        content.setPadding(new Insets(12));
        ps.setScene(new Scene(content, 400, 220));
        return ps;
    }

    private void showBatchResults(Stage owner, java.util.Map<String, String> results) {
        Stage rs = new Stage();
        rs.initOwner(owner);
        rs.initModality(Modality.APPLICATION_MODAL);
        rs.setTitle("Batch Classification Results");

        StringBuilder sb = new StringBuilder();
        for (var entry : results.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }

        TextArea area = new TextArea(sb.toString());
        area.setEditable(false);
        area.setPrefSize(450, 280);

        Button ok = new Button("OK");
        ok.setOnAction(e -> rs.close());

        VBox box = new VBox(8, area, ok);
        box.setPadding(new Insets(12));
        rs.setScene(new Scene(box, 480, 320));
        rs.showAndWait();
    }
}
