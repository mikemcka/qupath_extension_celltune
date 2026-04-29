package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.CompositeClassificationRule;
import qupath.ext.celltune.classifier.CompositeClassifier;
import qupath.ext.celltune.io.ProjectStateManager;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modal dialog for named composite rule management and execution.
 */
public class CompositeClassificationDialog {

    private static final Logger logger = LoggerFactory.getLogger(CompositeClassificationDialog.class);

    private final QuPathGUI qupath;

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

        Label titleLabel = new Label("Composite Classification Rules");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        Label savedLabel = new Label("Saved rules:");
        ComboBox<String> savedRulesCombo = new ComboBox<>();
        savedRulesCombo.setPrefWidth(360);

        Label nameLabel = new Label("Rule name:");
        TextField ruleNameField = new TextField();
        ruleNameField.setPromptText("CD4 T cell");

        Label expressionLabel = new Label("Rule expression (marker+polarity pairs):");
        TextField expressionField = new TextField();
        expressionField.setPromptText("CD4+:CD3+:CD20-");

        Label helperLabel = new Label("Use ':' between conditions. Each marker must end with '+' or '-'.");
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

        Runnable refreshRuleCombo = () -> {
            savedRulesCombo.setItems(FXCollections.observableArrayList(rulesByName.keySet()));
            deleteRuleButton.setDisable(rulesByName.isEmpty());
        };
        refreshRuleCombo.run();

        if (!rulesByName.isEmpty()) {
            String first = rulesByName.keySet().iterator().next();
            savedRulesCombo.getSelectionModel().select(first);
            CompositeClassificationRule rule = rulesByName.get(first);
            ruleNameField.setText(rule.name());
            expressionField.setText(rule.expression());
        }

        savedRulesCombo.setOnAction(e -> {
            String selectedName = savedRulesCombo.getValue();
            CompositeClassificationRule selectedRule = selectedName != null ? rulesByName.get(selectedName) : null;
            if (selectedRule != null) {
                ruleNameField.setText(selectedRule.name());
                expressionField.setText(selectedRule.expression());
                resultLabel.setText("Loaded rule '" + selectedRule.name() + "'.");
            }
        });

        saveRuleButton.setOnAction(e -> {
            CompositeClassificationRule parsedRule = parseRuleFromFields(ruleNameField, expressionField, resultLabel);
            if (parsedRule == null) {
                return;
            }

            rulesByName.put(parsedRule.name(), parsedRule);
            if (!persistRules(project, rulesByName.values(), resultLabel)) {
                return;
            }

            refreshRuleCombo.run();
            savedRulesCombo.getSelectionModel().select(parsedRule.name());
            resultLabel.setText("Saved rule '" + parsedRule.name() + "'.");
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
                expressionField.clear();
                savedRulesCombo.getSelectionModel().clearSelection();
            } else {
                String first = rulesByName.keySet().iterator().next();
                savedRulesCombo.getSelectionModel().select(first);
                CompositeClassificationRule remaining = rulesByName.get(first);
                ruleNameField.setText(remaining.name());
                expressionField.setText(remaining.expression());
            }
            resultLabel.setText("Deleted rule '" + selectedName + "'.");
        });

        closeButton.setOnAction(e -> {
            if (ruleNameField.getText() != null && !ruleNameField.getText().isBlank()
                    && expressionField.getText() != null && !expressionField.getText().isBlank()) {
                CompositeClassificationRule parsedRule = parseRuleFromFields(ruleNameField, expressionField, null);
                if (parsedRule != null) {
                    rulesByName.put(parsedRule.name(), parsedRule);
                    persistRules(project, rulesByName.values(), null);
                }
            }
            ((Stage) closeButton.getScene().getWindow()).close();
        });

        applyButton.setOnAction(e -> {
            var imageData = qupath.getImageData();
            if (imageData == null) {
                Dialogs.showErrorMessage("Composite Classification", "No image is open.");
                return;
            }

            CompositeClassificationRule rule = parseRuleFromFields(ruleNameField, expressionField, resultLabel);
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
                expressionLabel,
                expressionField,
                helperLabel,
                ruleButtons,
                resultLabel,
                actionButtons);
        root.setPadding(new Insets(12));

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(qupath.getStage());
        stage.setTitle("Composite Classification");
        stage.setScene(new Scene(root, 520, 370));
        stage.setResizable(true);
        stage.showAndWait();
    }

    private CompositeClassificationRule parseRuleFromFields(TextField ruleNameField,
                                                            TextField expressionField,
                                                            Label feedbackLabel) {
        String name = ruleNameField.getText() != null ? ruleNameField.getText().trim() : "";
        String expression = expressionField.getText() != null ? expressionField.getText().trim() : "";

        if (name.isBlank()) {
            if (feedbackLabel != null) {
                feedbackLabel.setText("Rule name is required.");
            }
            Dialogs.showErrorMessage("Composite Classification", "Rule name is required.");
            return null;
        }
        if (expression.isBlank()) {
            if (feedbackLabel != null) {
                feedbackLabel.setText("Rule expression is required.");
            }
            Dialogs.showErrorMessage("Composite Classification", "Rule expression is required.");
            return null;
        }

        try {
            return CompositeClassificationRule.parse(name, expression);
        } catch (IllegalArgumentException ex) {
            String msg = "Invalid rule: " + ex.getMessage();
            if (feedbackLabel != null) {
                feedbackLabel.setText(msg);
            }
            Dialogs.showErrorMessage("Composite Classification", msg);
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