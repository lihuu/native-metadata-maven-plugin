package com.licode.nativemetadata.pruner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.licode.nativemetadata.model.MetadataDocument;
import com.licode.nativemetadata.model.ReflectionEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class PruningRulesParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @NotNull
    public static List<PruningRule> parse(@NotNull Path path) throws IOException {
        String content = Files.readString(path);
        return parse(content);
    }

    @NotNull
    public static List<PruningRule> parse(@NotNull String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arrayNode;
            if (root.isArray()) {
                arrayNode = root;
            } else if (root.isObject() && root.has("rules") && root.get("rules").isArray()) {
                arrayNode = root.get("rules");
            } else {
                throw new IllegalArgumentException("Rules file must be a JSON array or object containing a 'rules' array");
            }

            List<PruningRule> rules = new ArrayList<>();
            for (JsonNode node : arrayNode) {
                if (!node.isObject()) {
                    continue;
                }
                String resource = node.path("resource").asText(null);
                if (resource == null || resource.isBlank()) {
                    throw new IllegalArgumentException("Rule missing required 'resource' field: " + node);
                }

                String type = null;
                if (node.hasNonNull("type")) {
                    type = node.get("type").asText();
                } else if (node.hasNonNull("name")) {
                    type = node.get("name").asText();
                }
                if (type == null || type.isBlank()) {
                    throw new IllegalArgumentException("Rule missing required 'type' field: " + node);
                }

                Map<String, Object> condition = null;
                if (node.hasNonNull("condition") && node.get("condition").isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> condMap = MAPPER.convertValue(node.get("condition"), Map.class);
                    condition = condMap;
                }

                JsonNode replNode = node.get("replacement");
                if (replNode == null || !replNode.isObject()) {
                    throw new IllegalArgumentException("Rule missing required 'replacement' object: " + node);
                }

                ReflectionEntry replacement = MetadataDocument.parseReflectionEntry(replNode);
                if (replacement == null) {
                    throw new IllegalArgumentException("Rule replacement could not be parsed: " + replNode);
                }

                rules.add(new PruningRule(resource, type, condition, replacement));
            }
            return Collections.unmodifiableList(rules);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse pruning rules: " + e.getMessage(), e);
        }
    }
}
