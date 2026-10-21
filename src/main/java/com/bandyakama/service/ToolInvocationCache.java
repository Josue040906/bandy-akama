package com.bandyakama.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

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
        return toolName + ":" + objectMapper.valueToTree(arguments);
    }
}
