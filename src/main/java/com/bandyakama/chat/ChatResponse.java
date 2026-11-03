package com.bandyakama.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatResponse(
        String message,
        List<ToolResult> toolResults,
        UUID conversationId,
        @JsonIgnore boolean resetConversationState,
        @JsonIgnore List<ToolState> toolStates,
        @JsonIgnore Map<String, JsonNode> objectiveStates
) {
    public ChatResponse(String message, List<ToolResult> toolResults) {
        this(message, toolResults, null, false, List.of(), Map.of());
    }

    public ChatResponse(
            String message,
            List<ToolResult> toolResults,
            UUID conversationId
    ) {
        this(message, toolResults, conversationId, false, List.of(), Map.of());
    }

    public ChatResponse(
            String message,
            List<ToolResult> toolResults,
            UUID conversationId,
            boolean resetConversationState
    ) {
        this(message, toolResults, conversationId, resetConversationState, List.of(), Map.of());
    }

    /** Compatibility constructor for callers that already provide tool state updates. */
    public ChatResponse(
            String message,
            List<ToolResult> toolResults,
            UUID conversationId,
            boolean resetConversationState,
            List<ToolState> toolStates
    ) {
        this(message, toolResults, conversationId, resetConversationState, toolStates, Map.of());
    }

    public ChatResponse {
        toolResults = List.copyOf(toolResults == null ? List.of() : toolResults);
        toolStates = List.copyOf(toolStates == null ? List.of() : toolStates);
        Map<String, JsonNode> safeStates = new LinkedHashMap<>();
        if (objectiveStates != null) {
            objectiveStates.forEach((objectiveId, state) -> {
                if (objectiveId != null && !objectiveId.isBlank() && state != null) {
                    safeStates.put(objectiveId, state.deepCopy());
                }
            });
        }
        objectiveStates = Collections.unmodifiableMap(safeStates);
    }

    @Override
    public Map<String, JsonNode> objectiveStates() {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        objectiveStates.forEach((id, state) -> copy.put(id, state.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    public ChatResponse withConversationId(UUID id) {
        return new ChatResponse(
                message,
                toolResults,
                id,
                resetConversationState,
                toolStates,
                objectiveStates
        );
    }

    public ChatResponse withConversationStateReset(boolean reset) {
        return new ChatResponse(
                message, toolResults, conversationId, reset, toolStates, objectiveStates
        );
    }

    public record ToolState(
            String name,
            Map<String, Object> arguments,
            Object result,
            String objectiveId
    ) {
        public ToolState(String name, Map<String, Object> arguments) {
            this(name, arguments, null, null);
        }

        public ToolState(String name, Map<String, Object> arguments, Object result) {
            this(name, arguments, result, null);
        }
    }

    public record ToolResult(
            String name,
            Object result,
            Map<String, Object> arguments,
            @JsonIgnore String objectiveId
    ) {
        public ToolResult(String name, Object result) {
            this(name, result, Map.of(), null);
        }

        public ToolResult(String name, Object result, Map<String, Object> arguments) {
            this(name, result, arguments, null);
        }
    }
}
