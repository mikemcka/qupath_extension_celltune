package qupath.ext.celltune.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.classifier.CompositeClassificationRule;
import qupath.ext.celltune.classifier.ModelType;
import qupath.ext.celltune.classifier.TrainingMetrics;
import qupath.ext.celltune.io.GroundTruthIO;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.CellTypeTable;
import qupath.ext.celltune.model.LabelStore;
import qupath.ext.celltune.model.PopulationSet;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final String COMPOSITE_RULES_FILENAME = "composite-rules.json";
    private static final int COMPOSITE_RULES_SCHEMA_VERSION = 1;
    private static final String MARKER_TABLE_FILENAME = "marker-table.json";
    private static final int MARKER_TABLE_SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ProjectStateManager() {} // utility class

    /**
     * Internal JSON-serialisable structure for the full classifier state.
     */
    public static class SavedState {
        public static class SavedTrainingRow {
            public String label;
            public float[] features;
        }

        public String name;
        public String timestamp;
        public List<String> featureNames;
        public List<String> classNames;
        public Map<String, String> labels; // cellId â†’ class name
        public String xgboostModelBase64;  // null if not yet trained
        public String lightgbmModelBase64; // null if not yet trained
        public String rfModel1Base64;      // null if not yet trained
        public String rfModel2Base64;      // null if not yet trained
        public String model1Type;          // ModelType name, null defaults to XGBOOST
        public String model2Type;          // ModelType name, null defaults to LIGHTGBM
        // New fields for feature selection and normalization
        public List<String> selectedFeatures;
        public Map<String, String> featureTransforms; // feature name â†’ transform name
        public Double arcsinhCofactor;
        public List<String> importedTrainingFeatureNames;
        public List<SavedTrainingRow> importedTrainingRows;
        // Persisted per-model training/validation metrics (nullable until first training)
        public TrainingMetrics model1TrainMetrics;
        public TrainingMetrics model1ValMetrics;
        public TrainingMetrics model2TrainMetrics;
        public TrainingMetrics model2ValMetrics;
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
     * Resolve the celltune directory <em>without</em> creating it.
     *
     * @param project the QuPath project
     * @return the path to {@code <project>/celltune}, or {@code null} if the
     *         project has no path on disk
     */
    public static Path cellTuneDirPath(Project<?> project) {
        if (project == null || project.getPath() == null) {
            return null;
        }
        return project.getPath().getParent().resolve(CELLTUNE_DIR);
    }

    /** @return true if the project has a {@code celltune/} state directory on disk. */
    public static boolean hasProjectState(Project<?> project) {
        Path dir = cellTuneDirPath(project);
        return dir != null && Files.isDirectory(dir);
    }

    /**
     * Zip the entire {@code celltune/} state directory to a timestamped archive
     * ({@code celltune_backup_<timestamp>.zip}) beside it in the project folder,
     * as a safety net before a destructive reset.
     *
     * @param project the QuPath project
     * @return the path to the written archive, or {@code null} if there was no
     *         state directory (or it was empty)
     * @throws IOException if writing the archive fails
     */
    public static Path backupProjectState(Project<?> project) throws IOException {
        Path ctDir = cellTuneDirPath(project);
        if (ctDir == null || !Files.isDirectory(ctDir)) {
            return null;
        }
        Path zipTarget = ctDir.getParent().resolve(
                "celltune_backup_" + LocalDateTime.now().format(TIMESTAMP_FMT) + ".zip");
        Path written = zipDirectory(ctDir, zipTarget);
        if (written != null) {
            logger.info("Backed up CellTune state to {}", written);
        }
        return written;
    }

    /**
     * Delete the entire {@code celltune/} state directory. A no-op if it does not
     * exist. Callers should normally {@link #backupProjectState(Project) back up}
     * first.
     *
     * @param project the QuPath project
     * @throws IOException if deletion fails
     */
    public static void deleteProjectState(Project<?> project) throws IOException {
        Path ctDir = cellTuneDirPath(project);
        if (ctDir == null) {
            return;
        }
        deleteDirectoryRecursively(ctDir);
        logger.info("Deleted CellTune state directory {}", ctDir);
    }

    /**
     * Zip every file under {@code dir} into {@code zipTarget}, using paths
     * relative to {@code dir} as entry names.
     *
     * @return {@code zipTarget} if at least one file was written, or {@code null}
     *         if {@code dir} is not a directory or is empty (no archive is left
     *         behind in the empty case)
     */
    static Path zipDirectory(Path dir, Path zipTarget) throws IOException {
        return FileSystemUtilities.zipDirectory(dir, zipTarget);
    }

    /** Recursively delete a directory tree. A no-op if {@code dir} does not exist. */
    static void deleteDirectoryRecursively(Path dir) throws IOException {
        FileSystemUtilities.deleteDirectoryRecursively(dir);
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
                                 ModelType model2Type,
                                 List<String> importedTrainingFeatureNames,
                                 List<GroundTruthIO.TrainingRow> importedTrainingRows) throws IOException {
        Path dir = getCellTuneDir(project);
        Path outPath = dir.resolve(STATE_FILENAME);

        SavedState existing = null;
        if (Files.exists(outPath)) {
            try {
                String existingJson = Files.readString(outPath, StandardCharsets.UTF_8);
                existing = GSON.fromJson(existingJson, SavedState.class);
            } catch (Exception ex) {
                logger.warn("Failed to read existing state for imported training preservation: {}",
                        ex.getMessage());
            }
        }

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

        if (importedTrainingFeatureNames != null || importedTrainingRows != null) {
            state.importedTrainingFeatureNames =
                    importedTrainingFeatureNames == null || importedTrainingFeatureNames.isEmpty()
                            ? null
                            : List.copyOf(importedTrainingFeatureNames);
            state.importedTrainingRows = toSavedTrainingRows(importedTrainingRows);
        } else if (existing != null) {
            // Preserve previously saved imported training data if caller didn't provide it.
            state.importedTrainingFeatureNames = existing.importedTrainingFeatureNames;
            state.importedTrainingRows = existing.importedTrainingRows;
        }

        writeState(outPath, state);
        logger.info("Saved classifier state to {} ({} labels, {} features)",
                outPath, labelStore.size(), featureNames.size());
        return outPath;
    }

    /**
     * Backward-compatible overload without imported training data.
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
        return saveState(project, classifierName, labelStore, featureNames, classNames,
                xgboostBytes, lightgbmBytes, rfModel1Bytes, rfModel2Bytes,
                model1Type, model2Type, null, null);
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
     * Persist the user's selected feature list so it survives QuPath restarts.
     * Merges into the existing state file (creating one if needed).
     *
     * @param project           target QuPath project
     * @param selectedFeatures  feature names to persist, or {@code null}/empty to clear the selection
     * @return path to the updated state file
     * @throws IOException if reading/writing the state file fails
     */
    public static Path saveSelectedFeatures(Project<?> project,
                                            List<String> selectedFeatures) throws IOException {
        Path dir = getCellTuneDir(project);
        Path statePath = dir.resolve(STATE_FILENAME);

        SavedState state;
        if (Files.exists(statePath)) {
            try {
                String json = Files.readString(statePath, StandardCharsets.UTF_8);
                state = GSON.fromJson(json, SavedState.class);
            } catch (Exception ex) {
                logger.warn("Failed to read existing state for feature selection merge: {}",
                        ex.getMessage());
                state = new SavedState();
            }
            if (state == null) state = new SavedState();
        } else {
            state = new SavedState();
        }

        if (state.name == null || state.name.isBlank()) state.name = "CellTune";
        state.timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        state.selectedFeatures =
                (selectedFeatures == null || selectedFeatures.isEmpty())
                        ? null
                        : List.copyOf(selectedFeatures);

        writeState(statePath, state);
        logger.info("Saved selected features to {} ({} features)",
                statePath,
                state.selectedFeatures == null ? 0 : state.selectedFeatures.size());
        return statePath;
    }

    /**
     * Merge per-model train/validation metrics into an existing saved state.
     * Called after {@link #saveState} so the metrics survive QuPath restarts.
     *
     * @return true if the file existed and was updated, false otherwise
     */
    public static boolean saveTrainingMetrics(Project<?> project,
                                              TrainingMetrics m1Train,
                                              TrainingMetrics m1Val,
                                              TrainingMetrics m2Train,
                                              TrainingMetrics m2Val) throws IOException {
        Path outPath = getCellTuneDir(project).resolve(STATE_FILENAME);
        if (!Files.exists(outPath)) {
            logger.warn("Cannot save training metrics: no classifier state at {}", outPath);
            return false;
        }
        SavedState state;
        try {
            String json = Files.readString(outPath, StandardCharsets.UTF_8);
            state = GSON.fromJson(json, SavedState.class);
        } catch (Exception ex) {
            logger.warn("Failed to read existing state for metrics merge: {}", ex.getMessage());
            return false;
        }
        if (state == null) return false;
        state.model1TrainMetrics = m1Train;
        state.model1ValMetrics = m1Val;
        state.model2TrainMetrics = m2Train;
        state.model2ValMetrics = m2Val;
        writeState(outPath, state);
        return true;
    }

    /**
     * Persist imported training vectors/labels immediately, even before model training.
     */
    public static Path saveImportedTrainingData(Project<?> project,
                                                List<String> featureNames,
                                                List<GroundTruthIO.TrainingRow> rows) throws IOException {
        Path dir = getCellTuneDir(project);
        Path statePath = dir.resolve(STATE_FILENAME);

        SavedState state;
        if (Files.exists(statePath)) {
            String json = Files.readString(statePath, StandardCharsets.UTF_8);
            state = GSON.fromJson(json, SavedState.class);
            if (state == null) state = new SavedState();
        } else {
            state = new SavedState();
        }

        if (state.name == null || state.name.isBlank()) state.name = "CellTune";
        state.timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        state.importedTrainingFeatureNames =
                featureNames == null || featureNames.isEmpty() ? null : List.copyOf(featureNames);
        state.importedTrainingRows = toSavedTrainingRows(rows);

        writeState(statePath, state);
        logger.info("Saved imported training data to {} ({} rows)",
                statePath, rows == null ? 0 : rows.size());
        return statePath;
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
     * Get imported training feature names from a saved state.
     */
    public static List<String> getImportedTrainingFeatureNames(SavedState state) {
        if (state == null || state.importedTrainingFeatureNames == null
                || state.importedTrainingFeatureNames.isEmpty()) {
            return null;
        }
        return new ArrayList<>(state.importedTrainingFeatureNames);
    }

    /**
     * Decode imported training rows from a saved state.
     */
    public static List<GroundTruthIO.TrainingRow> decodeImportedTrainingRows(SavedState state) {
        if (state == null || state.importedTrainingRows == null || state.importedTrainingRows.isEmpty()) {
            return null;
        }

        List<GroundTruthIO.TrainingRow> rows = new ArrayList<>();
        for (var saved : state.importedTrainingRows) {
            if (saved == null || saved.label == null || saved.label.isBlank() || saved.features == null) {
                continue;
            }
            rows.add(new GroundTruthIO.TrainingRow(saved.label, saved.features.clone()));
        }
        return rows.isEmpty() ? null : rows;
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
                ModelType.XGBOOST, ModelType.LIGHTGBM,
                null, null);
    }

    private static List<SavedState.SavedTrainingRow> toSavedTrainingRows(List<GroundTruthIO.TrainingRow> rows) {
        if (rows == null || rows.isEmpty()) return null;
        List<SavedState.SavedTrainingRow> out = new ArrayList<>(rows.size());
        for (var row : rows) {
            if (row == null || row.label() == null || row.label().isBlank() || row.features() == null) {
                continue;
            }
            SavedState.SavedTrainingRow saved = new SavedState.SavedTrainingRow();
            saved.label = row.label();
            saved.features = row.features().clone();
            out.add(saved);
        }
        return out.isEmpty() ? null : out;
    }

    private static void writeState(Path statePath, SavedState state) throws IOException {
        String json = GSON.toJson(state);
        Files.writeString(statePath, json, StandardCharsets.UTF_8);
    }

    /**
     * Save a timestamped backup of the current labels.
     * Useful for auto-backup before each training cycle.
     *
     * <p>Backwards-compatible overload — image name is unknown so it is recorded
     * as {@code null}. Prefer {@link #backupLabels(Project, String, LabelStore)}.
     */
    public static Path backupLabels(Project<?> project, LabelStore labelStore) throws IOException {
        return backupLabels(project, null, labelStore);
    }

    /**
     * Save a timestamped backup of labels across the whole project. The current
     * image's in-memory labels are merged with any per-image label files on disk
     * so the backup captures every labelled cell with its originating image.
     *
     * <p>Format: a JSON list of {@code {cellId, className, imageName}} records.
     *
     * @param project          the QuPath project
     * @param currentImageName image name for the in-memory labels (nullable)
     * @param currentLabels    in-memory labels for the current image
     * @return path to the backup file
     * @throws IOException if writing fails
     */
    public static Path backupLabels(Project<?> project,
                                    String currentImageName,
                                    LabelStore currentLabels) throws IOException {
        Path dir = getCellTuneDir(project);
        String filename = "labels_backup_" + LocalDateTime.now().format(TIMESTAMP_FMT) + ".json";
        Path outPath = dir.resolve(filename);

        // Collect per-image labels: image -> (cellId -> className).
        Map<String, Map<String, String>> byImage = new LinkedHashMap<>();

        // Disk-resident per-image labels (other images that aren't currently open).
        Path labelsDir = dir.resolve(IMAGE_LABELS_DIR);
        if (Files.isDirectory(labelsDir)) {
            try (var stream = Files.list(labelsDir)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    String fname = file.getFileName().toString();
                    if (!fname.endsWith(".json")) continue;
                    String imageName = fname.substring(0, fname.length() - ".json".length());
                    try {
                        String json = Files.readString(file, StandardCharsets.UTF_8);
                        @SuppressWarnings("unchecked")
                        Map<String, String> labels = GSON.fromJson(json, Map.class);
                        if (labels != null && !labels.isEmpty()) {
                            byImage.put(imageName, new LinkedHashMap<>(labels));
                        }
                    } catch (IOException | JsonSyntaxException e) {
                        logger.warn("Skipping unreadable label file {}: {}", file, e.getMessage());
                    }
                }
            }
        }

        // Overlay the current in-memory labels (most up-to-date for the open image).
        if (currentLabels != null && !currentLabels.getAllLabels().isEmpty()) {
            String key = currentImageName != null ? currentImageName : "";
            byImage.put(key, new LinkedHashMap<>(currentLabels.getAllLabels()));
        }

        // Flatten to a list of records so each entry carries its image name.
        List<Map<String, String>> records = new ArrayList<>();
        for (var imgEntry : byImage.entrySet()) {
            String imageName = imgEntry.getKey().isEmpty() ? null : imgEntry.getKey();
            for (var lbl : imgEntry.getValue().entrySet()) {
                Map<String, String> rec = new LinkedHashMap<>();
                rec.put("cellId", lbl.getKey());
                rec.put("className", lbl.getValue());
                rec.put("imageName", imageName);
                records.add(rec);
            }
        }

        Files.writeString(outPath, GSON.toJson(records), StandardCharsets.UTF_8);
        logger.info("Backed up {} labels across {} image(s) to {}",
                records.size(), byImage.size(), outPath);
        return outPath;
    }

    // â”€â”€ Per-image label persistence â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
     * Return true if a saved label file exists for the given image.
     * This is a cheap file-existence check â€” no JSON parsing.
     *
     * @param project   the QuPath project
     * @param imageName the image name (from ProjectImageEntry.getImageName())
     */
    public static boolean hasImageLabels(Project<?> project, String imageName) {
        try {
            Path dir = getCellTuneDir(project).resolve(IMAGE_LABELS_DIR);
            Path filePath = dir.resolve(sanitiseFileName(imageName) + ".json");
            return Files.exists(filePath);
        } catch (IOException e) {
            logger.debug("hasImageLabels: cannot resolve label path for '{}': {}", imageName, e.getMessage());
            return false;
        }
    }

    // ── Scope-aware overloads (binary classifier vs multi-class) ─────────────────
    //
    // When {@code scope} is null/blank, labels live in the shared
    // {@code image-labels/<image>.json} (multi-class).
    // When {@code scope} is a sanitized binary-marker name, labels live in
    // {@code binary-image-labels/<scope>/<image>.json} so each binary classifier
    // owns its own per-image labels and they don't bleed between classifiers.

    private static final String BINARY_IMAGE_LABELS_DIR = "binary-image-labels";

    private static Path resolveImageLabelsDir(Project<?> project, String scope) throws IOException {
        Path ctDir = getCellTuneDir(project);
        if (scope == null || scope.isBlank()) {
            return ctDir.resolve(IMAGE_LABELS_DIR);
        }
        String safeScope = sanitiseFileName(scope);
        return ctDir.resolve(BINARY_IMAGE_LABELS_DIR).resolve(safeScope);
    }

    /** Save labels for an image within an optional scope (null/blank = multi-class). */
    public static Path saveImageLabels(Project<?> project,
                                       String scope,
                                       String imageName,
                                       LabelStore labelStore) throws IOException {
        Path dir = resolveImageLabelsDir(project, scope);
        Files.createDirectories(dir);
        String safeFileName = sanitiseFileName(imageName) + ".json";
        Path outPath = dir.resolve(safeFileName);
        String json = GSON.toJson(labelStore.getAllLabels());
        Files.writeString(outPath, json, StandardCharsets.UTF_8);
        logger.info("Saved {} labels for image '{}' (scope='{}') to {}",
                labelStore.size(), imageName, scope == null ? "" : scope, outPath);
        return outPath;
    }

    /** Load labels for an image within an optional scope (null/blank = multi-class). */
    public static LabelStore loadImageLabels(Project<?> project,
                                             String scope,
                                             String imageName) throws IOException {
        Path dir = resolveImageLabelsDir(project, scope);
        Path filePath = dir.resolve(sanitiseFileName(imageName) + ".json");
        if (!Files.exists(filePath)) return null;
        String json = Files.readString(filePath, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, String> labels = GSON.fromJson(json, Map.class);
        if (labels == null || labels.isEmpty()) return null;
        return new LabelStore(imageName, labels);
    }

    /** True if labels exist for the image within the given scope. */
    public static boolean hasImageLabels(Project<?> project, String scope, String imageName) {
        try {
            Path dir = resolveImageLabelsDir(project, scope);
            Path filePath = dir.resolve(sanitiseFileName(imageName) + ".json");
            return Files.exists(filePath);
        } catch (IOException e) {
            logger.debug("hasImageLabels: cannot resolve label path for '{}' (scope '{}'): {}",
                    imageName, scope, e.getMessage());
            return false;
        }
    }

    /**
     * Replace characters that are unsafe in file names with underscores.
     */
    private static String sanitiseFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    /**
     * List all per-image label files for the given scope (binary marker name,
     * or null for multi-class). Returns an empty list if the directory does
     * not exist. The returned paths are absolute and point at the JSON files
     * themselves, not the image names — call {@link #loadImageLabels(Project, String, String)}
     * to read them by name.
     */
    public static List<Path> listImageLabelFiles(Project<?> project, String scope) throws IOException {
        Path dir = resolveImageLabelsDir(project, scope);
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
    }

    /**
     * Overwrite a per-image labels file with the raw map of cellId -> class.
     * Used by self-heal flows that have already filtered the map and need to
     * persist the cleaned version directly (without going through LabelStore).
     */
    public static void writeImageLabelsRaw(Path filePath, Map<String, String> labels) throws IOException {
        Files.createDirectories(filePath.getParent());
        String json = GSON.toJson(labels);
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
    }

    /**
     * Read a per-image labels file as a raw cellId -> class map. Returns an
     * empty map if the file does not exist or is empty.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> readImageLabelsRaw(Path filePath) throws IOException {
        if (!Files.exists(filePath)) return new LinkedHashMap<>();
        String json = Files.readString(filePath, StandardCharsets.UTF_8);
        Map<String, String> labels = GSON.fromJson(json, Map.class);
        return labels == null ? new LinkedHashMap<>() : labels;
    }

    // â”€â”€ Per-image sampled cell state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static final String IMAGE_SAMPLED_DIR = "image-sampled";
    private static final String IMAGE_PREDICTIONS_DIR = "image-predictions";

    /**
     * Internal JSON shape for persisted per-image predictions.
     */
    private static class SavedPredictions {
        List<String> classNames;
        List<SavedPredictionEntry> predictions;
    }

    /**
     * Internal JSON shape for a single persisted cell prediction.
     */
    private static class SavedPredictionEntry {
        String cellId;
        String model1Label;
        String model2Label;
        float[] model1Probs;
        float[] model2Probs;
    }

    /**
     * Save sampled cell IDs for a specific image.
     */
    public static Path saveImageSampledCells(Project<?> project, String imageName, List<String> sampledCellIds) throws IOException {
        Path dir = getCellTuneDir(project).resolve(IMAGE_SAMPLED_DIR);
        Files.createDirectories(dir);
        String safeFileName = sanitiseFileName(imageName) + ".json";
        Path outPath = dir.resolve(safeFileName);
        String json = GSON.toJson(sampledCellIds);
        Files.writeString(outPath, json, StandardCharsets.UTF_8);
        logger.info("Saved {} sampled cell IDs for image '{}' to {}", sampledCellIds.size(), imageName, outPath);
        return outPath;
    }

    /**
     * Load sampled cell IDs previously saved for a specific image.
     */
    @SuppressWarnings("unchecked")
    public static List<String> loadImageSampledCells(Project<?> project, String imageName) throws IOException {
        Path dir = getCellTuneDir(project).resolve(IMAGE_SAMPLED_DIR);
        String safeFileName = sanitiseFileName(imageName) + ".json";
        Path filePath = dir.resolve(safeFileName);
        if (!Files.exists(filePath)) {
            return null;
        }
        String json = Files.readString(filePath, StandardCharsets.UTF_8);
        List<String> ids = GSON.fromJson(json, List.class);
        if (ids == null || ids.isEmpty()) return null;
        return ids;
    }

    // â”€â”€ Per-image prediction persistence â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Save Pred_ALL predictions for a specific image.
     */
    public static Path saveImagePredictions(Project<?> project,
                                            String imageName,
                                            PopulationSet predAll) throws IOException {
        if (predAll == null || predAll.size() == 0) {
            return null;
        }

        Path dir = getCellTuneDir(project).resolve(IMAGE_PREDICTIONS_DIR);
        Files.createDirectories(dir);

        String safeFileName = sanitiseFileName(imageName) + ".json";
        Path outPath = dir.resolve(safeFileName);

        SavedPredictions state = new SavedPredictions();
        state.predictions = new ArrayList<>(predAll.size());

        List<String> classNames = null;
        for (CellPrediction pred : predAll.getAll().values()) {
            if (classNames == null || classNames.isEmpty()) {
                classNames = pred.getClassNames();
            }

            SavedPredictionEntry row = new SavedPredictionEntry();
            row.cellId = pred.getCellId();
            row.model1Label = pred.getModel1Label();
            row.model2Label = pred.getModel2Label();
            row.model1Probs = pred.getModel1Probs();
            row.model2Probs = pred.getModel2Probs();
            state.predictions.add(row);
        }

        if (classNames == null || classNames.isEmpty() || state.predictions.isEmpty()) {
            return null;
        }

        state.classNames = List.copyOf(classNames);
        Files.writeString(outPath, GSON.toJson(state), StandardCharsets.UTF_8);
        logger.info("Saved {} predictions for image '{}' to {}",
                state.predictions.size(), imageName, outPath);
        return outPath;
    }

    /**
     * Load Pred_ALL predictions previously saved for a specific image.
     */
    public static PopulationSet loadImagePredictions(Project<?> project,
                                                     String imageName) throws IOException {
        Path dir = getCellTuneDir(project).resolve(IMAGE_PREDICTIONS_DIR);
        String safeFileName = sanitiseFileName(imageName) + ".json";
        Path filePath = dir.resolve(safeFileName);

        if (!Files.exists(filePath)) {
            return null;
        }

        SavedPredictions saved = GSON.fromJson(Files.readString(filePath, StandardCharsets.UTF_8),
                SavedPredictions.class);
        if (saved == null || saved.classNames == null || saved.classNames.isEmpty()
                || saved.predictions == null || saved.predictions.isEmpty()) {
            return null;
        }

        PopulationSet predAll = new PopulationSet("Pred_ALL");
        for (SavedPredictionEntry row : saved.predictions) {
            if (row == null || row.cellId == null || row.cellId.isBlank()
                    || row.model1Label == null || row.model2Label == null
                    || row.model1Probs == null || row.model2Probs == null) {
                continue;
            }

            if (row.model1Probs.length != saved.classNames.size()
                    || row.model2Probs.length != saved.classNames.size()) {
                continue;
            }

            CellPrediction pred = new CellPrediction(
                    row.cellId,
                    row.model1Label,
                    row.model2Label,
                    row.model1Probs,
                    row.model2Probs,
                    saved.classNames);
            predAll.put(row.cellId, pred);
        }

        if (predAll.size() == 0) {
            return null;
        }

        logger.info("Loaded {} predictions for image '{}' from {}",
                predAll.size(), imageName, filePath);
        return predAll;
    }


    /**
     * List the names of all images in the project that have saved prediction files.
     */
    public static List<String> listImagesWithPredictions(Project<?> project) throws IOException {
        Path dir = getCellTuneDir(project).resolve(IMAGE_PREDICTIONS_DIR);
        if (!Files.exists(dir)) return List.of();
        List<String> result = new ArrayList<>();
        for (var entry : project.getImageList()) {
            String name = entry.getImageName();
            if (Files.exists(dir.resolve(sanitiseFileName(name) + ".json"))) {
                result.add(name);
            }
        }
        return result;
    }
    // â”€â”€ Binary classifier persistence â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Resolve the binary/ subdirectory inside the celltune dir, creating it if needed.
     */
    private static Path getBinaryDir(Project<?> project) throws IOException {
        Path ctDir = getCellTuneDir(project);
        Path binaryDir = ctDir.resolve("binary");
        Files.createDirectories(binaryDir);
        return binaryDir;
    }

    /**
     * Save a binary classifier's trained state to
     * {@code <project>/celltune/binary/<sanitizedMarker>.json}.
     * <p>
     * Reuses the existing {@link SavedState} structure. The classNames list must
     * have exactly 2 entries (e.g. {@code ["CD4_pos", "CD4_neg"]}).
     *
     * @param project              the QuPath project
     * @param sanitizedMarkerName  the sanitized marker name (from {@code BinaryClassifierRegistry.sanitizeMarkerName})
     * @param labelStore           current ground-truth labels for this marker
     * @param featureNames         feature column ordering used during training
     * @param classNames           class name ordering (exactly 2 classes)
     * @param xgboostBytes         serialised XGBoost model bytes (may be null)
     * @param lightgbmBytes        serialised LightGBM model bytes (may be null)
     * @param rfModel1Bytes        serialised RF model 1 bytes (may be null)
     * @param rfModel2Bytes        serialised RF model 2 bytes (may be null)
     * @param model1Type           type of model 1
     * @param model2Type           type of model 2
     * @return path to the saved JSON file
     * @throws IOException if writing fails
     */
    public static Path saveBinaryState(Project<?> project,
                                       String sanitizedMarkerName,
                                       LabelStore labelStore,
                                       List<String> featureNames,
                                       List<String> classNames,
                                       byte[] xgboostBytes,
                                       byte[] lightgbmBytes,
                                       byte[] rfModel1Bytes,
                                       byte[] rfModel2Bytes,
                                       ModelType model1Type,
                                       ModelType model2Type) throws IOException {
        Path binaryDir = getBinaryDir(project);
        Path outPath = binaryDir.resolve(sanitizedMarkerName + ".json");

        SavedState state = new SavedState();
        state.name = sanitizedMarkerName;
        state.timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        state.featureNames = List.copyOf(featureNames);
        state.classNames = List.copyOf(classNames);
        state.labels = labelStore.getAllLabels();
        if (xgboostBytes != null)  state.xgboostModelBase64  = Base64.getEncoder().encodeToString(xgboostBytes);
        if (lightgbmBytes != null) state.lightgbmModelBase64 = Base64.getEncoder().encodeToString(lightgbmBytes);
        if (rfModel1Bytes != null) state.rfModel1Base64       = Base64.getEncoder().encodeToString(rfModel1Bytes);
        if (rfModel2Bytes != null) state.rfModel2Base64       = Base64.getEncoder().encodeToString(rfModel2Bytes);
        state.model1Type = model1Type != null ? model1Type.name() : ModelType.XGBOOST.name();
        state.model2Type = model2Type != null ? model2Type.name() : ModelType.LIGHTGBM.name();

        writeState(outPath, state);
        logger.info("Saved binary classifier state for '{}' to {}", sanitizedMarkerName, outPath);
        return outPath;
    }

    /**
     * Load a binary classifier's state from
     * {@code <project>/celltune/binary/<sanitizedMarker>.json}.
     *
     * @param project             the QuPath project
     * @param sanitizedMarkerName the sanitized marker name
     * @return the loaded state, or null if no state file exists for this marker
     * @throws IOException if reading or parsing fails
     */
    public static SavedState loadBinaryState(Project<?> project,
                                             String sanitizedMarkerName) throws IOException {
        Path binaryDir = getBinaryDir(project);
        Path statePath = binaryDir.resolve(sanitizedMarkerName + ".json");
        if (!Files.exists(statePath)) return null;
        String json = Files.readString(statePath, StandardCharsets.UTF_8);
        return GSON.fromJson(json, SavedState.class);
    }

    /**
     * Save only the labels for a binary classifier without touching model bytes.
     * <p>
     * Used during active labelling before a model has been trained. If a state file
     * already exists, model bytes and other fields are preserved.
     *
     * @param project             the QuPath project
     * @param sanitizedMarkerName the sanitized marker name
     * @param labelStore          labels to persist
     * @throws IOException if reading or writing fails
     */
    public static void saveBinaryLabels(Project<?> project,
                                        String sanitizedMarkerName,
                                        LabelStore labelStore) throws IOException {
        Path binaryDir = getBinaryDir(project);
        Path statePath = binaryDir.resolve(sanitizedMarkerName + ".json");

        SavedState state;
        if (Files.exists(statePath)) {
            String existing = Files.readString(statePath, StandardCharsets.UTF_8);
            state = GSON.fromJson(existing, SavedState.class);
            if (state == null) state = new SavedState();
        } else {
            state = new SavedState();
        }
        state.name = sanitizedMarkerName;
        state.timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        state.labels = labelStore.getAllLabels();

        writeState(statePath, state);
        logger.info("Saved binary labels for '{}' ({} labels)", sanitizedMarkerName, labelStore.size());
    }

    /**
     * Load only the LabelStore for a binary classifier.
     * <p>
     * Returns an empty LabelStore named after the marker if no state file exists.
     *
     * @param project             the QuPath project
     * @param sanitizedMarkerName the sanitized marker name
     * @return a populated LabelStore, never null
     * @throws IOException if reading fails
     */
    public static LabelStore loadBinaryLabels(Project<?> project,
                                              String sanitizedMarkerName) throws IOException {
        SavedState state = loadBinaryState(project, sanitizedMarkerName);
        if (state == null || state.labels == null) return new LabelStore(sanitizedMarkerName);
        return toLabelStore(state);
    }


    /**
     * Marker-specific imported training payload used by binary transfer workflows.
     */
    public record BinaryImportedTrainingData(List<String> featureNames,
                                             List<GroundTruthIO.TrainingRow> rows) {}

    private static class SavedBinaryImportedTrainingData {
        public String timestamp;
        public List<String> featureNames;
        public List<SavedState.SavedTrainingRow> rows;
    }

    /**
     * Resolve the binary-imported/ subdirectory inside the celltune dir, creating it if needed.
     */
    private static Path getBinaryImportedDir(Project<?> project) throws IOException {
        Path ctDir = getCellTuneDir(project);
        Path importedDir = ctDir.resolve("binary-imported");
        Files.createDirectories(importedDir);
        return importedDir;
    }

    /**
     * Save imported training rows for a specific binary marker.
     *
     * @param project             the QuPath project
     * @param sanitizedMarkerName sanitized marker name (or raw marker name that can be sanitized)
     * @param featureNames        ordered feature names for the training rows
     * @param rows                imported labelled feature rows
     * @return path to the persisted marker payload
     * @throws IOException if writing fails
     */
    public static Path saveBinaryImportedTrainingData(Project<?> project,
                                                      String sanitizedMarkerName,
                                                      List<String> featureNames,
                                                      List<GroundTruthIO.TrainingRow> rows) throws IOException {
        String safeMarker = BinaryClassifierRegistry.sanitizeMarkerName(sanitizedMarkerName);
        Path importedDir = getBinaryImportedDir(project);
        Path outPath = importedDir.resolve(safeMarker + ".json");

        SavedBinaryImportedTrainingData saved = new SavedBinaryImportedTrainingData();
        saved.timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        saved.featureNames = featureNames == null ? List.of() : List.copyOf(featureNames);
        saved.rows = toSavedTrainingRows(rows);

        Files.writeString(outPath, GSON.toJson(saved), StandardCharsets.UTF_8);
        int rowCount = saved.rows == null ? 0 : saved.rows.size();
        logger.info("Saved binary imported training rows for '{}' ({} rows)", safeMarker, rowCount);
        return outPath;
    }

    /**
     * Load imported training rows for a specific binary marker.
     *
     * @param project             the QuPath project
     * @param sanitizedMarkerName sanitized marker name (or raw marker name that can be sanitized)
     * @return marker payload, or null when no payload exists
     * @throws IOException if reading fails
     */
    public static BinaryImportedTrainingData loadBinaryImportedTrainingData(Project<?> project,
                                                                            String sanitizedMarkerName) throws IOException {
        String safeMarker = BinaryClassifierRegistry.sanitizeMarkerName(sanitizedMarkerName);
        Path importedDir = getBinaryImportedDir(project);
        Path inPath = importedDir.resolve(safeMarker + ".json");
        if (!Files.exists(inPath)) {
            return null;
        }

        SavedBinaryImportedTrainingData saved =
                GSON.fromJson(Files.readString(inPath, StandardCharsets.UTF_8), SavedBinaryImportedTrainingData.class);
        if (saved == null) {
            return null;
        }

        List<String> featureNames = saved.featureNames == null ? List.of() : List.copyOf(saved.featureNames);
        List<GroundTruthIO.TrainingRow> rows = decodeSavedTrainingRows(saved.rows);
        return new BinaryImportedTrainingData(featureNames, rows);
    }

    private static List<GroundTruthIO.TrainingRow> decodeSavedTrainingRows(List<SavedState.SavedTrainingRow> savedRows) {
        if (savedRows == null || savedRows.isEmpty()) {
            return List.of();
        }
        List<GroundTruthIO.TrainingRow> rows = new ArrayList<>();
        for (SavedState.SavedTrainingRow saved : savedRows) {
            if (saved == null || saved.label == null || saved.label.isBlank() || saved.features == null) {
                continue;
            }
            rows.add(new GroundTruthIO.TrainingRow(saved.label, saved.features.clone()));
        }
        return rows;
    }

    // -- Composite rule persistence ---------------------------------------------

    /**
     * Save named composite rules to {@code <project>/celltune/composite-rules.json}.
     *
     * @param project the QuPath project (null-safe - logs warning and returns)
     * @param rules named rules to persist
     * @throws IOException if writing fails
     */
    public static void saveCompositeRules(Project<?> project,
                                          List<CompositeClassificationRule> rules) throws IOException {
        if (project == null) {
            logger.warn("saveCompositeRules: project is null - skipping save");
            return;
        }

        Path dir = getCellTuneDir(project);
        Path path = dir.resolve(COMPOSITE_RULES_FILENAME);

        JsonObject root = new JsonObject();
        root.addProperty("version", COMPOSITE_RULES_SCHEMA_VERSION);

        JsonArray rawRules = new JsonArray();
        LinkedHashSet<String> seenNames = new LinkedHashSet<>();

        List<CompositeClassificationRule> safeRules = rules != null ? rules : List.of();
        for (CompositeClassificationRule rule : safeRules) {
            if (rule == null) {
                logger.warn("saveCompositeRules: skipping null rule entry");
                continue;
            }

            String dedupeKey = rule.name().toLowerCase();
            if (!seenNames.add(dedupeKey)) {
                logger.warn("saveCompositeRules: duplicate rule name '{}' skipped", rule.name());
                continue;
            }

            JsonObject rawRule = new JsonObject();
            rawRule.addProperty("name", rule.name());
            rawRule.addProperty("expression", rule.expression());

            JsonArray rawConditions = new JsonArray();
            for (CompositeClassificationRule.MarkerCondition condition : rule.conditions()) {
                JsonObject rawCondition = new JsonObject();
                rawCondition.addProperty("marker", condition.markerName());
                rawCondition.addProperty("polarity", String.valueOf(condition.polarity().symbol()));
                rawConditions.add(rawCondition);
            }
            rawRule.add("conditions", rawConditions);
            rawRules.add(rawRule);
        }

        root.add("rules", rawRules);
        Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        logger.info("Saved {} composite rule(s) to {}", rawRules.size(), path);
    }

    /**
     * Load named composite rules from {@code <project>/celltune/composite-rules.json}.
     * Invalid rule rows are skipped with warnings; valid rows continue loading.
     *
     * @param project the QuPath project (null-safe - returns empty list)
     * @return mutable list of valid composite rules
     * @throws IOException if file reading fails
     */
    public static List<CompositeClassificationRule> loadCompositeRules(Project<?> project) throws IOException {
        if (project == null) {
            return new ArrayList<>();
        }

        Path dir = getCellTuneDir(project);
        Path path = dir.resolve(COMPOSITE_RULES_FILENAME);
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }

        String json = Files.readString(path, StandardCharsets.UTF_8);
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonSyntaxException ex) {
            logger.warn("loadCompositeRules: invalid JSON in {}: {}", path, ex.getMessage());
            return new ArrayList<>();
        }

        JsonArray rawRules = null;
        if (root.isJsonObject()) {
            JsonObject rootObj = root.getAsJsonObject();
            JsonElement rawVersion = rootObj.get("version");
            if (rawVersion != null && rawVersion.isJsonPrimitive() && rawVersion.getAsJsonPrimitive().isNumber()) {
                int version = rawVersion.getAsInt();
                if (version != COMPOSITE_RULES_SCHEMA_VERSION) {
                    logger.warn("loadCompositeRules: schema version {} in {} (expected {})", version, path,
                            COMPOSITE_RULES_SCHEMA_VERSION);
                }
            }
            JsonElement rulesElement = rootObj.get("rules");
            if (rulesElement != null && rulesElement.isJsonArray()) {
                rawRules = rulesElement.getAsJsonArray();
            }
        } else if (root.isJsonArray()) {
            // Legacy fallback: top-level array of rule objects
            rawRules = root.getAsJsonArray();
        }

        if (rawRules == null) {
            return new ArrayList<>();
        }

        List<CompositeClassificationRule> loaded = new ArrayList<>();
        LinkedHashSet<String> seenNames = new LinkedHashSet<>();

        for (JsonElement rawRuleElement : rawRules) {
            if (!rawRuleElement.isJsonObject()) {
                logger.warn("loadCompositeRules: skipping non-object rule entry");
                continue;
            }

            JsonObject rawRule = rawRuleElement.getAsJsonObject();
            String name = getOptionalString(rawRule, "name");
            if (name == null || name.isBlank()) {
                logger.warn("loadCompositeRules: skipping rule with missing name");
                continue;
            }

            try {
                CompositeClassificationRule parsedRule;

                String expression = getOptionalString(rawRule, "expression");
                if (expression != null && !expression.isBlank()) {
                    parsedRule = CompositeClassificationRule.parse(name, expression);
                } else {
                    JsonElement rawConditionsElement = rawRule.get("conditions");
                    if (rawConditionsElement == null || !rawConditionsElement.isJsonArray()) {
                        logger.warn("loadCompositeRules: rule '{}' missing expression/conditions - skipped", name);
                        continue;
                    }
                    List<CompositeClassificationRule.MarkerCondition> conditions =
                            parseRuleConditions(rawConditionsElement.getAsJsonArray());
                    parsedRule = CompositeClassificationRule.of(name, conditions);
                }

                String dedupeKey = parsedRule.name().toLowerCase();
                if (!seenNames.add(dedupeKey)) {
                    logger.warn("loadCompositeRules: duplicate rule name '{}' skipped", parsedRule.name());
                    continue;
                }
                loaded.add(parsedRule);
            } catch (IllegalArgumentException ex) {
                logger.warn("loadCompositeRules: skipping malformed rule '{}': {}", name, ex.getMessage());
            }
        }

        logger.info("Loaded {} composite rule(s) from {}", loaded.size(), path);
        return loaded;
    }

    private static String getOptionalString(JsonObject obj, String key) {
        JsonElement raw = obj.get(key);
        if (raw == null || raw.isJsonNull() || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            return null;
        }
        return raw.getAsString();
    }

    private static List<CompositeClassificationRule.MarkerCondition> parseRuleConditions(JsonArray rawConditions) {
        List<CompositeClassificationRule.MarkerCondition> conditions = new ArrayList<>();
        for (JsonElement rawConditionElement : rawConditions) {
            if (!rawConditionElement.isJsonObject()) {
                throw new IllegalArgumentException("Rule condition entry is not an object");
            }

            JsonObject rawCondition = rawConditionElement.getAsJsonObject();
            String marker = getOptionalString(rawCondition, "marker");
            String polarityToken = getOptionalString(rawCondition, "polarity");
            if (marker == null || marker.isBlank() || polarityToken == null || polarityToken.isBlank()) {
                throw new IllegalArgumentException("Rule condition missing marker or polarity");
            }

            CompositeClassificationRule.Polarity polarity = CompositeClassificationRule.Polarity.fromToken(polarityToken);
            conditions.add(CompositeClassificationRule.MarkerCondition.of(marker, polarity));
        }
        return conditions;
    }
    // -- Composite classifier config persistence --------------------------------

    /**
     * Save the composite classifier selection (which markers are checked) to
     * {@code <project>/celltune/composite-config.json}.
     *
     * @param project         the QuPath project (null-safe - logs warning and returns)
     * @param selectedMarkers the marker names to persist
     * @throws IOException if writing fails
     */
    public static void saveCompositeConfig(Project<?> project,
                                           List<String> selectedMarkers) throws IOException {
        if (project == null) {
            logger.warn("saveCompositeConfig: project is null - skipping save");
            return;
        }
        Path dir  = getCellTuneDir(project);
        Path path = dir.resolve("composite-config.json");
        String json = GSON.toJson(Map.of("selectedMarkers",
                selectedMarkers != null ? selectedMarkers : List.of()));
        Files.writeString(path, json, StandardCharsets.UTF_8);
        int count = selectedMarkers != null ? selectedMarkers.size() : 0;
        logger.info("Saved composite config ({} markers) to {}", count, path);
    }

    /**
     * Load the composite classifier selection from
     * {@code <project>/celltune/composite-config.json}.
     *
     * @param project the QuPath project (null-safe - returns empty list)
     * @return mutable list of selected marker names, never null
     * @throws IOException if reading or parsing fails
     */
    public static List<String> loadCompositeConfig(Project<?> project) throws IOException {
        if (project == null) {
            return new ArrayList<>();
        }
        Path dir  = getCellTuneDir(project);
        Path path = dir.resolve("composite-config.json");
        if (!Files.exists(path)) return new ArrayList<>();
        String json = Files.readString(path, StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = GSON.fromJson(json, Map.class);
        if (map == null) return new ArrayList<>();
        Object raw = map.get("selectedMarkers");
        if (raw instanceof List<?> rawList) {
            List<String> result = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof String s) result.add(s);
            }
            int count = result.size();
            logger.info("Loaded composite config: {} markers from {}", count, path);
            return result;
        }
        return new ArrayList<>();
    }

    // -- Marker table persistence -----------------------------------------------

    /**
     * Save the imported marker table to {@code <project>/celltune/marker-table.json}
     * so it survives QuPath restarts and no longer has to be re-imported.
     * <p>
     * Both CSV formats are preserved losslessly: simple tables store their display
     * markers, rule tables store their primary/secondary/tertiary gating expressions.
     *
     * @param project the QuPath project (null-safe - logs warning and returns)
     * @param table   the marker table to persist (null or empty clears the file)
     * @throws IOException if writing fails
     */
    public static void saveMarkerTable(Project<?> project, CellTypeTable table) throws IOException {
        if (project == null) {
            logger.warn("saveMarkerTable: project is null - skipping save");
            return;
        }

        Path dir = getCellTuneDir(project);
        Path path = dir.resolve(MARKER_TABLE_FILENAME);

        if (table == null || table.isEmpty()) {
            Files.deleteIfExists(path);
            logger.info("Cleared marker table at {}", path);
            return;
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", MARKER_TABLE_SCHEMA_VERSION);
        boolean hasRules = table.hasGatingRules();
        root.addProperty("hasRules", hasRules);

        JsonArray entries = new JsonArray();
        for (String cellType : table.getCellTypes()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("cellType", cellType);
            if (hasRules) {
                String primary = table.getPrimaryExpression(cellType);
                String secondary = table.getSecondaryMarkers(cellType);
                String tertiary = table.getTertiaryMarkers(cellType);
                if (primary != null) entry.addProperty("primary", primary);
                if (secondary != null) entry.addProperty("secondary", secondary);
                if (tertiary != null) entry.addProperty("tertiary", tertiary);
            } else {
                JsonArray markers = new JsonArray();
                for (String marker : table.getMarkers(cellType)) {
                    markers.add(marker);
                }
                entry.add("markers", markers);
            }
            entries.add(entry);
        }
        root.add("entries", entries);

        Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        logger.info("Saved marker table ({} cell types, {} format) to {}",
                entries.size(), hasRules ? "rule" : "simple", path);
    }

    /**
     * Load the persisted marker table from {@code <project>/celltune/marker-table.json}.
     *
     * @param project the QuPath project (null-safe - returns null)
     * @return the reconstructed marker table, or null if no valid file exists
     */
    public static CellTypeTable loadMarkerTable(Project<?> project) {
        if (project == null) {
            return null;
        }

        Path path;
        try {
            path = getCellTuneDir(project).resolve(MARKER_TABLE_FILENAME);
        } catch (IOException ex) {
            logger.warn("loadMarkerTable: cannot resolve celltune dir: {}", ex.getMessage());
            return null;
        }
        if (!Files.exists(path)) {
            return null;
        }

        JsonObject root;
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                logger.warn("loadMarkerTable: unexpected JSON shape in {}", path);
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | JsonSyntaxException ex) {
            logger.warn("loadMarkerTable: failed to read {}: {}", path, ex.getMessage());
            return null;
        }

        JsonElement rawVersion = root.get("version");
        if (rawVersion != null && rawVersion.isJsonPrimitive() && rawVersion.getAsJsonPrimitive().isNumber()
                && rawVersion.getAsInt() != MARKER_TABLE_SCHEMA_VERSION) {
            logger.warn("loadMarkerTable: schema version {} in {} (expected {})",
                    rawVersion.getAsInt(), path, MARKER_TABLE_SCHEMA_VERSION);
        }

        boolean hasRules = root.has("hasRules") && root.get("hasRules").isJsonPrimitive()
                && root.get("hasRules").getAsBoolean();

        JsonElement rawEntries = root.get("entries");
        if (rawEntries == null || !rawEntries.isJsonArray()) {
            return null;
        }

        CellTypeTable table = new CellTypeTable();
        for (JsonElement rawEntry : rawEntries.getAsJsonArray()) {
            if (!rawEntry.isJsonObject()) continue;
            JsonObject entry = rawEntry.getAsJsonObject();
            String cellType = getOptionalString(entry, "cellType");
            if (cellType == null || cellType.isBlank()) {
                logger.warn("loadMarkerTable: skipping entry with missing cellType");
                continue;
            }
            if (hasRules) {
                table.putRule(cellType,
                        getOptionalString(entry, "primary"),
                        getOptionalString(entry, "secondary"),
                        getOptionalString(entry, "tertiary"));
            } else {
                List<String> markers = new ArrayList<>();
                JsonElement rawMarkers = entry.get("markers");
                if (rawMarkers != null && rawMarkers.isJsonArray()) {
                    for (JsonElement m : rawMarkers.getAsJsonArray()) {
                        if (m.isJsonPrimitive() && m.getAsJsonPrimitive().isString()) {
                            markers.add(m.getAsString());
                        }
                    }
                }
                table.put(cellType, markers);
            }
        }

        if (table.isEmpty()) {
            return null;
        }
        logger.info("Loaded marker table ({} cell types, {} format) from {}",
                table.size(), hasRules ? "rule" : "simple", path);
        return table;
    }
}

