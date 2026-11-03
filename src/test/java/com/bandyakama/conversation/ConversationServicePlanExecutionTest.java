package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.ContextEngineInput;
import com.bandyakama.conversation.dialogue.ContextResolution;
import com.bandyakama.conversation.dialogue.ConversationPlanProposal;
import com.bandyakama.conversation.dialogue.ConversationPlanTransitioner;
import com.bandyakama.conversation.dialogue.ConversationPlanValidator;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateManager;
import com.bandyakama.conversation.dialogue.InterpretationConfidence;
import com.bandyakama.conversation.task.TaskStateManager;
import com.bandyakama.conversation.task.TaskStateSnapshotRepository;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationServicePlanExecutionTest {
    @Test
    void sendsEveryValidatedObjectiveOperationToAssistantAndCommitsThePlan() {
        ConversationRepository repository = mock(ConversationRepository.class);
        GeminiAssistant assistant = mock(GeminiAssistant.class);
        DialogueStateManager dialogueManager = mock(DialogueStateManager.class);
        ConversationCompletionService completion = mock(ConversationCompletionService.class);
        TaskStateSnapshotRepository snapshots = mock(TaskStateSnapshotRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        JsonNode activeState = mapper.createObjectNode();

        when(repository.beginRequest(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new ConversationRepository.RequestStart(null, claimId, activeState));
        when(repository.dialogueTurnContextForRequest(any(), any()))
                .thenReturn(new ConversationRepository.DialogueTurnContext(3, List.of(), ""));
        when(repository.contextForModelRequest(any(), any(), any(), anyBoolean()))
                .thenReturn("Contexte de conversation.");

        var createReport = new ConversationPlanProposal.Operation(
                "create-report",
                ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                null,
                "Préparer un rapport financier",
                "Rapport pour l'exercice 2026.",
                null,
                List.of(),
                List.of()
        );
        var createTrip = new ConversationPlanProposal.Operation(
                "create-trip",
                ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                null,
                "Organiser un voyage",
                "Préparer les étapes et le budget.",
                null,
                List.of(),
                List.of()
        );
        ConversationPlanProposal proposal = new ConversationPlanProposal(
                "Deux objectifs indépendants ont été exprimés.",
                InterpretationConfidence.HIGH,
                List.of(createReport, createTrip),
                List.of()
        );
        ContextEngineInput engineInput = new ContextEngineInput(
                "Prépare un rapport financier et organise mon voyage.",
                3,
                DialogueState.empty(),
                List.of(),
                ""
        );
        var validated = new ConversationPlanValidator().validate(engineInput, proposal);
        DialogueState proposed = new ConversationPlanTransitioner()
                .transition(engineInput, validated);
        ContextResolution resolution = ContextResolution.fromPlan(
                validated, DialogueState.empty(), proposed
        );
        when(dialogueManager.prepareTurn(any(), anyString(), anyLong(), any(), anyString()))
                .thenReturn(resolution);
        when(assistant.respond(anyString(), nullable(String.class), anyString(), any(), any()))
                .thenReturn(new ChatResponse("Je vais traiter les deux objectifs.", List.of()));

        ConversationService service = new ConversationService(
                repository,
                assistant,
                dialogueManager,
                completion,
                new TaskStateManager(mapper),
                snapshots,
                new PerformanceTelemetry(),
                30_000
        );

        ChatResponse response = service.sendMessage(
                conversationId,
                new UserIdentity("owner", "assistant-rh"),
                "Prépare un rapport financier et organise mon voyage.",
                null,
                requestId
        );

        assertEquals("Je vais traiter les deux objectifs.", response.message());
        ArgumentCaptor<String> assistantContext = ArgumentCaptor.forClass(String.class);
        verify(assistant).respond(
                anyString(), nullable(String.class), assistantContext.capture(), eq(requestId),
                isNull()
        );
        assertEquals(true, assistantContext.getValue().contains("Préparer un rapport financier"));
        assertEquals(true, assistantContext.getValue().contains("Organiser un voyage"));
        assertEquals(true, assistantContext.getValue().contains("CREATE_OBJECTIVE"));
        verify(completion).complete(
                eq(conversationId),
                eq(requestId),
                eq(claimId),
                any(ChatResponse.class),
                eq(Optional.of(proposed)),
                eq(Optional.of(proposed.activeObjectiveId())),
                isNull(),
                eq(Optional.empty()),
                isNull()
        );
    }
}
