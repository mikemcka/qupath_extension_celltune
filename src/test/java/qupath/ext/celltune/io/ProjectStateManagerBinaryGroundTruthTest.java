package qupath.ext.celltune.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectStateManagerBinaryGroundTruthTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsBinaryImportedTrainingRowsRoundTrip() throws Exception {
        Project<BufferedImage> project = fakeProject(tempDir.resolve("source-project/project.qpproj"));

        List<String> featureNames = List.of("feat_a", "feat_b");
        List<GroundTruthIO.TrainingRow> rows = List.of(
                new GroundTruthIO.TrainingRow("CD4_pos", new float[]{1.0f, 2.0f}),
                new GroundTruthIO.TrainingRow("CD4_neg", new float[]{3.5f, 4.5f})
        );

        ProjectStateManager.saveBinaryImportedTrainingData(project, "CD4", featureNames, rows);

        ProjectStateManager.BinaryImportedTrainingData loaded =
                ProjectStateManager.loadBinaryImportedTrainingData(project, "CD4");

        assertNotNull(loaded);
        assertEquals(featureNames, loaded.featureNames());
        assertEquals(2, loaded.rows().size());
        assertEquals("CD4_pos", loaded.rows().get(0).label());
        assertArrayEquals(new float[]{1.0f, 2.0f}, loaded.rows().get(0).features(), 1e-6f);
        assertEquals("CD4_neg", loaded.rows().get(1).label());
        assertArrayEquals(new float[]{3.5f, 4.5f}, loaded.rows().get(1).features(), 1e-6f);
    }

    @Test
    void returnsNullWhenBinaryImportedTrainingDataDoesNotExist() throws Exception {
        Project<BufferedImage> project = fakeProject(tempDir.resolve("target-project/project.qpproj"));
        assertNull(ProjectStateManager.loadBinaryImportedTrainingData(project, "CD8"));
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
