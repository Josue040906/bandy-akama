package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.ContextEngineInput;
import com.bandyakama.conversation.dialogue.ContextInterpretationProposal;
import com.bandyakama.conversation.dialogue.DialogueInterpreter;
import com.bandyakama.conversation.dialogue.DialogueObjective;
import com.bandyakama.conversation.dialogue.DialogueRelation;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateRepository;
import com.bandyakama.conversation.dialogue.InterpretationConfidence;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "bandy.conversations.allowed-application-ids=client-a,client-b",
        "bandy.conversations.context.recent-messages=10",
        "bandy.conversations.dialogue.enabled=true"
})
class ConversationContextIntegrationTest {

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private DialogueStateRepository dialogueStateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DialogueInterpreter dialogueInterpreter;

    @MockBean
    private GeminiAssistant assistant;

    private Boolean previousSnapshotTestToolSensitivity;
    private boolean organizationToolCreatedForSnapshotTest;

    @AfterEach
    void restoreOrganizationToolSensitivity() {
        if (organizationToolCreatedForSnapshotTest) {
            jdbcTemplate.update(
                    "DELETE FROM bandy_tool WHERE name = 'snapshot_test_safe_tool'"
            );
            organizationToolCreatedForSnapshotTest = false;
        } else if (previousSnapshotTestToolSensitivity != null) {
            jdbcTemplate.update(
                    "UPDATE bandy_tool SET sensitive = ? WHERE name = 'snapshot_test_safe_tool'",
                    previousSnapshotTestToolSensitivity
            );
            previousSnapshotTestToolSensitivity = null;
        }
    }

    @Test
    void resolvesAndPersistsNewTaskThenContinuesItOnTheNextTurn() {
        UserIdentity identity = new UserIdentity("context-owner", "client-a");
        Conversation conversation = conversationRepository.create(
                identity.applicationId(),
                identity.ownerId()
        );
        when(assistant.respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(UUID.class),
                nullable(com.fasterxml.jackson.databind.JsonNode.class)
        )).thenReturn(
                new ChatResponse("Le premier résultat est prêt.", List.of()),
                new ChatResponse("Voici le complément demandé.", List.of())
        );

        when(dialogueInterpreter.interpret(any(ContextEngineInput.class)))
                .thenAnswer(invocation -> {
                    ContextEngineInput input = invocation.getArgument(0);
                    DialogueState state = input.dialogueState();
                    if (state.activeObjectiveId() == null) {
                        return new ContextInterpretationProposal(
                                DialogueRelation.NEW_TASK,
                                null,
                                "La demande démarre un objectif indépendant.",
                                null,
                                InterpretationConfidence.HIGH,
                                "Préparer un rapport de ventes",
                                "Préparation du rapport de ventes.",
                                List.of()
                        );
                    }
                    return new ContextInterpretationProposal(
                            DialogueRelation.CONTINUATION,
                            state.activeObjectiveId(),
                            "Le message complète le rapport en cours.",
                            null,
                            InterpretationConfidence.HIGH,
                            null,
                            "Le rapport de ventes est complété avec une précision.",
                            List.of()
                    );
                });

        conversationService.sendMessage(
                conversation.id(),
                identity,
                "Prépare un rapport de ventes.",
                null,
                UUID.randomUUID()
        );
        DialogueState afterFirstTurn = dialogueStateRepository.load(conversation.id());
        assertNotNull(afterFirstTurn.activeObjectiveId());
        assertEquals(1, afterFirstTurn.objectives().size());

        conversationService.sendMessage(
                conversation.id(),
                identity,
                "Ajoute une comparaison par région.",
                null,
                UUID.randomUUID()
        );
        DialogueState afterSecondTurn = dialogueStateRepository.load(conversation.id());

        assertEquals(afterFirstTurn.activeObjectiveId(), afterSecondTurn.activeObjectiveId());
        assertEquals(1, afterSecondTurn.objectives().size());
        DialogueObjective active = afterSecondTurn.objectives().get(0);
        assertEquals("Préparer un rapport de ventes", active.goal());
        assertEquals(
                "Le rapport de ventes est complété avec une précision.",
                active.summary()
        );
        verify(dialogueInterpreter, times(2)).interpret(any(ContextEngineInput.class));
        verify(assistant, times(2)).respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(UUID.class),
                nullable(com.fasterxml.jackson.databind.JsonNode.class)
        );
    }

    @Test
    void persistsOnlyAllowlistedCriteriaAndSafeReferenceMetadataInTaskSnapshot()
            throws Exception {
        UserIdentity identity = new UserIdentity("snapshot-security-owner", "client-a");
        Conversation conversation = conversationRepository.create(
                identity.applicationId(),
                identity.ownerId()
        );
        List<Boolean> existingSensitivity = jdbcTemplate.query(
                "SELECT sensitive FROM bandy_tool WHERE name = 'snapshot_test_safe_tool'",
                (rs, row) -> rs.getBoolean(1)
        );
        if (existingSensitivity.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO bandy_tool (
                        name, description, when_to_use, http_method,
                        endpoint_url, enabled, sensitive
                    ) VALUES (
                        'snapshot_test_safe_tool', 'Organization lookup',
                        'Used by snapshot integration test', 'GET',
                        'http://localhost/organization', TRUE, FALSE
                    )
                    """);
            organizationToolCreatedForSnapshotTest = true;
        } else {
            previousSnapshotTestToolSensitivity = existingSensitivity.get(0);
            jdbcTemplate.update(
                    "UPDATE bandy_tool SET sensitive = FALSE WHERE name = 'snapshot_test_safe_tool'"
            );
        }
        String criteriaJson = """
                {"filters":[
                  {"field":"service","operator":"EQ","value":"Informatique"},
                  {"field":"classe","operator":"EQ","value":10},
                  {"field":"sexe","operator":"EQ","value":"M"},
                  {"field":"matricule","operator":"EQ","value":"RH12345678"}
                ]}
                """.replace("\\n", "");
        when(dialogueInterpreter.interpret(any(ContextEngineInput.class)))
                .thenReturn(new ContextInterpretationProposal(
                        DialogueRelation.NEW_TASK,
                        null,
                        "Nouvelle recherche.",
                        null,
                        InterpretationConfidence.HIGH,
                        "Recherche d'agents",
                        "Recherche d'agents par critères professionnels.",
                        List.of()
                ));
        when(assistant.respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenReturn(new ChatResponse(
                "Recherche préparée.",
                List.of(new ChatResponse.ToolResult(
                        "snapshot_test_safe_tool",
                        java.util.Map.of("description", "Service Informatique"),
                        java.util.Map.of(
                                "query", "Rakoto RH12345678",
                                "scope", "SERVICE"
                        )
                )),
                conversation.id(),
                false,
                List.of(new ChatResponse.ToolState(
                        "search_agents",
                        java.util.Map.of(
                                "criteriaJson", criteriaJson,
                                "authorization", "Bearer private-token"
                        )
                ))
        ));

        ChatResponse actualResponse = conversationService.sendMessage(
                conversation.id(),
                identity,
                "Cherche les agents du service informatique.",
                null,
                UUID.randomUUID()
        );
        assertEquals(1, actualResponse.toolResults().size(),
                "The mocked assistant response must reach ConversationService unchanged.");
        assertEquals("snapshot_test_safe_tool", actualResponse.toolResults().get(0).name());
        verify(assistant).respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        );

        String stateJson = jdbcTemplate.queryForObject("""
                SELECT operational_state::text
                FROM bandy_conversation_task_state
                WHERE conversation_id = ?
                """,
                String.class,
                conversation.id()
        );
        String referencesJson = jdbcTemplate.queryForObject("""
                SELECT result_references::text
                FROM bandy_conversation_task_state
                WHERE conversation_id = ?
                """,
                String.class,
                conversation.id()
        );
        Boolean organizationToolSensitive = jdbcTemplate.queryForObject(
                "SELECT sensitive FROM bandy_tool WHERE name = 'snapshot_test_safe_tool'",
                Boolean.class
        );
        String memoryReferencesJson = jdbcTemplate.queryForObject("""
                SELECT result_references::text
                FROM bandy_conversation_memory
                WHERE conversation_id = ?
                """,
                String.class,
                conversation.id()
        );
        JsonNode memoryReferences = objectMapper.readTree(memoryReferencesJson);
        JsonNode references = objectMapper.readTree(referencesJson);
        assertFalse(organizationToolSensitive,
                "The fixture tool must be non-sensitive for this metadata-persistence test.");
        assertEquals(1, memoryReferences.size(),
                "The tool result must reach conversation memory before snapshot capture.");

        assertTrue(stateJson.contains("Informatique"));
        assertTrue(stateJson.contains("classe"));
        assertFalse(stateJson.contains("RH12345678"));
        assertFalse(stateJson.contains("Bearer private-token"));
        assertFalse(stateJson.contains("\\\"sexe\\\""));
        assertEquals(1, references.size());
        assertTrue(referencesJson.contains("snapshot_test_safe_tool"));
        assertFalse(referencesJson.contains("Rakoto"));
        assertFalse(referencesJson.contains("RH12345678"));
        assertFalse(referencesJson.contains("description"));
        assertFalse(referencesJson.contains("scope"));
        assertFalse(references.get(0).has("result"));
    }

    @Test
    void persistsClarificationAndReturnsItsQuestionWithoutCallingAssistant() {
        UserIdentity identity = new UserIdentity("clarification-owner", "client-a");
        Conversation conversation = conversationRepository.create(
                identity.applicationId(),
                identity.ownerId()
        );
        when(dialogueInterpreter.interpret(any(ContextEngineInput.class)))
                .thenReturn(new ContextInterpretationProposal(
                        DialogueRelation.NEEDS_CLARIFICATION,
                        null,
                        "La référence de la demande est ambiguë.",
                        "Souhaitez-vous reprendre le sujet précédent ou commencer une nouvelle tâche ?",
                        InterpretationConfidence.HIGH,
                        null,
                        null,
                        List.of()
                ));

        ChatResponse response = conversationService.sendMessage(
                conversation.id(),
                identity,
                "Reprends ça.",
                null,
                UUID.randomUUID()
        );

        assertEquals(
                "Souhaitez-vous reprendre le sujet précédent ou commencer une nouvelle tâche ?",
                response.message()
        );
        DialogueState saved = dialogueStateRepository.load(conversation.id());
        assertNotNull(saved.pendingClarification());
        verify(assistant, times(0)).respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(com.fasterxml.jackson.databind.JsonNode.class)
        );
    }
}
