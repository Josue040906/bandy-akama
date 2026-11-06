package com.bandyakama.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Les réponses enregistrées avant cette version contiennent encore
// « needClarification » : on l'ignore pour les relire sans erreur.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String message,
        List<ToolResult> toolResults,
        UUID conversationId,
        List<Action> actions,
        @JsonIgnore List<ToolState> toolStates
) {
    public ChatResponse {
        // Les réponses enregistrées avant le canal d'actions n'en ont pas.
        actions = actions == null ? List.of() : List.copyOf(actions);
        toolStates = toolStates == null ? List.of() : toolStates;
    }

    public ChatResponse(String message, List<ToolResult> toolResults) {
        this(message, toolResults, null, List.of(), List.of());
    }

    public ChatResponse(String message, List<ToolResult> toolResults, UUID conversationId) {
        this(message, toolResults, conversationId, List.of(), List.of());
    }

    public ChatResponse withConversationId(UUID id) {
        return new ChatResponse(message, toolResults, id, actions, toolStates);
    }

    public record ToolState(String name, Map<String, Object> arguments) {
    }

    public record ToolResult(String name, Object result, Map<String, Object> arguments) {
        public ToolResult(String name, Object result) {
            this(name, result, Map.of());
        }
    }

    /**
     * Action proposée à l'interface. Pour une navigation, route est une route
     * du front déjà construite ; le front et l'API restent juges des droits.
     */
    public record Action(String type, String tool, String route) {
    }
}
