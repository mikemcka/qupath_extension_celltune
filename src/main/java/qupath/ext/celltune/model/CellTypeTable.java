package qupath.ext.celltune.model;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Maps a cell-type name to up to 3 marker channel names.
 * <p>
 * Loaded from a simple CSV file with format:
 * <pre>
 * CellType,Marker1,Marker2,Marker3
 * T-Cell,CD3,,
 * B-Cell,CD20,,
 * Macrophage,CD68,CD163,
 * </pre>
 * If fewer than 3 markers are present, the trailing entries are empty/null.
 */
public class CellTypeTable {

    /** Maximum number of marker channels per cell type. */
    public static final int MAX_MARKERS = 3;

    private final Map<String, List<String>> table; // cellType → markers (1-3)

    public CellTypeTable() {
        this.table = new LinkedHashMap<>();
    }

    /**
     * Define a cell type with its marker channels.
     *
     * @param cellType the cell type name (e.g. "T-Cell")
     * @param markers  marker channel names (at most {@value MAX_MARKERS})
     */
    public void put(String cellType, List<String> markers) {
        List<String> trimmed = new ArrayList<>();
        for (int i = 0; i < Math.min(markers.size(), MAX_MARKERS); i++) {
            String m = markers.get(i);
            if (m != null && !m.isBlank()) {
                trimmed.add(m.strip());
            }
        }
        table.put(cellType, Collections.unmodifiableList(trimmed));
    }

    /** @return marker channels for the given cell type, or empty list */
    public List<String> getMarkers(String cellType) {
        return table.getOrDefault(cellType, Collections.emptyList());
    }

    /** @return all cell type names in insertion order */
    public Set<String> getCellTypes() {
        return Collections.unmodifiableSet(table.keySet());
    }

    /** @return number of defined cell types */
    public int size() {
        return table.size();
    }

    /** @return true if the table contains no entries */
    public boolean isEmpty() {
        return table.isEmpty();
    }

    /**
     * Load a CellTypeTable from a CSV file.
     * The first row is treated as a header and skipped.
     *
     * @param path path to the CSV file
     * @return the loaded table
     * @throws IOException if the file cannot be read
     */
    public static CellTypeTable loadFromCSV(Path path) throws IOException {
        CellTypeTable tbl = new CellTypeTable();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine(); // skip header
            if (header == null) return tbl;

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",", -1);
                if (parts.length < 1) continue;

                String cellType = parts[0].strip();
                if (cellType.isEmpty()) continue;

                List<String> markers = new ArrayList<>();
                for (int i = 1; i < parts.length && i <= MAX_MARKERS; i++) {
                    markers.add(parts[i].strip());
                }
                tbl.put(cellType, markers);
            }
        }
        return tbl;
    }

    /**
     * Save this table to a CSV file.
     *
     * @param path path to write
     * @throws IOException if writing fails
     */
    public void saveToCSV(Path path) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("CellType,Marker1,Marker2,Marker3");
            writer.newLine();
            for (var entry : table.entrySet()) {
                StringBuilder sb = new StringBuilder();
                sb.append(entry.getKey());
                List<String> markers = entry.getValue();
                for (int i = 0; i < MAX_MARKERS; i++) {
                    sb.append(',');
                    if (i < markers.size()) {
                        sb.append(markers.get(i));
                    }
                }
                writer.write(sb.toString());
                writer.newLine();
            }
        }
    }

    @Override
    public String toString() {
        return "CellTypeTable[" + table.size() + " types]";
    }
}
