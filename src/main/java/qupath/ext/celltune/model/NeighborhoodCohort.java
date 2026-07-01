package qupath.ext.celltune.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.util.BackgroundExecutors;
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

    /** One image's contribution to the fit pool: its sampled rows and its non-empty-window count. */
    private record ImageSample(List<double[]> rows, int activeTotal) {}

    /** One image's assignment tally, merged into the cohort totals after all workers finish. */
    private record ImageAssign(long assigned, long empty, long[] cnCounts, double[][] cnMeanSum) {}

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
            int workers,
            Consumer<String> log) {
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        Map<String, Integer> typeIndex = typeIndex(typeNames);
        int nTypes = typeNames.size();
        int perImage = Math.max(1, sampleCap / Math.max(1, images.size()));

        // One task per image (each an independent read + composition build). Each image
        // draws its own seeded sub-sample so the pooled fit set is deterministic no matter
        // the order tasks finish in — reproducibility the single shared RNG used to give.
        int parallelism = Math.max(1, Math.min(workers, images.size()));
        ExecutorService pool = BackgroundExecutors.newFixedPool(parallelism, "CellTune-CN-Sample");
        try {
            List<Future<ImageSample>> futures = new ArrayList<>();
            for (int idx = 0; idx < images.size(); idx++) {
                String name = images.get(idx);
                ProjectImageEntry<BufferedImage> entry = byName.get(name);
                long seed = 42L + idx;
                futures.add(
                        pool.submit(() -> sampleOneImage(entry, name, typeIndex, nTypes, params, perImage, seed, log)));
            }

            // Merge in submission order (== image order) so the pooled rows are stable.
            List<double[]> pooled = new ArrayList<>();
            int total = 0;
            int imageCount = 0;
            for (Future<ImageSample> f : futures) {
                ImageSample s;
                try {
                    s = f.get();
                } catch (Exception e) {
                    continue;
                }
                if (s == null) {
                    continue;
                }
                total += s.activeTotal();
                imageCount++;
                pooled.addAll(s.rows());
            }
            double[][] rows = pooled.toArray(new double[0][]);
            return new SampleResult(rows, rows.length, total, imageCount);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Load one image, build its compositions, and draw a seeded sub-sample of its non-empty windows. */
    private static ImageSample sampleOneImage(
            ProjectImageEntry<BufferedImage> entry,
            String name,
            Map<String, Integer> typeIndex,
            int nTypes,
            Params params,
            int perImage,
            long seed,
            Consumer<String> log) {
        if (entry == null) {
            log.accept("[" + name + "] not in project — skipped");
            return null;
        }
        ImageData<BufferedImage> imageData;
        try {
            imageData = entry.readImageData();
        } catch (Exception e) {
            log.accept("[" + name + "] could not load — skipped");
            return null;
        }
        if (imageData == null) {
            return null;
        }
        ImageComp ic = build(imageData, typeIndex, nTypes, params);
        List<Integer> active = activeRows(ic.comp());
        if (active.isEmpty()) {
            log.accept("[" + name + "] no non-empty windows — skipped");
            return null;
        }
        int take = Math.min(perImage, active.size());
        int[] pick = CohortClusterModel.sampleIndices(active.size(), take, new Random(seed));
        List<double[]> rows = new ArrayList<>(pick.length);
        for (int p : pick) {
            rows.add(ic.comp()[active.get(p)]);
        }
        log.accept(String.format("[%s] sampled %,d of %,d windows", name, take, active.size()));
        return new ImageSample(rows, active.size());
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
            int workers,
            Consumer<String> log,
            DoubleConsumer progress) {

        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        Map<String, Integer> typeIndex = typeIndex(typeNames);
        int nTypes = typeNames.size();
        int kc = centroids.length;
        boolean standardize = mean != null && sd != null;
        int nImages = images.size();

        // Per-image classify+write+save, one task each. Each image mutates only its own
        // ImageData, so the only shared state is the merged tally (done after join) and the
        // progress counter (atomic). The open image still writes on the FX thread inside
        // applyMeasurements; closed images write on their worker thread.
        int parallelism = Math.max(1, Math.min(workers, nImages));
        ExecutorService pool = BackgroundExecutors.newFixedPool(parallelism, "CellTune-CN-Assign");
        AtomicInteger done = new AtomicInteger(0);
        try {
            List<Future<ImageAssign>> futures = new ArrayList<>();
            for (String name : images) {
                ProjectImageEntry<BufferedImage> entry = byName.get(name);
                futures.add(pool.submit(() -> {
                    try {
                        return assignOneImage(
                                entry,
                                name,
                                typeIndex,
                                nTypes,
                                params,
                                mean,
                                sd,
                                standardize,
                                centroids,
                                kc,
                                openData,
                                openName,
                                log);
                    } finally {
                        progress.accept(done.incrementAndGet() / (double) nImages);
                    }
                }));
            }

            long[] cnCounts = new long[kc];
            double[][] cnMeanSum = new double[kc][nTypes];
            long assigned = 0;
            long empty = 0;
            for (Future<ImageAssign> f : futures) {
                ImageAssign r;
                try {
                    r = f.get();
                } catch (Exception e) {
                    continue;
                }
                if (r == null) {
                    continue;
                }
                assigned += r.assigned();
                empty += r.empty();
                for (int c = 0; c < kc; c++) {
                    cnCounts[c] += r.cnCounts()[c];
                    for (int j = 0; j < nTypes; j++) {
                        cnMeanSum[c][j] += r.cnMeanSum()[c][j];
                    }
                }
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
        } finally {
            pool.shutdownNow();
        }
    }

    /** Classify every cell in one image to its nearest centroid, write the CN measurement, and save. */
    private static ImageAssign assignOneImage(
            ProjectImageEntry<BufferedImage> entry,
            String name,
            Map<String, Integer> typeIndex,
            int nTypes,
            Params params,
            double[] mean,
            double[] sd,
            boolean standardize,
            double[][] centroids,
            int kc,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log) {
        long[] cnCounts = new long[kc];
        double[][] cnMeanSum = new double[kc][nTypes];
        if (entry == null) {
            log.accept("[" + name + "] not in project — skipped");
            return new ImageAssign(0, 0, cnCounts, cnMeanSum);
        }
        boolean isOpen = name.equals(openName);
        ImageData<BufferedImage> imageData;
        try {
            imageData = isOpen ? openData : entry.readImageData();
        } catch (Exception e) {
            log.accept("[" + name + "] could not load — skipped");
            return new ImageAssign(0, 0, cnCounts, cnMeanSum);
        }
        if (imageData == null) {
            return new ImageAssign(0, 0, cnCounts, cnMeanSum);
        }

        ImageComp ic = build(imageData, typeIndex, nTypes, params);
        int n = ic.cells().size();
        double[] cnValue = new double[n];
        double[] z = new double[nTypes];
        long empty = 0;
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

        applyMeasurements(imageData, ic.cells(), cnValue, isOpen);
        try {
            entry.saveImageData(imageData);
        } catch (Exception e) {
            logger.error("Failed to save {}", name, e);
            log.accept("[" + name + "] save failed: " + e.getMessage());
        }
        log.accept(String.format("[%s] CN assigned to %,d of %,d cells", name, imgAssigned, n));
        return new ImageAssign(imgAssigned, empty, cnCounts, cnMeanSum);
    }

    // ── Pass 3: apply names (cohort-wide) ─────────────────────────────────────────

    /**
     * Write the user's CN naming/merge to every cell of the given {@code images}, in parallel.
     * Each cell's already-saved {@code CN} id (1-based; {@code -1} = empty window) is mapped through
     * {@code nameByCn} / {@code codeByCn} to a name string (written to metadata key {@code nameKey}) and
     * a numeric code (written to measurement {@code codeKey}); empty/unknown cells get
     * {@code unassignedName} / {@code -1}. Each image is read, updated, and saved. Non-destructive —
     * the cell phenotype ({@code getPathClass()}) is untouched.
     *
     * @return the total number of cells updated across all images
     */
    public static long applyNamesAcrossProject(
            Project<BufferedImage> project,
            List<String> images,
            Map<Integer, String> nameByCn,
            Map<Integer, Integer> codeByCn,
            String unassignedName,
            String codeKey,
            String nameKey,
            int workers,
            Consumer<String> log,
            DoubleConsumer progress) {
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        int nImages = images.size();
        int parallelism = Math.max(1, Math.min(workers, Math.max(1, nImages)));
        ExecutorService pool = BackgroundExecutors.newFixedPool(parallelism, "CellTune-CN-Naming");
        AtomicInteger done = new AtomicInteger(0);
        AtomicLong updated = new AtomicLong(0);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String name : images) {
                ProjectImageEntry<BufferedImage> entry = byName.get(name);
                futures.add(pool.submit(() -> {
                    try {
                        updated.addAndGet(applyNamesOneImage(
                                entry, name, nameByCn, codeByCn, unassignedName, codeKey, nameKey, log));
                    } finally {
                        progress.accept(nImages == 0 ? 1.0 : done.incrementAndGet() / (double) nImages);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    // per-image failures are already logged inside the task
                }
            }
            return updated.get();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Map every cell's {@code CN} id to a name/code and write both, then save the image. */
    private static long applyNamesOneImage(
            ProjectImageEntry<BufferedImage> entry,
            String name,
            Map<Integer, String> nameByCn,
            Map<Integer, Integer> codeByCn,
            String unassignedName,
            String codeKey,
            String nameKey,
            Consumer<String> log) {
        if (entry == null) {
            log.accept("[" + name + "] not in project — skipped");
            return 0;
        }
        ImageData<BufferedImage> imageData;
        try {
            imageData = entry.readImageData();
        } catch (Exception e) {
            log.accept("[" + name + "] could not load — skipped");
            return 0;
        }
        if (imageData == null) {
            return 0;
        }
        var hierarchy = imageData.getHierarchy();
        var cellsCol = hierarchy.getCellObjects();
        List<PathObject> cells = new ArrayList<>(cellsCol.isEmpty() ? hierarchy.getDetectionObjects() : cellsCol);

        long updated = 0;
        for (PathObject cell : cells) {
            double cnVal = cell.getMeasurementList().get(CN_MEASUREMENT);
            int code = -1;
            String nm = unassignedName;
            if (!Double.isNaN(cnVal) && cnVal >= 1) {
                int id = (int) Math.round(cnVal);
                String n = nameByCn.get(id);
                if (n != null) {
                    nm = n;
                }
                Integer c = codeByCn.get(id);
                code = c != null ? c : -1;
            }
            cell.getMeasurementList().put(codeKey, code);
            cell.getMetadata().put(nameKey, nm);
            updated++;
        }

        try {
            entry.saveImageData(imageData);
        } catch (Exception e) {
            logger.error("Failed to save {}", name, e);
            log.accept("[" + name + "] save failed: " + e.getMessage());
        }
        log.accept(String.format("[%s] CN Class written to %,d cells", name, updated));
        return updated;
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
