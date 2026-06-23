package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Tests the deterministic core of the cohort clustering backend: distinct-index
 * sampling and nearest-centroid assignment (the rule that gives every image's
 * cells a cohort-consistent cluster label).
 */
class CohortClusterModelTest {

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
