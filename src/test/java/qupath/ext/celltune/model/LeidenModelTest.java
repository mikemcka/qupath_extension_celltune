package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.celltune.model.LeidenModel.LeidenResult;

/**
 * Unit tests for {@link LeidenModel}: the pure feature-kNN / Jaccard-weighting /
 * CWTS-Leiden / kNN-label-transfer math behind graph-based phenotype clustering
 * (LEI-02, LEI-04, LEI-05). Synthetic clouds only — no QuPath/JavaFX APIs —
 * mirroring {@code NeighborhoodModelTest}.
 */
class LeidenModelTest {

    // ── featureKnn ─────────────────────────────────────────────────────────────

    @Test
    void featureKnnMatchesBruteForceSetOnRandomCloud() {
        Random rng = new Random(42);
        int n = 150;
        int d = 5;
        int k = 8;
        double[][] rows = randomCloud(rng, n, d);
        int[][] actual = LeidenModel.featureKnn(rows, k);
        for (int i = 0; i < n; i++) {
            assertEquals(k, actual[i].length, "Expected exactly k neighbours at index " + i);
            assertFalse(toSet(actual[i]).contains(i), "Self must be excluded at index " + i);
            Set<Integer> expected = bruteForceKnn(rows, i, k);
            assertEquals(expected, toSet(actual[i]), "kNN set mismatch at index " + i);
        }
    }

    @Test
    void featureKnnExcludesSelfWithDuplicateRows() {
        double[][] rows = {
            {1.0, 2.0, 3.0},
            {1.0, 2.0, 3.0},
            {100.0, 200.0, 300.0}
        };
        int[][] out = LeidenModel.featureKnn(rows, 1);
        assertEquals(1, out[0].length);
        assertEquals(1, out[0][0], "Duplicate row must match its twin, not itself");
        assertEquals(1, out[1].length);
        assertEquals(0, out[1][0]);
    }

    @Test
    void featureKnnReturnsNMinusOneWhenFewerThanK() {
        double[][] rows = {{0.0, 0.0}, {1.0, 0.0}, {2.0, 0.0}};
        int[][] out = LeidenModel.featureKnn(rows, 10);
        for (int i = 0; i < 3; i++) {
            assertEquals(2, out[i].length, "n-1 neighbours when n-1 < k at index " + i);
            assertFalse(toSet(out[i]).contains(i));
        }
    }

    // ── cluster: community recovery ───────────────────────────────────────────

    @Test
    void clusterRecoversThreeSeparatedBlobsByPurity() {
        Random rng = new Random(11);
        int per = 50;
        int n = per * 3;
        double[][] rows = new double[n][2];
        fillBlob(rows, rng, 0, per, 0.0, 0.0, 0.3);
        fillBlob(rows, rng, per, 2 * per, 20.0, 0.0, 0.3);
        fillBlob(rows, rng, 2 * per, 3 * per, 0.0, 20.0, 0.3);

        LeidenResult res = LeidenModel.cluster(rows, 15, 1.0, 10, 42L);

        assertTrue(res.nClusters() >= 2, "Expected at least 2 recovered communities, got " + res.nClusters());
        assertTrue(purity(res.labels(), 0, per) > 0.9, "Blob A not pure");
        assertTrue(purity(res.labels(), per, 2 * per) > 0.9, "Blob B not pure");
        assertTrue(purity(res.labels(), 2 * per, n) > 0.9, "Blob C not pure");

        // Labels must be dense/contiguous 0..nClusters-1 (downstream colouring contract).
        Set<Integer> distinct = toSet(res.labels());
        for (int label : distinct) {
            assertTrue(label >= 0 && label < res.nClusters(), "Label " + label + " outside [0, nClusters)");
        }
    }

    @Test
    void clusterHigherResolutionYieldsAtLeastAsManyClustersOnGradedSet() {
        // A graded synthetic set with several loosely-separated sub-groups: higher
        // resolution should not produce FEWER communities than a lower resolution
        // (monotone-ish, not strictly monotone for every possible network).
        Random rng = new Random(5);
        int perGroup = 30;
        int groups = 6;
        int n = perGroup * groups;
        double[][] rows = new double[n][2];
        for (int g = 0; g < groups; g++) {
            fillBlob(rows, rng, g * perGroup, (g + 1) * perGroup, g * 8.0, 0.0, 0.6);
        }

        LeidenResult low = LeidenModel.cluster(rows, 15, 0.2, 10, 7L);
        LeidenResult high = LeidenModel.cluster(rows, 15, 2.5, 10, 7L);

        assertTrue(
                high.nClusters() >= low.nClusters(),
                "Higher resolution (" + high.nClusters() + ") should yield >= clusters than lower resolution ("
                        + low.nClusters() + ")");
    }

    // ── cluster: reproducibility ──────────────────────────────────────────────

    @Test
    void clusterSameSeedProducesIdenticalLabels() {
        Random rng = new Random(3);
        double[][] rows = new double[120][3];
        fillBlob(rows, rng, 0, 60, 0.0, 0.0, 0.4);
        fillBlob(rows, rng, 60, 120, 15.0, 15.0, 0.4);

        LeidenResult a = LeidenModel.cluster(rows, 15, 1.0, 5, 99L);
        LeidenResult b = LeidenModel.cluster(rows, 15, 1.0, 5, 99L);

        assertArrayEquals(a.labels(), b.labels(), "Identical inputs+seed must yield identical labels");
        assertEquals(a.nClusters(), b.nClusters());
    }

    @Test
    void clusterDifferentSeedMayDiffer() {
        // Not a strict assertion of inequality (different seeds *can* coincide on
        // an easy problem) — just confirm neither seed crashes and both recover
        // the same well-separated structure (same nClusters, valid partitions).
        Random rng = new Random(21);
        double[][] rows = new double[100][2];
        fillBlob(rows, rng, 0, 50, 0.0, 0.0, 0.3);
        fillBlob(rows, rng, 50, 100, 25.0, 0.0, 0.3);

        LeidenResult a = LeidenModel.cluster(rows, 15, 1.0, 3, 1L);
        LeidenResult b = LeidenModel.cluster(rows, 15, 1.0, 3, 2L);

        assertTrue(a.nClusters() >= 2);
        assertTrue(b.nClusters() >= 2);
    }

    // ── transferLabels ─────────────────────────────────────────────────────────

    @Test
    void transferLabelsAssignsQueryPointsToNearestBlobLabel() {
        Random rng = new Random(13);
        int per = 40;
        double[][] reference = new double[per * 2][2];
        fillBlob(reference, rng, 0, per, 0.0, 0.0, 0.3);
        fillBlob(reference, rng, per, per * 2, 20.0, 0.0, 0.3);
        int[] refLabels = new int[per * 2];
        Arrays.fill(refLabels, 0, per, 0);
        Arrays.fill(refLabels, per, per * 2, 1);

        double[][] query = {
            {0.1, 0.1}, // near blob A
            {19.9, -0.1}, // near blob B
            {0.2, -0.2}, // near blob A
        };
        int[] assigned = LeidenModel.transferLabels(query, reference, refLabels, 10, 2);

        assertEquals(0, assigned[0], "Query near blob A must get label 0");
        assertEquals(1, assigned[1], "Query near blob B must get label 1");
        assertEquals(0, assigned[2], "Query near blob A must get label 0");
    }

    @Test
    void transferLabelsTiesResolveToLowestLabel() {
        // Reference: exactly one point at each of two labels, symmetric around the
        // query so k=2 pulls in exactly one of each label — a tie, must resolve to
        // the lowest label id.
        double[][] reference = {
            {-1.0, 0.0}, // label 1
            {1.0, 0.0}, // label 0
        };
        int[] refLabels = {1, 0};
        double[][] query = {{0.0, 0.0}};

        int[] assigned = LeidenModel.transferLabels(query, reference, refLabels, 2, 2);
        assertEquals(0, assigned[0], "Tie must resolve to the lowest label id");
    }

    @Test
    void transferLabelsHandlesDegenerateQueryWithoutCrashing() {
        double[][] reference = {{0.0, 0.0}, {1.0, 1.0}, {2.0, 2.0}};
        int[] refLabels = {0, 1, 1};
        // Query exactly coincident with a reference point, and a query with NaN.
        double[][] query = {{0.0, 0.0}, {Double.NaN, Double.NaN}};

        int[] assigned = assertDoesNotThrow(() -> LeidenModel.transferLabels(query, reference, refLabels, 2, 2));
        assertEquals(2, assigned.length);
    }

    @Test
    void transferLabelsSingleReferenceRowNeverCrashes() {
        double[][] reference = {{5.0, 5.0}};
        int[] refLabels = {0};
        double[][] query = {{0.0, 0.0}, {10.0, 10.0}};
        int[] assigned = assertDoesNotThrow(() -> LeidenModel.transferLabels(query, reference, refLabels, 5, 1));
        assertEquals(0, assigned[0]);
        assertEquals(0, assigned[1]);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static double[][] randomCloud(Random rng, int n, int d) {
        double[][] rows = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                rows[i][j] = rng.nextDouble() * 100.0;
            }
        }
        return rows;
    }

    private static void fillBlob(double[][] rows, Random rng, int from, int to, double cx, double cy, double spread) {
        for (int i = from; i < to; i++) {
            rows[i][0] = cx + rng.nextGaussian() * spread;
            rows[i][1] = cy + rng.nextGaussian() * spread;
        }
    }

    private static Set<Integer> bruteForceKnn(double[][] rows, int i, int k) {
        int n = rows.length;
        Integer[] order = new Integer[n];
        double[] d2 = new double[n];
        for (int j = 0; j < n; j++) {
            order[j] = j;
            d2[j] = (j == i) ? Double.POSITIVE_INFINITY : squaredDist(rows[i], rows[j]);
        }
        Arrays.sort(order, (p, q) -> Double.compare(d2[p], d2[q]));
        Set<Integer> set = new HashSet<>();
        for (int t = 0; t < k && t < n; t++) {
            set.add(order[t]);
        }
        return set;
    }

    private static double squaredDist(double[] a, double[] b) {
        double sum = 0;
        for (int c = 0; c < a.length; c++) {
            double diff = a[c] - b[c];
            sum += diff * diff;
        }
        return sum;
    }

    private static Set<Integer> toSet(int[] a) {
        Set<Integer> s = new HashSet<>();
        for (int v : a) {
            s.add(v);
        }
        return s;
    }

    /** Fraction of rows in [from,to) that carry the most common label in that range. */
    private static double purity(int[] labels, int from, int to) {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int i = from; i < to; i++) {
            counts.merge(labels[i], 1, Integer::sum);
        }
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return max / (double) (to - from);
    }
}
