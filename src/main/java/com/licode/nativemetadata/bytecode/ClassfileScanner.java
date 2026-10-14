package com.licode.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public final class ClassfileScanner {

    private static final Set<String> CLASS_REFLECTIVE_METHODS = Set.of(
            "getField", "getFields", "getMethod", "getMethods",
            "getConstructor", "getConstructors", "getRecordComponents", "getClasses"
    );

    @NotNull
    public BytecodeInventory scan(@NotNull List<String> classpath, @NotNull Set<String> targets) throws IOException {
        Set<String> seen = new HashSet<>();
        Map<String, ClassInventory> classes = new LinkedHashMap<>();
        List<MemberReference> ordinaryReferences = new ArrayList<>();
        List<AnnotationReference> annotationReferences = new ArrayList<>();
        List<ReflectionCallSite> reflectionSites = new ArrayList<>();

        for (String entry : classpath) {
            Path path = Path.of(entry);
            if (!Files.exists(path)) {
                continue;
            }
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    List<Path> classFiles = files.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".class"))
                            .sorted()
                            .toList();
                    for (Path file : classFiles) {
                        byte[] bytes = Files.readAllBytes(file);
                        inspectBytes(bytes, file.toString(), targets, seen, classes,
                                ordinaryReferences, annotationReferences, reflectionSites);
                    }
                }
            } else if (Files.isRegularFile(path) && (entry.endsWith(".jar") || entry.endsWith(".zip"))) {
                try (JarFile jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                    List<JarEntry> entries = jar.versionedStream()
                            .filter(e -> e.getName().endsWith(".class"))
                            .toList();
                    for (JarEntry jarEntry : entries) {
                        try (InputStream is = jar.getInputStream(jarEntry)) {
                            byte[] bytes = is.readAllBytes();
                            String source = path + "!/" + jarEntry.getRealName();
                            inspectBytes(bytes, source, targets, seen, classes,
                                    ordinaryReferences, annotationReferences, reflectionSites);
                        }
                    }
                }
            }
        }

        return new BytecodeInventory(classes, ordinaryReferences, annotationReferences, reflectionSites);
    }

    private void inspectBytes(byte[] bytes, String source, Set<String> targets, Set<String> seen,
                              Map<String, ClassInventory> classes,
                              List<MemberReference> ordinaryReferences,
                              List<AnnotationReference> annotationReferences,
                              List<ReflectionCallSite> reflectionSites) {
        if (bytes.length < 4) {
            return;
        }
        ClassModel model = ClassFile.of().parse(bytes);
        String internalName = model.thisClass().asInternalName();
        if (internalName.equals("module-info")) {
            return;
        }
        String className = internalName.replace('/', '.');
        if (!seen.add(className)) {
            return;
        }

        boolean isTarget = targets.isEmpty() || targets.contains(className);
        if (isTarget) {
            classes.put(className, extractClassInventory(model, className, source));
        }

        emitAnnotationReferences(model, className, "class", source, targets, annotationReferences);

        for (FieldModel field : model.fields()) {
            emitAnnotationReferences(field, className, field.fieldName().stringValue(), source, targets, annotationReferences);
        }

        for (MethodModel method : model.methods()) {
            String methodKey = method.methodName().stringValue() + method.methodType().stringValue();
            emitAnnotationReferences(method, className, methodKey, source, targets, annotationReferences);

            if (method.code().isEmpty()) {
                continue;
            }
            for (CodeElement element : method.code().get()) {
                if (element instanceof InvokeInstruction invoke) {
                    String owner = invoke.owner().asInternalName().replace('/', '.');
                    String member = invoke.name().stringValue();
                    boolean reflection = isReflective(owner, member);
                    if (reflection) {
                        reflectionSites.add(new ReflectionCallSite(
                                owner, member, invoke.type().stringValue(),
                                invoke.opcode().name(), className,
                                method.methodName().stringValue(),
                                method.methodType().stringValue(),
                                source
                        ));
                    }
                    if (targets.contains(owner)) {
                        ordinaryReferences.add(new MemberReference(
                                owner, member, invoke.type().stringValue(),
                                invoke.opcode().name(), className,
                                method.methodName().stringValue(),
                                method.methodType().stringValue(),
                                source
                        ));
                    }
                } else if (element instanceof FieldInstruction field) {
                    String owner = field.owner().asInternalName().replace('/', '.');
                    if (targets.contains(owner)) {
                        ordinaryReferences.add(new MemberReference(
                                owner, field.name().stringValue(), field.type().stringValue(),
                                field.opcode().name(), className,
                                method.methodName().stringValue(),
                                method.methodType().stringValue(),
                                source
                        ));
                    }
                }
            }
        }
    }

    private ClassInventory extractClassInventory(ClassModel model, String className, String source) {
        List<FieldInventory> fields = new ArrayList<>();
        for (FieldModel field : model.fields()) {
            fields.add(new FieldInventory(
                    field.fieldName().stringValue(),
                    field.fieldType().stringValue(),
                    field.flags().flagsMask(),
                    extractAnnotationDescriptors(field)
            ));
        }

        List<MethodInventory> methods = new ArrayList<>();
        for (MethodModel method : model.methods()) {
            methods.add(new MethodInventory(
                    method.methodName().stringValue(),
                    method.methodType().stringValue(),
                    method.flags().flagsMask(),
                    parseParameterTypes(method.methodType().stringValue()),
                    extractAnnotationDescriptors(method)
            ));
        }

        List<RecordComponentInventory> recordComponents = new ArrayList<>();
        boolean isRecord = false;
        for (RecordAttribute recordAttr : model.findAttributes(Attributes.record())) {
            isRecord = true;
            for (RecordComponentInfo info : recordAttr.components()) {
                recordComponents.add(new RecordComponentInventory(
                        info.name().stringValue(),
                        info.descriptor().stringValue()
                ));
            }
        }

        return new ClassInventory(
                className,
                source,
                fields,
                methods,
                recordComponents,
                isRecord,
                extractAnnotationDescriptors(model)
        );
    }

    @NotNull
    public static List<String> parseParameterTypes(@NotNull String descriptor) {
        List<String> result = new ArrayList<>();
        int index = 1;
        while (index < descriptor.length() && descriptor.charAt(index) != ')') {
            int arrayDimensions = 0;
            while (index < descriptor.length() && descriptor.charAt(index) == '[') {
                arrayDimensions++;
                index++;
            }
            if (index >= descriptor.length()) {
                break;
            }
            char ch = descriptor.charAt(index);
            String typeName;
            if (ch == 'L') {
                int end = descriptor.indexOf(';', index);
                if (end < 0) {
                    break;
                }
                typeName = descriptor.substring(index + 1, end).replace('/', '.');
                index = end + 1;
            } else {
                typeName = switch (ch) {
                    case 'B' -> "byte";
                    case 'C' -> "char";
                    case 'D' -> "double";
                    case 'F' -> "float";
                    case 'I' -> "int";
                    case 'J' -> "long";
                    case 'S' -> "short";
                    case 'Z' -> "boolean";
                    default -> String.valueOf(ch);
                };
                index++;
            }
            if (arrayDimensions > 0) {
                typeName = typeName + "[]".repeat(arrayDimensions);
            }
            result.add(typeName);
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean isReflective(String owner, String method) {
        if (owner.startsWith("java.lang.reflect.") || owner.equals("java.lang.invoke.MethodHandles$Lookup")) {
            return true;
        }
        if (owner.equals("java.lang.ClassLoader") && method.equals("loadClass")) {
            return true;
        }
        if (owner.equals("java.lang.Class")) {
            if (method.equals("forName") || method.startsWith("getDeclared") || CLASS_REFLECTIVE_METHODS.contains(method)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractAnnotationDescriptors(AttributedElement element) {
        List<String> descriptors = new ArrayList<>();
        for (Annotation annotation : getAnnotationModels(element)) {
            descriptors.add(annotation.classSymbol().descriptorString());
        }
        return descriptors;
    }

    private List<Annotation> getAnnotationModels(AttributedElement element) {
        List<Annotation> annotations = new ArrayList<>();
        for (RuntimeVisibleAnnotationsAttribute attr : element.findAttributes(Attributes.runtimeVisibleAnnotations())) {
            annotations.addAll(attr.annotations());
        }
        for (RuntimeInvisibleAnnotationsAttribute attr : element.findAttributes(Attributes.runtimeInvisibleAnnotations())) {
            annotations.addAll(attr.annotations());
        }
        return annotations;
    }

    private void emitAnnotationReferences(AttributedElement element, String caller, String member,
                                          String source, Set<String> targets,
                                          List<AnnotationReference> annotationReferences) {
        for (Annotation annotation : getAnnotationModels(element)) {
            List<String> referenced = new ArrayList<>();
            collectAnnotationClasses(annotation, referenced);
            for (String descriptor : referenced) {
                if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
                    continue;
                }
                String target = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                if (targets.contains(target)) {
                    annotationReferences.add(new AnnotationReference(
                            target,
                            caller,
                            member,
                            source,
                            annotation.classSymbol().descriptorString()
                    ));
                }
            }
        }
    }

    private void collectAnnotationClasses(Annotation annotation, List<String> referenced) {
        for (AnnotationElement element : annotation.elements()) {
            collectAnnotationValueClasses(element.value(), referenced);
        }
    }

    private void collectAnnotationValueClasses(AnnotationValue value, List<String> referenced) {
        if (value instanceof AnnotationValue.OfClass type) {
            referenced.add(type.className().stringValue());
        } else if (value instanceof AnnotationValue.OfAnnotation nested) {
            collectAnnotationClasses(nested.annotation(), referenced);
        } else if (value instanceof AnnotationValue.OfArray array) {
            for (AnnotationValue item : array.values()) {
                collectAnnotationValueClasses(item, referenced);
            }
        }
    }
}
