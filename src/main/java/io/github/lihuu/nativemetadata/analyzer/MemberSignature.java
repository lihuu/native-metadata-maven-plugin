package io.github.lihuu.nativemetadata.analyzer;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record MemberSignature(
        @NotNull String name,
        @NotNull List<String> parameterTypes
) {}
