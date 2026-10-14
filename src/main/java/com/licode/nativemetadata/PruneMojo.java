package com.licode.nativemetadata;

import com.licode.nativemetadata.analyzer.AnalysisReport;
import com.licode.nativemetadata.analyzer.ClassAnalysis;
import com.licode.nativemetadata.analyzer.MarkdownReportFormatter;
import com.licode.nativemetadata.analyzer.MetadataAnalyzer;
import com.licode.nativemetadata.model.MetadataDocument;
import com.licode.nativemetadata.model.ReflectionEntry;
import com.licode.nativemetadata.pruner.ChangelogWriter;
import com.licode.nativemetadata.pruner.OwnedConfigEntry;
import com.licode.nativemetadata.pruner.PruningApplier;
import com.licode.nativemetadata.pruner.PruningEngine;
import com.licode.nativemetadata.pruner.PruningResult;
import com.licode.nativemetadata.pruner.PruningRule;
import com.licode.nativemetadata.pruner.PruningRulesParser;
import com.licode.nativemetadata.scanner.ClasspathMetadataScanner;
import com.licode.nativemetadata.scanner.DiscoveredRegistration;
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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping native-metadata:prune");
            return;
        }

        List<Path> resolvedOwned = resolveOwnedConfigs();
        if (resolvedOwned.isEmpty()) {
            getLog().info("No owned native metadata configurations found; skipping pruning.");
            return;
        }

        List<String> resolvedClasspath = resolveClasspath();
        List<Path> resolvedTraces = (traceFiles != null)
                ? traceFiles.stream().map(File::toPath).filter(Files::isRegularFile).toList()
                : Collections.emptyList();

        try {
            // Step 1: Run analyze to generate full report and gather external registrations
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
                originalDocs.put(resName, doc);
                originalSourceFiles.put(resName, configPath);
                for (ReflectionEntry entry : doc.entries()) {
                    ownedEntries.add(new OwnedConfigEntry(resName, entry));
                }
            }

            List<DiscoveredRegistration> externalRegs = new ArrayList<>();
            for (ClassAnalysis ca : report.classes()) {
                externalRegs.addAll(ca.alsoRegisteredBy());
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
