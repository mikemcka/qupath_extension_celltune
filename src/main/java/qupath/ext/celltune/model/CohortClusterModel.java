package qupath.ext.celltune.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.UnaryOperator;
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
 * A third, all-cells mode (LEI-06/LEI-08, true-scanpy {@code sc.tl.leiden} style)
 * is also two-pass, but pools EVERY cell (not a bounded sample) and clusters them
 * in a single {@link LeidenModel#clusterViaAnn} partition rather than fitting on a
 * sample and transferring: {@link #poolAllCells} streams every image once,
 * z-scoring the full cohort and capturing each cell's packed {@code (msb,lsb)}
 * UUID before releasing its hierarchy; the single Leiden partition then runs over
 * the fully pooled matrix; {@link #writeClusterAllCells} re-reads each image and
 * writes the {@link #CLUSTER_MEASUREMENT} by UUID lookup ({@link #labelMapForImage}),
 * so a reordered second read still labels every cell correctly. A
 * {@link CancellationToken} allows honoring cancellation between images and at
 * phase boundaries, and an {@link AnnRecallException} from {@code clusterViaAnn}
 * aborts before any label is written. {@link AllCellsResult} also carries the
 * pooled partition's per-cluster centroids/counts and z-scoring mean/sd ({@link
 * #centroidsAndCounts}) so a UI caller can re-sync its cluster-assign heatmap to
 * the all-cells partition instead of a preview subsample's fit. Because the
 * all-cells partition is written to disk (not just fit in memory), assigning
 * classes afterward must read the WRITTEN {@link #CLUSTER_MEASUREMENT} rather than
 * re-transferring against any preview reference — {@link #assignAcrossProjectByMeasurement}
 * (backed by the pure {@link #assignmentsFromMeasurement} seam) is that path.
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
     * @param referenceRows   labelled reference: the fitted sample's rows in the SAME space
     *                        Leiden was clustered in — the PC-reduced matrix when the fit
     *                        applied PCA reduction (Task 4/Requirement 3), else the z-scored
     *                        marker matrix (same column order as {@code markers})
     * @param referenceLabels Leiden community label per reference row
     * @param transferK       number of nearest reference rows to vote over
     * @param nClusters       number of distinct Leiden labels (for the vote histogram)
     * @param queryProjector  projects each image's per-cell z-scored marker rows into the SAME
     *                        space {@code referenceRows} lives in before the kNN vote — the
     *                        fit's PCA projector when PCA was applied, or {@code null}/identity
     *                        when it was not (so query and reference always share one basis)
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
            UnaryOperator<double[][]> queryProjector,
            Map<Integer, PathClass> mapping,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress) {
        UnaryOperator<double[][]> proj = queryProjector != null ? queryProjector : UnaryOperator.identity();
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
                rows -> LeidenModel.transferLabels(
                        proj.apply(rows), referenceRows, referenceLabels, transferK, nClusters));
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
     *
     * @param referenceRows  the fitted sample's rows in the SAME space Leiden was clustered
     *                       in (PC-reduced when PCA was applied at fit time — see
     *                       {@link #assignAcrossProjectLeiden})
     * @param queryProjector projects each image's z-scored marker rows into that same space
     *                       before the kNN vote ({@code null}/identity when PCA was not applied)
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
            UnaryOperator<double[][]> queryProjector,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress) {
        UnaryOperator<double[][]> proj = queryProjector != null ? queryProjector : UnaryOperator.identity();
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
                rows -> LeidenModel.transferLabels(
                        proj.apply(rows), referenceRows, referenceLabels, transferK, nClusters));
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

    // ── All-cells cohort driver (LEI-06/LEI-08) ─────────────────────────────────

    /**
     * Minimal mid-run cancellation primitive. No existing analog in this codebase
     * covers TRUE mid-run cancellation — only pre-flight dialog-decline
     * "cancellations" exist elsewhere (see {@code ScatterPlotView}'s scope-switch
     * confirm). Checked at phase boundaries (pool / cluster / write) and between
     * images within each pass. Deliberately kept to a single flag, not a generic
     * task-cancellation framework (RESEARCH.md "Don't Hand-Roll").
     */
    public static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /**
     * Pass-1 (all-cells) pooling result: every (matching) cell's z-scored marker
     * row, its packed {@code (msb,lsb)} UUID, and the ordinal of its source image
     * within the {@code images} list passed to {@link #poolAllCells} — so pass 2
     * can filter this pooled data down to one image's slice by ordinal without
     * re-deriving identity from iteration order.
     *
     * @param rows         [m][nMarkers] z-scored marker rows for every pooled cell
     * @param uuidMsb      {@code PathObject.getID().getMostSignificantBits()} per row
     * @param uuidLsb      {@code PathObject.getID().getLeastSignificantBits()} per row
     * @param imageOrdinal index into {@code images} (the pass-1 input list) per row
     * @param imageNames   the {@code images} list, in the same order used for ordinals
     * @param mean         per-marker mean used to z-score (fit over every pooled cell)
     * @param sd           per-marker sd used to z-score
     * @param totalCells   total detections seen across the pooled images (before any
     *                     {@code classFilter})
     * @param cancelled    true if pooling stopped early because the token was cancelled
     *                     (in which case {@code rows}/etc. reflect only the images
     *                     processed so far)
     */
    public record PooledData(
            double[][] rows,
            long[] uuidMsb,
            long[] uuidLsb,
            int[] imageOrdinal,
            String[] imageNames,
            double[] mean,
            double[] sd,
            int totalCells,
            boolean cancelled) {}

    /**
     * Pass 1 of the all-cells cohort driver (LEI-06/LEI-08): streams EVERY cell
     * across {@code images} (no sample cap, unlike {@link #sample}), z-scoring the
     * full pooled matrix and capturing each cell's packed UUID before releasing its
     * hierarchy — mirrors {@link #sample}'s per-image "read, extract, move on"
     * shape (no explicit {@code close()} exists on this QuPath API either; the
     * hierarchy is simply not retained past the loop iteration).
     * <p>
     * UUID identity is captured as a primitive {@code (long, long)} pair via
     * {@code getID().getMostSignificantBits()}/{@code getLeastSignificantBits()},
     * NOT {@code getID().toString()} — a deliberate, phase-specific deviation from
     * this codebase's usual UUID-as-string convention (used everywhere else at
     * per-image, not per-cohort, scale). At tens of millions of pooled cells the
     * ~10-20x string/object overhead of the {@code .toString()} form is
     * prohibitive (RESEARCH.md Pattern 2 / Anti-Pattern) — do not "fix" this back.
     *
     * @param project     the open project
     * @param images      image names to pool from
     * @param markers     marker measurement names to extract (column order)
     * @param classFilter when non-blank, only cells whose current classification
     *                    contains it (case-insensitive) are pooled
     * @param normalizer  feature normalizer to apply during extraction (nullable)
     * @param token       checked at the top of each per-image iteration; pooling
     *                    stops early (returning what has been pooled so far, with
     *                    {@code cancelled=true}) if already cancelled
     * @param log         progress sink (called off the FX thread)
     */
    public static PooledData poolAllCells(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            String classFilter,
            FeatureNormalizer normalizer,
            CancellationToken token,
            Consumer<String> log) {
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);
        int nMarkers = markers.size();
        var extractor = new CellFeatureExtractor(markers, normalizer);
        boolean classFilterActive = classFilter != null && !classFilter.isBlank();
        String classKw = classFilterActive ? classFilter.trim().toLowerCase() : null;

        List<double[]> rawRows = new ArrayList<>();
        GrowableLongArray msbList = new GrowableLongArray();
        GrowableLongArray lsbList = new GrowableLongArray();
        GrowableIntArray ordinalList = new GrowableIntArray();
        int totalCells = 0;
        boolean cancelled = false;

        for (int ord = 0; ord < images.size(); ord++) {
            if (token != null && token.isCancelled()) {
                cancelled = true;
                break;
            }
            String name = images.get(ord);
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
            float[] flat = extractor.extractMatrix(cells);
            int pooledFromImage = 0;
            for (int i = 0; i < n; i++) {
                PathObject cell = cells.get(i);
                if (classFilterActive) {
                    PathClass pc = cell.getPathClass();
                    if (pc == null || !pc.toString().toLowerCase().contains(classKw)) {
                        continue;
                    }
                }
                double[] row = new double[nMarkers];
                int off = i * nMarkers;
                for (int j = 0; j < nMarkers; j++) {
                    row[j] = flat[off + j];
                }
                rawRows.add(row);
                var id = cell.getID();
                msbList.add(id.getMostSignificantBits());
                lsbList.add(id.getLeastSignificantBits());
                ordinalList.add(ord);
                pooledFromImage++;
            }
            totalCells += n;
            // imageData/hierarchy is not retained past this iteration — released for
            // GC exactly as sample() already does.
            log.accept(String.format(
                    "Pooling %d/%d images — [%s] pooled %,d of %,d cells",
                    ord + 1, images.size(), name, pooledFromImage, n));
        }

        double[][] raw = rawRows.toArray(new double[0][]);
        double[] mean = new double[nMarkers];
        double[] sd = new double[nMarkers];
        double[][] zscored = ScatterMath.standardizeColumns(raw, mean, sd);

        return new PooledData(
                zscored,
                msbList.toArray(),
                lsbList.toArray(),
                ordinalList.toArray(),
                images.toArray(new String[0]),
                mean,
                sd,
                totalCells,
                cancelled);
    }

    /** Packed-UUID map key ({@code (msb,lsb)} pair) — avoids the boxed-String/{@code getID().toString()} overhead at cohort scale. */
    record UuidKey(long msb, long lsb) {}

    /**
     * Pure, testable helper (LEI-08): builds a reorder-independent
     * {@code (msb,lsb) → community label} lookup for ONE image's slice of the
     * pooled arrays, filtering by {@code targetOrdinal}. Because the lookup is
     * keyed on each cell's exact UUID value (not its position in {@code labels}),
     * a caller reading that image's cells back in ANY order — shuffled, reversed,
     * whatever a second {@code readImageData()} happens to return — still resolves
     * every cell to the correct label.
     *
     * @param msb           packed UUID most-significant bits, parallel to {@code labels}
     * @param lsb           packed UUID least-significant bits, parallel to {@code labels}
     * @param imageOrdinal  source-image ordinal per pooled row, parallel to {@code labels}
     * @param labels        community label per pooled row (from a single
     *                      {@link LeidenModel#clusterViaAnn} partition)
     * @param targetOrdinal the image ordinal to build the lookup for
     */
    static Map<UuidKey, Integer> labelMapForImage(
            long[] msb, long[] lsb, int[] imageOrdinal, int[] labels, int targetOrdinal) {
        Map<UuidKey, Integer> map = new HashMap<>();
        for (int i = 0; i < imageOrdinal.length; i++) {
            if (imageOrdinal[i] == targetOrdinal) {
                map.put(new UuidKey(msb[i], lsb[i]), labels[i]);
            }
        }
        return map;
    }

    /**
     * Result of a {@link #writeClusterAllCells} run.
     *
     * @param nClusters       distinct community label count from the single
     *                        {@link LeidenModel#clusterViaAnn} partition (0 on
     *                        abort/cancel-before-clustering)
     * @param recall          measured ANN recall (D-09), or {@code -1.0} when not
     *                        exposed at this layer — {@link LeidenModel#clusterViaAnn}
     *                        does not currently return its internal recall-gate
     *                        measurement; surfacing it end-to-end is left to the
     *                        caller/UI layer if/when that is added, per this plan's
     *                        "if clusterViaAnn exposes it" scope
     * @param cellsWritten    total cells that received a non-(-1) cluster id across
     *                        every successfully-saved image
     * @param imagesWritten   images successfully re-read, labelled and saved
     * @param imagesNotWritten images skipped, failed to load/save, or left
     *                        unprocessed because of cancellation
     * @param aborted         true if the ANN recall gate failed — no image was
     *                        written in this case (T-15-10)
     * @param cancelled       true if the token was cancelled before or during the run
     * @param centroids       [nClusters][nMarkers] mean z-scored marker row per
     *                        cluster, over the FULL pooled all-cells matrix (LEI-09
     *                        UI re-sync) — {@code null} when {@code nClusters == 0}
     *                        (abort/cancel-before-clustering/no matching cells)
     * @param clusterCounts   pooled (matching) cell count per cluster, length
     *                        {@code nClusters} — {@code null} alongside {@code centroids}
     * @param mean            per-marker mean used to z-score the pooled matrix (same
     *                        standardization the caller must reuse for a consistent
     *                        assign-dialog heatmap) — {@code null} alongside {@code centroids}
     * @param sd              per-marker sd used to z-score the pooled matrix —
     *                        {@code null} alongside {@code centroids}
     */
    public record AllCellsResult(
            int nClusters,
            double recall,
            long cellsWritten,
            List<String> imagesWritten,
            List<String> imagesNotWritten,
            boolean aborted,
            boolean cancelled,
            double[][] centroids,
            int[] clusterCounts,
            double[] mean,
            double[] sd) {}

    /** Per-cluster mean z-scored marker row + pooled cell count, from {@link #centroidsAndCounts}. */
    public record CentroidsAndCounts(double[][] centroids, int[] counts) {}

    /**
     * Pure, testable helper: computes the mean z-scored marker row (centroid) and
     * pooled cell count for every cluster in {@code labels}, over the pooled
     * all-cells matrix {@code rows}. Rows whose label is out of {@code [0,nClusters)}
     * (should not normally occur — {@code clusterViaAnn} labels every row) are
     * skipped defensively rather than throwing. Empty clusters (count 0) keep an
     * all-zero centroid row.
     *
     * @param rows       [m][nMarkers] pooled z-scored marker rows
     * @param labels     community label per row, parallel to {@code rows}
     * @param nClusters  distinct cluster count (centroid/count array length)
     * @param nMarkers   marker column count (centroid row length)
     */
    static CentroidsAndCounts centroidsAndCounts(double[][] rows, int[] labels, int nClusters, int nMarkers) {
        double[][] centroids = new double[nClusters][nMarkers];
        int[] counts = new int[nClusters];
        for (int i = 0; i < rows.length; i++) {
            int lab = labels[i];
            if (lab < 0 || lab >= nClusters) {
                continue;
            }
            counts[lab]++;
            double[] row = rows[i];
            for (int j = 0; j < nMarkers; j++) {
                centroids[lab][j] += row[j];
            }
        }
        for (int lab = 0; lab < nClusters; lab++) {
            if (counts[lab] > 0) {
                for (int j = 0; j < nMarkers; j++) {
                    centroids[lab][j] /= counts[lab];
                }
            }
        }
        return new CentroidsAndCounts(centroids, counts);
    }

    /** Outcome of {@link #clusterOrAbort}: either a successful partition, or an aborted run with the recall-gate message. */
    record ClusterOutcome(LeidenModel.LeidenResult result, boolean aborted, String abortMessage) {}

    /**
     * Pure "cluster once, or abort" step (LEI-10 Test D — abort-writes-nothing):
     * runs {@code clusterCall} (in production, a
     * {@link LeidenModel#clusterViaAnn} invocation over the pooled matrix) and
     * turns a thrown {@link AnnRecallException} into a plain {@link ClusterOutcome}
     * instead of letting the exception escape — so {@link #writeClusterAllCells}'s
     * abort branch (and this method's own test) don't need a live pooled matrix
     * or Project/ImageData; any supplier that throws {@code AnnRecallException}
     * exercises the exact same abort-decision logic the real driver uses.
     */
    static ClusterOutcome clusterOrAbort(java.util.function.Supplier<LeidenModel.LeidenResult> clusterCall) {
        try {
            return new ClusterOutcome(clusterCall.get(), false, null);
        } catch (AnnRecallException e) {
            return new ClusterOutcome(null, true, e.getMessage());
        }
    }

    /**
     * The all-cells cohort driver (LEI-06/LEI-08): pools every (matching) cell
     * across {@code images} via {@link #poolAllCells}, runs a SINGLE
     * {@link LeidenModel#clusterViaAnn} partition over the whole pooled matrix (no
     * per-image sub-clustering, no transfer), and re-reads each image in a second
     * pass to write the {@link #CLUSTER_MEASUREMENT} by UUID lookup
     * ({@link #labelMapForImage}) — so a reordered second read still labels every
     * cell correctly (T-15-08: a UUID from pass 1 not found in pass 2's re-read is
     * a cell edited/deleted between passes and is silently left at -1, never a
     * crash).
     * <p>
     * If {@code clusterViaAnn} throws {@link AnnRecallException}, NOTHING is
     * written — pass 2 never starts (T-15-10). If {@code token} is cancelled
     * during pass 1, pooling stops early and nothing is written either (no partial
     * partition is ever clustered from an incomplete pool). If {@code token} is
     * cancelled during pass 2, the loop stops before the next image — already-
     * saved images keep their {@code Cluster} measurement (T-15-07); the result
     * reports exactly which images were / were not written.
     *
     * @param project      the open project
     * @param images       image names to include (cohort scope)
     * @param markers      marker measurement names (column order)
     * @param graphK       Leiden kNN graph neighbours
     * @param resolution   CPM resolution passed to {@code clusterViaAnn}
     * @param randomStarts Leiden random restarts passed to {@code clusterViaAnn}
     * @param seed         RNG seed (drives both the ANN build and Leiden)
     * @param reproducible when true, the ANN index build is best-effort
     *                     deterministic (see {@code HnswKnnIndex})
     * @param pcaEnabled   when true (and the pooled marker-column count exceeds
     *                     {@link ScatterMath#PCA_DEFAULT_THRESHOLD}), the pooled z-scored
     *                     matrix is PCA-reduced (fit on a bounded seeded subsample when the
     *                     pool is large, applied to every pooled row) BEFORE the single Leiden
     *                     partition is run — the all-cells analogue of the preview fit's
     *                     conditional PCA (Requirement 4). Per-cluster centroids returned on
     *                     {@link AllCellsResult} are always computed in the ORIGINAL pooled
     *                     z-scored MARKER space, never the PCA space
     * @param pcaMaxComponents PCA components to keep when {@code pcaEnabled} (ignored otherwise)
     * @param classFilter  when non-blank, only cells whose current classification
     *                     contains it (case-insensitive) are pooled/labelled
     * @param normalizer   feature normalizer to apply during extraction (nullable)
     * @param openData     open image's live data (nullable); its hierarchy is
     *                     mutated on the FX thread, others on the detached copy
     * @param openName     name of the open image (nullable)
     * @param token        checked at phase boundaries and between images in pass 2
     * @param log          progress sink — phase messages ("Pooling X/N images",
     *                     "Building kNN graph…", "Running Leiden…",
     *                     "Writing X/N images") plus per-image detail
     * @param progress     fraction sink in [0,1] for the write (pass 2) phase
     */
    public static AllCellsResult writeClusterAllCells(
            Project<BufferedImage> project,
            List<String> images,
            List<String> markers,
            int graphK,
            double resolution,
            int randomStarts,
            long seed,
            boolean reproducible,
            boolean pcaEnabled,
            int pcaMaxComponents,
            String classFilter,
            FeatureNormalizer normalizer,
            ImageData<BufferedImage> openData,
            String openName,
            CancellationToken token,
            Consumer<String> log,
            DoubleConsumer progress) {

        log.accept(String.format("Pooling %d image(s)…", images.size()));
        PooledData pooled = poolAllCells(project, images, markers, classFilter, normalizer, token, log);

        if (pooled.cancelled() || (token != null && token.isCancelled())) {
            log.accept("Cancelled during pooling — no images written.");
            return new AllCellsResult(0, -1.0, 0, List.of(), List.copyOf(images), false, true, null, null, null, null);
        }
        if (pooled.rows().length == 0) {
            log.accept("No matching cells found across the selected images — nothing to cluster.");
            return new AllCellsResult(0, -1.0, 0, List.of(), List.copyOf(images), false, false, null, null, null, null);
        }

        // Conditional PCA (Requirement 4/5): fit on a bounded seeded subsample when the pool
        // exceeds ScatterMath's fit-sample cap, apply the fitted projection to every pooled row.
        // clusterViaAnn runs on the (possibly reduced) matrix; centroidsAndCounts below always
        // reads pooled.rows() (the ORIGINAL marker-space z-scored matrix), never the reduced one.
        ScatterMath.PcaReduction pcaReduction = ScatterMath.pcaReduce(
                pooled.rows(),
                pcaEnabled ? pcaMaxComponents : 0,
                ScatterMath.PCA_DEFAULT_THRESHOLD,
                ScatterMath.PCA_DEFAULT_FIT_SAMPLE_CAP,
                ScatterMath.PCA_DEFAULT_SEED);
        if (pcaReduction.applied()) {
            String msg = String.format(
                    "PCA: %d → %d comps, %.1f%% variance",
                    pooled.rows()[0].length, pcaReduction.nComponents(), pcaReduction.cumulativeVariance() * 100);
            logger.info(msg);
            log.accept(msg);
        }
        double[][] clusterMatrix = pcaReduction.reduced();

        log.accept("Building kNN graph…");
        log.accept("Running Leiden…");
        ClusterOutcome clusterOutcome = clusterOrAbort(
                () -> LeidenModel.clusterViaAnn(clusterMatrix, graphK, resolution, randomStarts, seed, reproducible));
        if (clusterOutcome.aborted()) {
            logger.warn("All-cells ANN recall gate failed: {}", clusterOutcome.abortMessage());
            log.accept("ANN recall gate failed — aborting, no Cluster measurement written: "
                    + clusterOutcome.abortMessage());
            return new AllCellsResult(0, -1.0, 0, List.of(), List.copyOf(images), true, false, null, null, null, null);
        }

        int[] labels = clusterOutcome.result().labels();
        int nClusters = clusterOutcome.result().nClusters();
        int nMarkers = markers.size();
        // Marker space, NOT PCA space (Requirement 2/4) — pooled.rows() is the original
        // z-scored marker matrix regardless of whether clusterMatrix was PCA-reduced.
        CentroidsAndCounts centroidsAndCounts = centroidsAndCounts(pooled.rows(), labels, nClusters, nMarkers);
        boolean classFilterActive = classFilter != null && !classFilter.isBlank();
        String classKw = classFilterActive ? classFilter.trim().toLowerCase() : null;
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);

        long[] cellsWrittenBox = {0};
        int[] doneBox = {0};
        int total = images.size();

        Pass2Outcome outcome = runPass2Loop(images, token, (ord, name) -> {
            boolean ok;
            ProjectImageEntry<BufferedImage> entry = byName.get(name);
            if (entry == null) {
                log.accept("[" + name + "] not in project — skipped");
                ok = false;
            } else {
                boolean isOpen = name.equals(openName);
                ImageData<BufferedImage> imageData;
                try {
                    imageData = isOpen ? openData : entry.readImageData();
                } catch (Exception e) {
                    imageData = null;
                }
                if (imageData == null) {
                    log.accept("[" + name + "] could not load — skipped");
                    ok = false;
                } else {
                    List<PathObject> cells = detections(imageData);
                    int n = cells.size();
                    if (n == 0) {
                        log.accept("[" + name + "] no detections — skipped");
                        ok = false;
                    } else {
                        Map<UuidKey, Integer> labelMap = labelMapForImage(
                                pooled.uuidMsb(), pooled.uuidLsb(), pooled.imageOrdinal(), labels, ord);

                        double[] values = new double[n];
                        Arrays.fill(values, -1.0);
                        int assigned = 0;
                        for (int i = 0; i < n; i++) {
                            if (classFilterActive) {
                                PathClass pc = cells.get(i).getPathClass();
                                if (pc == null || !pc.toString().toLowerCase().contains(classKw)) {
                                    continue;
                                }
                            }
                            var id = cells.get(i).getID();
                            Integer label = labelMap.get(
                                    new UuidKey(id.getMostSignificantBits(), id.getLeastSignificantBits()));
                            if (label != null) {
                                values[i] = label + 1.0;
                                assigned++;
                            }
                        }

                        applyMeasurement(imageData, cells, values, isOpen);
                        try {
                            entry.saveImageData(imageData);
                            cellsWrittenBox[0] += assigned;
                            log.accept(String.format(
                                    "Writing %d/%d images — [%s] cluster written to %,d of %,d cells",
                                    ord + 1, total, name, assigned, n));
                            ok = true;
                        } catch (Exception e) {
                            logger.error("Failed to save {}", name, e);
                            log.accept("[" + name + "] save failed: " + e.getMessage());
                            ok = false;
                        }
                    }
                }
            }
            doneBox[0]++;
            progress.accept(doneBox[0] / (double) total);
            return ok;
        });

        if (outcome.cancelled() && !outcome.notWritten().isEmpty()) {
            log.accept("Cancelled — stopping before [" + outcome.notWritten().get(0) + "].");
        }

        return new AllCellsResult(
                nClusters,
                -1.0,
                cellsWrittenBox[0],
                outcome.written(),
                outcome.notWritten(),
                false,
                outcome.cancelled(),
                centroidsAndCounts.centroids(),
                centroidsAndCounts.counts(),
                pooled.mean(),
                pooled.sd());
    }

    /** Outcome of {@link #runPass2Loop}: which images were written, which weren't, and whether cancellation stopped the loop early. */
    record Pass2Outcome(List<String> written, List<String> notWritten, boolean cancelled) {}

    /**
     * Pure pass-2 loop policy (LEI-10 Test C — cancel-leaves-written-intact):
     * iterates {@code images} in order, checking {@code token.isCancelled()} at
     * the TOP of each iteration — exactly where {@link #writeClusterAllCells}'s
     * per-image loop checks it — and, once cancelled, stops WITHOUT attempting
     * the current or any later image (they are reported not-written, per D-12:
     * already-written images from earlier iterations keep their {@code Cluster}
     * measurement). Otherwise delegates the per-image work to {@code
     * processImage} (given the image's ordinal — matching {@link #poolAllCells}'s
     * ordinal convention — and name), which returns true if that image was
     * written. Exposed as a package-private pure seam, distinct from any live
     * {@code Project}/{@code ImageData} I/O, so this exact cancel-then-report
     * policy is unit-testable; {@link #writeClusterAllCells} delegates its own
     * per-image loop to this method (not a duplicate copy).
     *
     * @param images       ordered image names for pass 2
     * @param token        checked at the top of each iteration (nullable — never cancelled if null)
     * @param processImage does the real per-image work; returns true if written
     */
    static Pass2Outcome runPass2Loop(
            List<String> images,
            CancellationToken token,
            java.util.function.BiPredicate<Integer, String> processImage) {
        List<String> written = new ArrayList<>();
        List<String> notWritten = new ArrayList<>();
        boolean cancelled = false;
        for (int i = 0; i < images.size(); i++) {
            String name = images.get(i);
            if (token != null && token.isCancelled()) {
                cancelled = true;
                for (int rem = i; rem < images.size(); rem++) {
                    notWritten.add(images.get(rem));
                }
                break;
            }
            if (processImage.test(i, name)) {
                written.add(name);
            } else {
                notWritten.add(name);
            }
        }
        return new Pass2Outcome(written, notWritten, cancelled);
    }

    // ── Cluster→class assign from an already-written all-cells measurement ─────

    /**
     * Pure, testable helper: maps ONE cell's already-written {@link #CLUSTER_MEASUREMENT}
     * value to a {@link PathClass} via {@code mapping} — {@code null} for a missing
     * measurement ({@code NaN}), an unclustered cell ({@code -1}, which decodes to
     * label {@code -2} and is never a mapping key), or a cluster id absent from
     * {@code mapping} (the user chose "skip" for it).
     * <p>
     * The written value is 1-based ({@link #writeClusterAllCells}/{@link
     * #writeClusterAcrossProject} both write {@code label + 1}), so the inverse here
     * is {@code (int) Math.round(value) - 1}.
     */
    static PathClass classForMeasurementValue(double value, Map<Integer, PathClass> mapping) {
        if (Double.isNaN(value)) {
            return null;
        }
        int label = (int) Math.round(value) - 1;
        return mapping.get(label);
    }

    /** Parallel-list result of {@link #assignmentsFromMeasurement}: cells to change, and the class to set on each. */
    record MeasurementAssignment(List<PathObject> objects, List<PathClass> classes) {}

    /**
     * Pure, testable helper (mirrors {@link #runPass2Loop}/{@link #clusterOrAbort}'s
     * "extract a live-I/O-free seam" pattern): scans {@code cells} for an already-written
     * {@link #CLUSTER_MEASUREMENT} value and maps each to a class via {@code mapping}
     * ({@link #classForMeasurementValue}), returning the (cell, class) pairs to apply.
     * Cells with no match (unclustered, or mapped to "skip") are simply omitted — never
     * reclassified. Exercised directly against real {@link PathObject}s with a
     * measurement set, no live {@code Project}/{@code ImageData} required.
     */
    static MeasurementAssignment assignmentsFromMeasurement(List<PathObject> cells, Map<Integer, PathClass> mapping) {
        List<PathObject> objs = new ArrayList<>();
        List<PathClass> classes = new ArrayList<>();
        for (PathObject cell : cells) {
            double v = cell.getMeasurementList().get(CLUSTER_MEASUREMENT);
            PathClass pc = classForMeasurementValue(v, mapping);
            if (pc != null) {
                objs.add(cell);
                classes.add(pc);
            }
        }
        return new MeasurementAssignment(objs, classes);
    }

    /**
     * Assigns classes across {@code images} by reading each cell's ALREADY-WRITTEN
     * {@link #CLUSTER_MEASUREMENT} (not by re-transferring against any preview fit) —
     * the correct cohort-assign path after a completed {@link #writeClusterAllCells}
     * run, where the persisted measurement (not any in-memory preview reference) is
     * the source of truth for which cluster each cell belongs to. Mirrors {@link
     * #assignAcrossProject}'s per-image stream/save/progress contract, but the labelling
     * step is {@link #assignmentsFromMeasurement} instead of a z-scored-row classifier.
     *
     * @param project  the open project
     * @param images   image names to stream (cohort scope)
     * @param mapping  cluster id (0-based, i.e. written-value - 1) -> class
     * @param openData open image's live data (nullable); mutated on the FX thread
     * @param openName name of the open image (nullable)
     * @param log      progress sink (off the FX thread)
     * @param progress fraction sink in [0,1] (off the FX thread)
     * @return total cells reclassified
     */
    public static long assignAcrossProjectByMeasurement(
            Project<BufferedImage> project,
            List<String> images,
            Map<Integer, PathClass> mapping,
            ImageData<BufferedImage> openData,
            String openName,
            Consumer<String> log,
            DoubleConsumer progress) {
        Map<String, ProjectImageEntry<BufferedImage>> byName = entriesByName(project);

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

            MeasurementAssignment assignment = assignmentsFromMeasurement(cells, mapping);
            applyClasses(imageData, assignment.objects(), assignment.classes(), isOpen);
            try {
                entry.saveImageData(imageData);
            } catch (Exception e) {
                logger.error("Failed to save {}", name, e);
                log.accept("[" + name + "] save failed: " + e.getMessage());
            }
            totalAssigned += assignment.objects().size();
            log.accept(String.format(
                    "[%s] assigned %,d of %,d cells (from written Cluster measurement)",
                    name, assignment.objects().size(), n));

            done++;
            progress.accept(done / (double) images.size());
        }
        return totalAssigned;
    }

    /** Growable primitive {@code long} array — avoids boxed {@code Long} allocation while pooling tens of millions of UUID halves. */
    private static final class GrowableLongArray {
        private long[] data = new long[1024];
        private int size = 0;

        void add(long v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }

        long[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    /** Growable primitive {@code int} array — avoids boxed {@code Integer} allocation while pooling tens of millions of image ordinals. */
    private static final class GrowableIntArray {
        private int[] data = new int[1024];
        private int size = 0;

        void add(int v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
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
