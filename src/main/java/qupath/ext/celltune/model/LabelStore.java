package qupath.ext.celltune.model;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages ground-truth cell labels — the human-assigned class for each cell.
 * <p>
 * This is the central data structure that accumulates labels across review cycles.
 * Labels from landmarking, gating, and review mode all flow into the same
 * {@code LabelStore}, which then feeds the next training cycle.
 * <p>
 * Serialisable to/from JSON via Gson (all fields are simple maps/strings).
 */
public class LabelStore {

    private final String name;
    private final Map<String, String> labels;  // cellId → class name

    /**
     * Create an empty label store.
     *
     * @param name descriptive name (e.g. "Landmarks", "Labels_Classifier_1")
     */
    public LabelStore(String name) {
        this.name = name;
        this.labels = new LinkedHashMap<>();
    }

    /**
     * Create a label store pre-populated with labels.
     *
     * @param name   descriptive name
     * @param labels initial cellId → class name map (copied)
     */
    public LabelStore(String name, Map<String, String> labels) {
        this.name = name;
        this.labels = new LinkedHashMap<>(labels);
    }

    /** Assign a class label to a cell. Overwrites any existing label. */
    public void setLabel(String cellId, String className) {
        labels.put(cellId, className);
    }

    /** Remove a cell's label. */
    public void removeLabel(String cellId) {
        labels.remove(cellId);
    }

    /** Get a cell's label, or null if unlabelled. */
    public String getLabel(String cellId) {
        return labels.get(cellId);
    }

    /** @return true if the cell has a label in this store */
    public boolean hasLabel(String cellId) {
        return labels.containsKey(cellId);
    }

    /** @return unmodifiable view of all cellId → label mappings */
    public Map<String, String> getAllLabels() {
        return Collections.unmodifiableMap(labels);
    }

    /** @return total number of labelled cells */
    public int size() {
        return labels.size();
    }

    /** @return set of all distinct class names that have at least one labelled cell */
    public Set<String> getClassNames() {
        return labels.values().stream().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** @return count of labelled cells per class */
    public Map<String, Long> getClassCounts() {
        return labels.values().stream()
                .collect(Collectors.groupingBy(c -> c, LinkedHashMap::new, Collectors.counting()));
    }

    /** @return all cell IDs that have a specific class label */
    public List<String> getCellsWithLabel(String className) {
        return labels.entrySet().stream()
                .filter(e -> className.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** Merge all labels from another store into this one. Existing labels are overwritten. */
    public void mergeFrom(LabelStore other) {
        labels.putAll(other.labels);
    }

    /**
     * Remove all labels whose class name is not in the given set of valid classes.
     *
     * @param validClasses the set of class names to keep
     * @return number of labels removed
     */
    public int retainClasses(Set<String> validClasses) {
        int before = labels.size();
        labels.entrySet().removeIf(e -> !validClasses.contains(e.getValue()));
        return before - labels.size();
    }

    /**
     * Rename all labels from one class name to another.
     *
     * @param oldName the old class name
     * @param newName the new class name
     * @return number of labels renamed
     */
    public int renameClass(String oldName, String newName) {
        int count = 0;
        for (var entry : labels.entrySet()) {
            if (oldName.equals(entry.getValue())) {
                entry.setValue(newName);
                count++;
            }
        }
        return count;
    }

    /** Create a deep copy of this label store. */
    public LabelStore copy() {
        return new LabelStore(name, labels);
    }

    public String getName() { return name; }

    @Override
    public String toString() {
        return "LabelStore[" + name + ", " + labels.size() + " labels, "
                + getClassNames().size() + " classes]";
    }
}
