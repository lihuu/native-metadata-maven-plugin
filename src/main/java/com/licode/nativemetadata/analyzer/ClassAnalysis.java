package com.licode.nativemetadata.analyzer;

import com.licode.nativemetadata.bytecode.AnnotationReference;
import com.licode.nativemetadata.bytecode.ClassInventory;
import com.licode.nativemetadata.bytecode.MemberReference;
import com.licode.nativemetadata.model.ReflectionEntry;
import com.licode.nativemetadata.scanner.DiscoveredRegistration;
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
