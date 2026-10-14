package com.licode.nativemetadata.scanner;

import com.licode.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

public record DiscoveredRegistration(
        @NotNull DiscoveredMetadataSource source,
        @NotNull ReflectionEntry entry
) {}
