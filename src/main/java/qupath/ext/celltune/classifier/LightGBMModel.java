package qupath.ext.celltune.classifier;

import com.microsoft.ml.lightgbm.PredictionType;
import io.github.metarank.lightgbm4j.LGBMBooster;
import io.github.metarank.lightgbm4j.LGBMDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * Wraps LightGBM4J training and prediction behind the same style interface
 * as {@link XGBoostModel}.
 * <p>
 * Supports binary and multiclass objectives. For multiclass, uses
 * {@code objective=multiclass} with {@code num_class=N}.
 * Probability vectors are always returned as {@code float[nClasses]}.
 */
public class LightGBMModel {

    private static final Logger logger = LoggerFactory.getLogger(LightGBMModel.class);

    private LGBMBooster booster;
    private int nClasses;
    private List<String> classNames;
    private List<String> featureNames;
    private String lastDevice = "unknown";

    // ── Training ────────────────────────────────────────────────────────────────

    /**
     * Train a new LightGBM model.
     *
     * @param flatData     row-major feature matrix (nSamples × nFeatures)
     * @param labels       integer class labels as float (0-indexed)
     * @param nSamples     number of training samples
     * @param nFeatures    number of features per sample
     * @param classNames   ordered list of class names
     * @param featureNames ordered list of feature names
     * @param numRounds    boosting iterations
     * @param maxDepth     max tree depth (-1 for unlimited)
     * @param learningRate learning rate
     * @param subsample    row subsampling ratio per round
     * @throws Exception if training fails
     */
    public void train(float[] flatData, float[] labels,
                      int nSamples, int nFeatures,
                      List<String> classNames, List<String> featureNames,
                      int numRounds, int maxDepth, float learningRate, float subsample)
            throws Exception {

        this.nClasses = classNames.size();
        this.classNames = List.copyOf(classNames);
        this.featureNames = List.copyOf(featureNames);

        // Create dataset from matrix
        LGBMDataset dataset = LGBMDataset.createFromMat(flatData, nSamples, nFeatures, true, "", null);
        // LightGBM also rejects special/non-ASCII chars in feature names
        String[] safeNames = featureNames.stream()
                .map(XGBoostModel::sanitiseFeatureName)
                .toArray(String[]::new);
        dataset.setFeatureNames(safeNames);
        dataset.setField("label", labels);

        // Build parameter string
        String params = buildParams(nClasses, maxDepth, learningRate, subsample);

        // Attempt GPU training, fall back to CPU
        boolean usingGpu = false;
        try {
            String gpuParams = params + " device_type=gpu";
            logger.info("LightGBM: attempting GPU training…");
            booster = LGBMBooster.create(dataset, gpuParams);
            for (int i = 0; i < numRounds; i++) {
                booster.updateOneIter();
            }
            usingGpu = true;
            logger.info("LightGBM training: GPU — {} samples, {} features, {} classes, {} rounds",
                    nSamples, nFeatures, nClasses, numRounds);
        } catch (Exception gpuEx) {
            logger.info("LightGBM GPU not available ({}), falling back to CPU", gpuEx.getMessage());
            // Re-create booster with CPU params
            if (booster != null) {
                try { booster.close(); } catch (Exception ignore) {}
            }
            booster = LGBMBooster.create(dataset, params);
            for (int i = 0; i < numRounds; i++) {
                booster.updateOneIter();
            }
            logger.info("LightGBM training: CPU — {} samples, {} features, {} classes, {} rounds",
                    nSamples, nFeatures, nClasses, numRounds);
        }

        // Close dataset — booster keeps its own copy
        dataset.close();

        logger.info("LightGBM training complete ({})", usingGpu ? "GPU" : "CPU");
        lastDevice = usingGpu ? "GPU" : "CPU";
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
                              int maxRounds, int maxDepth, float learningRate, float subsample,
                              int patience, Consumer<String> log) throws Exception {

        LGBMDataset dataset = LGBMDataset.createFromMat(
                trainData, trainSize, nFeatures, true, "", null);
        dataset.setField("label", trainLabels);

        String params = buildParams(nClasses, maxDepth, learningRate, subsample);
        LGBMBooster booster = LGBMBooster.create(dataset, params);

        try {
            double bestLoss = Double.MAX_VALUE;
            int bestRound = 0;

            for (int round = 0; round < maxRounds; round++) {
                booster.updateOneIter();

                double[] preds = booster.predictForMat(
                        valData, valSize, nFeatures, true,
                        PredictionType.C_API_PREDICT_NORMAL);
                double loss = computeLogloss(preds, valLabels, valSize, nClasses);

                if (loss < bestLoss) {
                    bestLoss = loss;
                    bestRound = round;
                }
                if (round - bestRound >= patience) break;
            }

            int actualRounds = bestRound + 1;
            log.accept(String.format(
                    "LightGBM early stopping: best round %d/%d (val loss: %.6f)",
                    actualRounds, maxRounds, bestLoss));
            return actualRounds;

        } finally {
            booster.close();
            dataset.close();
        }
    }

    /** Compute mean log-loss from raw LightGBM predictions. */
    private static double computeLogloss(double[] preds, float[] labels,
                                         int n, int nClasses) {
        double loss = 0;
        if (nClasses == 2 && preds.length == n) {
            for (int i = 0; i < n; i++) {
                int trueClass = (int) labels[i];
                double p = trueClass == 1 ? preds[i] : 1 - preds[i];
                loss += -Math.log(Math.max(p, 1e-15));
            }
        } else {
            for (int i = 0; i < n; i++) {
                int trueClass = (int) labels[i];
                double p = preds[i * nClasses + trueClass];
                loss += -Math.log(Math.max(p, 1e-15));
            }
        }
        return loss / n;
    }

    // ── Prediction ──────────────────────────────────────────────────────────────

    /**
     * Predict class probabilities for multiple cells.
     *
     * @param flatData  row-major feature matrix (nSamples × nFeatures)
     * @param nSamples  number of samples
     * @param nFeatures number of features
     * @return probability matrix [nSamples][nClasses]
     * @throws Exception if prediction fails
     */
    public float[][] predictProba(float[] flatData, int nSamples, int nFeatures)
            throws Exception {

        double[] rawPreds = booster.predictForMat(flatData, nSamples, nFeatures, true,
                PredictionType.C_API_PREDICT_NORMAL);

        float[][] result = new float[nSamples][nClasses];

        if (nClasses == 2 && rawPreds.length == nSamples) {
            // Binary: raw returns P(class=1), one value per sample
            for (int i = 0; i < nSamples; i++) {
                result[i][1] = (float) rawPreds[i];
                result[i][0] = 1f - result[i][1];
            }
        } else {
            // Multiclass: raw is flat [nSamples * nClasses], row-major
            for (int i = 0; i < nSamples; i++) {
                for (int c = 0; c < nClasses; c++) {
                    result[i][c] = (float) rawPreds[i * nClasses + c];
                }
            }
        }
        return result;
    }

    /**
     * Predict the single best class index for each sample.
     *
     * @param flatData  row-major feature matrix
     * @param nSamples  number of samples
     * @param nFeatures number of features
     * @return array of predicted class indices
     * @throws Exception if prediction fails
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
     * Serialise the trained model to a byte array (UTF-8 model string).
     *
     * @return model bytes
     * @throws Exception if serialisation fails
     */
    public byte[] toBytes() throws Exception {
        String modelStr = booster.saveModelToString(0, 0, LGBMBooster.FeatureImportanceType.SPLIT);
        return modelStr.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Load a model from a byte array.
     *
     * @param bytes        model bytes (UTF-8 model string)
     * @param classNames   ordered class names
     * @param featureNames ordered feature names
     * @throws Exception if loading fails
     */
    public void loadFromBytes(byte[] bytes, List<String> classNames, List<String> featureNames)
            throws Exception {

        this.classNames = List.copyOf(classNames);
        this.featureNames = List.copyOf(featureNames);
        this.nClasses = classNames.size();

        String modelStr = new String(bytes, StandardCharsets.UTF_8);
        booster = LGBMBooster.loadModelFromString(modelStr);
    }

    /**
     * Release native resources held by the booster.
     * Call this when the model is no longer needed.
     */
    public void close() {
        if (booster != null) {
            try {
                booster.close();
            } catch (Exception e) {
                logger.warn("Error closing LightGBM booster", e);
            }
            booster = null;
        }
    }

    // ── Accessors ───────────────────────────────────────────────────────────────

    public boolean isTrained()            { return booster != null; }
    public int getNumClasses()            { return nClasses; }
    public List<String> getClassNames()   { return classNames; }
    public List<String> getFeatureNames() { return featureNames; }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private static String buildParams(int nClasses, int maxDepth,
                                      float learningRate, float subsample) {
        StringBuilder sb = new StringBuilder();
        if (nClasses == 2) {
            sb.append("objective=binary metric=binary_logloss");
        } else {
            sb.append("objective=multiclass metric=multi_logloss num_class=").append(nClasses);
        }
        sb.append(" max_depth=").append(maxDepth);
        sb.append(" learning_rate=").append(learningRate);
        sb.append(" bagging_fraction=").append(subsample);
        sb.append(" bagging_freq=1");
        sb.append(" feature_fraction=0.8");
        sb.append(" num_threads=").append(Runtime.getRuntime().availableProcessors());
        sb.append(" seed=42");
        sb.append(" verbosity=-1"); // suppress LightGBM logs
        return sb.toString();
    }
}
