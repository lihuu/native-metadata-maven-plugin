package io.github.lihuu.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;

public record MemberReference(
        @NotNull String target,
        @NotNull String member,
        @NotNull String descriptor,
        @NotNull String opcode,
        @NotNull String caller,
        @NotNull String callerMethod,
        @NotNull String callerDescriptor,
        @NotNull String source
) {}
