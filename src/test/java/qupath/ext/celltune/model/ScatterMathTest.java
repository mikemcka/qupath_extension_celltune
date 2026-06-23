package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the pure-Java numerical core of the scatter plot: column standardisation,
 * deterministic subsampling, and point-in-polygon hit-testing. The PCA/UMAP
 * embeddings are not exercised here — they load native math libraries (OpenBLAS /
 * ARPACK) that require {@code java.base/java.lang} to be opened, so they are
 * covered by manual QuPath QA rather than unit tests.
 */
class ScatterMathTest {

    private static final double EPS = 1e-9;

    // ── standardizeColumns ───────────────────────────────────────────────────

    @Test
    void standardizeColumnsGivesZeroMeanUnitSdPerColumn() {
        double[][] data = {{1, 10}, {2, 20}, {3, 30}};
        double[] mean = new double[2];
        double[] sd = new double[2];
        double[][] z = ScatterMath.standardizeColumns(data, mean, sd);

        // Captured per-column mean/sd (population sd, /n).
        assertEquals(2.0, mean[0], EPS);
        assertEquals(20.0, mean[1], EPS);
        assertEquals(Math.sqrt(2.0 / 3.0), sd[0], EPS);
        assertEquals(Math.sqrt(200.0 / 3.0), sd[1], EPS);

        // Each output column has ~zero mean and ~unit sd.
        for (int j = 0; j < 2; j++) {
            double m = 0;
            for (double[] row : z) {
                m += row[j];
            }
            m /= z.length;
            assertEquals(0.0, m, EPS, "column " + j + " mean");
            double var = 0;
            for (double[] row : z) {
                var += (row[j] - m) * (row[j] - m);
            }
            assertEquals(1.0, Math.sqrt(var / z.length), EPS, "column " + j + " sd");
        }
    }

    @Test
    void standardizeColumnsZeroesConstantColumns() {
        double[][] data = {{5, 1}, {5, 2}, {5, 3}};
        double[] mean = new double[2];
        double[] sd = new double[2];
        double[][] z = ScatterMath.standardizeColumns(data, mean, sd);

        assertEquals(5.0, mean[0], EPS);
        assertEquals(0.0, sd[0], EPS);
        // sd < 1e-9 → column maps to 0, never NaN/Inf.
        for (double[] row : z) {
            assertEquals(0.0, row[0], EPS);
        }
    }

    @Test
    void standardizeColumnsHandlesEmptyInput() {
        double[][] z = ScatterMath.standardizeColumns(new double[0][]);
        assertEquals(0, z.length);
    }

    @Test
    void standardizeColumnsSingleColumnConvenienceOverloadMatches() {
        double[][] data = {{1}, {2}, {3}, {4}};
        double[][] a = ScatterMath.standardizeColumns(data);
        double[][] b = ScatterMath.standardizeColumns(data, new double[1], new double[1]);
        for (int i = 0; i < data.length; i++) {
            assertArrayEquals(b[i], a[i], EPS);
        }
    }

    // ── identity / randomSubsample ───────────────────────────────────────────

    @Test
    void identityIsAscendingRange() {
        assertArrayEquals(new int[] {0, 1, 2, 3}, ScatterMath.identity(4));
        assertArrayEquals(new int[] {}, ScatterMath.identity(0));
    }

    @Test
    void randomSubsampleReturnsDistinctSortedInRangeIndices() {
        int n = 200;
        int count = 30;
        int[] pick = ScatterMath.randomSubsample(n, count);

        assertEquals(count, pick.length);
        assertTrue(Arrays.stream(pick).allMatch(i -> i >= 0 && i < n), "in range");
        assertEquals(count, Arrays.stream(pick).distinct().count(), "distinct");

        int[] sorted = pick.clone();
        Arrays.sort(sorted);
        assertArrayEquals(sorted, pick, "returned sorted ascending");
    }

    @Test
    void randomSubsampleIsDeterministic() {
        // Fixed internal seed → same data plots the same points across runs.
        assertArrayEquals(ScatterMath.randomSubsample(500, 50), ScatterMath.randomSubsample(500, 50));
    }

    // ── pointInPolygon ───────────────────────────────────────────────────────

    @Test
    void pointInPolygonInsideAndOutsideUnitSquare() {
        List<double[]> square = List.of(
                new double[] {0, 0}, new double[] {10, 0},
                new double[] {10, 10}, new double[] {0, 10});
        assertTrue(ScatterMath.pointInPolygon(5, 5, square), "centre is inside");
        assertFalse(ScatterMath.pointInPolygon(15, 5, square), "right is outside");
        assertFalse(ScatterMath.pointInPolygon(-1, 5, square), "left is outside");
        assertFalse(ScatterMath.pointInPolygon(5, 20, square), "above is outside");
    }

    @Test
    void pointInPolygonConcaveShape() {
        // Arrow/notch polygon: a point in the notch is outside.
        List<double[]> notch = List.of(
                new double[] {0, 0}, new double[] {10, 0}, new double[] {10, 10}, new double[] {5, 5}, new double[] {
                    0, 10
                });
        assertTrue(ScatterMath.pointInPolygon(2, 2, notch), "lower-left body inside");
        assertFalse(ScatterMath.pointInPolygon(5, 9, notch), "inside the notch is outside");
    }
}
