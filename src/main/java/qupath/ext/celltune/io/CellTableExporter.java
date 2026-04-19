package qupath.ext.celltune.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.celltune.model.CellFeatureExtractor;
import qupath.ext.celltune.model.CellPrediction;
import qupath.ext.celltune.model.LabelStore;
import qupath.ext.celltune.model.PopulationSet;
import qupath.lib.objects.PathObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Exports cell predictions, labels, and optionally feature values to CSV.
 * <p>
 * Core columns: CellID, GroundTruth, predictions, confidence, centroids.
 * When an extractor is provided, raw feature values are appended.
 * When the extractor also has a normaliser, normalised feature values
 * (suffixed {@code __norm}) are appended after the raw columns.
 */
public class CellTableExporter {

    private static final Logger logger = LoggerFactory.getLogger(CellTableExporter.class);
    private static final String DELIMITER = ",";

    private CellTableExporter() {} // utility class

    /**
     * Export predictions for all cells to a CSV file (without feature values).
     */
    public static void export(Path outputPath,
                              Collection<PathObject> cells,
                              PopulationSet predictions,
                              LabelStore labelStore) throws IOException {
        export(outputPath, cells, predictions, labelStore, null);
    }

    /**
     * Export predictions for all cells to a CSV file, optionally including
     * raw feature values and (when a normaliser is active) normalised values.
     *
     * @param outputPath   path for the output CSV file
     * @param cells        collection of cell PathObjects
     * @param predictions  the Pred_ALL population set with all cell predictions
     * @param labelStore   ground-truth labels (may be null)
     * @param extractor    feature extractor (may be null — features omitted)
     * @throws IOException if writing fails
     */
    public static void export(Path outputPath,
                              Collection<PathObject> cells,
                              PopulationSet predictions,
                              LabelStore labelStore,
                              CellFeatureExtractor extractor) throws IOException {
        export(outputPath, cells, predictions, labelStore, extractor, true, true);
    }

    /**
     * Export predictions with configurable raw/normalised feature columns.
     *
     * @param includeRaw   include raw feature columns
     * @param includeNorm  include normalised feature columns (suffixed __norm)
     */
    public static void export(Path outputPath,
                              Collection<PathObject> cells,
                              PopulationSet predictions,
                              LabelStore labelStore,
                              CellFeatureExtractor extractor,
                              boolean includeRaw,
                              boolean includeNorm) throws IOException {
        logger.info("Exporting cell table to {}", outputPath);

        boolean hasFeatures = extractor != null;
        boolean writeRaw = hasFeatures && includeRaw;
        boolean hasNorm = hasFeatures && includeNorm && extractor.getNormalizer() != null;
        List<String> featureNames = hasFeatures ? extractor.getFeatureNames() : List.of();

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            // Header
                StringBuilder hdr = new StringBuilder();
                hdr.append(String.join(DELIMITER,
                    "Image",
                    "CellID",
                    "GroundTruth",
                    "Pred_MDL1",
                    "Pred_MDL2",
                    "Pred_AVG",
                    "Pred_ALL",
                    "Model1_Confidence",
                    "Model2_Confidence",
                    "Disagreement",
                    "CentroidX",
                    "CentroidY"));
            if (writeRaw) {
                for (String feat : featureNames) {
                    hdr.append(DELIMITER).append(feat);
                }
            }
            if (hasNorm) {
                for (String feat : featureNames) {
                    hdr.append(DELIMITER).append(feat).append("__norm");
                }
            }
            writer.write(hdr.toString());
            writer.newLine();

            List<PathObject> cellList = (cells instanceof List)
                    ? (List<PathObject>) cells
                    : new ArrayList<>(cells);
            int nCells = cellList.size();

            // Pre-extract features in parallel if applicable
            float[][] rawRows = null;
            float[][] normRows = null;
            if (writeRaw || hasNorm) {
                if (writeRaw) {
                    rawRows = new float[nCells][];
                    float[][] rr = rawRows;
                    IntStream.range(0, nCells).parallel().forEach(i ->
                            rr[i] = extractor.extractRowRaw(cellList.get(i)));
                }
                if (hasNorm) {
                    normRows = new float[nCells][];
                    float[][] nr = normRows;
                    IntStream.range(0, nCells).parallel().forEach(i ->
                            nr[i] = extractor.extractRow(cellList.get(i)));
                }
            }

            int exported = 0;
            for (int idx = 0; idx < nCells; idx++) {
                PathObject cell = cellList.get(idx);
                String cellId = cell.getID().toString();
                CellPrediction pred = predictions.get(cellId);

                // Get cell centroid
                var roi = cell.getROI();
                double cx = roi != null ? roi.getCentroidX() : Double.NaN;
                double cy = roi != null ? roi.getCentroidY() : Double.NaN;

                // Ground truth label
                String gt = "";
                if (labelStore != null) {
                    String label = labelStore.getLabel(cellId);
                    if (label != null) gt = label;
                }
                String imageName = null;
                if (cell.getMeasurementList().containsKey("Image")) {
                    imageName = String.valueOf(cell.getMeasurementList().get("Image"));
                }
                if (imageName == null) imageName = "image";
                StringBuilder row = new StringBuilder();
                row.append(imageName).append(DELIMITER);
                if (pred != null) {
                    row.append(String.join(DELIMITER,
                            cellId,
                            gt,
                            pred.getModel1Label(),
                            pred.getModel2Label(),
                            pred.avgLabel(),
                            pred.allLabel(),
                            String.format("%.4f", pred.model1Confidence()),
                            String.format("%.4f", pred.model2Confidence()),
                            String.valueOf(pred.isDisagreement()),
                            String.format("%.2f", cx),
                            String.format("%.2f", cy)));
                } else {
                    // Cell without predictions — still export with coordinates + ground truth
                    row.append(String.join(DELIMITER,
                            cellId,
                            gt,
                            "", "", "", "",
                            "", "",
                            "",
                            String.format("%.2f", cx),
                            String.format("%.2f", cy)));
                }

                // Raw feature values
                if (writeRaw) {
                    float[] raw = rawRows[idx];
                    for (float v : raw) {
                        row.append(DELIMITER).append(v);
                    }
                }
                // Normalized feature values
                if (hasNorm) {
                    float[] norm = normRows[idx];
                    for (float v : norm) {
                        row.append(DELIMITER).append(v);
                    }
                }

                writer.write(row.toString());
                writer.newLine();
                exported++;
            }
            logger.info("Exported {} cells to {}", exported, outputPath);
        }
    }
}
