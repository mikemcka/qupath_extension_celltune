package qupath.ext.celltune.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
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
     * {@code n-1 < k}). Still brute-force O(n²) in the distance scan — acceptable
     * for the bounded sampled pool (plan ceiling: ~50k rows) and revisit with an ANN
     * index only if profiling demands it (plan's deferred item) — but each row's
     * search is (a) run in parallel across rows (they are independent, each writing
     * only its own {@code out[i]}) and (b) selected with a bounded size-{@code k}
     * max-heap instead of a full sort, so per row it is O(n log k) with no boxed
     * {@code Integer} allocation rather than the old O(n log n) sort of an
     * {@code Integer[n]}. Output is byte-identical to the previous stable full-sort:
     * the {@code k} smallest by (distance asc, index asc).
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
        IntStream.range(0, n).parallel().forEach(i -> out[i] = nearestForRow(rows, i, keep));
        return out;
    }

    /**
     * The {@code keep} nearest rows to row {@code i} (self excluded), ordered
     * ascending by (squared distance, index). Maintains a bounded max-heap of the
     * best candidates keyed by "worseness" (distance desc, index desc) so the root
     * is always the current worst kept candidate and a new candidate is admitted
     * only when strictly better — an O(n log keep) selection over primitive arrays.
     */
    private static int[] nearestForRow(double[][] rows, int i, int keep) {
        int n = rows.length;
        double[] ri = rows[i];
        double[] heapDist = new double[keep];
        int[] heapIdx = new int[keep];
        int size = 0;
        for (int j = 0; j < n; j++) {
            if (j == i) {
                continue;
            }
            double d = squaredDistance(ri, rows[j]);
            if (size < keep) {
                heapDist[size] = d;
                heapIdx[size] = j;
                siftUp(heapDist, heapIdx, size);
                size++;
            } else if (worse(heapDist[0], heapIdx[0], d, j)) {
                // The current worst kept candidate (root) is worse than this one — evict it.
                heapDist[0] = d;
                heapIdx[0] = j;
                siftDown(heapDist, heapIdx, size);
            }
        }
        // Order the kept neighbours ascending by (distance, index) to match the old
        // stable full-sort output exactly. keep is small (graph k, ~15), so an
        // insertion sort over the heap contents is cheaper than heap extraction.
        for (int a = 1; a < size; a++) {
            double dv = heapDist[a];
            int iv = heapIdx[a];
            int b = a - 1;
            while (b >= 0 && worse(heapDist[b], heapIdx[b], dv, iv)) {
                heapDist[b + 1] = heapDist[b];
                heapIdx[b + 1] = heapIdx[b];
                b--;
            }
            heapDist[b + 1] = dv;
            heapIdx[b + 1] = iv;
        }
        int[] result = new int[size];
        System.arraycopy(heapIdx, 0, result, 0, size);
        return result;
    }

    /**
     * True when candidate {@code a} is "worse" than {@code b} — larger distance,
     * ties broken by larger index. Defines the max-heap ordering (root = worst kept)
     * and reproduces the stable full-sort's (distance asc, index asc) tie-break.
     */
    private static boolean worse(double aDist, int aIdx, double bDist, int bIdx) {
        return aDist > bDist || (aDist == bDist && aIdx > bIdx);
    }

    /** Max-heap sift-up: bubble the element at {@code c} toward the root while it is worse than its parent. */
    private static void siftUp(double[] hd, int[] hi, int c) {
        while (c > 0) {
            int parent = (c - 1) >>> 1;
            if (worse(hd[c], hi[c], hd[parent], hi[parent])) {
                swap(hd, hi, c, parent);
                c = parent;
            } else {
                break;
            }
        }
    }

    /** Max-heap sift-down from the root over the first {@code size} elements. */
    private static void siftDown(double[] hd, int[] hi, int size) {
        int c = 0;
        while (true) {
            int left = 2 * c + 1;
            int right = 2 * c + 2;
            int worst = c;
            if (left < size && worse(hd[left], hi[left], hd[worst], hi[worst])) {
                worst = left;
            }
            if (right < size && worse(hd[right], hi[right], hd[worst], hi[worst])) {
                worst = right;
            }
            if (worst == c) {
                break;
            }
            swap(hd, hi, c, worst);
            c = worst;
        }
    }

    private static void swap(double[] hd, int[] hi, int a, int b) {
        double td = hd[a];
        hd[a] = hd[b];
        hd[b] = td;
        int ti = hi[a];
        hi[a] = hi[b];
        hi[b] = ti;
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
        // Each query row's kNN vote is independent (writes only its own assigned[qi]), so the
        // brute-force scan parallelises across query rows. Combined with the bounded-heap
        // selection in nearestReferenceIndices (no per-query full sort or boxed Integer[]),
        // this turns the transfer from serial O(nq·nRef·log nRef) into O(nq·nRef·log keep)
        // across all cores — the same optimisation applied to featureKnn. Labels are
        // byte-identical to the previous stable full-sort implementation.
        IntStream.range(0, nq)
                .parallel()
                .forEach(qi -> assigned[qi] = voteLabel(query[qi], reference, refLabels, keep, histSize));
        return assigned;
    }

    /** Majority label among the {@code keep} nearest reference rows to {@code q} (ties -> lowest label id). */
    private static int voteLabel(double[] q, double[][] reference, int[] refLabels, int keep, int histSize) {
        int[] nearest = nearestReferenceIndices(q, reference, keep);
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
        return argMaxLowestTie(votes);
    }

    /**
     * The {@code keep} nearest reference rows to {@code q}, ordered ascending by
     * (squared distance, index) — identical to the previous stable full-sort output,
     * so the majority vote and tie behaviour are unchanged. Uses the same bounded
     * max-heap selection as {@link #nearestForRow} (O(nRef log keep), no boxing).
     */
    private static int[] nearestReferenceIndices(double[] q, double[][] reference, int keep) {
        int n = reference.length;
        int k = Math.min(keep, n);
        double[] heapDist = new double[k];
        int[] heapIdx = new int[k];
        int size = 0;
        for (int j = 0; j < n; j++) {
            double d = squaredDistance(q, reference[j]);
            if (size < k) {
                heapDist[size] = d;
                heapIdx[size] = j;
                siftUp(heapDist, heapIdx, size);
                size++;
            } else if (worse(heapDist[0], heapIdx[0], d, j)) {
                heapDist[0] = d;
                heapIdx[0] = j;
                siftDown(heapDist, heapIdx, size);
            }
        }
        for (int a = 1; a < size; a++) {
            double dv = heapDist[a];
            int iv = heapIdx[a];
            int b = a - 1;
            while (b >= 0 && worse(heapDist[b], heapIdx[b], dv, iv)) {
                heapDist[b + 1] = heapDist[b];
                heapIdx[b + 1] = heapIdx[b];
                b--;
            }
            heapDist[b + 1] = dv;
            heapIdx[b + 1] = iv;
        }
        int[] result = new int[size];
        System.arraycopy(heapIdx, 0, result, 0, size);
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
