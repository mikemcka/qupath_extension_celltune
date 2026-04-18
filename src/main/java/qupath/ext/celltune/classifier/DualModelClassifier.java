package qupath.ext.celltune.classifier;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellFeatureExtractor;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.LabelStore;
import qupath.ext.celltune.model.PopulationSet;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.*;
import java.util.function.Consumer;

/**
 * Orchestrates training of both XGBoost and LightGBM models on the same
 * labelled data, then produces four population sets from predictions on
 * all cells.
 * <p>
 * The four population sets mirror CellTune's design:
 * <ul>
 *   <li><b>Pred_MDL1</b> — XGBoost predictions only</li>
 *   <li><b>Pred_MDL2</b> — LightGBM predictions only</li>
 *   <li><b>Pred_AVG</b>  — averaged probabilities from both models</li>
 *   <li><b>Pred_ALL</b>  — agreed label when models agree; both labels when they disagree</li>
 * </ul>
 * <p>
 * Training runs on whatever thread calls {@link #trainAndPredict}, so callers
 * should invoke it from a background thread. The {@link #progressProperty()}
 * and {@link #statusProperty()} can be bound to JavaFX UI elements and are
 * updated on the FX Application Thread.
 */
public class DualModelClassifier {

    private static final Logger logger = LoggerFactory.getLogger(DualModelClassifier.class);

    // ── Default hyperparameters ─────────────────────────────────────────────────
    private int numRounds = 200;
    private int maxDepth  = 6;
    private float eta     = 0.1f;
    private float subsample = 0.8f;

    // ── Models ──────────────────────────────────────────────────────────────────
    private final XGBoostModel xgbModel  = new XGBoostModel();
    private final LightGBMModel lgbModel = new LightGBMModel();

    // ── Observable progress for UI binding ──────────────────────────────────────
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty status   = new SimpleStringProperty("");

    // ── Population sets (filled after trainAndPredict) ──────────────────────────
    private PopulationSet predMDL1;
    private PopulationSet predMDL2;
    private PopulationSet predAVG;
    private PopulationSet predALL;
    private List<String> classNames;
    private List<String> featureNames;

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * Train both models on labelled cells, then predict all cells.
     * <p>
     * Call from a background thread. Progress and status properties are
     * updated on the FX Application Thread for safe UI binding.
     *
     * @param allCells             all detection objects in the current image
     * @param labelStore           ground-truth labels (cellId → class name)
     * @param extractor            feature extractor with fixed column ordering
     * @param supplementaryRows    pre-extracted feature rows from other images (may be null)
     * @param supplementaryLabels  class names for supplementary rows (may be null)
     * @param resampling           resampling strategy for class imbalance (may be null for NONE)
     * @param autoTune             if true, run TPE search to find optimal hyperparameters independently per model
     * @param earlyStop            if true, use early stopping to find optimal round counts
     * @param log                  optional progress callback (may be null)
     * @throws Exception if training or prediction fails
     */
    public void trainAndPredict(Collection<PathObject> allCells,
                                LabelStore labelStore,
                                CellFeatureExtractor extractor,
                                List<float[]> supplementaryRows,
                                List<String> supplementaryLabels,
                                ResamplingStrategy resampling,
                                boolean autoTune,
                                boolean earlyStop,
                                Consumer<String> log) throws Exception {

        Consumer<String> out = log != null ? log : s -> {};

        // ── 1. Collect training data ────────────────────────────────────────
        updateStatus("Collecting training data…", 0.0);
        out.accept("Collecting training data…");

        this.featureNames = extractor.getFeatureNames();

        // Build class name set — include supplementary classes so they get indices
        Set<String> classSet = new LinkedHashSet<>(labelStore.getClassNames());
        if (supplementaryLabels != null) {
            classSet.addAll(supplementaryLabels);
        }
        this.classNames = new ArrayList<>(classSet);
        Collections.sort(this.classNames); // deterministic ordering
        int nClasses = classNames.size();

        if (nClasses < 2) {
            throw new IllegalStateException(
                    "Need at least 2 classes to train, found " + nClasses);
        }

        // Map cell IDs to PathObjects for fast lookup
        Map<String, PathObject> cellById = new LinkedHashMap<>();
        for (PathObject cell : allCells) {
            cellById.put(cell.getID().toString(), cell);
        }

        // Build training arrays from current image
        List<float[]> trainRows = new ArrayList<>();
        List<Integer> trainLabels = new ArrayList<>();

        for (var entry : labelStore.getAllLabels().entrySet()) {
            String cellId = entry.getKey();
            String className = entry.getValue();
            PathObject cell = cellById.get(cellId);
            if (cell == null) continue; // cell not in current image

            int classIdx = classNames.indexOf(className);
            if (classIdx < 0) continue; // unknown class

            trainRows.add(extractor.extractRow(cell));
            trainLabels.add(classIdx);
        }

        int currentImageSamples = trainRows.size();

        // Append supplementary training data from other images
        if (supplementaryRows != null && supplementaryLabels != null) {
            int suppCount = 0;
            for (int i = 0; i < supplementaryRows.size(); i++) {
                String className = supplementaryLabels.get(i);
                int classIdx = classNames.indexOf(className);
                if (classIdx < 0) continue;
                trainRows.add(supplementaryRows.get(i));
                trainLabels.add(classIdx);
                suppCount++;
            }
            if (suppCount > 0) {
                out.accept("Pooled " + suppCount + " labelled cells from other images "
                        + "(current image: " + currentImageSamples + ")");
            }
        }

        int nSamples = trainRows.size();
        int nFeatures = extractor.getNumFeatures();

        if (nSamples < nClasses * 2) {
            throw new IllegalStateException(
                    "Too few training samples (" + nSamples + ") for " + nClasses
                    + " classes. Label more cells before training.");
        }

        out.accept("Training data: " + nSamples + " cells, "
                + nFeatures + " features, " + nClasses + " classes");
        out.accept("Threads: " + Runtime.getRuntime().availableProcessors()
                + " available processors");

        // ── 1b. Early stopping (split BEFORE resampling — validate on real data only)
        //        Save original data for the split, then resample separately.
        ResamplingStrategy strategy = resampling != null ? resampling : ResamplingStrategy.NONE;

        // Local per-model hyperparameters
        int xgbRounds = numRounds, lgbRounds = numRounds;
        int xgbDepth = maxDepth, lgbDepth = maxDepth;
        float xgbEta = eta, lgbEta = eta;
        float xgbSub = subsample, lgbSub = subsample;

        int nRealSamples = trainRows.size();

        if (earlyStop && nRealSamples >= 20) {
            updateStatus("Finding optimal round counts…", 0.05);
            out.accept("Early stopping: 80/20 stratified split on real data (patience=20)…");

            int patience = 20;
            int[] realIntLabels = new int[nRealSamples];
            for (int i = 0; i < nRealSamples; i++) realIntLabels[i] = trainLabels.get(i);

            // Split original (real) data 80/20
            int[][] split = stratifiedSplit(realIntLabels, nClasses, 0.8, new Random(42));

            // Extract the 80% training subset as lists for resampling
            List<float[]> esTrainRows = new ArrayList<>(split[0].length);
            List<Integer> esTrainLabelsList = new ArrayList<>(split[0].length);
            for (int idx : split[0]) {
                esTrainRows.add(trainRows.get(idx));
                esTrainLabelsList.add(trainLabels.get(idx));
            }

            // Resample only the 80% training portion
            if (strategy != ResamplingStrategy.NONE) {
                Resampler.Result esResampled = Resampler.apply(
                        esTrainRows, esTrainLabelsList, nClasses, strategy, out);
                esTrainRows = esResampled.rows();
                esTrainLabelsList = esResampled.labels();
                out.accept("Early stopping train set after resampling: " + esTrainRows.size()
                        + " (validation: " + split[1].length + " real samples)");
            }

            // Flatten resampled 80% train
            int esTrainSize = esTrainRows.size();
            float[] esTrainData = new float[esTrainSize * nFeatures];
            float[] esTrainLabels = new float[esTrainSize];
            for (int i = 0; i < esTrainSize; i++) {
                System.arraycopy(esTrainRows.get(i), 0, esTrainData, i * nFeatures, nFeatures);
                esTrainLabels[i] = esTrainLabelsList.get(i);
            }

            // Flatten real 20% validation (no resampling)
            float[] esValData = new float[split[1].length * nFeatures];
            float[] esValLabels = new float[split[1].length];
            for (int i = 0; i < split[1].length; i++) {
                System.arraycopy(trainRows.get(split[1][i]), 0,
                        esValData, i * nFeatures, nFeatures);
                esValLabels[i] = trainLabels.get(split[1][i]);
            }

            xgbRounds = XGBoostModel.findBestRounds(
                    esTrainData, esTrainLabels, esTrainSize,
                    esValData, esValLabels, split[1].length,
                    nFeatures, nClasses,
                    xgbRounds, xgbDepth, xgbEta, xgbSub, patience, out);

            lgbRounds = LightGBMModel.findBestRounds(
                    esTrainData, esTrainLabels, esTrainSize,
                    esValData, esValLabels, split[1].length,
                    nFeatures, nClasses,
                    lgbRounds, lgbDepth, lgbEta, lgbSub, patience, out);
        }

        // ── 1c. Resample full dataset if requested ──────────────────────────
        if (strategy != ResamplingStrategy.NONE) {
            Resampler.Result resampled = Resampler.apply(
                    trainRows, trainLabels, nClasses, strategy, out);
            trainRows = resampled.rows();
            trainLabels = resampled.labels();
            nSamples = trainRows.size();
        }

        // Flatten to arrays
        float[] flatData = new float[nSamples * nFeatures];
        float[] labelArray = new float[nSamples];
        for (int i = 0; i < nSamples; i++) {
            System.arraycopy(trainRows.get(i), 0, flatData, i * nFeatures, nFeatures);
            labelArray[i] = trainLabels.get(i);
        }

        // ── 1d. Auto-tune hyperparameters if requested ──────────────────────
        if (autoTune) {
            updateStatus("Auto-tuning hyperparameters…", earlyStop ? 0.10 : 0.05);
            out.accept("Auto-tuning hyperparameters (this may take several minutes)…");
            var tuneResult = HyperparameterTuner.tune(
                    flatData, labelArray, nSamples, nFeatures, nClasses,
                    HyperparameterTuner.DEFAULT_TRIALS,
                    HyperparameterTuner.DEFAULT_FOLDS, out);
            xgbRounds = tuneResult.xgbParams().numRounds();
            xgbDepth  = tuneResult.xgbParams().maxDepth();
            xgbEta    = tuneResult.xgbParams().eta();
            xgbSub    = tuneResult.xgbParams().subsample();
            lgbRounds = tuneResult.lgbParams().numRounds();
            lgbDepth  = tuneResult.lgbParams().maxDepth();
            lgbEta    = tuneResult.lgbParams().eta();
            lgbSub    = tuneResult.lgbParams().subsample();
        }

        // ── 2. Train XGBoost ────────────────────────────────────────────────
        updateStatus("Training XGBoost…", 0.15);
        out.accept("Training XGBoost (" + xgbRounds + " rounds)…");

        xgbModel.train(flatData, labelArray, nSamples, nFeatures,
                classNames, featureNames, xgbRounds, xgbDepth, xgbEta, xgbSub);
        out.accept("XGBoost trained on: " + xgbModel.getLastDevice());

        // ── 3. Train LightGBM ───────────────────────────────────────────────
        updateStatus("Training LightGBM…", 0.40);
        out.accept("Training LightGBM (" + lgbRounds + " rounds)…");

        lgbModel.train(flatData, labelArray, nSamples, nFeatures,
                classNames, featureNames, lgbRounds, lgbDepth, lgbEta, lgbSub);
        out.accept("LightGBM trained on: " + lgbModel.getLastDevice());

        // ── 4. Predict all cells ────────────────────────────────────────────
        updateStatus("Predicting all cells…", 0.65);
        int totalCells = allCells.size();
        out.accept("Predicting " + totalCells + " cells…");

        float[] allData = extractor.extractMatrix(allCells);

        float[][] xgbProbs = xgbModel.predictProba(allData, totalCells, nFeatures);
        float[][] lgbProbs = lgbModel.predictProba(allData, totalCells, nFeatures);

        // ── 5. Build population sets ────────────────────────────────────────
        updateStatus("Building population sets…", 0.85);
        out.accept("Building population sets…");

        predMDL1 = new PopulationSet("Pred_MDL1");
        predMDL2 = new PopulationSet("Pred_MDL2");
        predAVG  = new PopulationSet("Pred_AVG");
        predALL  = new PopulationSet("Pred_ALL");

        int idx = 0;
        int disagreements = 0;
        for (PathObject cell : allCells) {
            String cellId = cell.getID().toString();

            // Find best class for each model
            int xgbBest = argmax(xgbProbs[idx]);
            int lgbBest = argmax(lgbProbs[idx]);

            String xgbLabel = classNames.get(xgbBest);
            String lgbLabel = classNames.get(lgbBest);

            CellPrediction pred = new CellPrediction(
                    cellId, xgbLabel, lgbLabel,
                    xgbProbs[idx], lgbProbs[idx], classNames);

            predMDL1.put(cellId, pred);
            predMDL2.put(cellId, pred);
            predAVG.put(cellId, pred);
            predALL.put(cellId, pred);

            // Set the Pred_AVG classification on the PathObject
            String avgLabel = pred.avgLabel();
            cell.setPathClass(PathClass.fromString(avgLabel));

            if (pred.isDisagreement()) disagreements++;
            idx++;
        }

        out.accept("Predictions complete: " + totalCells + " cells, "
                + disagreements + " disagreements ("
                + String.format("%.1f%%", 100.0 * disagreements / totalCells) + ")");

        updateStatus("Training complete", 1.0);
        out.accept("Done.");
    }

    // ── Classifier state for persistence ────────────────────────────────────────

    /**
     * Apply predictions from the already-trained models to a collection of cells
     * without retraining. Used for classifying cells in other project images.
     *
     * @param cells     detection objects to classify
     * @param extractor feature extractor (must use the same feature columns as training)
     * @param log       optional progress callback
     * @throws Exception if prediction fails
     */
    public void predictOnly(Collection<PathObject> cells,
                            CellFeatureExtractor extractor,
                            Consumer<String> log) throws Exception {
        if (!isTrained()) {
            throw new IllegalStateException("Models must be trained before predicting.");
        }
        Consumer<String> out = log != null ? log : s -> {};

        int totalCells = cells.size();
        int nFeatures = extractor.getNumFeatures();

        out.accept("Predicting " + totalCells + " cells…");

        float[] allData = extractor.extractMatrix(cells);
        float[][] xgbProbs = xgbModel.predictProba(allData, totalCells, nFeatures);
        float[][] lgbProbs = lgbModel.predictProba(allData, totalCells, nFeatures);

        int idx = 0;
        int disagreements = 0;
        for (PathObject cell : cells) {
            int xgbBest = argmax(xgbProbs[idx]);
            int lgbBest = argmax(lgbProbs[idx]);

            String xgbLabel = classNames.get(xgbBest);
            String lgbLabel = classNames.get(lgbBest);

            CellPrediction pred = new CellPrediction(
                    cell.getID().toString(), xgbLabel, lgbLabel,
                    xgbProbs[idx], lgbProbs[idx], classNames);

            String avgLabel = pred.avgLabel();
            cell.setPathClass(PathClass.fromString(avgLabel));

            if (pred.isDisagreement()) disagreements++;
            idx++;
        }

        out.accept("Predictions applied: " + totalCells + " cells, "
                + disagreements + " disagreements ("
                + String.format("%.1f%%", 100.0 * disagreements / totalCells) + ")");
    }

    /**
     * Create a {@link ClassifierState} snapshot from the current trained models.
     *
     * @param name user-given classifier name
     * @return the classifier state
     * @throws Exception if model serialisation fails
     */
    public ClassifierState toClassifierState(String name) throws Exception {
        byte[] xgbBytes = xgbModel.isTrained() ? xgbModel.toBytes() : null;
        byte[] lgbBytes = lgbModel.isTrained() ? lgbModel.toBytes() : null;
        return new ClassifierState(name, featureNames, classNames, xgbBytes, lgbBytes);
    }

    /**
     * Restore models from a saved {@link ClassifierState}.
     *
     * @param state the saved state
     * @throws Exception if model loading fails
     */
    public void loadFromState(ClassifierState state) throws Exception {
        this.classNames = new ArrayList<>(state.getClassNames());
        this.featureNames = new ArrayList<>(state.getFeatureNames());

        byte[] xgbBytes = state.getXgboostBytes();
        if (xgbBytes != null) {
            xgbModel.loadFromBytes(xgbBytes, classNames, featureNames);
        }

        byte[] lgbBytes = state.getLightgbmBytes();
        if (lgbBytes != null) {
            lgbModel.loadFromBytes(lgbBytes, classNames, featureNames);
        }
    }

    // ── Hyperparameter setters ──────────────────────────────────────────────────

    public void setNumRounds(int numRounds)    { this.numRounds = numRounds; }
    public void setMaxDepth(int maxDepth)      { this.maxDepth = maxDepth; }
    public void setEta(float eta)              { this.eta = eta; }
    public void setSubsample(float subsample)  { this.subsample = subsample; }

    public int getNumRounds()    { return numRounds; }
    public int getMaxDepth()     { return maxDepth; }
    public float getEta()        { return eta; }
    public float getSubsample()  { return subsample; }

    // ── Population set accessors ────────────────────────────────────────────────

    public PopulationSet getPredMDL1() { return predMDL1; }
    public PopulationSet getPredMDL2() { return predMDL2; }
    public PopulationSet getPredAVG()  { return predAVG; }
    public PopulationSet getPredALL()  { return predALL; }

    public List<String> getClassNames()   { return classNames; }
    public List<String> getFeatureNames() { return featureNames; }

    public boolean isTrained() {
        return xgbModel.isTrained() && lgbModel.isTrained();
    }

    // ── Observable properties ───────────────────────────────────────────────────

    public DoubleProperty progressProperty() { return progress; }
    public StringProperty statusProperty()   { return status; }

    // ── Cleanup ─────────────────────────────────────────────────────────────────

    /** Release native resources held by the LightGBM booster. */
    public void close() {
        lgbModel.close();
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private void updateStatus(String msg, double prog) {
        Platform.runLater(() -> {
            status.set(msg);
            progress.set(prog);
        });
    }

    private static int argmax(float[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[best]) best = i;
        }
        return best;
    }

    // ── Early stopping helpers ─────────────────────────────────────────────

    private static int[][] stratifiedSplit(int[] labels, int nClasses,
                                           double trainRatio, Random rng) {
        List<List<Integer>> groups = new ArrayList<>();
        for (int c = 0; c < nClasses; c++) groups.add(new ArrayList<>());
        for (int i = 0; i < labels.length; i++) groups.get(labels[i]).add(i);

        List<Integer> trainList = new ArrayList<>();
        List<Integer> valList = new ArrayList<>();

        for (var group : groups) {
            Collections.shuffle(group, rng);
            int trainCount = Math.max(1, (int) (group.size() * trainRatio));
            trainList.addAll(group.subList(0, trainCount));
            if (trainCount < group.size()) {
                valList.addAll(group.subList(trainCount, group.size()));
            }
        }

        return new int[][] {
                trainList.stream().mapToInt(Integer::intValue).toArray(),
                valList.stream().mapToInt(Integer::intValue).toArray()
        };
    }

    private static float[] extractRowSubset(float[] flatData, int[] indices, int nFeatures) {
        float[] result = new float[indices.length * nFeatures];
        for (int i = 0; i < indices.length; i++) {
            System.arraycopy(flatData, indices[i] * nFeatures,
                    result, i * nFeatures, nFeatures);
        }
        return result;
    }

    private static float[] extractLabelSubset(float[] labels, int[] indices) {
        float[] result = new float[indices.length];
        for (int i = 0; i < indices.length; i++) result[i] = labels[indices[i]];
        return result;
    }
}
