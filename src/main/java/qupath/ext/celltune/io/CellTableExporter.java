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
import java.util.Collection;
import java.util.List;

/**
 * Exports cell predictions and labels to a CSV file.
 * <p>
 * Columns:
 * <ol>
 *   <li>CellID</li>
 *   <li>GroundTruth — human-assigned label (empty if unlabelled)</li>
 *   <li>Pred_MDL1 — XGBoost prediction</li>
 *   <li>Pred_MDL2 — LightGBM prediction</li>
 *   <li>Pred_AVG — averaged prediction</li>
 *   <li>Pred_ALL — combined prediction (agreed or "A/B")</li>
 *   <li>Model1_Confidence — max probability from model 1</li>
 *   <li>Model2_Confidence — max probability from model 2</li>
 *   <li>Disagreement — true/false</li>
 *   <li>CentroidX — cell centroid X coordinate</li>
 *   <li>CentroidY — cell centroid Y coordinate</li>
 * </ol>
 */
public class CellTableExporter {

    private static final Logger logger = LoggerFactory.getLogger(CellTableExporter.class);
    private static final String DELIMITER = ",";

    private CellTableExporter() {} // utility class

    /**
     * Export predictions for all cells to a CSV file.
     *
     * @param outputPath   path for the output CSV file
     * @param cells        collection of cell PathObjects (for coordinates)
     * @param predictions  the Pred_ALL population set with all cell predictions
     * @param labelStore   ground-truth labels (may be null if no labels available)
     * @throws IOException if writing fails
     */
    public static void export(Path outputPath,
                              Collection<PathObject> cells,
                              PopulationSet predictions,
                              LabelStore labelStore) throws IOException {
        logger.info("Exporting cell table to {}", outputPath);

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            // Header
            writer.write(String.join(DELIMITER,
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
            writer.newLine();

            int exported = 0;
            for (PathObject cell : cells) {
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

                if (pred != null) {
                    writer.write(String.join(DELIMITER,
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
                    writer.write(String.join(DELIMITER,
                            cellId,
                            gt,
                            "", "", "", "",
                            "", "",
                            "",
                            String.format("%.2f", cx),
                            String.format("%.2f", cy)));
                }
                writer.newLine();
                exported++;
            }
            logger.info("Exported {} cells to {}", exported, outputPath);
        }
    }
}
