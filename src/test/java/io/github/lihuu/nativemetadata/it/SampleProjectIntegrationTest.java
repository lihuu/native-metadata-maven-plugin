package io.github.lihuu.nativemetadata.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleProjectIntegrationTest {

    @Test
    void verifiesSampleProjectBuildPruningAndNativeExecution(@TempDir Path tempDir) throws Exception {
        Path pomFile = tempDir.resolve("pom.xml");
        Files.writeString(pomFile, """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample-app</artifactId>
                    <version>1.0-SNAPSHOT</version>

                    <properties>
                        <maven.compiler.release>25</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.15.0</version>
                            </plugin>
                            <plugin>
                                <groupId>io.github.lihuu</groupId>
                                <artifactId>native-metadata-maven-plugin</artifactId>
                                <version>1.0.0</version>
                                <executions>
                                    <execution>
                                        <id>prune-metadata</id>
                                        <phase>process-classes</phase>
                                        <goals>
                                            <goal>prune</goal>
                                        </goals>
                                        <configuration>
                                            <rulesFile>${project.basedir}/rules.json</rulesFile>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """);

        Path srcJava = tempDir.resolve("src/main/java/sample");
        Files.createDirectories(srcJava);

        Files.writeString(srcJava.resolve("Greeting.java"), """
                package sample;
                public class Greeting {
                    private String message;
                    public Greeting() { this.message = "hello"; }
                    public Greeting(String message) { this.message = message; }
                    public String getMessage() { return message; }
                    public void setMessage(String message) { this.message = message; }
                    public String greet(String target) { return message + " " + target; }
                }
                """);

        Files.writeString(srcJava.resolve("Main.java"), """
                package sample;
                import java.lang.reflect.Constructor;
                import java.lang.reflect.Method;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        Class<?> clazz = Class.forName("sample.Greeting");
                        Constructor<?> ctor = clazz.getDeclaredConstructor(String.class);
                        Object obj = ctor.newInstance("Native Reflection Works!");
                        Method method = clazz.getDeclaredMethod("greet", String.class);
                        Object res = method.invoke(obj, "Plugin");
                        System.out.println("OUTPUT:" + res);
                    }
                }
                """);

        Path resDir = tempDir.resolve("src/main/resources/META-INF/native-image/sample");
        Files.createDirectories(resDir);
        Path sourceConfig = resDir.resolve("reflect-config.json");
        String originalConfigJson = """
                [
                  {
                    "name": "sample.Greeting",
                    "allDeclaredFields": true,
                    "allDeclaredConstructors": true,
                    "allDeclaredMethods": true
                  },
                  {
                    "name": "sample.Greeting",
                    "allDeclaredFields": true,
                    "allDeclaredConstructors": true,
                    "allDeclaredMethods": true
                  }
                ]
                """;
        Files.writeString(sourceConfig, originalConfigJson);

        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, """
                [
                  {
                    "resource": "META-INF/native-image/sample/reflect-config.json",
                    "type": "sample.Greeting",
                    "replacement": {
                      "name": "sample.Greeting",
                      "fields": [{"name": "message"}],
                      "methods": [
                        {"name": "<init>", "parameterTypes": ["java.lang.String"]},
                        {"name": "greet", "parameterTypes": ["java.lang.String"]}
                      ]
                    }
                  }
                ]
                """);

        // Ensure plugin is installed to local repository if not already present
        Path localRepoArtifact = Path.of(System.getProperty("user.home"),
                ".m2/repository/io/github/lihuu/native-metadata-maven-plugin/1.0.0/native-metadata-maven-plugin-1.0.0.jar");
        if (!Files.isRegularFile(localRepoArtifact)) {
            Path projectRoot = Path.of("").toAbsolutePath();
            ProcessBuilder installPb = new ProcessBuilder("mvn", "install", "-DskipTests");
            installPb.directory(projectRoot.toFile());
            installPb.redirectErrorStream(true);
            Process installProc = installPb.start();
            installProc.getInputStream().readAllBytes();
            installProc.waitFor();
        }

        // Execute mvn package on sample project
        ProcessBuilder pb = new ProcessBuilder("mvn", "clean", "package");
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, "Maven build should succeed:\n" + output);

        // AC-5: Source file was not modified
        assertEquals(originalConfigJson, Files.readString(sourceConfig), "Source file in src/ must remain unmodified");

        // AC-6: Inspect built jar file and verify it carries pruned config
        Path jarFile = tempDir.resolve("target/sample-app-1.0-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(jarFile), "Packaged jar should exist");

        String jarMetadataContent;
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/native-image/sample/reflect-config.json");
            assertNotNull(entry, "Jar must contain reflect-config.json");
            try (InputStream is = zip.getInputStream(entry)) {
                jarMetadataContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        assertFalse(jarMetadataContent.contains("allDeclaredMethods"), "Packaged config must not be broad");
        assertTrue(jarMetadataContent.contains("greet"), "Packaged config must carry narrowed greet method");

        // Verify report files exist
        assertTrue(Files.isRegularFile(tempDir.resolve("target/native-metadata/report.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve("target/native-metadata/report.md")));
        assertTrue(Files.isRegularFile(tempDir.resolve("target/native-metadata/changelog.json")));

        // AC-7: If native-image is available, compile to native binary and execute
        String javaHome = System.getenv("JAVA_HOME");
        Path nativeImageBin = (javaHome != null)
                ? Path.of(javaHome, "bin", "native-image")
                : Path.of("/Users/lihu/Library/Java/JavaVirtualMachines/graalvm-ce-25.0.2/Contents/Home/bin/native-image");

        if (Files.isExecutable(nativeImageBin)) {
            Path nativeOutput = tempDir.resolve("target/sample-native");
            ProcessBuilder nativePb = new ProcessBuilder(
                    nativeImageBin.toString(),
                    "-cp", jarFile.toString(),
                    "-H:+UnlockExperimentalVMOptions",
                    "--no-fallback",
                    "sample.Main",
                    nativeOutput.toString()
            );
            nativePb.directory(tempDir.toFile());
            nativePb.redirectErrorStream(true);
            Process nativeProc = nativePb.start();
            String nativeBuildOutput = new String(nativeProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int nativeExitCode = nativeProc.waitFor();
            assertEquals(0, nativeExitCode, "Native-image compilation should succeed:\n" + nativeBuildOutput);

            assertTrue(Files.isExecutable(nativeOutput), "Native binary should be created");
            ProcessBuilder runPb = new ProcessBuilder(nativeOutput.toString());
            Process runProc = runPb.start();
            String runOutput = new String(runProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int runExitCode = runProc.waitFor();
            assertEquals(0, runExitCode);
            assertTrue(runOutput.contains("OUTPUT:Native Reflection Works! Plugin"),
                    "Native executable should execute reflective access successfully, output: " + runOutput);
        }
    }
}
