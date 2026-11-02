package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationCompletionServiceTest {

    private final ConversationRepository conversationRepository =
            mock(ConversationRepository.class);
    private final DialogueStateRepository dialogueStateRepository =
            mock(DialogueStateRepository.class);
    private final ConversationCompletionService completionService =
            new ConversationCompletionService(
                    conversationRepository,
                    dialogueStateRepository
            );

    @Test
    void persistsDialogueSnapshotOnlyAfterConversationCompletion() {
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        ChatResponse response = new ChatResponse("Réponse terminée.", List.of());
        DialogueState proposedState = DialogueState.empty();

        completionService.complete(
                conversationId,
                requestId,
                claimId,
                response,
                Optional.of(proposedState)
        );

        InOrder order = inOrder(
                conversationRepository,
                dialogueStateRepository
        );
        order.verify(conversationRepository).completeRequest(
                conversationId,
                requestId,
                claimId,
                response
        );
        order.verify(dialogueStateRepository).save(
                conversationId,
                proposedState
        );
    }

    @Test
    void doesNotWriteDialogueStateForLegacyCompletionPaths() {
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        ChatResponse response = new ChatResponse("Bonjour.", List.of());

        completionService.complete(
                conversationId,
                requestId,
                claimId,
                response,
                Optional.empty()
        );

        verify(conversationRepository).completeRequest(
                conversationId,
                requestId,
                claimId,
                response
        );
        verify(dialogueStateRepository, never()).save(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rollsBackByPropagatingConversationCompletionFailureBeforeStateSave() {
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        ChatResponse response = new ChatResponse("Réponse.", List.of());
        when(conversationRepository.completeRequest(
                conversationId,
                requestId,
                claimId,
                response
        )).thenThrow(new IllegalStateException("completion failed"));

        assertThrows(
                IllegalStateException.class,
                () -> completionService.complete(
                        conversationId,
                        requestId,
                        claimId,
                        response,
                        Optional.of(DialogueState.empty())
                )
        );

        verify(dialogueStateRepository, never()).save(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsNullOptionalBeforeWritingAnything() {
        assertThrows(
                NullPointerException.class,
                () -> completionService.complete(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        new ChatResponse("Réponse.", List.of()),
                        null
                )
        );
        verify(conversationRepository, never()).completeRequest(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
