package qupath.ext.celltune.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.ModelType;
import qupath.ext.celltune.model.LabelStore;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Saves and loads the full classifier state to/from a JSON file in the QuPath project folder.
 * <p>
 * The state includes:
 * <ul>
 *   <li>Serialised model bytes (XGBoost + LightGBM) as Base64</li>
 *   <li>The current {@link LabelStore}</li>
 *   <li>Feature column names used during training</li>
 *   <li>Class name ordering</li>
 * </ul>
 * Files are stored under {@code <project>/celltune/} with a descriptive name.
 */
public class ProjectStateManager {

    private static final Logger logger = LoggerFactory.getLogger(ProjectStateManager.class);
    private static final String CELLTUNE_DIR = "celltune";
    private static final String STATE_FILENAME = "classifier-state.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ProjectStateManager() {} // utility class

    /**
     * Internal JSON-serialisable structure for the full classifier state.
     */
    public static class SavedState {
        public String name;
        public String timestamp;
        public List<String> featureNames;
        public List<String> classNames;
        public Map<String, String> labels; // cellId → class name
        public String xgboostModelBase64;  // null if not yet trained
        public String lightgbmModelBase64; // null if not yet trained
        public String rfModel1Base64;      // null if not yet trained
        public String rfModel2Base64;      // null if not yet trained
        public String model1Type;          // ModelType name, null defaults to XGBOOST
        public String model2Type;          // ModelType name, null defaults to LIGHTGBM
        // New fields for feature selection and normalization
        public List<String> selectedFeatures;
        public Map<String, String> featureTransforms; // feature name → transform name
        public Double arcsinhCofactor;
    }

    /**
     * Resolve the celltune directory inside the project folder, creating it if needed.
     *
     * @param project the QuPath project
     * @return path to the celltune subdirectory
     * @throws IOException if the directory cannot be created
     */
    public static Path getCellTuneDir(Project<?> project) throws IOException {
        Path projectDir = project.getPath().getParent();
        Path ctDir = projectDir.resolve(CELLTUNE_DIR);
        Files.createDirectories(ctDir);
        return ctDir;
    }

    /**
     * Save the classifier state to the project's celltune directory.
     *
     * @param project       the QuPath project
     * @param classifierName user-given name for this classifier
     * @param labelStore    current ground-truth labels
     * @param featureNames  feature column ordering used during training
     * @param classNames    class name ordering used during training
     * @param xgboostBytes  serialised XGBoost model bytes (may be null)
     * @param lightgbmBytes serialised LightGBM model bytes (may be null)
     * @return path to the saved JSON file
     * @throws IOException if writing fails
     */
    public static Path saveState(Project<?> project,
                                 String classifierName,
                                 LabelStore labelStore,
                                 List<String> featureNames,
                                 List<String> classNames,
                                 byte[] xgboostBytes,
                                 byte[] lightgbmBytes,
                                 byte[] rfModel1Bytes,
                                 byte[] rfModel2Bytes,
                                 ModelType model1Type,
                                 ModelType model2Type) throws IOException {
        Path dir = getCellTuneDir(project);

        SavedState state = new SavedState();
        state.name = classifierName;
        state.timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        state.featureNames = List.copyOf(featureNames);
        state.classNames = List.copyOf(classNames);
        state.labels = labelStore.getAllLabels();
        if (xgboostBytes != null) {
            state.xgboostModelBase64 = Base64.getEncoder().encodeToString(xgboostBytes);
        }
        if (lightgbmBytes != null) {
            state.lightgbmModelBase64 = Base64.getEncoder().encodeToString(lightgbmBytes);
        }
        if (rfModel1Bytes != null) {
            state.rfModel1Base64 = Base64.getEncoder().encodeToString(rfModel1Bytes);
        }
        if (rfModel2Bytes != null) {
            state.rfModel2Base64 = Base64.getEncoder().encodeToString(rfModel2Bytes);
        }
        state.model1Type = model1Type != null ? model1Type.name() : ModelType.XGBOOST.name();
        state.model2Type = model2Type != null ? model2Type.name() : ModelType.LIGHTGBM.name();
        // Save feature selection and normalization
        state.selectedFeatures = null; // to be set by caller
        state.featureTransforms = null; // to be set by caller
        state.arcsinhCofactor = null; // to be set by caller

        Path outPath = dir.resolve(STATE_FILENAME);
        String json = GSON.toJson(state);
        Files.writeString(outPath, json, StandardCharsets.UTF_8);
        logger.info("Saved classifier state to {} ({} labels, {} features)",
                outPath, labelStore.size(), featureNames.size());
        return outPath;
    }

    /**
     * Load a previously saved classifier state from the project's celltune directory.
     *
     * @param project the QuPath project
     * @return the loaded state, or null if no saved state exists
     * @throws IOException if reading or parsing fails
     */
    public static SavedState loadState(Project<?> project) throws IOException {
        Path dir = getCellTuneDir(project);
        Path statePath = dir.resolve(STATE_FILENAME);

        if (!Files.exists(statePath)) {
            logger.info("No saved classifier state found at {}", statePath);
            return null;
        }

        String json = Files.readString(statePath, StandardCharsets.UTF_8);
        SavedState state = GSON.fromJson(json, SavedState.class);
        logger.info("Loaded classifier state '{}' from {} ({} labels)",
                state.name, statePath,
                state.labels != null ? state.labels.size() : 0);
        return state;
    }

    /**
     * Reconstruct a {@link LabelStore} from a saved state.
     *
     * @param state the loaded state
     * @return a LabelStore populated with the saved labels
     */
    public static LabelStore toLabelStore(SavedState state) {
        String storeName = state.name != null ? state.name : "Loaded";
        if (state.labels == null || state.labels.isEmpty()) {
            return new LabelStore(storeName);
        }
        return new LabelStore(storeName, state.labels);
    }

    /**
     * Decode the XGBoost model bytes from a saved state.
     *
     * @param state the loaded state
     * @return the raw model bytes, or null if not present
     */
    public static byte[] decodeXGBoostModel(SavedState state) {
        if (state.xgboostModelBase64 == null) return null;
        return Base64.getDecoder().decode(state.xgboostModelBase64);
    }

    /**
     * Decode the LightGBM model bytes from a saved state.
     */
    public static byte[] decodeLightGBMModel(SavedState state) {
        if (state.lightgbmModelBase64 == null) return null;
        return Base64.getDecoder().decode(state.lightgbmModelBase64);
    }

    /**
     * Decode the Random Forest model 1 bytes from a saved state.
     */
    public static byte[] decodeRFModel1(SavedState state) {
        if (state.rfModel1Base64 == null) return null;
        return Base64.getDecoder().decode(state.rfModel1Base64);
    }

    /**
     * Decode the Random Forest model 2 bytes from a saved state.
     */
    public static byte[] decodeRFModel2(SavedState state) {
        if (state.rfModel2Base64 == null) return null;
        return Base64.getDecoder().decode(state.rfModel2Base64);
    }

    /**
     * Get the model 1 type from a saved state, defaulting to XGBOOST.
     */
    public static ModelType getModel1Type(SavedState state) {
        if (state.model1Type == null) return ModelType.XGBOOST;
        try { return ModelType.valueOf(state.model1Type); }
        catch (IllegalArgumentException e) { return ModelType.XGBOOST; }
    }

    /**
     * Get the model 2 type from a saved state, defaulting to LIGHTGBM.
     */
    public static ModelType getModel2Type(SavedState state) {
        if (state.model2Type == null) return ModelType.LIGHTGBM;
        try { return ModelType.valueOf(state.model2Type); }
        catch (IllegalArgumentException e) { return ModelType.LIGHTGBM; }
    }

    /**
     * Backward-compatible overload for XGBoost + LightGBM only.
     */
    public static Path saveState(Project<?> project,
                                 String classifierName,
                                 LabelStore labelStore,
                                 List<String> featureNames,
                                 List<String> classNames,
                                 byte[] xgboostBytes,
                                 byte[] lightgbmBytes) throws IOException {
        return saveState(project, classifierName, labelStore, featureNames, classNames,
                xgboostBytes, lightgbmBytes, null, null,
                ModelType.XGBOOST, ModelType.LIGHTGBM);
    }

    /**
     * Save a timestamped backup of the current labels.
     * Useful for auto-backup before each training cycle.
     *
     * @param project    the QuPath project
     * @param labelStore labels to back up
     * @return path to the backup file
     * @throws IOException if writing fails
     */
    public static Path backupLabels(Project<?> project, LabelStore labelStore) throws IOException {
        Path dir = getCellTuneDir(project);
        String filename = "labels_backup_" + LocalDateTime.now().format(TIMESTAMP_FMT) + ".json";
        Path outPath = dir.resolve(filename);

        String json = GSON.toJson(labelStore.getAllLabels());
        Files.writeString(outPath, json, StandardCharsets.UTF_8);
        logger.info("Backed up {} labels to {}", labelStore.size(), outPath);
        return outPath;
    }

    // ── Per-image label persistence ─────────────────────────────────────────────

    private static final String IMAGE_LABELS_DIR = "image-labels";

    /**
     * Save labels for a specific image. Used to persist review and manual labels
     * so they can be pooled into training from other images.
     *
     * @param project    the QuPath project
     * @param imageName  the image name (from ProjectImageEntry.getImageName())
     * @param labelStore the labels to save
     * @return path to the saved file
     * @throws IOException if writing fails
     */
    public static Path saveImageLabels(Project<?> project,
                                       String imageName,
                                       LabelStore labelStore) throws IOException {
        Path dir = getCellTuneDir(project).resolve(IMAGE_LABELS_DIR);
        Files.createDirectories(dir);

        String safeFileName = sanitiseFileName(imageName) + ".json";
        Path outPath = dir.resolve(safeFileName);

        String json = GSON.toJson(labelStore.getAllLabels());
        Files.writeString(outPath, json, StandardCharsets.UTF_8);
        logger.info("Saved {} labels for image '{}' to {}", labelStore.size(), imageName, outPath);
        return outPath;
    }

    /**
     * Load labels previously saved for a specific image.
     *
     * @param project   the QuPath project
     * @param imageName the image name (from ProjectImageEntry.getImageName())
     * @return a LabelStore with the saved labels, or null if none exist
     * @throws IOException if reading fails
     */
    public static LabelStore loadImageLabels(Project<?> project,
                                             String imageName) throws IOException {
        Path dir = getCellTuneDir(project).resolve(IMAGE_LABELS_DIR);
        String safeFileName = sanitiseFileName(imageName) + ".json";
        Path filePath = dir.resolve(safeFileName);

        if (!Files.exists(filePath)) {
            return null;
        }

        String json = Files.readString(filePath, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, String> labels = GSON.fromJson(json, Map.class);
        if (labels == null || labels.isEmpty()) {
            return null;
        }

        LabelStore store = new LabelStore(imageName, labels);
        logger.info("Loaded {} labels for image '{}' from {}", store.size(), imageName, filePath);
        return store;
    }

    /**
     * Replace characters that are unsafe in file names with underscores.
     */
    private static String sanitiseFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
