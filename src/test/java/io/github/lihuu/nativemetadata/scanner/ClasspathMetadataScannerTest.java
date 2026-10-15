package io.github.lihuu.nativemetadata.scanner;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.lihuu.nativemetadata.model.MetadataDocument;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathMetadataScannerTest {

    @Test
    void scansDependencyJarForNativeMetadata(@TempDir Path tempDir) throws IOException {
        Path jarPath = tempDir.resolve("vendor.jar");
        String resourcePath = "META-INF/native-image/vendor/library/reflect-config.json";
        String json = """
                [
                  {
                    "name": "vendor.Message",
                    "allDeclaredMethods": true
                  }
                ]
                """;

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            zos.putNextEntry(new ZipEntry(resourcePath));
            zos.write(json.getBytes());
            zos.closeEntry();
        }

        byte[] before = Files.readAllBytes(jarPath);

        ClasspathMetadataScanner scanner = new ClasspathMetadataScanner();
        ClasspathMetadataResult result = scanner.scan(List.of(jarPath.toString()), List.of());

        assertEquals(1, result.sources().size());
        DiscoveredMetadataSource source = result.sources().get(0);
        assertEquals(resourcePath, source.resource());
        assertEquals(MetadataScope.DEPENDENCY, source.scope());
        assertTrue(source.readOnly());
        assertEquals(1, source.reflectionEntries());

        List<DiscoveredRegistration> registrations = result.registrationsByClass().get("vendor.Message");
        assertNotNull(registrations);
        assertEquals(1, registrations.size());
        assertEquals("vendor.Message", registrations.get(0).entry().name());

        // Verify jar was untouched
        assertEquals(before.length, Files.readAllBytes(jarPath).length);
    }

    @Test
    void dependencyJarWithTheProjectsOwnResourcePathIsNotProjectOwned(@TempDir Path tempDir) throws IOException {
        String resourcePath = "META-INF/native-image/com.example/app/reflect-config.json";
        Path jarPath = tempDir.resolve("vendor.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            zos.putNextEntry(new ZipEntry(resourcePath));
            zos.write("[{\"name\":\"com.example.FromDependency\"}]".getBytes());
            zos.closeEntry();
        }

        Path ownedConfig = tempDir.resolve("src/main/resources").resolve(resourcePath);
        Files.createDirectories(ownedConfig.getParent());
        Files.writeString(ownedConfig, "[{\"name\":\"com.example.Owned\"}]");

        ClasspathMetadataResult result = new ClasspathMetadataScanner()
                .scan(List.of(jarPath.toString()), List.of(ownedConfig));

        assertEquals(1, result.sources().size());
        DiscoveredMetadataSource source = result.sources().get(0);
        assertEquals(MetadataScope.DEPENDENCY, source.scope(),
                "a JAR is a dependency, even when its resource path equals the project's");
        assertNull(source.matchesSource(), "there is no project copy to compare against");
    }

    @Test
    void onlySourcesTheNativeBuildConsumesAreDeletionEvidence(@TempDir Path tempDir) throws IOException {
        String resourcePath = "META-INF/native-image/other/reflect-config.json";
        Path jarPath = tempDir.resolve("provided-or-compile-only.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            zos.putNextEntry(new ZipEntry(resourcePath));
            zos.write("[{\"name\":\"com.example.Foo\",\"allDeclaredMethods\":true}]".getBytes());
            zos.closeEntry();
        }
        Path outputDirectory = tempDir.resolve("target/classes");
        Files.createDirectories(outputDirectory);

        ClasspathMetadataScanner scanner = new ClasspathMetadataScanner();

        // The JAR is on the compile classpath only: it never reaches the native image.
        MetadataSourceScope compileOnly = MetadataSourceScope.of(
                List.of(), List.of(outputDirectory), List.of(outputDirectory.toString()));
        DiscoveredMetadataSource notConsumed = scanner
                .scan(List.of(jarPath.toString()), List.of(), compileOnly)
                .sources().get(0);
        assertEquals(MetadataScope.DEPENDENCY, notConsumed.scope());
        assertFalse(notConsumed.consumedByNativeBuild(),
                "a compile-only dependency must not justify deleting the project's own metadata");

        // The same JAR on the runtime classpath is read by the native build.
        MetadataSourceScope runtime = MetadataSourceScope.of(
                List.of(), List.of(outputDirectory),
                List.of(outputDirectory.toString(), jarPath.toString()));
        DiscoveredMetadataSource consumed = scanner
                .scan(List.of(jarPath.toString()), List.of(), runtime)
                .sources().get(0);
        assertTrue(consumed.consumedByNativeBuild());
    }

}
