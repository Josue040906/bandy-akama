package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Component
public class ConversationStateMerger {
    private final ObjectMapper objectMapper;

    public ConversationStateMerger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Keeps the model-produced arguments exactly as supplied. Contextual interpretation
     * is responsible for producing a complete argument object; Java never merges stale filters.
     */
    public Map<String, Object> mergeToolArguments(
            JsonNode previousState,
            String toolName,
            Map<String, Object> incomingArguments
    ) {
        if (incomingArguments == null) {
            throw new IllegalArgumentException("Les arguments de l’action actuelle sont obligatoires.");
        }
        return java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(incomingArguments)
        );
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

}
