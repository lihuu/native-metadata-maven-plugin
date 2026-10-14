package com.licode.nativemetadata.pruner;

import com.licode.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public record PruningRule(
        @NotNull String resource,
        @NotNull String type,
        @Nullable Map<String, Object> condition,
        @NotNull ReflectionEntry replacement
) {}
