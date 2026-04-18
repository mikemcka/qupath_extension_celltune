package qupath.ext.celltune.classifier;

import java.util.List;

/**
 * Immutable snapshot of a trained dual-model classifier's state.
 * <p>
 * Contains serialised model bytes for both XGBoost and LightGBM, plus the
 * metadata needed to reconstruct the models and verify feature compatibility.
 * Designed to be persisted via {@link qupath.ext.celltune.io.ProjectStateManager}.
 */
public class ClassifierState {

    private final String name;
    private final List<String> featureNames;
    private final List<String> classNames;
    private final byte[] xgboostBytes;
    private final byte[] lightgbmBytes;

    /**
     * @param name         user-given classifier name
     * @param featureNames ordered feature column names used during training
     * @param classNames   ordered class names used during training
     * @param xgboostBytes serialised XGBoost model (may be null if not yet trained)
     * @param lightgbmBytes serialised LightGBM model (may be null if not yet trained)
     */
    public ClassifierState(String name,
                           List<String> featureNames,
                           List<String> classNames,
                           byte[] xgboostBytes,
                           byte[] lightgbmBytes) {
        this.name = name;
        this.featureNames = List.copyOf(featureNames);
        this.classNames = List.copyOf(classNames);
        this.xgboostBytes = xgboostBytes != null ? xgboostBytes.clone() : null;
        this.lightgbmBytes = lightgbmBytes != null ? lightgbmBytes.clone() : null;
    }

    public String getName()              { return name; }
    public List<String> getFeatureNames() { return featureNames; }
    public List<String> getClassNames()  { return classNames; }
    public byte[] getXgboostBytes()      { return xgboostBytes != null ? xgboostBytes.clone() : null; }
    public byte[] getLightgbmBytes()     { return lightgbmBytes != null ? lightgbmBytes.clone() : null; }

    /** @return true if both models have been trained and serialised */
    public boolean isComplete() {
        return xgboostBytes != null && lightgbmBytes != null;
    }

    /**
     * Check whether a set of feature names is compatible with this classifier.
     * A mismatch means the model was trained on different features and should
     * not be used for prediction.
     *
     * @param currentFeatures the feature names from the current image
     * @return true if compatible
     */
    public boolean isFeatureCompatible(List<String> currentFeatures) {
        return featureNames.equals(currentFeatures);
    }

    @Override
    public String toString() {
        return "ClassifierState[" + name
                + ", " + featureNames.size() + " features"
                + ", " + classNames.size() + " classes"
                + ", xgb=" + (xgboostBytes != null ? xgboostBytes.length + "B" : "null")
                + ", lgbm=" + (lightgbmBytes != null ? lightgbmBytes.length + "B" : "null")
                + "]";
    }
}
