package qupath.ext.celltune.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import qupath.ext.celltune.model.FeatureNormalizer;
import qupath.ext.celltune.model.FeatureNormalizer.Transform;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dialog for configuring per-feature normalization transforms (arcsinh, sqrt).
 * <p>
 * Follows the same pattern as {@link FeatureSelectionPane}: a searchable,
 * filterable list of features with per-feature transform selection.
 * <p>
 * Recommended cofactors:
 * <ul>
 *   <li><b>1</b> — fluorescence imaging (COMET, CODEX, IF)</li>
 *   <li><b>100</b> — mass spectrometry (MIBI, IMC)</li>
 * </ul>
 */
public class NormalizationPane {

    private final Stage stage;
    private final ObservableList<NormItem> allItems = FXCollections.observableArrayList();
    private final FilteredList<NormItem> filteredItems;
    private final ListView<NormItem> listView;
    private final TextField searchField;
    private final ComboBox<String> prefixCombo;
    private final Spinner<Double> cofactorSpinner;
    private final Label countLabel;

    private boolean confirmed = false;

    /**
     * Create the normalization dialog.
     *
     * @param owner        parent stage
     * @param featureNames all available feature names (should match selected features)
     * @param existing     existing normalizer to pre-populate from (may be null)
     */
    public NormalizationPane(Stage owner, List<String> featureNames, FeatureNormalizer existing) {
        stage = new Stage();

        for (String name : featureNames) {
            Transform t = (existing != null) ? existing.getTransform(name) : Transform.NONE;
            allItems.add(new NormItem(name, t));
        }

        // Search / filter
        searchField = new TextField();
        searchField.setPromptText("Search features…");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        prefixCombo = new ComboBox<>();
        prefixCombo.getItems().add("All prefixes");
        prefixCombo.getItems().addAll(FeatureSelectionPane.discoverPrefixes(featureNames));
        prefixCombo.getSelectionModel().selectFirst();
        prefixCombo.setPrefWidth(180);

        filteredItems = new FilteredList<>(allItems, p -> true);
        searchField.textProperty().addListener((o, a, b) -> updateFilter());
        prefixCombo.valueProperty().addListener((o, a, b) -> updateFilter());

        // List view
        listView = new ListView<>(filteredItems);
        listView.setCellFactory(lv -> new NormListCell());
        listView.setPrefHeight(400);
        listView.setPrefWidth(600);
        VBox.setVgrow(listView, Priority.ALWAYS);

        // Count label
        countLabel = new Label();
        updateCount();

        // ── Cofactor row ──
        Label cofactorLabel = new Label("arcsinh cofactor:");
        cofactorSpinner = new Spinner<>(0.01, 10000.0,
                existing != null ? existing.getArcsinhCofactor() : 1.0, 1.0);
        cofactorSpinner.setEditable(true);
        cofactorSpinner.setPrefWidth(100);

        Label cofactorHint = new Label("(1 for fluorescence, 100 for mass spec)");
        cofactorHint.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");

        HBox cofactorRow = new HBox(8, cofactorLabel, cofactorSpinner, cofactorHint);
        cofactorRow.setAlignment(Pos.CENTER_LEFT);

        // ── Bulk action buttons ──
        ComboBox<Transform> bulkTransformCombo = new ComboBox<>();
        bulkTransformCombo.getItems().addAll(Transform.values());
        bulkTransformCombo.getSelectionModel().select(Transform.NONE);
        bulkTransformCombo.setPrefWidth(100);

        Button applyAllBtn = new Button("Apply to All Visible");
        applyAllBtn.setOnAction(e -> {
            Transform t = bulkTransformCombo.getValue();
            for (NormItem item : filteredItems) {
                item.setTransform(t);
            }
            listView.refresh();
            updateCount();
        });

        Button clearAllBtn = new Button("Clear All");
        clearAllBtn.setOnAction(e -> {
            for (NormItem item : allItems) {
                item.setTransform(Transform.NONE);
            }
            listView.refresh();
            updateCount();
        });

        HBox bulkRow = new HBox(6, new Label("Set:"), bulkTransformCombo, applyAllBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL), clearAllBtn);
        bulkRow.setAlignment(Pos.CENTER_LEFT);

        // ── OK / Cancel ──
        Button okBtn = new Button("OK");
        okBtn.setDefaultButton(true);
        okBtn.setPrefWidth(80);
        okBtn.setOnAction(e -> { confirmed = true; stage.close(); });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setPrefWidth(80);
        cancelBtn.setOnAction(e -> stage.close());

        HBox okCancelRow = new HBox(8, countLabel,
                new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }},
                okBtn, cancelBtn);
        okCancelRow.setAlignment(Pos.CENTER);

        // Filter row
        HBox filterRow = new HBox(6, new Label("Filter:"), searchField, prefixCombo);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, filterRow, cofactorRow, bulkRow, listView, okCancelRow);
        root.setPadding(new Insets(10));

        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Normalise Features");
        stage.setScene(new Scene(root, 650, 580));
        stage.setMinWidth(450);
        stage.setMinHeight(400);
    }

    /**
     * Show the dialog and block until the user confirms or cancels.
     *
     * @return a configured {@link FeatureNormalizer}, or null if cancelled
     */
    public FeatureNormalizer showAndWait() {
        confirmed = false;
        stage.showAndWait();
        if (!confirmed) return null;

        FeatureNormalizer normalizer = new FeatureNormalizer();
        normalizer.setArcsinhCofactor(cofactorSpinner.getValue());

        for (NormItem item : allItems) {
            if (item.getTransform() != Transform.NONE) {
                normalizer.setTransform(item.getName(), item.getTransform());
            }
        }

        return normalizer;
    }

    // ── Filter logic ────────────────────────────────────────────────────────────

    private void updateFilter() {
        String search = searchField.getText() == null ? "" : searchField.getText().toLowerCase().strip();
        String prefix = prefixCombo.getValue();
        boolean allPrefixes = "All prefixes".equals(prefix);

        filteredItems.setPredicate(item -> {
            boolean matchesSearch = search.isEmpty() || item.getName().toLowerCase().contains(search);
            boolean matchesPrefix = allPrefixes || item.getName().startsWith(prefix);
            return matchesSearch && matchesPrefix;
        });
    }

    private void updateCount() {
        long transformed = allItems.stream()
                .filter(i -> i.getTransform() != Transform.NONE)
                .count();
        countLabel.setText(transformed + " / " + allItems.size() + " normalised");
    }

    // ── Inner classes ───────────────────────────────────────────────────────────

    static class NormItem {
        private final String name;
        private Transform transform;

        NormItem(String name, Transform transform) {
            this.name = name;
            this.transform = transform;
        }

        String getName()                  { return name; }
        Transform getTransform()          { return transform; }
        void setTransform(Transform t)    { this.transform = t; }

        @Override
        public String toString() { return name; }
    }

    /** ListView cell with a feature name label and a Transform combo box. */
    private class NormListCell extends ListCell<NormItem> {
        private final HBox box = new HBox(8);
        private final Label nameLabel = new Label();
        private final ComboBox<Transform> combo = new ComboBox<>();

        NormListCell() {
            combo.getItems().addAll(Transform.values());
            combo.setPrefWidth(100);
            combo.setOnAction(e -> {
                NormItem item = getItem();
                if (item != null) {
                    item.setTransform(combo.getValue());
                    updateCount();
                }
            });
            HBox.setHgrow(nameLabel, Priority.ALWAYS);
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().addAll(nameLabel, combo);
        }

        @Override
        protected void updateItem(NormItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                nameLabel.setText(item.getName());
                combo.getSelectionModel().select(item.getTransform());
                setGraphic(box);
                setText(null);
            }
        }
    }
}
