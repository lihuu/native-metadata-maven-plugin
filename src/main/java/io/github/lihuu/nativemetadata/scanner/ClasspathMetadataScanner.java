package io.github.lihuu.nativemetadata.scanner;

import io.github.lihuu.nativemetadata.model.MetadataDocument;
import io.github.lihuu.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public final class ClasspathMetadataScanner {

    private static final Set<String> REFLECTION_RESOURCE_NAMES = Set.of(
            "reflect-config.json",
            "reflection-config.json",
            "reachability-metadata.json"
    );

    @NotNull
    public ClasspathMetadataResult scan(@NotNull List<String> classpath, @NotNull List<Path> ownedConfigs) throws IOException {
        Map<String, String> ownedResources = new LinkedHashMap<>();
        for (Path ownedPath : ownedConfigs) {
            if (Files.isRegularFile(ownedPath)) {
                String resName = extractResourceName(ownedPath.toString());
                if (!resName.isEmpty()) {
                    ownedResources.put(resName, Files.readString(ownedPath));
                }
            }
        }

        List<DiscoveredMetadataSource> sources = new ArrayList<>();
        Map<String, List<DiscoveredRegistration>> registrationsByClass = new LinkedHashMap<>();

        for (String entry : classpath) {
            Path path = Path.of(entry);
            if (!Files.exists(path)) {
                continue;
            }

            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    List<Path> jsonFiles = stream.filter(Files::isRegularFile)
                            .filter(f -> isReflectionResource(f.getFileName().toString()))
                            .sorted()
                            .toList();
                    for (Path file : jsonFiles) {
                        String resName = extractResourceName(file.toString());
                        if (resName.isEmpty()) {
                            continue;
                        }
                        String content = Files.readString(file);
                        processResource(file.toString(), resName, content, ownedResources, sources, registrationsByClass);
                    }
                }
            } else if (Files.isRegularFile(path) && (entry.endsWith(".jar") || entry.endsWith(".zip"))) {
                try (JarFile jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                    List<JarEntry> jarEntries = jar.versionedStream()
                            .filter(e -> !e.isDirectory())
                            .filter(e -> isReflectionResource(Path.of(e.getName()).getFileName().toString()))
                            .filter(e -> e.getName().contains("META-INF/native-image/"))
                            .sorted((e1, e2) -> e1.getName().compareTo(e2.getName()))
                            .toList();
                    for (JarEntry jarEntry : jarEntries) {
                        String resName = extractResourceName(jarEntry.getName());
                        try (InputStream is = jar.getInputStream(jarEntry)) {
                            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            String source = path + "!/" + jarEntry.getRealName();
                            processResource(source, resName, content, ownedResources, sources, registrationsByClass);
                        }
                    }
                }
            }
        }

        return new ClasspathMetadataResult(
                Collections.unmodifiableList(sources),
                Collections.unmodifiableMap(registrationsByClass)
        );
    }

    private void processResource(String source, String resourceName, String content,
                                 Map<String, String> ownedResources,
                                 List<DiscoveredMetadataSource> sources,
                                 Map<String, List<DiscoveredRegistration>> registrationsByClass) {
        MetadataDocument doc;
        try {
            doc = MetadataDocument.parse(content);
        } catch (IllegalArgumentException e) {
            return;
        }

        if (doc.format() == MetadataDocument.Format.REACHABILITY_METADATA && doc.entries().isEmpty()
                && !doc.rawRoot().containsKey("reflection")) {
            return;
        }

        MetadataScope scope;
        Boolean matchesSource = null;
        if (ownedResources.containsKey(resourceName)) {
            scope = MetadataScope.PACKAGED_PROJECT;
            matchesSource = content.strip().equals(ownedResources.get(resourceName).strip());
        } else if (source.contains("/picocli-generated/")) {
            scope = MetadataScope.GENERATED;
        } else {
            scope = MetadataScope.DEPENDENCY;
        }

        DiscoveredMetadataSource metaSource = new DiscoveredMetadataSource(
                source,
                resourceName,
                scope,
                true,
                doc.entries().size(),
                0,
                matchesSource,
                doc
        );
        sources.add(metaSource);

        if (scope != MetadataScope.PACKAGED_PROJECT) {
            for (ReflectionEntry entry : doc.entries()) {
                registrationsByClass.computeIfAbsent(entry.name(), k -> new ArrayList<>())
                        .add(new DiscoveredRegistration(metaSource, entry));
            }
        }
    }

    public static boolean isReflectionResource(@NotNull String fileName) {
        return REFLECTION_RESOURCE_NAMES.contains(fileName);
    }

    @NotNull
    public static String extractResourceName(@NotNull String pathString) {
        String normalized = pathString.replace('\\', '/');
        int metaInfIdx = normalized.indexOf("META-INF/");
        if (metaInfIdx >= 0) {
            return normalized.substring(metaInfIdx);
        }
        return "";
    }
}
