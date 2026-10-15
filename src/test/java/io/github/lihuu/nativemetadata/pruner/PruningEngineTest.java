package io.github.lihuu.nativemetadata.pruner;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lihuu.nativemetadata.model.MethodConfig;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import io.github.lihuu.nativemetadata.scanner.DiscoveredMetadataSource;
import io.github.lihuu.nativemetadata.scanner.DiscoveredRegistration;
import io.github.lihuu.nativemetadata.scanner.MetadataScope;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruningEngineTest {

    private static final String RESOURCE = "path/reflect-config.json";
    private static final String TYPE = "example.Foo";
    private static final MethodConfig STRING_CTOR = new MethodConfig("<init>", List.of("java.lang.String"));

    private static DiscoveredMetadataSource source(String name, MetadataScope scope) {
        return new DiscoveredMetadataSource(
                name,
                "META-INF/native-image/" + name,
                scope,
                true,
                1,
                0,
                null,
                true,
                null
        );
    }

    private static Entry entry(String name) {
        return new Entry(name);
    }

    /** Compact fluent builder so coverage fixtures read as flag combinations. */
    private static final class Entry {
        private final String name;
        private Map<String, Object> condition;
        private boolean allDeclaredFields;
        private boolean allPublicFields;
        private boolean allDeclaredMethods;
        private boolean allPublicMethods;
        private boolean allDeclaredConstructors;
        private boolean allPublicConstructors;
        private boolean allDeclaredClasses;
        private boolean allPublicClasses;
        private boolean queryAllDeclaredMethods;
        private boolean queryAllPublicMethods;
        private boolean queryAllDeclaredConstructors;
        private boolean queryAllPublicConstructors;
        private boolean unsafeAllocated;
        private List<MethodConfig> methods = Collections.emptyList();
        private List<MethodConfig> queriedMethods = Collections.emptyList();
        private Map<String, Object> extra = Collections.emptyMap();

        private Entry(String name) {
            this.name = name;
        }

        Entry condition(Map<String, Object> value) { this.condition = value; return this; }
        Entry allDeclaredFields() { this.allDeclaredFields = true; return this; }
        Entry allPublicFields() { this.allPublicFields = true; return this; }
        Entry allDeclaredMethods() { this.allDeclaredMethods = true; return this; }
        Entry allPublicMethods() { this.allPublicMethods = true; return this; }
        Entry allDeclaredConstructors() { this.allDeclaredConstructors = true; return this; }
        Entry allPublicConstructors() { this.allPublicConstructors = true; return this; }
        Entry allDeclaredClasses() { this.allDeclaredClasses = true; return this; }
        Entry allPublicClasses() { this.allPublicClasses = true; return this; }
        Entry queryAllDeclaredMethods() { this.queryAllDeclaredMethods = true; return this; }
        Entry queryAllPublicMethods() { this.queryAllPublicMethods = true; return this; }
        Entry queryAllDeclaredConstructors() { this.queryAllDeclaredConstructors = true; return this; }
        Entry queryAllPublicConstructors() { this.queryAllPublicConstructors = true; return this; }
        Entry unsafeAllocated() { this.unsafeAllocated = true; return this; }
        Entry methods(MethodConfig... value) { this.methods = List.of(value); return this; }
        Entry queriedMethods(MethodConfig... value) { this.queriedMethods = List.of(value); return this; }
        Entry extra(Map<String, Object> value) { this.extra = value; return this; }

        ReflectionEntry build() {
            return new ReflectionEntry(
                    name, "name", condition,
                    allDeclaredFields, allPublicFields, allDeclaredMethods, allPublicMethods,
                    allDeclaredConstructors, allPublicConstructors, allDeclaredClasses, allPublicClasses,
                    queryAllDeclaredMethods, queryAllPublicMethods,
                    queryAllDeclaredConstructors, queryAllPublicConstructors,
                    unsafeAllocated,
                    Collections.emptyList(), methods, queriedMethods, extra
            );
        }
    }

    private static PruningResult pruneWithExternal(ReflectionEntry owned, ReflectionEntry external) {
        DiscoveredRegistration dep = new DiscoveredRegistration(
                source("dep.jar", MetadataScope.DEPENDENCY),
                external
        );
        return new PruningEngine().prune(
                List.of(new OwnedConfigEntry(RESOURCE, owned)),
                List.of(dep),
                Collections.emptyList()
        );
    }

    private static PruningResult pruneOwned(ReflectionEntry... entries) {
        List<OwnedConfigEntry> owned = Arrays.stream(entries)
                .map(e -> new OwnedConfigEntry(RESOURCE, e))
                .toList();
        return new PruningEngine().prune(owned, Collections.emptyList(), Collections.emptyList());
    }

    private static void assertPrunedByExternal(ReflectionEntry owned, ReflectionEntry external) {
        PruningResult result = pruneWithExternal(owned, external);
        assertEquals(1, result.changes().size(), () -> "expected deletion, changes=" + result.changes());
        assertEquals(PruningAction.DELETED, result.changes().get(0).action());
        assertTrue(result.retainedEntries(RESOURCE).isEmpty(), "entry should have been pruned");
    }

    private static void assertRetainedAgainstExternal(ReflectionEntry owned, ReflectionEntry external) {
        PruningResult result = pruneWithExternal(owned, external);
        assertTrue(result.changes().isEmpty(), () -> "expected retention, changes=" + result.changes());
        assertEquals(1, result.retainedEntries(RESOURCE).size(), "entry should have been retained");
    }

    private record CoverageCase(String description, ReflectionEntry target,
                                ReflectionEntry covering, boolean expected) {
        @Override
        public String toString() {
            return description;
        }
    }

    private static void assertCoverage(List<CoverageCase> cases) {
        for (CoverageCase c : cases) {
            assertEquals(c.expected(), PruningEngine.isCoveredBy(c.target(), c.covering()),
                    () -> "[" + c.description() + "] target=" + c.target().toMap()
                            + " covering=" + c.covering().toMap());
        }
    }

    @Test
    void prunesOwnedEntryFullyCoveredByDependency() {
        ReflectionEntry owned = entry(TYPE).allDeclaredMethods().build();
        ReflectionEntry depEntry = entry(TYPE).allDeclaredMethods().build();

        assertPrunedByExternal(owned, depEntry);
    }

    @Test
    void retainsEntryWhenConditionMismatches() {
        // Owned is unconditional, dependency has a condition
        ReflectionEntry owned = entry(TYPE).allDeclaredMethods().build();
        ReflectionEntry depEntry = entry(TYPE)
                .condition(Map.of("typeReachable", "com.Other"))
                .allDeclaredMethods().build();

        assertRetainedAgainstExternal(owned, depEntry);
    }

    @Test
    void retainsEntryWhenCoveringSourceOnlyProvidesQueryPermission() {
        // Owned needs invocation on method "bar"; dependency only provides queriedMethods on "bar".
        ReflectionEntry owned = entry(TYPE)
                .methods(new MethodConfig("bar", List.of())).build();
        ReflectionEntry depEntry = entry(TYPE)
                .queriedMethods(new MethodConfig("bar", List.of())).build();

        assertRetainedAgainstExternal(owned, depEntry);
    }

    @Test
    void retainsEntryWithUnknownFields() {
        ReflectionEntry owned = entry(TYPE)
                .allDeclaredMethods()
                .extra(Map.of("unknownKey", "val"))
                .build();
        ReflectionEntry depEntry = entry(TYPE).allDeclaredMethods().build();

        assertRetainedAgainstExternal(owned, depEntry);
    }

    @Test
    void deduplicatesIdenticalOwnedEntriesRetainingOne() {
        PruningResult result = pruneOwned(
                entry(TYPE).allDeclaredMethods().build(),
                entry(TYPE).allDeclaredMethods().build()
        );

        assertEquals(1, result.changes().size());
        assertEquals(PruningAction.DELETED, result.changes().get(0).action());
        assertEquals(1, result.retainedEntries(RESOURCE).size());
    }

    // --- Bug 1: constructor query coverage -----------------------------------

    @Test
    void retainsConstructorQueryRegistrationWhenExternalCoveringIsEmpty() {
        // An empty external registration must not delete constructor-only query permissions.
        assertRetainedAgainstExternal(
                entry(TYPE).queryAllPublicConstructors().build(),
                entry(TYPE).build()
        );
    }

    @Test
    void retainsConstructorQueryRegistrationWhenExternalProvidesOnlyMethodCoverage() {
        // Method flags must never cover constructor queries.
        assertRetainedAgainstExternal(
                entry(TYPE).queryAllDeclaredConstructors().build(),
                entry(TYPE).allDeclaredMethods().queryAllDeclaredMethods().build()
        );
    }

    @Test
    void prunesConstructorQueryRegistrationCoveredByExternalQueryFlags() {
        assertPrunedByExternal(
                entry(TYPE).queryAllDeclaredConstructors().build(),
                entry(TYPE).queryAllDeclaredConstructors().build()
        );
    }

    @Test
    void retainsExplicitConstructorQueryWhenExternalProvidesOnlyMethodFlags() {
        assertRetainedAgainstExternal(
                entry(TYPE).queriedMethods(STRING_CTOR).build(),
                entry(TYPE).allDeclaredMethods().queryAllDeclaredMethods().build()
        );
    }

    @Test
    void prunesExplicitConstructorQueryWhenExternalProvidesConstructorInvocation() {
        assertPrunedByExternal(
                entry(TYPE).queriedMethods(STRING_CTOR).build(),
                entry(TYPE).methods(STRING_CTOR).build()
        );
    }

    @Test
    void retainsConstructorQueryWhenExternalConditionMismatches() {
        assertRetainedAgainstExternal(
                entry(TYPE).queryAllDeclaredConstructors().build(),
                entry(TYPE).condition(Map.of("typeReachable", "com.Other"))
                        .queryAllDeclaredConstructors().build()
        );
    }

    // --- Bug 2: declared-only flags must not cover public targets ------------

    @Test
    void retainsPublicMethodRegistrationWhenExternalProvidesOnlyDeclaredCoverage() {
        assertRetainedAgainstExternal(
                entry(TYPE).allPublicMethods().build(),
                entry(TYPE).allDeclaredMethods().build()
        );
    }

    @Test
    void retainsPublicFieldRegistrationWhenExternalProvidesOnlyDeclaredCoverage() {
        assertRetainedAgainstExternal(
                entry(TYPE).allPublicFields().build(),
                entry(TYPE).allDeclaredFields().build()
        );
    }

    @Test
    void retainsPublicClassRegistrationWhenExternalProvidesOnlyDeclaredCoverage() {
        assertRetainedAgainstExternal(
                entry(TYPE).allPublicClasses().build(),
                entry(TYPE).allDeclaredClasses().build()
        );
    }

    @Test
    void retainsPublicQueryRegistrationWhenExternalProvidesOnlyDeclaredQueryFlags() {
        assertRetainedAgainstExternal(
                entry(TYPE).queryAllPublicMethods().build(),
                entry(TYPE).allDeclaredMethods().queryAllDeclaredMethods().build()
        );
    }

    @Test
    void prunesPublicMethodRegistrationCoveredByExternalPublicCoverage() {
        assertPrunedByExternal(
                entry(TYPE).allPublicMethods().build(),
                entry(TYPE).allPublicMethods().build()
        );
    }

    // --- Owned (already retained) coverage path ------------------------------

    @Test
    void retainsPublicMethodWhenRetainedOwnedProvidesOnlyDeclaredCoverage() {
        PruningResult result = pruneOwned(
                entry(TYPE).allDeclaredMethods().build(),
                entry(TYPE).allPublicMethods().build()
        );

        assertTrue(result.changes().isEmpty(), () -> "changes=" + result.changes());
        assertEquals(2, result.retainedEntries(RESOURCE).size());
    }

    @Test
    void retainsConstructorQueryWhenRetainedOwnedEntryIsEmpty() {
        PruningResult result = pruneOwned(
                entry(TYPE).build(),
                entry(TYPE).queryAllDeclaredConstructors().build()
        );

        assertTrue(result.changes().isEmpty(), () -> "changes=" + result.changes());
        assertEquals(2, result.retainedEntries(RESOURCE).size());
    }

    @Test
    void stillDeduplicatesIdenticalPublicCoveredOwnedEntries() {
        PruningResult result = pruneOwned(
                entry(TYPE).allPublicMethods().build(),
                entry(TYPE).allPublicMethods().build()
        );

        assertEquals(1, result.changes().size());
        assertEquals(PruningAction.DELETED, result.changes().get(0).action());
        assertEquals(1, result.retainedEntries(RESOURCE).size());
    }

    // --- Coverage directionality tables --------------------------------------

    @Test
    void publicTargetsRequirePublicCoverageNotDeclaredOnly() {
        assertCoverage(List.of(
                new CoverageCase("allPublicMethods not covered by allDeclaredMethods",
                        entry(TYPE).allPublicMethods().build(),
                        entry(TYPE).allDeclaredMethods().build(), false),
                new CoverageCase("allPublicFields not covered by allDeclaredFields",
                        entry(TYPE).allPublicFields().build(),
                        entry(TYPE).allDeclaredFields().build(), false),
                new CoverageCase("allPublicClasses not covered by allDeclaredClasses",
                        entry(TYPE).allPublicClasses().build(),
                        entry(TYPE).allDeclaredClasses().build(), false),
                new CoverageCase("queryAllPublicMethods not covered by declared invocation",
                        entry(TYPE).queryAllPublicMethods().build(),
                        entry(TYPE).allDeclaredMethods().build(), false),
                new CoverageCase("queryAllPublicMethods not covered by declared queries",
                        entry(TYPE).queryAllPublicMethods().build(),
                        entry(TYPE).queryAllDeclaredMethods().build(), false),
                new CoverageCase("allPublicMethods covered by allPublicMethods",
                        entry(TYPE).allPublicMethods().build(),
                        entry(TYPE).allPublicMethods().build(), true),
                new CoverageCase("allPublicFields covered by allPublicFields",
                        entry(TYPE).allPublicFields().build(),
                        entry(TYPE).allPublicFields().build(), true),
                new CoverageCase("allPublicClasses covered by allPublicClasses",
                        entry(TYPE).allPublicClasses().build(),
                        entry(TYPE).allPublicClasses().build(), true),
                new CoverageCase("queryAllPublicMethods covered by public invocation",
                        entry(TYPE).queryAllPublicMethods().build(),
                        entry(TYPE).allPublicMethods().build(), true),
                new CoverageCase("queryAllPublicMethods covered by public queries",
                        entry(TYPE).queryAllPublicMethods().build(),
                        entry(TYPE).queryAllPublicMethods().build(), true)
        ));
    }

    @Test
    void invocationImpliesQueryButQueryNeverImpliesInvocation() {
        assertCoverage(List.of(
                new CoverageCase("allDeclaredMethods not covered by queryAllDeclaredMethods",
                        entry(TYPE).allDeclaredMethods().build(),
                        entry(TYPE).queryAllDeclaredMethods().build(), false),
                new CoverageCase("allPublicMethods not covered by queryAllPublicMethods",
                        entry(TYPE).allPublicMethods().build(),
                        entry(TYPE).queryAllPublicMethods().build(), false),
                new CoverageCase("allDeclaredConstructors not covered by queryAllDeclaredConstructors",
                        entry(TYPE).allDeclaredConstructors().build(),
                        entry(TYPE).queryAllDeclaredConstructors().build(), false),
                new CoverageCase("allPublicConstructors not covered by queryAllPublicConstructors",
                        entry(TYPE).allPublicConstructors().build(),
                        entry(TYPE).queryAllPublicConstructors().build(), false),
                new CoverageCase("queryAllDeclaredConstructors not covered by allPublicConstructors",
                        entry(TYPE).queryAllDeclaredConstructors().build(),
                        entry(TYPE).allPublicConstructors().build(), false),
                new CoverageCase("queryAllDeclaredConstructors not covered by queryAllPublicConstructors",
                        entry(TYPE).queryAllDeclaredConstructors().build(),
                        entry(TYPE).queryAllPublicConstructors().build(), false),
                new CoverageCase("queryAllDeclaredMethods covered by allDeclaredMethods",
                        entry(TYPE).queryAllDeclaredMethods().build(),
                        entry(TYPE).allDeclaredMethods().build(), true),
                new CoverageCase("queryAllPublicMethods covered by allPublicMethods",
                        entry(TYPE).queryAllPublicMethods().build(),
                        entry(TYPE).allPublicMethods().build(), true),
                new CoverageCase("queryAllDeclaredConstructors covered by allDeclaredConstructors",
                        entry(TYPE).queryAllDeclaredConstructors().build(),
                        entry(TYPE).allDeclaredConstructors().build(), true),
                new CoverageCase("queryAllPublicConstructors covered by allDeclaredConstructors (constructors do not inherit)",
                        entry(TYPE).queryAllPublicConstructors().build(),
                        entry(TYPE).allDeclaredConstructors().build(), true),
                new CoverageCase("queryAllPublicConstructors covered by queryAllDeclaredConstructors (constructors do not inherit)",
                        entry(TYPE).queryAllPublicConstructors().build(),
                        entry(TYPE).queryAllDeclaredConstructors().build(), true),
                new CoverageCase("queryAllPublicConstructors covered by allPublicConstructors",
                        entry(TYPE).queryAllPublicConstructors().build(),
                        entry(TYPE).allPublicConstructors().build(), true),
                new CoverageCase("queryAllPublicConstructors covered by queryAllPublicConstructors",
                        entry(TYPE).queryAllPublicConstructors().build(),
                        entry(TYPE).queryAllPublicConstructors().build(), true)
        ));
    }

    @Test
    void explicitConstructorQueriesUseConstructorCoverageFlags() {
        MethodConfig otherCtor = new MethodConfig("<init>", List.of("int"));
        MethodConfig plainMethod = new MethodConfig("greet", List.of());

        assertCoverage(List.of(
                new CoverageCase("ctor query not covered by queryAllDeclaredMethods",
                        entry(TYPE).queriedMethods(STRING_CTOR).build(),
                        entry(TYPE).queryAllDeclaredMethods().build(), false),
                new CoverageCase("ctor query not covered by allDeclaredMethods",
                        entry(TYPE).queriedMethods(STRING_CTOR).build(),
                        entry(TYPE).allDeclaredMethods().build(), false),
                new CoverageCase("ctor query not covered by allPublicMethods",
                        entry(TYPE).queriedMethods(STRING_CTOR).build(),
                        entry(TYPE).allPublicMethods().build(), false),
                new CoverageCase("ctor query not covered by a different constructor signature",
                        entry(TYPE).queriedMethods(STRING_CTOR).build(),
                        entry(TYPE).methods(otherCtor).build(), false),
                new CoverageCase("ctor query covered by queryAllDeclaredConstructors",
                        entry(TYPE).queriedMethods(STRING_CTOR).build(),
                        entry(TYPE).queryAllDeclaredConstructors().build(), true),
                new CoverageCase("ctor query covered by allDeclaredConstructors",
                        entry(TYPE).queriedMethods(STRING_CTOR).build(),
                        entry(TYPE).allDeclaredConstructors().build(), true),
                new CoverageCase("ctor query covered by explicit constructor invocation",
                        entry(TYPE).queriedMethods(STRING_CTOR).build(),
                        entry(TYPE).methods(STRING_CTOR).build(), true),
                new CoverageCase("ctor query covered by explicit queried constructor",
                        entry(TYPE).queriedMethods(STRING_CTOR).build(),
                        entry(TYPE).queriedMethods(STRING_CTOR).build(), true),
                new CoverageCase("plain method query not covered by allDeclaredConstructors",
                        entry(TYPE).queriedMethods(plainMethod).build(),
                        entry(TYPE).allDeclaredConstructors().build(), false)
        ));
    }

    @Test
    void memberLevelPropertiesMustMatchExactlyForCoverage() {
        // Field- and method-level properties this model does not interpret are carried through
        // verbatim; a covering entry that does not ask for the same thing must not be treated as
        // granting it.
        MethodConfig withProperty = new MethodConfig("greet", List.of(), Map.of("allowWrite", true));
        MethodConfig withoutProperty = new MethodConfig("greet", List.of());

        assertCoverage(List.of(
                new CoverageCase("covering entry without a member property does not cover one that has it",
                        entry(TYPE).methods(withProperty).build(),
                        entry(TYPE).methods(withoutProperty).build(), false),
                new CoverageCase("identical member properties cover",
                        entry(TYPE).methods(withProperty).build(),
                        entry(TYPE).methods(withProperty).build(), true)
        ));
    }

    @Test
    void everyOverloadAndAnExplicitZeroArgMemberAreDifferentPermissions() {
        MethodConfig everyOverload = MethodConfig.everyOverload("greet");
        MethodConfig noArgs = new MethodConfig("greet", List.of());
        MethodConfig oneArg = new MethodConfig("greet", List.of("java.lang.String"));

        assertCoverage(List.of(
                new CoverageCase("an explicit no-arg member does not cover every overload",
                        entry(TYPE).methods(everyOverload).build(),
                        entry(TYPE).methods(noArgs).build(), false),
                new CoverageCase("every overload covers an explicit no-arg member",
                        entry(TYPE).methods(noArgs).build(),
                        entry(TYPE).methods(everyOverload).build(), true),
                new CoverageCase("every overload covers every overload",
                        entry(TYPE).methods(everyOverload).build(),
                        entry(TYPE).methods(everyOverload).build(), true),
                new CoverageCase("a different signature does not cover the no-arg member",
                        entry(TYPE).methods(noArgs).build(),
                        entry(TYPE).methods(oneArg).build(), false)
        ));
    }

    @Test
    void pruningRuleCannotAttachOrDropACondition() {
        Map<String, Object> condition = Map.of("typeReachable", "com.example.Bar");
        ReflectionEntry unconditional = entry(TYPE).build();
        ReflectionEntry conditional = entry(TYPE).condition(condition).build();

        List<OwnedConfigEntry> owned = List.of(new OwnedConfigEntry(RESOURCE, unconditional));
        PruningEngine engine = new PruningEngine();

        // The rule names no condition, so it may only match unconditional entries; a replacement that
        // adds one silently changes when the entry applies.
        PruningRule attachesCondition = new PruningRule(RESOURCE, TYPE, null, conditional);
        assertThrows(IllegalStateException.class,
                () -> engine.prune(owned, List.of(), List.of(attachesCondition)));

        // And the other direction: dropping a condition from a conditional entry.
        List<OwnedConfigEntry> conditionalOwned = List.of(new OwnedConfigEntry(RESOURCE, conditional));
        PruningRule dropsCondition = new PruningRule(RESOURCE, TYPE, condition, unconditional);
        assertThrows(IllegalStateException.class,
                () -> engine.prune(conditionalOwned, List.of(), List.of(dropsCondition)));
    }

}
