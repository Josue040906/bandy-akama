package com.bandyakama.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class ConversationStateMerger {
    private final ObjectMapper objectMapper;

    public ConversationStateMerger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> mergeToolArguments(
            JsonNode previousState,
            String toolName,
            Map<String, Object> incomingArguments
    ) {
        Map<String, Object> merged = new HashMap<>();
        Object previousCriteria = null;
        boolean continuingSameTool = previousState != null
                && toolName.equals(previousState.path("toolName").asText());
        if (continuingSameTool
                && previousState.path("arguments").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields =
                    previousState.path("arguments").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                merged.put(field.getKey(), objectMapper.convertValue(
                        field.getValue(),
                        Object.class
                ));
            }
            previousCriteria = merged.get("criteriaJson");
        }
        merged.putAll(incomingArguments);

        Object incomingCriteria = incomingArguments.get("criteriaJson");
        if (incomingCriteria instanceof String incomingJson
                && previousCriteria instanceof String previousJson
                && continuingSameTool) {
            merged.put(
                    "criteriaJson",
                    mergeCriteria(previousJson, incomingJson)
            );
        }
        return merged;
    }

    public JsonNode stateAfterToolCall(
            String toolName,
            Map<String, Object> arguments
    ) {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("intent", "TOOL_CALL");
        state.put("toolName", toolName);
        state.set("arguments", objectMapper.valueToTree(arguments));
        Object criteriaJson = arguments.get("criteriaJson");
        if (criteriaJson instanceof String text) {
            try {
                state.set("criteria", objectMapper.readTree(text));
            } catch (JsonProcessingException exception) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Les critères produits par le modèle ne sont pas valides.",
                        exception
                );
            }
        }
        return state;
    }

    private String mergeCriteria(String previousJson, String incomingJson) {
        try {
            JsonNode previous = objectMapper.readTree(previousJson);
            JsonNode incoming = objectMapper.readTree(incomingJson);
            if (!previous.isObject() || !incoming.isObject()
                    || !previous.path("filters").isArray()
                    || !incoming.path("filters").isArray()) {
                return incomingJson;
            }

            ObjectNode merged = ((ObjectNode) previous).deepCopy();
            ArrayNode filters = merged.withArray("filters");
            Map<String, JsonNode> filtersByField = new java.util.LinkedHashMap<>();
            for (JsonNode filter : filters) {
                String field = filter.path("field").asText("");
                if (!field.isBlank()) {
                    filtersByField.put(field, filter.deepCopy());
                }
            }

            for (JsonNode filter : incoming.path("filters")) {
                String field = filter.path("field").asText("");
                if (field.isBlank()) {
                    continue;
                }
                if (filter.path("remove").asBoolean(false)) {
                    filtersByField.remove(field);
                } else {
                    ObjectNode normalized = filter.deepCopy();
                    normalized.remove("remove");
                    filtersByField.put(field, normalized);
                }
            }

            ArrayNode mergedFilters = objectMapper.createArrayNode();
            filtersByField.values().forEach(mergedFilters::add);
            merged.set("filters", mergedFilters);
            incoming.fields().forEachRemaining(entry -> {
                if (!"filters".equals(entry.getKey())) {
                    merged.set(entry.getKey(), entry.getValue());
                }
            });
            return objectMapper.writeValueAsString(merged);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Les critères produits par le modèle ne sont pas valides.",
                    exception
            );
        }
    }
}
