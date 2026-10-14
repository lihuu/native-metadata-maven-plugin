package io.github.lihuu.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;

public record AnnotationReference(
        @NotNull String target,
        @NotNull String caller,
        @NotNull String callerMember,
        @NotNull String source,
        @NotNull String annotation
) {}
