package io.github.lihuu.nativemetadata.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.lihuu.nativemetadata.bytecode.ReflectionCallSite;
import io.github.lihuu.nativemetadata.scanner.DiscoveredMetadataSource;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public record AnalysisReport(
        @NotNull ReportSummary summary,
        @NotNull List<ClassAnalysis> classes,
        @NotNull List<DiscoveredMetadataSource> metadataSources,
        @NotNull List<ReflectionCallSite> reflectionSites
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @NotNull
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize analysis report to JSON: " + e.getMessage(), e);
        }
    }

    public void writeJson(@NotNull Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, toJson());
    }
}
