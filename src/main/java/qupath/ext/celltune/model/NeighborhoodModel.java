package qupath.ext.celltune.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.ItemBoundable;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;
import smile.clustering.KMeans;

/**
 * Pure numerical core of the cellular-neighborhood (CN) analysis — the
 * Schürch/Nolan spatial-clustering method: for every cell, describe the
 * cell-type composition of its local spatial neighborhood, then k-means those
 * composition vectors to partition the tissue into recurring micro-environments
 * (Schürch et al., Cell 2020, STAR Methods "Neighborhood identification").
 *
 * <p>Every method is static and a pure function of primitive arrays (no JavaFX,
 * no QuPath types), so the neighbor/composition/cluster math is unit-testable in
 * isolation against synthetic clouds — mirroring {@code ScatterMath} and the
 * {@code STRtree} fast-path in {@code DistanceMeasurementsDialog}.
 */
public final class NeighborhoodModel {

    private NeighborhoodModel() {}

    // ── Neighbor finding ─────────────────────────────────────────────────────

    /**
     * For every point, the indices of its {@code k} nearest neighbours of ANY
     * type (Euclidean on x/y), excluding itself. {@code out[i].length <= k}
     * (fewer at edges / when {@code n-1 < k}). Points with a NaN coordinate get
     * an empty list and are ignored as neighbours of others.
     *
     * <p>Adapts the JTS {@link STRtree} + {@link ItemDistance} reference-equality
     * self-exclusion idiom from {@code DistanceMeasurementsDialog}. The single
     * nearest-neighbour version there used the 3-arg query; here we use the
     * k-nearest overload {@code nearestNeighbour(Envelope, item, ItemDistance, k)}
     * (verified present in the bundled JTS 1.20). Because that overload returns
     * up to {@code k} items and may include the query item itself when the tree
     * holds {@code <= k} points, we over-request and then drop self and keep the
     * {@code k} smallest exact distances.
     */
    public static int[][] kNearestNeighborIndices(double[] xs, double[] ys, int k) {
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("xs and ys must have the same length");
        }
        int n = xs.length;
        int[][] out = new int[n][];
        if (n == 0) {
            return out;
        }

        Object[] keys = new Object[n];
        IdentityHashMap<Object, Integer> indexByKey = new IdentityHashMap<>(n * 2);
        STRtree tree = new STRtree();
        int inserted = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(xs[i]) || Double.isNaN(ys[i])) {
                continue;
            }
            keys[i] = new Object();
            indexByKey.put(keys[i], i);
            tree.insert(new Envelope(xs[i], xs[i], ys[i], ys[i]), keys[i]);
            inserted++;
        }
        if (inserted < 2 || k < 1) {
            for (int i = 0; i < n; i++) {
                out[i] = new int[0];
            }
            return out;
        }
        tree.build();

        ItemDistance pointDistance = (ItemBoundable a, ItemBoundable b) -> {
            if (a.getItem() == b.getItem()) {
                return Double.POSITIVE_INFINITY;
            }
            Envelope ea = (Envelope) a.getBounds();
            Envelope eb = (Envelope) b.getBounds();
            double dx = ea.getMinX() - eb.getMinX();
            double dy = ea.getMinY() - eb.getMinY();
            return Math.sqrt(dx * dx + dy * dy);
        };

        // Over-request by one so a possible self entry can be dropped without
        // losing a genuine neighbour, then sort the candidates by exact distance.
        int request = Math.min(inserted, k + 1);
        for (int i = 0; i < n; i++) {
            if (keys[i] == null) {
                out[i] = new int[0];
                continue;
            }
            Envelope env = new Envelope(xs[i], xs[i], ys[i], ys[i]);
            Object[] near = tree.nearestNeighbour(env, keys[i], pointDistance, request);
            out[i] = pickNearest(i, near, indexByKey, xs, ys, k);
        }
        return out;
    }

    /** Drops self, sorts candidates by exact distance to {@code i}, keeps the {@code k} closest. */
    private static int[] pickNearest(
            int i, Object[] near, IdentityHashMap<Object, Integer> indexByKey, double[] xs, double[] ys, int k) {
        if (near == null || near.length == 0) {
            return new int[0];
        }
        double xi = xs[i];
        double yi = ys[i];
        List<int[]> cand = new ArrayList<>(near.length); // [index, sortKey-unused]; sort via parallel dist array
        double[] dists = new double[near.length];
        int m = 0;
        for (Object o : near) {
            Integer j = indexByKey.get(o);
            if (j == null || j == i) {
                continue;
            }
            double dx = xi - xs[j];
            double dy = yi - ys[j];
            dists[m] = dx * dx + dy * dy;
            cand.add(new int[] {j});
            m++;
        }
        Integer[] order = new Integer[m];
        for (int t = 0; t < m; t++) {
            order[t] = t;
        }
        java.util.Arrays.sort(order, (p, q) -> Double.compare(dists[p], dists[q]));
        int keep = Math.min(k, m);
        int[] result = new int[keep];
        for (int t = 0; t < keep; t++) {
            result[t] = cand.get(order[t])[0];
        }
        return result;
    }

    /**
     * For every point, the indices of all OTHER points within {@code radius}
     * (Euclidean, same units as the coordinates), self-excluded. Uses an
     * expanded-envelope {@link STRtree} query plus an exact
     * {@code dx*dx + dy*dy <= radius*radius} filter. NaN-coordinate points get an
     * empty list and are ignored by others.
     */
    public static int[][] radiusNeighborIndices(double[] xs, double[] ys, double radius) {
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("xs and ys must have the same length");
        }
        int n = xs.length;
        int[][] out = new int[n][];
        if (n == 0) {
            return out;
        }

        Object[] keys = new Object[n];
        IdentityHashMap<Object, Integer> indexByKey = new IdentityHashMap<>(n * 2);
        STRtree tree = new STRtree();
        int inserted = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(xs[i]) || Double.isNaN(ys[i])) {
                continue;
            }
            keys[i] = new Object();
            indexByKey.put(keys[i], i);
            tree.insert(new Envelope(xs[i], xs[i], ys[i], ys[i]), keys[i]);
            inserted++;
        }
        if (inserted < 2 || !(radius > 0)) {
            for (int i = 0; i < n; i++) {
                out[i] = new int[0];
            }
            return out;
        }
        tree.build();

        double r2 = radius * radius;
        for (int i = 0; i < n; i++) {
            if (keys[i] == null) {
                out[i] = new int[0];
                continue;
            }
            double xi = xs[i];
            double yi = ys[i];
            Envelope env = new Envelope(xi - radius, xi + radius, yi - radius, yi + radius);
            @SuppressWarnings("unchecked")
            List<Object> hits = tree.query(env);
            List<Integer> within = new ArrayList<>(hits.size());
            for (Object o : hits) {
                Integer j = indexByKey.get(o);
                if (j == null || j == i) {
                    continue;
                }
                double dx = xi - xs[j];
                double dy = yi - ys[j];
                if (dx * dx + dy * dy <= r2) {
                    within.add(j);
                }
            }
            int[] arr = new int[within.size()];
            for (int t = 0; t < arr.length; t++) {
                arr[t] = within.get(t);
            }
            out[i] = arr;
        }
        return out;
    }

    // ── Composition ──────────────────────────────────────────────────────────

    /**
     * Per-cell neighborhood composition: an {@code [n][nTypes]} matrix whose row
     * {@code i} holds the fraction of each cell type among cell {@code i}'s
     * window. {@code typeId} maps each cell to a type in {@code [0, nTypes)} or to
     * {@code -1} for "ignored" (excluded from both the counts and the
     * normalization base). When {@code includeCenter} is true the centre cell's
     * own type is counted (paper default). A window that is empty or wholly
     * ignored yields an all-zero row, which the caller flags as {@code CN = -1}.
     */
    public static double[][] compositionMatrix(int[][] neighbors, int[] typeId, int nTypes, boolean includeCenter) {
        int n = neighbors.length;
        double[][] out = new double[n][nTypes];
        for (int i = 0; i < n; i++) {
            double total = 0;
            if (includeCenter) {
                int t = typeId[i];
                if (t >= 0 && t < nTypes) {
                    out[i][t] += 1;
                    total++;
                }
            }
            for (int j : neighbors[i]) {
                int t = typeId[j];
                if (t >= 0 && t < nTypes) {
                    out[i][t] += 1;
                    total++;
                }
            }
            if (total > 0) {
                for (int c = 0; c < nTypes; c++) {
                    out[i][c] /= total;
                }
            }
        }
        return out;
    }

    // ── Clustering ─────────────────────────────────────────────────────────────

    /** k-means result: per-row labels, per-cluster mean centroids (in the fed space), and the effective k. */
    public record ClusterResult(int[] labels, double[][] centroids, int kEffective) {}

    /**
     * Number of k-means restarts used by {@link #clusterCompositions(double[][], int)}.
     * Mirrors scikit-learn's historic {@code n_init=10} default and the
     * "extension method" validation configuration against the Schürch/Nolan
     * published labels. A single k-means run is init-sensitive — on the paper's
     * CRC cohort agreement with {@code neighborhood10} swings from ARI ~0.57 to
     * ~0.72 depending on the seed — whereas keeping the lowest-inertia fit over
     * several restarts lands reliably near the top of that range.
     */
    public static final int DEFAULT_N_INIT = 10;

    /**
     * Cluster the (optionally pre-standardized) composition rows with k-means,
     * reusing the Smile {@code KMeans.fit} + per-cluster-mean recompute pattern
     * from {@code ScatterPlotView}, with {@link #DEFAULT_N_INIT} restarts. See
     * {@link #clusterCompositions(double[][], int, int)} for the semantics.
     */
    public static ClusterResult clusterCompositions(double[][] composition, int k) {
        return clusterCompositions(composition, k, DEFAULT_N_INIT);
    }

    /**
     * Cluster the (optionally pre-standardized) composition rows with k-means,
     * keeping the best of {@code nInit} independent restarts (lowest within-cluster
     * inertia, i.e. Smile's {@code distortion}), reusing the Smile {@code KMeans.fit}
     * + per-cluster-mean recompute pattern from {@code ScatterPlotView}.
     * {@code kEffective = min(k, nRows)}; with fewer than two rows or {@code k < 2}
     * every row is assigned cluster 0. Centroids are recomputed as the per-cluster
     * means of the fed rows (empty clusters stay all-zero).
     *
     * <p>Each restart draws a fresh k-means++ initialization from Smile's
     * per-thread RNG stream (the stream advances between {@code fit} calls rather
     * than re-seeding to a constant), so {@code nInit > 1} genuinely explores
     * different seeds and picks the tightest partition. Because that stream is
     * seeded deterministically, results are reproducible run-to-run on a given
     * thread. {@code nInit} is clamped to at least 1.
     *
     * <p>Note: raw label ids are still not meaningful across runs (cluster 3 in
     * one fit need not be cluster 3 in another); tests assert blob recovery by
     * cluster purity, not raw label ids.
     */
    public static ClusterResult clusterCompositions(double[][] composition, int k, int nInit) {
        int n = composition.length;
        int nCols = n == 0 ? 0 : composition[0].length;
        int kEff = Math.min(Math.max(k, 1), Math.max(n, 1));
        int restarts = Math.max(1, nInit);
        int[] labels = new int[n];
        if (n >= 2 && kEff >= 2) {
            KMeans best = KMeans.fit(composition, kEff);
            for (int r = 1; r < restarts; r++) {
                KMeans cand = KMeans.fit(composition, kEff);
                if (cand.distortion < best.distortion) {
                    best = cand;
                }
            }
            System.arraycopy(best.y, 0, labels, 0, n);
        }
        double[][] centroids = perClusterMeans(labels, composition, kEff, nCols);
        return new ClusterResult(labels, centroids, kEff);
    }

    /**
     * Mean composition fraction per CN: an {@code [nCN][nTypes]} matrix where row
     * {@code c} is the column-wise mean of the composition rows assigned to CN
     * {@code c}. Feeds the enrichment heatmap (paper Fig 4B) and CN naming. Always
     * computed on the raw fraction matrix for interpretability.
     */
    public static double[][] cnMeanComposition(int[] labels, double[][] composition, int nCN) {
        int nCols = composition.length == 0 ? 0 : composition[0].length;
        return perClusterMeans(labels, composition, nCN, nCols);
    }

    /**
     * Normalised Shannon diversity of a composition vector, in {@code [0, 1]}:
     * {@code H / ln(nTypes)} where {@code H = -Σ p_i ln p_i} over the non-zero
     * fractions. 0 when one type dominates (or the window is empty), 1 when the
     * neighborhood is an even mix of all {@code nTypes} cell types. Rewards both
     * richness (using more types) and evenness, so higher = more cell-type variety.
     */
    public static double compositionDiversity(double[] composition) {
        int nTypes = composition.length;
        if (nTypes < 2) {
            return 0.0;
        }
        double total = 0;
        for (double v : composition) {
            if (v > 0) {
                total += v;
            }
        }
        if (total <= 0) {
            return 0.0;
        }
        double h = 0;
        for (double v : composition) {
            if (v > 0) {
                double p = v / total;
                h -= p * Math.log(p);
            }
        }
        double norm = Math.log(nTypes);
        return norm > 0 ? Math.min(1.0, h / norm) : 0.0;
    }

    /**
     * Symmetric CN spatial-adjacency counts: {@code adj[a][b]} is the number of
     * times a cell of CN {@code a+1} has a neighbour of CN {@code b+1} (a != b),
     * summed over both directions. Used to colour spatially-touching CNs with
     * maximally-contrasting palette colours. CN ids are 1-based; cells with
     * {@code cn[i] < 1 || > k} (e.g. empty windows, -1) are ignored.
     */
    public static double[][] cnAdjacency(int[][] neighbors, int[] cn, int k) {
        double[][] adj = new double[k][k];
        for (int i = 0; i < neighbors.length; i++) {
            int a = cn[i];
            if (a < 1 || a > k) {
                continue;
            }
            for (int j : neighbors[i]) {
                int b = cn[j];
                if (b < 1 || b > k || b == a) {
                    continue;
                }
                adj[a - 1][b - 1] += 1;
            }
        }
        return adj;
    }

    private static double[][] perClusterMeans(int[] labels, double[][] data, int nGroups, int nCols) {
        double[][] sums = new double[nGroups][nCols];
        int[] counts = new int[nGroups];
        for (int i = 0; i < labels.length; i++) {
            int g = labels[i];
            if (g < 0 || g >= nGroups) {
                continue;
            }
            counts[g]++;
            double[] row = data[i];
            for (int c = 0; c < nCols; c++) {
                sums[g][c] += row[c];
            }
        }
        for (int g = 0; g < nGroups; g++) {
            if (counts[g] > 0) {
                for (int c = 0; c < nCols; c++) {
                    sums[g][c] /= counts[g];
                }
            }
        }
        return sums;
    }
}
