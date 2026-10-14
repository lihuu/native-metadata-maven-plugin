package io.github.lihuu.nativemetadata.pruner;

import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

public record OwnedConfigEntry(
        @NotNull String resourcePath,
        @NotNull ReflectionEntry entry
) {}
