package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import qupath.ext.celltune.model.CohortClusterModel.CancellationToken;
import qupath.ext.celltune.model.CohortClusterModel.CentroidsAndCounts;
import qupath.ext.celltune.model.CohortClusterModel.ClusterOutcome;
import qupath.ext.celltune.model.CohortClusterModel.MeasurementAssignment;
import qupath.ext.celltune.model.CohortClusterModel.Pass2Outcome;
import qupath.ext.celltune.model.CohortClusterModel.UuidKey;
import qupath.ext.celltune.model.LeidenModel.LeidenResult;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Tests the deterministic core of the cohort clustering backend: distinct-index
 * sampling and nearest-centroid assignment (the rule that gives every image's
 * cells a cohort-consistent cluster label), plus the all-cells (LEI-06/LEI-08)
 * two-pass driver's pure seams: packed-UUID reorder-safe write-back
 * ({@link CohortClusterModel#labelMapForImage}), pass-2 cancellation bookkeeping
 * ({@link CohortClusterModel#runPass2Loop}), and recall-gate abort handling
 * ({@link CohortClusterModel#clusterOrAbort}). These pure helpers are exercised
 * directly against synthetic/real {@link PathObject}s (no live {@code Project}/
 * {@code ImageData} required — see AnnotationLabelCollectorTest's real-hierarchy
 * construction pattern), mirroring what {@link CohortClusterModel#writeClusterAllCells}
 * itself calls.
 */
class CohortClusterModelTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    /** A small real detection with a real, stable {@code getID()} (like AnnotationLabelCollectorTest's pattern). */
    private static PathObject detectionAt(double x, double y) {
        ROI roi = ROIs.createRectangleROI(x, y, 5, 5, PLANE);
        return PathObjects.createDetectionObject(roi);
    }

    private static List<PathObject> detections(int n, double xOffset) {
        List<PathObject> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cells.add(detectionAt(xOffset + i * 10, 0));
        }
        return cells;
    }

    private static PathObjectHierarchy hierarchyWith(List<PathObject> objects) {
        PathObjectHierarchy h = new PathObjectHierarchy();
        for (PathObject o : objects) {
            h.addObject(o);
        }
        return h;
    }

    // ── LEI-08: packed-UUID reorder-safe write-back ─────────────────────────────

    @Test
    void uuidWriteBackSurvivesReorder() {
        // Two synthetic "images": image A (ordinal 0, 4 cells) and image B
        // (ordinal 1, 5 cells), each with real PathObjects (real getID()s).
        List<PathObject> imageACells = detections(4, 0);
        List<PathObject> imageBCells = detections(5, 1000);
        hierarchyWith(imageACells); // constructed to mirror a real per-image hierarchy; not otherwise used
        hierarchyWith(imageBCells);

        // Simulate pass 1's pooling: capture every cell's packed UUID + source
        // image ordinal, in pooled order (image A's cells first, then image B's).
        List<PathObject> pooledOrder = new ArrayList<>();
        pooledOrder.addAll(imageACells);
        pooledOrder.addAll(imageBCells);

        int m = pooledOrder.size();
        long[] msb = new long[m];
        long[] lsb = new long[m];
        int[] imageOrdinal = new int[m];
        int[] labels = new int[m];
        Map<PathObject, Integer> expectedLabelByCell = new HashMap<>();
        for (int i = 0; i < m; i++) {
            PathObject cell = pooledOrder.get(i);
            msb[i] = cell.getID().getMostSignificantBits();
            lsb[i] = cell.getID().getLeastSignificantBits();
            imageOrdinal[i] = i < imageACells.size() ? 0 : 1;
            labels[i] = i % 3; // an arbitrary synthetic Leiden community label per pooled row
            expectedLabelByCell.put(cell, labels[i]);
        }

        // Pass 2, for image B (ordinal 1): build the reorder-independent lookup
        // via the EXACT production helper.
        Map<UuidKey, Integer> labelMapB = CohortClusterModel.labelMapForImage(msb, lsb, imageOrdinal, labels, 1);

        // Read image B's cells back in a SHUFFLED order (simulating a second
        // readImageData() call returning cells in a different order) and confirm
        // every cell's looked-up label matches its pass-1 pooled-row label BY
        // UUID, not by position.
        List<PathObject> shuffledB = new ArrayList<>(imageBCells);
        Collections.shuffle(shuffledB, new Random(7));
        assertNotEquals(
                imageBCells, shuffledB, "shuffle must actually reorder the list for this test to be meaningful");

        for (PathObject cell : shuffledB) {
            UuidKey key = new UuidKey(
                    cell.getID().getMostSignificantBits(), cell.getID().getLeastSignificantBits());
            Integer looked = labelMapB.get(key);
            assertNotNull(looked, "every image-B cell must resolve a label via its UUID");
            assertEquals(
                    expectedLabelByCell.get(cell),
                    looked,
                    "label must match the pass-1 pooled-row label, not position");
        }

        // Also exercise a REVERSED read order for good measure.
        List<PathObject> reversedB = new ArrayList<>(imageBCells);
        Collections.reverse(reversedB);
        for (PathObject cell : reversedB) {
            UuidKey key = new UuidKey(
                    cell.getID().getMostSignificantBits(), cell.getID().getLeastSignificantBits());
            assertEquals(expectedLabelByCell.get(cell), labelMapB.get(key));
        }

        // image A's cells must NOT appear in image B's label map (ordinal filter).
        for (PathObject cell : imageACells) {
            UuidKey key = new UuidKey(
                    cell.getID().getMostSignificantBits(), cell.getID().getLeastSignificantBits());
            assertNull(labelMapB.get(key), "image A's cells must not leak into image B's label map");
        }
    }

    // ── LEI-08/LEI-10: pooling identity ──────────────────────────────────────────

    @Test
    void poolingIdentityEveryUuidResolvesToExactlyOneLabelPerImage() {
        List<PathObject> imageACells = detections(6, 0);
        List<PathObject> imageBCells = detections(3, 1000);

        List<PathObject> pooledOrder = new ArrayList<>();
        pooledOrder.addAll(imageACells);
        pooledOrder.addAll(imageBCells);
        int totalCells = imageACells.size() + imageBCells.size();

        int m = pooledOrder.size();
        long[] msb = new long[m];
        long[] lsb = new long[m];
        int[] imageOrdinal = new int[m];
        int[] labels = new int[m];
        for (int i = 0; i < m; i++) {
            PathObject cell = pooledOrder.get(i);
            msb[i] = cell.getID().getMostSignificantBits();
            lsb[i] = cell.getID().getLeastSignificantBits();
            imageOrdinal[i] = i < imageACells.size() ? 0 : 1;
            labels[i] = i;
        }

        // Pooled row count == total detections across both images.
        assertEquals(totalCells, m, "pooled row count must equal total detections across all images");

        Map<UuidKey, Integer> mapA = CohortClusterModel.labelMapForImage(msb, lsb, imageOrdinal, labels, 0);
        Map<UuidKey, Integer> mapB = CohortClusterModel.labelMapForImage(msb, lsb, imageOrdinal, labels, 1);

        // Each image's map has exactly that image's cell count -- no collisions, no
        // missing entries -- and every pooled UUID resolves back to exactly one label.
        assertEquals(imageACells.size(), mapA.size());
        assertEquals(imageBCells.size(), mapB.size());
        for (PathObject cell : imageACells) {
            UuidKey key = new UuidKey(
                    cell.getID().getMostSignificantBits(), cell.getID().getLeastSignificantBits());
            assertTrue(mapA.containsKey(key));
            assertFalse(mapB.containsKey(key), "image A cell must not resolve in image B's map");
        }
        for (PathObject cell : imageBCells) {
            UuidKey key = new UuidKey(
                    cell.getID().getMostSignificantBits(), cell.getID().getLeastSignificantBits());
            assertTrue(mapB.containsKey(key));
            assertFalse(mapA.containsKey(key), "image B cell must not resolve in image A's map");
        }
    }

    // ── LEI-10: cancel leaves already-written images intact ─────────────────────

    @Test
    void cancelLeavesWrittenIntactAndReportsUnwrittenImages() {
        List<String> images = List.of("img1", "img2", "img3", "img4");
        CancellationToken token = new CancellationToken();

        // "img2" is written successfully; cancel() fires WHILE processing img2 (as
        // if the user clicked Cancel mid-write), which runPass2Loop must only
        // observe on the NEXT iteration (it checks isCancelled() at the top of
        // each iteration, matching writeClusterAllCells's real per-image loop).
        List<String> processed = new ArrayList<>();
        Pass2Outcome outcome = CohortClusterModel.runPass2Loop(images, token, (ordinal, name) -> {
            processed.add(name);
            if (name.equals("img2")) {
                token.cancel();
                return true; // img2 itself still gets written before cancellation is observed
            }
            return true;
        });

        assertEquals(
                List.of("img1", "img2"), outcome.written(), "img1/img2 processed before cancellation was observed");
        assertEquals(List.of("img3", "img4"), outcome.notWritten(), "img3/img4 must be reported unwritten");
        assertTrue(outcome.cancelled(), "outcome must report the run was cancelled");
        assertEquals(
                List.of("img1", "img2"),
                processed,
                "processImage must never be invoked for img3/img4 after cancellation");
    }

    @Test
    void noCancellationProcessesEveryImage() {
        List<String> images = List.of("a", "b", "c");
        Pass2Outcome outcome =
                CohortClusterModel.runPass2Loop(images, new CancellationToken(), (ordinal, name) -> true);
        assertEquals(images, outcome.written());
        assertTrue(outcome.notWritten().isEmpty());
        assertFalse(outcome.cancelled());
    }

    @Test
    void failedImagesAreReportedNotWrittenWithoutStoppingTheLoop() {
        List<String> images = List.of("ok1", "fails", "ok2");
        Pass2Outcome outcome = CohortClusterModel.runPass2Loop(
                images, new CancellationToken(), (ordinal, name) -> !name.equals("fails"));
        assertEquals(List.of("ok1", "ok2"), outcome.written());
        assertEquals(List.of("fails"), outcome.notWritten());
        assertFalse(outcome.cancelled());
    }

    @Test
    void cancellationTokenReflectsCancelCall() {
        CancellationToken token = new CancellationToken();
        assertFalse(token.isCancelled());
        token.cancel();
        assertTrue(token.isCancelled());
    }

    // ── LEI-10: recall-gate abort writes nothing ─────────────────────────────────

    @Test
    void abortWritesNothingWhenRecallGateFails() {
        ClusterOutcome outcome = CohortClusterModel.clusterOrAbort(() -> {
            throw new AnnRecallException("ANN recall 0.42 remained below the required 0.95 threshold");
        });

        assertTrue(outcome.aborted(), "a thrown AnnRecallException must be reported as an abort");
        assertNull(outcome.result(), "no LeidenResult (and therefore no labels) on abort");
        assertNotNull(outcome.abortMessage());
        assertTrue(outcome.abortMessage().contains("0.42"));
    }

    @Test
    void successfulClusterCallIsNotAborted() {
        LeidenResult fakeResult = new LeidenResult(new int[] {0, 1, 0}, 2);
        ClusterOutcome outcome = CohortClusterModel.clusterOrAbort(() -> fakeResult);

        assertFalse(outcome.aborted());
        assertSame(fakeResult, outcome.result());
        assertNull(outcome.abortMessage());
    }

    // ── centroidsAndCounts (per-cluster centroid + pooled count over labels) ────

    @Test
    void centroidsAndCountsComputesMeanZScoredRowPerCluster() {
        // 3 markers, 2 clusters: cluster 0 = rows 0,1; cluster 1 = rows 2,3,4.
        double[][] rows = {
            {1.0, 2.0, 3.0},
            {3.0, 4.0, 5.0},
            {10.0, 0.0, -10.0},
            {20.0, 0.0, -20.0},
            {30.0, 0.0, -30.0},
        };
        int[] labels = {0, 0, 1, 1, 1};

        CentroidsAndCounts result = CohortClusterModel.centroidsAndCounts(rows, labels, 2, 3);

        assertArrayEquals(new int[] {2, 3}, result.counts());
        assertArrayEquals(new double[] {2.0, 3.0, 4.0}, result.centroids()[0], 1e-9);
        assertArrayEquals(new double[] {20.0, 0.0, -20.0}, result.centroids()[1], 1e-9);
    }

    @Test
    void centroidsAndCountsLeavesEmptyClustersAtZero() {
        double[][] rows = {{5.0, 5.0}};
        int[] labels = {0};

        // nClusters=3 but only cluster 0 has any pooled row -- clusters 1/2 stay all-zero.
        CentroidsAndCounts result = CohortClusterModel.centroidsAndCounts(rows, labels, 3, 2);

        assertArrayEquals(new int[] {1, 0, 0}, result.counts());
        assertArrayEquals(new double[] {5.0, 5.0}, result.centroids()[0], 1e-9);
        assertArrayEquals(new double[] {0.0, 0.0}, result.centroids()[1], 1e-9);
        assertArrayEquals(new double[] {0.0, 0.0}, result.centroids()[2], 1e-9);
    }

    @Test
    void centroidsAndCountsSkipsOutOfRangeLabelsDefensively() {
        double[][] rows = {{1.0}, {2.0}, {3.0}};
        // Label -1 (unclustered sentinel) and an out-of-range label must never crash or
        // pollute a real cluster's centroid.
        int[] labels = {-1, 0, 99};

        CentroidsAndCounts result = CohortClusterModel.centroidsAndCounts(rows, labels, 1, 1);

        assertArrayEquals(new int[] {1}, result.counts());
        assertArrayEquals(new double[] {2.0}, result.centroids()[0], 1e-9);
    }

    // ── classForMeasurementValue / assignmentsFromMeasurement (all-cells assign) ─

    @Test
    void classForMeasurementValueDecodesOneBasedWrittenValue() {
        PathClass tumor = PathClass.fromString("Tumor");
        PathClass stroma = PathClass.fromString("Stroma");
        Map<Integer, PathClass> mapping = Map.of(0, tumor, 2, stroma);

        // Written value is 1-based (label + 1): cluster 0 -> value 1.0, cluster 2 -> value 3.0.
        assertEquals(tumor, CohortClusterModel.classForMeasurementValue(1.0, mapping));
        assertEquals(stroma, CohortClusterModel.classForMeasurementValue(3.0, mapping));
    }

    @Test
    void classForMeasurementValueReturnsNullForNaNUnclusteredOrUnmapped() {
        PathClass tumor = PathClass.fromString("Tumor");
        Map<Integer, PathClass> mapping = Map.of(0, tumor);

        assertNull(CohortClusterModel.classForMeasurementValue(Double.NaN, mapping), "missing measurement -> no class");
        // -1 (unclustered sentinel) decodes to label -2, never a real mapping key.
        assertNull(CohortClusterModel.classForMeasurementValue(-1.0, mapping), "unclustered cell -> no class");
        // Cluster 1 exists in the written data but the user chose "skip" for it (absent from mapping).
        assertNull(CohortClusterModel.classForMeasurementValue(2.0, mapping), "unmapped cluster -> no class (skip)");
    }

    @Test
    void assignmentsFromMeasurementMatchesOnlyMappedWrittenClusters() {
        PathClass tumor = PathClass.fromString("Tumor");
        PathClass stroma = PathClass.fromString("Stroma");
        Map<Integer, PathClass> mapping = Map.of(0, tumor, 1, stroma);

        PathObject clusterZero = detectionAt(0, 0); // will carry written value 1.0 -> cluster 0 -> tumor
        PathObject clusterOne = detectionAt(10, 0); // written value 2.0 -> cluster 1 -> stroma
        PathObject skipped = detectionAt(20, 0); // written value 3.0 -> cluster 2 -> not in mapping, skipped
        PathObject unclustered = detectionAt(30, 0); // written value -1.0 -> unclustered, skipped
        PathObject noMeasurement = detectionAt(40, 0); // never written, NaN, skipped

        clusterZero.getMeasurementList().put(CohortClusterModel.CLUSTER_MEASUREMENT, 1.0);
        clusterOne.getMeasurementList().put(CohortClusterModel.CLUSTER_MEASUREMENT, 2.0);
        skipped.getMeasurementList().put(CohortClusterModel.CLUSTER_MEASUREMENT, 3.0);
        unclustered.getMeasurementList().put(CohortClusterModel.CLUSTER_MEASUREMENT, -1.0);

        List<PathObject> cells = List.of(clusterZero, clusterOne, skipped, unclustered, noMeasurement);
        MeasurementAssignment assignment = CohortClusterModel.assignmentsFromMeasurement(cells, mapping);

        assertEquals(2, assignment.objects().size(), "only the two mapped, clustered cells are matched");
        assertEquals(List.of(clusterZero, clusterOne), assignment.objects());
        assertEquals(List.of(tumor, stroma), assignment.classes());
    }

    // ── nearestCentroid ──────────────────────────────────────────────────────

    @Test
    void nearestCentroidPicksClosestBySquaredEuclidean() {
        double[][] cents = {{0, 0}, {10, 10}};
        assertEquals(0, CohortClusterModel.nearestCentroid(new double[] {1, 1}, cents));
        assertEquals(1, CohortClusterModel.nearestCentroid(new double[] {9, 9}, cents));
        assertEquals(0, CohortClusterModel.nearestCentroid(new double[] {4.9, 4.9}, cents));
        assertEquals(1, CohortClusterModel.nearestCentroid(new double[] {5.1, 5.1}, cents));
    }

    @Test
    void nearestCentroidBreaksTiesToFirst() {
        double[][] cents = {{0, 0}, {2, 2}};
        // Equidistant point → the first centroid wins (strict < comparison).
        assertEquals(0, CohortClusterModel.nearestCentroid(new double[] {1, 1}, cents));
    }

    @Test
    void nearestCentroidWorksWithSingleCluster() {
        double[][] cents = {{3, 3, 3}};
        assertEquals(0, CohortClusterModel.nearestCentroid(new double[] {-5, 100, 0}, cents));
    }

    // ── sampleIndices ────────────────────────────────────────────────────────

    @Test
    void sampleIndicesReturnsAllWhenCountAtLeastN() {
        int[] all = CohortClusterModel.sampleIndices(5, 5, new Random(1));
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, all);

        int[] capped = CohortClusterModel.sampleIndices(3, 10, new Random(1));
        assertArrayEquals(new int[] {0, 1, 2}, capped);
    }

    @Test
    void sampleIndicesReturnsRequestedCountOfDistinctInRangeIndices() {
        int n = 100;
        int count = 20;
        int[] pick = CohortClusterModel.sampleIndices(n, count, new Random(42));

        assertEquals(count, pick.length);
        // All in range.
        assertTrue(Arrays.stream(pick).allMatch(i -> i >= 0 && i < n));
        // All distinct.
        assertEquals(count, Arrays.stream(pick).distinct().count(), "indices must be distinct");
    }

    @Test
    void sampleIndicesIsDeterministicForAFixedSeed() {
        int[] a = CohortClusterModel.sampleIndices(50, 10, new Random(7));
        int[] b = CohortClusterModel.sampleIndices(50, 10, new Random(7));
        assertArrayEquals(a, b, "same seed → same sample");
    }

    @Test
    void sampleIndicesCoversWholeRangeOverManyDraws() {
        // Sanity: with count==n via the fast path we get every index exactly once.
        int[] pick = CohortClusterModel.sampleIndices(8, 8, new Random(0));
        assertEquals(8, IntStream.of(pick).distinct().count());
    }
}
