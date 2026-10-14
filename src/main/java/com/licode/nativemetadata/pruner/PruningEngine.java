package com.licode.nativemetadata.pruner;

import com.licode.nativemetadata.model.FieldConfig;
import com.licode.nativemetadata.model.MethodConfig;
import com.licode.nativemetadata.model.ReflectionEntry;
import com.licode.nativemetadata.scanner.DiscoveredRegistration;
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
            if (rule.condition() != null && !rule.condition().equals(rule.replacement().condition())) {
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
        if (target.allPublicFields() && !covering.allDeclaredFields() && !covering.allPublicFields()) {
            return false;
        }
        if (!covering.allDeclaredFields()) {
            for (FieldConfig fc : target.fields()) {
                boolean found = covering.fields().stream().anyMatch(f -> f.name().equals(fc.name()));
                if (!found) {
                    return false;
                }
            }
        }

        // Constructors check
        if (target.allDeclaredConstructors() && !covering.allDeclaredConstructors()) {
            return false;
        }
        if (target.allPublicConstructors() && !covering.allDeclaredConstructors() && !covering.allPublicConstructors()) {
            return false;
        }
        if (!covering.allDeclaredConstructors()) {
            for (MethodConfig mc : target.methods()) {
                if (mc.name().equals("<init>")) {
                    boolean found = covering.methods().stream().anyMatch(m -> m.name().equals("<init>")
                            && m.parameterTypes().equals(mc.parameterTypes()));
                    if (!found) {
                        return false;
                    }
                }
            }
        }

        // Methods check (invocations)
        if (target.allDeclaredMethods() && !covering.allDeclaredMethods()) {
            return false;
        }
        if (target.allPublicMethods() && !covering.allDeclaredMethods() && !covering.allPublicMethods()) {
            return false;
        }
        if (!covering.allDeclaredMethods()) {
            for (MethodConfig mc : target.methods()) {
                if (!mc.name().equals("<init>")) {
                    boolean found = covering.methods().stream().anyMatch(m -> m.name().equals(mc.name())
                            && m.parameterTypes().equals(mc.parameterTypes()));
                    if (!found) {
                        return false;
                    }
                }
            }
        }

        // Queries check
        if (target.queryAllDeclaredMethods() && !covering.allDeclaredMethods() && !covering.queryAllDeclaredMethods()) {
            return false;
        }
        if (target.queryAllPublicMethods() && !covering.allDeclaredMethods() && !covering.allPublicMethods()
                && !covering.queryAllDeclaredMethods() && !covering.queryAllPublicMethods()) {
            return false;
        }
        for (MethodConfig mc : target.queriedMethods()) {
            boolean found = covering.methods().stream().anyMatch(m -> m.name().equals(mc.name()) && m.parameterTypes().equals(mc.parameterTypes()))
                    || covering.queriedMethods().stream().anyMatch(m -> m.name().equals(mc.name()) && m.parameterTypes().equals(mc.parameterTypes()))
                    || covering.allDeclaredMethods() || covering.queryAllDeclaredMethods();
            if (!found) {
                return false;
            }
        }

        // Classes check
        if (target.allDeclaredClasses() && !covering.allDeclaredClasses()) {
            return false;
        }
        if (target.allPublicClasses() && !covering.allDeclaredClasses() && !covering.allPublicClasses()) {
            return false;
        }

        // UnsafeAllocated check
        if (target.unsafeAllocated() && !covering.unsafeAllocated()) {
            return false;
        }

        return true;
    }
}
