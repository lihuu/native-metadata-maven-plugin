package io.github.lihuu.nativemetadata.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public record MethodConfig(
        @NotNull String name,
        @NotNull List<String> parameterTypes,
        @NotNull Map<String, Object> extra
) {
    public MethodConfig(@NotNull String name, @NotNull List<String> parameterTypes) {
        this(name, parameterTypes, Collections.emptyMap());
    }
}
