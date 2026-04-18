package qupath.ext.celltune.classifier;

import com.microsoft.ml.lightgbm.PredictionType;
import io.github.metarank.lightgbm4j.LGBMBooster;
import io.github.metarank.lightgbm4j.LGBMDataset;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * Random search hyperparameter tuner with stratified k-fold cross-validation.
 * <p>
 * Evaluates random hyperparameter combinations on both XGBoost and LightGBM,
 * scoring each trial by the mean macro F1 across folds and models.
 * Returns the best-performing parameter set.
 * <p>
 * All training during tuning uses CPU to avoid GPU probe overhead.
 */
public final class HyperparameterTuner {

    private static final Logger logger = LoggerFactory.getLogger(HyperparameterTuner.class);

    /** Default number of random search trials. */
    public static final int DEFAULT_TRIALS = 20;

    /** Default number of CV folds. */
    public static final int DEFAULT_FOLDS = 5;

    /** Minimum training samples required for tuning. */
    private static final int MIN_SAMPLES = 20;

    // ── Search space bounds ─────────────────────────────────────────────────
    private static final int ROUNDS_MIN = 50, ROUNDS_MAX = 500;
    private static final int DEPTH_MIN = 2, DEPTH_MAX = 12;
    private static final double ETA_MIN = 0.01, ETA_MAX = 0.3;
    private static final double SUB_MIN = 0.5, SUB_MAX = 1.0;

    private HyperparameterTuner() {} // utility class

    // ── Result types ────────────────────────────────────────────────────────

    /** A set of hyperparameters for boosted tree models. */
    public record HyperParams(int numRounds, int maxDepth, float eta, float subsample) {
        @Override
        public String toString() {
            return String.format("rounds=%d, depth=%d, eta=%.4f, subsample=%.2f",
                    numRounds, maxDepth, eta, subsample);
        }
    }

    /** Result of a tuning run: best parameters and their CV score. */
    public record TuningResult(HyperParams bestParams, double bestScore) {}

    // ── Main API ────────────────────────────────────────────────────────────

    /**
     * Tune hyperparameters via random search with stratified k-fold CV.
     * <p>
     * For each trial, a random parameter set is sampled and evaluated on
     * both XGBoost and LightGBM across all folds. The score is the mean
     * macro F1 averaged across both models and all folds.
     *
     * @param flatData  row-major training feature matrix
     * @param labels    float class labels (0-indexed)
     * @param nSamples  number of training samples
     * @param nFeatures number of features per sample
     * @param nClasses  number of classes
     * @param nTrials   number of random search iterations
     * @param nFolds    number of CV folds (typically 5)
     * @param log       progress callback (may be null)
     * @return best parameters and their score
     */
    public static TuningResult tune(float[] flatData, float[] labels,
                                    int nSamples, int nFeatures, int nClasses,
                                    int nTrials, int nFolds,
                                    Consumer<String> log) {
        Consumer<String> out = log != null ? log : s -> {};

        if (nSamples < MIN_SAMPLES) {
            out.accept("Too few samples (" + nSamples + ") for auto-tuning — using defaults");
            return new TuningResult(new HyperParams(200, 6, 0.1f, 0.8f), 0);
        }

        out.accept("Auto-tuning: " + nTrials + " trials × " + nFolds
                + " folds on " + nSamples + " samples");

        Random rng = new Random(42);

        // Integer labels for stratification
        int[] intLabels = new int[nSamples];
        for (int i = 0; i < nSamples; i++) intLabels[i] = (int) labels[i];

        List<int[][]> folds = stratifiedKFold(intLabels, nClasses, nFolds, rng);

        HyperParams bestParams = null;
        double bestScore = -1;

        for (int trial = 0; trial < nTrials; trial++) {
            HyperParams hp = sampleRandom(rng);

            double totalScore = 0;
            int validFolds = 0;

            for (var fold : folds) {
                int[] trainIdx = fold[0];
                int[] testIdx = fold[1];

                float[] foldTrainData = extractRows(flatData, trainIdx, nFeatures);
                float[] foldTrainLabels = extractLabels(labels, trainIdx);
                float[] foldTestData = extractRows(flatData, testIdx, nFeatures);
                int[] foldTestTruth = extractIntLabels(intLabels, testIdx);

                double xgbF1 = evaluateXGBoostFold(
                        foldTrainData, foldTrainLabels, trainIdx.length,
                        foldTestData, foldTestTruth, testIdx.length,
                        nFeatures, nClasses, hp);

                double lgbF1 = evaluateLightGBMFold(
                        foldTrainData, foldTrainLabels, trainIdx.length,
                        foldTestData, foldTestTruth, testIdx.length,
                        nFeatures, nClasses, hp);

                if (xgbF1 >= 0 && lgbF1 >= 0) {
                    totalScore += (xgbF1 + lgbF1) / 2.0;
                    validFolds++;
                }
            }

            if (validFolds > 0) {
                double meanScore = totalScore / validFolds;
                out.accept(String.format("  Trial %2d/%d: %s → F1 = %.4f",
                        trial + 1, nTrials, hp, meanScore));

                if (meanScore > bestScore) {
                    bestScore = meanScore;
                    bestParams = hp;
                }
            } else {
                out.accept(String.format("  Trial %2d/%d: %s → failed (no valid folds)",
                        trial + 1, nTrials, hp));
            }
        }

        if (bestParams == null) {
            bestParams = new HyperParams(200, 6, 0.1f, 0.8f);
            out.accept("Tuning produced no valid results — using defaults");
        } else {
            out.accept(String.format("Best: %s → F1 = %.4f", bestParams, bestScore));
        }

        return new TuningResult(bestParams, bestScore);
    }

    // ── Random parameter sampling ───────────────────────────────────────────

    private static HyperParams sampleRandom(Random rng) {
        // Uniform integer, rounded to nearest 10
        int numRounds = ROUNDS_MIN + rng.nextInt(ROUNDS_MAX - ROUNDS_MIN + 1);
        numRounds = ((numRounds + 5) / 10) * 10;

        int maxDepth = DEPTH_MIN + rng.nextInt(DEPTH_MAX - DEPTH_MIN + 1);

        // Log-uniform for learning rate
        float eta = (float) Math.exp(
                Math.log(ETA_MIN) + rng.nextDouble() * (Math.log(ETA_MAX) - Math.log(ETA_MIN)));

        // Uniform for subsample
        float subsample = (float) (SUB_MIN + rng.nextDouble() * (SUB_MAX - SUB_MIN));

        return new HyperParams(numRounds, maxDepth, eta, subsample);
    }

    // ── Stratified k-fold ───────────────────────────────────────────────────

    private static List<int[][]> stratifiedKFold(int[] labels, int nClasses,
                                                 int k, Random rng) {
        // Group sample indices by class
        List<List<Integer>> classGroups = new ArrayList<>();
        for (int c = 0; c < nClasses; c++) classGroups.add(new ArrayList<>());
        for (int i = 0; i < labels.length; i++) classGroups.get(labels[i]).add(i);

        // Shuffle each group
        for (var group : classGroups) Collections.shuffle(group, rng);

        // Round-robin assignment to folds
        List<List<Integer>> foldLists = new ArrayList<>();
        for (int f = 0; f < k; f++) foldLists.add(new ArrayList<>());

        for (var group : classGroups) {
            for (int i = 0; i < group.size(); i++) {
                foldLists.get(i % k).add(group.get(i));
            }
        }

        // Build train/test index pairs
        List<int[][]> folds = new ArrayList<>();
        for (int f = 0; f < k; f++) {
            List<Integer> testList = foldLists.get(f);
            List<Integer> trainList = new ArrayList<>();
            for (int g = 0; g < k; g++) {
                if (g != f) trainList.addAll(foldLists.get(g));
            }
            folds.add(new int[][] {
                    trainList.stream().mapToInt(Integer::intValue).toArray(),
                    testList.stream().mapToInt(Integer::intValue).toArray()
            });
        }

        return folds;
    }

    // ── XGBoost fold evaluation ─────────────────────────────────────────────

    private static double evaluateXGBoostFold(
            float[] trainData, float[] trainLabels, int trainSize,
            float[] testData, int[] testTruth, int testSize,
            int nFeatures, int nClasses, HyperParams hp) {

        DMatrix trainMat = null;
        DMatrix testMat = null;
        try {
            trainMat = new DMatrix(trainData, trainSize, nFeatures, Float.NaN);
            trainMat.setLabel(trainLabels);

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("max_depth", hp.maxDepth());
            params.put("eta", (double) hp.eta());
            params.put("subsample", (double) hp.subsample());
            params.put("colsample_bytree", 0.8);
            params.put("objective", nClasses == 2 ? "binary:logistic" : "multi:softprob");
            params.put("eval_metric", nClasses == 2 ? "logloss" : "mlogloss");
            params.put("device", "cpu");
            params.put("tree_method", "hist");
            params.put("nthread", Runtime.getRuntime().availableProcessors());
            params.put("seed", 42);
            params.put("verbosity", 0);
            if (nClasses > 2) params.put("num_class", nClasses);

            Booster booster = XGBoost.train(trainMat, params, hp.numRounds(),
                    new LinkedHashMap<>(), null, null);

            testMat = new DMatrix(testData, testSize, nFeatures, Float.NaN);
            float[][] preds = booster.predict(testMat);

            int[] predClasses = toPredictedClasses(preds, testSize, nClasses);
            return macroF1(predClasses, testTruth, nClasses);

        } catch (Exception e) {
            logger.debug("XGBoost CV fold failed: {}", e.getMessage());
            return -1;
        } finally {
            try { if (trainMat != null) trainMat.dispose(); } catch (Exception ignore) {}
            try { if (testMat != null) testMat.dispose(); } catch (Exception ignore) {}
        }
    }

    // ── LightGBM fold evaluation ────────────────────────────────────────────

    private static double evaluateLightGBMFold(
            float[] trainData, float[] trainLabels, int trainSize,
            float[] testData, int[] testTruth, int testSize,
            int nFeatures, int nClasses, HyperParams hp) {

        LGBMDataset dataset = null;
        LGBMBooster booster = null;
        try {
            dataset = LGBMDataset.createFromMat(
                    trainData, trainSize, nFeatures, true, "", null);
            dataset.setField("label", trainLabels);

            StringBuilder sb = new StringBuilder();
            if (nClasses == 2) {
                sb.append("objective=binary metric=binary_logloss");
            } else {
                sb.append("objective=multiclass metric=multi_logloss num_class=")
                  .append(nClasses);
            }
            sb.append(" max_depth=").append(hp.maxDepth());
            sb.append(" learning_rate=").append(hp.eta());
            sb.append(" bagging_fraction=").append(hp.subsample());
            sb.append(" bagging_freq=1");
            sb.append(" feature_fraction=0.8");
            sb.append(" num_threads=").append(Runtime.getRuntime().availableProcessors());
            sb.append(" seed=42");
            sb.append(" verbosity=-1");

            booster = LGBMBooster.create(dataset, sb.toString());
            for (int i = 0; i < hp.numRounds(); i++) {
                booster.updateOneIter();
            }

            double[] rawPreds = booster.predictForMat(
                    testData, testSize, nFeatures, true,
                    PredictionType.C_API_PREDICT_NORMAL);

            int[] predClasses = toLGBPredictedClasses(rawPreds, testSize, nClasses);
            return macroF1(predClasses, testTruth, nClasses);

        } catch (Exception e) {
            logger.debug("LightGBM CV fold failed: {}", e.getMessage());
            return -1;
        } finally {
            try { if (booster != null) booster.close(); } catch (Exception ignore) {}
            try { if (dataset != null) dataset.close(); } catch (Exception ignore) {}
        }
    }

    // ── Prediction helpers ──────────────────────────────────────────────────

    private static int[] toPredictedClasses(float[][] preds, int n, int nClasses) {
        int[] result = new int[n];
        if (nClasses == 2 && preds[0].length == 1) {
            for (int i = 0; i < n; i++) {
                result[i] = preds[i][0] >= 0.5f ? 1 : 0;
            }
        } else {
            for (int i = 0; i < n; i++) {
                int best = 0;
                for (int c = 1; c < nClasses; c++) {
                    if (preds[i][c] > preds[i][best]) best = c;
                }
                result[i] = best;
            }
        }
        return result;
    }

    private static int[] toLGBPredictedClasses(double[] raw, int n, int nClasses) {
        int[] result = new int[n];
        if (nClasses == 2 && raw.length == n) {
            for (int i = 0; i < n; i++) {
                result[i] = raw[i] >= 0.5 ? 1 : 0;
            }
        } else {
            for (int i = 0; i < n; i++) {
                int best = 0;
                for (int c = 1; c < nClasses; c++) {
                    if (raw[i * nClasses + c] > raw[i * nClasses + best]) best = c;
                }
                result[i] = best;
            }
        }
        return result;
    }

    // ── Scoring ─────────────────────────────────────────────────────────────

    private static double macroF1(int[] predicted, int[] truth, int nClasses) {
        int[] tp = new int[nClasses];
        int[] fp = new int[nClasses];
        int[] fn = new int[nClasses];

        for (int i = 0; i < predicted.length; i++) {
            if (predicted[i] == truth[i]) {
                tp[predicted[i]]++;
            } else {
                fp[predicted[i]]++;
                fn[truth[i]]++;
            }
        }

        double sumF1 = 0;
        int counted = 0;
        for (int c = 0; c < nClasses; c++) {
            int support = tp[c] + fn[c];
            if (support == 0) continue; // class absent from truth in this fold
            double precision = (tp[c] + fp[c]) > 0
                    ? (double) tp[c] / (tp[c] + fp[c]) : 0;
            double recall = (double) tp[c] / (tp[c] + fn[c]);
            double f1 = (precision + recall) > 0
                    ? 2 * precision * recall / (precision + recall) : 0;
            sumF1 += f1;
            counted++;
        }

        return counted > 0 ? sumF1 / counted : 0;
    }

    // ── Data extraction helpers ─────────────────────────────────────────────

    private static float[] extractRows(float[] flatData, int[] indices, int nFeatures) {
        float[] result = new float[indices.length * nFeatures];
        for (int i = 0; i < indices.length; i++) {
            System.arraycopy(flatData, indices[i] * nFeatures,
                    result, i * nFeatures, nFeatures);
        }
        return result;
    }

    private static float[] extractLabels(float[] labels, int[] indices) {
        float[] result = new float[indices.length];
        for (int i = 0; i < indices.length; i++) result[i] = labels[indices[i]];
        return result;
    }

    private static int[] extractIntLabels(int[] labels, int[] indices) {
        int[] result = new int[indices.length];
        for (int i = 0; i < indices.length; i++) result[i] = labels[indices[i]];
        return result;
    }
}
