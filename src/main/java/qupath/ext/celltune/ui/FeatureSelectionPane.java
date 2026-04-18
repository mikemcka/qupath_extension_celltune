package qupath.ext.celltune.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
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

import java.util.*;
import java.util.stream.Collectors;

/**
 * A dialog for selecting which cell measurement features to include in ML
 * training.
 * <p>
 * Designed for large panels (COMET, MIBI, etc.) with 2000+ features per cell.
 * Provides:
 * <ul>
 *   <li>Instant search/filter box</li>
 *   <li>Select by prefix (e.g. "Cell:", "Nucleus:", "Membrane:", etc.)</li>
 *   <li>Select All / Clear All</li>
 *   <li>Counter showing selected / total</li>
 *   <li>Pre-selects all features by default; user can refine</li>
 * </ul>
 */
public class FeatureSelectionPane {

    private final Stage stage;
    private final ObservableList<FeatureItem> allFeatures = FXCollections.observableArrayList();
    private final FilteredList<FeatureItem> filteredFeatures;
    private final ListView<FeatureItem> listView;
    private final TextField searchField;
    private final ComboBox<String> prefixCombo;
    private final Label countLabel;

    private boolean confirmed = false;

    /**
     * Create a feature selection dialog.
     *
     * @param owner        parent stage
     * @param featureNames all available feature names
     * @param preSelected  features to pre-select (if null or empty, all are selected)
     */
    public FeatureSelectionPane(Stage owner, List<String> featureNames, List<String> preSelected) {
        stage = new Stage();

        // Build items
        Set<String> selected = (preSelected != null && !preSelected.isEmpty())
                ? new LinkedHashSet<>(preSelected)
                : new LinkedHashSet<>(featureNames);

        for (String name : featureNames) {
            allFeatures.add(new FeatureItem(name, selected.contains(name)));
        }

        // Search/filter
        searchField = new TextField();
        searchField.setPromptText("Search features…");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // Prefix combo
        prefixCombo = new ComboBox<>();
        prefixCombo.getItems().add("All prefixes");
        prefixCombo.getItems().addAll(discoverPrefixes(featureNames));
        prefixCombo.getSelectionModel().selectFirst();
        prefixCombo.setPrefWidth(180);

        // Filtered list
        filteredFeatures = new FilteredList<>(allFeatures, p -> true);
        searchField.textProperty().addListener((o, a, b) -> updateFilter());
        prefixCombo.valueProperty().addListener((o, a, b) -> updateFilter());

        // ListView with checkboxes
        listView = new ListView<>(filteredFeatures);
        listView.setCellFactory(lv -> new CheckBoxListCell());
        listView.setPrefHeight(400);
        listView.setPrefWidth(500);
        VBox.setVgrow(listView, Priority.ALWAYS);

        // Count label
        countLabel = new Label();
        updateCount();

        // Buttons row
        Button selectAllBtn = new Button("Select All");
        selectAllBtn.setOnAction(e -> setAllVisible(true));

        Button clearAllBtn = new Button("Clear All");
        clearAllBtn.setOnAction(e -> setAllVisible(false));

        Button selectPrefixBtn = new Button("Select Prefix");
        selectPrefixBtn.setOnAction(e -> selectCurrentPrefix());

        Button clearPrefixBtn = new Button("Clear Prefix");
        clearPrefixBtn.setOnAction(e -> clearCurrentPrefix());

        HBox btnRow = new HBox(6, selectAllBtn, clearAllBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                selectPrefixBtn, clearPrefixBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        // OK / Cancel
        Button okBtn = new Button("OK");
        okBtn.setDefaultButton(true);
        okBtn.setPrefWidth(80);
        okBtn.setOnAction(e -> { confirmed = true; stage.close(); });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setPrefWidth(80);
        cancelBtn.setOnAction(e -> stage.close());

        HBox okCancelRow = new HBox(8, countLabel, new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, okBtn, cancelBtn);
        okCancelRow.setAlignment(Pos.CENTER);

        // Filter row
        HBox filterRow = new HBox(6, new Label("Filter:"), searchField, prefixCombo);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, filterRow, btnRow, listView, okCancelRow);
        root.setPadding(new Insets(10));

        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Select Features for Training");
        stage.setScene(new Scene(root, 600, 550));
        stage.setMinWidth(400);
        stage.setMinHeight(350);
    }

    /**
     * Show the dialog and block until the user confirms or cancels.
     *
     * @return the selected feature names, or null if cancelled
     */
    public List<String> showAndWait() {
        confirmed = false;
        stage.showAndWait();
        if (!confirmed) return null;

        return allFeatures.stream()
                .filter(FeatureItem::isSelected)
                .map(FeatureItem::getName)
                .collect(Collectors.toList());
    }

    // ── Filter logic ────────────────────────────────────────────────────────────

    private void updateFilter() {
        String search = searchField.getText() == null ? "" : searchField.getText().toLowerCase().strip();
        String prefix = prefixCombo.getValue();
        boolean allPrefixes = "All prefixes".equals(prefix);

        filteredFeatures.setPredicate(item -> {
            boolean matchesSearch = search.isEmpty() || item.getName().toLowerCase().contains(search);
            boolean matchesPrefix = allPrefixes || item.getName().startsWith(prefix);
            return matchesSearch && matchesPrefix;
        });
    }

    private void setAllVisible(boolean selected) {
        for (FeatureItem item : filteredFeatures) {
            item.setSelected(selected);
        }
        listView.refresh();
        updateCount();
    }

    private void selectCurrentPrefix() {
        String prefix = prefixCombo.getValue();
        if ("All prefixes".equals(prefix)) {
            setAllVisible(true);
            return;
        }
        for (FeatureItem item : allFeatures) {
            if (item.getName().startsWith(prefix)) {
                item.setSelected(true);
            }
        }
        listView.refresh();
        updateCount();
    }

    private void clearCurrentPrefix() {
        String prefix = prefixCombo.getValue();
        if ("All prefixes".equals(prefix)) {
            setAllVisible(false);
            return;
        }
        for (FeatureItem item : allFeatures) {
            if (item.getName().startsWith(prefix)) {
                item.setSelected(false);
            }
        }
        listView.refresh();
        updateCount();
    }

    private void updateCount() {
        long selected = allFeatures.stream().filter(FeatureItem::isSelected).count();
        countLabel.setText(selected + " / " + allFeatures.size() + " selected");
    }

    // ── Discover measurement name prefixes ──────────────────────────────────────

    /**
     * Extract common prefixes from feature names.
     * <p>
     * QuPath measurement names typically look like:
     * {@code "Cell: Area"}, {@code "Nucleus: Mean"}, {@code "Membrane: CD3 mean"}, etc.
     * This extracts the part before the first ": " as the prefix group.
     */
    static List<String> discoverPrefixes(List<String> featureNames) {
        Set<String> prefixes = new LinkedHashSet<>();
        for (String name : featureNames) {
            int colonIdx = name.indexOf(": ");
            if (colonIdx > 0) {
                prefixes.add(name.substring(0, colonIdx + 2)); // include ": "
            }
        }
        List<String> sorted = new ArrayList<>(prefixes);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    // ── Inner classes ───────────────────────────────────────────────────────────

    /** A feature with a selected state. */
    static class FeatureItem {
        private final String name;
        private boolean selected;

        FeatureItem(String name, boolean selected) {
            this.name = name;
            this.selected = selected;
        }

        String getName()           { return name; }
        boolean isSelected()       { return selected; }
        void setSelected(boolean s) { this.selected = s; }

        @Override
        public String toString() { return name; }
    }

    /** ListView cell with a CheckBox. */
    private class CheckBoxListCell extends ListCell<FeatureItem> {
        private final CheckBox checkBox = new CheckBox();

        CheckBoxListCell() {
            checkBox.setOnAction(e -> {
                FeatureItem item = getItem();
                if (item != null) {
                    item.setSelected(checkBox.isSelected());
                    updateCount();
                }
            });
        }

        @Override
        protected void updateItem(FeatureItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                checkBox.setSelected(item.isSelected());
                checkBox.setText(item.getName());
                setGraphic(checkBox);
                setText(null);
            }
        }
    }
}
