package io.github.lihuu.nativemetadata.analyzer;

import io.github.lihuu.nativemetadata.bytecode.AnnotationReference;
import io.github.lihuu.nativemetadata.bytecode.ClassInventory;
import io.github.lihuu.nativemetadata.bytecode.MemberReference;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import io.github.lihuu.nativemetadata.scanner.DiscoveredRegistration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record ClassAnalysis(
        @NotNull String name,
        @NotNull List<ReflectionEntry> registrations,
        @Nullable ClassInventory bytecode,
        @NotNull RegisteredMembers registered,
        @NotNull TraceEvidence trace,
        @NotNull List<MemberSignature> reviewMethods,
        @NotNull List<MemberSignature> missingOwnedInvocations,
        @NotNull List<DiscoveredRegistration> alsoRegisteredBy,
        @NotNull List<MemberReference> ordinaryReferences,
        @NotNull List<AnnotationReference> annotationReferences,
        @NotNull List<String> notes
) {}
