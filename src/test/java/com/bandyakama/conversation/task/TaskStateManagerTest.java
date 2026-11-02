package com.bandyakama.conversation.task;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueRelation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStateManagerTest {
    private ObjectMapper objectMapper;
    private TaskStateManager manager;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        manager = new TaskStateManager(objectMapper);
    }

    @Test
    void reusesOperationalStateOnlyForRelationsWithinTheSameTask() {
        ObjectNode persisted = objectMapper.createObjectNode()
                .put("toolName", "search_records");

        for (DialogueRelation relation : List.of(
                DialogueRelation.CONTINUATION,
                DialogueRelation.CORRECTION,
                DialogueRelation.USER_CLARIFICATION
        )) {
            JsonNode selected = manager.forDialogueRelation(relation, persisted);
            assertEquals(persisted, selected);
            assertNotSame(persisted, selected);
        }

        for (DialogueRelation relation : List.of(
                DialogueRelation.NEW_TASK,
                DialogueRelation.TOPIC_RESUMPTION,
                DialogueRelation.NEEDS_CLARIFICATION
        )) {
            assertNull(manager.forDialogueRelation(relation, persisted));
        }
    }

    @Test
    void legacyPolicyRetainsStateOnlyForContinuation() {
        ObjectNode persisted = objectMapper.createObjectNode().put("intent", "SEARCH");

        assertEquals(persisted, manager.forLegacyContinuation(true, persisted));
        assertNotSame(persisted, manager.forLegacyContinuation(true, persisted));
        assertNull(manager.forLegacyContinuation(false, persisted));
    }

    @Test
    void resetClearsStateEvenWhenTheResponseContainsToolUpdates() {
        ObjectNode previous = objectMapper.createObjectNode().put("old", true);
        List<ChatResponse.ToolState> updates = List.of(
                new ChatResponse.ToolState("search_records", Map.of("query", "new"))
        );

        ObjectNode next = manager.applyToolUpdates(previous, updates, true);

        assertTrue(next.isEmpty());
        assertEquals(true, previous.path("old").asBoolean());
    }

    @Test
    void appliesToolUpdatesWithoutMutatingPreviousStateAndKeepsProfileCacheBounded() {
        ObjectNode previous = objectMapper.createObjectNode();
        ObjectNode existingProfiles = previous.putObject("profiles");
        existingProfiles.putObject("old-profile").put("name", "Ancien");

        List<ChatResponse.ToolState> updates = List.of(
                profileUpdate("MAT-001", "A"),
                profileUpdate("MAT-002", "B"),
                profileUpdate("MAT-003", "C"),
                profileUpdate("MAT-004", "D")
        );

        ObjectNode next = manager.applyToolUpdates(previous, updates, false);

        assertTrue(previous.path("profiles").has("old-profile"));
        assertFalse(previous.path("profiles").has("MAT-004"));
        assertEquals("get_agent_profile", next.path("toolName").asText());
        assertEquals(3, next.path("profiles").size());
        assertTrue(next.path("profiles").has("mat-004"));
        assertFalse(next.path("profiles").has("old-profile"));
    }

    @Test
    void extractsExplicitToolStatesAndFallsBackToToolResults() {
        ChatResponse explicit = new ChatResponse(
                "ok",
                List.of(new ChatResponse.ToolResult(
                        "ignored",
                        Map.of("value", 1),
                        Map.of()
                )),
                null,
                false,
                List.of(new ChatResponse.ToolState(
                        "explicit",
                        Map.of("id", 2)
                ))
        );
        assertEquals("explicit", manager.updatesFor(explicit).get(0).name());

        ChatResponse legacy = new ChatResponse(
                "ok",
                List.of(new ChatResponse.ToolResult(
                        "search_records",
                        Map.of("rows", 2),
                        Map.of("query", "x")
                ))
        );
        assertEquals("search_records", manager.updatesFor(legacy).get(0).name());
        assertNull(manager.updatesFor(legacy).get(0).result());
    }

    @Test
    void reconstructsHistoricalToolStateAndParsesCriteriaJson() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("criteriaJson", "{\"service\":\"finance\"}");

        ObjectNode state = manager.fromHistoricalToolResult(
                "search_records",
                arguments
        );

        assertEquals("TOOL_CALL", state.path("intent").asText());
        assertEquals("search_records", state.path("toolName").asText());
        assertEquals("finance", state.path("criteria").path("service").asText());
    }

    private ChatResponse.ToolState profileUpdate(String matricule, String name) {
        return new ChatResponse.ToolState(
                "get_agent_profile",
                Map.of("nom_agent", name),
                Map.of("agent", Map.of("matricule", matricule, "name", name))
        );
    }
}
