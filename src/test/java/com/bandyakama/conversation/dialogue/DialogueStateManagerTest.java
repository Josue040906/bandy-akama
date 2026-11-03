package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DialogueStateManagerTest {

    private final DialogueStateRepository stateRepository =
            mock(DialogueStateRepository.class);
    private final ConversationContextEngine contextEngine =
            mock(ConversationContextEngine.class);
    private final DialogueStateManager manager =
            new DialogueStateManager(stateRepository, contextEngine);

    @Test
    void delegatesHistoricalRecoveryToTheSemanticEngineWithoutRegexClassification() {
        UUID conversationId = UUID.randomUUID();
        DialogueState persistedState = DialogueState.empty();
        ContextResolution expectedResolution = new ContextResolution(
                new ContextDecision(
                        DialogueRelation.NEW_TASK,
                        null,
                        "Nouvel objectif indépendant.",
                        null
                ),
                InterpretationConfidence.HIGH,
                new DialogueState(
                        DialogueState.CURRENT_SCHEMA_VERSION,
                        "objective-1",
                        List.of(new DialogueObjective(
                                "objective-1", "Préparer un rapport", "Préparation.",
                                DialogueObjectiveStatus.ACTIVE, null,
                                Instant.EPOCH, Instant.EPOCH, List.of()
                        )),
                        null
                )
        );
        List<DialogueMessage> history = List.of(
                new DialogueMessage(1, DialogueMessage.Role.USER, "Je prépare un rapport."),
                new DialogueMessage(2, DialogueMessage.Role.ASSISTANT, "Quel est le sujet ?")
        );
        when(stateRepository.load(conversationId)).thenReturn(persistedState);
        when(contextEngine.resolve(any(ContextEngineInput.class)))
                .thenReturn(expectedResolution);

        ContextResolution actual = manager.prepareTurn(
                conversationId, "Le rapport concerne les ventes.",
                3, history, "Échange initial sur la préparation d'un rapport."
        );

        assertSame(expectedResolution, actual);
        ArgumentCaptor<ContextEngineInput> inputCaptor =
                ArgumentCaptor.forClass(ContextEngineInput.class);
        verify(contextEngine).resolve(inputCaptor.capture());
        ContextEngineInput input = inputCaptor.getValue();
        assertEquals(persistedState, input.dialogueState());
        assertEquals("Le rapport concerne les ventes.", input.currentMessage());
        assertEquals(3, input.currentMessageSequenceNumber());
        assertEquals(history, input.history());
        assertEquals(
                "Échange initial sur la préparation d'un rapport.",
                input.conversationSummary()
        );
        verify(stateRepository).load(conversationId);
        verify(stateRepository, never()).save(any(), any());
    }

    @Test
    void doesNotPersistAnythingWhenContextResolutionFails() {
        UUID conversationId = UUID.randomUUID();
        when(stateRepository.load(conversationId))
                .thenReturn(DialogueState.empty());
        when(contextEngine.resolve(any(ContextEngineInput.class)))
                .thenThrow(new IllegalStateException("interpreter unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> manager.prepareTurn(
                        conversationId,
                        "Continue.",
                        1,
                        List.of(),
                        ""
                )
        );

        verify(stateRepository, never()).save(any(), any());
    }

    @Test
    void rejectsMissingConversationIdBeforeLoadingState() {
        assertThrows(
                NullPointerException.class,
                () -> manager.prepareTurn(
                        null,
                        "Bonjour",
                        1,
                        List.of(),
                        ""
                )
        );
        verify(stateRepository, never()).load(any());
    }
}
