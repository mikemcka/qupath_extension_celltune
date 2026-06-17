package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellFeatureExtractor;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectFilter;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import smile.clustering.KMeans;

/**
 * Project-wide cell clustering: fit one k-means model on a sample pooled across
 * the selected images, then assign every cell in every image to its nearest
 * cohort centroid and write that as a QuPath classification. Because a single
 * model is fit once and applied everywhere, cluster identity is consistent
 * across the cohort (cluster 3 = the same phenotype in every image).
 * <p>
 * Memory-safe by construction: only a bounded sample is held in memory to fit
 * the model; the assignment pass streams image-by-image, never holding the whole
 * project at once. Writes classifications, not CellTune training labels.
 */
public class ProjectClusteringDialog {

    private static final Logger logger =
            LoggerFactory.getLogger(ProjectClusteringDialog.class);

    private static final String SKIP_CLASS = "— skip —";

    private final QuPathGUI qupath;
    private final List<String> markerFeatures;
    private final List<String> allImageNames;
    private final String currentImageName;

    private final Stage stage;
    private final Label imagesLabel;
    private final Spinner<Integer> kSpinner;
    private final Spinner<Integer> sampleSpinner;
    private final Button selectImagesBtn;
    private final Button runBtn;
    private final Button showPlotBtn;
    private final Button assignBtn;
    private final ProgressBar progress;
    private final TextArea logArea;

    private List<String> selectedImages;
    private volatile boolean busy = false;

    // ── Fit result (set by the clustering pass, consumed by the assign pass) ──
    private double[] featMean;     // per-marker mean over the sample (raw)
    private double[] featSd;       // per-marker sd over the sample (raw)
    private double[][] centroids;  // [k][nMarkers] in standardized space
    private int[] sampleCounts;    // sample cells per cluster
    private double[][] sampleStd;  // standardized sample matrix (for the preview)
    private int[] sampleLabels;    // k-means cluster per sample row (for preview)
    private int fittedK = 0;

    public ProjectClusteringDialog(QuPathGUI qupath, List<String> markerFeatures,
                                   List<String> allImageNames,
                                   String currentImageName) {
        this.qupath = qupath;
        this.markerFeatures = List.copyOf(markerFeatures);
        this.allImageNames = List.copyOf(allImageNames);
        this.currentImageName = currentImageName;
        this.selectedImages = new ArrayList<>(allImageNames);

        stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);
        stage.setTitle("CellTune — Project Cell Clustering");

        imagesLabel = new Label();
        selectImagesBtn = new Button("Select Images…");
        selectImagesBtn.setOnAction(e -> selectImages());

        kSpinner = new Spinner<>(2, 50, 8);
        kSpinner.setEditable(true);
        kSpinner.setPrefWidth(80);

        sampleSpinner = new Spinner<>(1000, 5_000_000, 50_000, 10_000);
        sampleSpinner.setEditable(true);
        sampleSpinner.setPrefWidth(110);
        sampleSpinner.setTooltip(new javafx.scene.control.Tooltip(
                "Cells pooled to FIT k-means (default 50,000, drawn evenly across "
                + "images). EVERY cell is still classified in the assign pass — "
                + "50k is usually plenty to define stable centroids. Raise it to "
                + "fit on more cells (slower); it does not limit how many cells "
                + "get a class."));

        runBtn = new Button("Run Clustering");
        runBtn.setOnAction(e -> runClustering());

        showPlotBtn = new Button("Show Plot…");
        showPlotBtn.setDisable(true);
        showPlotBtn.setTooltip(new javafx.scene.control.Tooltip(
                "Show a PCA/UMAP scatter of the pooled sample, coloured by "
                + "cluster. Visualisation only — clustering uses all markers."));
        showPlotBtn.setOnAction(e -> showPreview());

        assignBtn = new Button("Assign Classes…");
        assignBtn.setDisable(true);
        assignBtn.setOnAction(e -> assignClasses());

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.close());

        progress = new ProgressBar(0);
        progress.setPrefWidth(260);
        progress.setVisible(false);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(12);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");

        HBox imagesRow = new HBox(8, new Label("Images:"), imagesLabel,
                new Region(), selectImagesBtn);
        imagesRow.setAlignment(Pos.CENTER_LEFT);

        HBox paramRow = new HBox(8,
                new Label("Clusters (k):"), kSpinner,
                new Label("Sample size:"), sampleSpinner);
        paramRow.setAlignment(Pos.CENTER_LEFT);

        Label markersInfo = new Label(String.format(
                "Clustering on %d marker measurement(s).", markerFeatures.size()));
        markersInfo.setStyle("-fx-text-fill: -fx-text-background-color; "
                + "-fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttonRow = new HBox(8, runBtn, showPlotBtn, assignBtn, progress,
                spacer, closeBtn);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10,
                imagesRow, paramRow, markersInfo, new Separator(),
                buttonRow, new Label("Log:"), logArea);
        root.setPadding(new Insets(12));
        VBox.setVgrow(logArea, Priority.ALWAYS);

        stage.setScene(new Scene(root, 560, 460));
        updateImagesLabel();
    }

    public void show() {
        stage.show();
    }

    // ── UI helpers ──────────────────────────────────────────────────────────

    private void updateImagesLabel() {
        imagesLabel.setText(String.format("%d of %d selected",
                selectedImages.size(), allImageNames.size()));
    }

    private void selectImages() {
        var pane = new ImageSelectionPane(
                stage, allImageNames, currentImageName);
        List<String> chosen = pane.showAndWait();
        if (chosen != null) {
            selectedImages = chosen;
            updateImagesLabel();
        }
    }

    private void log(String msg) {
        Platform.runLater(() -> logArea.appendText(msg + "\n"));
    }

    private void setBusy(boolean b) {
        busy = b;
        selectImagesBtn.setDisable(b);
        kSpinner.setDisable(b);
        sampleSpinner.setDisable(b);
        runBtn.setDisable(b);
        // assignBtn / showPlotBtn stay disabled until a fit exists; when busy
        // they are disabled, then re-enabled after a successful fit.
        if (b) {
            assignBtn.setDisable(true);
            showPlotBtn.setDisable(true);
        }
        progress.setVisible(b);
    }

    private Color clusterColor(int c, int k) {
        return Color.hsb(360.0 * (c % k) / k, 0.72, 0.88);
    }

    // ── Pass 1: sample + fit one k-means across the cohort ────────────────────

    private void runClustering() {
        if (busy) {
            return;
        }
        if (selectedImages.isEmpty()) {
            Dialogs.showWarningNotification("CellTune", "No images selected.");
            return;
        }
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage("CellTune", "No project is open.");
            return;
        }
        final int k = kSpinner.getValue();
        final int sampleCap = sampleSpinner.getValue();
        final List<String> images = new ArrayList<>(selectedImages);

        setBusy(true);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        log(String.format("Sampling up to %,d cells across %d image(s)…",
                sampleCap, images.size()));

        new Thread(() -> {
            try {
                Map<String, ProjectImageEntry<BufferedImage>> byName =
                        entriesByName(project);
                int nMarkers = markerFeatures.size();
                int perImage = Math.max(1, sampleCap / Math.max(1, images.size()));
                Random rng = new Random(42);
                List<float[]> sample = new ArrayList<>();
                var extractor = new CellFeatureExtractor(markerFeatures);

                for (String name : images) {
                    ProjectImageEntry<BufferedImage> entry = byName.get(name);
                    if (entry == null) {
                        log("[" + name + "] not in project — skipped");
                        continue;
                    }
                    ImageData<BufferedImage> imageData = entry.readImageData();
                    if (imageData == null) {
                        log("[" + name + "] could not load — skipped");
                        continue;
                    }
                    List<PathObject> cells = detections(imageData);
                    int n = cells.size();
                    if (n == 0) {
                        log("[" + name + "] no detections — skipped");
                        continue;
                    }
                    float[] flat = extractor.extractMatrix(cells);
                    int take = Math.min(perImage, n);
                    int[] pick = sampleIndices(n, take, rng);
                    for (int idx : pick) {
                        float[] row = new float[nMarkers];
                        System.arraycopy(flat, idx * nMarkers, row, 0, nMarkers);
                        sample.add(row);
                    }
                    log(String.format("[%s] sampled %,d of %,d cells", name, take, n));
                }

                int m = sample.size();
                if (m < k) {
                    log("Not enough sampled cells (" + m + ") for k=" + k + ".");
                    Platform.runLater(() -> {
                        setBusy(false);
                        progress.setProgress(0);
                    });
                    return;
                }

                // Standardize the sample, remembering mean/sd to reuse at assign.
                double[] mean = new double[nMarkers];
                double[] sd = new double[nMarkers];
                double[][] std = standardize(sample, mean, sd);

                log(String.format("Fitting k-means (k=%d) on %,d cells…", k, m));
                KMeans km = KMeans.fit(std, k);

                double[][] cents = new double[k][nMarkers];
                int[] counts = new int[k];
                for (int i = 0; i < m; i++) {
                    int c = km.y[i];
                    counts[c]++;
                    for (int j = 0; j < nMarkers; j++) {
                        cents[c][j] += std[i][j];
                    }
                }
                for (int c = 0; c < k; c++) {
                    if (counts[c] > 0) {
                        for (int j = 0; j < nMarkers; j++) {
                            cents[c][j] /= counts[c];
                        }
                    }
                }

                this.featMean = mean;
                this.featSd = sd;
                this.centroids = cents;
                this.sampleCounts = counts;
                this.sampleStd = std;
                this.sampleLabels = km.y;
                this.fittedK = k;

                StringBuilder sizes = new StringBuilder("Cluster sizes (sample): ");
                for (int c = 0; c < k; c++) {
                    sizes.append(String.format("C%d=%,d ", c, counts[c]));
                }
                log(sizes.toString().trim());
                log("Clustering done. Use “Assign Classes…” to map clusters to "
                        + "classes and write them across the cohort.");

                Platform.runLater(() -> {
                    setBusy(false);
                    progress.setProgress(0);
                    assignBtn.setDisable(false);
                    showPlotBtn.setDisable(false);
                });
            } catch (Throwable t) {
                logger.error("Project clustering failed", t);
                log("ERROR: " + t.getMessage());
                Platform.runLater(() -> {
                    setBusy(false);
                    progress.setProgress(0);
                });
            }
        }, "CellTune-ProjectClustering").start();
    }

    /** Opens a PCA/UMAP scatter preview of the pooled sample, coloured by cluster. */
    private void showPreview() {
        if (fittedK == 0 || sampleStd == null) {
            return;
        }
        new ClusterPreviewWindow(stage, sampleStd, sampleLabels, fittedK).show();
    }

    // ── Pass 2: map clusters → classes, then assign + save per image ──────────

    private void assignClasses() {
        if (busy || fittedK == 0) {
            return;
        }
        Map<Integer, PathClass> mapping = showMappingDialog();
        if (mapping == null || mapping.isEmpty()) {
            return;
        }

        boolean ok = Dialogs.showConfirmDialog("Assign across project",
                String.format("Assign %d cluster(s) as classifications to every "
                        + "matching cell across %d image(s)? Each image is saved. "
                        + "This replaces existing classes on the assigned cells.",
                        mapping.size(), selectedImages.size()));
        if (!ok) {
            return;
        }

        // Register new classes up front (FX thread).
        var available = qupath.getAvailablePathClasses();
        for (PathClass pc : mapping.values()) {
            if (pc != null && !available.contains(pc)) {
                available.add(pc);
            }
        }

        final Project<BufferedImage> project = qupath.getProject();
        final List<String> images = new ArrayList<>(selectedImages);
        final double[] mean = featMean;
        final double[] sd = featSd;
        final double[][] cents = centroids;

        setBusy(true);
        progress.setProgress(0);
        log("Assigning classifications across " + images.size() + " image(s)…");

        new Thread(() -> {
            int nMarkers = markerFeatures.size();
            var extractor = new CellFeatureExtractor(markerFeatures);
            Map<String, ProjectImageEntry<BufferedImage>> byName =
                    entriesByName(project);
            ImageData<BufferedImage> openData = qupath.getImageData();
            ProjectImageEntry<BufferedImage> openEntry =
                    (openData != null) ? project.getEntry(openData) : null;
            String openName = (openEntry != null) ? openEntry.getImageName() : null;

            long totalAssigned = 0;
            int done = 0;
            try {
                for (String name : images) {
                    ProjectImageEntry<BufferedImage> entry = byName.get(name);
                    if (entry == null) {
                        log("[" + name + "] not in project — skipped");
                        done++;
                        continue;
                    }
                    boolean isOpen = name.equals(openName);
                    ImageData<BufferedImage> imageData =
                            isOpen ? openData : entry.readImageData();
                    if (imageData == null) {
                        log("[" + name + "] could not load — skipped");
                        done++;
                        continue;
                    }

                    List<PathObject> cells = detections(imageData);
                    int n = cells.size();
                    if (n == 0) {
                        log("[" + name + "] no detections — skipped");
                        done++;
                        continue;
                    }
                    float[] flat = extractor.extractMatrix(cells);

                    List<PathObject> changedObjs = new ArrayList<>();
                    List<PathClass> changedCls = new ArrayList<>();
                    double[] z = new double[nMarkers];
                    for (int i = 0; i < n; i++) {
                        int off = i * nMarkers;
                        for (int j = 0; j < nMarkers; j++) {
                            double v = flat[off + j];
                            z[j] = sd[j] < 1e-9 ? 0.0 : (v - mean[j]) / sd[j];
                        }
                        int c = nearestCentroid(z, cents);
                        PathClass pc = mapping.get(c);
                        if (pc != null) {
                            changedObjs.add(cells.get(i));
                            changedCls.add(pc);
                        }
                    }

                    applyClasses(imageData, changedObjs, changedCls, isOpen);
                    entry.saveImageData(imageData);
                    totalAssigned += changedObjs.size();
                    log(String.format("[%s] assigned %,d of %,d cells",
                            name, changedObjs.size(), n));

                    done++;
                    final double frac = done / (double) images.size();
                    Platform.runLater(() -> progress.setProgress(frac));
                }
                final long fTotal = totalAssigned;
                log(String.format("Done — assigned %,d cell(s) across %d image(s).",
                        fTotal, images.size()));
            } catch (Throwable t) {
                logger.error("Project assignment failed", t);
                log("ERROR: " + t.getMessage());
            } finally {
                Platform.runLater(() -> {
                    setBusy(false);
                    assignBtn.setDisable(false);
                    progress.setProgress(0);
                });
            }
        }, "CellTune-ProjectAssign").start();
    }

    /** Applies classes; for the open image this marshals to the FX thread. */
    private void applyClasses(ImageData<BufferedImage> imageData,
                              List<PathObject> objs, List<PathClass> classes,
                              boolean isOpen) {
        Runnable apply = () -> {
            for (int i = 0; i < objs.size(); i++) {
                objs.get(i).setPathClass(classes.get(i));
            }
            imageData.getHierarchy().fireObjectClassificationsChangedEvent(this, objs);
        };
        if (isOpen) {
            // Live hierarchy: mutate on the FX thread and wait for completion.
            if (Platform.isFxApplicationThread()) {
                apply.run();
            } else {
                var latch = new java.util.concurrent.CountDownLatch(1);
                Platform.runLater(() -> {
                    try {
                        apply.run();
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    latch.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        } else {
            // Detached copy: safe to mutate off-thread.
            apply.run();
        }
    }

    // ── Mapping dialog (per-cluster centroid heatmap + class dropdowns) ───────

    private Map<Integer, PathClass> showMappingDialog() {
        int k = fittedK;
        int nMarkers = markerFeatures.size();

        // Largest |z| across centroids → symmetric colour scale.
        double maxAbs = 1e-9;
        for (int c = 0; c < k; c++) {
            for (int j = 0; j < nMarkers; j++) {
                maxAbs = Math.max(maxAbs, Math.abs(centroids[c][j]));
            }
        }

        List<String> classNames = new ArrayList<>();
        for (PathClass pc : qupath.getAvailablePathClasses()) {
            if (pc != null && pc.getName() != null) {
                classNames.add(pc.toString());
            }
        }

        GridPane grid = new GridPane();
        grid.setHgap(3);
        grid.setVgap(4);
        grid.setPadding(new Insets(4));

        // Header: marker names (rotated would be nicer; kept horizontal + small).
        grid.add(boldLabel("Cluster"), 0, 0);
        grid.add(boldLabel("Cells"), 1, 0);
        for (int j = 0; j < nMarkers; j++) {
            Label h = new Label(markerFeatures.get(j));
            h.setStyle("-fx-font-size: 9px;");
            h.setRotate(-90);
            var box = new HBox(h);
            box.setAlignment(Pos.BOTTOM_CENTER);
            box.setMinHeight(64);
            grid.add(box, 2 + j, 0);
        }
        grid.add(boldLabel("Assign to class"), 2 + nMarkers, 0);

        Map<Integer, ComboBox<String>> selectors = new LinkedHashMap<>();
        for (int c = 0; c < k; c++) {
            int row = c + 1;
            Rectangle swatch = new Rectangle(12, 12, clusterColor(c, k));
            swatch.setStroke(Color.gray(0.4));
            HBox label = new HBox(5, swatch, new Label("Cluster " + c));
            label.setAlignment(Pos.CENTER_LEFT);
            grid.add(label, 0, row);
            grid.add(new Label(String.format("%,d", sampleCounts[c])), 1, row);

            for (int j = 0; j < nMarkers; j++) {
                Rectangle cell = new Rectangle(16, 16, heatColor(centroids[c][j], maxAbs));
                cell.setStroke(Color.gray(0.85));
                javafx.scene.control.Tooltip.install(cell,
                        new javafx.scene.control.Tooltip(String.format(
                                "Cluster %d · %s: z=%.2f",
                                c, markerFeatures.get(j), centroids[c][j])));
                grid.add(cell, 2 + j, row);
            }

            ComboBox<String> combo = new ComboBox<>();
            combo.setEditable(true);
            combo.getItems().add(SKIP_CLASS);
            combo.getItems().addAll(classNames);
            combo.setValue(SKIP_CLASS);
            combo.setPrefWidth(170);
            selectors.put(c, combo);
            grid.add(combo, 2 + nMarkers, row);
        }

        Label legend = new Label(
                "Heatmap = per-cluster mean marker intensity (z-scored): "
                + "red = high, blue = low. Name each cluster from its high markers, "
                + "or leave “" + SKIP_CLASS + "”.");
        legend.setWrapText(true);
        legend.setMaxWidth(880);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(false);
        scroll.setPrefViewportHeight(Math.min(420, 90 + k * 26));
        scroll.setPrefViewportWidth(880); // bound width; scroll horizontally
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        VBox content = new VBox(10, scroll, new Separator(), legend);
        content.setPadding(new Insets(6));

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.initOwner(stage);
        dlg.setTitle("Assign Cohort Clusters to Classes");
        dlg.setResizable(true);
        dlg.getDialogPane().setPrefWidth(940);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);

        Optional<ButtonType> res = dlg.showAndWait();
        if (res.isEmpty() || res.get() != ButtonType.APPLY) {
            return null;
        }

        Map<Integer, PathClass> mapping = new LinkedHashMap<>();
        for (var e : selectors.entrySet()) {
            String v = e.getValue().getValue();
            if (v == null) {
                continue;
            }
            v = v.trim();
            if (v.isEmpty() || v.equals(SKIP_CLASS)) {
                continue;
            }
            mapping.put(e.getKey(), PathClass.fromString(v));
        }
        return mapping;
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    /** Diverging colour: blue (low) → white (0) → red (high), scaled by maxAbs. */
    private static Color heatColor(double v, double maxAbs) {
        double t = maxAbs < 1e-9 ? 0 : Math.max(-1, Math.min(1, v / maxAbs));
        if (t >= 0) {
            return Color.color(1, 1 - t, 1 - t); // white → red
        }
        return Color.color(1 + t, 1 + t, 1); // white → blue
    }

    private Map<String, ProjectImageEntry<BufferedImage>> entriesByName(
            Project<BufferedImage> project) {
        Map<String, ProjectImageEntry<BufferedImage>> map = new LinkedHashMap<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            map.put(entry.getImageName(), entry);
        }
        return map;
    }

    private static List<PathObject> detections(ImageData<BufferedImage> imageData) {
        return imageData.getHierarchy().getObjects(null, PathObject.class).stream()
                .filter(PathObjectFilter.DETECTIONS_ALL)
                .toList();
    }

    /** Random distinct indices in [0, n) — partial Fisher–Yates. */
    private static int[] sampleIndices(int n, int count, Random rng) {
        if (count >= n) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) {
                all[i] = i;
            }
            return all;
        }
        int[] pool = new int[n];
        for (int i = 0; i < n; i++) {
            pool[i] = i;
        }
        for (int i = 0; i < count; i++) {
            int j = i + rng.nextInt(n - i);
            int tmp = pool[i];
            pool[i] = pool[j];
            pool[j] = tmp;
        }
        int[] out = new int[count];
        System.arraycopy(pool, 0, out, 0, count);
        return out;
    }

    /** Z-scores columns of the sample, writing the per-column mean/sd into args. */
    private static double[][] standardize(List<float[]> rows, double[] mean,
                                          double[] sd) {
        int m = rows.size();
        int p = mean.length;
        for (int j = 0; j < p; j++) {
            double sum = 0;
            for (float[] row : rows) {
                sum += row[j];
            }
            mean[j] = sum / Math.max(1, m);
            double var = 0;
            for (float[] row : rows) {
                double d = row[j] - mean[j];
                var += d * d;
            }
            sd[j] = Math.sqrt(var / Math.max(1, m));
        }
        double[][] out = new double[m][p];
        for (int i = 0; i < m; i++) {
            float[] row = rows.get(i);
            for (int j = 0; j < p; j++) {
                out[i][j] = sd[j] < 1e-9 ? 0.0 : (row[j] - mean[j]) / sd[j];
            }
        }
        return out;
    }

    private static int nearestCentroid(double[] z, double[][] cents) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int c = 0; c < cents.length; c++) {
            double d = 0;
            double[] cc = cents[c];
            for (int j = 0; j < z.length; j++) {
                double diff = z[j] - cc[j];
                d += diff * diff;
            }
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }
}
