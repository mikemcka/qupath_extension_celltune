package qupath.ext.celltune.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureSelectionPaneTest {

    @Test
    void markerIntensityFeaturesGroupUnderTheirMarker() {
        assertEquals("DAPI_AF", FeatureSelectionPane.groupOf("DAPI_AF: Cell: Mean"));
        assertEquals("DAPI_AF", FeatureSelectionPane.groupOf("DAPI_AF: Cytoplasm: Std.Dev."));
        assertEquals("DAPI_AF", FeatureSelectionPane.groupOf("DAPI_AF: Cell: Percentile: 70.0"));
        assertEquals("DAPI_AF", FeatureSelectionPane.groupOf("DAPI_AF: Cell: ErosionBin_1: Mean"));
        assertEquals("mCherry_S1 - TRITC_AF",
                FeatureSelectionPane.groupOf("mCherry_S1 - TRITC_AF: Membrane: Median"));
        assertEquals("PD-L1_S13 - TRITC_AF",
                FeatureSelectionPane.groupOf("PD-L1_S13 - TRITC_AF: Cell: Max"));
    }

    @Test
    void compartmentOnlyFeaturesAreMorphology() {
        assertEquals(FeatureSelectionPane.GROUP_MORPHOLOGY,
                FeatureSelectionPane.groupOf("Cell: Area µm^2"));
        assertEquals(FeatureSelectionPane.GROUP_MORPHOLOGY,
                FeatureSelectionPane.groupOf("Cell: Circularity"));
        assertEquals(FeatureSelectionPane.GROUP_MORPHOLOGY,
                FeatureSelectionPane.groupOf("Cell: Solidity"));
        assertEquals(FeatureSelectionPane.GROUP_MORPHOLOGY,
                FeatureSelectionPane.groupOf("Cell: ErosionBin_1: Area_px"));
        assertEquals(FeatureSelectionPane.GROUP_MORPHOLOGY,
                FeatureSelectionPane.groupOf("Cell: ErosionBin_2: Area_Fraction"));
    }

    @Test
    void embeddingFeaturesAreDetectedAcrossNamingStyles() {
        assertTrue(FeatureSelectionPane.isEmbedding("UMAP 1"));
        assertTrue(FeatureSelectionPane.isEmbedding("Embedding_0"));
        assertTrue(FeatureSelectionPane.isEmbedding("PCA_2"));
        assertTrue(FeatureSelectionPane.isEmbedding("tSNE 3"));
        assertEquals(FeatureSelectionPane.GROUP_EMBEDDINGS,
                FeatureSelectionPane.groupOf("UMAP 1"));
        // Markers that merely contain the letters must not be misclassified.
        assertFalse(FeatureSelectionPane.isEmbedding("PECAM_S1 - Cy5_AF: Cell: Mean"));
        assertFalse(FeatureSelectionPane.isEmbedding("DAPI_AF: Cell: Mean"));
    }

    @Test
    void leafLabelStripsMarkerPrefixOnlyForMarkerGroups() {
        assertEquals("Cell: Mean",
                FeatureSelectionPane.leafLabel("DAPI_AF: Cell: Mean", "DAPI_AF"));
        assertEquals("Membrane: Std.Dev.",
                FeatureSelectionPane.leafLabel(
                        "mCherry_S1 - TRITC_AF: Membrane: Std.Dev.", "mCherry_S1 - TRITC_AF"));
        // Morphology / embedding labels are left intact.
        assertEquals("Cell: Area µm^2",
                FeatureSelectionPane.leafLabel("Cell: Area µm^2", FeatureSelectionPane.GROUP_MORPHOLOGY));
    }
}
