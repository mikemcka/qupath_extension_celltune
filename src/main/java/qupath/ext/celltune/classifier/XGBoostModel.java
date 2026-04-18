package qupath.ext.celltune.classifier;

import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Wraps XGBoost4J training and prediction behind a simple interface.
 * <p>
 * Supports both binary ({@code binary:logistic}) and multiclass
 * ({@code multi:softprob}) objectives. Probability vectors are always
 * returned as {@code float[nClasses]}.
 */
public class XGBoostModel {

    private static final Logger logger = LoggerFactory.getLogger(XGBoostModel.class);

    private Booster booster;
    private int nClasses;
    private List<String> classNames;
    private List<String> featureNames;
    private String lastDevice = "unknown";

    // ── Training ────────────────────────────────────────────────────────────────

    /**
     * Train a new XGBoost model.
     *
     * @param flatData     row-major feature matrix (nSamples × nFeatures)
     * @param labels       integer class labels (0-indexed)
     * @param nSamples     number of training samples
     * @param nFeatures    number of features per sample
     * @param classNames   ordered list of class names
     * @param featureNames ordered list of feature names
     * @param numRounds    boosting iterations
     * @param maxDepth     max tree depth
     * @param eta          learning rate
     * @param subsample    row subsampling ratio per round
     * @throws XGBoostError if training fails
     */
    public void train(float[] flatData, float[] labels,
                      int nSamples, int nFeatures,
                      List<String> classNames, List<String> featureNames,
                      int numRounds, int maxDepth, float eta, float subsample)
            throws XGBoostError {

        this.nClasses = classNames.size();
        this.classNames = List.copyOf(classNames);
        this.featureNames = List.copyOf(featureNames);

        DMatrix trainMat = new DMatrix(flatData, nSamples, nFeatures, Float.NaN);
        try {
            trainMat.setLabel(labels);

            Map<String, Object> params = buildParams(nClasses, maxDepth, eta, subsample);
            Map<String, DMatrix> watches = new LinkedHashMap<>();
            watches.put("train", trainMat);

            // Attempt GPU training, fall back to CPU
            boolean usingGpu = false;
            try {
                params.put("device", "cuda");
                params.put("tree_method", "hist");
                logger.info("XGBoost: attempting GPU (CUDA) training…");
                booster = XGBoost.train(trainMat, params, numRounds, watches, null, null);

                // XGBoost may silently fall back to CPU without throwing.
                // Check the JSON model dump to see whether CUDA was actually used.
                try {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    booster.saveModel(buf, "json");
                    String modelJson = buf.toString(StandardCharsets.UTF_8);
                    usingGpu = modelJson.contains("\"device\": \"cuda\"")
                            || modelJson.contains("\"device\":\"cuda\"");
                } catch (Exception probeEx) {
                    // If JSON dump fails, assume GPU since training didn't throw
                    logger.warn("Could not verify GPU via model dump: {}", probeEx.getMessage());
                    usingGpu = true;
                }

                if (usingGpu) {
                    logger.info("XGBoost training: GPU (CUDA) — {} samples, {} features, {} classes, {} rounds",
                            nSamples, nFeatures, nClasses, numRounds);
                } else {
                    logger.info("XGBoost: CUDA requested but device silently fell back to CPU");
                    logger.info("XGBoost training: CPU — {} samples, {} features, {} classes, {} rounds",
                            nSamples, nFeatures, nClasses, numRounds);
                }
            } catch (Exception gpuEx) {
                logger.info("XGBoost GPU not available ({}), falling back to CPU", gpuEx.getMessage());
                params.put("device", "cpu");
                params.put("tree_method", "hist");
                booster = XGBoost.train(trainMat, params, numRounds, watches, null, null);
                logger.info("XGBoost training: CPU — {} samples, {} features, {} classes, {} rounds",
                        nSamples, nFeatures, nClasses, numRounds);
            }

            this.lastDevice = usingGpu ? "GPU (CUDA)" : "CPU";
        } finally {
            trainMat.dispose();
        }

        // Embed metadata for later serialisation
        // XGBoost rejects special chars (µ, ^, :, /, etc.) in feature names
        String[] safeNames = featureNames.stream()
                .map(XGBoostModel::sanitiseFeatureName)
                .toArray(String[]::new);
        booster.setFeatureNames(safeNames);
        booster.setAttr("class_names", String.join(",", classNames));

        logger.info("XGBoost training complete ({})", lastDevice);
    }

    /** @return the device used for the last training run */
    public String getLastDevice() { return lastDevice; }

    // ── Early Stopping ──────────────────────────────────────────────────────────

    /**
     * Find the optimal number of boosting rounds by training on a subset and
     * monitoring validation loss. Uses CPU only for speed.
     *
     * @return optimal number of rounds (1-indexed)
     */
    static int findBestRounds(float[] trainData, float[] trainLabels, int trainSize,
                              float[] valData, float[] valLabels, int valSize,
                              int nFeatures, int nClasses,
                              int maxRounds, int maxDepth, float eta, float subsample,
                              int patience, Consumer<String> log) throws Exception {

        DMatrix trainMat = null;
        DMatrix valMat = null;
        try {
            trainMat = new DMatrix(trainData, trainSize, nFeatures, Float.NaN);
            trainMat.setLabel(trainLabels);
            valMat = new DMatrix(valData, valSize, nFeatures, Float.NaN);
            valMat.setLabel(valLabels);

            Map<String, Object> params = buildParams(nClasses, maxDepth, eta, subsample);
            params.put("device", "cpu");
            params.put("tree_method", "hist");
            params.put("verbosity", 0);

            // Train 1 round to create a Booster
            Booster booster = XGBoost.train(trainMat, params, 1,
                    new LinkedHashMap<>(), null, null);

            String evalStr = booster.evalSet(
                    new DMatrix[]{valMat}, new String[]{"val"}, 0);
            double bestLoss = parseEvalMetric(evalStr);
            int bestRound = 0;

            for (int round = 1; round < maxRounds; round++) {
                booster.update(trainMat, round);
                evalStr = booster.evalSet(
                        new DMatrix[]{valMat}, new String[]{"val"}, round);
                double loss = parseEvalMetric(evalStr);

                if (loss < bestLoss) {
                    bestLoss = loss;
                    bestRound = round;
                }
                if (round - bestRound >= patience) break;
            }

            int actualRounds = bestRound + 1;
            log.accept(String.format(
                    "XGBoost early stopping: best round %d/%d (val loss: %.6f)",
                    actualRounds, maxRounds, bestLoss));
            return actualRounds;

        } finally {
            try { if (trainMat != null) trainMat.dispose(); } catch (Exception ignore) {}
            try { if (valMat != null) valMat.dispose(); } catch (Exception ignore) {}
        }
    }

    /** Parse the last metric value from an XGBoost evalSet string. */
    private static double parseEvalMetric(String evalStr) {
        int lastColon = evalStr.lastIndexOf(':');
        if (lastColon < 0) return Double.MAX_VALUE;
        String valStr = evalStr.substring(lastColon + 1).trim();
        try {
            return Double.parseDouble(valStr);
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

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
            throws XGBoostError {

        DMatrix predMat = new DMatrix(flatData, nSamples, nFeatures, Float.NaN);
        float[][] rawPreds;
        try {
            rawPreds = booster.predict(predMat);
        } finally {
            predMat.dispose();
        }

        // binary:logistic returns [n][1]; multi:softprob returns [n][nClasses]
        if (nClasses == 2 && rawPreds[0].length == 1) {
            float[][] expanded = new float[nSamples][2];
            for (int i = 0; i < nSamples; i++) {
                expanded[i][1] = rawPreds[i][0];
                expanded[i][0] = 1f - rawPreds[i][0];
            }
            return expanded;
        }
        return rawPreds;
    }

    /**
     * Predict the single best class index for each sample.
     *
     * @param flatData  row-major feature matrix
     * @param nSamples  number of samples
     * @param nFeatures number of features
     * @return array of predicted class indices
     */
    public int[] predict(float[] flatData, int nSamples, int nFeatures)
            throws XGBoostError {

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
     * Serialise the trained model to a byte array.
     *
     * @return raw model bytes
     * @throws XGBoostError if serialisation fails
     */
    public byte[] toBytes() throws XGBoostError {
        return booster.toByteArray();
    }

    /**
     * Load a model from a byte array.
     *
     * @param bytes       raw model bytes
     * @param classNames  ordered class names
     * @param featureNames ordered feature names
     * @throws XGBoostError if loading fails
     */
    public void loadFromBytes(byte[] bytes, List<String> classNames, List<String> featureNames)
            throws XGBoostError, IOException {

        this.classNames = List.copyOf(classNames);
        this.featureNames = List.copyOf(featureNames);
        this.nClasses = classNames.size();

        // XGBoost4J loadModel expects an InputStream
        booster = XGBoost.loadModel(new ByteArrayInputStream(bytes));
    }

    // ── Accessors ───────────────────────────────────────────────────────────────

    public boolean isTrained()            { return booster != null; }
    public int getNumClasses()            { return nClasses; }
    public List<String> getClassNames()   { return classNames; }
    public List<String> getFeatureNames() { return featureNames; }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Replace characters that XGBoost4J rejects in feature names.
     * XGBoost forbids: [ ] < > , " and any non-ASCII.
     */
    static String sanitiseFeatureName(String name) {
        // Replace known problematic chars with ASCII equivalents
        String s = name
                .replace("\u00b5", "u")  // micro sign µ
                .replace("\u03bc", "u")  // greek mu μ
                .replace("^", "_pow_")
                .replace(":", "_")
                .replace("/", "_per_")
                .replace(" ", "_")
                .replace("[", "_")
                .replace("]", "_")
                .replace("<", "_lt_")
                .replace(">", "_gt_")
                .replace(",", "_")
                .replace("\"", "_")
                .replace(".", "_")
                .replace("{", "_")
                .replace("}", "_");
        // Strip any remaining non-ASCII
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c <= 0x7E ? c : '_');
        }
        return sb.toString();
    }

    private static Map<String, Object> buildParams(
            int nClasses, int maxDepth, float eta, float subsample) {

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("max_depth", maxDepth);
        p.put("eta", (double) eta);
        p.put("subsample", (double) subsample);
        p.put("colsample_bytree", 0.8);
        p.put("objective", nClasses == 2 ? "binary:logistic" : "multi:softprob");
        p.put("eval_metric", nClasses == 2 ? "logloss" : "mlogloss");
        p.put("nthread", Runtime.getRuntime().availableProcessors());
        p.put("seed", 42);
        if (nClasses > 2) p.put("num_class", nClasses);
        return p;
    }
}
