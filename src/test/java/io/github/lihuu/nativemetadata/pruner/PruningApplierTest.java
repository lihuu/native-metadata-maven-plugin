package io.github.lihuu.nativemetadata.pruner;

import io.github.lihuu.nativemetadata.model.MetadataDocument;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
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

    @Test
    void doesNotPartiallyApplyWhenOneDocumentCannotBeSerialized(@TempDir Path tempDir) throws IOException {
        Path buildDir = tempDir.resolve("target");
        Path outputDir = buildDir.resolve("classes");
        String resA = "META-INF/native-image/test/a/reflect-config.json";
        String resB = "META-INF/native-image/test/b/reflect-config.json";

        String jsonA = "[{\"name\": \"com.example.A\"}]";
        String jsonB = "[{\"name\": \"com.example.B\"}, {\"broken\": true}]";
        for (String[] pair : new String[][]{{resA, jsonA}, {resB, jsonB}}) {
            Path file = outputDir.resolve(pair[0]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, pair[1]);
        }

        PruningApplier applier = new PruningApplier(buildDir, outputDir, false);
        Map<String, MetadataDocument> docs = Map.of(
                resA, MetadataDocument.parse(jsonA),
                resB, MetadataDocument.parse(jsonB));

        assertThrows(IllegalStateException.class, () -> applier.apply(
                Map.of(resA, Collections.emptyList(), resB, Collections.emptyList()),
                docs,
                Map.of()));

        // Nothing may have been written: a partially pruned tree is worse than an untouched one.
        assertEquals(jsonA, Files.readString(outputDir.resolve(resA)));
        assertEquals(jsonB, Files.readString(outputDir.resolve(resB)));
        assertFalse(Files.exists(buildDir.resolve("native-metadata/candidates")),
                "candidates must not be written when the plan cannot be completed");
    }

    @Test
    void rejectsSymlinkedOutputBeforeAnyWrite(@TempDir Path tempDir) throws IOException {
        Path build = tempDir.resolve("target");
        Path source = tempDir.resolve("src/main/resources");
        Files.createDirectories(build);
        Files.createDirectories(source);
        Path protectedFile = source.resolve("reflect-config.json");
        Files.writeString(protectedFile, "source must survive");
        Files.createSymbolicLink(build.resolve("classes"), source);
        PruningApplier applier = new PruningApplier(build, build.resolve("classes"), false);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(
                Map.of("reflect-config.json", List.of()),
                Map.of("reflect-config.json", MetadataDocument.parse("[]")), Map.of()));
        assertEquals("source must survive", Files.readString(protectedFile));
        assertFalse(Files.exists(build.resolve("native-metadata/candidates")));
    }

    @Test
    void rejectsSymlinkedCandidateDirectoryInDryRun(@TempDir Path tempDir) throws IOException {
        Path build = tempDir.resolve("target");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(build.resolve("native-metadata"));
        Files.createDirectories(outside);
        Files.createSymbolicLink(build.resolve("native-metadata/candidates"), outside);
        PruningApplier applier = new PruningApplier(build, build.resolve("classes"), true);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(
                Map.of("reflect-config.json", List.of()),
                Map.of("reflect-config.json", MetadataDocument.parse("[]")), Map.of()));
        assertFalse(Files.exists(outside.resolve("reflect-config.json")));
    }

    @Test
    void rejectsResourcePathTraversalInDryRun(@TempDir Path tempDir) {
        Path build = tempDir.resolve("target");
        String resource = "../../../escape.json";
        PruningApplier applier = new PruningApplier(build, build.resolve("classes"), true);
        assertThrows(IllegalArgumentException.class, () -> applier.apply(
                Map.of(resource, List.of()), Map.of(resource, MetadataDocument.parse("[]")), Map.of()));
        assertFalse(Files.exists(tempDir.resolve("escape.json")));
    }

}
