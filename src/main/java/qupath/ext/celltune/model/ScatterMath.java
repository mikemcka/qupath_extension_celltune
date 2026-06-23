package qupath.ext.celltune.model;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import smile.feature.extraction.PCA;
import smile.manifold.UMAP;

/**
 * Pure numerical helpers for the cell scatter plot: column standardisation, the
 * PCA/UMAP 2-D embeddings, deterministic subsampling, and point-in-polygon
 * hit-testing. No JavaFX, no per-row state — every method is static and a pure
 * function of its arguments, so the embedding core is unit-testable in isolation
 * from the interactive {@code ScatterPlotView}.
 */
public final class ScatterMath {

    private ScatterMath() {}

    // ── Standardisation ──────────────────────────────────────────────────────

    /** Z-scores each column (per-column mean 0, sd 1; constant columns → 0). */
    public static double[][] standardizeColumns(double[][] data) {
        int p = data.length == 0 ? 0 : data[0].length;
        return standardizeColumns(data, new double[p], new double[p]);
    }

    /**
     * Z-scores each column, writing the per-column mean/sd into {@code outMean}/
     * {@code outSd} so the same transform can be replayed at cohort-assign time.
     */
    public static double[][] standardizeColumns(double[][] data, double[] outMean, double[] outSd) {
        int n = data.length;
        int p = n == 0 ? 0 : data[0].length;
        double[][] out = new double[n][p];
        for (int j = 0; j < p; j++) {
            double mean = 0;
            for (double[] row : data) {
                mean += row[j];
            }
            mean /= Math.max(1, n);
            double var = 0;
            for (double[] row : data) {
                double d = row[j] - mean;
                var += d * d;
            }
            double sd = Math.sqrt(var / Math.max(1, n));
            outMean[j] = mean;
            outSd[j] = sd;
            double inv = sd < 1e-9 ? 0.0 : 1.0 / sd;
            for (int i = 0; i < n; i++) {
                out[i][j] = (data[i][j] - mean) * inv;
            }
        }
        return out;
    }

    // ── Embeddings ───────────────────────────────────────────────────────────

    /** Projects all rows onto their first two principal components. */
    public static void fillPca(double[][] std, int n, double[] nx, double[] ny) {
        double[][] proj = PCA.fit(std).getProjection(2).apply(std);
        for (int i = 0; i < n; i++) {
            nx[i] = proj[i][0];
            ny[i] = proj[i][1];
        }
    }

    /**
     * Embeds the rows with UMAP, subsampling to {@code maxCells} when the input is
     * larger (pass {@link Integer#MAX_VALUE} to embed everything). Returns a
     * status notice (currently empty).
     *
     * @throws LinkageError if the native ARPACK library cannot be loaded (caller
     *     falls back to PCA)
     */
    public static String fillUmap(double[][] std, int n, double[] nx, double[] ny, int maxCells) {
        // UMAP: subsample if larger than the cap; embed a connected subset.
        int[] sub = (n > maxCells) ? randomSubsample(n, maxCells) : identity(n);
        // The status bar's "clustered · plotted" counts convey the subsample, so
        // no extra notice is emitted here.
        String notice = "";
        double[][] subMatrix = new double[sub.length][];
        for (int s = 0; s < sub.length; s++) {
            subMatrix[s] = std[sub[s]];
        }
        int neighbors = Math.min(15, subMatrix.length - 1);
        if (neighbors < 2) {
            throw new IllegalStateException("Too few cells for UMAP (need at least 3).");
        }
        UMAP umap = UMAP.of(subMatrix, neighbors);
        // coordinates[j] corresponds to subMatrix row umap.index[j].
        for (int j = 0; j < umap.coordinates.length; j++) {
            int orig = sub[umap.index[j]];
            nx[orig] = umap.coordinates[j][0];
            ny[orig] = umap.coordinates[j][1];
        }
        return notice;
    }

    // ── Subsampling ──────────────────────────────────────────────────────────

    /** Identity index array {@code [0, 1, ..., n-1]}. */
    public static int[] identity(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }

    /**
     * Deterministic (seeded) subsample of {@code count} distinct indices in
     * {@code [0, n)}, returned sorted ascending. Uses a fixed seed so repeated
     * runs over the same data plot the same points.
     */
    public static int[] randomSubsample(int n, int count) {
        int[] all = identity(n);
        Random rng = new Random(42);
        // Partial Fisher-Yates: first `count` slots become the sample.
        for (int i = 0; i < count; i++) {
            int j = i + rng.nextInt(n - i);
            int tmp = all[i];
            all[i] = all[j];
            all[j] = tmp;
        }
        int[] out = new int[count];
        System.arraycopy(all, 0, out, 0, count);
        Arrays.sort(out);
        return out;
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    /**
     * Even-odd ray-cast point-in-polygon test. {@code poly} is a list of
     * {@code [x, y]} vertices; the polygon is treated as closed.
     */
    public static boolean pointInPolygon(double x, double y, List<double[]> poly) {
        boolean in = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly.get(i)[0], yi = poly.get(i)[1];
            double xj = poly.get(j)[0], yj = poly.get(j)[1];
            boolean intersect = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi);
            if (intersect) {
                in = !in;
            }
        }
        return in;
    }
}
