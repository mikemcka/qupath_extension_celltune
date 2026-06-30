package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.celltune.model.NeighborhoodModel.ClusterResult;

/**
 * Unit tests for {@link NeighborhoodModel}: the pure neighbor/composition/cluster
 * math behind cellular-neighborhood analysis (CN-01, CN-02, CN-04). Synthetic
 * clouds only — no QuPath/JavaFX APIs — mirroring {@code DistanceMeasurementsDialogTest}
 * and {@code ScatterMathTest}.
 */
class NeighborhoodModelTest {

    private static final double EPS = 1e-9;

    // ── kNN ──────────────────────────────────────────────────────────────────

    @Test
    void knnMatchesBruteForceSetOnRandomCloud() {
        Random rng = new Random(42);
        int n = 200;
        int k = 8;
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = rng.nextDouble() * 1000.0;
            ys[i] = rng.nextDouble() * 1000.0;
        }
        int[][] actual = NeighborhoodModel.kNearestNeighborIndices(xs, ys, k);
        for (int i = 0; i < n; i++) {
            assertEquals(k, actual[i].length, "Expected exactly k neighbours at index " + i);
            assertFalse(toSet(actual[i]).contains(i), "Self must be excluded at index " + i);
            Set<Integer> expected = bruteForceKnn(xs, ys, i, k);
            assertEquals(expected, toSet(actual[i]), "kNN set mismatch at index " + i);
        }
    }

    @Test
    void knnExcludesSelfWithDuplicateCoordinates() {
        double[] xs = {7.0, 7.0, 100.0};
        double[] ys = {7.0, 7.0, 100.0};
        int[][] out = NeighborhoodModel.kNearestNeighborIndices(xs, ys, 1);
        assertEquals(1, out[0].length);
        assertEquals(1, out[0][0], "Duplicate at same location must match its twin, not itself");
        assertEquals(1, out[1].length);
        assertEquals(0, out[1][0]);
    }

    @Test
    void knnReturnsNMinusOneWhenFewerThanK() {
        double[] xs = {0.0, 1.0, 2.0};
        double[] ys = {0.0, 0.0, 0.0};
        int[][] out = NeighborhoodModel.kNearestNeighborIndices(xs, ys, 10);
        for (int i = 0; i < 3; i++) {
            assertEquals(2, out[i].length, "n-1 neighbours when n-1 < k at index " + i);
            assertFalse(toSet(out[i]).contains(i));
        }
    }

    @Test
    void knnNanCoordinatesAreEmptyAndIgnoredByOthers() {
        double[] xs = {0.0, Double.NaN, 10.0};
        double[] ys = {0.0, Double.NaN, 0.0};
        int[][] out = NeighborhoodModel.kNearestNeighborIndices(xs, ys, 1);
        assertEquals(0, out[1].length, "NaN-coord point has no window");
        assertEquals(1, out[0].length);
        assertEquals(2, out[0][0], "Valid point ignores the NaN neighbour");
        assertEquals(0, out[2][0], "Valid point ignores the NaN neighbour");
    }

    // ── radius ─────────────────────────────────────────────────────────────────

    @Test
    void radiusMatchesBruteForceOnRandomCloud() {
        Random rng = new Random(7);
        int n = 300;
        double radius = 80.0;
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = rng.nextDouble() * 1000.0;
            ys[i] = rng.nextDouble() * 1000.0;
        }
        int[][] actual = NeighborhoodModel.radiusNeighborIndices(xs, ys, radius);
        double r2 = radius * radius;
        for (int i = 0; i < n; i++) {
            Set<Integer> expected = new HashSet<>();
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                double dx = xs[i] - xs[j];
                double dy = ys[i] - ys[j];
                if (dx * dx + dy * dy <= r2) {
                    expected.add(j);
                }
            }
            assertEquals(expected, toSet(actual[i]), "radius window mismatch at index " + i);
            assertFalse(toSet(actual[i]).contains(i), "Self must be excluded at index " + i);
        }
    }

    @Test
    void radiusTooSmallYieldsEmptyWindows() {
        double[] xs = {0.0, 10.0, 20.0};
        double[] ys = {0.0, 0.0, 0.0};
        int[][] out = NeighborhoodModel.radiusNeighborIndices(xs, ys, 1.0);
        for (int[] w : out) {
            assertEquals(0, w.length);
        }
    }

    // ── composition ─────────────────────────────────────────────────────────────

    @Test
    void compositionRowsSumToOneAndHonorIncludeCenter() {
        // typeId: cells 0,1 = type0; cell2 = type1; cell3 = ignored.
        int[] typeId = {0, 0, 1, -1};
        int[][] neighbors = {{1, 2, 3}, {0}, {0, 1}, {}};
        int nTypes = 2;

        double[][] noCenter = NeighborhoodModel.compositionMatrix(neighbors, typeId, nTypes, false);
        // cell0 window {1(t0), 2(t1), 3(ignored)} → counts t0=1,t1=1, base=2.
        assertArrayEqualsD(new double[] {0.5, 0.5}, noCenter[0]);
        assertEquals(1.0, noCenter[0][0] + noCenter[0][1], EPS);
        // cell3 is isolated (empty window) → all-zero row.
        assertArrayEqualsD(new double[] {0.0, 0.0}, noCenter[3]);

        double[][] withCenter = NeighborhoodModel.compositionMatrix(neighbors, typeId, nTypes, true);
        // cell0 + center(t0): t0=2,t1=1, base=3.
        assertArrayEqualsD(new double[] {2.0 / 3.0, 1.0 / 3.0}, withCenter[0]);
    }

    @Test
    void compositionExcludesIgnoredFromCountsAndBase() {
        int[] typeId = {0, -1, -1};
        int[][] neighbors = {{1, 2}, {}, {}};
        double[][] comp = NeighborhoodModel.compositionMatrix(neighbors, typeId, 1, false);
        // cell0's only neighbours are ignored → empty effective window → zero row.
        assertArrayEqualsD(new double[] {0.0}, comp[0]);
    }

    // ── clustering ───────────────────────────────────────────────────────────────

    @Test
    void clusterRecoversTwoSeparatedBlobsByPurity() {
        Random rng = new Random(11);
        int per = 60;
        int n = per * 2;
        double[][] comp = new double[n][2];
        for (int i = 0; i < per; i++) {
            comp[i][0] = 0.95 + rng.nextDouble() * 0.05; // blob A near (1,0)
            comp[i][1] = 1.0 - comp[i][0];
        }
        for (int i = per; i < n; i++) {
            comp[i][1] = 0.95 + rng.nextDouble() * 0.05; // blob B near (0,1)
            comp[i][0] = 1.0 - comp[i][1];
        }
        ClusterResult res = NeighborhoodModel.clusterCompositions(comp, 2);
        assertEquals(2, res.kEffective());
        // Each blob should be internally pure: one dominant label covering it.
        assertTrue(purity(res.labels(), 0, per) > 0.95, "Blob A not pure");
        assertTrue(purity(res.labels(), per, n) > 0.95, "Blob B not pure");
        // And the two blobs must land in different clusters.
        assertFalse(
                res.labels()[0] == res.labels()[n - 1] && purity(res.labels(), 0, n) > 0.95,
                "Blobs should be separated into distinct clusters");
    }

    @Test
    void clusterKEffectiveCappedAtRowCount() {
        double[][] comp = {{1, 0}, {0, 1}, {0.5, 0.5}};
        ClusterResult res = NeighborhoodModel.clusterCompositions(comp, 10);
        assertEquals(3, res.kEffective(), "kEffective must be min(k, nRows)");
    }

    // ── cnMeanComposition ──────────────────────────────────────────────────────

    @Test
    void cnMeanCompositionEqualsHandComputedColumnMeans() {
        int[] labels = {0, 0, 1};
        double[][] comp = {{1.0, 0.0}, {0.5, 0.5}, {0.0, 1.0}};
        double[][] mean = NeighborhoodModel.cnMeanComposition(labels, comp, 2);
        assertArrayEqualsD(new double[] {0.75, 0.25}, mean[0]);
        assertArrayEqualsD(new double[] {0.0, 1.0}, mean[1]);
    }

    // ── diversity + adjacency ─────────────────────────────────────────────────────

    @Test
    void compositionDiversityRewardsEvennessAndRichness() {
        assertEquals(0.0, NeighborhoodModel.compositionDiversity(new double[] {1, 0, 0, 0}), EPS, "single type → 0");
        assertEquals(0.0, NeighborhoodModel.compositionDiversity(new double[] {0, 0, 0, 0}), EPS, "empty → 0");
        assertEquals(
                1.0,
                NeighborhoodModel.compositionDiversity(new double[] {0.25, 0.25, 0.25, 0.25}),
                EPS,
                "even over all types → 1");
        // Even over 2 of 4 types: ln(2)/ln(4) = 0.5.
        assertEquals(0.5, NeighborhoodModel.compositionDiversity(new double[] {0.5, 0.5, 0, 0}), EPS);
        // More even mixes score higher than dominated ones.
        double mixed = NeighborhoodModel.compositionDiversity(new double[] {0.4, 0.3, 0.3, 0.0});
        double dominated = NeighborhoodModel.compositionDiversity(new double[] {0.9, 0.05, 0.05, 0.0});
        assertTrue(mixed > dominated, "more even mix should be more diverse");
    }

    @Test
    void cnAdjacencyCountsTouchingPairsBothDirections() {
        int[] cn = {1, 2, 1};
        int[][] neighbors = {{1}, {0, 2}, {1}};
        double[][] adj = NeighborhoodModel.cnAdjacency(neighbors, cn, 2);
        assertEquals(2.0, adj[0][1], EPS); // CN1 sees CN2: cell0→1 and cell2→1
        assertEquals(2.0, adj[1][0], EPS); // CN2 sees CN1: cell1→0 and cell1→2
        // Same-CN neighbours and out-of-range ids contribute nothing.
        int[] cnWithEmpty = {1, -1, 1};
        double[][] adj2 = NeighborhoodModel.cnAdjacency(new int[][] {{1, 2}, {0}, {0}}, cnWithEmpty, 2);
        assertEquals(0.0, adj2[0][0], EPS);
        assertEquals(0.0, adj2[0][1], EPS);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Set<Integer> bruteForceKnn(double[] xs, double[] ys, int i, int k) {
        int n = xs.length;
        Integer[] order = new Integer[n];
        double[] d2 = new double[n];
        for (int j = 0; j < n; j++) {
            order[j] = j;
            double dx = xs[i] - xs[j];
            double dy = ys[i] - ys[j];
            d2[j] = (j == i) ? Double.POSITIVE_INFINITY : dx * dx + dy * dy;
        }
        Arrays.sort(order, (p, q) -> Double.compare(d2[p], d2[q]));
        Set<Integer> set = new HashSet<>();
        for (int t = 0; t < k && t < n; t++) {
            set.add(order[t]);
        }
        return set;
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

    private static void assertArrayEqualsD(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], EPS, "Mismatch at column " + i);
        }
    }
}
