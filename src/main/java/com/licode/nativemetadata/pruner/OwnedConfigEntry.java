package com.licode.nativemetadata.pruner;

import com.licode.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

public record OwnedConfigEntry(
        @NotNull String resourcePath,
        @NotNull ReflectionEntry entry
) {}
