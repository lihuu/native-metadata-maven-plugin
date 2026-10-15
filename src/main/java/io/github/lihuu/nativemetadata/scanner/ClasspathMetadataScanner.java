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
import java.util.LinkedHashSet;
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
        return scan(classpath, ownedConfigs, MetadataSourceScope.none());
    }

    @NotNull
    public ClasspathMetadataResult scan(@NotNull List<String> classpath, @NotNull List<Path> ownedConfigs,
                                        @NotNull MetadataSourceScope scope) throws IOException {
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
                        processResource(file.toString(), file.toAbsolutePath().normalize(), false,
                                resName, content, ownedResources, sources, registrationsByClass, scope);
                    }
                }
            } else if (Files.isRegularFile(path) && (entry.endsWith(".jar") || entry.endsWith(".zip"))) {
                Path jarPath = path.toAbsolutePath().normalize();
                try (JarFile jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                    // versionedStream() is the JVM's own multi-release selection. Note that the JAR
                    // specification does not allow versioned resources under META-INF, and this
                    // method implements that: META-INF/native-image/... is read from its
                    // unversioned entry only, whatever META-INF/versions/N/... contains.
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
                            String source = jarPath + "!/" + jarEntry.getRealName();
                            processResource(source, jarPath, true, resName, content, ownedResources,
                                    sources, registrationsByClass, scope);
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

    private void processResource(String source, Path physicalPath, boolean insideArchive,
                                 String resourceName, String content,
                                 Map<String, String> ownedResources,
                                 List<DiscoveredMetadataSource> sources,
                                 Map<String, List<DiscoveredRegistration>> registrationsByClass,
                                 MetadataSourceScope scope) {
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

        // Ownership is decided by physical location, not by the resource path. A dependency JAR that
        // happens to contain META-INF/native-image/<group>/<artifact>/reflect-config.json - which is
        // exactly what a published library looks like, and which a project's own resource path can
        // collide with - is a dependency registration, not a copy of this project's configuration.
        MetadataScope metadataScope;
        Boolean matchesSource = null;
        if (!insideArchive && isInsideAny(physicalPath, scope.generatedRoots())) {
            metadataScope = MetadataScope.GENERATED;
        } else if (!insideArchive && isInsideAny(physicalPath, scope.projectOutputRoots())) {
            if (ownedResources.containsKey(resourceName)) {
                metadataScope = MetadataScope.PACKAGED_PROJECT;
                matchesSource = content.strip().equals(ownedResources.get(resourceName).strip());
            } else {
                // Build output that is not one of the project's own files: AOT or other generated
                // resources copied into the output directory.
                metadataScope = MetadataScope.GENERATED;
            }
        } else if (source.contains("/picocli-generated/")) {
            metadataScope = MetadataScope.GENERATED;
        } else {
            metadataScope = MetadataScope.DEPENDENCY;
        }

        boolean consumedByNativeBuild = isConsumedByNativeBuild(physicalPath, metadataScope, scope);

        DiscoveredMetadataSource metaSource = new DiscoveredMetadataSource(
                source,
                resourceName,
                metadataScope,
                true,
                doc.entries().size(),
                doc.unsupportedEntryCount(),
                matchesSource,
                consumedByNativeBuild,
                doc
        );
        sources.add(metaSource);

        if (metadataScope != MetadataScope.PACKAGED_PROJECT) {
            for (ReflectionEntry entry : doc.entries()) {
                registrationsByClass.computeIfAbsent(entry.name(), k -> new ArrayList<>())
                        .add(new DiscoveredRegistration(metaSource, entry));
            }
        }
    }

    /**
     * A registration can only justify deleting the project's own entry if the native build reads it.
     * Compiled-only and provided dependencies are on the Maven compile classpath but not in the image.
     */
    private static boolean isConsumedByNativeBuild(Path physicalPath, MetadataScope metadataScope,
                                                   MetadataSourceScope scope) {
        if (metadataScope == MetadataScope.PACKAGED_PROJECT) {
            return false;
        }
        if (metadataScope == MetadataScope.GENERATED) {
            return isInsideAny(physicalPath, scope.generatedRoots())
                    || isInsideAny(physicalPath, scope.projectOutputRoots());
        }
        for (String entry : scope.nativeInputClasspath()) {
            Path entryPath;
            try {
                entryPath = Path.of(entry).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue;
            }
            if (Files.isDirectory(entryPath)) {
                if (physicalPath.startsWith(entryPath)) {
                    return true;
                }
            } else if (physicalPath.equals(entryPath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideAny(Path physicalPath, List<Path> roots) {
        for (Path root : roots) {
            if (physicalPath.startsWith(root.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
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

    /** Resource names that came from the project's own configuration, for ownership reporting. */
    @NotNull
    public static Set<String> ownedResourceNames(@NotNull List<Path> ownedConfigs) {
        Set<String> names = new LinkedHashSet<>();
        for (Path ownedConfig : ownedConfigs) {
            String name = extractResourceName(ownedConfig.toString());
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }
}
