package qupath.ext.celltune.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BinaryGroundTruthBundleIOTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsAndImportsBundleAcrossProjects() throws Exception {
        Project<BufferedImage> source = fakeProject(tempDir.resolve("source/project.qpproj"));
        Project<BufferedImage> target = fakeProject(tempDir.resolve("target/project.qpproj"));

        LinkedHashMap<String, String> sourceRegistry = new LinkedHashMap<>();
        BinaryClassifierRegistry.register(source, sourceRegistry, "CD4");
        BinaryClassifierRegistry.register(source, sourceRegistry, "CD8");

        ProjectStateManager.saveBinaryImportedTrainingData(
                source,
                "CD4",
                List.of("f1", "f2"),
                List.of(
                        new GroundTruthIO.TrainingRow("CD4_pos", new float[]{1f, 2f}),
                        new GroundTruthIO.TrainingRow("CD4_neg", new float[]{3f, 4f})
                )
        );
        ProjectStateManager.saveBinaryImportedTrainingData(
                source,
                "CD8",
                List.of("f1", "f2"),
                List.of(new GroundTruthIO.TrainingRow("CD8_pos", new float[]{5f, 6f}))
        );

        Path bundle = tempDir.resolve("binary-ground-truth-bundle.json");
        BinaryGroundTruthBundleIO.exportBundle(source, bundle);
        assertTrue(Files.exists(bundle));

        BinaryGroundTruthBundleIO.ImportReport report =
                BinaryGroundTruthBundleIO.importBundle(target, bundle, BinaryGroundTruthBundleIO.ImportMode.MERGE);

        assertEquals(2, report.importedMarkers());
        assertEquals(0, report.replacedMarkers());
        assertEquals(0, report.failedMarkers());

        var targetRegistry = BinaryClassifierRegistry.load(target);
        assertTrue(targetRegistry.containsKey("CD4"));
        assertTrue(targetRegistry.containsKey("CD8"));

        var cd4 = ProjectStateManager.loadBinaryImportedTrainingData(target, "CD4");
        assertNotNull(cd4);
        assertEquals(List.of("f1", "f2"), cd4.featureNames());
        assertEquals(2, cd4.rows().size());
        assertEquals("CD4_pos", cd4.rows().get(0).label());

        var cd8 = ProjectStateManager.loadBinaryImportedTrainingData(target, "CD8");
        assertNotNull(cd8);
        assertEquals(1, cd8.rows().size());
        assertEquals("CD8_pos", cd8.rows().get(0).label());
    }

    @Test
    void replaceModeOverwritesExistingRowsForMarker() throws Exception {
        Project<BufferedImage> source = fakeProject(tempDir.resolve("source-replace/project.qpproj"));
        Project<BufferedImage> target = fakeProject(tempDir.resolve("target-replace/project.qpproj"));

        LinkedHashMap<String, String> sourceRegistry = new LinkedHashMap<>();
        BinaryClassifierRegistry.register(source, sourceRegistry, "CD4");

        ProjectStateManager.saveBinaryImportedTrainingData(
                source,
                "CD4",
                List.of("f1", "f2"),
                List.of(new GroundTruthIO.TrainingRow("NEW", new float[]{9f, 9f}))
        );

        LinkedHashMap<String, String> targetRegistry = new LinkedHashMap<>();
        BinaryClassifierRegistry.register(target, targetRegistry, "CD4");
        ProjectStateManager.saveBinaryImportedTrainingData(
                target,
                "CD4",
                List.of("f1", "f2"),
                List.of(new GroundTruthIO.TrainingRow("OLD", new float[]{1f, 1f}))
        );

        Path bundle = tempDir.resolve("replace-bundle.json");
        BinaryGroundTruthBundleIO.exportBundle(source, bundle);

        BinaryGroundTruthBundleIO.ImportReport report =
                BinaryGroundTruthBundleIO.importBundle(target, bundle, BinaryGroundTruthBundleIO.ImportMode.REPLACE);

        assertEquals(0, report.importedMarkers());
        assertEquals(1, report.replacedMarkers());
        assertEquals(0, report.failedMarkers());

        var cd4 = ProjectStateManager.loadBinaryImportedTrainingData(target, "CD4");
        assertNotNull(cd4);
        assertEquals(1, cd4.rows().size());
        assertEquals("NEW", cd4.rows().get(0).label());
        assertArrayEquals(new float[]{9f, 9f}, cd4.rows().get(0).features(), 1e-6f);
    }

    @Test
    void malformedBundleFailsWithoutMutatingUntouchedMarkers() throws Exception {
        Project<BufferedImage> target = fakeProject(tempDir.resolve("target-malformed/project.qpproj"));

        LinkedHashMap<String, String> targetRegistry = new LinkedHashMap<>();
        BinaryClassifierRegistry.register(target, targetRegistry, "CD8");
        ProjectStateManager.saveBinaryImportedTrainingData(
                target,
                "CD8",
                List.of("f1", "f2"),
                List.of(new GroundTruthIO.TrainingRow("KEEP", new float[]{7f, 8f}))
        );

        Path malformed = tempDir.resolve("malformed-bundle.json");
        Files.writeString(malformed, "{\"version\":1,\"markers\":[{\"marker\":\"bad/marker\"}]}", StandardCharsets.UTF_8);

        BinaryGroundTruthBundleIO.ImportReport report =
                BinaryGroundTruthBundleIO.importBundle(target, malformed, BinaryGroundTruthBundleIO.ImportMode.MERGE);

        assertEquals(0, report.importedMarkers());
        assertEquals(0, report.replacedMarkers());
        assertTrue(report.failedMarkers() >= 1);

        var cd8 = ProjectStateManager.loadBinaryImportedTrainingData(target, "CD8");
        assertNotNull(cd8);
        assertEquals(1, cd8.rows().size());
        assertEquals("KEEP", cd8.rows().get(0).label());
    }

    @SuppressWarnings("unchecked")
    private static Project<BufferedImage> fakeProject(Path projectFile) throws IOException {
        Files.createDirectories(projectFile.getParent());
        if (!Files.exists(projectFile)) {
            Files.createFile(projectFile);
        }

        return (Project<BufferedImage>) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class[]{Project.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    return switch (name) {
                        case "getPath" -> projectFile;
                        case "toString" -> "FakeProject(" + projectFile + ")";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException("Method not implemented in fake project: " + name);
                    };
                }
        );
    }
}
