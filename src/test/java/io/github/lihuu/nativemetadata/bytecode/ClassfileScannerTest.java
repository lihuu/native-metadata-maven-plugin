package io.github.lihuu.nativemetadata.bytecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassfileScannerTest {

    @Test
    void scansRecordBytecodeAndReferences(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("example");
        Files.createDirectories(src);
        Path sourceFile = src.resolve("Message.java");
        Files.writeString(sourceFile, """
                package example;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @interface Bound { Class<?> value(); }
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @interface Binding { Bound[] value(); }
                @Binding({@Bound(Message.class)})
                public record Message(String text, int count) {
                    public static Message create(String text) { return new Message(text, 1); }
                    public String upper() { return text.toUpperCase(); }
                    public static Object reflected(Message message) throws Exception {
                        return Message.class.getDeclaredMethod("upper").invoke(message);
                    }
                }
                """);

        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler required");
        int exitCode = compiler.run(null, null, null,
                "--release", "25",
                "-d", classesDir.toString(),
                sourceFile.toString());
        assertEquals(0, exitCode, "Compilation of test fixture should succeed");

        ClassfileScanner scanner = new ClassfileScanner();
        BytecodeInventory inventory = scanner.scan(List.of(classesDir.toString()), Set.of("example.Message"));

        ClassInventory messageClass = inventory.findClass("example.Message").orElse(null);
        assertNotNull(messageClass, "example.Message should be present in inventory");
        assertTrue(messageClass.isRecord(), "example.Message should be recognized as a record");

        List<String> fieldNames = messageClass.fields().stream().map(FieldInventory::name).sorted().toList();
        assertEquals(List.of("count", "text"), fieldNames);

        MethodInventory canonicalConstructor = messageClass.methods().stream()
                .filter(m -> m.name().equals("<init>") && m.parameterTypes().size() == 2)
                .findFirst().orElse(null);
        assertNotNull(canonicalConstructor);
        assertEquals(List.of("java.lang.String", "int"), canonicalConstructor.parameterTypes());

        List<String> recordComponentNames = messageClass.recordComponents().stream()
                .map(RecordComponentInventory::name).toList();
        assertEquals(List.of("text", "count"), recordComponentNames);

        List<MemberReference> references = inventory.findOrdinaryReferences("example.Message");
        assertTrue(references.stream().anyMatch(r -> r.member().equals("<init>") && r.callerMethod().equals("create")),
                "Should have ordinary reference to <init> from create method");

        List<ReflectionCallSite> reflectionSites = inventory.reflectionSites();
        assertTrue(reflectionSites.stream().anyMatch(s -> s.caller().equals("example.Message") && s.member().equals("getDeclaredMethod")),
                "Should identify Class.getDeclaredMethod callsite");

        List<AnnotationReference> annotationReferences = inventory.findAnnotationReferences("example.Message");
        assertFalse(annotationReferences.isEmpty(), "Should capture nested annotation reference to Message.class");
        assertEquals("example.Message", annotationReferences.get(0).target());
    }
}
