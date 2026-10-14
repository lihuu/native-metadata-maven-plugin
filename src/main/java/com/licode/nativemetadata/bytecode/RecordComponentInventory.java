package com.licode.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;

public record RecordComponentInventory(
        @NotNull String name,
        @NotNull String descriptor
) {}
