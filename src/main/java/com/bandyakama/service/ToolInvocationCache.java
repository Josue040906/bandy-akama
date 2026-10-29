package com.bandyakama.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

final class ToolInvocationCache {
    private final ObjectMapper objectMapper;
    private final Map<String, Object> results = new HashMap<>();

    ToolInvocationCache(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    boolean contains(String toolName, Map<String, Object> arguments) {
        return results.containsKey(key(toolName, arguments));
    }

    Object get(String toolName, Map<String, Object> arguments) {
        return results.get(key(toolName, arguments));
    }

    void put(
            String toolName,
            Map<String, Object> arguments,
            Object result
    ) {
        results.put(key(toolName, arguments), result);
    }

    private String key(String toolName, Map<String, Object> arguments) {
        JsonNode argumentsNode = objectMapper.valueToTree(arguments);
        return toolName + ":" + canonicalize(argumentsNode);
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            Map<String, JsonNode> sortedFields = new TreeMap<>();
            value.fields().forEachRemaining(entry ->
                    sortedFields.put(entry.getKey(), entry.getValue())
            );
            sortedFields.forEach((name, child) ->
                    canonical.set(name, canonicalize(child))
            );
            return canonical;
        }
        if (value.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            value.forEach(child -> canonical.add(canonicalize(child)));
            return canonical;
        }
        return value.deepCopy();
    }
}
