package com.licode.nativemetadata.pruner;

import com.licode.nativemetadata.model.MetadataDocument;
import com.licode.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class PruningApplier {

    private final Path buildDirectory;
    private final Path outputDirectory;
    private final boolean dryRun;

    public PruningApplier(@NotNull Path buildDirectory, @NotNull Path outputDirectory, boolean dryRun) {
        this.buildDirectory = buildDirectory;
        this.outputDirectory = outputDirectory;
        this.dryRun = dryRun;
    }

    public void apply(@NotNull Map<String, List<ReflectionEntry>> prunedConfigs,
                      @NotNull Map<String, MetadataDocument> originalDocs,
                      @NotNull Map<String, Path> originalSourceFiles) throws IOException {
        Path normalizedBuildDir = buildDirectory.toAbsolutePath().normalize();
        Path candidateRoot = normalizedBuildDir.resolve("native-metadata/candidates");

        // Step 1: Always write candidate files to buildDirectory/native-metadata/candidates/
        for (Map.Entry<String, List<ReflectionEntry>> entry : prunedConfigs.entrySet()) {
            String resourcePath = entry.getKey();
            MetadataDocument doc = originalDocs.get(resourcePath);
            if (doc == null) {
                continue;
            }
            String serialized = doc.serialize(entry.getValue());
            Path candidateFile = candidateRoot.resolve(resourcePath);
            if (candidateFile.getParent() != null) {
                Files.createDirectories(candidateFile.getParent());
            }
            Files.writeString(candidateFile, serialized);
        }

        // Step 2: In dry-run mode, do not modify output directory
        if (dryRun) {
            return;
        }

        // Step 3: Verify outputDirectory is strictly within build directory (target/)
        Path normalizedOutputDir = outputDirectory.toAbsolutePath().normalize();
        if (!normalizedOutputDir.startsWith(normalizedBuildDir)) {
            throw new IllegalArgumentException("Output directory is outside build directory: " + outputDirectory);
        }

        // Step 4: Write pruned configurations to outputDirectory
        for (Map.Entry<String, List<ReflectionEntry>> entry : prunedConfigs.entrySet()) {
            String resourcePath = entry.getKey();
            MetadataDocument doc = originalDocs.get(resourcePath);
            if (doc == null) {
                continue;
            }
            Path targetFile = outputDirectory.resolve(resourcePath).toAbsolutePath().normalize();
            if (!targetFile.startsWith(normalizedBuildDir)) {
                throw new IllegalArgumentException("Target file is outside build directory: " + targetFile);
            }

            for (Path srcPath : originalSourceFiles.values()) {
                if (srcPath.toAbsolutePath().normalize().equals(targetFile)) {
                    throw new IllegalArgumentException("Target file would overwrite source file: " + targetFile);
                }
            }

            String serialized = doc.serialize(entry.getValue());
            if (targetFile.getParent() != null) {
                Files.createDirectories(targetFile.getParent());
            }
            Files.writeString(targetFile, serialized);
        }
    }
}
