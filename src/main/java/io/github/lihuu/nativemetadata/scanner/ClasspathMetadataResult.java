package io.github.lihuu.nativemetadata.scanner;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public record ClasspathMetadataResult(
        @NotNull List<DiscoveredMetadataSource> sources,
        @NotNull Map<String, List<DiscoveredRegistration>> registrationsByClass
) {}
