package qupath.ext.celltune.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.projects.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Import/export utility for transferring binary-marker ground-truth training rows
 * between projects using a single JSON bundle.
 */
public class BinaryGroundTruthBundleIO {

    private static final Logger logger = LoggerFactory.getLogger(BinaryGroundTruthBundleIO.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int BUNDLE_VERSION = 1;

    private BinaryGroundTruthBundleIO() {}

    public enum ImportMode {
        MERGE,
        REPLACE
    }

    public enum MarkerStatus {
        IMPORTED,
        REPLACED,
        SKIPPED,
        FAILED
    }

    public record MarkerResult(String marker,
                               MarkerStatus status,
                               int rowsChanged,
                               String message) {}

    public record ImportReport(int importedMarkers,
                               int replacedMarkers,
                               int skippedMarkers,
                               int failedMarkers,
                               List<MarkerResult> markerResults) {
        public String summaryLine() {
            return String.format("Imported=%d, Replaced=%d, Skipped=%d, Failed=%d",
                    importedMarkers, replacedMarkers, skippedMarkers, failedMarkers);
        }
    }

    private static class BundleDocument {
        int version;
        String exportedAt;
        List<BundleMarker> markers = new ArrayList<>();
    }

    private static class BundleMarker {
        String marker;
        List<String> featureNames;
        List<BundleRow> rows;
    }

    private static class BundleRow {
        String label;
        float[] features;
    }

    /**
     * Export marker-specific imported training rows for all registered binary classifiers.
     */
    public static Path exportBundle(Project<?> project, Path bundlePath) throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        if (bundlePath == null) throw new IllegalArgumentException("bundlePath must not be null");

        Map<String, String> registry = BinaryClassifierRegistry.load(project);

        BundleDocument bundle = new BundleDocument();
        bundle.version = BUNDLE_VERSION;
        bundle.exportedAt = LocalDateTime.now().toString();

        for (String marker : registry.keySet()) {
            ProjectStateManager.BinaryImportedTrainingData data =
                    ProjectStateManager.loadBinaryImportedTrainingData(project, marker);
            if (data == null || data.featureNames() == null || data.featureNames().isEmpty()
                    || data.rows() == null || data.rows().isEmpty()) {
                continue;
            }

            BundleMarker markerDoc = new BundleMarker();
            markerDoc.marker = marker;
            markerDoc.featureNames = List.copyOf(data.featureNames());
            markerDoc.rows = new ArrayList<>();
            for (GroundTruthIO.TrainingRow row : data.rows()) {
                if (row == null || row.label() == null || row.label().isBlank() || row.features() == null) {
                    continue;
                }
                BundleRow out = new BundleRow();
                out.label = row.label();
                out.features = row.features().clone();
                markerDoc.rows.add(out);
            }
            if (!markerDoc.rows.isEmpty()) {
                bundle.markers.add(markerDoc);
            }
        }

        Path parent = bundlePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(bundlePath, GSON.toJson(bundle), StandardCharsets.UTF_8);
        logger.info("Exported binary ground-truth bundle to {} ({} marker payloads)",
                bundlePath, bundle.markers.size());
        return bundlePath;
    }

    /**
     * Import marker-specific training rows from a bundle.
     */
    public static ImportReport importBundle(Project<?> project,
                                            Path bundlePath,
                                            ImportMode mode) throws IOException {
        if (project == null) throw new IllegalArgumentException("project must not be null");
        if (bundlePath == null) throw new IllegalArgumentException("bundlePath must not be null");
        if (mode == null) mode = ImportMode.MERGE;

        String raw = Files.readString(bundlePath, StandardCharsets.UTF_8);
        BundleDocument bundle;
        try {
            bundle = GSON.fromJson(raw, BundleDocument.class);
        } catch (JsonSyntaxException ex) {
            return new ImportReport(0, 0, 0, 1,
                    List.of(new MarkerResult("__bundle__", MarkerStatus.FAILED, 0,
                            "Invalid JSON: " + ex.getMessage())));
        }

        if (bundle == null || bundle.version != BUNDLE_VERSION || bundle.markers == null) {
            return new ImportReport(0, 0, 0, 1,
                    List.of(new MarkerResult("__bundle__", MarkerStatus.FAILED, 0,
                            "Missing/invalid bundle manifest")));
        }

        Map<String, String> registry = new LinkedHashMap<>(BinaryClassifierRegistry.load(project));
        List<MarkerResult> results = new ArrayList<>();
        int imported = 0;
        int replaced = 0;
        int skipped = 0;
        int failed = 0;

        for (BundleMarker markerDoc : bundle.markers) {
            if (markerDoc == null) {
                failed++;
                results.add(new MarkerResult("<null>", MarkerStatus.FAILED, 0, "Null marker payload"));
                continue;
            }

            String safeMarker;
            try {
                safeMarker = BinaryClassifierRegistry.sanitizeMarkerName(markerDoc.marker);
            } catch (Exception ex) {
                failed++;
                results.add(new MarkerResult(String.valueOf(markerDoc.marker), MarkerStatus.FAILED, 0,
                        "Invalid marker name: " + ex.getMessage()));
                continue;
            }

            List<String> featureNames = markerDoc.featureNames == null ? List.of() : List.copyOf(markerDoc.featureNames);
            List<GroundTruthIO.TrainingRow> incomingRows = toTrainingRows(markerDoc.rows);

            if (featureNames.isEmpty() || incomingRows.isEmpty()) {
                failed++;
                results.add(new MarkerResult(safeMarker, MarkerStatus.FAILED, 0,
                        "Marker payload missing feature names or rows"));
                continue;
            }

            ProjectStateManager.BinaryImportedTrainingData existing =
                    ProjectStateManager.loadBinaryImportedTrainingData(project, safeMarker);

            if (mode == ImportMode.MERGE
                    && existing != null
                    && existing.featureNames() != null
                    && !existing.featureNames().isEmpty()
                    && !existing.featureNames().equals(featureNames)) {
                skipped++;
                results.add(new MarkerResult(safeMarker, MarkerStatus.SKIPPED, 0,
                        "Schema mismatch in merge mode"));
                continue;
            }

            List<GroundTruthIO.TrainingRow> rowsToSave = new ArrayList<>();
            MarkerStatus status;

            if (mode == ImportMode.MERGE && existing != null && existing.rows() != null && !existing.rows().isEmpty()) {
                rowsToSave.addAll(copyRows(existing.rows()));
                rowsToSave.addAll(copyRows(incomingRows));
                status = MarkerStatus.IMPORTED;
                imported++;
            } else {
                rowsToSave.addAll(copyRows(incomingRows));
                boolean hadExisting = existing != null && existing.rows() != null && !existing.rows().isEmpty();
                if (mode == ImportMode.REPLACE && hadExisting) {
                    status = MarkerStatus.REPLACED;
                    replaced++;
                } else {
                    status = MarkerStatus.IMPORTED;
                    imported++;
                }
            }

            ProjectStateManager.saveBinaryImportedTrainingData(project, safeMarker, featureNames, rowsToSave);

            if (!registry.containsKey(safeMarker)) {
                BinaryClassifierRegistry.register(project, registry, safeMarker);
            }

            results.add(new MarkerResult(safeMarker, status, rowsToSave.size(),
                    mode == ImportMode.MERGE ? "Merged/imported" : "Replaced/imported"));
        }

        ImportReport report = new ImportReport(imported, replaced, skipped, failed, List.copyOf(results));
        logger.info("Imported binary ground-truth bundle from {} ({})", bundlePath, report.summaryLine());
        return report;
    }

    private static List<GroundTruthIO.TrainingRow> toTrainingRows(List<BundleRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<GroundTruthIO.TrainingRow> out = new ArrayList<>();
        for (BundleRow row : rows) {
            if (row == null || row.label == null || row.label.isBlank() || row.features == null) {
                continue;
            }
            float[] copied = row.features.clone();
            for (int i = 0; i < copied.length; i++) {
                if (!Float.isFinite(copied[i])) {
                    copied[i] = 0f;
                }
            }
            out.add(new GroundTruthIO.TrainingRow(row.label, copied));
        }
        return out;
    }

    private static List<GroundTruthIO.TrainingRow> copyRows(List<GroundTruthIO.TrainingRow> rows) {
        List<GroundTruthIO.TrainingRow> copied = new ArrayList<>(rows.size());
        for (GroundTruthIO.TrainingRow row : rows) {
            copied.add(new GroundTruthIO.TrainingRow(row.label(), row.features().clone()));
        }
        return copied;
    }
}
