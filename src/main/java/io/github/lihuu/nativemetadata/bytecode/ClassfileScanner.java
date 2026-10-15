package io.github.lihuu.nativemetadata.bytecode;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * Static class-file inspector.
 *
 * <p>Reads class files with ASM so that it can run on the Java 17 baseline. The scanner never
 * loads or initializes application classes: it only reads the bytes it is handed, which is what
 * lets a Java 17 JVM inspect Java 21/25 bytecode that it could not load.</p>
 */
public final class ClassfileScanner {

    private static final Set<String> CLASS_REFLECTIVE_METHODS = Set.of(
            "getField", "getFields", "getMethod", "getMethods",
            "getConstructor", "getConstructors", "getRecordComponents", "getClasses"
    );

    /** ASM parsing options: debug info and stack map frames are irrelevant to this analysis. */
    private static final int PARSE_OPTIONS = ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;

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
                    // versionedStream() applies the multi-release rules the JVM itself uses: a
                    // versioned entry is selected for the running version, and entries under
                    // META-INF/ are never versioned (JAR file specification). Reimplementing this
                    // selection is how a scanner starts inventing classes the JVM would not load.
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
                              List<ReflectionCallSite> reflectionSites) throws IOException {
        // A file too short to be a class file is a broken input, not something to step over: a
        // silently skipped class is a class the analysis then reports as unresolvable or unused.
        if (bytes.length < 4) {
            throw new IOException("Not a class file (only " + bytes.length + " bytes): " + source);
        }
        ClassReader reader;
        String internalName;
        try {
            reader = new ClassReader(bytes);
            internalName = reader.getClassName();
        } catch (RuntimeException e) {
            throw new IOException("Cannot read class file " + source + ": " + e.getMessage(), e);
        }
        // module-info.class is not a business class; its name never appears in reflection configs.
        if ("module-info".equals(internalName)) {
            return;
        }

        String className = internalName.replace('/', '.');
        if (!seen.add(className)) {
            return;
        }

        boolean isTarget = targets.isEmpty() || targets.contains(className);
        ClassInventoryVisitor visitor = new ClassInventoryVisitor(
                className, source, targets, isTarget,
                ordinaryReferences, annotationReferences, reflectionSites);
        try {
            reader.accept(visitor, PARSE_OPTIONS);
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse class " + source + " (" + className + "): " + e.getMessage(), e);
        }
        if (visitor.inventory != null) {
            classes.put(className, visitor.inventory);
        }
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

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            case Opcodes.GETFIELD -> "GETFIELD";
            case Opcodes.PUTFIELD -> "PUTFIELD";
            case Opcodes.GETSTATIC -> "GETSTATIC";
            case Opcodes.PUTSTATIC -> "PUTSTATIC";
            default -> "OPCODE_" + opcode;
        };
    }

    /** Collects the class-valued members of an annotation element tree, as descriptors. */
    private static final class ClassValueCollector extends AnnotationVisitor {
        private final List<String> referenced;

        ClassValueCollector(List<String> referenced) {
            super(Opcodes.ASM9);
            this.referenced = referenced;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof Type type) {
                referenced.add(type.getDescriptor());
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            return new ClassValueCollector(referenced);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return new ClassValueCollector(referenced);
        }
    }

    /** One annotation occurrence on an element, in class-file visibility groups. */
    private static final class AnnotationRecord {
        final String descriptor;
        final boolean visible;
        final List<String> referencedClasses = new ArrayList<>();

        AnnotationRecord(String descriptor, boolean visible) {
            this.descriptor = descriptor;
            this.visible = visible;
        }
    }

    /** Accumulates annotations for a single element (class, field or method). */
    private static final class ElementAnnotations {
        final List<AnnotationRecord> records = new ArrayList<>();

        AnnotationVisitor visit(String descriptor, boolean visible) {
            AnnotationRecord record = new AnnotationRecord(descriptor, visible);
            records.add(record);
            return new ClassValueCollector(record.referencedClasses);
        }

        List<String> descriptors() {
            List<String> result = new ArrayList<>(records.size());
            for (AnnotationRecord record : visibleFirst()) {
                result.add(record.descriptor);
            }
            return result;
        }

        /** Visible annotations are reported before invisible ones, matching the previous backend. */
        private List<AnnotationRecord> visibleFirst() {
            List<AnnotationRecord> ordered = new ArrayList<>(records.size());
            for (AnnotationRecord record : records) {
                if (record.visible) {
                    ordered.add(record);
                }
            }
            for (AnnotationRecord record : records) {
                if (!record.visible) {
                    ordered.add(record);
                }
            }
            return ordered;
        }

        void emitReferences(String caller, String member, String source, Set<String> targets,
                            List<AnnotationReference> sink) {
            for (AnnotationRecord record : visibleFirst()) {
                for (String descriptor : record.referencedClasses) {
                    if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) {
                        continue;
                    }
                    String target = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                    if (targets.contains(target)) {
                        sink.add(new AnnotationReference(target, caller, member, source, record.descriptor));
                    }
                }
            }
        }
    }

    private static final class ClassInventoryVisitor extends ClassVisitor {
        private final String className;
        private final String source;
        private final Set<String> targets;
        private final boolean collectInventory;
        private final List<MemberReference> ordinaryReferences;
        private final List<AnnotationReference> annotationReferences;
        private final List<ReflectionCallSite> reflectionSites;

        private final ElementAnnotations classAnnotations = new ElementAnnotations();
        private final List<FieldInventory> fields = new ArrayList<>();
        private final List<MethodInventory> methods = new ArrayList<>();
        private final List<RecordComponentInventory> recordComponents = new ArrayList<>();
        private boolean isRecord;

        ClassInventory inventory;

        ClassInventoryVisitor(String className, String source, Set<String> targets, boolean collectInventory,
                             List<MemberReference> ordinaryReferences,
                             List<AnnotationReference> annotationReferences,
                             List<ReflectionCallSite> reflectionSites) {
            super(Opcodes.ASM9);
            this.className = className;
            this.source = source;
            this.targets = targets;
            this.collectInventory = collectInventory;
            this.ordinaryReferences = ordinaryReferences;
            this.annotationReferences = annotationReferences;
            this.reflectionSites = reflectionSites;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return classAnnotations.visit(descriptor, visible);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            // ACC_RECORD is the authoritative signal. Inferring "is a record" from the presence of a
            // Record attribute's components would call a zero-component record (record Empty() {})
            // a plain class.
            isRecord = (access & Opcodes.ACC_RECORD) != 0;
        }

        @Override
        public org.objectweb.asm.RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            recordComponents.add(new RecordComponentInventory(name, descriptor));
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            ElementAnnotations annotations = new ElementAnnotations();
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotations.visit(annotationDescriptor, visible);
                }

                @Override
                public void visitEnd() {
                    if (collectInventory) {
                        fields.add(new FieldInventory(name, descriptor, access, annotations.descriptors()));
                    }
                    annotations.emitReferences(className, name, source, targets, annotationReferences);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            String member = name + descriptor;
            ElementAnnotations annotations = new ElementAnnotations();
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotations.visit(annotationDescriptor, visible);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor,
                                            boolean isInterface) {
                    String ownerName = owner.replace('/', '.');
                    if (isReflective(ownerName, methodName)) {
                        reflectionSites.add(new ReflectionCallSite(
                                ownerName, methodName, methodDescriptor, opcodeName(opcode),
                                className, name, descriptor, source));
                    }
                    if (targets.contains(ownerName)) {
                        ordinaryReferences.add(new MemberReference(
                                ownerName, methodName, methodDescriptor, opcodeName(opcode),
                                className, name, descriptor, source));
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                    String ownerName = owner.replace('/', '.');
                    if (targets.contains(ownerName)) {
                        ordinaryReferences.add(new MemberReference(
                                ownerName, fieldName, fieldDescriptor, opcodeName(opcode),
                                className, name, descriptor, source));
                    }
                }

                @Override
                public void visitEnd() {
                    if (collectInventory) {
                        methods.add(new MethodInventory(name, descriptor, access,
                                parseParameterTypes(descriptor), annotations.descriptors()));
                    }
                    annotations.emitReferences(className, member, source, targets, annotationReferences);
                }
            };
        }

        @Override
        public void visitEnd() {
            classAnnotations.emitReferences(className, "class", source, targets, annotationReferences);
            if (collectInventory) {
                inventory = new ClassInventory(className, source, fields, methods, recordComponents,
                        isRecord, classAnnotations.descriptors());
            }
        }

    }
}
