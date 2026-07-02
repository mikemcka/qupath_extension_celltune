package qupath.ext.celltune.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;

/**
 * Pure numerical core of graph-based (Leiden) phenotype clustering: feature-space
 * kNN graph construction, Jaccard/shared-nearest-neighbour edge weighting, CWTS
 * Leiden community detection, and kNN label transfer for cohort assignment
 * (scanpy {@code sc.tl.ingest} style). Every method is static and a pure function
 * of primitive arrays (no JavaFX, no QuPath types), so the graph/clustering/
 * transfer math is unit-testable against synthetic clouds — mirroring
 * {@code NeighborhoodModel} and {@code ScatterMath}.
 *
 * <p>The CWTS {@code LeidenAlgorithm} optimises the Constant Potts Model (CPM)
 * quality function (not Modularity — this library does not expose a Modularity
 * variant of Leiden; {@code LouvainAlgorithm} is the separate, also-CPM,
 * alternative). CPM {@code resolution} still behaves monotonically like the
 * scanpy/leidenalg resolution knob: larger resolution biases toward more, smaller
 * communities.
 */
public final class LeidenModel {

    private LeidenModel() {}

    /** Community labels 0..nClusters-1 for every input row, and the cluster count. */
    public record LeidenResult(int[] labels, int nClusters) {}

    // ── Feature-space kNN ────────────────────────────────────────────────────

    /**
     * For every row, the indices of its {@code k} nearest neighbours (Euclidean
     * over all columns), excluding itself. {@code out[i].length <= k} (fewer when
     * {@code n-1 < k}). Brute-force — acceptable for the bounded sampled pool
     * (plan ceiling: <= ~50k rows); revisit with an ANN index only if profiling
     * demands it (see plan's deferred items).
     */
    public static int[][] featureKnn(double[][] rows, int k) {
        int n = rows.length;
        int[][] out = new int[n][];
        if (n == 0 || k < 1) {
            for (int i = 0; i < n; i++) {
                out[i] = new int[0];
            }
            return out;
        }
        int keep = Math.min(k, n - 1);
        if (keep <= 0) {
            for (int i = 0; i < n; i++) {
                out[i] = new int[0];
            }
            return out;
        }
        for (int i = 0; i < n; i++) {
            out[i] = nearestForRow(rows, i, keep);
        }
        return out;
    }

    private static int[] nearestForRow(double[][] rows, int i, int keep) {
        int n = rows.length;
        double[] dist = new double[n];
        Integer[] order = new Integer[n];
        double[] ri = rows[i];
        for (int j = 0; j < n; j++) {
            order[j] = j;
            dist[j] = (j == i) ? Double.POSITIVE_INFINITY : squaredDistance(ri, rows[j]);
        }
        Arrays.sort(order, (p, q) -> Double.compare(dist[p], dist[q]));
        int[] result = new int[keep];
        for (int t = 0; t < keep; t++) {
            result[t] = order[t];
        }
        return result;
    }

    private static double squaredDistance(double[] a, double[] b) {
        double sum = 0;
        int len = Math.min(a.length, b.length);
        for (int c = 0; c < len; c++) {
            double diff = a[c] - b[c];
            if (Double.isNaN(diff)) {
                continue;
            }
            sum += diff * diff;
        }
        return sum;
    }

    // ── Leiden community detection ───────────────────────────────────────────

    /** Default randomness parameter (mirrors {@link LeidenAlgorithm#DEFAULT_RANDOMNESS}). */
    private static final double DEFAULT_RANDOMNESS = LeidenAlgorithm.DEFAULT_RANDOMNESS;

    /** Default local-moving iterations per Leiden run. */
    private static final int DEFAULT_ITERATIONS = 10;

    /**
     * Build a symmetric kNN graph on {@code rows}, weight edges by Jaccard
     * similarity of each pair's (closed, self-included) neighbour sets, run the
     * CWTS Leiden algorithm at {@code resolution} keeping the best of
     * {@code randomStarts} restarts (by CPM quality), and return per-row
     * community labels relabelled to a dense {@code 0..nClusters-1} range.
     *
     * @param rows rows to cluster (e.g. the z-scored active marker matrix)
     * @param k graph kNN neighbours (default suggestion ~15, scanpy-style)
     * @param resolution CPM resolution; higher -> more, smaller communities
     * @param randomStarts number of independent Leiden restarts to keep the best
     *     of (>= 1; values < 1 are treated as 1)
     * @param seed RNG seed for reproducibility
     */
    public static LeidenResult cluster(double[][] rows, int k, double resolution, int randomStarts, long seed) {
        int n = rows.length;
        if (n == 0) {
            return new LeidenResult(new int[0], 0);
        }
        if (n == 1) {
            return new LeidenResult(new int[] {0}, 1);
        }

        int[][] neighbors = featureKnn(rows, k);
        Network rawNetwork = buildJaccardWeightedNetwork(n, neighbors);
        // Association-strength normalization (Network.createNormalizedNetworkUsingAssociationStrength)
        // divides each edge weight by its expected weight under a random-configuration
        // null model, exactly like modularity's null-model correction. Without it, CPM's
        // resolution has no natural "1.0" scale against raw Jaccard weights (<=1) and
        // even resolution=1.0 shatters every node into its own singleton community —
        // empirically confirmed while developing this method. With normalization,
        // resolution in the plan's UI range (0.1-3.0, default 1.0) behaves like the
        // familiar scanpy/leidenalg resolution knob.
        Network network = rawNetwork.createNormalizedNetworkUsingAssociationStrength();

        int starts = Math.max(1, randomStarts);
        Random random = new Random(seed);
        LeidenAlgorithm leiden = new LeidenAlgorithm(resolution, DEFAULT_ITERATIONS, DEFAULT_RANDOMNESS, random);

        Clustering best = null;
        double bestQuality = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < starts; r++) {
            Clustering candidate = leiden.findClustering(network);
            double quality = leiden.calcQuality(network, candidate);
            if (best == null || quality > bestQuality) {
                best = candidate;
                bestQuality = quality;
            }
        }
        best.removeEmptyClusters();
        int[] labels = best.getClusters();
        return new LeidenResult(labels, best.getNClusters());
    }

    /**
     * Builds the undirected, Jaccard-weighted {@link Network} from a symmetric kNN
     * neighbour list. Each undirected edge {@code (i, j)} with {@code i < j} is
     * added once; the {@code Network} constructor used here (verified against the
     * CWTS {@code FileIO.readEdgeList} reference reader) symmetrizes single-
     * direction edge input internally. Jaccard weight uses the CLOSED neighbour
     * sets (each node's set includes itself) — the PhenoGraph/shared-nearest-
     * neighbour convention — so two nodes that are each other's only neighbour get
     * weight 1.0 rather than an undefined/zero weight.
     */
    private static Network buildJaccardWeightedNetwork(int n, int[][] neighbors) {
        Set<Integer>[] closedSets = buildClosedNeighborSets(n, neighbors);
        Set<Long> seenEdges = new HashSet<>();

        LargeIntArray from = new LargeIntArray(0);
        LargeIntArray to = new LargeIntArray(0);
        LargeDoubleArray weights = new LargeDoubleArray(0);

        for (int i = 0; i < n; i++) {
            for (int j : neighbors[i]) {
                int a = Math.min(i, j);
                int b = Math.max(i, j);
                if (a == b) {
                    continue;
                }
                long key = (((long) a) << 32) | (b & 0xffffffffL);
                if (!seenEdges.add(key)) {
                    continue;
                }
                double w = jaccard(closedSets[a], closedSets[b]);
                from.append(a);
                to.append(b);
                weights.append(w);
            }
        }

        // Network(nNodes, setNodeWeightsToTotalEdgeWeights, edges, edgeWeights,
        // sortedEdges, checkIntegrity) — verified against the CWTS source
        // (Network.java javadoc): sortedEdges=false means each undirected edge is
        // listed ONCE, unsorted, and the constructor itself doubles + sorts them
        // into the internal CSR adjacency. Passing sortedEdges=true here (as an
        // earlier draft did) corrupts firstNeighborIndices and crashes
        // LocalMergingAlgorithm with an ArrayIndexOutOfBoundsException.
        //
        // setNodeWeightsToTotalEdgeWeights=true so createNormalizedNetworkUsingAssociationStrength()
        // (called by the caller right after this) has a meaningful node-weight
        // basis for its null-model correction.
        return new Network(n, true, new LargeIntArray[] {from, to}, weights, false, true);
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer>[] buildClosedNeighborSets(int n, int[][] neighbors) {
        Set<Integer>[] sets = new HashSet[n];
        for (int i = 0; i < n; i++) {
            Set<Integer> s = new HashSet<>(neighbors[i].length * 2 + 1);
            s.add(i);
            for (int j : neighbors[i]) {
                s.add(j);
            }
            sets[i] = s;
        }
        return sets;
    }

    private static double jaccard(Set<Integer> a, Set<Integer> b) {
        Set<Integer> smaller = a.size() <= b.size() ? a : b;
        Set<Integer> larger = a.size() <= b.size() ? b : a;
        int intersection = 0;
        for (Integer v : smaller) {
            if (larger.contains(v)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : intersection / (double) union;
    }

    // ── kNN label transfer ───────────────────────────────────────────────────

    /**
     * kNN label transfer (scanpy {@code sc.tl.ingest} style): assign each query
     * row the majority label among its {@code k} nearest reference rows (ties ->
     * lowest label id). Brute-force over the bounded reference sample. Handles a
     * degenerate/NaN query row and a single-row reference without crashing.
     *
     * @param query rows to label (e.g. every cell in a cohort image, z-scored)
     * @param reference the labelled pooled sample (z-scored)
     * @param refLabels labels for each reference row, parallel to {@code reference}
     * @param k number of nearest reference rows to vote over
     * @param nClusters number of distinct labels (for sizing the vote histogram)
     */
    public static int[] transferLabels(double[][] query, double[][] reference, int[] refLabels, int k, int nClusters) {
        int nq = query.length;
        int[] assigned = new int[nq];
        int nRef = reference.length;
        if (nRef == 0) {
            return assigned;
        }
        int keep = Math.max(1, Math.min(k, nRef));
        int histSize = Math.max(1, nClusters);

        for (int qi = 0; qi < nq; qi++) {
            int[] nearest = nearestReferenceIndices(query[qi], reference, keep);
            int[] votes = new int[histSize];
            for (int idx : nearest) {
                int label = refLabels[idx];
                if (label >= 0) {
                    if (label >= votes.length) {
                        votes = Arrays.copyOf(votes, label + 1);
                    }
                    votes[label]++;
                }
            }
            assigned[qi] = argMaxLowestTie(votes);
        }
        return assigned;
    }

    private static int[] nearestReferenceIndices(double[] q, double[][] reference, int keep) {
        int n = reference.length;
        double[] dist = new double[n];
        Integer[] order = new Integer[n];
        for (int j = 0; j < n; j++) {
            order[j] = j;
            dist[j] = squaredDistance(q, reference[j]);
        }
        Arrays.sort(order, (p, r) -> Double.compare(dist[p], dist[r]));
        int actualKeep = Math.min(keep, n);
        int[] result = new int[actualKeep];
        for (int t = 0; t < actualKeep; t++) {
            result[t] = order[t];
        }
        return result;
    }

    private static int argMaxLowestTie(int[] votes) {
        int best = 0;
        int bestCount = votes.length == 0 ? 0 : votes[0];
        for (int label = 1; label < votes.length; label++) {
            if (votes[label] > bestCount) {
                bestCount = votes[label];
                best = label;
            }
        }
        return best;
    }
}
