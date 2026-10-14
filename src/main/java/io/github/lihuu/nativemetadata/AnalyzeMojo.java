package io.github.lihuu.nativemetadata;

import io.github.lihuu.nativemetadata.analyzer.AnalysisReport;
import io.github.lihuu.nativemetadata.analyzer.MarkdownReportFormatter;
import io.github.lihuu.nativemetadata.analyzer.MetadataAnalyzer;
import io.github.lihuu.nativemetadata.scanner.ClasspathMetadataScanner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Mojo(name = "analyze",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public final class AnalyzeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "native.metadata.ownedConfigs")
    private List<File> ownedConfigs;

    @Parameter(property = "native.metadata.classpath")
    private List<String> classpath;

    @Parameter(property = "native.metadata.traceFiles")
    private List<File> traceFiles;

    @Parameter(property = "native.metadata.jsonReport",
            defaultValue = "${project.build.directory}/native-metadata/report.json")
    private File jsonReport;

    @Parameter(property = "native.metadata.markdownReport",
            defaultValue = "${project.build.directory}/native-metadata/report.md")
    private File markdownReport;

    @Parameter(property = "native.metadata.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping native-metadata:analyze");
            return;
        }

        List<Path> resolvedOwned = resolveOwnedConfigs();
        if (resolvedOwned.isEmpty()) {
            getLog().info("No owned native metadata configurations found; skipping analysis.");
            return;
        }

        List<String> resolvedClasspath = resolveClasspath();
        List<Path> resolvedTraces = (traceFiles != null)
                ? traceFiles.stream().map(File::toPath).filter(Files::isRegularFile).toList()
                : Collections.emptyList();

        try {
            MetadataAnalyzer analyzer = new MetadataAnalyzer();
            AnalysisReport report = analyzer.analyze(resolvedOwned, resolvedClasspath, resolvedTraces);

            if (jsonReport != null) {
                report.writeJson(jsonReport.toPath());
                getLog().info("Wrote JSON analysis report to " + jsonReport);
            }
            if (markdownReport != null) {
                MarkdownReportFormatter.writeMarkdown(report, markdownReport.toPath());
                getLog().info("Wrote Markdown summary report to " + markdownReport);
            }

            getLog().info("Native metadata analysis summary: " + report.summary().ownedTypes() + " owned types, "
                    + report.summary().resolvedTypes() + " resolved, "
                    + report.summary().protectedOverlaps() + " protected overlaps.");
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyze native image metadata: " + e.getMessage(), e);
        }
    }

    public List<Path> resolveOwnedConfigs() throws MojoExecutionException {
        if (ownedConfigs != null && !ownedConfigs.isEmpty()) {
            List<Path> list = new ArrayList<>();
            for (File file : ownedConfigs) {
                if (file.exists() && file.isFile()) {
                    list.add(file.toPath());
                } else {
                    throw new MojoExecutionException("Specified owned config does not exist: " + file);
                }
            }
            return list;
        }

        List<Path> discovered = new ArrayList<>();
        if (project != null && project.getBasedir() != null) {
            Path resDir = project.getBasedir().toPath().resolve("src/main/resources");
            if (Files.isDirectory(resDir)) {
                try (Stream<Path> stream = Files.walk(resDir)) {
                    List<Path> matching = stream.filter(Files::isRegularFile)
                            .filter(f -> ClasspathMetadataScanner.isReflectionResource(f.getFileName().toString()))
                            .filter(f -> f.toString().contains("META-INF/native-image/"))
                            .sorted()
                            .toList();
                    discovered.addAll(matching);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to scan for owned metadata in " + resDir + ": " + e.getMessage(), e);
                }
            }
        }
        return discovered;
    }

    public List<String> resolveClasspath() throws MojoExecutionException {
        Set<String> cp = new LinkedHashSet<>();
        if (classpath != null && !classpath.isEmpty()) {
            cp.addAll(classpath);
            return new ArrayList<>(cp);
        }

        if (project != null) {
            try {
                if (project.getBuild() != null && project.getBuild().getOutputDirectory() != null) {
                    cp.add(project.getBuild().getOutputDirectory());
                }
                List<String> compileClasspath = project.getCompileClasspathElements();
                if (compileClasspath != null) {
                    cp.addAll(compileClasspath);
                }
                List<String> runtimeClasspath = project.getRuntimeClasspathElements();
                if (runtimeClasspath != null) {
                    cp.addAll(runtimeClasspath);
                }
            } catch (Exception e) {
                throw new MojoExecutionException("Failed to resolve project classpath: " + e.getMessage(), e);
            }
        }
        return new ArrayList<>(cp);
    }
}
