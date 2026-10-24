package com.bandyakama.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatResponse(
        String message,
        List<ToolResult> toolResults,
        UUID conversationId,
        @JsonIgnore boolean resetConversationState,
        @JsonIgnore List<ToolState> toolStates
) {
    public ChatResponse(String message, List<ToolResult> toolResults) {
        this(message, toolResults, null, false, List.of());
    }

    public ChatResponse(
            String message,
            List<ToolResult> toolResults,
            UUID conversationId
    ) {
        this(message, toolResults, conversationId, false, List.of());
    }

    public ChatResponse(
            String message,
            List<ToolResult> toolResults,
            UUID conversationId,
            boolean resetConversationState
    ) {
        this(message, toolResults, conversationId, resetConversationState, List.of());
    }

    public ChatResponse withConversationId(UUID id) {
        return new ChatResponse(
                message,
                toolResults,
                id,
                resetConversationState,
                toolStates
        );
    }

    public ChatResponse withConversationStateReset(boolean reset) {
        return new ChatResponse(message, toolResults, conversationId, reset, toolStates);
    }

    public record ToolState(
            String name,
            Map<String, Object> arguments,
            Object result
    ) {
        public ToolState(String name, Map<String, Object> arguments) {
            this(name, arguments, null);
        }
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
