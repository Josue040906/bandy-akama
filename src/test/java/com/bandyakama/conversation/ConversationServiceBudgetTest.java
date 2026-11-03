package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueStateManager;
import com.bandyakama.conversation.dialogue.ContextResolution;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.ConversationPlanProposal;
import com.bandyakama.conversation.dialogue.ConversationPlanValidator;
import com.bandyakama.conversation.dialogue.ConversationPlanTransitioner;
import com.bandyakama.conversation.dialogue.ContextEngineInput;
import com.bandyakama.conversation.dialogue.InterpretationConfidence;
import com.bandyakama.conversation.task.TaskStateManager;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.observability.RequestBudget;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceBudgetTest {
    @Test
    void expiredTurnIsReportedAsTimeoutAndReleasesItsRequestClaim()
            throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        ConversationCompletionService completionService =
                mock(ConversationCompletionService.class);
        DialogueStateManager dialogueStateManager = mock(DialogueStateManager.class);
        GeminiAssistant assistant = mock(GeminiAssistant.class);
        UUID claimId = UUID.randomUUID();
        when(repository.beginRequest(
                any(),
                anyString(),
                anyString(),
                any(),
                anyString()
        )).thenReturn(new ConversationRepository.RequestStart(
                null,
                claimId,
                new ObjectMapper().createObjectNode()
        ));
        when(repository.dialogueTurnContextForRequest(any(), any()))
                .thenReturn(new ConversationRepository.DialogueTurnContext(
                        2, java.util.List.of(), ""
                ));
        ContextEngineInput planInput = new ContextEngineInput(
                "Cherche les agents du service.", 2, DialogueState.empty(),
                java.util.List.of(), ""
        );
        ConversationPlanProposal proposal = new ConversationPlanProposal(
                "Nouvel objectif de recherche.",
                InterpretationConfidence.HIGH,
                java.util.List.of(new ConversationPlanProposal.Operation(
                        "create-search",
                        ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                        null,
                        "Chercher les agents du service",
                        "Recherche d'agents par service.",
                        null,
                        java.util.List.of(),
                        java.util.List.of()
                )),
                java.util.List.of()
        );
        var validatedPlan = new ConversationPlanValidator().validate(planInput, proposal);
        DialogueState proposedState = new ConversationPlanTransitioner()
                .transition(planInput, validatedPlan);
        when(dialogueStateManager.prepareTurn(
                any(), anyString(), anyLong(), any(), anyString()
        )).thenReturn(ContextResolution.fromPlan(
                validatedPlan, DialogueState.empty(), proposedState
        ));
        when(assistant.respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(),
                any(),
                any(),
                anyMap()
        )).thenAnswer(invocation -> {
            Thread.sleep(250);
            return new ChatResponse("late response", java.util.List.of());
        });
        AtomicBoolean cleanupRanOutsideBudget = new AtomicBoolean();
        doAnswer(invocation -> {
            cleanupRanOutsideBudget.set(
                    RequestBudget.current().isEmpty()
            );
            return null;
        }).when(repository).failRequest(any(), any(), any());

        ConversationService service = new ConversationService(
                repository,
                assistant,
                dialogueStateManager,
                completionService,
                new TaskStateManager(new ObjectMapper()),
                new PerformanceTelemetry(),
                100
        );

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.sendMessage(
                        UUID.randomUUID(),
                        new UserIdentity("42", "assistant-rh"),
                        "Cherche les agents du service.",
                        null,
                        UUID.randomUUID()
                )
        );

        assertEquals(504, failure.getStatusCode().value());
        assertTrue(cleanupRanOutsideBudget.get());
        verify(repository).failRequest(any(), any(), any());
    }
}
