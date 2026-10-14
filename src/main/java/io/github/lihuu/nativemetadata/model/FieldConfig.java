package io.github.lihuu.nativemetadata.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;

public record FieldConfig(
        @NotNull String name,
        @NotNull Map<String, Object> extra
) {
    public FieldConfig(@NotNull String name) {
        this(name, Collections.emptyMap());
    }
}
