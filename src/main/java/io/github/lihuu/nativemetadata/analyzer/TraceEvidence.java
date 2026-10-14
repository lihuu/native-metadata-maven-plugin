package io.github.lihuu.nativemetadata.analyzer;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record TraceEvidence(
        boolean provided,
        boolean typePresent,
        @NotNull List<MemberSignature> invocations,
        @NotNull List<MemberSignature> queries
) {}
