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
 * Orchestrates training of two tree-based models on the same labelled data,
 * then produces four population sets from predictions on all cells.
 * <p>
 * By default uses XGBoost + LightGBM, but the model types can be changed to
 * any pair from {@link ModelType} (e.g. XGBoost + Random Forest).
 * <p>
 * The four population sets mirror CellTune's design:
 * <ul>
 *   <li><b>Pred_MDL1</b> — Model 1 predictions only</li>
 *   <li><b>Pred_MDL2</b> — Model 2 predictions only</li>
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
    private int numRounds = 1000;
    private int maxDepth  = 6;
    private float eta     = 0.1f;
    private float subsample = 0.8f;

    /** Max cells per prediction chunk to stay within flat float[] int-index limit. */
    private static final int PREDICT_CHUNK_SIZE = 100_000;
    /** Max cells sampled for SHAP computation (TreeSHAP is O(n·depth·features)). */
    private static final int MAX_SHAP_SAMPLES = 5_000;

    /**
     * Per-class feature importance computed by {@link #computeFeatureImportance}.
     *
     * @param classNames   ordered class names
     * @param featureNames ordered feature names
     * @param meanAbsShap  mean |SHAP| values indexed as [nClasses][nFeatures]
     */
    public record ShapResult(
            List<String> classNames,
            List<String> featureNames,
            double[][] meanAbsShap) {}

    // ── Model type selection ────────────────────────────────────────────────────
    private ModelType model1Type = ModelType.XGBOOST;
    private ModelType model2Type = ModelType.LIGHTGBM;

    // ── Models (created lazily based on model type selection) ────────────────────
    private XGBoostModel xgbModel;
    private LightGBMModel lgbModel;
    private RandomForestModel rfModel1;
    private RandomForestModel rfModel2;

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

    // ── Training/validation metrics from 80/20 stratified split ─────────────────
    private TrainingMetrics model1TrainMetrics;
    private TrainingMetrics model1ValMetrics;
    private TrainingMetrics model2TrainMetrics;
    private TrainingMetrics model2ValMetrics;

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
        // LightGBM defaults to 0.05 lr (matching Python CellTune) unless overridden by tuning
        // Random Forest uses numRounds as nTrees and maxDepth=100 by default
        int mdl1Rounds = numRounds, mdl2Rounds = numRounds;
        int mdl1Depth = maxDepth, mdl2Depth = maxDepth;
        float mdl1Eta = eta, mdl2Eta = eta;
        float mdl1Sub = subsample, mdl2Sub = subsample;

        // Apply model-type-specific defaults
        if (model1Type == ModelType.LIGHTGBM) mdl1Eta = 0.05f;
        if (model2Type == ModelType.LIGHTGBM) mdl2Eta = 0.05f;
        if (model1Type == ModelType.RANDOM_FOREST) { mdl1Rounds = 100; mdl1Depth = 100; }
        if (model2Type == ModelType.RANDOM_FOREST) { mdl2Rounds = 100; mdl2Depth = 100; }

        int nRealSamples = trainRows.size();

        // Early stopping — only for boosted models (XGBoost, LightGBM)
        boolean mdl1Boosted = model1Type != ModelType.RANDOM_FOREST;
        boolean mdl2Boosted = model2Type != ModelType.RANDOM_FOREST;

        if (earlyStop && nRealSamples >= 20 && (mdl1Boosted || mdl2Boosted)) {
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

            if (mdl1Boosted && model1Type == ModelType.XGBOOST) {
                mdl1Rounds = XGBoostModel.findBestRounds(
                    esTrainData, esTrainLabels, esTrainSize,
                    esValData, esValLabels, split[1].length,
                    nFeatures, nClasses,
                    mdl1Rounds, mdl1Depth, mdl1Eta, mdl1Sub, patience, out);
            } else if (mdl1Boosted && model1Type == ModelType.LIGHTGBM) {
                mdl1Rounds = LightGBMModel.findBestRounds(
                    esTrainData, esTrainLabels, esTrainSize,
                    esValData, esValLabels, split[1].length,
                    nFeatures, nClasses,
                    mdl1Rounds, mdl1Depth, mdl1Eta, mdl1Sub, patience, out);
            }

            if (mdl2Boosted && model2Type == ModelType.XGBOOST) {
                mdl2Rounds = XGBoostModel.findBestRounds(
                    esTrainData, esTrainLabels, esTrainSize,
                    esValData, esValLabels, split[1].length,
                    nFeatures, nClasses,
                    mdl2Rounds, mdl2Depth, mdl2Eta, mdl2Sub, patience, out);
            } else if (mdl2Boosted && model2Type == ModelType.LIGHTGBM) {
                mdl2Rounds = LightGBMModel.findBestRounds(
                    esTrainData, esTrainLabels, esTrainSize,
                    esValData, esValLabels, split[1].length,
                    nFeatures, nClasses,
                    mdl2Rounds, mdl2Depth, mdl2Eta, mdl2Sub, patience, out);
            }
        }

        // ── 1c. Resample full dataset if requested ──────────────────────────
        // Keep references to the pre-resampling data so the train/val metrics
        // step below can do an honest 80/20 split on real (non-synthetic) samples.
        List<float[]> realTrainRows = trainRows;
        List<Integer> realTrainLabels = trainLabels;
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

        // ── 1d. Auto-tune hyperparameters if requested (boosted models only) ──
        if (autoTune && (mdl1Boosted || mdl2Boosted)) {
            updateStatus("Auto-tuning hyperparameters…", earlyStop ? 0.10 : 0.05);
            out.accept("Auto-tuning hyperparameters (this may take several minutes)…");
            var tuneResult = HyperparameterTuner.tune(
                    flatData, labelArray, nSamples, nFeatures, nClasses,
                    HyperparameterTuner.DEFAULT_TRIALS,
                    HyperparameterTuner.DEFAULT_FOLDS, out);
            if (mdl1Boosted && model1Type == ModelType.XGBOOST) {
                mdl1Rounds = tuneResult.xgbParams().numRounds();
                mdl1Depth  = tuneResult.xgbParams().maxDepth();
                mdl1Eta    = tuneResult.xgbParams().eta();
                mdl1Sub    = tuneResult.xgbParams().subsample();
            } else if (mdl1Boosted && model1Type == ModelType.LIGHTGBM) {
                mdl1Rounds = tuneResult.lgbParams().numRounds();
                mdl1Depth  = tuneResult.lgbParams().maxDepth();
                mdl1Eta    = tuneResult.lgbParams().eta();
                mdl1Sub    = tuneResult.lgbParams().subsample();
            }
            if (mdl2Boosted && model2Type == ModelType.XGBOOST) {
                mdl2Rounds = tuneResult.xgbParams().numRounds();
                mdl2Depth  = tuneResult.xgbParams().maxDepth();
                mdl2Eta    = tuneResult.xgbParams().eta();
                mdl2Sub    = tuneResult.xgbParams().subsample();
            } else if (mdl2Boosted && model2Type == ModelType.LIGHTGBM) {
                mdl2Rounds = tuneResult.lgbParams().numRounds();
                mdl2Depth  = tuneResult.lgbParams().maxDepth();
                mdl2Eta    = tuneResult.lgbParams().eta();
                mdl2Sub    = tuneResult.lgbParams().subsample();
            }
        }

        // ── 1e. Train/validation metrics (80/20 stratified split) ──────────
        // Always run when there are enough labelled samples so users get an
        // honest, sklearn-style classification report for both models. Trains
        // *evaluation copies* on the 80% only using the chosen final
        // hyperparameters/round counts — these get overwritten below when the
        // final models are retrained on the full dataset.
        if (nRealSamples >= 20) {
            updateStatus("Computing training/validation metrics\u2026",
                    earlyStop ? 0.12 : 0.08);
            out.accept("Computing training/validation metrics on 80/20 stratified split\u2026");
            try {
                computeTrainValMetrics(realTrainRows, realTrainLabels, nRealSamples,
                        nClasses, nFeatures, strategy,
                        mdl1Rounds, mdl1Depth, mdl1Eta, mdl1Sub,
                        mdl2Rounds, mdl2Depth, mdl2Eta, mdl2Sub,
                        out);
            } catch (Exception ex) {
                logger.warn("Failed to compute training/validation metrics", ex);
                out.accept("Note: train/val metrics computation failed: " + ex.getMessage());
            }
        } else {
            this.model1TrainMetrics = null;
            this.model1ValMetrics   = null;
            this.model2TrainMetrics = null;
            this.model2ValMetrics   = null;
            out.accept("Skipping train/val metrics (need \u2265 20 labelled samples).");
        }

        // ── 2. Train Model 1 ───────────────────────────────────────────────
        updateStatus("Training " + model1Type + "…", 0.15);
        out.accept("Training " + model1Type + " (" + mdl1Rounds
                + (model1Type == ModelType.RANDOM_FOREST ? " trees" : " rounds") + ")…");
        trainModel(model1Type, true, flatData, labelArray, nSamples, nFeatures,
                mdl1Rounds, mdl1Depth, mdl1Eta, mdl1Sub);
        out.accept(model1Type + " trained on: " + getModelDevice(model1Type, true));

        // ── 3. Train Model 2 ───────────────────────────────────────────────
        updateStatus("Training " + model2Type + "…", 0.40);
        out.accept("Training " + model2Type + " (" + mdl2Rounds
                + (model2Type == ModelType.RANDOM_FOREST ? " trees" : " rounds") + ")…");
        trainModel(model2Type, false, flatData, labelArray, nSamples, nFeatures,
                mdl2Rounds, mdl2Depth, mdl2Eta, mdl2Sub);
        out.accept(model2Type + " trained on: " + getModelDevice(model2Type, false));

        // ── 4. Predict all cells (chunked for large datasets) ────────────
        updateStatus("Predicting all cells…", 0.65);
        int totalCells = allCells.size();
        out.accept("Predicting " + totalCells + " cells…");

        predMDL1 = new PopulationSet("Pred_MDL1");
        predMDL2 = new PopulationSet("Pred_MDL2");
        predAVG  = new PopulationSet("Pred_AVG");
        predALL  = new PopulationSet("Pred_ALL");

        // Collect PathClass assignments for batch application on the FX thread.
        // PathObject.setPathClass() may use a JavaFX property in future QuPath
        // versions; always apply on the FX thread to be forward-compatible.
        List<PathObject> classifyObjects = new ArrayList<>(totalCells);
        List<PathClass>  classifyClasses = new ArrayList<>(totalCells);

        int disagreements = 0;
        List<PathObject> cellList = (allCells instanceof List)
                ? (List<PathObject>) allCells
                : new ArrayList<>(allCells);

        for (int chunkStart = 0; chunkStart < totalCells; chunkStart += PREDICT_CHUNK_SIZE) {
            int chunkEnd = Math.min(chunkStart + PREDICT_CHUNK_SIZE, totalCells);
            int chunkSize = chunkEnd - chunkStart;
            List<PathObject> chunk = cellList.subList(chunkStart, chunkEnd);

            float[] chunkData = extractor.extractMatrix(chunk);
            float[][] mdl1Probs = predictModel(model1Type, true, chunkData, chunkSize, nFeatures);
            float[][] mdl2Probs = predictModel(model2Type, false, chunkData, chunkSize, nFeatures);

            for (int i = 0; i < chunkSize; i++) {
                PathObject cell = chunk.get(i);
                String cellId = cell.getID().toString();

                int mdl1Best = argmax(mdl1Probs[i]);
                int mdl2Best = argmax(mdl2Probs[i]);

                String mdl1Label = classNames.get(mdl1Best);
                String mdl2Label = classNames.get(mdl2Best);

                CellPrediction pred = new CellPrediction(
                        cellId, mdl1Label, mdl2Label,
                        mdl1Probs[i], mdl2Probs[i], classNames);

                predMDL1.put(cellId, pred);
                predMDL2.put(cellId, pred);
                predAVG.put(cellId, pred);
                predALL.put(cellId, pred);

                classifyObjects.add(cell);
                classifyClasses.add(PathClass.fromString(pred.avgLabel()));

                if (pred.isDisagreement()) disagreements++;
            }

            double progress = 0.65 + 0.20 * ((double) chunkEnd / totalCells);
            updateStatus("Predicting… " + chunkEnd + "/" + totalCells, progress);
        }

        // Apply PathClass assignments on the FX thread (queued before the hierarchy
        // event that CellTuneExtension fires in its own Platform.runLater block).
        Platform.runLater(() -> {
            for (int i = 0; i < classifyObjects.size(); i++) {
                classifyObjects.get(i).setPathClass(classifyClasses.get(i));
            }
        });

        // ── 5. Summary ─────────────────────────────────────────────────────

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
     * <p>
     * If {@code populateSets} is true, the internal PopulationSets
     * (predALL, predMDL1, predMDL2, predAVG) are rebuilt so that
     * disagreement counts, confusion matrices and review mode work
     * on the predicted image.
     *
     * @param cells         detection objects to classify
     * @param extractor     feature extractor (must use the same feature columns as training)
     * @param populateSets  whether to rebuild the internal PopulationSets
     * @param log           optional progress callback
     * @throws Exception if prediction fails
     */
    public void predictOnly(Collection<PathObject> cells,
                            CellFeatureExtractor extractor,
                            boolean populateSets,
                            Consumer<String> log) throws Exception {
        if (!isTrained()) {
            throw new IllegalStateException("Models must be trained before predicting.");
        }
        Consumer<String> out = log != null ? log : s -> {};

        int totalCells = cells.size();
        int nFeatures = extractor.getNumFeatures();

        // Guard against mis-matched feature sets (e.g. loading a model from a different session)
        if (featureNames != null && featureNames.size() != nFeatures) {
            throw new IllegalStateException(
                    "Feature count mismatch: extractor has " + nFeatures
                    + " feature(s) but this classifier was trained with "
                    + featureNames.size() + ". Re-select features and retrain.");
        }

        out.accept("Predicting " + totalCells + " cells…");

        List<PathObject> cellList = (cells instanceof List)
                ? (List<PathObject>) cells
                : new ArrayList<>(cells);

        PopulationSet localMDL1 = populateSets ? new PopulationSet("Pred_MDL1") : null;
        PopulationSet localMDL2 = populateSets ? new PopulationSet("Pred_MDL2") : null;
        PopulationSet localAVG  = populateSets ? new PopulationSet("Pred_AVG")  : null;
        PopulationSet localALL  = populateSets ? new PopulationSet("Pred_ALL")  : null;

        List<PathObject> classifyObjects = new ArrayList<>(totalCells);
        List<PathClass>  classifyClasses = new ArrayList<>(totalCells);

        int disagreements = 0;
        for (int chunkStart = 0; chunkStart < totalCells; chunkStart += PREDICT_CHUNK_SIZE) {
            int chunkEnd = Math.min(chunkStart + PREDICT_CHUNK_SIZE, totalCells);
            int chunkSize = chunkEnd - chunkStart;
            List<PathObject> chunk = cellList.subList(chunkStart, chunkEnd);

            float[] chunkData = extractor.extractMatrix(chunk);
            float[][] mdl1Probs = predictModel(model1Type, true, chunkData, chunkSize, nFeatures);
            float[][] mdl2Probs = predictModel(model2Type, false, chunkData, chunkSize, nFeatures);

            for (int i = 0; i < chunkSize; i++) {
                PathObject cell = chunk.get(i);
                String cellId = cell.getID().toString();
                int mdl1Best = argmax(mdl1Probs[i]);
                int mdl2Best = argmax(mdl2Probs[i]);

                String mdl1Label = classNames.get(mdl1Best);
                String mdl2Label = classNames.get(mdl2Best);

                CellPrediction pred = new CellPrediction(
                        cellId, mdl1Label, mdl2Label,
                        mdl1Probs[i], mdl2Probs[i], classNames);

                classifyObjects.add(cell);
                classifyClasses.add(PathClass.fromString(pred.avgLabel()));

                if (populateSets) {
                    localMDL1.put(cellId, pred);
                    localMDL2.put(cellId, pred);
                    localAVG.put(cellId, pred);
                    localALL.put(cellId, pred);
                }

                if (pred.isDisagreement()) disagreements++;
            }

            out.accept("Predicted " + chunkEnd + "/" + totalCells + " cells…");
        }

        // Apply PathClass assignments on the FX thread and wait for completion
        // so callers can safely persist immediately after predictOnly returns.
        if (Platform.isFxApplicationThread()) {
            for (int i = 0; i < classifyObjects.size(); i++) {
                classifyObjects.get(i).setPathClass(classifyClasses.get(i));
            }
        } else {
            var done = new java.util.concurrent.CountDownLatch(1);
            final RuntimeException[] fxError = new RuntimeException[1];
            Platform.runLater(() -> {
                try {
                    for (int i = 0; i < classifyObjects.size(); i++) {
                        classifyObjects.get(i).setPathClass(classifyClasses.get(i));
                    }
                } catch (RuntimeException ex) {
                    fxError[0] = ex;
                } finally {
                    done.countDown();
                }
            });

            try {
                done.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while applying predictions.", ie);
            }

            if (fxError[0] != null) {
                throw fxError[0];
            }
        }

        if (populateSets) {
            this.predMDL1 = localMDL1;
            this.predMDL2 = localMDL2;
            this.predAVG  = localAVG;
            this.predALL  = localALL;
        }

        out.accept("Predictions applied: " + totalCells + " cells, "
                + disagreements + " disagreements ("
                + String.format("%.1f%%", 100.0 * disagreements / totalCells) + ")");
    }

    /**
     * Convenience overload — does not populate internal PopulationSets.
     */
    public void predictOnly(Collection<PathObject> cells,
                            CellFeatureExtractor extractor,
                            Consumer<String> log) throws Exception {
        predictOnly(cells, extractor, false, log);
    }

    /**
     * Apply predictions to a collection of cells and return a freshly built
     * {@code Pred_ALL} {@link PopulationSet} for those cells, without mutating
     * any shared classifier state. This is the thread-safe variant used by the
     * parallel batch-apply loops, so each worker can persist its own
     * {@code PopulationSet} via {@code ProjectStateManager.saveImagePredictions}.
     *
     * @param cells     detection objects to classify
     * @param extractor feature extractor (must use the same feature columns as training)
     * @param log       optional progress callback
     * @return a populated {@link PopulationSet} keyed by cell ID
     * @throws Exception if prediction fails
     */
    public PopulationSet predictAndCollect(Collection<PathObject> cells,
                                           CellFeatureExtractor extractor,
                                           Consumer<String> log) throws Exception {
        if (!isTrained()) {
            throw new IllegalStateException("Models must be trained before predicting.");
        }
        Consumer<String> out = log != null ? log : s -> {};

        int totalCells = cells.size();
        int nFeatures = extractor.getNumFeatures();

        if (featureNames != null && featureNames.size() != nFeatures) {
            throw new IllegalStateException(
                    "Feature count mismatch: extractor has " + nFeatures
                    + " feature(s) but this classifier was trained with "
                    + featureNames.size() + ". Re-select features and retrain.");
        }

        out.accept("Predicting " + totalCells + " cells…");

        List<PathObject> cellList = (cells instanceof List)
                ? (List<PathObject>) cells
                : new ArrayList<>(cells);

        PopulationSet localALL = new PopulationSet("Pred_ALL");
        List<PathObject> classifyObjects = new ArrayList<>(totalCells);
        List<PathClass>  classifyClasses = new ArrayList<>(totalCells);

        int disagreements = 0;
        for (int chunkStart = 0; chunkStart < totalCells; chunkStart += PREDICT_CHUNK_SIZE) {
            int chunkEnd = Math.min(chunkStart + PREDICT_CHUNK_SIZE, totalCells);
            int chunkSize = chunkEnd - chunkStart;
            List<PathObject> chunk = cellList.subList(chunkStart, chunkEnd);

            float[] chunkData = extractor.extractMatrix(chunk);
            float[][] mdl1Probs = predictModel(model1Type, true, chunkData, chunkSize, nFeatures);
            float[][] mdl2Probs = predictModel(model2Type, false, chunkData, chunkSize, nFeatures);

            for (int i = 0; i < chunkSize; i++) {
                PathObject cell = chunk.get(i);
                String cellId = cell.getID().toString();
                int mdl1Best = argmax(mdl1Probs[i]);
                int mdl2Best = argmax(mdl2Probs[i]);

                String mdl1Label = classNames.get(mdl1Best);
                String mdl2Label = classNames.get(mdl2Best);

                CellPrediction pred = new CellPrediction(
                        cellId, mdl1Label, mdl2Label,
                        mdl1Probs[i], mdl2Probs[i], classNames);

                classifyObjects.add(cell);
                classifyClasses.add(PathClass.fromString(pred.avgLabel()));

                localALL.put(cellId, pred);
                if (pred.isDisagreement()) disagreements++;
            }

            out.accept("Predicted " + chunkEnd + "/" + totalCells + " cells…");
        }

        // Apply PathClass assignments on the FX thread and wait for completion
        // so callers can safely persist immediately after this returns.
        if (Platform.isFxApplicationThread()) {
            for (int i = 0; i < classifyObjects.size(); i++) {
                classifyObjects.get(i).setPathClass(classifyClasses.get(i));
            }
        } else {
            var done = new java.util.concurrent.CountDownLatch(1);
            final RuntimeException[] fxError = new RuntimeException[1];
            Platform.runLater(() -> {
                try {
                    for (int i = 0; i < classifyObjects.size(); i++) {
                        classifyObjects.get(i).setPathClass(classifyClasses.get(i));
                    }
                } catch (RuntimeException ex) {
                    fxError[0] = ex;
                } finally {
                    done.countDown();
                }
            });
            try {
                done.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while applying predictions.", ie);
            }
            if (fxError[0] != null) {
                throw fxError[0];
            }
        }

        out.accept("Predictions applied: " + totalCells + " cells, "
                + disagreements + " disagreements ("
                + String.format("%.1f%%", 100.0 * disagreements / totalCells) + ")");

        return localALL;
    }

    /**
     * Create a {@link ClassifierState} snapshot from the current trained models.
     *
     * @param name user-given classifier name
     * @return the classifier state
     * @throws Exception if model serialisation fails
     */
    public ClassifierState toClassifierState(String name) throws Exception {
        byte[] xgbBytes = xgbModel != null && xgbModel.isTrained() ? xgbModel.toBytes() : null;
        byte[] lgbBytes = lgbModel != null && lgbModel.isTrained() ? lgbModel.toBytes() : null;
        byte[] rf1Bytes = rfModel1 != null && rfModel1.isTrained() ? rfModel1.toBytes() : null;
        byte[] rf2Bytes = rfModel2 != null && rfModel2.isTrained() ? rfModel2.toBytes() : null;
        return new ClassifierState(name, featureNames, classNames,
                xgbBytes, lgbBytes, rf1Bytes, rf2Bytes, model1Type, model2Type);
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
        this.model1Type = state.getModel1Type();
        this.model2Type = state.getModel2Type();

        byte[] xgbBytes = state.getXgboostBytes();
        if (xgbBytes != null) {
            xgbModel = new XGBoostModel();
            xgbModel.loadFromBytes(xgbBytes, classNames, featureNames);
        }

        byte[] lgbBytes = state.getLightgbmBytes();
        if (lgbBytes != null) {
            lgbModel = new LightGBMModel();
            lgbModel.loadFromBytes(lgbBytes, classNames, featureNames);
        }

        byte[] rf1Bytes = state.getRfModel1Bytes();
        if (rf1Bytes != null) {
            rfModel1 = new RandomForestModel();
            rfModel1.loadFromBytes(rf1Bytes, classNames, featureNames);
        }

        byte[] rf2Bytes = state.getRfModel2Bytes();
        if (rf2Bytes != null) {
            rfModel2 = new RandomForestModel();
            rfModel2.loadFromBytes(rf2Bytes, classNames, featureNames);
        }
    }

    // ── SHAP Feature Importance ─────────────────────────────────────────────────

    /**
     * Compute per-class mean absolute SHAP feature importances using the active
     * trained models.
     * <p>
     * XGBoost and LightGBM use their native TreeSHAP implementations.
     * Random Forest uses normalised split counts (class-agnostic; same values
     * shown for every class).
     * Results from multiple active models are averaged.
     * <p>
     * A random sample of up to {@value MAX_SHAP_SAMPLES} cells is used for
     * performance.
     *
     * @param cells     cells to compute importances over
     * @param extractor feature extractor initialised with training feature names
     * @return per-class mean |SHAP| values
     * @throws Exception if SHAP computation fails
     */
    public ShapResult computeFeatureImportance(Collection<PathObject> cells,
                                               CellFeatureExtractor extractor)
            throws Exception {
        if (!isTrained()) {
            throw new IllegalStateException(
                    "Models must be trained before computing feature importance.");
        }

        int nFeatures = extractor.getNumFeatures();
        int nClasses  = classNames.size();

        // Sample cells for performance
        List<PathObject> sample = new ArrayList<>(cells);
        if (sample.size() > MAX_SHAP_SAMPLES) {
            Collections.shuffle(sample, new Random(42));
            sample = sample.subList(0, MAX_SHAP_SAMPLES);
        }
        int nSamples = sample.size();
        float[] flatData = extractor.extractMatrix(sample);

        double[][] result = new double[nClasses][nFeatures];
        int modelCount = 0;

        // ── XGBoost TreeSHAP ────────────────────────────────────────────────
        if (xgbModel != null && xgbModel.isTrained()
                && (model1Type == ModelType.XGBOOST || model2Type == ModelType.XGBOOST)) {
            double[][] shap = xgbModel.computeMeanAbsShap(flatData, nSamples, nFeatures);
            for (int c = 0; c < nClasses; c++)
                for (int f = 0; f < nFeatures; f++)
                    result[c][f] += shap[c][f];
            modelCount++;
        }

        // ── Random Forest split importance ──────────────────────────────────
        if (model1Type == ModelType.RANDOM_FOREST
                && rfModel1 != null && rfModel1.isTrained()) {
            double[][] imp = rfModel1.computeSplitImportance();
            for (int c = 0; c < nClasses; c++)
                for (int f = 0; f < nFeatures; f++)
                    result[c][f] += imp[c][f];
            modelCount++;
        }
        // RF model 2 only if it is a distinct model slot
        if (model2Type == ModelType.RANDOM_FOREST
                && rfModel2 != null && rfModel2.isTrained()
                && model1Type != ModelType.RANDOM_FOREST) {
            double[][] imp = rfModel2.computeSplitImportance();
            for (int c = 0; c < nClasses; c++)
                for (int f = 0; f < nFeatures; f++)
                    result[c][f] += imp[c][f];
            modelCount++;
        }

        if (modelCount > 1) {
            for (int c = 0; c < nClasses; c++)
                for (int f = 0; f < nFeatures; f++)
                    result[c][f] /= modelCount;
        }

        return new ShapResult(classNames, featureNames, result);
    }

    // ── Hyperparameter setters ──────────────────────────────────────────────────

    public void setNumRounds(int numRounds)    { this.numRounds = numRounds; }
    public void setMaxDepth(int maxDepth)      { this.maxDepth = maxDepth; }
    public void setEta(float eta)              { this.eta = eta; }
    public void setSubsample(float subsample)  { this.subsample = subsample; }
    public void setModel1Type(ModelType type)  { this.model1Type = type; }
    public void setModel2Type(ModelType type)  { this.model2Type = type; }

    public int getNumRounds()    { return numRounds; }
    public int getMaxDepth()     { return maxDepth; }
    public float getEta()        { return eta; }
    public float getSubsample()  { return subsample; }
    public ModelType getModel1Type() { return model1Type; }
    public ModelType getModel2Type() { return model2Type; }

    // ── Population set accessors ────────────────────────────────────────────────

    public PopulationSet getPredMDL1() { return predMDL1; }
    public PopulationSet getPredMDL2() { return predMDL2; }
    public PopulationSet getPredAVG()  { return predAVG; }
    public PopulationSet getPredALL()  { return predALL; }

    public List<String> getClassNames()   { return classNames; }
    public List<String> getFeatureNames() { return featureNames; }

    public boolean isTrained() {
        return isModelTrained(model1Type, true) && isModelTrained(model2Type, false);
    }

    // ── Observable properties ───────────────────────────────────────────────────

    public DoubleProperty progressProperty() { return progress; }
    public StringProperty statusProperty()   { return status; }

    /** Synchronously reset progress and status to their initial state. */
    public void resetProgress() {
        progress.set(0);
        status.set("");
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────────

    /** Release native resources held by model boosters. */
    public void close() {
        if (lgbModel != null) lgbModel.close();
        if (rfModel1 != null) rfModel1.close();
        if (rfModel2 != null) rfModel2.close();
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

    // ── Model dispatch helpers ──────────────────────────────────────────────────

    private void trainModel(ModelType type, boolean isModel1,
                            float[] flatData, float[] labels,
                            int nSamples, int nFeatures,
                            int rounds, int depth, float lr, float sub) throws Exception {
        switch (type) {
            case XGBOOST -> {
                if (xgbModel == null) xgbModel = new XGBoostModel();
                xgbModel.train(flatData, labels, nSamples, nFeatures,
                        classNames, featureNames, rounds, depth, lr, sub);
            }
            case LIGHTGBM -> {
                if (lgbModel == null) lgbModel = new LightGBMModel();
                lgbModel.train(flatData, labels, nSamples, nFeatures,
                        classNames, featureNames, rounds, depth, lr, sub);
            }
            case RANDOM_FOREST -> {
                var rf = new RandomForestModel();
                rf.train(flatData, labels, nSamples, nFeatures,
                        classNames, featureNames, rounds, depth, lr, sub);
                if (isModel1) rfModel1 = rf; else rfModel2 = rf;
            }
        }
    }

    private float[][] predictModel(ModelType type, boolean isModel1,
                                   float[] flatData, int nSamples, int nFeatures)
            throws Exception {
        return switch (type) {
            case XGBOOST -> xgbModel.predictProba(flatData, nSamples, nFeatures);
            case LIGHTGBM -> lgbModel.predictProba(flatData, nSamples, nFeatures);
            case RANDOM_FOREST -> (isModel1 ? rfModel1 : rfModel2)
                    .predictProba(flatData, nSamples, nFeatures);
        };
    }

    private boolean isModelTrained(ModelType type, boolean isModel1) {
        return switch (type) {
            case XGBOOST -> xgbModel != null && xgbModel.isTrained();
            case LIGHTGBM -> lgbModel != null && lgbModel.isTrained();
            case RANDOM_FOREST -> {
                var rf = isModel1 ? rfModel1 : rfModel2;
                yield rf != null && rf.isTrained();
            }
        };
    }

    private String getModelDevice(ModelType type, boolean isModel1) {
        return switch (type) {
            case XGBOOST -> xgbModel != null ? xgbModel.getLastDevice() : "unknown";
            case LIGHTGBM -> lgbModel != null ? lgbModel.getLastDevice() : "unknown";
            case RANDOM_FOREST -> {
                var rf = isModel1 ? rfModel1 : rfModel2;
                yield rf != null ? rf.getLastDevice() : "unknown";
            }
        };
    }

    // ── Train/validation metrics ────────────────────────────────────────────────

    /** @return per-class precision/recall/F1 for Model 1 on the 80% train portion (may be null). */
    public TrainingMetrics getModel1TrainMetrics() { return model1TrainMetrics; }
    /** @return per-class precision/recall/F1 for Model 1 on the 20% held-out validation portion (may be null). */
    public TrainingMetrics getModel1ValMetrics()   { return model1ValMetrics; }
    /** @return per-class precision/recall/F1 for Model 2 on the 80% train portion (may be null). */
    public TrainingMetrics getModel2TrainMetrics() { return model2TrainMetrics; }
    /** @return per-class precision/recall/F1 for Model 2 on the 20% held-out validation portion (may be null). */
    public TrainingMetrics getModel2ValMetrics()   { return model2ValMetrics; }

    /** @return true if any train/val metrics are available. */
    public boolean hasTrainValMetrics() {
        return model1TrainMetrics != null || model1ValMetrics != null
                || model2TrainMetrics != null || model2ValMetrics != null;
    }

    /**
     * Compute per-class train and validation metrics for both models on a fresh
     * 80/20 stratified split of the real (pre-resampling) labelled data.
     * <p>Trains evaluation copies on the 80% only using the chosen final
     * hyperparameters; these copies overwrite any prior model state and are
     * themselves overwritten by the final full-data training step that runs
     * immediately after.
     */
    private void computeTrainValMetrics(List<float[]> realRows,
                                        List<Integer> realLabels,
                                        int nRealSamples,
                                        int nClasses,
                                        int nFeatures,
                                        ResamplingStrategy strategy,
                                        int mdl1Rounds, int mdl1Depth, float mdl1Eta, float mdl1Sub,
                                        int mdl2Rounds, int mdl2Depth, float mdl2Eta, float mdl2Sub,
                                        Consumer<String> out) throws Exception {
        int[] realIntLabels = new int[nRealSamples];
        for (int i = 0; i < nRealSamples; i++) realIntLabels[i] = realLabels.get(i);

        int[][] split = stratifiedSplit(realIntLabels, nClasses, 0.8, new Random(42));
        int valSize = split[1].length;
        if (valSize == 0 || split[0].length == 0) {
            out.accept("Skipping metrics: stratified split produced empty fold.");
            return;
        }

        // Build 80% train (eligible for resampling) and 20% val (real only).
        List<float[]> evTrainRows = new ArrayList<>(split[0].length);
        List<Integer> evTrainLabelsList = new ArrayList<>(split[0].length);
        for (int idx : split[0]) {
            evTrainRows.add(realRows.get(idx));
            evTrainLabelsList.add(realLabels.get(idx));
        }
        if (strategy != ResamplingStrategy.NONE) {
            Resampler.Result res = Resampler.apply(
                    evTrainRows, evTrainLabelsList, nClasses, strategy, s -> {});
            evTrainRows = res.rows();
            evTrainLabelsList = res.labels();
        }

        int evTrainSize = evTrainRows.size();
        float[] evTrainData = new float[evTrainSize * nFeatures];
        float[] evTrainLabels = new float[evTrainSize];
        for (int i = 0; i < evTrainSize; i++) {
            System.arraycopy(evTrainRows.get(i), 0, evTrainData, i * nFeatures, nFeatures);
            evTrainLabels[i] = evTrainLabelsList.get(i);
        }
        float[] evValData = new float[valSize * nFeatures];
        float[] evValLabels = new float[valSize];
        for (int i = 0; i < valSize; i++) {
            System.arraycopy(realRows.get(split[1][i]), 0, evValData, i * nFeatures, nFeatures);
            evValLabels[i] = realLabels.get(split[1][i]);
        }

        // ── Eval Model 1 ────────────────────────────────────────────────────
        trainModel(model1Type, true, evTrainData, evTrainLabels, evTrainSize, nFeatures,
                mdl1Rounds, mdl1Depth, mdl1Eta, mdl1Sub);
        float[][] m1TrainProba = predictModel(model1Type, true, evTrainData, evTrainSize, nFeatures);
        float[][] m1ValProba   = predictModel(model1Type, true, evValData, valSize, nFeatures);
        this.model1TrainMetrics = TrainingMetrics.compute(
                "Model 1 (" + model1Type + ") \u2014 Train (80%)",
                classNames, evTrainLabels, m1TrainProba);
        this.model1ValMetrics = TrainingMetrics.compute(
                "Model 1 (" + model1Type + ") \u2014 Validation (20%)",
                classNames, evValLabels, m1ValProba);

        // ── Eval Model 2 ────────────────────────────────────────────────────
        trainModel(model2Type, false, evTrainData, evTrainLabels, evTrainSize, nFeatures,
                mdl2Rounds, mdl2Depth, mdl2Eta, mdl2Sub);
        float[][] m2TrainProba = predictModel(model2Type, false, evTrainData, evTrainSize, nFeatures);
        float[][] m2ValProba   = predictModel(model2Type, false, evValData, valSize, nFeatures);
        this.model2TrainMetrics = TrainingMetrics.compute(
                "Model 2 (" + model2Type + ") \u2014 Train (80%)",
                classNames, evTrainLabels, m2TrainProba);
        this.model2ValMetrics = TrainingMetrics.compute(
                "Model 2 (" + model2Type + ") \u2014 Validation (20%)",
                classNames, evValLabels, m2ValProba);

        out.accept(String.format(
                "Macro F1: M1 train=%.3f val=%.3f | M2 train=%.3f val=%.3f",
                model1TrainMetrics.macroF1(), model1ValMetrics.macroF1(),
                model2TrainMetrics.macroF1(), model2ValMetrics.macroF1()));
    }
}
