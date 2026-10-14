package com.licode.nativemetadata.pruner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public record PruningChange(
        @NotNull String resourcePath,
        @NotNull String typeName,
        @Nullable Map<String, Object> condition,
        @NotNull PruningAction action,
        @NotNull String reason,
        @NotNull String details
) {}
