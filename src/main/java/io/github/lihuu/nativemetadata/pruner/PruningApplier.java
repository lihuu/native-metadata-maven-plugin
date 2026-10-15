package io.github.lihuu.nativemetadata.pruner;

import io.github.lihuu.nativemetadata.model.MetadataDocument;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PruningApplier {

    private record PendingWrite(Path destination, String content) {
    }

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

        // Everything is planned and serialised first. A failure part-way through writing would
        // otherwise leave some resources pruned and others not, which is exactly the "partially
        // applied configuration" state the analysis must never produce.
        List<PendingWrite> candidates = new ArrayList<>();
        List<PendingWrite> outputs = new ArrayList<>();

        for (Map.Entry<String, List<ReflectionEntry>> entry : prunedConfigs.entrySet()) {
            String resourcePath = entry.getKey();
            MetadataDocument doc = originalDocs.get(resourcePath);
            if (doc == null) {
                continue;
            }
            String serialized = doc.serialize(entry.getValue());
            Path candidate = checkedDestination(normalizedBuildDir, candidateRoot, resourcePath);
            candidates.add(new PendingWrite(candidate, serialized));

            if (dryRun) {
                continue;
            }

            Path normalizedOutputDir = outputDirectory.toAbsolutePath().normalize();
            if (!normalizedOutputDir.startsWith(normalizedBuildDir)) {
                throw new IllegalArgumentException("Output directory is outside build directory: " + outputDirectory);
            }

            Path targetFile = checkedDestination(normalizedBuildDir, normalizedOutputDir, resourcePath);
            for (Path srcPath : originalSourceFiles.values()) {
                if (srcPath.toAbsolutePath().normalize().equals(targetFile)
                        || (Files.exists(srcPath) && Files.exists(targetFile)
                        && Files.isSameFile(srcPath, targetFile))) {
                    throw new IllegalArgumentException("Target file would overwrite source file: " + targetFile);
                }
            }
            outputs.add(new PendingWrite(targetFile, serialized));
        }

        for (PendingWrite write : candidates) {
            writeFile(write);
        }
        for (PendingWrite write : outputs) {
            writeFile(write);
        }
    }

    private static Path checkedDestination(Path buildDirectory, Path root, String resourcePath) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path destination = normalizedRoot.resolve(resourcePath).normalize();
        if (!normalizedRoot.startsWith(buildDirectory) || !destination.startsWith(normalizedRoot)
                || destination.equals(normalizedRoot)) {
            throw new IllegalArgumentException("Resource escapes its output directory: " + resourcePath);
        }
        // Validate candidates as well as applied outputs before creating any directory. A lexical
        // target/ prefix is insufficient when an existing component points back into src/.
        Path current = buildDirectory;
        if (Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("Build directory is a symbolic link: " + current);
        }
        for (Path component : buildDirectory.relativize(destination)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Output path contains a symbolic link: " + current);
            }
        }
        return destination;
    }

    private static void writeFile(PendingWrite write) throws IOException {
        Path parent = write.destination().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(write.destination(), write.content());
    }
}
