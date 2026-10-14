package io.github.lihuu.nativemetadata.scanner;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.lihuu.nativemetadata.model.MetadataDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record DiscoveredMetadataSource(
        @NotNull String source,
        @NotNull String resource,
        @NotNull MetadataScope scope,
        boolean readOnly,
        int reflectionEntries,
        int unsupportedEntries,
        @Nullable Boolean matchesSource,
        @JsonIgnore @Nullable MetadataDocument document
) {}
