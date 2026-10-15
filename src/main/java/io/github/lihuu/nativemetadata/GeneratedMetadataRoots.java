package io.github.lihuu.nativemetadata;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the optional {@code generatedMetadataDirectories} parameter.
 *
 * <p>These are directories whose metadata was produced by a framework during the build (Spring AOT
 * is the motivating case). They are read-only inputs: they take part in analysis and in coverage
 * decisions, but the plugin never writes into them, and the user does not have to replace the whole
 * {@code classpath} to have them considered.</p>
 *
 * <p>An explicitly configured directory that does not exist is an error. Silently analysing fewer
 * sources than the user asked for would turn "the generated metadata was not read" into "there is
 * nothing to see here", which is the failure mode this whole exercise is about.</p>
 */
final class GeneratedMetadataRoots {

    private GeneratedMetadataRoots() {
    }

    @NotNull
    static List<Path> resolve(List<File> configured) throws MojoExecutionException {
        List<Path> roots = new ArrayList<>();
        if (configured == null) {
            return roots;
        }
        for (File directory : configured) {
            if (!directory.exists() || !directory.isDirectory()) {
                throw new MojoExecutionException(
                        "generatedMetadataDirectories entry does not exist or is not a directory: " + directory);
            }
            roots.add(directory.toPath().toAbsolutePath().normalize());
        }
        return roots;
    }

    /** Adds generated directories to the scanned classpath, without duplicating existing entries. */
    static void appendToClasspath(@NotNull List<Path> roots, @NotNull List<String> classpath) {
        for (Path root : roots) {
            String value = root.toString();
            if (!classpath.contains(value)) {
                classpath.add(value);
            }
        }
    }

    /**
     * Registers this build's own output directory as a project-owned location.
     *
     * <p>Spring AOT writes its generated metadata both into {@code target/spring-aot/main/resources}
     * and into {@code target/classes}. Both are then on the native-image classpath. Classifying the
     * second copy as a "dependency" because its resource path carries an extra artifactId segment
     * would misreport where the metadata came from; it is still this build's output, and it exists in
     * the native input only because this build produced it.</p>
     */
    static void addBuildOutputDirectory(@NotNull List<Path> roots, String outputDirectory) {
        if (outputDirectory == null || outputDirectory.isBlank()) {
            return;
        }
        Path path = Path.of(outputDirectory).toAbsolutePath().normalize();
        if (!roots.contains(path)) {
            roots.add(path);
        }
    }

    /** This build's own output directory, where the packaged copies of the project's files live. */
    @NotNull
    static List<Path> projectOutputRoots(MavenProject project) {
        List<Path> roots = new ArrayList<>();
        if (project != null && project.getBuild() != null) {
            addBuildOutputDirectory(roots, project.getBuild().getOutputDirectory());
        }
        return roots;
    }

    /**
     * The classpath the native build consumes: this build's output directory plus the runtime
     * classpath. {@code getCompileClasspathElements()} is deliberately not included - provided and
     * compile-only dependencies appear there but never reach the image.
     */
    @NotNull
    static List<String> nativeInputClasspath(MavenProject project) {
        List<String> entries = new ArrayList<>();
        if (project == null) {
            return entries;
        }
        try {
            if (project.getBuild() != null && project.getBuild().getOutputDirectory() != null) {
                entries.add(project.getBuild().getOutputDirectory());
            }
            for (String element : project.getRuntimeClasspathElements()) {
                if (element != null && !entries.contains(element)) {
                    entries.add(element);
                }
            }
        } catch (Exception e) {
            // Fall back to the output directory alone: fewer sources are eligible as deletion
            // evidence, which is the safe direction.
            return entries;
        }
        return entries;
    }
}
