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

    public Map<String, Object> argumentsForExecution(
            Map<String, Object> modelArguments
    ) {
        return new java.util.LinkedHashMap<>(modelArguments);
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
