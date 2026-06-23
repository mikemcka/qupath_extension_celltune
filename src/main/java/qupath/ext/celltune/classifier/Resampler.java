package qupath.ext.celltune.classifier;

import java.util.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resampling methods for addressing class imbalance in training data.
 * <p>
 * All methods operate on parallel lists of feature rows and integer class labels,
 * returning new resampled lists. The originals are not modified.
 * <p>
 * Supported strategies:
 * <ul>
 *   <li><b>SMOTE</b> — oversample minority classes by interpolating between
 *       k-nearest same-class neighbours</li>
 *   <li><b>ADASYN</b> — like SMOTE but concentrates synthetic samples on
 *       harder-to-learn minority examples (those with more different-class neighbours)</li>
 *   <li><b>Tomek links</b> — remove majority-class samples that form mutual
 *       nearest-neighbour pairs with minority-class samples</li>
 *   <li><b>Combinations</b> — SMOTE + Tomek, ADASYN + Tomek (oversample then clean)</li>
 * </ul>
 */
public final class Resampler {

    private static final Logger logger = LoggerFactory.getLogger(Resampler.class);

    /** Default number of nearest neighbours for SMOTE/ADASYN. */
    private static final int DEFAULT_K = 5;

    private Resampler() {} // utility class

    /**
     * Apply the given resampling strategy to the training data.
     *
     * @param rows     feature vectors (one per sample)
     * @param labels   integer class labels (parallel to rows)
     * @param nClasses total number of classes
     * @param strategy which resampling method to apply
     * @param log      optional progress callback
     * @return a {@link Result} containing the resampled rows and labels
     */
    public static Result apply(
            List<float[]> rows, List<Integer> labels, int nClasses, ResamplingStrategy strategy, Consumer<String> log) {
        Consumer<String> out = log != null ? log : s -> {};

        // Validate label indices up front so a corrupt label (e.g. a class index
        // left over after an out-of-sync class edit) fails with a clear message
        // rather than a bare ArrayIndexOutOfBoundsException deep in counting/kNN.
        validateLabels(labels, nClasses);

        if (strategy == ResamplingStrategy.NONE) {
            return new Result(new ArrayList<>(rows), new ArrayList<>(labels));
        }

        out.accept("Resampling: " + strategy + "…");

        // Count per-class
        int[] classCounts = new int[nClasses];
        for (int lbl : labels) classCounts[lbl]++;

        int maxCount = 0;
        for (int c : classCounts) if (c > maxCount) maxCount = c;

        List<float[]> outRows = new ArrayList<>(rows);
        List<Integer> outLabels = new ArrayList<>(labels);

        switch (strategy) {
            case SMOTE -> {
                smote(outRows, outLabels, classCounts, maxCount, nClasses, out);
            }
            case ADASYN -> {
                adasyn(outRows, outLabels, classCounts, maxCount, nClasses, out);
            }
            case TOMEK -> {
                int removed = tomekLinks(outRows, outLabels, classCounts, nClasses, out);
                out.accept("Tomek links removed " + removed + " majority-class samples");
            }
            case SMOTE_TOMEK -> {
                smote(outRows, outLabels, classCounts, maxCount, nClasses, out);
                // Recount after SMOTE
                int[] newCounts = recount(outLabels, nClasses);
                int removed = tomekLinks(outRows, outLabels, newCounts, nClasses, out);
                out.accept("Tomek links removed " + removed + " borderline samples after SMOTE");
            }
            case ADASYN_TOMEK -> {
                adasyn(outRows, outLabels, classCounts, maxCount, nClasses, out);
                int[] newCounts = recount(outLabels, nClasses);
                int removed = tomekLinks(outRows, outLabels, newCounts, nClasses, out);
                out.accept("Tomek links removed " + removed + " borderline samples after ADASYN");
            }
            default -> {
                /* NONE — handled above */
            }
        }

        // Log final distribution
        int[] finalCounts = recount(outLabels, nClasses);
        StringBuilder sb = new StringBuilder("Resampled distribution: ");
        for (int c = 0; c < nClasses; c++) {
            if (c > 0) sb.append(", ");
            sb.append("class ").append(c).append("=").append(finalCounts[c]);
        }
        sb.append(" (total ")
                .append(outRows.size())
                .append(", was ")
                .append(rows.size())
                .append(")");
        out.accept(sb.toString());

        return new Result(outRows, outLabels);
    }

    // ── SMOTE ───────────────────────────────────────────────────────────────────

    private static void smote(
            List<float[]> rows,
            List<Integer> labels,
            int[] classCounts,
            int targetCount,
            int nClasses,
            Consumer<String> log) {
        Random rng = new Random(42);
        int nFeatures = rows.get(0).length;

        for (int cls = 0; cls < nClasses; cls++) {
            int count = classCounts[cls];
            int needed = targetCount - count;
            if (needed <= 0) continue;

            // Collect indices of this class
            List<Integer> classIndices = indicesOfClass(labels, cls);
            if (classIndices.size() < 2) continue; // can't interpolate with < 2

            int k = Math.min(DEFAULT_K, classIndices.size() - 1);

            // Pre-compute pairwise distances within class
            int generated = 0;
            for (int i = 0; generated < needed; i++) {
                int idx = classIndices.get(i % classIndices.size());
                float[] sample = rows.get(idx);

                // Find k nearest same-class neighbours
                int[] neighbours = knnSameClass(rows, classIndices, idx, k);
                int neighbourIdx = classIndices.get(neighbours[rng.nextInt(k)]);
                float[] neighbour = rows.get(neighbourIdx);

                // Interpolate
                float lambda = rng.nextFloat();
                float[] synthetic = new float[nFeatures];
                for (int f = 0; f < nFeatures; f++) {
                    synthetic[f] = sample[f] + lambda * (neighbour[f] - sample[f]);
                }

                rows.add(synthetic);
                labels.add(cls);
                generated++;
            }

            log.accept("SMOTE: generated " + generated + " synthetic samples for class " + cls);
        }
    }

    // ── ADASYN ──────────────────────────────────────────────────────────────────

    private static void adasyn(
            List<float[]> rows,
            List<Integer> labels,
            int[] classCounts,
            int targetCount,
            int nClasses,
            Consumer<String> log) {
        Random rng = new Random(42);
        int nFeatures = rows.get(0).length;
        int totalSamples = rows.size();

        for (int cls = 0; cls < nClasses; cls++) {
            int count = classCounts[cls];
            int needed = targetCount - count;
            if (needed <= 0) continue;

            List<Integer> classIndices = indicesOfClass(labels, cls);
            if (classIndices.size() < 2) continue;

            int k = Math.min(DEFAULT_K, totalSamples - 1);

            // For each minority sample, compute ratio of different-class neighbours
            double[] ratios = new double[classIndices.size()];
            double ratioSum = 0;
            for (int i = 0; i < classIndices.size(); i++) {
                int idx = classIndices.get(i);
                float[] sample = rows.get(idx);

                // Find k nearest neighbours (all classes)
                int[] nnIndices = knnAll(rows, idx, k);
                int differentCount = 0;
                for (int ni : nnIndices) {
                    if (!labels.get(ni).equals(cls)) differentCount++;
                }
                ratios[i] = (double) differentCount / k;
                ratioSum += ratios[i];
            }

            if (ratioSum < 1e-10) {
                // All neighbours are same class — fall back to uniform SMOTE
                smoteSingle(rows, labels, classIndices, needed, nFeatures, rng);
                log.accept("ADASYN (uniform fallback): generated " + needed + " synthetic samples for class " + cls);
                continue;
            }

            // Normalise ratios to get per-sample generation weights
            int generated = 0;
            for (int i = 0; i < classIndices.size() && generated < needed; i++) {
                int numForThis = (int) Math.round(needed * ratios[i] / ratioSum);
                if (numForThis <= 0) continue;

                int idx = classIndices.get(i);
                float[] sample = rows.get(idx);

                int kLocal = Math.min(DEFAULT_K, classIndices.size() - 1);
                int[] neighbours = knnSameClass(rows, classIndices, idx, kLocal);

                for (int g = 0; g < numForThis && generated < needed; g++) {
                    int neighbourIdx = classIndices.get(neighbours[rng.nextInt(kLocal)]);
                    float[] neighbour = rows.get(neighbourIdx);

                    float lambda = rng.nextFloat();
                    float[] synthetic = new float[nFeatures];
                    for (int f = 0; f < nFeatures; f++) {
                        synthetic[f] = sample[f] + lambda * (neighbour[f] - sample[f]);
                    }
                    rows.add(synthetic);
                    labels.add(cls);
                    generated++;
                }
            }

            log.accept("ADASYN: generated " + generated + " synthetic samples for class " + cls);
        }
    }

    private static void smoteSingle(
            List<float[]> rows,
            List<Integer> labels,
            List<Integer> classIndices,
            int needed,
            int nFeatures,
            Random rng) {
        int k = Math.min(DEFAULT_K, classIndices.size() - 1);
        int cls = labels.get(classIndices.get(0));
        int generated = 0;
        for (int i = 0; generated < needed; i++) {
            int idx = classIndices.get(i % classIndices.size());
            float[] sample = rows.get(idx);
            int[] neighbours = knnSameClass(rows, classIndices, idx, k);
            int neighbourIdx = classIndices.get(neighbours[rng.nextInt(k)]);
            float[] neighbour = rows.get(neighbourIdx);

            float lambda = rng.nextFloat();
            float[] synthetic = new float[nFeatures];
            for (int f = 0; f < nFeatures; f++) {
                synthetic[f] = sample[f] + lambda * (neighbour[f] - sample[f]);
            }
            rows.add(synthetic);
            labels.add(cls);
            generated++;
        }
    }

    // ── Tomek Links ─────────────────────────────────────────────────────────────

    /**
     * Remove majority-class members of Tomek link pairs.
     * A Tomek link is a pair (a, b) from different classes where each is the
     * other's nearest neighbour of the opposite class.
     *
     * @return number of samples removed
     */
    private static int tomekLinks(
            List<float[]> rows, List<Integer> labels, int[] classCounts, int nClasses, Consumer<String> log) {
        int maxCount = 0;
        for (int c : classCounts) if (c > maxCount) maxCount = c;

        // Identify majority classes (those at the max count)
        Set<Integer> majorityClasses = new HashSet<>();
        for (int c = 0; c < nClasses; c++) {
            if (classCounts[c] == maxCount) majorityClasses.add(c);
        }

        // For each sample, find its nearest neighbour of a different class
        int n = rows.size();
        int[] nnDiffClass = new int[n];
        for (int i = 0; i < n; i++) {
            float[] a = rows.get(i);
            int labelA = labels.get(i);
            float bestDist = Float.MAX_VALUE;
            int bestIdx = -1;
            for (int j = 0; j < n; j++) {
                if (j == i || labels.get(j).equals(labelA)) continue;
                float d = euclideanDistSq(a, rows.get(j));
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = j;
                }
            }
            nnDiffClass[i] = bestIdx;
        }

        // Find Tomek links — mutual nearest different-class neighbours
        Set<Integer> toRemove = new TreeSet<>(Collections.reverseOrder());
        for (int i = 0; i < n; i++) {
            int j = nnDiffClass[i];
            if (j < 0) continue;
            if (nnDiffClass[j] == i) {
                // Tomek link found — remove the majority-class member
                if (majorityClasses.contains(labels.get(i))) {
                    toRemove.add(i);
                } else if (majorityClasses.contains(labels.get(j))) {
                    toRemove.add(j);
                }
            }
        }

        // Remove in reverse index order to preserve indices
        for (int idx : toRemove) {
            rows.remove(idx);
            labels.remove(idx);
        }

        return toRemove.size();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Indices within classIndices of the k nearest same-class neighbours of rows[sampleIdx]. */
    private static int[] knnSameClass(List<float[]> rows, List<Integer> classIndices, int sampleIdx, int k) {
        float[] sample = rows.get(sampleIdx);
        float[] dists = new float[classIndices.size()];
        int selfPos = -1;

        for (int i = 0; i < classIndices.size(); i++) {
            int ci = classIndices.get(i);
            if (ci == sampleIdx) {
                dists[i] = Float.MAX_VALUE;
                selfPos = i;
            } else {
                dists[i] = euclideanDistSq(sample, rows.get(ci));
            }
        }

        // Partial sort to find k smallest
        int[] indices = new int[classIndices.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;

        for (int i = 0; i < k; i++) {
            int minIdx = i;
            for (int j = i + 1; j < indices.length; j++) {
                if (dists[indices[j]] < dists[indices[minIdx]]) {
                    minIdx = j;
                }
            }
            int tmp = indices[i];
            indices[i] = indices[minIdx];
            indices[minIdx] = tmp;
        }

        return Arrays.copyOf(indices, k);
    }

    /** Indices of the k nearest neighbours (all classes) of rows[sampleIdx]. */
    private static int[] knnAll(List<float[]> rows, int sampleIdx, int k) {
        float[] sample = rows.get(sampleIdx);
        int n = rows.size();
        float[] dists = new float[n];
        for (int i = 0; i < n; i++) {
            dists[i] = (i == sampleIdx) ? Float.MAX_VALUE : euclideanDistSq(sample, rows.get(i));
        }

        int[] indices = new int[n];
        for (int i = 0; i < n; i++) indices[i] = i;

        for (int i = 0; i < k; i++) {
            int minIdx = i;
            for (int j = i + 1; j < n; j++) {
                if (dists[indices[j]] < dists[indices[minIdx]]) {
                    minIdx = j;
                }
            }
            int tmp = indices[i];
            indices[i] = indices[minIdx];
            indices[minIdx] = tmp;
        }

        return Arrays.copyOf(indices, k);
    }

    private static float euclideanDistSq(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
    }

    private static List<Integer> indicesOfClass(List<Integer> labels, int cls) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i) == cls) indices.add(i);
        }
        return indices;
    }

    /**
     * Ensure every label is a valid class index in {@code [0, nClasses)}.
     *
     * @throws IllegalArgumentException if {@code nClasses < 1} or any label is out of range
     */
    private static void validateLabels(List<Integer> labels, int nClasses) {
        if (nClasses < 1) {
            throw new IllegalArgumentException("nClasses must be >= 1, was " + nClasses);
        }
        for (int i = 0; i < labels.size(); i++) {
            Integer lbl = labels.get(i);
            if (lbl == null || lbl < 0 || lbl >= nClasses) {
                throw new IllegalArgumentException("Label at index " + i + " is " + lbl + ", outside valid range [0, "
                        + nClasses + "). Training data is out of sync with the class list.");
            }
        }
    }

    private static int[] recount(List<Integer> labels, int nClasses) {
        int[] counts = new int[nClasses];
        for (int lbl : labels) counts[lbl]++;
        return counts;
    }

    /** Result container for resampled data. */
    public static final class Result {
        private final List<float[]> rows;
        private final List<Integer> labels;

        Result(List<float[]> rows, List<Integer> labels) {
            this.rows = rows;
            this.labels = labels;
        }

        public List<float[]> rows() {
            return rows;
        }

        public List<Integer> labels() {
            return labels;
        }
    }
}
