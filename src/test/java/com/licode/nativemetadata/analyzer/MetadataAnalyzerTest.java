package com.licode.nativemetadata.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataAnalyzerTest {

    @Test
    void analyzesFixtureWithFieldsSignaturesSourcesTracesAndOverlaps(@TempDir Path tempDir) throws IOException {
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
        int exitCode = compiler.run(null, null, null,
                "--release", "25",
                "-d", classesDir.toString(),
                sourceFile.toString());
        assertEquals(0, exitCode);

        // Dependency jar that also registers Message
        Path vendorJar = tempDir.resolve("vendor.jar");
        String vendorResource = "META-INF/native-image/vendor/library/reflect-config.json";
        String vendorJson = """
                [
                  {
                    "name": "example.Message",
                    "allDeclaredMethods": true
                  }
                ]
                """;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(vendorJar))) {
            zos.putNextEntry(new ZipEntry(vendorResource));
            zos.write(vendorJson.getBytes());
            zos.closeEntry();
        }

        // Trace file
        Path traceFile = tempDir.resolve("trace.json");
        String traceJson = """
                {
                  "reflection": [
                    {
                      "type": "example.Message",
                      "methods": [{"name": "upper", "parameterTypes": []}],
                      "queriedMethods": [{"name": "reflected", "parameterTypes": ["example.Message"]}]
                    }
                  ]
                }
                """;
        Files.writeString(traceFile, traceJson);

        // Owned config
        Path ownedConfig = tempDir.resolve("reflect-config.json");
        String ownedJson = """
                [
                  {
                    "name": "example.Message",
                    "allDeclaredFields": true,
                    "allDeclaredConstructors": true,
                    "allDeclaredMethods": true
                  }
                ]
                """;
        Files.writeString(ownedConfig, ownedJson);

        byte[] ownedBefore = Files.readAllBytes(ownedConfig);

        MetadataAnalyzer analyzer = new MetadataAnalyzer();
        AnalysisReport report = analyzer.analyze(
                List.of(ownedConfig),
                List.of(classesDir.toString(), vendorJar.toString()),
                List.of(traceFile)
        );

        // Assert input unmodified (AC-2)
        assertEquals(new String(ownedBefore), Files.readString(ownedConfig));

        // Summary assertions
        assertEquals(1, report.summary().ownedTypes());
        assertEquals(1, report.summary().resolvedTypes());
        assertTrue(report.summary().traceProvided());
        assertEquals(1, report.summary().protectedOverlaps());
        assertEquals(2, report.summary().registeredFields());
        assertEquals(1, report.summary().registeredConstructors());

        // Class analysis assertions
        ClassAnalysis classAnalysis = report.classes().stream()
                .filter(c -> c.name().equals("example.Message"))
                .findFirst().orElse(null);
        assertNotNull(classAnalysis);
        assertEquals(List.of("count", "text"), classAnalysis.registered().fields());
        assertEquals(1, classAnalysis.registered().constructors().size());
        assertEquals(List.of("java.lang.String", "int"), classAnalysis.registered().constructors().get(0).parameterTypes());

        // Trace evidence: invocations vs queries separated
        assertTrue(classAnalysis.trace().provided());
        assertTrue(classAnalysis.trace().typePresent());
        assertEquals(1, classAnalysis.trace().invocations().size());
        assertEquals("upper", classAnalysis.trace().invocations().get(0).name());
        assertEquals(1, classAnalysis.trace().queries().size());
        assertEquals("reflected", classAnalysis.trace().queries().get(0).name());

        // Review methods: upper is invoked in trace -> not in review; record accessors text/count have hints -> not in review; create has no hint and not in trace -> in review
        List<String> reviewNames = classAnalysis.reviewMethods().stream().map(m -> m.name()).toList();
        assertTrue(reviewNames.contains("create"));
        assertFalse(reviewNames.contains("upper"));
        assertFalse(reviewNames.contains("text"));

        // Overlap: also registered by vendor
        assertFalse(classAnalysis.alsoRegisteredBy().isEmpty());
        assertEquals("example.Message", classAnalysis.alsoRegisteredBy().get(0).entry().name());

        // Markdown report formatting
        String markdown = MarkdownReportFormatter.format(report);
        assertTrue(markdown.contains("example.Message"));
        assertTrue(markdown.contains("自持 Native Image 反射配置分析"));
    }
}
