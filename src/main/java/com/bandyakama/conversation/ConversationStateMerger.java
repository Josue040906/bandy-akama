package com.bandyakama.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
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
        Map<String, Object> arguments = new LinkedHashMap<>(modelArguments);
        Object criteriaJson = arguments.get("criteriaJson");
        if (criteriaJson instanceof String text) {
            try {
                objectMapper.readTree(text);
            } catch (JsonProcessingException exception) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Les critères produits par le modèle ne sont pas valides.",
                        exception
                );
            }
        }
        return arguments;
    }
}
