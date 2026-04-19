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
 * Exports cell data in AnnData-compatible CSV format that can be directly
 * loaded by Python's {@code anndata.read_csv()} or converted to H5AD.
 * <p>
 * The output format matches Python CellTune's {@code write_to_anndata()}:
 * <ul>
 *   <li>Index column: {@code fov__cellID} (unique cell identifier)</li>
 *   <li>Feature columns: all measurement features (the X matrix in AnnData)</li>
 *   <li>Metadata columns: fov, cellID, centroids, population sets, ground truth</li>
 * </ul>
 * A companion Python conversion script is also generated alongside the CSV.
 */
public class AnnDataExporter {

    private static final Logger logger = LoggerFactory.getLogger(AnnDataExporter.class);
    private static final String DELIMITER = ",";

    private AnnDataExporter() {}

    /**
     * Export cell data in AnnData-compatible CSV format.
     *
     * @param outputPath   path for the output CSV file (should end in .csv)
     * @param cells        all cell PathObjects
     * @param extractor    feature extractor with column names
     * @param predictions  Pred_ALL population set (may be null if not yet trained)
     * @param labelStore   ground-truth labels (may be null)
     * @param imageName    image/FOV name for the fov column
     * @throws IOException if writing fails
     */
    public static void export(Path outputPath,
                              Collection<PathObject> cells,
                              CellFeatureExtractor extractor,
                              PopulationSet predictions,
                              LabelStore labelStore,
                              String imageName) throws IOException {

        List<String> featureNames = extractor.getFeatureNames();
        String fov = imageName != null ? imageName : "image";

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {

            // Header: unique_id, fov, cellID, features..., centroids, population sets, ground truth
            StringBuilder header = new StringBuilder();
            header.append("unique_id").append(DELIMITER);
            header.append("fov").append(DELIMITER);
            header.append("cellID");

            // Feature columns
            for (String feat : featureNames) {
                header.append(DELIMITER).append(escapeCsv(feat));
            }

            // Centroid columns (AnnData spatial)
            header.append(DELIMITER).append("Centroid_X__Cell__RegionProps");
            header.append(DELIMITER).append("Centroid_Y__Cell__RegionProps");

            // Population set columns
            boolean hasPreds = predictions != null;
            if (hasPreds) {
                header.append(DELIMITER).append("PopulationSet__Pred_MDL1");
                header.append(DELIMITER).append("PopulationSet__Pred_MDL2");
                header.append(DELIMITER).append("PopulationSet__Pred_AVG");
                header.append(DELIMITER).append("PopulationSet__Pred_ALL");
            }

            // Ground truth
            header.append(DELIMITER).append("GroundTruth");

            writer.write(header.toString());
            writer.newLine();

            int exported = 0;
            for (PathObject cell : cells) {
                String cellId = cell.getID().toString();
                String uniqueId = fov + "__" + cellId;

                StringBuilder row = new StringBuilder();
                row.append(escapeCsv(uniqueId)).append(DELIMITER);
                row.append(escapeCsv(fov)).append(DELIMITER);
                row.append(escapeCsv(cellId));

                // Feature values
                float[] features = extractor.extractRow(cell);
                for (float v : features) {
                    row.append(DELIMITER);
                    if (Float.isNaN(v)) {
                        row.append("");
                    } else {
                        row.append(v);
                    }
                }

                // Centroids
                var roi = cell.getROI();
                double cx = roi != null ? roi.getCentroidX() : Double.NaN;
                double cy = roi != null ? roi.getCentroidY() : Double.NaN;
                row.append(DELIMITER).append(Double.isNaN(cx) ? "" : String.format("%.2f", cx));
                row.append(DELIMITER).append(Double.isNaN(cy) ? "" : String.format("%.2f", cy));

                // Population sets
                if (hasPreds) {
                    CellPrediction pred = predictions.get(cellId);
                    if (pred != null) {
                        row.append(DELIMITER).append(pred.getModel1Label());
                        row.append(DELIMITER).append(pred.getModel2Label());
                        row.append(DELIMITER).append(pred.avgLabel());
                        row.append(DELIMITER).append(pred.allLabel());
                    } else {
                        row.append(DELIMITER).append(DELIMITER).append(DELIMITER).append(DELIMITER);
                    }
                }

                // Ground truth
                String gt = labelStore != null ? labelStore.getLabel(cellId) : null;
                row.append(DELIMITER).append(gt != null ? escapeCsv(gt) : "");

                writer.write(row.toString());
                writer.newLine();
                exported++;
            }

            logger.info("Exported {} cells in AnnData-compatible format to {}", exported, outputPath);
        }

        // Generate companion Python conversion script
        generateConversionScript(outputPath);
    }

    /**
     * Generate a Python script alongside the CSV that converts it to H5AD.
     */
    private static void generateConversionScript(Path csvPath) throws IOException {
        String csvName = csvPath.getFileName().toString();
        String h5adName = csvName.replace(".csv", ".h5ad");
        Path scriptPath = csvPath.resolveSibling("convert_to_h5ad.py");

        String script = """
                #!/usr/bin/env python3
                \"\"\"Convert CellTune AnnData-compatible CSV to H5AD format.\"\"\"
                import pandas as pd
                import anndata
                import numpy as np
                from pathlib import Path
                
                csv_path = Path(__file__).parent / "%s"
                h5ad_path = Path(__file__).parent / "%s"
                
                print(f"Reading {csv_path}...")
                data = pd.read_csv(csv_path, index_col="unique_id")
                
                # Identify column types
                centroid_cols = [c for c in data.columns if c.startswith("Centroid_") and "__Cell__RegionProps" in c]
                pop_set_cols = [c for c in data.columns if c.startswith("PopulationSet__")]
                meta_cols = ["fov", "cellID", "GroundTruth"] + centroid_cols + pop_set_cols
                feature_cols = [c for c in data.columns if c not in meta_cols]
                
                # Build AnnData
                X = data[feature_cols].to_numpy(dtype=float)
                adata = anndata.AnnData(X)
                adata.var_names = feature_cols
                adata.obs_names = data.index
                
                # Metadata
                for col in ["fov", "cellID", "GroundTruth"]:
                    if col in data.columns:
                        adata.obs[col] = data[col].values
                
                # Population sets as categorical
                for col in pop_set_cols:
                    adata.obs[col] = data[col].astype("category")
                
                # Spatial coordinates
                if len(centroid_cols) == 2:
                    adata.obsm["spatial"] = data[centroid_cols].to_numpy(dtype=float)
                
                adata.write_h5ad(h5ad_path)
                print(f"AnnData written to: {h5ad_path} ({adata.n_obs} cells, {adata.n_vars} features)")
                """.formatted(csvName, h5adName);

        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
        logger.info("Generated H5AD conversion script: {}", scriptPath);
    }

    private static String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
