package com.bandyakama.conversation.task;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueRelation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final ObjectMapper objectMapper;
    private final List<OperationalStateContributor> contributors;

    @Autowired
    public TaskStateManager(
            ObjectMapper objectMapper,
            List<OperationalStateContributor> contributors
    ) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "objectMapper must not be null."
        );
        this.contributors = List.copyOf(Objects.requireNonNull(
                contributors, "contributors must not be null."
        ));
    }

    /** Convenient constructor for domain-neutral unit tests and standalone use. */
    public TaskStateManager(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
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
            case CONTINUATION, CORRECTION, USER_CLARIFICATION, SOCIAL_TURN ->
                    copyOrNull(persistedState);
            case NEW_TASK, TOPIC_RESUMPTION, NEW_TOPIC_REQUEST, NEEDS_CLARIFICATION -> null;
        };
    }

    /**
     * Whether a dialogue relation starts a distinct operational task and
     * therefore requires the completion path to clear prior task memory.
     */
    public boolean resetsOperationalState(DialogueRelation relation) {
        Objects.requireNonNull(relation, "relation must not be null.");
        return relation == DialogueRelation.NEW_TASK
                || relation == DialogueRelation.TOPIC_RESUMPTION
                || relation == DialogueRelation.NEW_TOPIC_REQUEST;
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
            ObjectNode nextState = toolState(
                    toolState.name(),
                    objectMapper.valueToTree(toolState.arguments())
            );
            for (OperationalStateContributor contributor : contributors) {
                if (contributor.supports(toolState)) {
                    contributor.contribute(nextState, currentState, toolState);
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

}
