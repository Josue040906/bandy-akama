package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateRepository;
import com.bandyakama.conversation.task.TaskStateSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    void capturesActiveObjectiveSnapshotAfterSuccessfulResponsePersistence() {
        ConversationRepository repository = mock(ConversationRepository.class);
        DialogueStateRepository dialogueRepository = mock(DialogueStateRepository.class);
        TaskStateSnapshotRepository taskSnapshots = mock(TaskStateSnapshotRepository.class);
        ConversationCompletionService service = new ConversationCompletionService(
                repository, dialogueRepository, taskSnapshots
        );
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        ChatResponse response = new ChatResponse("Recherche terminée.", List.of());
        DialogueState state = DialogueState.empty();
        com.fasterxml.jackson.databind.JsonNode resumedState =
                new ObjectMapper().createObjectNode().put("toolName", "search_agents");

        service.complete(
                conversationId,
                requestId,
                claimId,
                response,
                Optional.of(state),
                Optional.of("objective-1"),
                resumedState
        );

        InOrder order = inOrder(repository, dialogueRepository, taskSnapshots);
        order.verify(repository).completeRequest(
                conversationId, requestId, claimId, response, resumedState
        );
        order.verify(dialogueRepository).save(conversationId, state);
        order.verify(taskSnapshots).capture(conversationId, "objective-1");
    }

    @Test
    void snapshotsPreviousAndNewFocusInsideOneCompletionSequence() {
        ConversationRepository repository = mock(ConversationRepository.class);
        DialogueStateRepository dialogueRepository = mock(DialogueStateRepository.class);
        TaskStateSnapshotRepository taskSnapshots = mock(TaskStateSnapshotRepository.class);
        ConversationCompletionService service = new ConversationCompletionService(
                repository, dialogueRepository, taskSnapshots
        );
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        ChatResponse response = new ChatResponse("Objectif changé.", List.of());
        DialogueState proposed = DialogueState.empty();
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode previousState =
                mapper.createObjectNode().put("toolName", "search_agents");
        com.fasterxml.jackson.databind.JsonNode resumedState =
                mapper.createObjectNode().put("toolName", "get_organization_info");

        service.complete(
                conversationId, requestId, claimId, response,
                Optional.of(proposed), Optional.of("objective-new"), resumedState,
                Optional.of("objective-old"), previousState
        );

        InOrder order = inOrder(taskSnapshots, repository, dialogueRepository);
        order.verify(taskSnapshots).capture(
                conversationId, "objective-old", previousState
        );
        order.verify(repository).completeRequest(
                conversationId, requestId, claimId, response, resumedState
        );
        order.verify(dialogueRepository).save(conversationId, proposed);
        order.verify(taskSnapshots).capture(conversationId, "objective-new");
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
        doThrow(new IllegalStateException("completion failed"))
                .when(conversationRepository)
                .completeRequest(
                        conversationId,
                        requestId,
                        claimId,
                        response
                );

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
