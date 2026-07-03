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
import qupath.lib.objects.PathObjectFilter;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Headless, memory-safe backend for project-wide cell clustering. It owns the two
 * streaming passes that must never hold the whole cohort in memory at once:
 * <ol>
 *   <li>{@link #sample} — pool a bounded random sample of cells across the selected
 *       images, carrying each cell's raw marker vector, current class and source
 *       image. The caller standardises + fits k-means on this sample (in memory).</li>
 *   <li>{@link #assignAcrossProject} — stream image-by-image, assign every (matching)
 *       cell to its nearest cohort centroid, write that as a QuPath classification,
 *       and save each image.</li>
 * </ol>
 * Because a single model is fit once and applied everywhere, cluster identity is
 * consistent across the cohort (cluster 3 = the same phenotype in every image).
 * <p>
 * No JavaFX UI is built here; the only FX dependency is marshalling mutations of
 * the open image's live hierarchy onto the FX thread, which QuPath requires.
 */
public final class CohortClusterModel {

    private static final Logger logger = LoggerFactory.getLogger(CohortClusterModel.class);

    /**
     * Numeric per-cell measurement holding the assigned phenotype cluster id (1-based;
     * -1 = not in the clustered population). Written non-destructively by
     * {@link #writeClusterAcrossProject} so every image carries a persistent {@code Cluster}
     * column for downstream analysis and the viewer overlay. Matches
     * {@code ScatterPlotView.CLUSTER_MEASUREMENT}.
     */
    public static final String CLUSTER_MEASUREMENT = "Cluster";

    private CohortClusterModel() {}

    /**
     * A bounded sample pooled across the cohort. All arrays are aligned by row.
     *
     * @param raw          [m][nMarkers] raw marker values for the sampled cells
     * @param rowClass     current class name per row (null if unclassified)
     * @param rowImage     source image name per row
     * @param markers      marker measurement names (column order of {@code raw})
     * @param sampledCells number of cells actually sampled ({@code m})
     * @param totalCells   total detections seen across the sampled images
     * @param imageCount   number of images that contributed cells
     */
    public record SampleData(
            double[][] raw,
            String[] rowClass,
            String[] rowImage,
            List<String> markers,
            int sampledCells,
            int totalCells,
            int imageCount) {}

    /**
     * Pool a bounded random sample of cells across {@code images}, drawn roughly
     * evenly per image. Carries each cell's raw marker vector, current class, and
     * source image so the caller can colour by marker/class and sub-cluster within
     * a class without re-reading the project.
     *
     * @param project   the open project
     * @param images    image names to sample from
     * @param markers   marker measurement names to extract (column order)
     * @param sampleCap total cells to pool (drawn evenly across images)
     * @param normalizer feature normalizer to apply during extraction (nullable —
     *                  must match the one used at {@link #assignAcrossProject})
     * @param log       progress sink (called off the FX thread)
     */
    public static SampleData sample(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            int sampleCap,
            FeatureNormalizer normalizer,
            Consumer<String> log) {
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        int nMarkers = markers.size();
        int perImage = Math.max(1, sampleCap / Math.max(1, images.size()));
        Random rng = new Random(42);
        var extractor = new CellFeatureExtractor(markers, normalizer);

        List<float[]> rows = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        int totalCells = 0;
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
                log.accept("[" + name + "] could not load — skipped");
                continue;
            }
            List<PathObject> cells = detections(imageData);
            int n = cells.size();
            if (n == 0) {
                log.accept("[" + name + "] no detections — skipped");
                continue;
            }
            totalCells += n;
            imageCount++;
            float[] flat = extractor.extractMatrix(cells);
            int take = Math.min(perImage, n);
            int[] pick = sampleIndices(n, take, rng);
            for (int idx : pick) {
                float[] row = new float[nMarkers];
                System.arraycopy(flat, idx * nMarkers, row, 0, nMarkers);
                rows.add(row);
                PathClass pc = cells.get(idx).getPathClass();
                classes.add(pc == null ? null : pc.toString());
                sources.add(name);
            }
            log.accept(String.format("[%s] sampled %,d of %,d cells", name, take, n));
        }

        int m = rows.size();
        double[][] raw = new double[m][nMarkers];
        for (int i = 0; i < m; i++) {
            float[] row = rows.get(i);
            for (int j = 0; j < nMarkers; j++) {
                raw[i][j] = row[j];
            }
        }
        return new SampleData(
                raw,
                classes.toArray(new String[0]),
                sources.toArray(new String[0]),
                List.copyOf(markers),
                m,
                totalCells,
                imageCount);
    }

    /**
     * Stream over {@code images}, assigning every matching cell to its nearest
     * cohort centroid and writing the mapped class. The fit ({@code mean}/{@code sd}/
     * {@code centroids}) must be over the same {@code markers} columns the caller
     * clustered on.
     * <p>
     * When {@code classFilter} is non-blank, only cells whose current classification
     * contains it (case-insensitive) are reclassified — keeping a within-class
     * sub-clustering consistent with what was displayed.
     *
     * @param normalizer feature normalizer to apply during extraction (nullable —
     *                 must match the one used to fit {@code centroids})
     * @param openData open image's live data (nullable); its hierarchy is mutated
     *                 on the FX thread, others are mutated on the detached copy
     * @param openName name of the open image (nullable)
     * @param log      progress sink (off the FX thread)
     * @param progress fraction sink in [0,1] (off the FX thread)
     * @return total cells assigned
     */
    public static long assignAcrossProject(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            double[] mean,
            double[] sd,
            double[][] centroids,
            Map<Integer, PathClass> mapping,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress) {
        return assignAcrossProject(
                project,
                images,
                markers,
                mean,
                sd,
                mapping,
                classFilter,
                normalizer,
                openData,
                openName,
                log,
                progress,
                rows -> {
                    int[] labels = new int[rows.length];
                    for (int i = 0; i < rows.length; i++) {
                        labels[i] = nearestCentroid(rows[i], centroids);
                    }
                    return labels;
                });
    }

    /**
     * Stream over {@code images}, assigning every matching cell a class via Leiden
     * kNN label transfer against the labelled pooled sample (scanpy {@code
     * sc.tl.ingest} style), instead of nearest-centroid assignment. The reference
     * ({@code referenceRows}/{@code referenceLabels}) is the SAME z-scored sample
     * rows Leiden was fit on (Task 4's {@code fitLeidenReference}/{@code
     * fitLeidenReferenceLabels}), so the reference kNN index is built once here and
     * reused across every cell/image in the stream — the reference stays bounded by
     * the Sample spinner cap for the whole pass.
     * <p>
     * Mirrors the k-means overload's semantics (same {@code mean}/{@code sd}
     * z-scoring, same {@code classFilter}, same per-image stream/save/progress
     * contract) so callers can pick either assign path with an otherwise identical
     * call shape.
     *
     * @param referenceRows   labelled reference: the z-scored, Leiden-labelled
     *                        pooled sample rows (same column order as {@code markers})
     * @param referenceLabels Leiden community label per reference row
     * @param transferK       number of nearest reference rows to vote over
     * @param nClusters       number of distinct Leiden labels (for the vote histogram)
     */
    public static long assignAcrossProjectLeiden(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            double[] mean,
            double[] sd,
            double[][] referenceRows,
            int[] referenceLabels,
            int transferK,
            int nClusters,
            Map<Integer, PathClass> mapping,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress) {
        return assignAcrossProject(
                project,
                images,
                markers,
                mean,
                sd,
                mapping,
                classFilter,
                normalizer,
                openData,
                openName,
                log,
                progress,
                rows -> LeidenModel.transferLabels(rows, referenceRows, referenceLabels, transferK, nClusters));
    }

    /**
     * Stream over {@code images}, assign every (matching) cell to its nearest cohort
     * centroid, and write the cluster id as a non-destructive numeric
     * {@link #CLUSTER_MEASUREMENT} (1-based; -1 for cells outside the within-class
     * population), saving each image. The measurement analogue of
     * {@link #assignAcrossProject} — it never changes the cell's classification, so a
     * persistent {@code Cluster} column is added across the cohort for downstream
     * analysis and the viewer overlay.
     */
    public static long writeClusterAcrossProject(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            double[] mean,
            double[] sd,
            double[][] centroids,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress) {
        return writeClusterAcrossProject(
                project,
                images,
                markers,
                mean,
                sd,
                classFilter,
                normalizer,
                openData,
                openName,
                log,
                progress,
                rows -> {
                    int[] labels = new int[rows.length];
                    for (int i = 0; i < rows.length; i++) {
                        labels[i] = nearestCentroid(rows[i], centroids);
                    }
                    return labels;
                });
    }

    /**
     * Leiden variant of {@link #writeClusterAcrossProject}: labels each cell by kNN
     * transfer against the labelled pooled sample (mirrors
     * {@link #assignAcrossProjectLeiden}) and writes the {@link #CLUSTER_MEASUREMENT}.
     */
    public static long writeClusterAcrossProjectLeiden(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            double[] mean,
            double[] sd,
            double[][] referenceRows,
            int[] referenceLabels,
            int transferK,
            int nClusters,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress) {
        return writeClusterAcrossProject(
                project,
                images,
                markers,
                mean,
                sd,
                classFilter,
                normalizer,
                openData,
                openName,
                log,
                progress,
                rows -> LeidenModel.transferLabels(rows, referenceRows, referenceLabels, transferK, nClusters));
    }

    /**
     * Shared per-image streaming/save/progress driver for the measurement-writing
     * cohort passes. Mirrors {@link #assignAcrossProject(Project, List, List, double[],
     * double[], Map, String, FeatureNormalizer, ImageData, String, Consumer,
     * DoubleConsumer, java.util.function.Function)} but writes a numeric cluster id to
     * every cell (matched cells get {@code label + 1}; cells outside the within-class
     * filter get -1) instead of a classification.
     */
    private static long writeClusterAcrossProject(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            double[] mean,
            double[] sd,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress,
            java.util.function.Function<double[][], int[]> labelsForRows) {

        int nMarkers = markers.size();
        var extractor = new CellFeatureExtractor(markers, normalizer);
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        boolean classFilterActive = classFilter != null && !classFilter.isBlank();
        String classKw = classFilterActive ? classFilter.trim().toLowerCase() : null;

        long totalAssigned = 0;
        int done = 0;
        for (String name : images) {
            ProjectImageEntry<BufferedImage> entry = byName.get(name);
            if (entry == null) {
                log.accept("[" + name + "] not in project — skipped");
                done++;
                progress.accept(done / (double) images.size());
                continue;
            }
            boolean isOpen = name.equals(openName);
            ImageData<BufferedImage> imageData;
            try {
                imageData = isOpen ? openData : entry.readImageData();
            } catch (Exception e) {
                log.accept("[" + name + "] could not load — skipped");
                done++;
                progress.accept(done / (double) images.size());
                continue;
            }
            if (imageData == null) {
                log.accept("[" + name + "] could not load — skipped");
                done++;
                progress.accept(done / (double) images.size());
                continue;
            }

            List<PathObject> cells = detections(imageData);
            int n = cells.size();
            if (n == 0) {
                log.accept("[" + name + "] no detections — skipped");
                done++;
                progress.accept(done / (double) images.size());
                continue;
            }
            float[] flat = extractor.extractMatrix(cells);

            // Z-score the matching cells, remembering their positions so the labels can be
            // scattered back into a full [n] value array (non-matching cells stay -1).
            List<Integer> matchedIdx = new ArrayList<>();
            List<double[]> matchedRows = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (classFilterActive) {
                    PathClass pc = cells.get(i).getPathClass();
                    if (pc == null || !pc.toString().toLowerCase().contains(classKw)) {
                        continue;
                    }
                }
                double[] z = new double[nMarkers];
                int off = i * nMarkers;
                for (int j = 0; j < nMarkers; j++) {
                    double v = flat[off + j];
                    z[j] = sd[j] < 1e-9 ? 0.0 : (v - mean[j]) / sd[j];
                }
                matchedIdx.add(i);
                matchedRows.add(z);
            }

            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                values[i] = -1.0;
            }
            int assigned = 0;
            if (!matchedRows.isEmpty()) {
                int[] labels = labelsForRows.apply(matchedRows.toArray(new double[0][]));
                for (int t = 0; t < matchedIdx.size(); t++) {
                    if (labels[t] >= 0) {
                        values[matchedIdx.get(t)] = labels[t] + 1.0;
                        assigned++;
                    }
                }
            }

            applyMeasurement(imageData, cells, values, isOpen);
            try {
                entry.saveImageData(imageData);
            } catch (Exception e) {
                logger.error("Failed to save {}", name, e);
                log.accept("[" + name + "] save failed: " + e.getMessage());
            }
            totalAssigned += assigned;
            log.accept(String.format("[%s] cluster written to %,d of %,d cells", name, assigned, n));

            done++;
            progress.accept(done / (double) images.size());
        }
        return totalAssigned;
    }

    /** Writes the {@link #CLUSTER_MEASUREMENT}; for the open image this marshals to the FX thread. */
    private static void applyMeasurement(
            ImageData<BufferedImage> imageData, List<PathObject> cells, double[] values, boolean isOpen) {
        Runnable apply = () -> {
            for (int i = 0; i < cells.size(); i++) {
                cells.get(i).getMeasurementList().put(CLUSTER_MEASUREMENT, values[i]);
            }
            imageData.getHierarchy().fireHierarchyChangedEvent(CohortClusterModel.class);
        };
        if (isOpen) {
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
            apply.run();
        }
    }

    /**
     * Shared per-image streaming/save/progress driver for
     * {@link #assignAcrossProject} and {@link #assignAcrossProjectLeiden}: both
     * differ only in HOW a batch of z-scored rows maps to cluster ids ({@code
     * labelsForRows}), not in the image-streaming, class-filtering, save, or
     * progress-reporting mechanics. Batching per-image (rather than one row at a
     * time) lets the Leiden path make a single {@link LeidenModel#transferLabels}
     * call per image instead of one kNN search invocation per cell.
     */
    private static long assignAcrossProject(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            double[] mean,
            double[] sd,
            Map<Integer, PathClass> mapping,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress,
            java.util.function.Function<double[][], int[]> labelsForRows) {

        int nMarkers = markers.size();
        var extractor = new CellFeatureExtractor(markers, normalizer);
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        boolean classFilterActive = classFilter != null && !classFilter.isBlank();
        String classKw = classFilterActive ? classFilter.trim().toLowerCase() : null;

        long totalAssigned = 0;
        int done = 0;
        for (String name : images) {
            ProjectImageEntry<BufferedImage> entry = byName.get(name);
            if (entry == null) {
                log.accept("[" + name + "] not in project — skipped");
                done++;
                progress.accept(done / (double) images.size());
                continue;
            }
            boolean isOpen = name.equals(openName);
            ImageData<BufferedImage> imageData;
            try {
                imageData = isOpen ? openData : entry.readImageData();
            } catch (Exception e) {
                log.accept("[" + name + "] could not load — skipped");
                done++;
                progress.accept(done / (double) images.size());
                continue;
            }
            if (imageData == null) {
                log.accept("[" + name + "] could not load — skipped");
                done++;
                progress.accept(done / (double) images.size());
                continue;
            }

            List<PathObject> cells = detections(imageData);
            int n = cells.size();
            if (n == 0) {
                log.accept("[" + name + "] no detections — skipped");
                done++;
                progress.accept(done / (double) images.size());
                continue;
            }
            float[] flat = extractor.extractMatrix(cells);

            // Z-score every matching cell first, then label the whole image's rows
            // in one batch call (nearestCentroid: cheap either way; transferLabels:
            // avoids a separate kNN search invocation per cell).
            List<PathObject> matchedCells = new ArrayList<>();
            List<double[]> matchedRows = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (classFilterActive) {
                    PathClass pc = cells.get(i).getPathClass();
                    if (pc == null || !pc.toString().toLowerCase().contains(classKw)) {
                        continue;
                    }
                }
                double[] z = new double[nMarkers];
                int off = i * nMarkers;
                for (int j = 0; j < nMarkers; j++) {
                    double v = flat[off + j];
                    z[j] = sd[j] < 1e-9 ? 0.0 : (v - mean[j]) / sd[j];
                }
                matchedCells.add(cells.get(i));
                matchedRows.add(z);
            }

            List<PathObject> changedObjs = new ArrayList<>();
            List<PathClass> changedCls = new ArrayList<>();
            if (!matchedRows.isEmpty()) {
                int[] labels = labelsForRows.apply(matchedRows.toArray(new double[0][]));
                for (int i = 0; i < matchedCells.size(); i++) {
                    PathClass pc = mapping.get(labels[i]);
                    if (pc != null) {
                        changedObjs.add(matchedCells.get(i));
                        changedCls.add(pc);
                    }
                }
            }

            applyClasses(imageData, changedObjs, changedCls, isOpen);
            try {
                entry.saveImageData(imageData);
            } catch (Exception e) {
                logger.error("Failed to save {}", name, e);
                log.accept("[" + name + "] save failed: " + e.getMessage());
            }
            totalAssigned += changedObjs.size();
            log.accept(String.format("[%s] assigned %,d of %,d cells", name, changedObjs.size(), n));

            done++;
            progress.accept(done / (double) images.size());
        }
        return totalAssigned;
    }

    /** Applies classes; for the open image this marshals to the FX thread. */
    private static void applyClasses(
            ImageData<BufferedImage> imageData, List<PathObject> objs, List<PathClass> classes, boolean isOpen) {
        Runnable apply = () -> {
            for (int i = 0; i < objs.size(); i++) {
                objs.get(i).setPathClass(classes.get(i));
            }
            imageData.getHierarchy().fireObjectClassificationsChangedEvent(CohortClusterModel.class, objs);
        };
        if (isOpen) {
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
            apply.run();
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    private static Map<String, ProjectImageEntry<BufferedImage>> entriesByName(Project<BufferedImage> project) {
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
    static int[] sampleIndices(int n, int count, Random rng) {
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

    static int nearestCentroid(double[] z, double[][] cents) {
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
