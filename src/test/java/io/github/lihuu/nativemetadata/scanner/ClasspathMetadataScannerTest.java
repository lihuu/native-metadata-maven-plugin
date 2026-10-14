package io.github.lihuu.nativemetadata.scanner;

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
}
