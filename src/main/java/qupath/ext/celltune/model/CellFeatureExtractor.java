package qupath.ext.celltune.model;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectFilter;
import qupath.lib.objects.PathObjectTools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads QuPath cell measurements into flat float arrays suitable for ML training/inference.
 * <p>
 * A single instance holds a fixed list of feature names (column ordering) so that
 * every call to {@link #extractRow(PathObject)} produces a vector with the same layout.
 * Both XGBoost and LightGBM must share the same {@code CellFeatureExtractor} to guarantee
 * identical feature ordering.
 */
public class CellFeatureExtractor {

    private final List<String> featureNames;

    /**
     * Create an extractor with a specific ordered list of feature (measurement) names.
     *
     * @param featureNames ordered list of measurement names to extract
     */
    public CellFeatureExtractor(List<String> featureNames) {
        this.featureNames = List.copyOf(featureNames);
    }

    /**
     * Discover all available measurement names from a collection of detection objects.
     *
     * @param detections detection objects to scan
     * @return ordered list of unique measurement names found across all detections
     */
    public static List<String> discoverFeatureNames(Collection<PathObject> detections) {
        return new ArrayList<>(PathObjectTools.getAvailableFeatures(detections));
    }

    /**
     * Extract a single cell's measurements as a float array, in the order defined
     * by this extractor's feature name list.
     * <p>
     * Missing or NaN values are replaced with 0f.
     *
     * @param cell the detection object to extract features from
     * @return float array of length {@link #getNumFeatures()}
     */
    public float[] extractRow(PathObject cell) {
        var mlist = cell.getMeasurementList();
        float[] row = new float[featureNames.size()];
        for (int i = 0; i < featureNames.size(); i++) {
            double v = mlist.get(featureNames.get(i));
            row[i] = Double.isNaN(v) ? 0f : (float) v;
        }
        return row;
    }

    /**
     * Extract features from multiple cells into a flat float array (row-major),
     * suitable for constructing a DMatrix or LightGBM Dataset.
     * <p>
     * Extraction is parallelised across available processors for large cell counts.
     *
     * @param cells collection of detection objects
     * @return flat float array of size {@code cells.size() * getNumFeatures()}
     */
    public float[] extractMatrix(Collection<PathObject> cells) {
        int nFeatures = featureNames.size();
        int nCells = cells.size();
        float[] matrix = new float[nCells * nFeatures];

        // Convert to indexed list for parallel random access
        List<PathObject> cellList = (cells instanceof List)
                ? (List<PathObject>) cells
                : new ArrayList<>(cells);

        java.util.stream.IntStream.range(0, nCells).parallel().forEach(i -> {
            var mlist = cellList.get(i).getMeasurementList();
            int offset = i * nFeatures;
            for (int j = 0; j < nFeatures; j++) {
                double v = mlist.get(featureNames.get(j));
                matrix[offset + j] = Double.isNaN(v) ? 0f : (float) v;
            }
        });

        return matrix;
    }

    /** @return the ordered list of feature names this extractor uses */
    public List<String> getFeatureNames() {
        return featureNames;
    }

    /** @return number of features (columns) in each extracted row */
    public int getNumFeatures() {
        return featureNames.size();
    }
}
