package com.licode.nativemetadata.analyzer;

public record ReportSummary(
        int ownedTypes,
        int resolvedTypes,
        boolean traceProvided,
        int protectedOverlaps,
        int unsupportedOwnedEntries,
        int registeredFields,
        int registeredConstructors,
        int registeredMethods
) {}
