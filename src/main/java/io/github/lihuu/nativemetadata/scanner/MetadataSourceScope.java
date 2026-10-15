package io.github.lihuu.nativemetadata.scanner;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/**
 * Where the scanner may look for metadata, and which of those locations the native build will
 * actually consume.
 *
 * <p>The three lists exist because they answer different questions. A framework-generated directory
 * is read-only input that the user explicitly declared. This build's own output directory is where
 * the packaged copies of the project's configuration live. And the native input classpath is what
 * decides whether a registration is evidence for <em>deleting</em> a self-owned entry: a registration
 * that comes from a compiled-only or provided dependency never reaches the native image, so
 * "something else registers this" would be false.</p>
 */
public record MetadataSourceScope(
        @NotNull List<Path> generatedRoots,
        @NotNull List<Path> projectOutputRoots,
        @NotNull List<String> nativeInputClasspath
) {

    public MetadataSourceScope {
        generatedRoots = List.copyOf(generatedRoots);
        projectOutputRoots = List.copyOf(projectOutputRoots);
        nativeInputClasspath = List.copyOf(nativeInputClasspath);
    }

    /** No locations known: everything is reported, nothing is eligible as deletion evidence. */
    @NotNull
    public static MetadataSourceScope none() {
        return new MetadataSourceScope(List.of(), List.of(), List.of());
    }

    @NotNull
    public static MetadataSourceScope of(List<Path> generatedRoots, List<Path> projectOutputRoots,
                                         List<String> nativeInputClasspath) {
        return new MetadataSourceScope(generatedRoots, projectOutputRoots, nativeInputClasspath);
    }
}
