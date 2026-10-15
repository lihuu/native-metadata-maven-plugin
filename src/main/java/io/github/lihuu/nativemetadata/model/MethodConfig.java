package io.github.lihuu.nativemetadata.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One entry of {@code methods} / {@code queriedMethods}.
 *
 * <p>{@code parameterTypesSpecified} exists because "no parameterTypes key" and
 * {@code "parameterTypes": []} do not mean the same thing: the first asks for every overload with
 * that name, the second asks for the zero-argument member only. Collapsing the two would make a
 * rule that means "the no-arg method" read as "all overloads" - that is, a silent widening.
 * Serialising must preserve whichever form was read.</p>
 */
public record MethodConfig(
        @NotNull String name,
        @NotNull List<String> parameterTypes,
        boolean parameterTypesSpecified,
        @NotNull Map<String, Object> extra
) {
    public MethodConfig(@NotNull String name, @NotNull List<String> parameterTypes,
                        @NotNull Map<String, Object> extra) {
        this(name, parameterTypes, true, extra);
    }

    public MethodConfig(@NotNull String name, @NotNull List<String> parameterTypes) {
        this(name, parameterTypes, true, Collections.emptyMap());
    }

    /** A member entry that names every overload: the {@code parameterTypes} key was absent. */
    @NotNull
    public static MethodConfig everyOverload(@NotNull String name) {
        return new MethodConfig(name, Collections.emptyList(), false, Collections.emptyMap());
    }
}
