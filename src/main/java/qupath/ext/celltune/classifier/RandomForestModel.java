package qupath.ext.celltune.classifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * Pure-Java Random Forest classifier matching Python CellTune's
 * {@code RandomForestClassifier(max_depth=100, criterion='log_loss')}.
 * <p>
 * Uses bootstrap aggregation with random feature subsets (mtry = sqrt(nFeatures))
 * at each split. Each tree is a CART decision tree using log-loss (cross-entropy)
 * as the split criterion. Class probabilities are averaged across all trees.
 * <p>
 * Designed to be a drop-in alongside {@link XGBoostModel} and {@link LightGBMModel}
 * with the same method signatures.
 */
public class RandomForestModel {

    private static final Logger logger = LoggerFactory.getLogger(RandomForestModel.class);

    /** Minimum samples required to attempt a split. */
    private static final int MIN_SAMPLES_SPLIT = 2;
    /** Minimum samples in a leaf node. */
    private static final int MIN_SAMPLES_LEAF = 1;

    // ── Serialised tree node (compact, serializable) ────────────────────────────

    private static final byte NODE_INTERNAL = 1;
    private static final byte NODE_LEAF = 2;

    private int nClasses;
    private List<String> classNames;
    private List<String> featureNames;
    private int nTrees;
    private byte[][] serialisedTrees;  // one byte[] per tree for fast serialisation
    private String lastDevice = "CPU";

    // ── In-memory tree structure (used during training, then serialised) ─────────

    private sealed interface Node permits InternalNode, LeafNode {}

    private record InternalNode(int featureIdx, float threshold,
                                Node left, Node right) implements Node {}

    private record LeafNode(float[] classDist) implements Node {}

    // ── Training ────────────────────────────────────────────────────────────────

    /**
     * Train a Random Forest model.
     * <p>
     * Parameter mapping from the boosted-tree API:
     * <ul>
     *   <li>{@code numRounds} → number of trees in the forest (default 100 in sklearn)</li>
     *   <li>{@code maxDepth} → max tree depth (Python CellTune uses 100)</li>
     *   <li>{@code eta} → ignored (no learning rate in RF)</li>
     *   <li>{@code subsample} → bootstrap sample fraction (1.0 = standard bootstrap)</li>
     * </ul>
     */
    public void train(float[] flatData, float[] labels,
                      int nSamples, int nFeatures,
                      List<String> classNames, List<String> featureNames,
                      int numRounds, int maxDepth, float eta, float subsample)
            throws Exception {

        this.nClasses = classNames.size();
        this.classNames = List.copyOf(classNames);
        this.featureNames = List.copyOf(featureNames);
        this.nTrees = numRounds;

        int mtry = Math.max(1, (int) Math.sqrt(nFeatures));
        Random rng = new Random(42);

        // Convert labels to int array
        int[] intLabels = new int[nSamples];
        for (int i = 0; i < nSamples; i++) {
            intLabels[i] = (int) labels[i];
        }

        serialisedTrees = new byte[nTrees][];
        int threads = Runtime.getRuntime().availableProcessors();

        logger.info("Random Forest training: {} trees, max_depth={}, mtry={}, {} samples, {} features, {} classes",
                nTrees, maxDepth, mtry, nSamples, nFeatures, nClasses);

        // Train trees (parallelised)
        Node[] trees = new Node[nTrees];
        Arrays.parallelSetAll(trees, t -> {
            Random treeRng = new Random(42 + t);
            int bootstrapSize = Math.max(1, (int) (nSamples * Math.min(subsample, 1.0f)));
            int[] bootstrap = bootstrapSample(nSamples, bootstrapSize, treeRng);
            return buildTree(flatData, intLabels, nFeatures, bootstrap,
                    maxDepth, mtry, nClasses, treeRng, 0);
        });

        // Serialise trees to byte arrays for compact storage
        for (int t = 0; t < nTrees; t++) {
            serialisedTrees[t] = serialiseTree(trees[t]);
        }

        lastDevice = "CPU";
        logger.info("Random Forest training complete ({} trees)", nTrees);
    }

    /** @return the device used for the last training run */
    public String getLastDevice() { return lastDevice; }

    // ── Prediction ──────────────────────────────────────────────────────────────

    /**
     * Predict class probabilities for multiple cells.
     *
     * @param flatData  row-major feature matrix (nSamples × nFeatures)
     * @param nSamples  number of samples
     * @param nFeatures number of features
     * @return probability matrix [nSamples][nClasses]
     */
    public float[][] predictProba(float[] flatData, int nSamples, int nFeatures)
            throws Exception {

        // Deserialise trees
        Node[] trees = new Node[nTrees];
        for (int t = 0; t < nTrees; t++) {
            trees[t] = deserialiseTree(serialisedTrees[t]);
        }

        float[][] result = new float[nSamples][nClasses];

        for (int i = 0; i < nSamples; i++) {
            // Average class distributions across all trees
            float[] avg = new float[nClasses];
            for (int t = 0; t < nTrees; t++) {
                float[] dist = predictSingle(trees[t], flatData, i, nFeatures);
                for (int c = 0; c < nClasses; c++) {
                    avg[c] += dist[c];
                }
            }
            for (int c = 0; c < nClasses; c++) {
                avg[c] /= nTrees;
            }
            result[i] = avg;
        }
        return result;
    }

    /**
     * Predict the single best class index for each sample.
     */
    public int[] predict(float[] flatData, int nSamples, int nFeatures)
            throws Exception {

        float[][] probs = predictProba(flatData, nSamples, nFeatures);
        int[] preds = new int[nSamples];
        for (int i = 0; i < nSamples; i++) {
            int best = 0;
            for (int c = 1; c < nClasses; c++) {
                if (probs[i][c] > probs[i][best]) best = c;
            }
            preds[i] = best;
        }
        return preds;
    }

    // ── Serialisation ───────────────────────────────────────────────────────────

    /**
     * Serialise the entire model (metadata + all trees) to a byte array.
     */
    public byte[] toBytes() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        // Header
        out.writeInt(nClasses);
        out.writeInt(nTrees);
        out.writeInt(classNames.size());
        for (String cn : classNames) out.writeUTF(cn);
        out.writeInt(featureNames.size());
        for (String fn : featureNames) out.writeUTF(fn);

        // Trees
        for (int t = 0; t < nTrees; t++) {
            out.writeInt(serialisedTrees[t].length);
            out.write(serialisedTrees[t]);
        }

        out.flush();
        return baos.toByteArray();
    }

    /**
     * Load a model from a byte array.
     */
    public void loadFromBytes(byte[] bytes, List<String> classNames, List<String> featureNames)
            throws Exception {

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        this.nClasses = in.readInt();
        this.nTrees = in.readInt();

        int nCN = in.readInt();
        List<String> cn = new ArrayList<>(nCN);
        for (int i = 0; i < nCN; i++) cn.add(in.readUTF());
        this.classNames = List.copyOf(cn);

        int nFN = in.readInt();
        List<String> fn = new ArrayList<>(nFN);
        for (int i = 0; i < nFN; i++) fn.add(in.readUTF());
        this.featureNames = List.copyOf(fn);

        serialisedTrees = new byte[nTrees][];
        for (int t = 0; t < nTrees; t++) {
            int len = in.readInt();
            serialisedTrees[t] = new byte[len];
            in.readFully(serialisedTrees[t]);
        }
    }

    /** Release resources (no-op for pure Java model). */
    public void close() {
        serialisedTrees = null;
    }

    // ── Accessors ───────────────────────────────────────────────────────────────

    public boolean isTrained()            { return serialisedTrees != null; }
    public int getNumClasses()            { return nClasses; }
    public List<String> getClassNames()   { return classNames; }
    public List<String> getFeatureNames() { return featureNames; }

    // ── CART tree building ───────────────────────────────────────────────────────

    private static int[] bootstrapSample(int n, int sampleSize, Random rng) {
        int[] indices = new int[sampleSize];
        for (int i = 0; i < sampleSize; i++) {
            indices[i] = rng.nextInt(n);
        }
        return indices;
    }

    /**
     * Build a CART decision tree using log-loss (cross-entropy) as the
     * split criterion, matching sklearn's {@code criterion='log_loss'}.
     */
    private static Node buildTree(float[] flatData, int[] labels, int nFeatures,
                                  int[] indices, int maxDepth, int mtry,
                                  int nClasses, Random rng, int depth) {

        // Compute class distribution for this node
        float[] classDist = classDist(labels, indices, nClasses);

        // Stopping conditions
        if (depth >= maxDepth || indices.length < MIN_SAMPLES_SPLIT || isPure(classDist)) {
            return new LeafNode(classDist);
        }

        // Select random feature subset
        int[] candidateFeatures = selectFeatures(nFeatures, mtry, rng);

        // Find best split
        float bestGain = 0;
        int bestFeature = -1;
        float bestThreshold = 0;
        int[] bestLeft = null, bestRight = null;

        for (int feat : candidateFeatures) {
            // Collect unique values for this feature among the bootstrap indices
            float[] values = new float[indices.length];
            for (int i = 0; i < indices.length; i++) {
                values[i] = flatData[indices[i] * nFeatures + feat];
            }
            Arrays.sort(values);

            // Try midpoints between consecutive distinct values
            for (int i = 0; i < values.length - 1; i++) {
                if (values[i] == values[i + 1]) continue;
                float threshold = (values[i] + values[i + 1]) / 2f;

                // Partition indices
                int leftCount = 0;
                for (int idx : indices) {
                    if (flatData[idx * nFeatures + feat] <= threshold) leftCount++;
                }
                if (leftCount < MIN_SAMPLES_LEAF || indices.length - leftCount < MIN_SAMPLES_LEAF) {
                    continue;
                }

                int[] left = new int[leftCount];
                int[] right = new int[indices.length - leftCount];
                int li = 0, ri = 0;
                for (int idx : indices) {
                    if (flatData[idx * nFeatures + feat] <= threshold) {
                        left[li++] = idx;
                    } else {
                        right[ri++] = idx;
                    }
                }

                float gain = informationGain(classDist, labels, left, right, nClasses, indices.length);
                if (gain > bestGain) {
                    bestGain = gain;
                    bestFeature = feat;
                    bestThreshold = threshold;
                    bestLeft = left;
                    bestRight = right;
                }
            }
        }

        // No valid split found
        if (bestFeature < 0) {
            return new LeafNode(classDist);
        }

        Node leftChild = buildTree(flatData, labels, nFeatures, bestLeft,
                maxDepth, mtry, nClasses, rng, depth + 1);
        Node rightChild = buildTree(flatData, labels, nFeatures, bestRight,
                maxDepth, mtry, nClasses, rng, depth + 1);

        return new InternalNode(bestFeature, bestThreshold, leftChild, rightChild);
    }

    /** Compute normalised class distribution for the given indices. */
    private static float[] classDist(int[] labels, int[] indices, int nClasses) {
        float[] dist = new float[nClasses];
        for (int idx : indices) {
            dist[labels[idx]] += 1f;
        }
        float total = indices.length;
        for (int c = 0; c < nClasses; c++) {
            dist[c] /= total;
        }
        return dist;
    }

    /** Check if a class distribution is pure (only one class). */
    private static boolean isPure(float[] dist) {
        int nonZero = 0;
        for (float d : dist) {
            if (d > 0) nonZero++;
        }
        return nonZero <= 1;
    }

    /** Select mtry random feature indices without replacement. */
    private static int[] selectFeatures(int nFeatures, int mtry, Random rng) {
        if (mtry >= nFeatures) {
            int[] all = new int[nFeatures];
            for (int i = 0; i < nFeatures; i++) all[i] = i;
            return all;
        }
        Set<Integer> selected = new LinkedHashSet<>();
        while (selected.size() < mtry) {
            selected.add(rng.nextInt(nFeatures));
        }
        return selected.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Compute information gain using cross-entropy (log-loss), matching
     * sklearn's {@code criterion='log_loss'}.
     * Gain = H(parent) - weighted_avg(H(left), H(right))
     */
    private static float informationGain(float[] parentDist, int[] labels,
                                         int[] left, int[] right,
                                         int nClasses, int total) {
        float parentEntropy = crossEntropy(parentDist);
        float[] leftDist = classDist(labels, left, nClasses);
        float[] rightDist = classDist(labels, right, nClasses);
        float leftEntropy = crossEntropy(leftDist);
        float rightEntropy = crossEntropy(rightDist);

        float wLeft = (float) left.length / total;
        float wRight = (float) right.length / total;

        return parentEntropy - (wLeft * leftEntropy + wRight * rightEntropy);
    }

    /** Cross-entropy: -sum(p * log(p)) */
    private static float crossEntropy(float[] dist) {
        float entropy = 0;
        for (float p : dist) {
            if (p > 0) {
                entropy -= p * (float) Math.log(p);
            }
        }
        return entropy;
    }

    /** Traverse a tree to predict class distribution for a single sample. */
    private static float[] predictSingle(Node node, float[] flatData,
                                         int sampleIdx, int nFeatures) {
        return switch (node) {
            case LeafNode leaf -> leaf.classDist();
            case InternalNode internal -> {
                float value = flatData[sampleIdx * nFeatures + internal.featureIdx()];
                yield value <= internal.threshold()
                        ? predictSingle(internal.left(), flatData, sampleIdx, nFeatures)
                        : predictSingle(internal.right(), flatData, sampleIdx, nFeatures);
            }
        };
    }

    // ── Tree serialisation (compact binary format) ──────────────────────────────

    private static byte[] serialiseTree(Node node) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        writeNode(out, node);
        out.flush();
        return baos.toByteArray();
    }

    private static void writeNode(DataOutputStream out, Node node) throws IOException {
        switch (node) {
            case LeafNode leaf -> {
                out.writeByte(NODE_LEAF);
                out.writeInt(leaf.classDist().length);
                for (float v : leaf.classDist()) out.writeFloat(v);
            }
            case InternalNode internal -> {
                out.writeByte(NODE_INTERNAL);
                out.writeInt(internal.featureIdx());
                out.writeFloat(internal.threshold());
                writeNode(out, internal.left());
                writeNode(out, internal.right());
            }
        }
    }

    private static Node deserialiseTree(byte[] bytes) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        return readNode(in);
    }

    private static Node readNode(DataInputStream in) throws IOException {
        byte type = in.readByte();
        if (type == NODE_LEAF) {
            int n = in.readInt();
            float[] dist = new float[n];
            for (int i = 0; i < n; i++) dist[i] = in.readFloat();
            return new LeafNode(dist);
        } else {
            int featureIdx = in.readInt();
            float threshold = in.readFloat();
            Node left = readNode(in);
            Node right = readNode(in);
            return new InternalNode(featureIdx, threshold, left, right);
        }
    }
}
