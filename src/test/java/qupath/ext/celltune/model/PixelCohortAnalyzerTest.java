package qupath.ext.celltune.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelCohortAnalyzerTest {

    /** Build a 2-channel image whose channels share the given summary values. */
    private static ImagePixelStats.ImageStats image(
            String name, double fgCoverage, double dynamicRange,
            double saturationFraction, double emptyFraction, double median) {
        double bg = 1.0 - fgCoverage;
        double p1 = 10.0;
        double p99 = p1 + dynamicRange;
        var channels = new ArrayList<ImagePixelStats.ChannelStats>();
        for (String ch : List.of("DAPI", "CD8")) {
            channels.add(new ImagePixelStats.ChannelStats(
                    ch, 1_000_000L,
                    median,            // mean (≈ median for the test)
                    dynamicRange / 4,  // std
                    0.0,               // min
                    median,            // median
                    p99 + 1,           // max
                    p1, p99,
                    saturationFraction,
                    50.0,              // otsu threshold
                    bg, fgCoverage,
                    dynamicRange));
        }
        return new ImagePixelStats.ImageStats(name, 4.0, 1024, 1024, channels, emptyFraction);
    }

    /** Six tightly-clustered normal images. */
    private static List<ImagePixelStats.ImageStats> normalCohort() {
        var list = new ArrayList<ImagePixelStats.ImageStats>();
        for (int i = 0; i < 6; i++) {
            double jitter = i * 0.3;
            list.add(image("normal-" + i,
                    0.60 + i * 0.004,   // fg coverage ~0.60
                    190.0 + jitter,     // dynamic range ~190
                    0.0,                // no saturation
                    0.30 + i * 0.004,   // empty fraction ~0.30
                    100.0 + jitter));   // median ~100
        }
        return list;
    }

    @Test
    void flagsBackgroundHeavyImage() {
        var inputs = normalCohort();
        inputs.add(image("mostly-background",
                0.08,    // very low foreground coverage
                190.0,   // normal dynamic range
                0.0,
                0.90,    // very high empty fraction
                100.0));

        var report = PixelCohortAnalyzer.analyze(inputs);
        var r = report.byImageName().get("mostly-background");

        assertNotNull(r);
        assertTrue(r.flags().contains(PixelCohortReport.BACKGROUND_HEAVY),
                "flags=" + r.flags());
        assertEquals("Background-heavy", r.verdict());
        assertTrue(r.narrative().toLowerCase().contains("background"));
        // Worst image sorts to the top.
        assertEquals("mostly-background", report.images().get(0).imageName());
    }

    @Test
    void flagsSaturatedImageEvenWhenBaselineIsZero() {
        var inputs = normalCohort();   // all 0% saturation
        inputs.add(image("over-exposed",
                0.60, 190.0,
                0.25,    // 25% of pixels clipped
                0.30, 100.0));

        var report = PixelCohortAnalyzer.analyze(inputs);
        var r = report.byImageName().get("over-exposed");

        assertNotNull(r);
        assertTrue(r.flags().contains(PixelCohortReport.SATURATED), "flags=" + r.flags());
        assertTrue(List.of("DAPI", "CD8").contains(r.maxSaturationChannel()));
        assertTrue(r.maxSaturationFraction() >= 0.01);
    }

    @Test
    void flagsWeakSignalImage() {
        var inputs = normalCohort();
        inputs.add(image("washed-out",
                0.60,
                4.0,     // almost no dynamic range
                0.0, 0.30, 100.0));

        var report = PixelCohortAnalyzer.analyze(inputs);
        var r = report.byImageName().get("washed-out");

        assertNotNull(r);
        assertTrue(r.flags().contains(PixelCohortReport.WEAK_SIGNAL), "flags=" + r.flags());
    }

    @Test
    void leavesNormalImagesUnflagged() {
        var report = PixelCohortAnalyzer.analyze(normalCohort());
        for (var r : report.images()) {
            assertFalse(r.flagged(), r.imageName() + " should not be flagged: " + r.flags());
            assertEquals(PixelCohortReport.VERDICT_OK, r.verdict());
        }
    }

    @Test
    void emptyInputYieldsEmptyReport() {
        assertTrue(PixelCohortAnalyzer.analyze(List.of()).images().isEmpty());
        assertTrue(PixelCohortAnalyzer.analyze(null).images().isEmpty());
    }

    @Test
    void robustZFallsBackToStdWhenMadIsZero() {
        // Seven zeros and one clear outlier: MAD is 0, so the std fallback must
        // still produce a large positive z for the outlier.
        double[] vals = {0, 0, 0, 0, 0, 0, 0, 0.25};
        double[] z = PixelCohortAnalyzer.robustZ(vals);
        assertTrue(z[7] > 2.5, "outlier z=" + z[7]);
    }
}
