package io.github.lihuu.nativemetadata;

import io.github.lihuu.nativemetadata.analyzer.AnalysisReport;
import io.github.lihuu.nativemetadata.analyzer.ClassAnalysis;
import io.github.lihuu.nativemetadata.analyzer.MarkdownReportFormatter;
import io.github.lihuu.nativemetadata.analyzer.MetadataAnalyzer;
import io.github.lihuu.nativemetadata.model.MetadataDocument;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import io.github.lihuu.nativemetadata.pruner.ChangelogWriter;
import io.github.lihuu.nativemetadata.pruner.OwnedConfigEntry;
import io.github.lihuu.nativemetadata.pruner.PruningApplier;
import io.github.lihuu.nativemetadata.pruner.PruningEngine;
import io.github.lihuu.nativemetadata.pruner.PruningResult;
import io.github.lihuu.nativemetadata.pruner.PruningRule;
import io.github.lihuu.nativemetadata.pruner.PruningRulesParser;
import io.github.lihuu.nativemetadata.scanner.ClasspathMetadataScanner;
import io.github.lihuu.nativemetadata.scanner.DiscoveredRegistration;
import io.github.lihuu.nativemetadata.scanner.MetadataSourceScope;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.rtinfo.RuntimeInformation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Mojo(name = "prune",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public final class PruneMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "native.metadata.ownedConfigs")
    private List<File> ownedConfigs;

    @Parameter(property = "native.metadata.classpath")
    private List<String> classpath;

    @Parameter(property = "native.metadata.traceFiles")
    private List<File> traceFiles;

    @Parameter
    private List<File> generatedMetadataDirectories;

    @Parameter(property = "native.metadata.rulesFile")
    private File rulesFile;

    @Parameter(property = "native.metadata.dryRun", defaultValue = "false")
    private boolean dryRun;

    @Parameter(property = "native.metadata.outputDirectory",
            defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(property = "native.metadata.jsonReport",
            defaultValue = "${project.build.directory}/native-metadata/report.json")
    private File jsonReport;

    @Parameter(property = "native.metadata.markdownReport",
            defaultValue = "${project.build.directory}/native-metadata/report.md")
    private File markdownReport;

    @Parameter(property = "native.metadata.changelogFile",
            defaultValue = "${project.build.directory}/native-metadata/changelog.json")
    private File changelogFile;

    @Parameter(property = "native.metadata.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "native.metadata.environmentFile",
            defaultValue = "${project.build.directory}/native-metadata/environment.json")
    private File environmentFile;

    @Component
    private RuntimeInformation runtimeInformation;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping native-metadata:prune");
            return;
        }

        recordEnvironment("prune");

        MetadataSourceScope scope = MetadataSourceScope.of(
                GeneratedMetadataRoots.resolve(generatedMetadataDirectories),
                GeneratedMetadataRoots.projectOutputRoots(project),
                GeneratedMetadataRoots.nativeInputClasspath(project));
        List<Path> generatedRoots = scope.generatedRoots();

        List<Path> resolvedOwned = resolveOwnedConfigs();
        if (resolvedOwned.isEmpty()) {
            getLog().info("No owned native metadata configurations found; skipping pruning.");
            return;
        }

        List<String> resolvedClasspath = resolveClasspath();
        GeneratedMetadataRoots.appendToClasspath(generatedRoots, resolvedClasspath);
        List<Path> resolvedTraces = (traceFiles != null)
                ? traceFiles.stream().map(File::toPath).filter(Files::isRegularFile).toList()
                : Collections.emptyList();

        try {
            // Step 1: Run analyze to generate full report and gather external registrations
            MetadataAnalyzer analyzer = new MetadataAnalyzer();
            AnalysisReport report = analyzer.analyze(resolvedOwned, resolvedClasspath, resolvedTraces, scope);

            if (jsonReport != null) {
                report.writeJson(jsonReport.toPath());
                getLog().info("Wrote JSON analysis report to " + jsonReport);
            }
            if (markdownReport != null) {
                MarkdownReportFormatter.writeMarkdown(report, markdownReport.toPath());
                getLog().info("Wrote Markdown summary report to " + markdownReport);
            }

            // Step 2: Load rules
            List<PruningRule> rules = Collections.emptyList();
            if (rulesFile != null) {
                if (!rulesFile.exists() || !rulesFile.isFile()) {
                    throw new MojoExecutionException("Specified rulesFile does not exist: " + rulesFile);
                }
                rules = PruningRulesParser.parse(rulesFile.toPath());
                getLog().info("Loaded " + rules.size() + " pruning rules from " + rulesFile);
            }

            // Step 3: Prepare owned entries
            List<OwnedConfigEntry> ownedEntries = new ArrayList<>();
            Map<String, MetadataDocument> originalDocs = new LinkedHashMap<>();
            Map<String, Path> originalSourceFiles = new LinkedHashMap<>();

            for (Path configPath : resolvedOwned) {
                String resName = ClasspathMetadataScanner.extractResourceName(configPath.toString());
                if (resName.isEmpty()) {
                    resName = configPath.getFileName().toString();
                }
                MetadataDocument doc = MetadataDocument.parse(configPath);
                if (doc.unsupportedEntryCount() > 0) {
                    throw new MojoExecutionException("Refusing to rewrite " + configPath + ": "
                            + doc.unsupportedEntryCount() + " reflection entr(ies) could not be parsed "
                            + "and would be lost: " + doc.unsupportedEntryReasons());
                }
                originalDocs.put(resName, doc);
                originalSourceFiles.put(resName, configPath);
                for (ReflectionEntry entry : doc.entries()) {
                    ownedEntries.add(new OwnedConfigEntry(resName, entry));
                }
            }

            // Only registrations the native build actually reads may justify removing the project's
            // own entry. A provided or compile-only dependency is on the Maven compile classpath but
            // never in the image, so "something else already registers this" would be untrue.
            List<DiscoveredRegistration> externalRegs = new ArrayList<>();
            int skippedNotConsumed = 0;
            for (ClassAnalysis ca : report.classes()) {
                for (DiscoveredRegistration registration : ca.alsoRegisteredBy()) {
                    if (registration.source().consumedByNativeBuild()) {
                        externalRegs.add(registration);
                    } else {
                        skippedNotConsumed++;
                    }
                }
            }
            if (skippedNotConsumed > 0) {
                getLog().info("Ignoring " + skippedNotConsumed
                        + " registration(s) from sources the native build does not consume");
            }

            // Step 4: Execute pruning engine
            PruningEngine pruner = new PruningEngine();
            PruningResult pruningResult = pruner.prune(ownedEntries, externalRegs, rules);

            // Step 5: Write changelog
            if (changelogFile != null) {
                ChangelogWriter.write(pruningResult.changes(), changelogFile.toPath());
                getLog().info("Wrote pruning changelog to " + changelogFile);
            }

            // Step 6: Apply pruning to output directory and candidates
            Path buildDir = (project != null && project.getBuild() != null && project.getBuild().getDirectory() != null)
                    ? Path.of(project.getBuild().getDirectory())
                    : outputDirectory.toPath().getParent();
            Path targetOutputDir = (outputDirectory != null)
                    ? outputDirectory.toPath()
                    : Path.of(project.getBuild().getOutputDirectory());

            PruningApplier applier = new PruningApplier(buildDir, targetOutputDir, dryRun);
            applier.apply(pruningResult.updatedConfigs(), originalDocs, originalSourceFiles);

            getLog().info("Native metadata prune completed: " + pruningResult.changes().size() + " changes ("
                    + (dryRun ? "dry-run" : "applied to " + targetOutputDir) + ")");
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to prune native image metadata: " + e.getMessage(), e);
        }
    }

    private void recordEnvironment(String goalName) {
        if (environmentFile == null) {
            return;
        }
        String mavenVersion = (runtimeInformation != null) ? runtimeInformation.getMavenVersion() : "unknown";
        try {
            NativeMetadataEnvironment.write(environmentFile.toPath(), goalName, mavenVersion);
            getLog().info("native-metadata " + goalName + " is running on Java "
                    + Runtime.version().feature() + " (" + System.getProperty("java.home") + "), Maven "
                    + mavenVersion);
        } catch (IOException e) {
            getLog().warn("Could not write the environment record to " + environmentFile + ": " + e.getMessage());
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
