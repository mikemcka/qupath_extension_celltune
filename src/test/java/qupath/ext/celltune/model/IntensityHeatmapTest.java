package qupath.ext.celltune.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class IntensityHeatmapTest {

    private static final double DELTA = 1e-9;

    @Test
    void discoverMarkerFeaturesKeepsOnlyCellMeanColumns() {
        List<String> features =
                List.of("CD8: Cell: Mean", "DAPI: Cell: Mean", "Cell: Area µm^2", "CD8: Nucleus: Mean", ": Cell: Mean");
        List<String> markers = IntensityHeatmap.discoverMarkerFeatures(features);
        assertEquals(List.of("CD8: Cell: Mean", "DAPI: Cell: Mean"), markers);
    }

    @Test
    void discoverMarkerFeaturesExcludesDerivedAggregates() {
        List<String> features = List.of(
                "CD8: Cell: Mean",
                "Neighbors: Mean: DAPI: Cell: Mean",
                "Neighbors: Mean: CD8: Cell: Mean",
                "DAPI: Cell: Mean");
        // Only plain "<channel>: Cell: Mean" markers survive — derived
        // neighbour-aggregated features (label contains ":") are dropped.
        List<String> markers = IntensityHeatmap.discoverMarkerFeatures(features);
        assertEquals(List.of("CD8: Cell: Mean", "DAPI: Cell: Mean"), markers);
    }

    @Test
    void discoverMarkerFeaturesHandlesNull() {
        assertTrue(IntensityHeatmap.discoverMarkerFeatures(null).isEmpty());
    }

    @Test
    void markerLabelStripsSuffix() {
        assertEquals("CD8", IntensityHeatmap.markerLabel("CD8: Cell: Mean"));
        assertEquals("FOXP3", IntensityHeatmap.markerLabel("FOXP3: Cell: Mean"));
        // No suffix: returned unchanged.
        assertEquals("Area", IntensityHeatmap.markerLabel("Area"));
    }

    @Test
    void zScoreByColumnStandardisesAcrossRows() {
        double[][] means = {
            {1.0}, {2.0}, {3.0},
        };
        // population mean = 2, population std = sqrt(2/3)
        double std = Math.sqrt(2.0 / 3.0);
        double[][] z = IntensityHeatmap.zScoreByColumn(means);
        assertEquals((1.0 - 2.0) / std, z[0][0], DELTA);
        assertEquals(0.0, z[1][0], DELTA);
        assertEquals((3.0 - 2.0) / std, z[2][0], DELTA);
        // Mean of z-scores is ~0.
        assertEquals(0.0, (z[0][0] + z[1][0] + z[2][0]) / 3.0, DELTA);
    }

    @Test
    void zScoreByColumnZeroVarianceYieldsZero() {
        double[][] means = {
            {5.0}, {5.0}, {5.0},
        };
        double[][] z = IntensityHeatmap.zScoreByColumn(means);
        assertEquals(0.0, z[0][0], DELTA);
        assertEquals(0.0, z[1][0], DELTA);
        assertEquals(0.0, z[2][0], DELTA);
    }

    @Test
    void zScoreByColumnNaNStaysNaN() {
        double[][] means = {
            {1.0, Double.NaN},
            {3.0, 4.0},
        };
        double[][] z = IntensityHeatmap.zScoreByColumn(means);
        assertTrue(Double.isNaN(z[0][1]));
        // Column 1 has a single valid row -> count < 2 -> 0.0 for finite value.
        assertEquals(0.0, z[1][1], DELTA);
    }

    @Test
    void accumulatorComputesPooledMeans() {
        var acc = new IntensityHeatmap.Accumulator(List.of("CD8: Cell: Mean", "FOXP3: Cell: Mean"));
        assertEquals(2, acc.markerCount());

        acc.addRow("Tumour", new double[] {10.0, 2.0});
        acc.addRow("Tumour", new double[] {20.0, 4.0});
        acc.addRow("Treg", new double[] {1.0, 8.0});

        IntensityHeatmap.Result r = acc.build();
        assertEquals(List.of("Treg", "Tumour"), r.classNames());
        assertEquals(List.of("CD8", "FOXP3"), r.markers());

        int tumour = r.classNames().indexOf("Tumour");
        int treg = r.classNames().indexOf("Treg");
        assertEquals(15.0, r.meanIntensity()[tumour][0], DELTA);
        assertEquals(3.0, r.meanIntensity()[tumour][1], DELTA);
        assertEquals(1.0, r.meanIntensity()[treg][0], DELTA);
        assertEquals(8.0, r.meanIntensity()[treg][1], DELTA);

        assertEquals(2L, r.classCounts()[tumour]);
        assertEquals(1L, r.classCounts()[treg]);
    }

    @Test
    void accumulatorSkipsNaNPerMarker() {
        var acc = new IntensityHeatmap.Accumulator(List.of("CD8: Cell: Mean"));
        acc.addRow("A", new double[] {10.0});
        acc.addRow("A", new double[] {Double.NaN});
        IntensityHeatmap.Result r = acc.build();
        // Only the finite value contributes to the mean.
        assertEquals(10.0, r.meanIntensity()[0][0], DELTA);
        // But both cells count toward the class total.
        assertEquals(2L, r.classCounts()[0]);
    }

    @Test
    void accumulatorEmptyMarkerColumnIsNaN() {
        var acc = new IntensityHeatmap.Accumulator(List.of("CD8: Cell: Mean"));
        acc.addRow("A", new double[] {Double.NaN});
        IntensityHeatmap.Result r = acc.build();
        assertTrue(Double.isNaN(r.meanIntensity()[0][0]));
    }

    @Test
    void accumulatorSortsUnclassifiedLast() {
        var acc = new IntensityHeatmap.Accumulator(List.of("CD8: Cell: Mean"));
        acc.addRow("Zeta", new double[] {1.0});
        acc.addRow(null, new double[] {2.0});
        acc.addRow("Alpha", new double[] {3.0});
        IntensityHeatmap.Result r = acc.build();
        assertEquals(List.of("Alpha", "Zeta", IntensityHeatmap.UNCLASSIFIED), r.classNames());
    }
}
