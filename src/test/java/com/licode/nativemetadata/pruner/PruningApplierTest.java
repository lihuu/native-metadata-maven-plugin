package com.licode.nativemetadata.pruner;

import com.licode.nativemetadata.model.MetadataDocument;
import com.licode.nativemetadata.model.ReflectionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruningApplierTest {

    @Test
    void replacesOutputFileInTargetPreservingSourceAndReachabilityMetadata(@TempDir Path tempDir) throws IOException {
        Path buildDir = tempDir.resolve("target");
        Path outputDir = buildDir.resolve("classes");
        Path srcDir = tempDir.resolve("src/main/resources");

        String resPath = "META-INF/native-image/test/reachability-metadata.json";
        Path srcFile = srcDir.resolve(resPath);
        Path outputFile = outputDir.resolve(resPath);
        Files.createDirectories(srcFile.getParent());
        Files.createDirectories(outputFile.getParent());

        String initialJson = """
                {
                  "reflection": [
                    {
                      "type": "com.example.Foo",
                      "allDeclaredMethods": true
                    }
                  ],
                  "resources": [
                    {"glob": "**"}
                  ]
                }
                """;
        Files.writeString(srcFile, initialJson);
        Files.writeString(outputFile, initialJson);

        byte[] srcBefore = Files.readAllBytes(srcFile);

        MetadataDocument originalDoc = MetadataDocument.parse(initialJson);

        PruningApplier applier = new PruningApplier(buildDir, outputDir, false);
        applier.apply(
                Map.of(resPath, Collections.emptyList()), // prune all reflection entries
                Map.of(resPath, originalDoc),
                Map.of(resPath, srcFile)
        );

        // Source file untouched (AC-5)
        assertEquals(new String(srcBefore), Files.readString(srcFile));

        // Output file replaced: reflection is empty, resources section preserved
        MetadataDocument updatedDoc = MetadataDocument.parse(outputFile);
        assertTrue(updatedDoc.entries().isEmpty());
        assertTrue(updatedDoc.rawRoot().containsKey("resources"));

        // Candidates directory written
        Path candidateFile = buildDir.resolve("native-metadata/candidates").resolve(resPath);
        assertTrue(Files.isRegularFile(candidateFile));
    }

    @Test
    void dryRunDoesNotModifyOutputDirectory(@TempDir Path tempDir) throws IOException {
        Path buildDir = tempDir.resolve("target");
        Path outputDir = buildDir.resolve("classes");
        String resPath = "META-INF/native-image/test/reflect-config.json";
        Path outputFile = outputDir.resolve(resPath);
        Files.createDirectories(outputFile.getParent());
        String initialJson = "[{\"name\": \"foo.Bar\"}]";
        Files.writeString(outputFile, initialJson);

        MetadataDocument doc = MetadataDocument.parse(initialJson);

        PruningApplier applier = new PruningApplier(buildDir, outputDir, true);
        applier.apply(
                Map.of(resPath, Collections.emptyList()),
                Map.of(resPath, doc),
                Map.of(resPath, tempDir.resolve("src").resolve(resPath))
        );

        // Output file remains unchanged in dryRun mode (AC-5)
        assertEquals(initialJson, Files.readString(outputFile));

        // Candidates directory is still written
        Path candidateFile = buildDir.resolve("native-metadata/candidates").resolve(resPath);
        assertTrue(Files.isRegularFile(candidateFile));
    }

    @Test
    void rejectsTargetOutsideBuildDirectory(@TempDir Path tempDir) {
        Path buildDir = tempDir.resolve("target");
        Path outputDir = tempDir.resolve("outside-target");
        PruningApplier applier = new PruningApplier(buildDir, outputDir, false);
        MetadataDocument doc = MetadataDocument.parse("[]");

        assertThrows(IllegalArgumentException.class, () -> applier.apply(
                Map.of("test.json", Collections.emptyList()),
                Map.of("test.json", doc),
                Map.of("test.json", tempDir.resolve("src.json"))
        ));
    }
}
