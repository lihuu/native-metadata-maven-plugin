package com.licode.nativemetadata.analyzer;

import com.licode.nativemetadata.bytecode.BytecodeInventory;
import com.licode.nativemetadata.bytecode.ClassInventory;
import com.licode.nativemetadata.bytecode.ClassfileScanner;
import com.licode.nativemetadata.bytecode.FieldInventory;
import com.licode.nativemetadata.bytecode.MethodInventory;
import com.licode.nativemetadata.bytecode.RecordComponentInventory;
import com.licode.nativemetadata.model.FieldConfig;
import com.licode.nativemetadata.model.MetadataDocument;
import com.licode.nativemetadata.model.MethodConfig;
import com.licode.nativemetadata.model.ReflectionEntry;
import com.licode.nativemetadata.scanner.ClasspathMetadataResult;
import com.licode.nativemetadata.scanner.ClasspathMetadataScanner;
import com.licode.nativemetadata.scanner.DiscoveredMetadataSource;
import com.licode.nativemetadata.scanner.DiscoveredRegistration;
import com.licode.nativemetadata.scanner.MetadataScope;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class MetadataAnalyzer {

    private static final Set<String> JACKSON_ANNOTATIONS = Set.of(
            "Lcom/fasterxml/jackson/annotation/JsonCreator;",
            "Lcom/fasterxml/jackson/annotation/JsonProperty;",
            "Lcom/fasterxml/jackson/annotation/JsonValue;",
            "Lcom/fasterxml/jackson/annotation/JsonGetter;",
            "Lcom/fasterxml/jackson/annotation/JsonSetter;"
    );

    @NotNull
    public AnalysisReport analyze(@NotNull List<Path> ownedConfigs,
                                  @NotNull List<String> classpath,
                                  @NotNull List<Path> traceFiles) throws IOException {
        List<ReflectionEntry> ownedEntries = new ArrayList<>();
        List<DiscoveredMetadataSource> ownedSources = new ArrayList<>();
        for (Path configPath : ownedConfigs) {
            MetadataDocument doc = MetadataDocument.parse(configPath);
            ownedEntries.addAll(doc.entries());
            ownedSources.add(new DiscoveredMetadataSource(
                    configPath.toString(),
                    ClasspathMetadataScanner.extractResourceName(configPath.toString()),
                    MetadataScope.PROJECT,
                    true,
                    doc.entries().size(),
                    0,
                    null,
                    doc
            ));
        }

        List<ReflectionEntry> traceEntries = new ArrayList<>();
        for (Path tracePath : traceFiles) {
            MetadataDocument doc = MetadataDocument.parse(tracePath);
            traceEntries.addAll(doc.entries());
        }

        Set<String> targetNames = new TreeSet<>();
        for (ReflectionEntry entry : ownedEntries) {
            targetNames.add(entry.name());
        }

        ClassfileScanner classfileScanner = new ClassfileScanner();
        BytecodeInventory bytecodeInventory = classfileScanner.scan(classpath, targetNames);

        ClasspathMetadataScanner classpathScanner = new ClasspathMetadataScanner();
        ClasspathMetadataResult classpathMetadata = classpathScanner.scan(classpath, ownedConfigs);

        List<ClassAnalysis> classAnalyses = new ArrayList<>();
        boolean traceProvided = !traceFiles.isEmpty();

        int totalRegisteredFields = 0;
        int totalRegisteredConstructors = 0;
        int totalRegisteredMethods = 0;
        int protectedOverlaps = 0;

        for (String className : targetNames) {
            List<ReflectionEntry> configured = ownedEntries.stream()
                    .filter(e -> e.name().equals(className))
                    .toList();
            ClassInventory inventory = bytecodeInventory.findClass(className).orElse(null);

            RegisteredMembers registered = (inventory != null)
                    ? computeRegisteredMembers(configured, inventory)
                    : new RegisteredMembers(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

            totalRegisteredFields += registered.fields().size();
            totalRegisteredConstructors += registered.constructors().size();
            totalRegisteredMethods += registered.methods().size();

            TraceEvidence traceEvidence = computeTraceEvidence(className, traceEntries, inventory, traceProvided);

            List<MemberSignature> reviewMethods = new ArrayList<>();
            if (inventory != null) {
                for (MemberSignature method : registered.methods()) {
                    boolean hasBindingHint = hasBindingHints(inventory, method);
                    boolean inTraceInvocations = traceEvidence.invocations().contains(method);
                    if (!hasBindingHint && !inTraceInvocations) {
                        reviewMethods.add(method);
                    }
                }
            }

            List<MemberSignature> missingInvocations = new ArrayList<>();
            for (MemberSignature invocation : traceEvidence.invocations()) {
                boolean inRegistered = registered.methods().contains(invocation)
                        || registered.constructors().contains(invocation);
                if (!inRegistered) {
                    missingInvocations.add(invocation);
                }
            }

            List<DiscoveredRegistration> overlaps = classpathMetadata.registrationsByClass()
                    .getOrDefault(className, Collections.emptyList());
            if (!overlaps.isEmpty()) {
                protectedOverlaps++;
            }

            List<String> notes = new ArrayList<>();
            if (inventory == null) {
                notes.add("Bytecode missing; no unused-code conclusion");
            }
            if (traceProvided && !traceEvidence.typePresent()) {
                notes.add("No trace metadata for this type; execution/filter coverage is unknown");
            }
            if (configured.stream().anyMatch(e -> e.condition() != null)) {
                notes.add("Conditional entries are a potential union; conditions are not evaluated");
            }
            if (configured.stream().anyMatch(e -> e.allPublicMethods() || e.allPublicFields())) {
                notes.add("Counts cover declared members only; inherited public members are not expanded");
            }

            classAnalyses.add(new ClassAnalysis(
                    className,
                    configured,
                    inventory,
                    registered,
                    traceEvidence,
                    reviewMethods,
                    missingInvocations,
                    overlaps,
                    bytecodeInventory.findOrdinaryReferences(className),
                    bytecodeInventory.findAnnotationReferences(className),
                    notes
            ));
        }

        List<DiscoveredMetadataSource> allSources = new ArrayList<>(ownedSources);
        allSources.addAll(classpathMetadata.sources());

        ReportSummary summary = new ReportSummary(
                targetNames.size(),
                (int) classAnalyses.stream().filter(c -> c.bytecode() != null).count(),
                traceProvided,
                protectedOverlaps,
                0,
                totalRegisteredFields,
                totalRegisteredConstructors,
                totalRegisteredMethods
        );

        return new AnalysisReport(
                summary,
                classAnalyses,
                allSources,
                bytecodeInventory.reflectionSites()
        );
    }

    private RegisteredMembers computeRegisteredMembers(List<ReflectionEntry> configured, ClassInventory inventory) {
        Set<String> fields = new TreeSet<>();
        Set<String> namedFields = new HashSet<>();
        for (ReflectionEntry entry : configured) {
            for (FieldConfig fc : entry.fields()) {
                namedFields.add(fc.name());
            }
        }
        for (FieldInventory fi : inventory.fields()) {
            boolean selected = false;
            for (ReflectionEntry entry : configured) {
                if (entry.allDeclaredFields() || namedFields.contains(fi.name())
                        || (entry.allPublicFields() && (fi.flags() & 1) != 0)) {
                    selected = true;
                    break;
                }
            }
            if (selected) {
                fields.add(fi.name());
            }
        }

        List<MemberSignature> constructors = new ArrayList<>();
        List<MemberSignature> methods = new ArrayList<>();

        for (MethodInventory mi : inventory.methods()) {
            String name = mi.name();
            if (name.equals("<clinit>")) {
                continue;
            }
            boolean isConstructor = name.equals("<init>");
            boolean selected = false;

            for (ReflectionEntry entry : configured) {
                if (isConstructor) {
                    if (entry.allDeclaredConstructors() || (entry.allPublicConstructors() && (mi.flags() & 1) != 0)) {
                        selected = true;
                        break;
                    }
                } else {
                    if (entry.allDeclaredMethods() || (entry.allPublicMethods() && (mi.flags() & 1) != 0)) {
                        selected = true;
                        break;
                    }
                }
                for (MethodConfig mc : entry.methods()) {
                    if (mc.name().equals(name)) {
                        if (mc.parameterTypes().isEmpty() || mc.parameterTypes().equals(mi.parameterTypes())) {
                            selected = true;
                            break;
                        }
                    }
                }
                if (selected) {
                    break;
                }
            }

            if (selected) {
                MemberSignature signature = new MemberSignature(name, mi.parameterTypes());
                if (isConstructor) {
                    if (!constructors.contains(signature)) {
                        constructors.add(signature);
                    }
                } else {
                    if (!methods.contains(signature)) {
                        methods.add(signature);
                    }
                }
            }
        }

        return new RegisteredMembers(new ArrayList<>(fields), constructors, methods);
    }

    private TraceEvidence computeTraceEvidence(String className, List<ReflectionEntry> traceEntries,
                                               ClassInventory inventory, boolean traceProvided) {
        List<ReflectionEntry> forClass = traceEntries.stream()
                .filter(e -> e.name().equals(className))
                .toList();
        if (!traceProvided) {
            return new TraceEvidence(false, false, Collections.emptyList(), Collections.emptyList());
        }
        if (forClass.isEmpty()) {
            return new TraceEvidence(true, false, Collections.emptyList(), Collections.emptyList());
        }

        List<MemberSignature> invocations = new ArrayList<>();
        List<MemberSignature> queries = new ArrayList<>();

        for (ReflectionEntry entry : forClass) {
            if (inventory != null) {
                RegisteredMembers registered = computeRegisteredMembers(List.of(entry), inventory);
                for (MemberSignature ctor : registered.constructors()) {
                    if (!invocations.contains(ctor)) invocations.add(ctor);
                }
                for (MemberSignature method : registered.methods()) {
                    if (!invocations.contains(method)) invocations.add(method);
                }

                // Query members
                ReflectionEntry queryEntry = new ReflectionEntry(
                        entry.name(),
                        entry.typeFieldName(),
                        entry.condition(),
                        false, false, false, false, false, false, false, false,
                        false, false, false, false, false,
                        Collections.emptyList(),
                        entry.queriedMethods(),
                        Collections.emptyList(),
                        Collections.emptyMap()
                );
                RegisteredMembers queryRegistered = computeRegisteredMembers(List.of(queryEntry), inventory);
                for (MemberSignature ctor : queryRegistered.constructors()) {
                    if (!queries.contains(ctor)) queries.add(ctor);
                }
                for (MemberSignature method : queryRegistered.methods()) {
                    if (!queries.contains(method)) queries.add(method);
                }
            } else {
                for (MethodConfig mc : entry.methods()) {
                    MemberSignature sig = new MemberSignature(mc.name(), mc.parameterTypes());
                    if (!invocations.contains(sig)) invocations.add(sig);
                }
                for (MethodConfig mc : entry.queriedMethods()) {
                    MemberSignature sig = new MemberSignature(mc.name(), mc.parameterTypes());
                    if (!queries.contains(sig)) queries.add(sig);
                }
            }
        }

        return new TraceEvidence(true, true, invocations, queries);
    }

    private boolean hasBindingHints(ClassInventory inventory, MemberSignature method) {
        for (MethodInventory mi : inventory.methods()) {
            if (!mi.name().equals(method.name()) || !mi.parameterTypes().equals(method.parameterTypes())) {
                continue;
            }
            for (String annot : mi.annotations()) {
                if (JACKSON_ANNOTATIONS.contains(annot)) {
                    return true;
                }
            }
            if (method.parameterTypes().isEmpty()) {
                for (RecordComponentInventory component : inventory.recordComponents()) {
                    if (component.name().equals(method.name())) {
                        String expectedDesc = "()" + component.descriptor();
                        if (mi.descriptor().equals(expectedDesc)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
