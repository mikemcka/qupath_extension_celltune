package qupath.ext.celltune.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.analysis.DistanceTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Project-wide batch dialog that runs distance-measurement computations on the
 * selected images:
 *
 * <ul>
 *   <li>Detection-to-annotation signed distances ({@link DistanceTools#detectionToAnnotationDistancesSigned})</li>
 *   <li>Cross-class centroid distances ({@link DistanceTools#detectionCentroidDistances})</li>
 *   <li>Same-class nearest-neighbour centroid distances (computed here, excludes self)</li>
 * </ul>
 *
 * <p>If the user supplies a pixel size (µm/pixel) it temporarily overrides each
 * image's calibration so the resulting measurements are written in microns.
 * The override is reverted after the run unless "Persist…" is checked.
 *
 * <p>Images are processed in parallel via a fixed thread pool capped at
 * {@code min(selected, max(1, min(4, cores/2)))} — matching the extension's
 * existing I/O-bound parallelism pattern.
 */
public class DistanceMeasurementsDialog {

    private static final Logger logger = LoggerFactory.getLogger(DistanceMeasurementsDialog.class);

    private final QuPathGUI qupath;
    private final Stage stage;

    private final Map<String, CheckBox> imageCheckBoxes = new LinkedHashMap<>();
    private final CheckBox annotationDistCheck = new CheckBox(
            "Detection-to-annotation signed distances");
    private final CheckBox crossClassCheck = new CheckBox(
            "Cross-class centroid distances");
    private final CheckBox sameClassCheck = new CheckBox(
            "Same-class nearest-neighbour distances (excludes self)");
    private final TextField pixelSizeField = new TextField();
    private final CheckBox updateCalibrationCheck = new CheckBox(
            "Persist this pixel size to each image's calibration on save");

    private final TextArea logArea = new TextArea();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Configure and click Apply.");
    private final Button applyBtn = new Button("Apply");
    private final Button closeBtn = new Button("Close");

    public DistanceMeasurementsDialog(QuPathGUI qupath) {
        this.qupath = qupath;
        this.stage = buildStage();
    }

    public void show() {
        stage.show();
    }

    // ── UI ─────────────────────────────────────────────────────────────────────

    private Stage buildStage() {
        var project = qupath.getProject();

        // Resolve current image + its existing pixel calibration (used as default).
        String currentImageName = null;
        Double defaultPixelSize = null;
        var liveImageData = qupath.getImageData();
        if (liveImageData != null) {
            if (project != null) {
                var entry = project.getEntry(liveImageData);
                if (entry != null) currentImageName = entry.getImageName();
            }
            try {
                var cal = liveImageData.getServer().getPixelCalibration();
                if (cal.hasPixelSizeMicrons()) {
                    defaultPixelSize = cal.getPixelWidthMicrons();
                }
            } catch (Exception ignored) {}
        }
        final String currentImageNameFinal = currentImageName;

        // ── Image checklist ──
        VBox imageBox = new VBox(4);
        imageBox.setPadding(new Insets(4));
        if (project != null) {
            @SuppressWarnings("unchecked")
            var entries = (List<ProjectImageEntry<BufferedImage>>) (List<?>) project.getImageList();
            for (var entry : entries) {
                String name = entry.getImageName();
                if (name == null) continue;
                CheckBox cb = new CheckBox(name);
                cb.setSelected(true);
                imageCheckBoxes.put(name, cb);
                imageBox.getChildren().add(cb);
            }
        }
        if (imageCheckBoxes.isEmpty()) {
            imageBox.getChildren().add(new Label("No project images found."));
        }

        ScrollPane imageScroll = new ScrollPane(imageBox);
        imageScroll.setFitToWidth(true);
        imageScroll.setPrefHeight(180);
        imageScroll.setStyle("-fx-border-color: #ccc;");

        Button selectAllImgs = new Button("All");
        Button selectNoneImgs = new Button("None");
        Button selectCurrentImg = new Button("Current only");
        selectAllImgs.setOnAction(e -> imageCheckBoxes.values().forEach(cb -> cb.setSelected(true)));
        selectNoneImgs.setOnAction(e -> imageCheckBoxes.values().forEach(cb -> cb.setSelected(false)));
        selectCurrentImg.setOnAction(e -> imageCheckBoxes.forEach((n, cb) ->
                cb.setSelected(n.equals(currentImageNameFinal))));
        HBox imgButtons = new HBox(6, new Label("Images:"), selectAllImgs, selectNoneImgs, selectCurrentImg);
        imgButtons.setAlignment(Pos.CENTER_LEFT);

        // ── Computation toggles ──
        annotationDistCheck.setSelected(true);
        crossClassCheck.setSelected(true);
        sameClassCheck.setSelected(true);
        annotationDistCheck.setTooltip(new Tooltip(
                "Calls QuPath's DistanceTools.detectionToAnnotationDistancesSigned.\n"
              + "Negative when the detection centroid lies inside the annotation, positive when outside."));
        crossClassCheck.setTooltip(new Tooltip(
                "Calls QuPath's DistanceTools.detectionCentroidDistances.\n"
              + "For each cell, writes the nearest centroid-to-centroid distance to a cell of every OTHER class."));
        sameClassCheck.setTooltip(new Tooltip(
                "For each cell, finds the nearest cell of the SAME class (excluding itself) and writes the distance under\n"
              + "'Distance to other <class> <unit>'. Parallelised across CPU cores per class."));

        // ── Pixel size override ──
        pixelSizeField.setPromptText("e.g. 0.5");
        pixelSizeField.setPrefColumnCount(8);
        if (defaultPixelSize != null) {
            pixelSizeField.setText(formatPixelSize(defaultPixelSize));
        }
        Label pxLabel = new Label("Pixel size:");
        HBox pxRow = new HBox(6, pxLabel, pixelSizeField, new Label("µm/pixel"));
        pxRow.setAlignment(Pos.CENTER_LEFT);
        String pxSourceMsg;
        if (defaultPixelSize != null) {
            String srcName = currentImageNameFinal != null ? "\"" + currentImageNameFinal + "\"" : "the current image";
            pxSourceMsg = "Pre-filled from " + srcName + " (" + formatPixelSize(defaultPixelSize)
                    + " µm/pixel) — edit if incorrect. ";
        } else if (liveImageData != null) {
            pxSourceMsg = "No pixel size found in the current image's metadata — enter one manually if you want µm units. ";
        } else {
            pxSourceMsg = "";
        }
        Label pxHint = new Label(pxSourceMsg
                + "Leave blank to use each image's existing calibration. If supplied, the override applies to every selected image.");
        pxHint.setWrapText(true);
        pxHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        updateCalibrationCheck.setSelected(false);
        updateCalibrationCheck.setTooltip(new Tooltip(
                "When checked, the pixel size above is saved into each image's calibration metadata,\n"
              + "so future measurements also use this scale. When unchecked, the override is reverted after the run."));

        // ── Log / progress ──
        logArea.setEditable(false);
        logArea.setPrefHeight(160);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        progressBar.setPrefWidth(Double.MAX_VALUE);

        // ── Action buttons ──
        applyBtn.setDefaultButton(true);
        applyBtn.setPrefWidth(90);
        applyBtn.setOnAction(e -> runMeasurements());
        closeBtn.setPrefWidth(90);
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(10, applyBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(4, 0, 0, 0));

        // ── Root layout ──
        VBox root = new VBox(10,
                imgButtons,
                imageScroll,
                new Separator(),
                new Label("Measurements to generate:"),
                annotationDistCheck,
                crossClassCheck,
                sameClassCheck,
                new Separator(),
                pxRow,
                pxHint,
                updateCalibrationCheck,
                new Separator(),
                statusLabel,
                progressBar,
                logArea,
                buttons);
        root.setPadding(new Insets(14));

        Stage s = new Stage();
        s.setTitle("Generate Distance Measurements");
        s.initOwner(qupath.getStage());
        s.initModality(Modality.NONE);
        s.setScene(new Scene(root, 580, 760));
        return s;
    }

    private static String formatPixelSize(double v) {
        String s = String.format(Locale.US, "%.6f", v);
        // Trim trailing zeros / dot
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ── Worker ─────────────────────────────────────────────────────────────────

    private void runMeasurements() {
        var project = qupath.getProject();
        if (project == null) {
            log("ERROR: No project open.");
            return;
        }

        var selectedImages = imageCheckBoxes.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (selectedImages.isEmpty()) {
            log("Select at least one image.");
            return;
        }

        if (!annotationDistCheck.isSelected()
                && !crossClassCheck.isSelected()
                && !sameClassCheck.isSelected()) {
            log("Select at least one measurement type.");
            return;
        }

        // Pixel size override (optional)
        Double pixelSizeOverride = null;
        String pxText = pixelSizeField.getText() == null ? "" : pixelSizeField.getText().trim();
        if (!pxText.isEmpty()) {
            try {
                pixelSizeOverride = Double.parseDouble(pxText);
                if (pixelSizeOverride <= 0 || Double.isNaN(pixelSizeOverride) || Double.isInfinite(pixelSizeOverride)) {
                    log("ERROR: Pixel size must be a positive number.");
                    return;
                }
            } catch (NumberFormatException ex) {
                log("ERROR: Pixel size must be a number (e.g. 0.5).");
                return;
            }
        }

        final boolean doAnn = annotationDistCheck.isSelected();
        final boolean doCross = crossClassCheck.isSelected();
        final boolean doSame = sameClassCheck.isSelected();
        final boolean persistCal = updateCalibrationCheck.isSelected();
        final Double pxSize = pixelSizeOverride;

        applyBtn.setDisable(true);
        progressBar.setProgress(0);
        logArea.clear();
        log("Starting on " + selectedImages.size() + " image(s)…");

        @SuppressWarnings("unchecked")
        var typedProject = (Project<BufferedImage>) (Object) project;

        Thread worker = new Thread(() -> {
            int cores = Runtime.getRuntime().availableProcessors();
            int nThreads = Math.min(selectedImages.size(),
                    Math.max(1, Math.min(4, cores / 2)));
            log("Using " + nThreads + " parallel I/O worker(s) (cores=" + cores + ").");
            ExecutorService pool = Executors.newFixedThreadPool(nThreads, r -> {
                Thread t = new Thread(r, "CellTune-Distances");
                t.setDaemon(true);
                return t;
            });
            AtomicInteger done = new AtomicInteger();
            int total = selectedImages.size();

            List<Future<?>> futures = new ArrayList<>();
            for (String imgName : selectedImages) {
                futures.add(pool.submit(() -> {
                    try {
                        processOne(typedProject, imgName, doAnn, doCross, doSame, pxSize, persistCal);
                    } catch (Exception ex) {
                        log("[" + imgName + "] ERROR: " + ex.getMessage());
                        logger.warn("Distance computation failed for {}", imgName, ex);
                    } finally {
                        int d = done.incrementAndGet();
                        Platform.runLater(() -> {
                            progressBar.setProgress((double) d / total);
                            statusLabel.setText("Processed " + d + " / " + total);
                        });
                    }
                }));
            }

            pool.shutdown();
            for (var f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }

            Platform.runLater(() -> {
                log("Done.");
                applyBtn.setDisable(false);
            });
        }, "CellTune-Distances-Coordinator");
        worker.setDaemon(true);
        worker.start();
    }

    private void processOne(Project<BufferedImage> project,
                            String imgName,
                            boolean doAnn, boolean doCross, boolean doSame,
                            Double pxSize, boolean persistCal) throws Exception {
        var entryOpt = project.getImageList().stream()
                .filter(e -> imgName.equals(e.getImageName()))
                .findFirst();
        if (entryOpt.isEmpty()) {
            log("[" + imgName + "] not found in project");
            return;
        }
        var entry = entryOpt.get();
        log("[" + imgName + "] Loading…");
        var imageData = entry.readImageData();
        if (imageData == null) {
            log("[" + imgName + "] could not load image data");
            return;
        }

        // Possibly override pixel calibration. Stash the original so we can
        // restore it after the run if the user didn't ask to persist.
        ImageServerMetadata originalMeta = null;
        if (pxSize != null) {
            try {
                var oldMeta = imageData.getServer().getMetadata();
                var oldCal = oldMeta.getPixelCalibration();
                boolean needsUpdate = !oldCal.hasPixelSizeMicrons()
                        || Math.abs(oldCal.getPixelWidthMicrons() - pxSize) > 1e-9
                        || Math.abs(oldCal.getPixelHeightMicrons() - pxSize) > 1e-9;
                if (needsUpdate) {
                    originalMeta = oldMeta;
                    var newMeta = new ImageServerMetadata.Builder(oldMeta)
                            .pixelSizeMicrons(pxSize, pxSize)
                            .build();
                    imageData.updateServerMetadata(newMeta);
                    log("[" + imgName + "] pixel size set to " + pxSize + " µm/pixel");
                }
            } catch (Exception ex) {
                log("[" + imgName + "] WARN: could not override pixel calibration (" + ex.getMessage() + "); using existing");
            }
        }

        try {
            if (doAnn) {
                log("[" + imgName + "] Annotation signed distances…");
                DistanceTools.detectionToAnnotationDistancesSigned(imageData, false);
            }
            if (doCross) {
                log("[" + imgName + "] Cross-class centroid distances…");
                DistanceTools.detectionCentroidDistances(imageData, false);
            }
            if (doSame) {
                log("[" + imgName + "] Same-class nearest-neighbour distances…");
                computeSameClassDistances(imageData,
                        msg -> log("[" + imgName + "] " + msg));
            }
        } finally {
            if (originalMeta != null && !persistCal) {
                try {
                    imageData.updateServerMetadata(originalMeta);
                } catch (Exception ex) {
                    log("[" + imgName + "] WARN: could not restore original calibration: " + ex.getMessage());
                }
            }
        }

        imageData.getHierarchy().fireHierarchyChangedEvent(this);
        entry.saveImageData(imageData);
        log("[" + imgName + "] Saved.");
    }

    /**
     * For each represented detection class, computes centroid-to-centroid
     * distance to the nearest OTHER detection in the same class. Distances are
     * scaled by the imageData's current pixel calibration (µm if calibrated,
     * pixels otherwise) — the measurement name carries the unit suffix.
     *
     * <p>Per-class loops are O(n²) but parallelised across cores. For class
     * sizes up to ~100k this completes in seconds on a multi-core machine.
     */
    private static void computeSameClassDistances(ImageData<?> imageData,
                                                  java.util.function.Consumer<String> log) {
        var hierarchy = imageData.getHierarchy();
        var cal = imageData.getServer().getPixelCalibration();
        boolean calibrated = cal.hasPixelSizeMicrons();
        double pw = calibrated ? cal.getPixelWidthMicrons() : 1.0;
        double ph = calibrated ? cal.getPixelHeightMicrons() : 1.0;
        String unit = calibrated ? "µm" : "px";

        Map<String, List<PathObject>> byClass = new LinkedHashMap<>();
        for (var cell : hierarchy.getCellObjects()) {
            var pc = cell.getPathClass();
            if (pc == null) continue;
            byClass.computeIfAbsent(pc.toString(), k -> new ArrayList<>()).add(cell);
        }

        for (var entry : byClass.entrySet()) {
            String className = entry.getKey();
            List<PathObject> cells = entry.getValue();
            int n = cells.size();
            if (n < 2) {
                log.accept("Skipping '" + className + "' (n=" + n + ")");
                continue;
            }

            double[] xs = new double[n];
            double[] ys = new double[n];
            for (int i = 0; i < n; i++) {
                var roi = cells.get(i).getROI();
                if (roi == null) {
                    xs[i] = Double.NaN;
                    ys[i] = Double.NaN;
                } else {
                    xs[i] = roi.getCentroidX() * pw;
                    ys[i] = roi.getCentroidY() * ph;
                }
            }

            String mname = "Distance to other " + className + " " + unit;
            long t0 = System.currentTimeMillis();
            IntStream.range(0, n).parallel().forEach(i -> {
                double xi = xs[i], yi = ys[i];
                if (Double.isNaN(xi) || Double.isNaN(yi)) {
                    cells.get(i).getMeasurementList().put(mname, Double.NaN);
                    return;
                }
                double minSq = Double.POSITIVE_INFINITY;
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    double xj = xs[j], yj = ys[j];
                    if (Double.isNaN(xj) || Double.isNaN(yj)) continue;
                    double dx = xi - xj;
                    double dy = yi - yj;
                    double d2 = dx * dx + dy * dy;
                    if (d2 < minSq) minSq = d2;
                }
                double dist = minSq == Double.POSITIVE_INFINITY ? Double.NaN : Math.sqrt(minSq);
                // Each cell's MeasurementList is touched by exactly one thread
                // (the one owning index i), so no extra synchronisation needed.
                cells.get(i).getMeasurementList().put(mname, dist);
            });
            long elapsed = System.currentTimeMillis() - t0;
            log.accept(String.format(Locale.US, "  %s: %d cells in %d ms → %s",
                    className, n, elapsed, mname));
        }
    }

    private void log(String msg) {
        Platform.runLater(() -> logArea.appendText(msg + "\n"));
    }
}
