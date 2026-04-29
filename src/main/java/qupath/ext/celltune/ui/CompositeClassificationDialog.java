package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.CompositeClassificationRule;
import qupath.ext.celltune.classifier.CompositeClassifier;
import qupath.ext.celltune.io.BinaryClassifierRegistry;
import qupath.ext.celltune.io.ProjectStateManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modal dialog for named composite rule management and execution.
 */
public class CompositeClassificationDialog {

    private static final Logger logger = LoggerFactory.getLogger(CompositeClassificationDialog.class);

    private final QuPathGUI qupath;

    private enum RowPolarity {
        POSITIVE,
        NEGATIVE,
        IGNORE
    }

    private static final class MarkerRowControl {
        private final String markerName;
        private final ToggleGroup polarityGroup;
        private final Toggle positiveToggle;
        private final Toggle negativeToggle;
        private final Toggle ignoreToggle;
        private final HBox row;

        private MarkerRowControl(String markerName) {
            this.markerName = markerName;

            Label markerLabel = new Label(markerName);
            markerLabel.setMinWidth(150);
            markerLabel.setPrefWidth(180);

            RadioButton plusButton = new RadioButton("+");
            RadioButton minusButton = new RadioButton("-");
            RadioButton ignoreButton = new RadioButton("ignore");

            polarityGroup = new ToggleGroup();
            plusButton.setToggleGroup(polarityGroup);
            minusButton.setToggleGroup(polarityGroup);
            ignoreButton.setToggleGroup(polarityGroup);

            plusButton.setUserData(RowPolarity.POSITIVE);
            minusButton.setUserData(RowPolarity.NEGATIVE);
            ignoreButton.setUserData(RowPolarity.IGNORE);

            ignoreButton.setSelected(true);

            positiveToggle = plusButton;
            negativeToggle = minusButton;
            ignoreToggle = ignoreButton;

            HBox polarityBox = new HBox(10, plusButton, minusButton, ignoreButton);
            polarityBox.setAlignment(Pos.CENTER_LEFT);

            Region spacer = new Region();
            spacer.setMinWidth(4);

            row = new HBox(12, markerLabel, spacer, polarityBox);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(2, 0, 2, 0));
        }

        public HBox node() {
            return row;
        }

        public String markerName() {
            return markerName;
        }

        public void onSelectionChanged(Runnable listener) {
            polarityGroup.selectedToggleProperty().addListener((obs, oldV, newV) -> listener.run());
        }

        public void setSelection(RowPolarity polarity) {
            if (polarity == RowPolarity.POSITIVE) {
                polarityGroup.selectToggle(positiveToggle);
            } else if (polarity == RowPolarity.NEGATIVE) {
                polarityGroup.selectToggle(negativeToggle);
            } else {
                polarityGroup.selectToggle(ignoreToggle);
            }
        }

        public void setFromCondition(CompositeClassificationRule.MarkerCondition condition) {
            if (condition == null) {
                setSelection(RowPolarity.IGNORE);
                return;
            }
            setSelection(condition.polarity() == CompositeClassificationRule.Polarity.POSITIVE
                    ? RowPolarity.POSITIVE
                    : RowPolarity.NEGATIVE);
        }

        public CompositeClassificationRule.MarkerCondition toConditionOrNull() {
            Toggle selected = polarityGroup.getSelectedToggle();
            if (selected == null || selected.getUserData() == RowPolarity.IGNORE) {
                return null;
            }
            CompositeClassificationRule.Polarity polarity =
                    selected.getUserData() == RowPolarity.POSITIVE
                            ? CompositeClassificationRule.Polarity.POSITIVE
                            : CompositeClassificationRule.Polarity.NEGATIVE;
            return CompositeClassificationRule.MarkerCondition.of(markerName, polarity);
        }
    }

    public CompositeClassificationDialog(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    public void showAndWait() {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("Composite Classification", "No project is open.");
            return;
        }

        Map<String, CompositeClassificationRule> rulesByName = new LinkedHashMap<>();
        try {
            for (CompositeClassificationRule rule : ProjectStateManager.loadCompositeRules(project)) {
                rulesByName.put(rule.name(), rule);
            }
        } catch (IOException ex) {
            logger.warn("Failed to load composite rules: {}", ex.getMessage());
        }

        List<String> markerNames;
        try {
            markerNames = loadTrainedMarkerNames(project);
        } catch (IOException ex) {
            Dialogs.showErrorMessage("Composite Classification",
                    "Failed to load binary marker registry: " + ex.getMessage());
            return;
        }

        Label titleLabel = new Label("Composite Classification Rules");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label savedLabel = new Label("Saved rules:");
        ComboBox<String> savedRulesCombo = new ComboBox<>();
        savedRulesCombo.setPrefWidth(420);

        Label nameLabel = new Label("Rule name:");
        TextField ruleNameField = new TextField();
        ruleNameField.setPromptText("CD4 T cell");

        Label markersLabel = new Label("Marker polarity selections:");
        VBox markerRowsBox = new VBox(4);
        markerRowsBox.setPadding(new Insets(4, 0, 4, 0));

        Map<String, MarkerRowControl> rowsByMarkerLower = new LinkedHashMap<>();
        for (String markerName : markerNames) {
            MarkerRowControl row = new MarkerRowControl(markerName);
            rowsByMarkerLower.put(markerName.toLowerCase(Locale.ROOT), row);
            markerRowsBox.getChildren().add(row.node());
        }

        if (rowsByMarkerLower.isEmpty()) {
            Label emptyLabel = new Label("No trained binary markers found. Train at least one marker classifier first.");
            emptyLabel.setWrapText(true);
            emptyLabel.setStyle("-fx-text-fill: #9a2a2a;");
            markerRowsBox.getChildren().add(emptyLabel);
        }

        ScrollPane markerScroll = new ScrollPane(markerRowsBox);
        markerScroll.setFitToWidth(true);
        markerScroll.setPannable(true);
        markerScroll.setPrefViewportHeight(170);
        markerScroll.setMinHeight(120);

        Label expressionPreviewLabel = new Label("Rule expression preview:");
        TextField expressionPreviewField = new TextField();
        expressionPreviewField.setEditable(false);

        Label helperLabel = new Label("Select +, -, or ignore for each marker. At least one marker must be + or -.");
        helperLabel.setStyle("-fx-text-fill: #666666;");
        helperLabel.setWrapText(true);

        Button saveRuleButton = new Button("Save Rule");
        Button deleteRuleButton = new Button("Delete Rule");
        Button applyButton = new Button("Apply");
        Button batchButton = new Button("Apply to which images...");
        Button closeButton = new Button("Close");

        Label resultLabel = new Label("");
        resultLabel.setWrapText(true);
        resultLabel.setStyle("-fx-text-fill: #2a7a2a;");

        final List<String>[] batchHolder = new List[]{new ArrayList<>()};

        Runnable refreshExpressionPreview = () ->
                expressionPreviewField.setText(buildExpressionPreview(rowsByMarkerLower.values()));

        for (MarkerRowControl row : rowsByMarkerLower.values()) {
            row.onSelectionChanged(refreshExpressionPreview);
        }
        refreshExpressionPreview.run();

        Runnable refreshRuleCombo = () -> {
            savedRulesCombo.setItems(FXCollections.observableArrayList(rulesByName.keySet()));
            deleteRuleButton.setDisable(rulesByName.isEmpty());
        };
        refreshRuleCombo.run();

        boolean hasMarkerRows = !rowsByMarkerLower.isEmpty();
        saveRuleButton.setDisable(!hasMarkerRows);
        applyButton.setDisable(!hasMarkerRows);
        batchButton.setDisable(!hasMarkerRows);

        if (!rulesByName.isEmpty()) {
            String first = rulesByName.keySet().iterator().next();
            savedRulesCombo.getSelectionModel().select(first);
            CompositeClassificationRule initialRule = rulesByName.get(first);
            ruleNameField.setText(initialRule.name());
            List<String> unknown = applyRuleToRows(initialRule, rowsByMarkerLower);
            refreshExpressionPreview.run();
            if (!unknown.isEmpty()) {
                resultLabel.setText("Loaded rule with unavailable markers ignored: " + String.join(", ", unknown));
            }
        }

        savedRulesCombo.setOnAction(e -> {
            String selectedName = savedRulesCombo.getValue();
            CompositeClassificationRule selectedRule = selectedName != null ? rulesByName.get(selectedName) : null;
            if (selectedRule != null) {
                ruleNameField.setText(selectedRule.name());
                List<String> unknown = applyRuleToRows(selectedRule, rowsByMarkerLower);
                refreshExpressionPreview.run();
                if (unknown.isEmpty()) {
                    resultLabel.setText("Loaded rule '" + selectedRule.name() + "'.");
                } else {
                    resultLabel.setText("Loaded rule '" + selectedRule.name()
                            + "' with unavailable markers ignored: " + String.join(", ", unknown));
                }
            }
        });

        saveRuleButton.setOnAction(e -> {
            CompositeClassificationRule builtRule = buildRuleFromRows(ruleNameField,
                    rowsByMarkerLower.values(), resultLabel, true);
            if (builtRule == null) {
                return;
            }

            rulesByName.put(builtRule.name(), builtRule);
            if (!persistRules(project, rulesByName.values(), resultLabel)) {
                return;
            }

            refreshRuleCombo.run();
            savedRulesCombo.getSelectionModel().select(builtRule.name());
            resultLabel.setText("Saved rule '" + builtRule.name() + "'.");
            refreshExpressionPreview.run();
        });

        deleteRuleButton.setOnAction(e -> {
            String selectedName = savedRulesCombo.getValue();
            if (selectedName == null || selectedName.isBlank()) {
                Dialogs.showErrorMessage("Composite Classification", "Select a saved rule to delete.");
                return;
            }

            rulesByName.remove(selectedName);
            if (!persistRules(project, rulesByName.values(), resultLabel)) {
                return;
            }

            refreshRuleCombo.run();
            if (rulesByName.isEmpty()) {
                ruleNameField.clear();
                savedRulesCombo.getSelectionModel().clearSelection();
                for (MarkerRowControl row : rowsByMarkerLower.values()) {
                    row.setSelection(RowPolarity.IGNORE);
                }
            } else {
                String first = rulesByName.keySet().iterator().next();
                savedRulesCombo.getSelectionModel().select(first);
                CompositeClassificationRule remaining = rulesByName.get(first);
                ruleNameField.setText(remaining.name());
                applyRuleToRows(remaining, rowsByMarkerLower);
            }

            refreshExpressionPreview.run();
            resultLabel.setText("Deleted rule '" + selectedName + "'.");
        });

        closeButton.setOnAction(e -> {
            CompositeClassificationRule builtRule = buildRuleFromRows(ruleNameField,
                    rowsByMarkerLower.values(), null, false);
            if (builtRule != null) {
                rulesByName.put(builtRule.name(), builtRule);
                persistRules(project, rulesByName.values(), null);
            }
            ((Stage) closeButton.getScene().getWindow()).close();
        });

        applyButton.setOnAction(e -> {
            var imageData = qupath.getImageData();
            if (imageData == null) {
                Dialogs.showErrorMessage("Composite Classification", "No image is open.");
                return;
            }

            CompositeClassificationRule rule = buildRuleFromRows(ruleNameField,
                    rowsByMarkerLower.values(), resultLabel, true);
            if (rule == null) {
                return;
            }

            rulesByName.put(rule.name(), rule);
            if (!persistRules(project, rulesByName.values(), resultLabel)) {
                return;
            }
            refreshRuleCombo.run();
            savedRulesCombo.getSelectionModel().select(rule.name());

            String currentImgName = null;
            var currentEntry = project.getEntry(imageData);
            if (currentEntry != null) {
                currentImgName = currentEntry.getImageName();
            }

            final String finalCurrentImgName = currentImgName;
            final List<String> selectedBatch = new ArrayList<>(batchHolder[0]);

            resultLabel.setText("Applying rule...");
            applyButton.setDisable(true);
            batchButton.setDisable(true);

            CompositeClassifier classifier = new CompositeClassifier();
            Stage progressStage = buildProgressStage((Stage) applyButton.getScene().getWindow(), classifier);
            progressStage.show();

            Task<String> task = new Task<>() {
                @Override
                protected String call() throws Exception {
                    int matchedCurrent = classifier.applyRule(imageData, rule, project,
                            msg -> Platform.runLater(() -> {
                            }));

                    List<String> otherImages = new ArrayList<>();
                    for (String imageName : selectedBatch) {
                        if (finalCurrentImgName == null || !finalCurrentImgName.equals(imageName)) {
                            otherImages.add(imageName);
                        }
                    }

                    Map<String, String> batchResults = new LinkedHashMap<>();
                    if (!otherImages.isEmpty()) {
                        batchResults = classifier.batchApplyRule(project, otherImages, rule, false,
                                msg -> Platform.runLater(() -> {
                                }));
                    }

                    StringBuilder summary = new StringBuilder();
                    summary.append("Matched ").append(matchedCurrent).append(" cell(s) in current image.");
                    if (!batchResults.isEmpty()) {
                        summary.append(" Batch images: ");
                        for (Map.Entry<String, String> entry : batchResults.entrySet()) {
                            summary.append(entry.getKey()).append("=").append(entry.getValue()).append("; ");
                        }
                    }
                    return summary.toString();
                }
            };

            task.setOnSucceeded(ev -> Platform.runLater(() -> {
                progressStage.close();
                applyButton.setDisable(false);
                batchButton.setDisable(false);
                resultLabel.setText(task.getValue());
            }));

            task.setOnFailed(ev -> Platform.runLater(() -> {
                progressStage.close();
                applyButton.setDisable(false);
                batchButton.setDisable(false);
                String msg = task.getException() != null ? task.getException().getMessage() : "Unknown error";
                resultLabel.setText("Error: " + msg);
                Dialogs.showErrorMessage("Composite Classification", msg);
            }));

            new Thread(task, "composite-rule-apply").start();
        });

        batchButton.setOnAction(e -> {
            List<String> allImageNames = new ArrayList<>();
            for (var entry : project.getImageList()) {
                allImageNames.add(entry.getImageName());
            }
            if (allImageNames.isEmpty()) {
                return;
            }

            String currentImgName = null;
            if (qupath.getImageData() != null) {
                var currentEntry = project.getEntry(qupath.getImageData());
                if (currentEntry != null) {
                    currentImgName = currentEntry.getImageName();
                }
            }

            ImageSelectionPane selectionPane = new ImageSelectionPane(
                    (Stage) batchButton.getScene().getWindow(), allImageNames, currentImgName);
            List<String> picked = selectionPane.showAndWait();
            if (picked == null) {
                return;
            }

            batchHolder[0] = new ArrayList<>(picked);
            batchButton.setText(picked.isEmpty()
                    ? "Apply to which images..."
                    : "Apply to which images... (" + picked.size() + ")");
        });

        HBox ruleButtons = new HBox(8, saveRuleButton, deleteRuleButton);
        HBox actionButtons = new HBox(8, applyButton, batchButton, closeButton);

        VBox root = new VBox(8,
                titleLabel,
                savedLabel,
                savedRulesCombo,
                nameLabel,
                ruleNameField,
                markersLabel,
                markerScroll,
                expressionPreviewLabel,
                expressionPreviewField,
                helperLabel,
                ruleButtons,
                resultLabel,
                actionButtons);
        root.setPadding(new Insets(12));

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(qupath.getStage());
        stage.setTitle("Composite Classification");
        stage.setScene(new Scene(root, 620, 520));
        stage.setResizable(true);
        stage.showAndWait();
    }

    private List<String> loadTrainedMarkerNames(qupath.lib.projects.Project<?> project) throws IOException {
        Map<String, String> registry = BinaryClassifierRegistry.load(project);
        List<String> trainedMarkers = new ArrayList<>();
        for (String markerName : registry.keySet()) {
            if (isMarkerTrained(project, markerName)) {
                trainedMarkers.add(markerName);
            }
        }
        Collections.sort(trainedMarkers, String.CASE_INSENSITIVE_ORDER);
        return trainedMarkers;
    }

    private boolean isMarkerTrained(qupath.lib.projects.Project<?> project, String markerName) {
        String sanitized = BinaryClassifierRegistry.sanitizeMarkerName(markerName);
        try {
            ProjectStateManager.SavedState state = ProjectStateManager.loadBinaryState(project, sanitized);
            return state != null
                    && state.xgboostModelBase64 != null
                    && state.featureNames != null
                    && !state.featureNames.isEmpty()
                    && state.classNames != null
                    && state.classNames.size() >= 2;
        } catch (IOException ex) {
            logger.warn("Failed to check marker state for '{}': {}", sanitized, ex.getMessage());
            return false;
        }
    }

    private List<String> applyRuleToRows(CompositeClassificationRule rule,
                                         Map<String, MarkerRowControl> rowsByMarkerLower) {
        for (MarkerRowControl row : rowsByMarkerLower.values()) {
            row.setSelection(RowPolarity.IGNORE);
        }

        List<String> unknownMarkers = new ArrayList<>();
        if (rule == null) {
            return unknownMarkers;
        }

        for (CompositeClassificationRule.MarkerCondition condition : rule.conditions()) {
            MarkerRowControl row = rowsByMarkerLower.get(condition.markerName().toLowerCase(Locale.ROOT));
            if (row == null) {
                unknownMarkers.add(condition.markerName());
                continue;
            }
            row.setFromCondition(condition);
        }
        return unknownMarkers;
    }

    private String buildExpressionPreview(Collection<MarkerRowControl> rows) {
        List<CompositeClassificationRule.MarkerCondition> conditions = new ArrayList<>();
        for (MarkerRowControl row : rows) {
            CompositeClassificationRule.MarkerCondition condition = row.toConditionOrNull();
            if (condition != null) {
                conditions.add(condition);
            }
        }

        if (conditions.isEmpty()) {
            return "(no marker selected)";
        }

        try {
            return CompositeClassificationRule.formatExpression(conditions);
        } catch (IllegalArgumentException ex) {
            return "(invalid selection: " + ex.getMessage() + ")";
        }
    }

    private CompositeClassificationRule buildRuleFromRows(TextField ruleNameField,
                                                          Collection<MarkerRowControl> rows,
                                                          Label feedbackLabel,
                                                          boolean showDialogs) {
        String name = ruleNameField.getText() != null ? ruleNameField.getText().trim() : "";
        if (name.isBlank()) {
            String msg = "Rule name is required.";
            if (feedbackLabel != null) {
                feedbackLabel.setText(msg);
            }
            if (showDialogs) {
                Dialogs.showErrorMessage("Composite Classification", msg);
            }
            return null;
        }

        List<CompositeClassificationRule.MarkerCondition> conditions = new ArrayList<>();
        for (MarkerRowControl row : rows) {
            CompositeClassificationRule.MarkerCondition condition = row.toConditionOrNull();
            if (condition != null) {
                conditions.add(condition);
            }
        }

        if (conditions.isEmpty()) {
            String msg = "Select at least one marker as + or - before saving or applying.";
            if (feedbackLabel != null) {
                feedbackLabel.setText(msg);
            }
            if (showDialogs) {
                Dialogs.showErrorMessage("Composite Classification", msg);
            }
            return null;
        }

        try {
            return CompositeClassificationRule.of(name, conditions);
        } catch (IllegalArgumentException ex) {
            String msg = "Invalid rule: " + ex.getMessage();
            if (feedbackLabel != null) {
                feedbackLabel.setText(msg);
            }
            if (showDialogs) {
                Dialogs.showErrorMessage("Composite Classification", msg);
            }
            return null;
        }
    }

    private boolean persistRules(qupath.lib.projects.Project<?> project,
                                 Iterable<CompositeClassificationRule> rules,
                                 Label feedbackLabel) {
        List<CompositeClassificationRule> snapshot = new ArrayList<>();
        for (CompositeClassificationRule rule : rules) {
            snapshot.add(rule);
        }

        try {
            ProjectStateManager.saveCompositeRules(project, snapshot);
            return true;
        } catch (IOException ex) {
            logger.warn("Failed to save composite rules: {}", ex.getMessage());
            String msg = "Failed to save composite rules: " + ex.getMessage();
            if (feedbackLabel != null) {
                feedbackLabel.setText(msg);
            }
            Dialogs.showErrorMessage("Composite Classification", msg);
            return false;
        }
    }

    private Stage buildProgressStage(Stage owner, CompositeClassifier classifier) {
        Stage stage = new Stage();
        stage.initModality(Modality.NONE);
        stage.initOwner(owner);
        stage.setTitle("Composite Classification");

        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(350);
        bar.progressProperty().bind(classifier.progressProperty());

        Label statusLabel = new Label("Starting...");
        statusLabel.textProperty().bind(classifier.statusProperty());

        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(100);

        VBox root = new VBox(8, statusLabel, bar, logArea);
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root, 420, 220));
        return stage;
    }
}