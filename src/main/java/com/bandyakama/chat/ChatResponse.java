package com.bandyakama.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

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

    public ChatResponse withActions(List<Action> replacedActions) {
        return new ChatResponse(message, toolResults, conversationId, replacedActions, toolStates);
    }

    public record ToolState(String name, Map<String, Object> arguments) {
    }

    public record ToolResult(String name, Object result, Map<String, Object> arguments) {
        public ToolResult(String name, Object result) {
            this(name, result, Map.of());
        }
    }

    /**
     * Action proposée à l'interface ; le front et l'API restent juges des
     * droits. route est une route du front (navigation, tâche préparée).
     * fields contient seulement les valeurs proposées par Bandy : champs à
     * préremplir, ou arguments d'une tâche à confirmer. confirmationId
     * identifie une tâche confirmée, exécutée seulement après le clic
     * « Confirmer » de l'utilisateur.
     */
    public record Action(
            String type,
            String tool,
            @JsonInclude(JsonInclude.Include.NON_NULL) String route,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> fields,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID confirmationId
    ) {
        public Action {
            fields = fields == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        }

        public Action(String type, String tool, String route, Map<String, Object> fields) {
            this(type, tool, route, fields, null);
        }

        public Action withConfirmationId(UUID id) {
            return new Action(type, tool, route, fields, id);
        }
    }
}
