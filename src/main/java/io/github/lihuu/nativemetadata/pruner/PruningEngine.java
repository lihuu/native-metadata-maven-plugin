package io.github.lihuu.nativemetadata.pruner;

import io.github.lihuu.nativemetadata.model.FieldConfig;
import io.github.lihuu.nativemetadata.model.MethodConfig;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import io.github.lihuu.nativemetadata.scanner.DiscoveredRegistration;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PruningEngine {

    @NotNull
    public PruningResult prune(@NotNull List<OwnedConfigEntry> ownedEntries,
                               @NotNull List<DiscoveredRegistration> externalRegistrations,
                               @NotNull List<PruningRule> rules) {
        // Step 1: Validate and match explicit rules
        Map<Integer, PruningRule> matchedRuleIndices = new LinkedHashMap<>();
        Set<Integer> duplicateIndicesToDelete = new HashSet<>();

        for (PruningRule rule : rules) {
            List<Integer> matchIndices = new ArrayList<>();
            for (int i = 0; i < ownedEntries.size(); i++) {
                OwnedConfigEntry e = ownedEntries.get(i);
                boolean resourceMatch = e.resourcePath().equals(rule.resource())
                        || e.resourcePath().endsWith(rule.resource())
                        || rule.resource().endsWith(e.resourcePath());
                boolean typeMatch = e.entry().name().equals(rule.type());
                boolean conditionMatch;
                if (rule.condition() == null) {
                    conditionMatch = (e.entry().condition() == null);
                } else {
                    conditionMatch = rule.condition().equals(e.entry().condition());
                }
                if (resourceMatch && typeMatch && conditionMatch) {
                    matchIndices.add(i);
                }
            }

            if (matchIndices.isEmpty()) {
                throw new IllegalStateException("Pruning rule did not match any owned entry: "
                        + rule.resource() + " type: " + rule.type());
            }
            if (matchIndices.size() > 1) {
                // If all matched entries are identical duplicate entries in the same resource, keep the first and mark duplicates for deletion
                OwnedConfigEntry firstMatch = ownedEntries.get(matchIndices.get(0));
                boolean allIdentical = true;
                for (int j = 1; j < matchIndices.size(); j++) {
                    OwnedConfigEntry m = ownedEntries.get(matchIndices.get(j));
                    if (!m.resourcePath().equals(firstMatch.resourcePath())
                            || !m.entry().equals(firstMatch.entry())) {
                        allIdentical = false;
                        break;
                    }
                }
                if (!allIdentical) {
                    throw new IllegalStateException("Pruning rule ambiguously matches " + matchIndices.size()
                            + " entries: " + rule.resource() + " type: " + rule.type());
                }
                for (int j = 1; j < matchIndices.size(); j++) {
                    duplicateIndicesToDelete.add(matchIndices.get(j));
                }
            }

            int primaryIndex = matchIndices.get(0);
            if (!rule.replacement().name().equals(rule.type())) {
                throw new IllegalStateException("Pruning rule cannot change type name: "
                        + rule.type() + " -> " + rule.replacement().name());
            }
            // Symmetric on purpose. The previous form only compared when the rule named a condition,
            // so a rule without one could silently attach a condition to the replacement - a change of
            // meaning smuggled in through a field the check skipped.
            if (!java.util.Objects.equals(rule.condition(), rule.replacement().condition())) {
                throw new IllegalStateException("Pruning rule cannot change entry condition: "
                        + rule.condition() + " -> " + rule.replacement().condition());
            }

            matchedRuleIndices.put(primaryIndex, rule);
        }

        List<PruningChange> changes = new ArrayList<>();
        List<OwnedConfigEntry> preparedEntries = new ArrayList<>();

        for (int i = 0; i < ownedEntries.size(); i++) {
            OwnedConfigEntry current = ownedEntries.get(i);
            if (duplicateIndicesToDelete.contains(i)) {
                changes.add(new PruningChange(
                        current.resourcePath(),
                        current.entry().name(),
                        current.entry().condition(),
                        PruningAction.DELETED,
                        "Duplicate of entry narrowed by rule: " + current.resourcePath(),
                        "Identical entry replaced by rule match on primary entry"
                ));
                continue;
            }
            PruningRule rule = matchedRuleIndices.get(i);
            if (rule != null) {
                changes.add(new PruningChange(
                        current.resourcePath(),
                        current.entry().name(),
                        current.entry().condition(),
                        PruningAction.NARROWED,
                        "Explicit rule match: " + rule.resource(),
                        "Replaced with explicit members defined in rule"
                ));
                preparedEntries.add(new OwnedConfigEntry(current.resourcePath(), rule.replacement()));
            } else {
                preparedEntries.add(current);
            }
        }

        // Step 2: De-duplication and coverage elimination
        List<OwnedConfigEntry> retained = new ArrayList<>();
        Set<String> allResourcePaths = new HashSet<>();
        for (OwnedConfigEntry e : ownedEntries) {
            allResourcePaths.add(e.resourcePath());
        }

        for (OwnedConfigEntry current : preparedEntries) {
            ReflectionEntry target = current.entry();

            // Check if covered by external registration
            DiscoveredRegistration externalCover = null;
            for (DiscoveredRegistration ext : externalRegistrations) {
                if (isCoveredBy(target, ext.entry())) {
                    externalCover = ext;
                    break;
                }
            }

            if (externalCover != null) {
                changes.add(new PruningChange(
                        current.resourcePath(),
                        target.name(),
                        target.condition(),
                        PruningAction.DELETED,
                        "Covered by external source: " + externalCover.source().source(),
                        "All permissions and members covered by " + externalCover.source().scope().name().toLowerCase()
                ));
                continue;
            }

            // Check if covered by an already retained owned entry
            OwnedConfigEntry ownedCover = null;
            for (OwnedConfigEntry ret : retained) {
                if (isCoveredBy(target, ret.entry())) {
                    ownedCover = ret;
                    break;
                }
            }

            if (ownedCover != null) {
                changes.add(new PruningChange(
                        current.resourcePath(),
                        target.name(),
                        target.condition(),
                        PruningAction.DELETED,
                        "Duplicate of retained entry in " + ownedCover.resourcePath(),
                        "All members covered by retained owned configuration"
                ));
                continue;
            }

            retained.add(current);
        }

        Map<String, List<ReflectionEntry>> updatedConfigs = new LinkedHashMap<>();
        for (String path : allResourcePaths) {
            updatedConfigs.put(path, new ArrayList<>());
        }
        for (OwnedConfigEntry ret : retained) {
            updatedConfigs.computeIfAbsent(ret.resourcePath(), k -> new ArrayList<>()).add(ret.entry());
        }

        return new PruningResult(
                Collections.unmodifiableMap(updatedConfigs),
                Collections.unmodifiableList(changes)
        );
    }

    public static boolean isCoveredBy(@NotNull ReflectionEntry target, @NotNull ReflectionEntry covering) {
        if (!covering.name().equals(target.name())) {
            return false;
        }
        if (target.hasUnknownFields() || covering.hasUnknownFields()) {
            return false;
        }
        // Condition check: covering must be unconditional, or match target condition
        if (covering.condition() != null) {
            if (target.condition() == null || !covering.condition().equals(target.condition())) {
                return false;
            }
        }

        // Fields check
        if (target.allDeclaredFields() && !covering.allDeclaredFields()) {
            return false;
        }
        if (target.allPublicFields() && !covering.allPublicFields()) {
            return false;
        }
        for (FieldConfig fc : target.fields()) {
            // A broad flag says "every declared field", which is a known permission. It says nothing
            // about member-level properties this model does not interpret, so those always need an
            // explicit entry carrying the same properties.
            if (covering.allDeclaredFields() && fc.extra().isEmpty()) {
                continue;
            }
            boolean found = covering.fields().stream()
                    .anyMatch(f -> f.name().equals(fc.name()) && f.extra().equals(fc.extra()));
            if (!found) {
                return false;
            }
        }

        // Constructors check
        if (target.allDeclaredConstructors() && !covering.allDeclaredConstructors()) {
            return false;
        }
        if (target.allPublicConstructors() && !covering.allDeclaredConstructors() && !covering.allPublicConstructors()) {
            return false;
        }
        for (MethodConfig mc : target.methods()) {
            if (!mc.name().equals("<init>")) {
                continue;
            }
            if (covering.allDeclaredConstructors() && mc.extra().isEmpty()) {
                continue;
            }
            if (!memberCoveredBy(mc, covering.methods())) {
                return false;
            }
        }

        // Methods check (invocations)
        if (target.allDeclaredMethods() && !covering.allDeclaredMethods()) {
            return false;
        }
        if (target.allPublicMethods() && !covering.allPublicMethods()) {
            return false;
        }
        for (MethodConfig mc : target.methods()) {
            if (mc.name().equals("<init>")) {
                continue;
            }
            if (covering.allDeclaredMethods() && mc.extra().isEmpty()) {
                continue;
            }
            if (!memberCoveredBy(mc, covering.methods())) {
                return false;
            }
        }

        // Queries check
        if (target.queryAllDeclaredMethods() && !covering.allDeclaredMethods() && !covering.queryAllDeclaredMethods()) {
            return false;
        }
        if (target.queryAllPublicMethods() && !covering.allPublicMethods() && !covering.queryAllPublicMethods()) {
            return false;
        }
        for (MethodConfig mc : target.queriedMethods()) {
            boolean constructor = mc.name().equals("<init>");
            boolean allCovered = constructor
                    ? covering.allDeclaredConstructors() || covering.queryAllDeclaredConstructors()
                    : covering.allDeclaredMethods() || covering.queryAllDeclaredMethods();
            if (allCovered && mc.extra().isEmpty()) {
                continue;
            }
            boolean explicitMatch = memberCoveredBy(mc, covering.methods())
                    || memberCoveredBy(mc, covering.queriedMethods());
            if (!explicitMatch) {
                return false;
            }
        }

        // Constructor queries have their own permissions. Constructors are never inherited,
        // so declared constructor coverage also covers public constructors.
        if (target.queryAllDeclaredConstructors()
                && !covering.allDeclaredConstructors() && !covering.queryAllDeclaredConstructors()) {
            return false;
        }
        if (target.queryAllPublicConstructors()
                && !covering.allDeclaredConstructors() && !covering.allPublicConstructors()
                && !covering.queryAllDeclaredConstructors() && !covering.queryAllPublicConstructors()) {
            return false;
        }

        // Classes check
        if (target.allDeclaredClasses() && !covering.allDeclaredClasses()) {
            return false;
        }
        if (target.allPublicClasses() && !covering.allPublicClasses()) {
            return false;
        }

        // UnsafeAllocated check
        if (target.unsafeAllocated() && !covering.unsafeAllocated()) {
            return false;
        }

        return true;
    }

    /**
     * Whether one member entry of {@code covering} grants what {@code target} asks for.
     *
     * <ul>
     *   <li>An entry without a {@code parameterTypes} key means every overload with that name, so it
     *       covers anything with that name. An entry with an explicit list covers only that exact
     *       signature - and never "every overload".</li>
     *   <li>Member-level properties the model does not interpret must match exactly. Ignoring them
     *       would let a covering entry that does not grant, say, write access displace a target entry
     *       that does.</li>
     * </ul>
     */
    private static boolean memberCoveredBy(MethodConfig target, List<MethodConfig> covering) {
        for (MethodConfig candidate : covering) {
            if (!candidate.name().equals(target.name())) {
                continue;
            }
            if (candidate.parameterTypesSpecified()) {
                // The candidate names one signature. It covers the target only if the target names
                // the same one; a target that asks for every overload needs an entry that also does.
                if (!target.parameterTypesSpecified()
                        || !candidate.parameterTypes().equals(target.parameterTypes())) {
                    continue;
                }
            }
            if (!candidate.extra().equals(target.extra())) {
                continue;
            }
            return true;
        }
        return false;
    }
}
