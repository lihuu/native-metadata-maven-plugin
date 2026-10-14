package com.licode.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record MethodInventory(
        @NotNull String name,
        @NotNull String descriptor,
        int flags,
        @NotNull List<String> parameterTypes,
        @NotNull List<String> annotations
) {}
