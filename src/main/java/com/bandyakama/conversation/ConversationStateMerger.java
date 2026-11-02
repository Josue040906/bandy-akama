package com.bandyakama.conversation;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies parameter-level continuation policies without knowing the business
 * meaning of a tool or of its arguments.
 */
@Component
public class ConversationStateMerger {
    private final ObjectMapper objectMapper;

    public ConversationStateMerger(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public Map<String, Object> mergeToolArguments(
            JsonNode previousState,
            ToolDefinition tool,
            Map<String, Object> incomingArguments
    ) {
        Objects.requireNonNull(incomingArguments, "incomingArguments must not be null.");
        Map<String, Object> merged = new HashMap<>();
        boolean continuingSameTool = tool != null
                && previousState != null
                && tool.getName().equals(previousState.path("toolName").asText());

        if (continuingSameTool && previousState.path("arguments").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields =
                    previousState.path("arguments").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                merged.put(field.getKey(), objectMapper.convertValue(
                        field.getValue(),
                        Object.class
                ));
            }
        }

        Map<String, ToolParameter> parameterPolicies = new HashMap<>();
        if (tool != null && tool.getParameters() != null) {
            tool.getParameters().forEach(parameter ->
                    parameterPolicies.put(parameter.getName(), parameter)
            );
        }

        for (Map.Entry<String, Object> incoming : incomingArguments.entrySet()) {
            Object previous = merged.get(incoming.getKey());
            ToolParameter parameter = parameterPolicies.get(incoming.getKey());
            if (continuingSameTool
                    && previous instanceof String previousJson
                    && incoming.getValue() instanceof String incomingJson
                    && parameter != null
                    && parameter.getMergeStrategy()
                    == ToolParameter.MergeStrategy.MERGE_JSON) {
                merged.put(
                        incoming.getKey(),
                        mergeJson(
                                previousJson,
                                incomingJson,
                                parameter.getMergeKey(),
                                parameter.getRemoveMarker()
                        )
                );
            } else {
                merged.put(incoming.getKey(), incoming.getValue());
            }
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
        return state;
    }

    private String mergeJson(
            String previousJson,
            String incomingJson,
            String mergeKey,
            String removeMarker
    ) {
        try {
            JsonNode previous = objectMapper.readTree(previousJson);
            JsonNode incoming = objectMapper.readTree(incomingJson);
            JsonNode merged = mergeNodes(
                    previous,
                    incoming,
                    blankToNull(mergeKey),
                    blankToDefault(removeMarker, "remove")
            );
            return objectMapper.writeValueAsString(merged);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Un paramètre JSON fourni par le modèle est invalide.",
                    exception
            );
        }
    }

    private JsonNode mergeNodes(
            JsonNode previous,
            JsonNode incoming,
            String mergeKey,
            String removeMarker
    ) {
        if (previous != null && previous.isObject() && incoming.isObject()) {
            ObjectNode merged = ((ObjectNode) previous).deepCopy();
            incoming.fields().forEachRemaining(entry -> {
                JsonNode oldValue = merged.get(entry.getKey());
                merged.set(
                        entry.getKey(),
                        oldValue == null
                                ? entry.getValue().deepCopy()
                                : mergeNodes(
                                        oldValue,
                                        entry.getValue(),
                                        mergeKey,
                                        removeMarker
                                )
                );
            });
            return merged;
        }

        if (previous != null && previous.isArray() && incoming.isArray()
                && mergeKey != null) {
            return mergeKeyedArray(
                    (ArrayNode) previous,
                    (ArrayNode) incoming,
                    mergeKey,
                    removeMarker
            );
        }

        // Scalars and arrays without an explicit key are replaced, never guessed.
        return incoming.deepCopy();
    }

    private ArrayNode mergeKeyedArray(
            ArrayNode previous,
            ArrayNode incoming,
            String mergeKey,
            String removeMarker
    ) {
        Map<String, JsonNode> keyedItems = new LinkedHashMap<>();
        java.util.List<JsonNode> unkeyedItems = new java.util.ArrayList<>();

        for (JsonNode item : previous) {
            String key = itemKey(item, mergeKey);
            if (key == null) {
                unkeyedItems.add(item.deepCopy());
            } else {
                keyedItems.put(key, item.deepCopy());
            }
        }

        for (JsonNode item : incoming) {
            String key = itemKey(item, mergeKey);
            if (key == null) {
                unkeyedItems.add(item.deepCopy());
                continue;
            }
            if (item.path(removeMarker).asBoolean(false)) {
                keyedItems.remove(key);
                continue;
            }
            JsonNode previousItem = keyedItems.get(key);
            JsonNode mergedItem = previousItem == null
                    ? item.deepCopy()
                    : mergeNodes(previousItem, item, mergeKey, removeMarker);
            if (mergedItem.isObject()) {
                ((ObjectNode) mergedItem).remove(removeMarker);
            }
            keyedItems.put(key, mergedItem);
        }

        ArrayNode result = objectMapper.createArrayNode();
        keyedItems.values().forEach(result::add);
        unkeyedItems.forEach(result::add);
        return result;
    }

    private String itemKey(JsonNode item, String mergeKey) {
        if (!item.isObject()) {
            return null;
        }
        JsonNode key = item.get(mergeKey);
        return key == null || key.isNull() || key.isContainerNode()
                ? null
                : key.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
