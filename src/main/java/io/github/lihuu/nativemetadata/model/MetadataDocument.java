package io.github.lihuu.nativemetadata.model;

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
    private final int unsupportedEntryCount;
    private final List<String> unsupportedEntryReasons;

    public MetadataDocument(@NotNull Format format,
                            @NotNull Map<String, Object> rawRoot,
                            @NotNull List<ReflectionEntry> entries) {
        this(format, rawRoot, entries, 0, Collections.emptyList());
    }

    public MetadataDocument(@NotNull Format format,
                            @NotNull Map<String, Object> rawRoot,
                            @NotNull List<ReflectionEntry> entries,
                            int unsupportedEntryCount,
                            @NotNull List<String> unsupportedEntryReasons) {
        this.format = format;
        this.rawRoot = Collections.unmodifiableMap(new LinkedHashMap<>(rawRoot));
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.unsupportedEntryCount = unsupportedEntryCount;
        this.unsupportedEntryReasons = Collections.unmodifiableList(new ArrayList<>(unsupportedEntryReasons));
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

    /**
     * Entries this parser could not model. They must not be written back out: serialising the parsed
     * entries alone would silently drop them, and a dropped registration is a permission removed by
     * accident. Callers are expected to refuse to rewrite such a document.
     */
    public int unsupportedEntryCount() {
        return unsupportedEntryCount;
    }

    @NotNull
    public List<String> unsupportedEntryReasons() {
        return unsupportedEntryReasons;
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
                ParseResult result = parseReflectionArray(root);
                return new MetadataDocument(Format.REFLECT_CONFIG, Collections.emptyMap(), result.entries(),
                        result.unsupportedCount(), result.reasons());
            } else if (root.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rootMap = MAPPER.readValue(json, Map.class);
                JsonNode reflectionNode = root.get("reflection");
                ParseResult result = (reflectionNode != null && reflectionNode.isArray())
                        ? parseReflectionArray(reflectionNode)
                        : new ParseResult(Collections.emptyList(), 0, Collections.emptyList());
                return new MetadataDocument(Format.REACHABILITY_METADATA, rootMap, result.entries(),
                        result.unsupportedCount(), result.reasons());
            } else {
                throw new IllegalArgumentException("Expected JSON array or JSON object for reflection metadata");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed JSON metadata: " + e.getMessage(), e);
        }
    }

    private record ParseResult(List<ReflectionEntry> entries, int unsupportedCount, List<String> reasons) {
    }

    private static ParseResult parseReflectionArray(JsonNode arrayNode) {
        List<ReflectionEntry> entries = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int index = 0;
        for (JsonNode node : arrayNode) {
            ReflectionEntry entry = parseReflectionEntry(node);
            if (entry != null) {
                entries.add(entry);
            } else {
                reasons.add("entry[" + index + "]: " + node);
            }
            index++;
        }
        return new ParseResult(entries, reasons.size(), reasons);
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
                // "no parameterTypes key" (every overload) and "parameterTypes: []" (the zero-arg
                // member) are different permissions; keep them apart instead of collapsing both to
                // an empty list on the way in and out again.
                boolean parameterTypesSpecified = mNode.has("parameterTypes");
                if (parameterTypesSpecified && mNode.get("parameterTypes").isArray()) {
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
                result.add(new MethodConfig(mName, paramTypes, parameterTypesSpecified, extra));
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

        if (unsupportedEntryCount > 0) {
            throw new IllegalStateException("Refusing to rewrite a document with "
                    + unsupportedEntryCount + " unsupported reflection entries; they would be lost: "
                    + unsupportedEntryReasons);
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
