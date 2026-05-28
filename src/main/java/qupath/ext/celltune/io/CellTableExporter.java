package qupath.ext.celltune.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.interfaces.ROI;

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
 * Exports cell spatial data and marker intensities to CSV.
 * <p>
 * Columns: Image, CellID, CentroidX, CentroidY, Area, Classification,
 * ParentAnnotations, Geometry (WKT polygon), then one column per
 * Cell: Mean feature.
 */
public class CellTableExporter {

    private static final Logger logger = LoggerFactory.getLogger(CellTableExporter.class);
    private static final String DELIMITER = ",";

    private CellTableExporter() {}

    /**
     * Export cell spatial data and Cell: Mean features to CSV.
     *
     * @param outputPath   destination CSV file
     * @param cells        detection objects to export
     * @param imageName    image name written to the Image column
     * @param featureNames ordered list of measurement names to export as feature columns
     * @throws IOException if writing fails
     */
    public static void export(Path outputPath,
                              Collection<PathObject> cells,
                              String imageName,
                              List<String> featureNames) throws IOException {
        logger.info("Exporting cell table ({} features) to {}", featureNames.size(), outputPath);

        String resolvedImageName = (imageName != null && !imageName.isBlank()) ? imageName : "image";

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {

            // ── Header ──────────────────────────────────────────────────
            StringBuilder hdr = new StringBuilder();
            hdr.append(String.join(DELIMITER,
                    "Image",
                    "CellID",
                    "CentroidX",
                    "CentroidY",
                    "Area",
                    "Classification",
                    "ParentAnnotations",
                    "Geometry"));
            for (String feat : featureNames) {
                hdr.append(DELIMITER).append(csvQuote(feat));
            }
            writer.write(hdr.toString());
            writer.newLine();

            // ── Pre-extract features in parallel ────────────────────────
            List<PathObject> cellList = (cells instanceof List<PathObject> l)
                    ? l : new ArrayList<>(cells);
            int nCells = cellList.size();

            float[][] featureRows = new float[nCells][];
            if (!featureNames.isEmpty()) {
                IntStream.range(0, nCells).parallel().forEach(i ->
                        featureRows[i] = extractFeatures(cellList.get(i), featureNames));
            }

            // ── Rows ────────────────────────────────────────────────────
            int exported = 0;
            for (int idx = 0; idx < nCells; idx++) {
                PathObject cell = cellList.get(idx);
                ROI roi = cell.getROI();

                double cx   = roi != null ? roi.getCentroidX() : Double.NaN;
                double cy   = roi != null ? roi.getCentroidY() : Double.NaN;
                double area = roi != null ? roi.getArea()      : Double.NaN;

                String classification = classificationName(cell);
                String parents        = parentAnnotations(cell);
                String geometry       = roiToWkt(roi);

                StringBuilder row = new StringBuilder();
                row.append(resolvedImageName).append(DELIMITER)
                   .append(cell.getID()).append(DELIMITER)
                   .append(fmt(cx)).append(DELIMITER)
                   .append(fmt(cy)).append(DELIMITER)
                   .append(fmt(area)).append(DELIMITER)
                   .append(csvQuote(classification)).append(DELIMITER)
                   .append(csvQuote(parents)).append(DELIMITER)
                   .append(csvQuote(geometry));

                if (!featureNames.isEmpty()) {
                    float[] fv = featureRows[idx];
                    for (float v : fv) {
                        row.append(DELIMITER).append(Float.isNaN(v) ? "" : v);
                    }
                }

                writer.write(row.toString());
                writer.newLine();
                exported++;
            }
            logger.info("Exported {} cells to {}", exported, outputPath);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Extract named measurements from a cell as a float array. */
    private static float[] extractFeatures(PathObject cell, List<String> names) {
        var mlist = cell.getMeasurementList();
        float[] row = new float[names.size()];
        for (int i = 0; i < names.size(); i++) {
            double v = mlist.get(names.get(i));
            row[i] = Double.isNaN(v) ? Float.NaN : (float) v;
        }
        return row;
    }

    /**
     * Walk up the hierarchy collecting all ancestor annotation labels.
     * Each label is formatted as "Name [Class]", "Name", "[Class]", or "Annotation".
     * Multiple parents are joined with "; ".
     */
    private static String parentAnnotations(PathObject cell) {
        List<String> parts = new ArrayList<>();
        PathObject parent = cell.getParent();
        while (parent != null && !parent.isRootObject()) {
            if (parent.isAnnotation()) {
                String name    = parent.getName();
                PathClass cls  = parent.getPathClass();
                String clsName = cls != null ? cls.getName() : null;

                String label;
                if (name != null && !name.isBlank() && clsName != null) {
                    label = name + " [" + clsName + "]";
                } else if (name != null && !name.isBlank()) {
                    label = name;
                } else if (clsName != null) {
                    label = "[" + clsName + "]";
                } else {
                    label = "Annotation";
                }
                parts.add(label);
            }
            parent = parent.getParent();
        }
        return String.join("; ", parts);
    }

    /** Return the cell's PathClass name, or empty string if unclassified. */
    private static String classificationName(PathObject cell) {
        PathClass cls = cell.getPathClass();
        return cls != null ? cls.getName() : "";
    }

    /**
     * Convert a QuPath ROI to a WKT POLYGON string.
     * e.g. {@code POLYGON ((x1 y1, x2 y2, x3 y3, x1 y1))}
     * Returns an empty string for null ROIs or ROIs with no points.
     */
    private static String roiToWkt(ROI roi) {
        if (roi == null) return "";
        var points = roi.getAllPoints();
        if (points == null || points.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("POLYGON ((");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.2f", points.get(i).getX()))
              .append(" ")
              .append(String.format("%.2f", points.get(i).getY()));
        }
        // Close the ring (WKT requires first == last point)
        sb.append(", ")
          .append(String.format("%.2f", points.get(0).getX()))
          .append(" ")
          .append(String.format("%.2f", points.get(0).getY()));
        sb.append("))");
        return sb.toString();
    }

    /** Quote a CSV field if it contains commas, quotes, or newlines. */
    private static String csvQuote(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "" : String.format("%.2f", v);
    }
}
