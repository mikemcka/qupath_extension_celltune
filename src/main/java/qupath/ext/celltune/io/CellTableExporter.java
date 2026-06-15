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
 * Columns: Image, CellID, CentroidX_um, CentroidY_um, Area_um2, Classification,
 * ParentAnnotations, ContainingAnnotations, Geometry_um (WKT polygon in microns),
 * then one column per feature name supplied by the caller.
 * <p>
 * {@code ParentAnnotations} reflects QuPath's single-parent hierarchy (each cell
 * has exactly one parent path). {@code ContainingAnnotations} is computed by an
 * explicit geometric point-in-polygon test of the cell centroid against every
 * annotation, so it captures membership in overlapping annotations (e.g. Ignore
 * regions) that the hierarchy discards.
 * <p>
 * Centroids, area and geometry are written in microns: the {@code "Centroid X µm"} /
 * {@code "Centroid Y µm"} / {@code "Cell: Area µm^2"} measurements are used when
 * present, otherwise the pixel values are converted using the supplied pixel
 * calibration. The polygon vertices are always scaled from pixels to microns
 * using the supplied pixel calibration. Any measurement that cannot be made is
 * written as {@code NA}.
 */
public class CellTableExporter {

    private static final Logger logger = LoggerFactory.getLogger(CellTableExporter.class);
    private static final String DELIMITER = ",";

    private CellTableExporter() {}

    /**
     * Export cell spatial data and Cell: Mean features to CSV.
     *
     * @param outputPath         destination CSV file
     * @param cells              detection objects to export
     * @param annotations        annotation objects tested for geometric containment
     * @param imageName          image name written to the Image column
     * @param featureNames       ordered list of measurement names to export as feature columns
     * @param pixelWidthMicrons  microns per pixel in X (centroid fallback conversion)
     * @param pixelHeightMicrons microns per pixel in Y (centroid fallback conversion)
     * @throws IOException if writing fails
     */
    public static void export(Path outputPath,
                              Collection<PathObject> cells,
                              Collection<PathObject> annotations,
                              String imageName,
                              List<String> featureNames,
                              double pixelWidthMicrons,
                              double pixelHeightMicrons) throws IOException {
        logger.info("Exporting cell table ({} features) to {}", featureNames.size(), outputPath);

        String resolvedImageName = (imageName != null && !imageName.isBlank()) ? imageName : "image";

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {

            // ── Header ──────────────────────────────────────────────────
            StringBuilder hdr = new StringBuilder();
            hdr.append(String.join(DELIMITER,
                    "Image",
                    "CellID",
                    "CentroidX_um",
                    "CentroidY_um",
                    "Area_um2",
                    "Classification",
                    "ParentAnnotations",
                    "ContainingAnnotations",
                    "Geometry_um"));
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

            // ── Pre-compute geometric containment in parallel ───────────
            // Test each cell's pixel centroid against every annotation ROI so
            // that membership in overlapping annotations (e.g. Ignore regions)
            // is captured, unlike the single-parent hierarchy.
            List<PathObject> annoList = annotations == null
                    ? List.of()
                    : new ArrayList<>(annotations);
            int nAnnos = annoList.size();
            ROI[] annoRois = new ROI[nAnnos];
            String[] annoLabels = new String[nAnnos];
            for (int a = 0; a < nAnnos; a++) {
                annoRois[a] = annoList.get(a).getROI();
                annoLabels[a] = annotationLabel(annoList.get(a));
            }
            String[] containingRows = new String[nCells];
            if (nAnnos > 0) {
                IntStream.range(0, nCells).parallel().forEach(i ->
                        containingRows[i] = containingAnnotations(
                                cellList.get(i), annoRois, annoLabels));
            }

            // ── Rows ────────────────────────────────────────────────────
            int exported = 0;
            for (int idx = 0; idx < nCells; idx++) {
                PathObject cell = cellList.get(idx);
                ROI roi = cell.getROI();

                // Prefer QuPath's calibrated centroid measurements; fall back to
                // converting the pixel centroid with the supplied calibration.
                var ml = cell.getMeasurementList();
                double cx = ml.get("Centroid X µm");
                double cy = ml.get("Centroid Y µm");
                if (Double.isNaN(cx)) {
                    cx = roi != null ? roi.getCentroidX() * pixelWidthMicrons : Double.NaN;
                }
                if (Double.isNaN(cy)) {
                    cy = roi != null ? roi.getCentroidY() * pixelHeightMicrons : Double.NaN;
                }
                // Prefer QuPath's calibrated cell area (µm²); fall back to
                // converting the pixel area with the supplied pixel calibration.
                double area = ml.get("Cell: Area µm^2");
                if (Double.isNaN(area)) {
                    area = roi != null
                            ? roi.getArea() * pixelWidthMicrons * pixelHeightMicrons
                            : Double.NaN;
                }

                String classification = classificationName(cell);
                String parents        = parentAnnotations(cell);
                String containing     = (containingRows[idx] != null) ? containingRows[idx] : "";
                String geometry       = roiToWkt(roi, pixelWidthMicrons, pixelHeightMicrons);

                StringBuilder row = new StringBuilder();
                row.append(resolvedImageName).append(DELIMITER)
                   .append(cell.getID()).append(DELIMITER)
                   .append(fmt(cx)).append(DELIMITER)
                   .append(fmt(cy)).append(DELIMITER)
                   .append(fmt(area)).append(DELIMITER)
                   .append(csvQuote(classification)).append(DELIMITER)
                   .append(csvQuote(parents)).append(DELIMITER)
                   .append(csvQuote(containing)).append(DELIMITER)
                   .append(csvQuote(geometry));

                if (!featureNames.isEmpty()) {
                    float[] fv = featureRows[idx];
                    for (float v : fv) {
                        row.append(DELIMITER).append(Float.isNaN(v) ? "NA" : v);
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
                parts.add(annotationLabel(parent));
            }
            parent = parent.getParent();
        }
        return String.join("; ", parts);
    }

    /**
     * Return every annotation whose ROI geometrically contains the cell's pixel
     * centroid, formatted with {@link #annotationLabel} and joined with "; ".
     * Unlike {@link #parentAnnotations}, this captures overlapping annotations
     * (e.g. Ignore regions) that QuPath's single-parent hierarchy discards.
     * Containment is tested in pixel coordinates, so no calibration is needed.
     */
    private static String containingAnnotations(PathObject cell, ROI[] annoRois, String[] annoLabels) {
        ROI roi = cell.getROI();
        if (roi == null) return "";
        double px = roi.getCentroidX();
        double py = roi.getCentroidY();
        if (Double.isNaN(px) || Double.isNaN(py)) return "";
        List<String> parts = new ArrayList<>();
        for (int a = 0; a < annoRois.length; a++) {
            ROI ar = annoRois[a];
            if (ar != null && ar.contains(px, py)) {
                parts.add(annoLabels[a]);
            }
        }
        return String.join("; ", parts);
    }

    /**
     * Format an annotation's label as "Name [Class]", "Name", "[Class]", or
     * "Annotation" when neither a name nor a class is present.
     */
    private static String annotationLabel(PathObject annotation) {
        String name    = annotation.getName();
        PathClass cls  = annotation.getPathClass();
        String clsName = cls != null ? cls.getName() : null;
        if (name != null && !name.isBlank() && clsName != null) {
            return name + " [" + clsName + "]";
        } else if (name != null && !name.isBlank()) {
            return name;
        } else if (clsName != null) {
            return "[" + clsName + "]";
        }
        return "Annotation";
    }

    /** Return the cell's PathClass name, or empty string if unclassified. */
    private static String classificationName(PathObject cell) {
        PathClass cls = cell.getPathClass();
        return cls != null ? cls.getName() : "";
    }

    /**
     * Convert a QuPath ROI to a WKT POLYGON string with vertices in microns.
     * e.g. {@code POLYGON ((x1 y1, x2 y2, x3 y3, x1 y1))}
     * Each pixel vertex is scaled by the supplied pixel calibration.
     * Returns an empty string for null ROIs or ROIs with no points.
     */
    private static String roiToWkt(ROI roi, double pixelWidthMicrons, double pixelHeightMicrons) {
        if (roi == null) return "";
        var points = roi.getAllPoints();
        if (points == null || points.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("POLYGON ((");
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.2f", points.get(i).getX() * pixelWidthMicrons))
              .append(" ")
              .append(String.format("%.2f", points.get(i).getY() * pixelHeightMicrons));
        }
        // Close the ring (WKT requires first == last point)
        sb.append(", ")
          .append(String.format("%.2f", points.get(0).getX() * pixelWidthMicrons))
          .append(" ")
          .append(String.format("%.2f", points.get(0).getY() * pixelHeightMicrons));
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
        return Double.isNaN(v) ? "NA" : String.format("%.2f", v);
    }
}
