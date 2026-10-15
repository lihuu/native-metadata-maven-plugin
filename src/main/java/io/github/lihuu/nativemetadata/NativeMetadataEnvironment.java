package io.github.lihuu.nativemetadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the JVM and Maven that are actually running the plugin, from inside the Maven process.
 *
 * <p>A {@code mvn --version} typed in a terminal, or a {@code JAVA_HOME} exported in a shell, is not
 * evidence about the process that executed a goal: IDEA, toolchains and PATH ordering routinely make
 * those differ. This file is written by the running mojo and is therefore first-hand.</p>
 */
final class NativeMetadataEnvironment {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private NativeMetadataEnvironment() {
    }

    @NotNull
    static Map<String, Object> describe(@NotNull String goalName, @NotNull String mavenVersion) {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("goal", goalName);
        environment.put("mavenVersion", mavenVersion);
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("javaFeature", Runtime.version().feature());
        environment.put("javaVendor", System.getProperty("java.vendor"));
        environment.put("javaVmName", System.getProperty("java.vm.name"));
        environment.put("javaHome", System.getProperty("java.home"));
        environment.put("osName", System.getProperty("os.name"));
        environment.put("osArch", System.getProperty("os.arch"));
        environment.put("pluginImplementationVersion", implementationVersion());
        return environment;
    }

    static void write(@NotNull Path destination, @NotNull String goalName, @NotNull String mavenVersion)
            throws IOException {
        Map<String, Object> environment = describe(goalName, mavenVersion);
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writeValue(destination.toFile(), environment);
    }

    private static String implementationVersion() {
        Package pkg = NativeMetadataEnvironment.class.getPackage();
        String version = (pkg != null) ? pkg.getImplementationVersion() : null;
        return (version != null) ? version : "unknown";
    }
}
