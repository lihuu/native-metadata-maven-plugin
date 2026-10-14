package io.github.lihuu.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ClassInventory(
        @NotNull String name,
        @NotNull String source,
        @NotNull List<FieldInventory> fields,
        @NotNull List<MethodInventory> methods,
        @NotNull List<RecordComponentInventory> recordComponents,
        boolean isRecord,
        @NotNull List<String> annotations
) {}
