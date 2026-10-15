package io.github.lihuu.nativemetadata.bytecode;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reads class files newer than the running JVM.
 *
 * <p>The fixtures are produced by {@code scripts/build-high-version-fixtures.sh} on the newest
 * supported JDK and handed to this test through {@code -Dnative.metadata.test.highVersionFixtures}.
 * When the property is set explicitly the fixtures are required: a missing or unreadable fixture
 * fails the test rather than being skipped, because "the low-JVM job silently skipped the
 * interesting input" is exactly the false green this suite is meant to prevent.</p>
 */
class HighVersionInputTest {

    private static final String FIXTURE_PROPERTY = "native.metadata.test.highVersionFixtures";

    @Test
    void readsClassFilesNewerThanTheRunningJvmWithoutLoadingThem() throws Exception {
        Path root = fixtureRoot();
        boolean explicitlyConfigured = System.getProperty(FIXTURE_PROPERTY) != null;

        List<Path> jars = listFixtures(root);
        if (explicitlyConfigured) {
            assertFalse(jars.isEmpty(), "native.metadata.test.highVersionFixtures=" + root + " contains no highversion-*.jar");
        } else {
            assumeTrue(!jars.isEmpty(), "No pre-generated high-version fixtures under " + root
                    + "; run scripts/build-high-version-fixtures.sh (see docs/validation/toolchains.json)");
        }

        Path marker = root.resolve("initializer-marker.txt");
        Files.deleteIfExists(marker);
        System.setProperty("highversion.marker", marker.toString());
        try {
            boolean sawNewerThanRuntime = false;
            boolean sawHigherThanBaseline = false;
            for (Path jar : jars) {
                int major = majorVersionOfFirstClass(jar);
                boolean newerThanRuntime = major > currentMajorVersion();
                sawNewerThanRuntime |= newerThanRuntime;
                sawHigherThanBaseline |= major > 61;

                BytecodeInventory inventory = new ClassfileScanner()
                        .scan(List.of(jar.toString()), Set.of(
                                "example.HighVersionProbe", "example.HighVersionRecord", "example.HighVersion21"));

                ClassInventory probe = inventory.findClass("example.HighVersionProbe").orElse(null);
                ClassInventory record = inventory.findClass("example.HighVersionRecord").orElse(null);
                if (major < 65) {
                    // The 17 artifact excludes the probe on purpose: its source needs Java 21 APIs.
                    assertNotNull(record, jar + ": record fixture must be readable");
                } else {
                    assertNotNull(probe, jar + ": probe fixture must be readable");
                    assertTrue(probe.methods().stream().anyMatch(m -> m.name().equals("describe")),
                            jar + ": expected the varargs describe() method");
                    assertTrue(probe.methods().stream().anyMatch(m -> m.name().equals("weights")
                                    && m.descriptor().equals("()[I")),
                            jar + ": expected the int[]-returning weights() method");
                    assertTrue(probe.fields().stream().anyMatch(f -> f.name().equals("weights")
                                    && f.descriptor().equals("[I")),
                            jar + ": expected the int[] field descriptor to survive");
                    assertTrue(inventory.findClass("example.HighVersion21").isPresent(),
                            jar + ": Java 21 API class must be readable");
                    List<MemberReference> references = inventory.findOrdinaryReferences("example.HighVersionRecord");
                    assertTrue(references.stream().anyMatch(r -> r.member().equals("<init>")
                                    && r.callerMethod().equals("of")),
                            jar + ": record factory must reference its own constructor, got " + references);
                    assertNotNull(record, jar + ": record fixture must be readable");
                    assertEquals(List.of("name", "values"),
                            record.recordComponents().stream().map(RecordComponentInventory::name).toList(),
                            jar + ": record components must be readable");
                    assertTrue(record.isRecord(), jar + ": record flag must be recognised");
                    assertTrue(record.annotations().contains("Lexample/VisibleBinding;"),
                            jar + ": runtime-visible annotation must be listed, got " + record.annotations());
                    assertTrue(record.annotations().contains("Lexample/InvisibleBinding;"),
                            jar + ": runtime-invisible annotation must be listed, got " + record.annotations());
                    assertEquals(2, inventory.findAnnotationReferences("example.HighVersionProbe").size(),
                            jar + ": both annotations point at HighVersionProbe");
                }

                if (newerThanRuntime) {
                    assertUnloadable(jar);
                }
            }
            // What the fixtures have to prove is that a class file *above the Java 17 baseline* was
            // read. "Newer than the running JVM" is stronger and is what makes a low-JVM run
            // interesting, but on the newest supported JDK (25) nothing can be newer than the
            // runtime, so requiring that there would fail the build job for the wrong reason.
            if (explicitlyConfigured) {
                assertTrue(sawHigherThanBaseline,
                        "No fixture with a class-file version above the Java 17 baseline was found in " + root);
                if (Runtime.version().feature() < 25) {
                    assertTrue(sawNewerThanRuntime,
                            "No fixture newer than the running JVM (" + Runtime.version() + ") was found in " + root);
                }
            } else {
                assumeTrue(sawHigherThanBaseline,
                        "No pre-generated high-version fixtures under " + root
                                + "; run scripts/build-high-version-fixtures.sh");
            }
        } finally {
            System.clearProperty("highversion.marker");
        }
        assertFalse(Files.exists(marker), "Static initializer ran: the scanner loaded/initialized a fixture");
    }

    /** Proves the fixture really is too new for this JVM, rather than trusting the file name. */
    private void assertUnloadable(Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            Throwable thrown = assertThrows(Throwable.class, () -> Class.forName("example.HighVersionProbe", true, loader));
            // Older JVMs throw UnsupportedClassVersionError directly; newer ones wrap it in
            // ClassNotFoundException. Accept either, but not something unrelated.
            Throwable cause = (thrown instanceof ClassNotFoundException && thrown.getCause() != null)
                    ? thrown.getCause()
                    : thrown;
            assertTrue(cause instanceof UnsupportedClassVersionError,
                    "Expected UnsupportedClassVersionError for " + jar + " but got " + thrown);
        }
    }

    private static Path fixtureRoot() {
        String configured = System.getProperty(FIXTURE_PROPERTY);
        if (configured != null) {
            Path path = Path.of(configured);
            assertTrue(Files.isDirectory(path),
                    "native.metadata.test.highVersionFixtures points at a non-existent directory: " + path);
            return path;
        }
        return Path.of(System.getProperty("basedir", ".")).resolve("target/high-version-fixtures");
    }

    private static List<Path> listFixtures(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            List<Path> jars = new ArrayList<>(files
                    .filter(p -> p.getFileName().toString().startsWith("highversion-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList());
            return jars;
        }
    }

    private static int majorVersionOfFirstClass(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.stream()
                    .filter(e -> e.getName().equals("example/HighVersionRecord.class"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No example/HighVersionRecord.class in " + jar));
            try (InputStream is = jarFile.getInputStream(entry)) {
                byte[] header = is.readNBytes(8);
                return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            }
        }
    }

    private static int currentMajorVersion() {
        return 44 + Runtime.version().feature();
    }
}
