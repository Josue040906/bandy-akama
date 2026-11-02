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
    private static final int MAX_RETAINED_RESULT_CHARACTERS = 8_000;

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

    /**
     * Whether a dialogue relation starts a distinct operational task and
     * therefore requires the completion path to clear prior task memory.
     */
    public boolean resetsOperationalState(DialogueRelation relation) {
        Objects.requireNonNull(relation, "relation must not be null.");
        return relation == DialogueRelation.NEW_TASK
                || relation == DialogueRelation.TOPIC_RESUMPTION;
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
     * snapshot. A reset discards the prior task, then applies updates produced
     * by the current turn so a newly-started task can persist its own results.
     */
    public ObjectNode applyToolUpdates(
            JsonNode previousState,
            List<ChatResponse.ToolState> toolStates,
            boolean reset
    ) {
        Objects.requireNonNull(toolStates, "toolStates must not be null.");
        // A reset discards the previous task, but tool results produced
        // by the current turn still belong to the newly-created task.
        JsonNode currentState = reset || previousState == null
                ? objectMapper.createObjectNode()
                : previousState.deepCopy();

        for (ChatResponse.ToolState toolState : toolStates) {
            Objects.requireNonNull(toolState, "toolState must not be null.");
            JsonNode legacyProfiles = currentState.path("profiles");
            ObjectNode nextState = toolState(
                    toolState.name(),
                    objectMapper.valueToTree(toolState.arguments())
            );

            // Preserve legacy RH profile snapshots during migration, but new
            // retention behavior is driven only by the tool configuration.
            if (legacyProfiles.isObject() && !legacyProfiles.isEmpty()) {
                nextState.set("profiles", legacyProfiles.deepCopy());
            }

            if (toolState.result() != null) {
                JsonNode retainedResult = objectMapper.valueToTree(toolState.result());
                String serializedResult = writeJson(retainedResult);
                if (serializedResult.length() <= MAX_RETAINED_RESULT_CHARACTERS) {
                    nextState.set("retainedResult", retainedResult);
                } else {
                    LOGGER.warn(
                            "Tool result retention skipped for {} because the "
                                    + "result exceeded {} characters (actual: {}).",
                            toolState.name(),
                            MAX_RETAINED_RESULT_CHARACTERS,
                            serializedResult.length()
                    );
                }
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
                        null
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
        JsonNode argumentsNode = state.path("arguments");
        ObjectNode structuredArguments = objectMapper.createObjectNode();
        if (argumentsNode.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> fields =
                    argumentsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getValue().isTextual()) {
                    continue;
                }
                try {
                    JsonNode parsed = objectMapper.readTree(
                            field.getValue().textValue()
                    );
                    if (parsed != null && parsed.isContainerNode()) {
                        structuredArguments.set(field.getKey(), parsed);
                        // Compatibility alias consumed only by the legacy RH intent resolver.
                        if ("criteriaJson".equals(field.getKey())) {
                            state.set("criteria", parsed.deepCopy());
                        }
                    }
                } catch (JsonProcessingException invalidStructuredArgument) {
                    if ("criteriaJson".equals(field.getKey())) {
                        state.put("criteriaText", field.getValue().textValue());
                    }
                }
            }
        }
        if (!structuredArguments.isEmpty()) {
            state.set("structuredArguments", structuredArguments);
        }
        return state;
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
