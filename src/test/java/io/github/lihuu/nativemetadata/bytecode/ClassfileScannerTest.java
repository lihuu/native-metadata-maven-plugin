package io.github.lihuu.nativemetadata.bytecode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.lihuu.nativemetadata.support.JavaFixtureCompiler;

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

        // Java 17 baseline: the fixture must be compilable and readable on the oldest supported JVM.
        JavaFixtureCompiler.compile(classesDir, List.of(sourceFile));

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

    @Test
    void zeroComponentRecordIsRecognisedAsARecord(@TempDir Path tempDir) throws IOException {
        // A record with no components has no Record attribute components, so "is a record" cannot be
        // inferred from them: the ACC_RECORD flag is the only authoritative signal.
        Path src = tempDir.resolve("example");
        Files.createDirectories(src);
        Path sourceFile = src.resolve("Empty.java");
        Files.writeString(sourceFile, "package example; public record Empty() { }");

        Path classesDir = tempDir.resolve("classes-empty");
        JavaFixtureCompiler.compile(classesDir, List.of(sourceFile));

        ClassInventory inventory = new ClassfileScanner()
                .scan(List.of(classesDir.toString()), Set.of("example.Empty"))
                .findClass("example.Empty")
                .orElse(null);

        assertNotNull(inventory, "example.Empty must be present");
        assertTrue(inventory.isRecord(), "a zero-component record is still a record");
        assertTrue(inventory.recordComponents().isEmpty());
    }

    @Test
    void malformedClassFailsWithItsSourceInsteadOfBeingSkipped(@TempDir Path tempDir) throws IOException {
        Path classesDir = tempDir.resolve("classes-broken");
        Files.createDirectories(classesDir);
        Path broken = classesDir.resolve("Broken.class");
        Files.write(broken, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0});

        ClassfileScanner scanner = new ClassfileScanner();
        IOException tooShort = assertThrows(IOException.class,
                () -> scanner.scan(List.of(classesDir.toString()), Set.of("example.Empty")));
        assertTrue(tooShort.getMessage().contains(broken.toString()),
                "the error must name the file: " + tooShort.getMessage());

        Files.write(broken, new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61, 1, 2, 3, 4});
        IOException unparsable = assertThrows(IOException.class,
                () -> scanner.scan(List.of(classesDir.toString()), Set.of("example.Empty")));
        assertTrue(unparsable.getMessage().contains(broken.toString()),
                "the error must name the file: " + unparsable.getMessage());
    }

}
