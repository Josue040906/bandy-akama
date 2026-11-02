package com.bandyakama.conversation.task;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueRelation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the lifecycle and transformation rules of operational task state.
 *
 * <p>This component is deliberately independent from JDBC and dialogue
 * persistence. It decides whether operational state may be reused and
 * transforms tool execution data into the next immutable JSON snapshot.
 * Persistence remains the responsibility of ConversationRepository.</p>
 */
@Service
public class TaskStateManager {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(TaskStateManager.class);
    private static final int MAX_CACHED_PROFILE_CHARACTERS = 8_000;
    private static final int MAX_CACHED_PROFILES = 3;

    private final ObjectMapper objectMapper;

    public TaskStateManager(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null."
        );
    }

    /**
     * Returns a defensive copy of operational state only for relations that
     * continue or clarify the same task. New and resumed topics start clean.
     */
    public JsonNode forDialogueRelation(
            DialogueRelation relation,
            JsonNode persistedState
    ) {
        Objects.requireNonNull(relation, "relation must not be null.");
        return switch (relation) {
            case CONTINUATION, CORRECTION, USER_CLARIFICATION ->
                    copyOrNull(persistedState);
            case NEW_TASK, TOPIC_RESUMPTION, NEEDS_CLARIFICATION -> null;
        };
    }

    /** Compatibility policy for the legacy intent resolver. */
    public JsonNode forLegacyContinuation(
            boolean continuation,
            JsonNode persistedState
    ) {
        return continuation ? copyOrNull(persistedState) : null;
    }

    /**
     * Applies tool state updates without mutating the previously persisted
     * snapshot. A reset always wins over updates from the current response.
     */
    public ObjectNode applyToolUpdates(
            JsonNode previousState,
            List<ChatResponse.ToolState> toolStates,
            boolean reset
    ) {
        Objects.requireNonNull(toolStates, "toolStates must not be null.");
        if (reset) {
            return objectMapper.createObjectNode();
        }

        JsonNode currentState = previousState == null
                ? objectMapper.createObjectNode()
                : previousState.deepCopy();

        for (ChatResponse.ToolState toolState : toolStates) {
            Objects.requireNonNull(toolState, "toolState must not be null.");
            ObjectNode cachedProfiles = currentState.path("profiles").isObject()
                    ? ((ObjectNode) currentState.path("profiles")).deepCopy()
                    : objectMapper.createObjectNode();
            ObjectNode nextState = toolState(
                    toolState.name(),
                    objectMapper.valueToTree(toolState.arguments())
            );

            if ("get_agent_profile".equals(toolState.name())
                    && toolState.result() != null) {
                JsonNode profile = objectMapper.valueToTree(toolState.result());
                String serializedProfile = writeJson(profile);
                if (serializedProfile.length() <= MAX_CACHED_PROFILE_CHARACTERS) {
                    cachedProfiles.set(
                            profileCacheKey(toolState.arguments(), profile),
                            profile
                    );
                    while (cachedProfiles.size() > MAX_CACHED_PROFILES) {
                        cachedProfiles.remove(cachedProfiles.fieldNames().next());
                    }
                } else {
                    LOGGER.warn(
                            "Assistant profile cache skipped because the result "
                                    + "exceeded {} characters (actual: {}).",
                            MAX_CACHED_PROFILE_CHARACTERS,
                            serializedProfile.length()
                    );
                }
            }

            if (!cachedProfiles.isEmpty()) {
                nextState.set("profiles", cachedProfiles);
            }
            currentState = nextState;
        }
        return currentState.isObject()
                ? (ObjectNode) currentState
                : objectMapper.createObjectNode();
    }

    /**
     * Extracts explicit state updates, retaining the historical fallback from
     * tool results for callers that do not yet provide ToolState separately.
     */
    public List<ChatResponse.ToolState> updatesFor(ChatResponse response) {
        Objects.requireNonNull(response, "response must not be null.");
        if (!response.toolStates().isEmpty()) {
            return response.toolStates();
        }
        return response.toolResults().stream()
                .map(result -> new ChatResponse.ToolState(
                        result.name(),
                        result.arguments(),
                        "get_agent_profile".equals(result.name())
                                ? result.result()
                                : null
                ))
                .toList();
    }

    /**
     * Reconstructs the operational state represented by a historical tool
     * result when migrating old conversation history into memory.
     */
    public ObjectNode fromHistoricalToolResult(
            String name,
            JsonNode arguments
    ) {
        return toolState(name, arguments);
    }

    private JsonNode copyOrNull(JsonNode state) {
        return state == null ? null : state.deepCopy();
    }

    private ObjectNode toolState(String name, JsonNode arguments) {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("intent", "TOOL_CALL");
        state.put("toolName", name);
        state.set("arguments", arguments == null
                ? objectMapper.createObjectNode()
                : arguments.deepCopy());
        JsonNode criteriaJson = state.path("arguments").path("criteriaJson");
        if (criteriaJson.isTextual()) {
            try {
                state.set("criteria", objectMapper.readTree(criteriaJson.textValue()));
            } catch (JsonProcessingException invalidToolInput) {
                state.put("criteriaText", criteriaJson.textValue());
            }
        }
        return state;
    }

    private String profileCacheKey(
            Map<String, Object> arguments,
            JsonNode profile
    ) {
        String matricule = profile.path("agent").path("matricule").asText("");
        String identity = matricule.isBlank()
                ? String.valueOf(arguments == null
                        ? ""
                        : arguments.getOrDefault("nom_agent", ""))
                : matricule;
        return identity.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Impossible de sérialiser l’état opérationnel.",
                    exception
            );
        }
    }
}
