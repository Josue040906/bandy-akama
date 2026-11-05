package com.bandyakama.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatResponse(
        String message,
        List<ToolResult> toolResults,
        UUID conversationId,
        @JsonIgnore List<ToolState> toolStates,
        boolean needClarification
) {
    public ChatResponse(String message, List<ToolResult> toolResults) {
        this(message, toolResults, null, List.of(), false);
    }

    public ChatResponse(String message, List<ToolResult> toolResults, UUID conversationId) {
        this(message, toolResults, conversationId, List.of(), false);
    }

    public ChatResponse withConversationId(UUID id) {
        return new ChatResponse(message, toolResults, id, toolStates, needClarification);
    }

    public record ToolState(String name, Map<String, Object> arguments, Object result) {
        public ToolState(String name, Map<String, Object> arguments) {
            this(name, arguments, null);
        }
    }

    public record ToolResult(String name, Object result, Map<String, Object> arguments) {
        public ToolResult(String name, Object result) {
            this(name, result, Map.of());
        }
    }
}
