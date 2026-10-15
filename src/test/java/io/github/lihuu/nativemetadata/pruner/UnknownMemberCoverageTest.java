package io.github.lihuu.nativemetadata.pruner;

import io.github.lihuu.nativemetadata.model.MetadataDocument;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regressions from parent review: broad flags cannot establish unknown member permissions. */
class UnknownMemberCoverageTest {

    @Test
    void declaredFieldFlagDoesNotCoverUnknownFieldPermission() {
        assertFalse(PruningEngine.isCoveredBy(
                entry("\"fields\":[{\"name\":\"value\",\"futurePermission\":true}]"),
                entry("\"allDeclaredFields\":true")));
    }

    @Test
    void declaredMethodFlagDoesNotCoverUnknownMethodPermission() {
        assertFalse(PruningEngine.isCoveredBy(
                entry("\"methods\":[{\"name\":\"run\",\"parameterTypes\":[],\"futurePermission\":true}]"),
                entry("\"allDeclaredMethods\":true")));
    }

    @Test
    void declaredQueryFlagDoesNotCoverUnknownQueryPermission() {
        assertFalse(PruningEngine.isCoveredBy(
                entry("\"queriedMethods\":[{\"name\":\"run\",\"parameterTypes\":[],\"futurePermission\":true}]"),
                entry("\"queryAllDeclaredMethods\":true")));
    }

    private ReflectionEntry entry(String members) {
        return MetadataDocument.parse("[{\"name\":\"example.Future\"," + members + "}]")
                .entries().get(0);
    }
}
