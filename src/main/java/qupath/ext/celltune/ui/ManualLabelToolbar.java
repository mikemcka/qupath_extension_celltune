package qupath.ext.celltune.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.LabelStore;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;

import java.util.*;

/**
 * Manual Label Mode — a floating toolbar that lets users click on cells in the
 * QuPath viewer and assign class labels directly, outside of Review Mode.
 *
 * <p>When a detection cell is selected in the viewer, a status label shows its
 * ID and current class. Clicking a class button assigns that class to the cell
 * and records it in the extension's ground-truth {@link LabelStore}.
 *
 * <p>Class buttons are populated from QuPath project classes and the
 * optional CellTypeTable entries.
 *
 * <p>Layout:
 * <pre>
 *  Selected: Cell_abc (Bcell)     ● 42 labels
 *  [CD4] [CD8] [Bcell] [DC] [Macrophage] [Treg] | [All Classes ▼]
 *  [ ] Auto-advance to next detection
 * </pre>
 */
public class ManualLabelToolbar {

    private static final Logger logger = LoggerFactory.getLogger(ManualLabelToolbar.class);

    private final QuPathGUI qupath;
    private final LabelStore labelStore;
    private final Stage stage;

    // UI elements
    private final Label selectedCellLabel = new Label("Select a cell in the viewer");
    private final Label labelCountLabel = new Label("0 labels");
    private final Circle statusDot = new Circle(6, Color.WHITE);
    private final HBox classButtonBox = new HBox(4);
    private final MenuButton allClassesMenu = new MenuButton("All Classes \u25BC");
    private final CheckBox autoAdvance = new CheckBox("Auto-advance to next detection");

    // Currently selected detection
    private PathObject currentCell = null;

    // Listener reference for cleanup
    private PathObjectSelectionListener selectionListener;

    /**
     * Create and show the manual label toolbar.
     *
     * @param qupath       the QuPath GUI
     * @param labelStore   the ground-truth label store to write to
     * @param extraClasses additional class names (e.g. from CellTypeTable), may be null
     * @param owner        owner window for positioning
     */
    public ManualLabelToolbar(QuPathGUI qupath,
                              LabelStore labelStore,
                              Set<String> extraClasses,
                              Window owner) {
        this.qupath = qupath;
        this.labelStore = labelStore;

        stage = new Stage();
        stage.setTitle("Manual Label Mode");
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(true);
        stage.setResizable(true);

        // ── Status row ──────────────────────────────────────────────────────
        selectedCellLabel.setStyle("-fx-font-size: 12px;");
        statusDot.setStroke(Color.GRAY);
        labelCountLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        updateLabelCount();

        HBox statusRow = new HBox(8, selectedCellLabel, statusDot, new Region(), labelCountLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusRow.getChildren().get(2), Priority.ALWAYS);

        // ── Class buttons ───────────────────────────────────────────────────
        classButtonBox.setAlignment(Pos.CENTER_LEFT);
        populateClassButtons(extraClasses);

        allClassesMenu.setStyle("-fx-font-size: 11px;");
        populateAllClassesMenu(extraClasses);

        HBox buttonRow = new HBox(8, classButtonBox, allClassesMenu);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        // ── Auto-advance checkbox ───────────────────────────────────────────
        autoAdvance.setSelected(false);

        // ── Layout ──────────────────────────────────────────────────────────
        VBox root = new VBox(8, statusRow, buttonRow, autoAdvance);
        root.setPadding(new Insets(10));
        stage.setScene(new Scene(root));
        stage.sizeToScene();

        // ── Listen for cell selection changes ───────────────────────────────
        installSelectionListener();

        // ── Cleanup on close ────────────────────────────────────────────────
        stage.setOnHidden(e -> removeSelectionListener());

        stage.show();
    }

    /** @return the stage for external control */
    public Stage getStage() { return stage; }

    // ── Selection listener ──────────────────────────────────────────────────

    private void installSelectionListener() {
        var imageData = qupath.getImageData();
        if (imageData == null) return;

        selectionListener = (pathObjectSelected, previousObject, allSelected) -> {
            javafx.application.Platform.runLater(() -> {
                if (pathObjectSelected != null && pathObjectSelected.isDetection()) {
                    currentCell = pathObjectSelected;
                    String id = shortId(pathObjectSelected);
                    String cls = pathObjectSelected.getPathClass() != null
                            ? pathObjectSelected.getPathClass().getName() : "unlabelled";
                    selectedCellLabel.setText("Selected: " + id + " (" + cls + ")");
                    statusDot.setFill(pathObjectSelected.getPathClass() != null ? Color.LIMEGREEN : Color.WHITE);
                } else {
                    currentCell = null;
                    selectedCellLabel.setText("Select a detection cell in the viewer");
                    statusDot.setFill(Color.WHITE);
                }
            });
        };

        imageData.getHierarchy().getSelectionModel()
                .addPathObjectSelectionListener(selectionListener);
    }

    private void removeSelectionListener() {
        if (selectionListener == null) return;
        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().getSelectionModel()
                    .removePathObjectSelectionListener(selectionListener);
        }
        selectionListener = null;
    }

    // ── Class buttons ───────────────────────────────────────────────────────

    private void populateClassButtons(Set<String> extraClasses) {
        classButtonBox.getChildren().clear();
        Set<String> allNames = new LinkedHashSet<>();

        // From QuPath project
        var project = qupath.getProject();
        if (project != null) {
            for (var pc : project.getPathClasses()) {
                if (pc != null && pc.getName() != null && !pc.getName().isEmpty()) {
                    allNames.add(pc.getName());
                }
            }
        }

        // From CellTypeTable or other extra sources
        if (extraClasses != null) {
            allNames.addAll(extraClasses);
        }

        // Show up to 12 buttons inline; rest go into All Classes menu
        int count = 0;
        for (String name : allNames) {
            if (count >= 12) break;
            Button btn = new Button(name);
            btn.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
            btn.setOnAction(e -> assignLabel(name));
            classButtonBox.getChildren().add(btn);
            count++;
        }
    }

    private void populateAllClassesMenu(Set<String> extraClasses) {
        allClassesMenu.getItems().clear();
        Set<String> allNames = new LinkedHashSet<>();

        var project = qupath.getProject();
        if (project != null) {
            for (var pc : project.getPathClasses()) {
                if (pc != null && pc.getName() != null && !pc.getName().isEmpty()) {
                    allNames.add(pc.getName());
                }
            }
        }
        if (extraClasses != null) {
            allNames.addAll(extraClasses);
        }

        for (String name : allNames) {
            MenuItem item = new MenuItem(name);
            item.setOnAction(e -> assignLabel(name));
            allClassesMenu.getItems().add(item);
        }

        if (allClassesMenu.getItems().isEmpty()) {
            allClassesMenu.setDisable(true);
        }
    }

    // ── Label assignment ────────────────────────────────────────────────────

    private void assignLabel(String className) {
        if (currentCell == null) {
            return;
        }

        // Set PathClass on the cell
        currentCell.setPathClass(PathClass.fromString(className));

        // Record in ground-truth label store
        String cellId = currentCell.getID().toString();
        labelStore.setLabel(cellId, className);

        logger.info("Manual label: cell {} → {}", cellId, className);

        // Update status
        selectedCellLabel.setText("Selected: " + shortId(currentCell) + " (" + className + ")");
        statusDot.setFill(Color.LIMEGREEN);
        updateLabelCount();

        // Fire hierarchy update so overlay colours refresh
        var imageData = qupath.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().fireObjectClassificationsChangedEvent(this,
                    Collections.singleton(currentCell));
        }

        // Auto-advance: select next detection in the hierarchy
        if (autoAdvance.isSelected()) {
            advanceToNextDetection();
        }
    }

    private void advanceToNextDetection() {
        var imageData = qupath.getImageData();
        if (imageData == null || currentCell == null) return;

        var detections = new ArrayList<>(imageData.getHierarchy().getDetectionObjects());
        int idx = detections.indexOf(currentCell);
        if (idx >= 0 && idx + 1 < detections.size()) {
            PathObject next = detections.get(idx + 1);
            imageData.getHierarchy().getSelectionModel().setSelectedObject(next);

            // Centre viewer on the next cell
            var viewer = qupath.getViewer();
            if (viewer != null && next.getROI() != null) {
                viewer.setCenterPixelLocation(
                        next.getROI().getCentroidX(),
                        next.getROI().getCentroidY());
            }
        }
    }

    private void updateLabelCount() {
        labelCountLabel.setText(labelStore.size() + " labels");
    }

    private static String shortId(PathObject cell) {
        String id = cell.getID().toString();
        return id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }
}
