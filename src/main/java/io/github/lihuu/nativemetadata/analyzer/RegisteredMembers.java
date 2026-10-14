package io.github.lihuu.nativemetadata.analyzer;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record RegisteredMembers(
        @NotNull List<String> fields,
        @NotNull List<MemberSignature> constructors,
        @NotNull List<MemberSignature> methods
) {}
