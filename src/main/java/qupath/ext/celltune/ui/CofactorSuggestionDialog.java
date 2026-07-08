package qupath.ext.celltune.ui;

import java.util.List;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CofactorEstimator;
import qupath.ext.celltune.model.IntensityHeatmap;
import qupath.lib.gui.QuPathGUI;

/**
 * Non-modal tool window for the Phase 17 cofactor-suggestion workflow (COF-02/03/05/06/08). It
 * composes existing machinery into a small UI: an independent calibration picker (a reused
 * {@link FeatureSelectionPane}, defaulting to per-marker whole-cell mean intensities), a scope
 * selector (open image / whole project, defaulting to whole project), a user-triggered Run that
 * computes off the JavaFX thread, a per-feature diagnostics table, one prominent recommended global
 * cofactor, and an Apply button that hands the clamped global back to the caller via a
 * {@link DoubleConsumer} — it mutates no measurements or normalizer state.
 *
 * <p><b>Ownership (critical correction #2 / D-05).</b> The window is owned by the
 * <em>NormalizationPane's own stage</em> (passed in as {@code ownerStage}), NOT the QuPath main
 * stage. The Normalise pane is {@code APPLICATION_MODAL}; a window owned by the QuPath main stage
 * would be blocked by it, whereas a {@code Modality.NONE} child of the modal owner stays
 * interactive. Plan 17-04 wires the real owner via {@code normalizationPane.getStage()}.
 *
 * <p>All background work marshals every scene-graph touch through {@link Platform#runLater} — the
 * worker thread never touches nodes directly (CLAUDE.md UI-thread safety).
 */
public class CofactorSuggestionDialog {

    private static final Logger logger = LoggerFactory.getLogger(CofactorSuggestionDialog.class);

    /** Arcsinh cofactor spinner lower bound — the normalizer rejects a cofactor {@code <= 0}. */
    private static final double MIN_COFACTOR = 0.01;

    /** Spinner upper bound for the arcsinh cofactor. */
    private static final double MAX_COFACTOR = 10000.0;

    private final QuPathGUI qupath;
    private final List<String> allFeatureNames;
    private final DoubleConsumer applyGlobalCofactor;
    // Non-final: read inside the calibration-picker lambda, which is defined before assignment below.
    private Stage stage;

    /** Calibration set — independent of the normalize set; defaults to per-marker means (COF-02). */
    private List<String> selectedFeatures;

    /** Last successful run's result; guards Apply and is read from the FX thread. */
    private volatile CofactorEstimator.CofactorSuggestion lastResult;

    // Controls kept as fields so the Task-2 Run pipeline can drive them.
    private final ToggleGroup scopeGroup = new ToggleGroup();
    private final RadioButton openImageScope = new RadioButton("Open image");
    private final RadioButton wholeProjectScope = new RadioButton("Whole project (all cells)");
    private final Label calibCountLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button runBtn = new Button("Run");
    private final Button cancelBtn = new Button("Cancel");
    private final Button applyBtn = new Button("Apply");
    private final TextArea logArea = new TextArea();
    private final GridPane resultsGrid = new GridPane();
    private final Label globalLabel = new Label("Recommended cofactor: —");

    /**
     * Create the cofactor-suggestion window.
     *
     * @param ownerStage          the NormalizationPane's OWN stage (correction #2) — NOT the QuPath
     *                            main stage; the window is owned by it and non-modal
     * @param qupath              QuPath handle for {@code getProject()} / {@code getImageData()}
     * @param allFeatureNames     the full measurement list from the pane (the pick-from universe)
     * @param applyGlobalCofactor callback that writes the chosen global cofactor into the normalize
     *                            spinner (COF-07 handoff); invoked only on Apply, with a clamped value
     */
    public CofactorSuggestionDialog(
            Stage ownerStage, QuPathGUI qupath, List<String> allFeatureNames, DoubleConsumer applyGlobalCofactor) {
        this.qupath = qupath;
        this.allFeatureNames = allFeatureNames == null ? List.of() : List.copyOf(allFeatureNames);
        this.applyGlobalCofactor = applyGlobalCofactor;
        // Default calibration set = per-marker whole-cell mean intensities (COF-02, D-12) — no hardcoded name.
        this.selectedFeatures = IntensityHeatmap.discoverMarkerFeatures(this.allFeatureNames);

        // 1) Calibration picker — a reused FeatureSelectionPane, independent of the normalize set (COF-02).
        Button calibBtn = new Button("Calibration features…");
        updateCalibLabel();
        calibBtn.setOnAction(e -> {
            FeatureSelectionPane picker = new FeatureSelectionPane(stage, this.allFeatureNames, selectedFeatures);
            picker.setTitle("Calibration features");
            List<String> chosen = picker.showAndWait(); // transient blocking picker, owned by this stage
            if (chosen != null) {
                selectedFeatures = chosen;
                updateCalibLabel();
            }
        });
        HBox calibRow = new HBox(8, calibBtn, calibCountLabel);
        calibRow.setAlignment(Pos.CENTER_LEFT);

        // 2) Scope selector — whole project is the default (D-13); disabled with no project open.
        openImageScope.setToggleGroup(scopeGroup);
        wholeProjectScope.setToggleGroup(scopeGroup);
        wholeProjectScope.setSelected(true);
        if (qupath == null || qupath.getProject() == null) {
            wholeProjectScope.setDisable(true);
            openImageScope.setSelected(true);
        }
        VBox scopeBox = new VBox(4, new Label("Compute over:"), openImageScope, wholeProjectScope);

        // 3) Run / Cancel / progress / log / results / global / apply.
        cancelBtn.setDisable(true);
        applyBtn.setDisable(true);
        runBtn.setOnAction(e -> runSuggestion());
        HBox runRow = new HBox(8, runBtn, cancelBtn);
        runRow.setAlignment(Pos.CENTER_LEFT);

        logArea.setEditable(false);
        logArea.setPrefRowCount(5);
        logArea.setWrapText(true);

        resultsGrid.setHgap(12);
        resultsGrid.setVgap(4);
        resultsGrid.setPadding(new Insets(4));
        ScrollPane resultsScroll = new ScrollPane(resultsGrid);
        resultsScroll.setFitToWidth(true);
        resultsScroll.setPrefViewportHeight(260);

        globalLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");

        // 4) Apply — single action; writes ONLY the clamped global back via the callback. No mutation (COF-07).
        applyBtn.setOnAction(e -> {
            CofactorEstimator.CofactorSuggestion r = lastResult;
            if (r != null && applyGlobalCofactor != null) {
                double g = Math.max(MIN_COFACTOR, Math.min(MAX_COFACTOR, r.globalCofactor()));
                applyGlobalCofactor.accept(g);
                log(String.format("Applied global cofactor %.4g to the normalize spinner.", g));
            }
        });
        HBox applyRow = new HBox(8, applyBtn);
        applyRow.setAlignment(Pos.CENTER_LEFT);

        // 5) Owned NON-MODAL stage (correction #2 / D-05).
        VBox root = new VBox(
                12,
                new Label("Suggest an arcsinh cofactor from the background level of the calibration features."),
                calibRow,
                new Separator(),
                scopeBox,
                new Separator(),
                runRow,
                progressBar,
                new Label("Log:"),
                logArea,
                new Separator(),
                new Label("Per-feature diagnostics:"),
                resultsScroll,
                globalLabel,
                applyRow);
        root.setPadding(new Insets(14));

        ScrollPane rootScroll = new ScrollPane(root);
        rootScroll.setFitToWidth(true);

        stage = new Stage();
        stage.setTitle("Suggest cofactor");
        stage.initOwner(ownerStage);
        stage.initModality(Modality.NONE);
        stage.setScene(new Scene(rootScroll, 620, 720));
    }

    /** Show the (non-blocking) window — mirrors {@code NeighborhoodAnalysisDialog.show()}. */
    public void show() {
        stage.show();
    }

    private void updateCalibLabel() {
        calibCountLabel.setText(selectedFeatures.size() + " calibration feature(s) selected");
    }

    /** User-triggered Run — the off-FX compute pipeline is implemented in Task 2. */
    private void runSuggestion() {
        // Task 2: snapshot the scope + features, spawn the daemon worker, compute + render.
    }

    /** Render the per-feature table + prominent global — filled in Task 2. */
    private void renderResults(CofactorEstimator.CofactorSuggestion result) {
        resultsGrid.getChildren().clear();
        // Task 2: rebuild the header + one row per FeatureCofactor and set globalLabel.
    }

    /** Marshal a log line onto the FX thread. */
    private void log(String msg) {
        Platform.runLater(() -> logArea.appendText(msg + "\n"));
    }

    /** Bold header label (copied from ClusterAssignmentPane). */
    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }
}
