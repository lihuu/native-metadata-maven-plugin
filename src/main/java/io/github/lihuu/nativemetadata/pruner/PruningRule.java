package io.github.lihuu.nativemetadata.pruner;

import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public record PruningRule(
        @NotNull String resource,
        @NotNull String type,
        @Nullable Map<String, Object> condition,
        @NotNull ReflectionEntry replacement
) {}
