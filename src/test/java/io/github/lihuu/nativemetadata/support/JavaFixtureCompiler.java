package io.github.lihuu.nativemetadata.support;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles throwaway class files used as scanner input.
 *
 * <p>Fixtures default to {@code --release 17} so the core test suite can run on the Java 17
 * baseline. Tests that need newer class files ask for an explicit release and are expected to
 * receive pre-generated input instead of compiling on the fly (see
 * {@code scripts/build-high-version-fixtures.sh}).</p>
 */
public final class JavaFixtureCompiler {

    /** Release used when a test does not care about the class-file version. */
    public static final int DEFAULT_RELEASE = 17;

    private JavaFixtureCompiler() {
    }

    public static void compile(Path outputDirectory, List<Path> sources) throws IOException {
        compile(outputDirectory, DEFAULT_RELEASE, sources);
    }

    public static void compile(Path outputDirectory, int release, List<Path> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available; a JDK (not a JRE) is required to run these tests");
        }
        Files.createDirectories(outputDirectory);
        List<String> args = new ArrayList<>();
        args.add("--release");
        args.add(Integer.toString(release));
        args.add("-d");
        args.add(outputDirectory.toString());
        for (Path source : sources) {
            args.add(source.toString());
        }
        int exitCode = compiler.run(null, null, null, args.toArray(new String[0]));
        if (exitCode != 0) {
            throw new IllegalStateException("Fixture compilation failed with exit code " + exitCode
                    + " for " + sources + " at --release " + release);
        }
    }

    /** Whether the JDK running the tests can emit the given class-file release. */
    public static boolean supportsRelease(int release) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return false;
        }
        return compiler.isSupportedOption("--release") == 1
                && java.util.Set.of(14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
                .stream().anyMatch(r -> r == release)
                && Runtime.version().feature() >= release;
    }
}
