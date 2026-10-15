package io.github.lihuu.nativemetadata.scanner;

import io.github.lihuu.nativemetadata.bytecode.BytecodeInventory;
import io.github.lihuu.nativemetadata.bytecode.ClassfileScanner;
import io.github.lihuu.nativemetadata.support.JavaFixtureCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-release JAR behaviour, following the JAR file specification rather than a convenient
 * approximation of it.
 *
 * <p>Two separate rules are covered:</p>
 * <ul>
 *   <li>A versioned <em>class</em> entry is selected when its version is at most the runtime version,
 *       which is what a class loader would load. The result is allowed to differ between Maven JVMs;
 *       what is asserted is that it follows the running version.</li>
 *   <li>Resources under {@code META-INF} are <em>not</em> versioned. A
 *       {@code META-INF/versions/N/META-INF/native-image/...} file is not a registration any JVM (or
 *       native-image) will read, so the scanner must not treat it as one - least of all as a covering
 *       registration that would justify deleting the project's own metadata.</li>
 * </ul>
 */
class MrJarMetadataSelectionTest {

    private static final String PROBE_ENTRY = "example/Probe.class";
    private static final String METADATA_PATH = "META-INF/native-image/probe/reflect-config.json";

    @Test
    void selectsTheVersionedClassForTheRunningJvm(@TempDir Path tempDir) throws IOException {
        Path jar = writeVersionedClassJar(tempDir);

        BytecodeInventory inventory = new ClassfileScanner().scan(List.of(jar.toString()),
                Set.of("example.V17", "example.V21", "example.Base"));

        int feature = Runtime.version().feature();
        String expected = (feature >= 21) ? "example.V21" : "example.V17";
        assertNotNull(inventory.findClass(expected).orElse(null),
                "expected the class compiled for the running version (" + expected + ")");
        assertTrue(inventory.findClass("example.V17").isPresent() ^ inventory.findClass("example.V21").isPresent(),
                "exactly one versioned class must be present");
    }

    @Test
    void metadataUnderMetaInfIsNeverVersioned(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("mr-metadata.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), multiReleaseManifest())) {
            put(out, METADATA_PATH, "[{\"name\":\"example.FromBase\"}]");
            // Invalid per the JAR specification: META-INF resources are not versioned. It must be
            // ignored, not read as an override.
            put(out, "META-INF/versions/17/" + METADATA_PATH, "[{\"name\":\"example.Illegal17\"}]");
            put(out, "META-INF/versions/21/" + METADATA_PATH, "[{\"name\":\"example.Illegal21\"}]");
        }

        ClasspathMetadataResult result = new ClasspathMetadataScanner().scan(List.of(jar.toString()), List.of());

        assertNotNull(result.registrationsByClass().get("example.FromBase"),
                "the unversioned metadata must be used, got " + result.registrationsByClass().keySet());
        assertNull(result.registrationsByClass().get("example.Illegal17"),
                "META-INF/native-image is not a versionable resource");
        assertNull(result.registrationsByClass().get("example.Illegal21"),
                "META-INF/native-image is not a versionable resource");
        assertEquals(1, result.sources().size(),
                "only the unversioned resource is a source, got " + result.sources());
    }

    private Path writeVersionedClassJar(Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Path src = tempDir.resolve("src/example");
        Files.createDirectories(src);
        Path v17 = src.resolve("V17.java");
        Path v21 = src.resolve("V21.java");
        Path base = src.resolve("Base.java");
        Files.writeString(v17, "package example; public class V17 { public String where() { return \"17\"; } }");
        Files.writeString(v21, "package example; public class V21 { public String where() { return \"21\"; } }");
        Files.writeString(base, "package example; public class Base { public String where() { return \"base\"; } }");
        JavaFixtureCompiler.compile(classes, List.of(v17, v21, base));

        byte[] v17Bytes = Files.readAllBytes(classes.resolve("example/V17.class"));
        byte[] v21Bytes = Files.readAllBytes(classes.resolve("example/V21.class"));
        byte[] baseBytes = Files.readAllBytes(classes.resolve("example/Base.class"));

        Path jar = tempDir.resolve("mr-classes.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), multiReleaseManifest())) {
            put(out, PROBE_ENTRY, baseBytes);
            put(out, "META-INF/versions/17/" + PROBE_ENTRY, v17Bytes);
            put(out, "META-INF/versions/21/" + PROBE_ENTRY, v21Bytes);
        }
        return jar;
    }

    private static Manifest multiReleaseManifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Multi-Release"), "true");
        return manifest;
    }

    private static void put(JarOutputStream out, String name, String content) throws IOException {
        put(out, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(JarOutputStream out, String name, byte[] content) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }
}
