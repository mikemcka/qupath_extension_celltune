package qupath.ext.celltune.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LabelStoreTest {

    @Test
    void setAndGetLabelRoundTrip() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        assertEquals("T", store.getLabel("cell-1"));
    }

    @Test
    void getLabelReturnsNullForUnlabelledCell() {
        var store = new LabelStore("test");
        assertNull(store.getLabel("cell-99"));
    }

    @Test
    void hasLabelReturnsTrueAfterSet() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "NK");
        assertTrue(store.hasLabel("cell-1"));
        assertFalse(store.hasLabel("cell-2"));
    }

    @Test
    void removeLabelRemovesEntry() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        store.removeLabel("cell-1");
        assertFalse(store.hasLabel("cell-1"));
        assertNull(store.getLabel("cell-1"));
        assertEquals(0, store.size());
    }

    @Test
    void setLabelOverwritesExisting() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        store.setLabel("cell-1", "NK");
        assertEquals("NK", store.getLabel("cell-1"));
        assertEquals(1, store.size());
    }

    @Test
    void getClassNamesReturnsDistinctClasses() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        store.setLabel("cell-2", "NK");
        store.setLabel("cell-3", "T");
        var classes = store.getClassNames();
        assertEquals(2, classes.size());
        assertTrue(classes.contains("T"));
        assertTrue(classes.contains("NK"));
    }

    @Test
    void getClassCountsReturnsAccurateCounts() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        store.setLabel("cell-2", "T");
        store.setLabel("cell-3", "NK");
        var counts = store.getClassCounts();
        assertEquals(2L, counts.get("T"));
        assertEquals(1L, counts.get("NK"));
    }

    @Test
    void getCellsWithLabelReturnsMatchingCells() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        store.setLabel("cell-2", "NK");
        store.setLabel("cell-3", "T");
        var tCells = store.getCellsWithLabel("T");
        assertEquals(2, tCells.size());
        assertTrue(tCells.contains("cell-1"));
        assertTrue(tCells.contains("cell-3"));
        assertTrue(store.getCellsWithLabel("B").isEmpty());
    }

    @Test
    void mergeFromOverwritesDuplicateLabels() {
        var store = new LabelStore("primary");
        store.setLabel("cell-1", "T");
        store.setLabel("cell-2", "NK");

        var other = new LabelStore("secondary");
        other.setLabel("cell-2", "B");  // overwrites "NK"
        other.setLabel("cell-3", "Macro");

        store.mergeFrom(other);

        assertEquals("T", store.getLabel("cell-1"));
        assertEquals("B", store.getLabel("cell-2"));
        assertEquals("Macro", store.getLabel("cell-3"));
        assertEquals(3, store.size());
    }

    @Test
    void retainClassesRemovesInvalidAndReturnsCount() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        store.setLabel("cell-2", "NK");
        store.setLabel("cell-3", "T");

        int removed = store.retainClasses(Set.of("T"));

        assertEquals(1, removed);
        assertEquals(2, store.size());
        assertFalse(store.hasLabel("cell-2"));
        assertTrue(store.hasLabel("cell-1"));
        assertTrue(store.hasLabel("cell-3"));
    }

    @Test
    void renameClassUpdatesLabelsAndReturnsCount() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        store.setLabel("cell-2", "T");
        store.setLabel("cell-3", "NK");

        int count = store.renameClass("T", "Tcell");

        assertEquals(2, count);
        assertEquals("Tcell", store.getLabel("cell-1"));
        assertEquals("Tcell", store.getLabel("cell-2"));
        assertEquals("NK", store.getLabel("cell-3"));
    }

    @Test
    void getAllLabelsReturnsUnmodifiableView() {
        var store = new LabelStore("test", Map.of("cell-1", "T"));
        var all = store.getAllLabels();
        assertEquals(1, all.size());
        assertThrows(UnsupportedOperationException.class, () -> all.put("cell-2", "NK"));
    }

    @Test
    void copyCreatesIndependentCopy() {
        var original = new LabelStore("test");
        original.setLabel("cell-1", "T");

        var copy = original.copy();
        copy.setLabel("cell-2", "NK");

        assertFalse(original.hasLabel("cell-2"));
        assertEquals(1, original.size());
        assertEquals(2, copy.size());
    }

    @Test
    void constructorWithMapPrePopulatesLabels() {
        var store = new LabelStore("seeded", Map.of("c1", "A", "c2", "B"));
        assertEquals(2, store.size());
        assertEquals("A", store.getLabel("c1"));
        assertEquals("B", store.getLabel("c2"));
    }

    @Test
    void retainClassesWithEmptySetRemovesAll() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        store.setLabel("cell-2", "NK");
        int removed = store.retainClasses(Set.of());
        assertEquals(2, removed);
        assertEquals(0, store.size());
    }

    @Test
    void renameClassWhenOldNameAbsentReturnsZero() {
        var store = new LabelStore("test");
        store.setLabel("cell-1", "T");
        int count = store.renameClass("NonExistent", "New");
        assertEquals(0, count);
        // original label unchanged
        assertEquals("T", store.getLabel("cell-1"));
    }
}
