package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.ContextEngineInput;
import com.bandyakama.conversation.dialogue.ContextInterpretationProposal;
import com.bandyakama.conversation.dialogue.DialogueInterpreter;
import com.bandyakama.conversation.dialogue.DialogueObjective;
import com.bandyakama.conversation.dialogue.DialogueObjectiveStatus;
import com.bandyakama.conversation.dialogue.DialogueRelation;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateRepository;
import com.bandyakama.conversation.dialogue.InterpretationConfidence;
import com.bandyakama.conversation.task.TaskStateSnapshotRepository;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import org.junit.jupiter.api.Test;
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

    @Autowired
    private TaskStateSnapshotRepository taskStateSnapshotRepository;

    @MockBean
    private DialogueInterpreter dialogueInterpreter;

    @MockBean
    private GeminiAssistant assistant;

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
    void suspendsIndependentObjectiveAndResumesTheExactEarlierObjective() {
        UserIdentity identity = new UserIdentity("objective-switch-owner", "client-a");
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
                new ChatResponse("Le rapport est préparé.", List.of()),
                new ChatResponse("Le plan est préparé.", List.of()),
                new ChatResponse("Reprenons le rapport.", List.of())
        );

        when(dialogueInterpreter.interpret(any(ContextEngineInput.class)))
                .thenAnswer(invocation -> {
                    ContextEngineInput input = invocation.getArgument(0);
                    DialogueState state = input.dialogueState();
                    if (state.objectives().isEmpty()) {
                        return new ContextInterpretationProposal(
                                DialogueRelation.NEW_TASK,
                                null,
                                "Premier objectif indépendant.",
                                null,
                                InterpretationConfidence.HIGH,
                                "Préparer un rapport de ventes",
                                "Rapport de ventes en préparation.",
                                List.of()
                        );
                    }

                    DialogueObjective active = state.objectives().stream()
                            .filter(objective ->
                                    objective.status() == DialogueObjectiveStatus.ACTIVE)
                            .findFirst()
                            .orElseThrow();

                    if ("Préparer un rapport de ventes".equals(active.goal())) {
                        return new ContextInterpretationProposal(
                                DialogueRelation.NEW_TASK,
                                null,
                                "La planification constitue un objectif indépendant.",
                                null,
                                InterpretationConfidence.HIGH,
                                "Préparer un plan de voyage",
                                "Plan de voyage en préparation.",
                                List.of()
                        );
                    }

                    DialogueObjective report = state.objectives().stream()
                            .filter(objective -> "Préparer un rapport de ventes"
                                    .equals(objective.goal()))
                            .findFirst()
                            .orElseThrow();
                    return new ContextInterpretationProposal(
                            DialogueRelation.TOPIC_RESUMPTION,
                            report.id(),
                            "L'utilisateur reprend explicitement le rapport suspendu.",
                            null,
                            InterpretationConfidence.HIGH,
                            null,
                            "Reprise du rapport de ventes.",
                            List.of()
                    );
                });

        conversationService.sendMessage(
                conversation.id(), identity,
                "Prépare un rapport de ventes.", null, UUID.randomUUID()
        );
        DialogueState afterReport = dialogueStateRepository.load(conversation.id());
        String reportId = afterReport.activeObjectiveId();
        assertNotNull(reportId);

        conversationService.sendMessage(
                conversation.id(), identity,
                "Maintenant, prépare un plan de voyage.", null, UUID.randomUUID()
        );
        DialogueState afterPlan = dialogueStateRepository.load(conversation.id());
        String planId = afterPlan.activeObjectiveId();
        assertNotNull(planId);
        assertFalse(reportId.equals(planId));
        assertEquals(
                DialogueObjectiveStatus.SUSPENDED,
                afterPlan.objectives().stream()
                        .filter(objective -> objective.id().equals(reportId))
                        .findFirst().orElseThrow().status()
        );
        assertEquals(2, afterPlan.objectives().size());

        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bandy_conversation_task_state
                WHERE conversation_id = ? AND objective_id = ?
                """, Long.class, conversation.id(), reportId));
        assertEquals(1L, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bandy_conversation_task_state
                WHERE conversation_id = ? AND objective_id = ?
                """, Long.class, conversation.id(), planId));

        conversationService.sendMessage(
                conversation.id(), identity,
                "Reprenons le rapport de ventes.", null, UUID.randomUUID()
        );
        DialogueState afterResumption = dialogueStateRepository.load(conversation.id());

        assertEquals(reportId, afterResumption.activeObjectiveId());
        assertEquals(
                DialogueObjectiveStatus.ACTIVE,
                afterResumption.objectives().stream()
                        .filter(objective -> objective.id().equals(reportId))
                        .findFirst().orElseThrow().status()
        );
        assertEquals(
                DialogueObjectiveStatus.SUSPENDED,
                afterResumption.objectives().stream()
                        .filter(objective -> objective.id().equals(planId))
                        .findFirst().orElseThrow().status()
        );
        assertEquals(2, afterResumption.objectives().size());
        verify(dialogueInterpreter, times(3)).interpret(any(ContextEngineInput.class));
        verify(assistant, times(3)).respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(UUID.class),
                nullable(com.fasterxml.jackson.databind.JsonNode.class)
        );
    }

    @Test
    void explicitlyCompletesActiveObjectiveAndAllowsANewObjectiveAfterward() {
        UserIdentity identity = new UserIdentity("objective-completion-owner", "client-a");
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
                new ChatResponse("Le rapport est prêt.", List.of()),
                new ChatResponse("Le nouveau sujet est prêt.", List.of())
        );

        when(dialogueInterpreter.interpret(any(ContextEngineInput.class)))
                .thenAnswer(invocation -> {
                    ContextEngineInput input = invocation.getArgument(0);
                    DialogueState state = input.dialogueState();
                    if (input.currentMessage().equals("Prépare un rapport de ventes.")) {
                        return new ContextInterpretationProposal(
                                DialogueRelation.NEW_TASK,
                                null,
                                "La demande démarre un rapport.",
                                null,
                                InterpretationConfidence.HIGH,
                                "Préparer un rapport de ventes",
                                "Rapport de ventes en préparation.",
                                List.of()
                        );
                    }
                    if (input.currentMessage().equals("Le rapport est terminé, merci.")) {
                        return new ContextInterpretationProposal(
                                DialogueRelation.TASK_COMPLETION,
                                state.activeObjectiveId(),
                                "L'utilisateur clôt explicitement le rapport.",
                                null,
                                InterpretationConfidence.HIGH,
                                null,
                                null,
                                List.of()
                        );
                    }
                    return new ContextInterpretationProposal(
                            DialogueRelation.NEW_TASK,
                            null,
                            "La demande suivante constitue un nouvel objectif.",
                            null,
                            InterpretationConfidence.HIGH,
                            "Préparer une présentation",
                            "Présentation en préparation.",
                            List.of()
                    );
                });

        conversationService.sendMessage(
                conversation.id(), identity,
                "Prépare un rapport de ventes.", null, UUID.randomUUID()
        );
        DialogueState afterCreation = dialogueStateRepository.load(conversation.id());
        String reportId = afterCreation.activeObjectiveId();
        assertNotNull(reportId);

        ChatResponse completionResponse = conversationService.sendMessage(
                conversation.id(), identity,
                "Le rapport est terminé, merci.", null, UUID.randomUUID()
        );
        DialogueState afterCompletion = dialogueStateRepository.load(conversation.id());

        assertTrue(completionResponse.message().toLowerCase().contains("terminé"));
        assertEquals(null, afterCompletion.activeObjectiveId());
        assertEquals(
                DialogueObjectiveStatus.COMPLETED,
                afterCompletion.objectives().stream()
                        .filter(objective -> objective.id().equals(reportId))
                        .findFirst().orElseThrow().status()
        );
        assertEquals(1, afterCompletion.objectives().size());

        conversationService.sendMessage(
                conversation.id(), identity,
                "Prépare maintenant une présentation.", null, UUID.randomUUID()
        );
        DialogueState afterNewObjective = dialogueStateRepository.load(conversation.id());

        assertNotNull(afterNewObjective.activeObjectiveId());
        assertFalse(reportId.equals(afterNewObjective.activeObjectiveId()));
        assertEquals(
                DialogueObjectiveStatus.COMPLETED,
                afterNewObjective.objectives().stream()
                        .filter(objective -> objective.id().equals(reportId))
                        .findFirst().orElseThrow().status()
        );
        assertEquals(2, afterNewObjective.objectives().size());
        assertEquals(1L, afterNewObjective.objectives().stream()
                .filter(objective -> objective.status() == DialogueObjectiveStatus.ACTIVE)
                .count());
        verify(dialogueInterpreter, times(3)).interpret(any(ContextEngineInput.class));
        verify(assistant, times(2)).respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(UUID.class),
                nullable(com.fasterxml.jackson.databind.JsonNode.class)
        );
    }

    @Test
    void persistsOnlyAllowlistedOperationalCriteriaInTaskSnapshot()
            throws Exception {
        UserIdentity identity = new UserIdentity("snapshot-security-owner", "client-a");
        Conversation conversation = conversationRepository.create(
                identity.applicationId(),
                identity.ownerId()
        );

        String criteriaJson = """
                {"filters":[
                  {"field":"service","operator":"EQ","value":"Informatique"},
                  {"field":"classe","operator":"EQ","value":10},
                  {"field":"sexe","operator":"EQ","value":"M"},
                  {"field":"matricule","operator":"EQ","value":"RH12345678"}
                ]}
                """;

        com.fasterxml.jackson.databind.node.ObjectNode activeState =
                objectMapper.createObjectNode();
        activeState.put("toolName", "search_agents");
        activeState.put("intent", "TOOL_CALL");
        com.fasterxml.jackson.databind.node.ObjectNode activeArguments =
                activeState.putObject("arguments");
        activeArguments.put("criteriaJson", criteriaJson);
        activeArguments.put("authorization", "Bearer private-token");

        jdbcTemplate.update("""
                UPDATE bandy_conversation_memory
                SET active_state = CAST(? AS JSONB)
                WHERE conversation_id = ?
                """,
                objectMapper.writeValueAsString(activeState),
                conversation.id()
        );

        // Exercise the snapshot persistence boundary directly. Conversation
        // tool-result eligibility is covered by ConversationRepository tests;
        // it must not make this sanitizer test depend on the tool registry.
        taskStateSnapshotRepository.capture(
                conversation.id(),
                "snapshot-security-objective"
        );

        String stateJson = jdbcTemplate.queryForObject("""
                SELECT operational_state::text
                FROM bandy_conversation_task_state
                WHERE conversation_id = ? AND objective_id = ?
                """,
                String.class,
                conversation.id(),
                "snapshot-security-objective"
        );

        assertTrue(stateJson.contains("Informatique"));
        assertTrue(stateJson.contains("classe"));
        assertFalse(stateJson.contains("RH12345678"));
        assertFalse(stateJson.contains("Bearer private-token"));
        assertFalse(stateJson.contains("sexe"));
    }

    @Test
    void clarificationAnswerResolvesPendingReferenceAndContinuesTheSameObjective() {
        UserIdentity identity = new UserIdentity("clarification-resolution-owner", "client-a");
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
                new ChatResponse("Le rapport est prêt.", List.of()),
                new ChatResponse("Voici le complément du rapport.", List.of())
        );

        when(dialogueInterpreter.interpret(any(ContextEngineInput.class)))
                .thenAnswer(invocation -> {
                    ContextEngineInput input = invocation.getArgument(0);
                    DialogueState state = input.dialogueState();
                    if (state.objectives().isEmpty()) {
                        return new ContextInterpretationProposal(
                                DialogueRelation.NEW_TASK,
                                null,
                                "Création du rapport.",
                                null,
                                InterpretationConfidence.HIGH,
                                "Préparer un rapport de ventes",
                                "Rapport de ventes en préparation.",
                                List.of()
                        );
                    }
                    if (input.currentMessage().equals("Ajoute celui-là aussi.")) {
                        return new ContextInterpretationProposal(
                                DialogueRelation.NEEDS_CLARIFICATION,
                                state.activeObjectiveId(),
                                "La référence « celui-là » ne désigne pas un élément identifiable.",
                                "Quel élément souhaitez-vous ajouter au rapport ?",
                                InterpretationConfidence.HIGH,
                                null,
                                null,
                                List.of()
                        );
                    }
                    assertNotNull(state.pendingClarification());
                    assertEquals(
                            state.activeObjectiveId(),
                            state.pendingClarification().targetObjectiveId()
                    );
                    assertTrue(input.currentMessage().contains("les ventes régionales"));
                    return new ContextInterpretationProposal(
                            DialogueRelation.USER_CLARIFICATION,
                            state.pendingClarification().targetObjectiveId(),
                            "La réponse précise la référence demandée.",
                            null,
                            InterpretationConfidence.HIGH,
                            null,
                            "Le rapport inclut maintenant les ventes régionales.",
                            List.of()
                    );
                });

        conversationService.sendMessage(
                conversation.id(), identity,
                "Prépare un rapport de ventes.", null, UUID.randomUUID()
        );
        DialogueState afterCreation = dialogueStateRepository.load(conversation.id());
        String objectiveId = afterCreation.activeObjectiveId();
        assertNotNull(objectiveId);

        ChatResponse clarification = conversationService.sendMessage(
                conversation.id(), identity,
                "Ajoute celui-là aussi.", null, UUID.randomUUID()
        );
        DialogueState whilePending = dialogueStateRepository.load(conversation.id());

        assertEquals(
                "Quel élément souhaitez-vous ajouter au rapport ?",
                clarification.message()
        );
        assertNotNull(whilePending.pendingClarification());
        assertEquals(objectiveId, whilePending.activeObjectiveId());
        assertEquals(1, whilePending.objectives().size());

        conversationService.sendMessage(
                conversation.id(), identity,
                "Je parle des ventes régionales.", null, UUID.randomUUID()
        );
        DialogueState afterAnswer = dialogueStateRepository.load(conversation.id());

        assertEquals(objectiveId, afterAnswer.activeObjectiveId());
        assertEquals(null, afterAnswer.pendingClarification());
        assertEquals(1, afterAnswer.objectives().size());
        assertEquals(
                "Le rapport inclut maintenant les ventes régionales.",
                afterAnswer.objectives().get(0).summary()
        );
        verify(dialogueInterpreter, times(3)).interpret(any(ContextEngineInput.class));
        verify(assistant, times(2)).respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(UUID.class),
                nullable(com.fasterxml.jackson.databind.JsonNode.class)
        );
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
