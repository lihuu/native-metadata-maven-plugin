package com.licode.nativemetadata.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MetadataDocument {

    public enum Format {
        REFLECT_CONFIG,
        REACHABILITY_METADATA
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final Set<String> KNOWN_PROPERTIES = Set.of(
            "name", "type", "condition",
            "allDeclaredFields", "allPublicFields",
            "allDeclaredMethods", "allPublicMethods",
            "allDeclaredConstructors", "allPublicConstructors",
            "allDeclaredClasses", "allPublicClasses",
            "queryAllDeclaredMethods", "queryAllPublicMethods",
            "queryAllDeclaredConstructors", "queryAllPublicConstructors",
            "unsafeAllocated", "fields", "methods", "queriedMethods"
    );

    private final Format format;
    private final Map<String, Object> rawRoot;
    private final List<ReflectionEntry> entries;

    public MetadataDocument(@NotNull Format format,
                            @NotNull Map<String, Object> rawRoot,
                            @NotNull List<ReflectionEntry> entries) {
        this.format = format;
        this.rawRoot = Collections.unmodifiableMap(new LinkedHashMap<>(rawRoot));
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    @NotNull
    public Format format() {
        return format;
    }

    @NotNull
    public Map<String, Object> rawRoot() {
        return rawRoot;
    }

    @NotNull
    public List<ReflectionEntry> entries() {
        return entries;
    }

    @NotNull
    public static MetadataDocument parse(@NotNull Path path) throws IOException {
        String content = Files.readString(path);
        return parse(content);
    }

    @NotNull
    public static MetadataDocument parse(@NotNull String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isArray()) {
                List<ReflectionEntry> list = parseReflectionArray(root);
                return new MetadataDocument(Format.REFLECT_CONFIG, Collections.emptyMap(), list);
            } else if (root.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rootMap = MAPPER.readValue(json, Map.class);
                JsonNode reflectionNode = root.get("reflection");
                List<ReflectionEntry> list = (reflectionNode != null && reflectionNode.isArray())
                        ? parseReflectionArray(reflectionNode)
                        : Collections.emptyList();
                return new MetadataDocument(Format.REACHABILITY_METADATA, rootMap, list);
            } else {
                throw new IllegalArgumentException("Expected JSON array or JSON object for reflection metadata");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed JSON metadata: " + e.getMessage(), e);
        }
    }

    private static List<ReflectionEntry> parseReflectionArray(JsonNode arrayNode) {
        List<ReflectionEntry> entries = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            ReflectionEntry entry = parseReflectionEntry(node);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public static ReflectionEntry parseReflectionEntry(@NotNull JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String typeFieldName = "name";
        String name = null;
        if (node.hasNonNull("name")) {
            name = node.get("name").asText();
            typeFieldName = "name";
        } else if (node.hasNonNull("type")) {
            name = node.get("type").asText();
            typeFieldName = "type";
        }
        if (name == null || name.isBlank()) {
            return null;
        }

        Map<String, Object> condition = null;
        if (node.hasNonNull("condition") && node.get("condition").isObject()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> condMap = MAPPER.convertValue(node.get("condition"), Map.class);
            condition = condMap;
        }

        boolean allDeclaredFields = node.path("allDeclaredFields").asBoolean(false);
        boolean allPublicFields = node.path("allPublicFields").asBoolean(false);
        boolean allDeclaredMethods = node.path("allDeclaredMethods").asBoolean(false);
        boolean allPublicMethods = node.path("allPublicMethods").asBoolean(false);
        boolean allDeclaredConstructors = node.path("allDeclaredConstructors").asBoolean(false);
        boolean allPublicConstructors = node.path("allPublicConstructors").asBoolean(false);
        boolean allDeclaredClasses = node.path("allDeclaredClasses").asBoolean(false);
        boolean allPublicClasses = node.path("allPublicClasses").asBoolean(false);
        boolean queryAllDeclaredMethods = node.path("queryAllDeclaredMethods").asBoolean(false);
        boolean queryAllPublicMethods = node.path("queryAllPublicMethods").asBoolean(false);
        boolean queryAllDeclaredConstructors = node.path("queryAllDeclaredConstructors").asBoolean(false);
        boolean queryAllPublicConstructors = node.path("queryAllPublicConstructors").asBoolean(false);
        boolean unsafeAllocated = node.path("unsafeAllocated").asBoolean(false);

        List<FieldConfig> fields = new ArrayList<>();
        if (node.has("fields") && node.get("fields").isArray()) {
            for (JsonNode fNode : node.get("fields")) {
                if (fNode.hasNonNull("name")) {
                    String fName = fNode.get("name").asText();
                    Map<String, Object> extra = new LinkedHashMap<>();
                    fNode.fieldNames().forEachRemaining(key -> {
                        if (!key.equals("name")) {
                            extra.put(key, MAPPER.convertValue(fNode.get(key), Object.class));
                        }
                    });
                    fields.add(new FieldConfig(fName, extra));
                }
            }
        }

        List<MethodConfig> methods = parseMethodList(node.get("methods"));
        List<MethodConfig> queriedMethods = parseMethodList(node.get("queriedMethods"));

        Map<String, Object> extra = new LinkedHashMap<>();
        node.fieldNames().forEachRemaining(key -> {
            if (!KNOWN_PROPERTIES.contains(key)) {
                extra.put(key, MAPPER.convertValue(node.get(key), Object.class));
            }
        });

        return new ReflectionEntry(
                name,
                typeFieldName,
                condition,
                allDeclaredFields,
                allPublicFields,
                allDeclaredMethods,
                allPublicMethods,
                allDeclaredConstructors,
                allPublicConstructors,
                allDeclaredClasses,
                allPublicClasses,
                queryAllDeclaredMethods,
                queryAllPublicMethods,
                queryAllDeclaredConstructors,
                queryAllPublicConstructors,
                unsafeAllocated,
                fields,
                methods,
                queriedMethods,
                extra
        );
    }

    private static List<MethodConfig> parseMethodList(JsonNode arrayNode) {
        List<MethodConfig> result = new ArrayList<>();
        if (arrayNode == null || !arrayNode.isArray()) {
            return result;
        }
        for (JsonNode mNode : arrayNode) {
            if (mNode.hasNonNull("name")) {
                String mName = mNode.get("name").asText();
                List<String> paramTypes = new ArrayList<>();
                if (mNode.has("parameterTypes") && mNode.get("parameterTypes").isArray()) {
                    for (JsonNode pNode : mNode.get("parameterTypes")) {
                        paramTypes.add(pNode.asText());
                    }
                }
                Map<String, Object> extra = new LinkedHashMap<>();
                mNode.fieldNames().forEachRemaining(key -> {
                    if (!key.equals("name") && !key.equals("parameterTypes")) {
                        extra.put(key, MAPPER.convertValue(mNode.get(key), Object.class));
                    }
                });
                result.add(new MethodConfig(mName, paramTypes, extra));
            }
        }
        return result;
    }

    @NotNull
    public String serialize(@NotNull List<ReflectionEntry> updatedEntries) {
        List<Map<String, Object>> entriesList = new ArrayList<>();
        for (ReflectionEntry entry : updatedEntries) {
            entriesList.add(entry.toMap());
        }

        try {
            if (format == Format.REFLECT_CONFIG) {
                return MAPPER.writeValueAsString(entriesList) + "\n";
            } else {
                Map<String, Object> outputMap = new LinkedHashMap<>(rawRoot);
                outputMap.put("reflection", entriesList);
                return MAPPER.writeValueAsString(outputMap) + "\n";
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reflection metadata: " + e.getMessage(), e);
        }
    }
}
