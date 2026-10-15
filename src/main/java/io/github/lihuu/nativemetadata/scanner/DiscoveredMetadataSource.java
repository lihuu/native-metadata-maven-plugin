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
        /**
         * Whether the native build reads this source. Only such a source may justify deleting a
         * self-owned registration; a compile-only or provided dependency is on the Maven classpath
         * but never in the image.
         */
        boolean consumedByNativeBuild,
        @JsonIgnore @Nullable MetadataDocument document
) {}
