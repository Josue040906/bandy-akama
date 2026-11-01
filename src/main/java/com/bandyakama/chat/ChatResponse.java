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
        @JsonIgnore List<ToolState> toolStates,
        boolean needClarification
) {
    public ChatResponse(String message, List<ToolResult> toolResults) {
        this(message, toolResults, null, false, List.of(), false);
    }

    public ChatResponse(String message, List<ToolResult> toolResults, UUID conversationId) {
        this(message, toolResults, conversationId, false, List.of(), false);
    }

    public ChatResponse(
            String message,
            List<ToolResult> toolResults,
            UUID conversationId,
            boolean resetConversationState
    ) {
        this(message, toolResults, conversationId, resetConversationState, List.of(), false);
    }

    public ChatResponse(
            String message,
            List<ToolResult> toolResults,
            UUID conversationId,
            boolean resetConversationState,
            List<ToolState> toolStates
    ) {
        this(message, toolResults, conversationId, resetConversationState, toolStates, false);
    }

    public static ChatResponse clarification(String message) {
        return new ChatResponse(
                message,
                List.of(),
                null,
                false,
                List.of(new ToolState(
                        "clarification_pending",
                        Map.of("targetTool", "search_agents")
                )),
                true
        );
    }

    public ChatResponse withConversationId(UUID id) {
        return new ChatResponse(message, toolResults, id, resetConversationState,
                toolStates, needClarification);
    }

    public ChatResponse withConversationStateReset(boolean reset) {
        return new ChatResponse(message, toolResults, conversationId, reset,
                toolStates, needClarification);
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
