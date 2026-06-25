package qupath.ext.celltune.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the pre-extracted ({@code float[][]}) pruning overload used to
 * prune the full, normalised cross-image training matrix (current + pooled rows)
 * rather than one image's raw measurements.
 */
class FeaturePrunerTest {

    // minNonZeroCells=1, within-marker r>0.95, cross-marker off, guardrail off
    private static final FeaturePruner.PruneOptions OPTS = new FeaturePruner.PruneOptions(1, 0.95, 1.0, false);

    @Test
    void dropsNearConstantColumn() {
        List<String> names = List.of("CD3: Mean", "CD8: Mean");
        // CD3 varies across rows; CD8 is constant (zero variance).
        float[][] rows = {
            {1f, 5f}, {2f, 5f}, {3f, 5f}, {4f, 5f}, {5f, 5f}, {6f, 5f},
        };
        FeaturePruner.PruneResult pr = FeaturePruner.prune(rows, names, OPTS, null);
        assertEquals(List.of("CD3: Mean"), pr.keptFeatures());
        assertEquals(1, pr.droppedConstant());
    }

    @Test
    void dropsRedundantWithinMarker() {
        List<String> names = List.of("CD3: Mean", "CD3: Median", "CD8: Mean");
        // CD3 Median == 2 * CD3 Mean -> perfectly correlated within the CD3 group.
        // CD8 is independent and in its own group.
        float[][] rows = {
            {1f, 2f, 9f}, {2f, 4f, 1f}, {3f, 6f, 7f}, {4f, 8f, 2f}, {5f, 10f, 8f}, {6f, 12f, 3f},
        };
        FeaturePruner.PruneResult pr = FeaturePruner.prune(rows, names, OPTS, null);
        assertEquals(1, pr.droppedWithinMarker());
        assertEquals(2, pr.keptFeatures().size());
        assertTrue(pr.keptFeatures().contains("CD8: Mean"));
        // Exactly one of the two correlated CD3 features survives.
        assertTrue(pr.keptFeatures().contains("CD3: Mean") ^ pr.keptFeatures().contains("CD3: Median"));
    }

    @Test
    void keepsFeatureThatOnlyVariesAcrossPooledRows() {
        // The whole point of pruning after pooling: a feature flat within the first
        // image's cells but varying once other images are pooled must be kept.
        List<String> names = List.of("CD3: Mean", "CD20: Mean");
        // CD20 is constant in the first 4 rows (the "current image") but varies in the
        // last 4 ("pooled" rows) -> across the full matrix it is NOT near-constant.
        float[][] rows = {
            {1f, 7f}, {2f, 7f}, {3f, 7f}, {4f, 7f}, {5f, 2f}, {6f, 9f}, {7f, 1f}, {8f, 8f},
        };
        FeaturePruner.PruneResult pr = FeaturePruner.prune(rows, names, OPTS, null);
        assertTrue(pr.keptFeatures().contains("CD20: Mean"), "feature varying across pooled rows must survive");
        assertEquals(0, pr.droppedConstant());

        // Sanity: judged on only the first image's rows, CD20 IS near-constant and dropped.
        float[][] currentOnly = {{1f, 7f}, {2f, 7f}, {3f, 7f}, {4f, 7f}};
        FeaturePruner.PruneResult prCurrent = FeaturePruner.prune(currentOnly, names, OPTS, null);
        assertFalse(
                prCurrent.keptFeatures().contains("CD20: Mean"),
                "feature flat within current image alone is dropped — the bug this reorder fixes");
    }

    @Test
    void emptyRowsKeepsAllFeatures() {
        List<String> names = List.of("CD3: Mean", "CD8: Mean");
        FeaturePruner.PruneResult pr =
                FeaturePruner.prune(new float[0][], names, FeaturePruner.PruneOptions.defaults(), null);
        assertEquals(names, pr.keptFeatures());
    }

    @Test
    void rejectsRaggedRows() {
        List<String> names = List.of("a", "b");
        float[][] rows = {{1f, 2f}, {3f}};
        assertThrows(
                IllegalArgumentException.class,
                () -> FeaturePruner.prune(rows, names, FeaturePruner.PruneOptions.defaults(), null));
    }
}
