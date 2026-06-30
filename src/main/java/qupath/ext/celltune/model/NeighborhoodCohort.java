package qupath.ext.celltune.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Headless, memory-safe backend for <b>project-wide</b> cellular-neighborhood (CN)
 * clustering — the cohort analogue of the single-image pipeline in
 * {@code NeighborhoodAnalysisDialog}, mirroring {@link CohortClusterModel}'s two
 * streaming passes (and reusing its {@code sampleIndices}/{@code nearestCentroid}):
 *
 * <ol>
 *   <li>{@link #sample} — pool a bounded random sample of per-cell neighborhood
 *       composition vectors across the selected images (drawn evenly per image).
 *       The caller standardises (optionally) and fits k-means on this sample.</li>
 *   <li>{@link #assignAcrossProject} — stream image-by-image, recompute every
 *       cell's composition, assign it to its nearest cohort centroid, write the CN
 *       id as a numeric {@code "CN"} measurement, and save each image.</li>
 * </ol>
 *
 * Because one model is fit once and applied everywhere, CN identity is consistent
 * across the cohort (CN 3 = the same micro-environment in every image), exactly as
 * Schürch et al. cluster both patient groups together. The cell phenotype
 * ({@code getPathClass()}) is never modified.
 */
public final class NeighborhoodCohort {

    private static final Logger logger = LoggerFactory.getLogger(NeighborhoodCohort.class);

    /** Numeric measurement written per cell (matches {@code NeighborhoodAnalysisDialog.CN_MEASUREMENT}). */
    public static final String CN_MEASUREMENT = "CN";

    private NeighborhoodCohort() {}

    /** Shared neighborhood-window parameters for both passes. */
    public record Params(boolean knn, int k, double radius, boolean includeCenter, Double pixelOverride) {}

    /** A bounded pool of raw composition rows for fitting. */
    public record SampleResult(double[][] rows, int sampled, int total, int imageCount) {}

    /**
     * Cohort assignment outcome.
     *
     * @param assigned  cells given a CN (non-empty window) across all images
     * @param empty     cells with an empty window (CN = -1)
     * @param cnCounts  per-CN cell counts (pooled across images)
     * @param cnMean    per-CN mean composition fraction (pooled, raw) for the enrichment heatmap
     */
    public record AssignResult(long assigned, long empty, long[] cnCounts, double[][] cnMean) {}

    private record ImageComp(List<PathObject> cells, double[][] comp) {}

    // ── Pass 1: sample ──────────────────────────────────────────────────────────

    /**
     * Pool a bounded random sample of non-empty composition rows across {@code images},
     * drawn roughly evenly per image (seeded, deterministic). Returns raw fraction
     * rows; the caller standardises/fits.
     */
    public static SampleResult sample(
            Project<BufferedImage> project,
            List<String> images,
            List<String> typeNames,
            Params params,
            int sampleCap,
            Consumer<String> log) {
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        Map<String, Integer> typeIndex = typeIndex(typeNames);
        int nTypes = typeNames.size();
        int perImage = Math.max(1, sampleCap / Math.max(1, images.size()));
        Random rng = new Random(42);

        List<double[]> pool = new ArrayList<>();
        int total = 0;
        int imageCount = 0;
        for (String name : images) {
            ProjectImageEntry<BufferedImage> entry = byName.get(name);
            if (entry == null) {
                log.accept("[" + name + "] not in project — skipped");
                continue;
            }
            ImageData<BufferedImage> imageData;
            try {
                imageData = entry.readImageData();
            } catch (Exception e) {
                log.accept("[" + name + "] could not load — skipped");
                continue;
            }
            if (imageData == null) {
                continue;
            }
            ImageComp ic = build(imageData, typeIndex, nTypes, params);
            List<Integer> active = activeRows(ic.comp());
            if (active.isEmpty()) {
                log.accept("[" + name + "] no non-empty windows — skipped");
                continue;
            }
            total += active.size();
            imageCount++;
            int take = Math.min(perImage, active.size());
            int[] pick = CohortClusterModel.sampleIndices(active.size(), take, rng);
            for (int p : pick) {
                pool.add(ic.comp()[active.get(p)]);
            }
            log.accept(String.format("[%s] sampled %,d of %,d windows", name, take, active.size()));
        }

        double[][] rows = pool.toArray(new double[0][]);
        return new SampleResult(rows, rows.length, total, imageCount);
    }

    // ── Pass 2: assign ──────────────────────────────────────────────────────────

    /**
     * Stream over {@code images}, assigning every cell's neighborhood composition to
     * its nearest cohort centroid and writing the CN id (1-based; empty windows = -1)
     * as a numeric {@code "CN"} measurement, then saving each image. When
     * {@code mean}/{@code sd} are non-null the same standardisation used at fit time
     * is replayed before the nearest-centroid lookup.
     *
     * @param centroids fitted centroids in the same space as the (optionally standardised) sample
     * @param openData  open image's live data (nullable); its hierarchy is mutated on the FX thread
     * @param openName  name of the open image (nullable)
     */
    public static AssignResult assignAcrossProject(
            Project<BufferedImage> project,
            List<String> images,
            List<String> typeNames,
            Params params,
            double[] mean,
            double[] sd,
            double[][] centroids,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress) {

        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        Map<String, Integer> typeIndex = typeIndex(typeNames);
        int nTypes = typeNames.size();
        int kc = centroids.length;
        boolean standardize = mean != null && sd != null;

        long[] cnCounts = new long[kc];
        double[][] cnMeanSum = new double[kc][nTypes];
        long assigned = 0;
        long empty = 0;
        int done = 0;
        for (String name : images) {
            ProjectImageEntry<BufferedImage> entry = byName.get(name);
            if (entry == null) {
                log.accept("[" + name + "] not in project — skipped");
                progress.accept(++done / (double) images.size());
                continue;
            }
            boolean isOpen = name.equals(openName);
            ImageData<BufferedImage> imageData;
            try {
                imageData = isOpen ? openData : entry.readImageData();
            } catch (Exception e) {
                log.accept("[" + name + "] could not load — skipped");
                progress.accept(++done / (double) images.size());
                continue;
            }
            if (imageData == null) {
                progress.accept(++done / (double) images.size());
                continue;
            }

            ImageComp ic = build(imageData, typeIndex, nTypes, params);
            int n = ic.cells().size();
            double[] cnValue = new double[n];
            double[] z = new double[nTypes];
            int imgAssigned = 0;
            for (int i = 0; i < n; i++) {
                double[] row = ic.comp()[i];
                double sum = 0;
                for (double v : row) {
                    sum += v;
                }
                if (sum <= 0) {
                    cnValue[i] = -1;
                    empty++;
                    continue;
                }
                if (standardize) {
                    for (int j = 0; j < nTypes; j++) {
                        z[j] = sd[j] < 1e-9 ? 0.0 : (row[j] - mean[j]) / sd[j];
                    }
                } else {
                    System.arraycopy(row, 0, z, 0, nTypes);
                }
                int c = CohortClusterModel.nearestCentroid(z, centroids);
                cnValue[i] = c + 1.0;
                cnCounts[c]++;
                for (int j = 0; j < nTypes; j++) {
                    cnMeanSum[c][j] += row[j];
                }
                imgAssigned++;
            }
            assigned += imgAssigned;

            applyMeasurements(imageData, ic.cells(), cnValue, isOpen);
            try {
                entry.saveImageData(imageData);
            } catch (Exception e) {
                logger.error("Failed to save {}", name, e);
                log.accept("[" + name + "] save failed: " + e.getMessage());
            }
            log.accept(String.format("[%s] CN assigned to %,d of %,d cells", name, imgAssigned, n));
            progress.accept(++done / (double) images.size());
        }

        double[][] cnMean = new double[kc][nTypes];
        for (int c = 0; c < kc; c++) {
            if (cnCounts[c] > 0) {
                for (int j = 0; j < nTypes; j++) {
                    cnMean[c][j] = cnMeanSum[c][j] / cnCounts[c];
                }
            }
        }
        return new AssignResult(assigned, empty, cnCounts, cnMean);
    }

    // ── Shared helpers ──────────────────────────────────────────────────────────

    /** Build the [n][nTypes] raw composition matrix and aligned cell list for one image. */
    private static ImageComp build(ImageData<?> imageData, Map<String, Integer> typeIndex, int nTypes, Params params) {
        var hierarchy = imageData.getHierarchy();
        var cellsCol = hierarchy.getCellObjects();
        List<PathObject> cells = new ArrayList<>(cellsCol.isEmpty() ? hierarchy.getDetectionObjects() : cellsCol);
        int n = cells.size();

        var cal = imageData.getServer().getPixelCalibration();
        boolean calibrated = cal.hasPixelSizeMicrons();
        double pw = params.pixelOverride() != null
                ? params.pixelOverride()
                : (calibrated ? cal.getPixelWidthMicrons() : 1.0);
        double ph = params.pixelOverride() != null
                ? params.pixelOverride()
                : (calibrated ? cal.getPixelHeightMicrons() : 1.0);

        double[] xs = new double[n];
        double[] ys = new double[n];
        int[] typeId = new int[n];
        for (int i = 0; i < n; i++) {
            var roi = cells.get(i).getROI();
            if (roi == null) {
                xs[i] = Double.NaN;
                ys[i] = Double.NaN;
            } else {
                xs[i] = roi.getCentroidX() * pw;
                ys[i] = roi.getCentroidY() * ph;
            }
            PathClass pc = cells.get(i).getPathClass();
            Integer idx = (pc != null && pc.isValid() && !PathClassTools.isIgnoredClass(pc))
                    ? typeIndex.get(pc.toString())
                    : null;
            typeId[i] = idx != null ? idx : -1;
        }

        int[][] neighbors = params.knn()
                ? NeighborhoodModel.kNearestNeighborIndices(xs, ys, params.k())
                : NeighborhoodModel.radiusNeighborIndices(xs, ys, params.radius());
        double[][] comp = NeighborhoodModel.compositionMatrix(neighbors, typeId, nTypes, params.includeCenter());
        return new ImageComp(cells, comp);
    }

    private static List<Integer> activeRows(double[][] comp) {
        List<Integer> active = new ArrayList<>();
        for (int i = 0; i < comp.length; i++) {
            double sum = 0;
            for (double v : comp[i]) {
                sum += v;
            }
            if (sum > 0) {
                active.add(i);
            }
        }
        return active;
    }

    /** Writes the "CN" measurement; for the open image this marshals to the FX thread. */
    private static void applyMeasurements(
            ImageData<?> imageData, List<PathObject> cells, double[] values, boolean isOpen) {
        Runnable apply = () -> {
            for (int i = 0; i < cells.size(); i++) {
                cells.get(i).getMeasurementList().put(CN_MEASUREMENT, values[i]);
            }
            imageData.getHierarchy().fireHierarchyChangedEvent(NeighborhoodCohort.class);
        };
        if (isOpen && !Platform.isFxApplicationThread()) {
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
        } else {
            apply.run();
        }
    }

    private static Map<String, Integer> typeIndex(List<String> typeNames) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (String t : typeNames) {
            idx.put(t, idx.size());
        }
        return idx;
    }

    private static Map<String, ProjectImageEntry<BufferedImage>> entriesByName(Project<BufferedImage> project) {
        Map<String, ProjectImageEntry<BufferedImage>> map = new LinkedHashMap<>();
        for (ProjectImageEntry<BufferedImage> entry : project.getImageList()) {
            map.put(entry.getImageName(), entry);
        }
        return map;
    }
}
