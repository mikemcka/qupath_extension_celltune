package qupath.ext.celltune.classifier;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.io.BinaryClassifierRegistry;
import qupath.ext.celltune.io.ProjectStateManager;
import qupath.ext.celltune.model.CellFeatureExtractor;
import java.awt.image.BufferedImage;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Applies multiple trained binary classifiers to all cell detections and assigns
 * a composite QuPath PathClass (e.g. {@code CD3+:CD4+:CD45-}) per cell.
 *
 * <p>Scores are averaged across XGBoost and LightGBM models. A cell is considered
 * positive for a marker when the averaged probability for class index 1 is &ge; 0.5.
 * Markers are always written in alphabetical order in the composite PathClass name.
 */
public class CompositeClassifier {

    private static final Logger logger = LoggerFactory.getLogger(CompositeClassifier.class);

    private final DoubleProperty progress = new SimpleDoubleProperty(0.0);
    private final StringProperty status   = new SimpleStringProperty("");

    public DoubleProperty progressProperty() { return progress; }
    public StringProperty statusProperty()   { return status; }

    private void updateStatus(String msg, double pct) {
        Platform.runLater(() -> {
            status.set(msg);
            progress.set(pct);
        });
    }

    /**
     * Classify all detection objects in {@code imageData} using the given binary markers.
     *
     * @param imageData   the image whose detections will be classified
     * @param markerNames display names of the binary classifiers to include (unsanitized)
     * @param project     QuPath project (used to load binary states)
     * @param log         optional log consumer — may be null
     * @return number of cells classified
     * @throws Exception if a fatal error occurs; per-marker errors are logged and skipped
     */
    public int apply(ImageData<?> imageData,
                     List<String> markerNames,
                     Project<?> project,
                     Consumer<String> log) throws Exception {

        Consumer<String> out = log != null ? log : s -> {};

        List<PathObject> detectionList = new ArrayList<>(
                imageData.getHierarchy().getDetectionObjects());

        if (detectionList.isEmpty()) {
            out.accept("No detections found in current image.");
            return 0;
        }

        int nCells = detectionList.size();

        // Alphabetical order for deterministic PathClass naming (D-07)
        List<String> sorted = new ArrayList<>(markerNames);
        Collections.sort(sorted);

        updateStatus("Starting composite classification...", 0.0);
        out.accept("Classifying " + nCells + " cells using " + sorted.size() + " markers...");

        // Per-marker results accumulated in sorted order
        List<String>   activeMarkers   = new ArrayList<>();
        List<float[]>  markerPosProbs  = new ArrayList<>();

        for (int mi = 0; mi < sorted.size(); mi++) {
            String markerName = sorted.get(mi);
            String sanitized  = BinaryClassifierRegistry.sanitizeMarkerName(markerName);
            try {
                ProjectStateManager.SavedState state =
                        ProjectStateManager.loadBinaryState(project, sanitized);

                if (state == null || state.xgboostModelBase64 == null
                        || state.featureNames == null || state.featureNames.isEmpty()) {
                    out.accept("Skipping '" + markerName + "' — not trained or missing state.");
                    continue;
                }

                List<String> classNames = state.classNames;
                if (classNames == null || classNames.size() < 2) {
                    out.accept("Skipping '" + markerName + "' — invalid class list.");
                    continue;
                }

                byte[] xgbBytes = ProjectStateManager.decodeXGBoostModel(state);
                byte[] lgbBytes = ProjectStateManager.decodeLightGBMModel(state);
                if (xgbBytes == null) {
                    out.accept("Skipping '" + markerName + "' — no XGBoost model bytes.");
                    continue;
                }

                // Each binary classifier may use its own feature set (D-03)
                CellFeatureExtractor extractor = new CellFeatureExtractor(state.featureNames);
                float[] flatData  = extractor.extractMatrix(detectionList);
                int     nFeatures = extractor.getNumFeatures();

                XGBoostModel xgbModel = new XGBoostModel();
                xgbModel.loadFromBytes(xgbBytes, classNames, state.featureNames);
                float[][] xgbProbs = xgbModel.predictProba(flatData, nCells, nFeatures);

                float[][] lgbProbs = null;
                if (lgbBytes != null) {
                    LightGBMModel lgbModel = new LightGBMModel();
                    lgbModel.loadFromBytes(lgbBytes, classNames, state.featureNames);
                    lgbProbs = lgbModel.predictProba(flatData, nCells, nFeatures);
                }

                // Class index 1 = positive class.
                // classNames are sorted alphabetically at training time; for typical
                // marker names (e.g. CD4_neg/CD4_pos) "neg" < "pos" → index 1 = positive.
                float[] posProbs = new float[nCells];
                for (int i = 0; i < nCells; i++) {
                    float xgbPos = (xgbProbs[i].length > 1) ? xgbProbs[i][1] : xgbProbs[i][0];
                    float lgbPos = (lgbProbs != null && lgbProbs[i].length > 1)
                            ? lgbProbs[i][1] : xgbPos;
                    posProbs[i] = (lgbProbs != null) ? (xgbPos + lgbPos) / 2.0f : xgbPos;
                }

                activeMarkers.add(markerName);
                markerPosProbs.add(posProbs);
                out.accept("Predicted '" + markerName + "'.");

            } catch (Exception ex) {
                out.accept("Error processing '" + markerName + "': " + ex.getMessage());
                logger.warn("CompositeClassifier: error for marker '{}': {}",
                        markerName, ex.getMessage(), ex);
            }

            double pct = 0.2 + 0.6 * ((double)(mi + 1) / sorted.size());
            updateStatus("Predicting " + markerName + "...", pct);
        }

        // Build PathClass for every detection
        List<PathObject> classifyObjects = new ArrayList<>(nCells);
        List<PathClass>  classifyClasses = new ArrayList<>(nCells);

        if (activeMarkers.isEmpty()) {
            // No trained markers contributed — D-09/D-10: set all to Unclassified
            for (PathObject det : detectionList) {
                classifyObjects.add(det);
                classifyClasses.add(null); // null = Unclassified in QuPath
            }
        } else {
            for (int i = 0; i < nCells; i++) {
                StringBuilder sb = new StringBuilder();
                for (int mi = 0; mi < activeMarkers.size(); mi++) {
                    if (sb.length() > 0) sb.append(':');
                    sb.append(activeMarkers.get(mi));
                    sb.append(markerPosProbs.get(mi)[i] >= 0.5f ? '+' : '-');
                }
                classifyObjects.add(detectionList.get(i));
                classifyClasses.add(PathClass.fromString(sb.toString()));
            }
        }

        // Batch PathClass assignment on the FX thread (per UI thread-safety convention)
        Platform.runLater(() -> {
            for (int i = 0; i < classifyObjects.size(); i++) {
                classifyObjects.get(i).setPathClass(classifyClasses.get(i));
            }
            imageData.getHierarchy().fireObjectClassificationsChangedEvent(
                    CompositeClassifier.this, classifyObjects);
        });

        updateStatus("Complete", 1.0);
        out.accept("Composite classification complete: " + nCells + " cells.");
        return nCells;
    }

    /**
     * Classify cells across multiple project images.
     *
     * @param project     the QuPath project
     * @param imageNames  names of images to classify
     * @param markerNames display names of markers to use
     * @param log         optional log consumer — may be null
     * @return map of imageName → result string
     * @throws Exception if a fatal error occurs; per-image errors are caught and reported
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> batch(Project<?> project,
                                     List<String> imageNames,
                                     List<String> markerNames,
                                     Consumer<String> log) throws Exception {

        Consumer<String> out = log != null ? log : s -> {};
        Map<String, String> results = new LinkedHashMap<>();
        int total = imageNames.size();

        for (int idx = 0; idx < total; idx++) {
            String imageName = imageNames.get(idx);
            updateStatus("Processing " + imageName + "...", (double) idx / total);

            var typedProject = (Project<BufferedImage>)(Project<?>)project;
            var entryOpt = typedProject.getImageList().stream()
                    .filter(e -> e.getImageName().equals(imageName))
                    .findFirst();

            if (entryOpt.isEmpty()) {
                results.put(imageName, "Skipped: image not found in project");
                out.accept("Skipped '" + imageName + "': not found in project.");
                continue;
            }

            var entry = entryOpt.get();
            try {
                var imageData = entry.readImageData();
                if (imageData == null) {
                    results.put(imageName, "Skipped: could not read image data");
                    out.accept("Skipped '" + imageName + "': null imageData.");
                    continue;
                }

                int count = apply(imageData, markerNames, project, out);
                entry.saveImageData(imageData);
                results.put(imageName, "Classified " + count + " cells");
                out.accept("'" + imageName + "': Classified " + count + " cells.");

            } catch (Exception ex) {
                results.put(imageName, "Error: " + ex.getMessage());
                out.accept("Error processing '" + imageName + "': " + ex.getMessage());
                logger.warn("CompositeClassifier batch error for '{}': {}",
                        imageName, ex.getMessage(), ex);
            }
        }

        updateStatus("Batch complete", 1.0);
        return results;
    }
}
