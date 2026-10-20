package com.bandyakama.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatResponse(
        String message,
        List<ToolResult> toolResults,
        UUID conversationId,
        @JsonIgnore boolean resetConversationState
) {
    public ChatResponse(String message, List<ToolResult> toolResults) {
        this(message, toolResults, null, false);
    }

    public ChatResponse(
            String message,
            List<ToolResult> toolResults,
            UUID conversationId
    ) {
        this(message, toolResults, conversationId, false);
    }

    public ChatResponse withConversationId(UUID id) {
        return new ChatResponse(message, toolResults, id, resetConversationState);
    }

    public ChatResponse withConversationStateReset(boolean reset) {
        return new ChatResponse(message, toolResults, conversationId, reset);
    }

    public record ToolResult(
            String name,
            Object result,
            Map<String, Object> arguments
    ) {
        public ToolResult(String name, Object result) {
            this(name, result, Map.of());
        }
    }
}
