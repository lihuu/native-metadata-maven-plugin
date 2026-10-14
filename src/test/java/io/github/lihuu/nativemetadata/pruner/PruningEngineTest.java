package io.github.lihuu.nativemetadata.pruner;

import io.github.lihuu.nativemetadata.model.MethodConfig;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import io.github.lihuu.nativemetadata.scanner.DiscoveredMetadataSource;
import io.github.lihuu.nativemetadata.scanner.DiscoveredRegistration;
import io.github.lihuu.nativemetadata.scanner.MetadataScope;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruningEngineTest {

    private DiscoveredMetadataSource createSource(String name, MetadataScope scope) {
        return new DiscoveredMetadataSource(
                name,
                "META-INF/native-image/" + name,
                scope,
                true,
                1,
                0,
                null,
                null
        );
    }

    private ReflectionEntry createEntry(String name, Map<String, Object> condition,
                                        boolean allMethods, List<MethodConfig> methods,
                                        List<MethodConfig> queriedMethods,
                                        Map<String, Object> extra) {
        return new ReflectionEntry(
                name, "name", condition,
                false, false, allMethods, false,
                false, false, false, false,
                false, false, false, false,
                false,
                Collections.emptyList(),
                methods,
                queriedMethods,
                extra
        );
    }

    @Test
    void prunesOwnedEntryFullyCoveredByDependency() {
        ReflectionEntry owned = createEntry("example.Foo", null, true,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        ReflectionEntry depEntry = createEntry("example.Foo", null, true,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

        DiscoveredRegistration dep = new DiscoveredRegistration(
                createSource("dep.jar", MetadataScope.DEPENDENCY),
                depEntry
        );

        PruningEngine pruner = new PruningEngine();
        PruningResult result = pruner.prune(
                List.of(new OwnedConfigEntry("path/reflect-config.json", owned)),
                List.of(dep),
                Collections.emptyList()
        );

        assertEquals(1, result.changes().size());
        PruningChange change = result.changes().get(0);
        assertEquals(PruningAction.DELETED, change.action());
        assertEquals("example.Foo", change.typeName());
        assertTrue(result.retainedEntries("path/reflect-config.json").isEmpty());
    }

    @Test
    void retainsEntryWhenConditionMismatches() {
        // Owned is unconditional, dependency has a condition
        ReflectionEntry owned = createEntry("example.Foo", null, true,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        ReflectionEntry depEntry = createEntry("example.Foo", Map.of("typeReachable", "com.Other"), true,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

        DiscoveredRegistration dep = new DiscoveredRegistration(
                createSource("dep.jar", MetadataScope.DEPENDENCY),
                depEntry
        );

        PruningEngine pruner = new PruningEngine();
        PruningResult result = pruner.prune(
                List.of(new OwnedConfigEntry("path/reflect-config.json", owned)),
                List.of(dep),
                Collections.emptyList()
        );

        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.retainedEntries("path/reflect-config.json").size());
    }

    @Test
    void retainsEntryWhenCoveringSourceOnlyProvidesQueryPermission() {
        // Owned needs invocation on method "bar"
        ReflectionEntry owned = createEntry("example.Foo", null, false,
                List.of(new MethodConfig("bar", List.of())), Collections.emptyList(), Collections.emptyMap());
        // Dependency only provides queriedMethods on "bar"
        ReflectionEntry depEntry = createEntry("example.Foo", null, false,
                Collections.emptyList(), List.of(new MethodConfig("bar", List.of())), Collections.emptyMap());

        DiscoveredRegistration dep = new DiscoveredRegistration(
                createSource("dep.jar", MetadataScope.DEPENDENCY),
                depEntry
        );

        PruningEngine pruner = new PruningEngine();
        PruningResult result = pruner.prune(
                List.of(new OwnedConfigEntry("path/reflect-config.json", owned)),
                List.of(dep),
                Collections.emptyList()
        );

        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.retainedEntries("path/reflect-config.json").size());
    }

    @Test
    void retainsEntryWithUnknownFields() {
        ReflectionEntry owned = createEntry("example.Foo", null, true,
                Collections.emptyList(), Collections.emptyList(), Map.of("unknownKey", "val"));
        ReflectionEntry depEntry = createEntry("example.Foo", null, true,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

        DiscoveredRegistration dep = new DiscoveredRegistration(
                createSource("dep.jar", MetadataScope.DEPENDENCY),
                depEntry
        );

        PruningEngine pruner = new PruningEngine();
        PruningResult result = pruner.prune(
                List.of(new OwnedConfigEntry("path/reflect-config.json", owned)),
                List.of(dep),
                Collections.emptyList()
        );

        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.retainedEntries("path/reflect-config.json").size());
    }

    @Test
    void deduplicatesIdenticalOwnedEntriesRetainingOne() {
        ReflectionEntry owned1 = createEntry("example.Foo", null, true,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        ReflectionEntry owned2 = createEntry("example.Foo", null, true,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

        PruningEngine pruner = new PruningEngine();
        PruningResult result = pruner.prune(
                List.of(
                        new OwnedConfigEntry("path/reflect-config.json", owned1),
                        new OwnedConfigEntry("path/reflect-config.json", owned2)
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );

        assertEquals(1, result.changes().size());
        assertEquals(PruningAction.DELETED, result.changes().get(0).action());
        assertEquals(1, result.retainedEntries("path/reflect-config.json").size());
    }
}
