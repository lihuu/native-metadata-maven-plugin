package io.github.lihuu.nativemetadata.pruner;

import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record PruningResult(
        @NotNull Map<String, List<ReflectionEntry>> updatedConfigs,
        @NotNull List<PruningChange> changes
) {
    @NotNull
    public List<ReflectionEntry> retainedEntries(@NotNull String resourcePath) {
        return updatedConfigs.getOrDefault(resourcePath, Collections.emptyList());
    }
}
