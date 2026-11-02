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
    void resetsOperationalStateOnlyForNewOrResumedTasks() {
        assertTrue(manager.resetsOperationalState(DialogueRelation.NEW_TASK));
        assertTrue(manager.resetsOperationalState(DialogueRelation.TOPIC_RESUMPTION));
        assertFalse(manager.resetsOperationalState(DialogueRelation.CONTINUATION));
        assertFalse(manager.resetsOperationalState(DialogueRelation.CORRECTION));
        assertFalse(manager.resetsOperationalState(DialogueRelation.USER_CLARIFICATION));
        assertFalse(manager.resetsOperationalState(DialogueRelation.NEEDS_CLARIFICATION));
    }

    @Test
    void legacyPolicyRetainsStateOnlyForContinuation() {
        ObjectNode persisted = objectMapper.createObjectNode().put("intent", "SEARCH");

        assertEquals(persisted, manager.forLegacyContinuation(true, persisted));
        assertNotSame(persisted, manager.forLegacyContinuation(true, persisted));
        assertNull(manager.forLegacyContinuation(false, persisted));
    }

    @Test
    void resetDropsPreviousStateButRetainsUpdatesFromTheCurrentTurn() {
        ObjectNode previous = objectMapper.createObjectNode().put("old", true);
        List<ChatResponse.ToolState> updates = List.of(
                new ChatResponse.ToolState("search_records", Map.of("query", "new"))
        );

        ObjectNode next = manager.applyToolUpdates(previous, updates, true);

        assertFalse(next.has("old"));
        assertEquals("search_records", next.path("toolName").asText());
        assertEquals("new", next.path("arguments").path("query").asText());
        assertEquals(true, previous.path("old").asBoolean());
    }

    @Test
    void retainsConfiguredGenericToolResultsWithinTheSizeLimit() {
        ObjectNode previous = objectMapper.createObjectNode()
                .put("toolName", "previous_lookup");
        previous.putObject("profiles").putObject("legacy-profile")
                .put("name", "Legacy snapshot");

        List<ChatResponse.ToolState> updates = List.of(
                new ChatResponse.ToolState(
                        "lookup_customer",
                        Map.of("customerId", "C-42"),
                        Map.of("id", "C-42", "status", "ACTIVE")
                )
        );

        ObjectNode next = manager.applyToolUpdates(previous, updates, false);

        assertEquals("lookup_customer", next.path("toolName").asText());
        assertEquals("C-42", next.path("arguments").path("customerId").asText());
        assertEquals("C-42", next.path("retainedResult").path("id").asText());
        assertEquals("ACTIVE", next.path("retainedResult").path("status").asText());
        assertEquals(
                "Legacy snapshot",
                next.path("extensions").path("profiles")
                        .path("legacy-profile").path("name").asText()
        );
        assertFalse(next.has("profiles"));
        assertEquals("previous_lookup", previous.path("toolName").asText());
    }

    @Test
    void skipsOversizedRetainedResultsWithoutFailingTheConversation() {
        List<ChatResponse.ToolState> updates = List.of(
                new ChatResponse.ToolState(
                        "large_document_lookup",
                        Map.of("documentId", "D-1"),
                        Map.of("content", "x".repeat(9_000))
                )
        );

        ObjectNode next = manager.applyToolUpdates(
                objectMapper.createObjectNode(),
                updates,
                false
        );

        assertEquals("large_document_lookup", next.path("toolName").asText());
        assertFalse(next.has("retainedResult"));
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
    void reconstructsHistoricalToolStateAndParsesStructuredJsonArguments() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("querySpec", "{\"service\":\"finance\"}");

        ObjectNode state = manager.fromHistoricalToolResult(
                "search_records",
                arguments
        );

        assertEquals("TOOL_CALL", state.path("intent").asText());
        assertEquals("search_records", state.path("toolName").asText());
        assertEquals(
                "finance",
                state.path("structuredArguments").path("querySpec")
                        .path("service").asText()
        );
        assertFalse(state.has("criteria"));
    }

    @Test
    void reconstructsStructuredJsonArgumentsForAnyToolWithoutDomainAliases() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("querySpec", "{\"filters\":[{\"field\":\"status\",\"value\":\"OPEN\"}]}");

        ObjectNode state = manager.fromHistoricalToolResult(
                "generic_search",
                arguments
        );

        assertEquals(
                "status",
                state.path("structuredArguments").path("querySpec")
                        .path("filters").get(0).path("field").asText()
        );
    }



    @Test
    void carriesUnknownLegacyStateFieldsAsOpaqueExtensions() {
        ObjectNode previous = objectMapper.createObjectNode()
                .put("toolName", "legacy_tool");
        previous.putObject("profiles").put("cached", true);
        previous.putObject("customMetadata").put("source", "archive");

        ObjectNode next = manager.applyToolUpdates(
                previous,
                List.of(new ChatResponse.ToolState(
                        "generic_tool",
                        Map.of("query", "new")
                )),
                false
        );

        assertTrue(next.path("extensions").path("profiles")
                .path("cached").asBoolean());
        assertEquals(
                "archive",
                next.path("extensions").path("customMetadata")
                        .path("source").asText()
        );
        assertFalse(next.has("profiles"));
        assertFalse(next.has("customMetadata"));
    }


}
