package io.github.lihuu.nativemetadata.scanner;

import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

public record DiscoveredRegistration(
        @NotNull DiscoveredMetadataSource source,
        @NotNull ReflectionEntry entry
) {}
