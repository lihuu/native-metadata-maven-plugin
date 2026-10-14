package io.github.lihuu.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record BytecodeInventory(
        @NotNull Map<String, ClassInventory> classes,
        @NotNull List<MemberReference> ordinaryReferences,
        @NotNull List<AnnotationReference> annotationReferences,
        @NotNull List<ReflectionCallSite> reflectionSites
) {
    public Optional<ClassInventory> findClass(@NotNull String className) {
        return Optional.ofNullable(classes.get(className));
    }

    public List<MemberReference> findOrdinaryReferences(@NotNull String target) {
        return ordinaryReferences.stream().filter(r -> r.target().equals(target)).toList();
    }

    public List<AnnotationReference> findAnnotationReferences(@NotNull String target) {
        return annotationReferences.stream().filter(r -> r.target().equals(target)).toList();
    }
}
