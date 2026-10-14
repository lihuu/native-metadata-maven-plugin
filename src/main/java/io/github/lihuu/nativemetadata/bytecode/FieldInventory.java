package io.github.lihuu.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record FieldInventory(
        @NotNull String name,
        @NotNull String descriptor,
        int flags,
        @NotNull List<String> annotations
) {}
