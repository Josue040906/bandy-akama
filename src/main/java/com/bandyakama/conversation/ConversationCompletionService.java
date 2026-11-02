package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates the durable completion of a conversation turn.
 *
 * <p>The assistant response, operational memory updates, idempotency record,
 * claim release and optional universal dialogue snapshot are committed in a
 * single transaction. A failure in either persistence adapter rolls the
 * entire completion back.</p>
 */
@Service
public class ConversationCompletionService {
    private final ConversationRepository conversationRepository;
    private final DialogueStateRepository dialogueStateRepository;

    public ConversationCompletionService(
            ConversationRepository conversationRepository,
            DialogueStateRepository dialogueStateRepository
    ) {
        this.conversationRepository = Objects.requireNonNull(
                conversationRepository,
                "conversationRepository must not be null."
        );
        this.dialogueStateRepository = Objects.requireNonNull(
                dialogueStateRepository,
                "dialogueStateRepository must not be null."
        );
    }

    /**
     * Completes a turn and, when supplied, stores its proposed dialogue state.
     *
     * <p>The state is optional to preserve existing deterministic response
     * paths until they are explicitly integrated with the context engine.</p>
     */
    @Transactional
    public void complete(
            UUID conversationId,
            UUID idempotencyKey,
            UUID claimId,
            ChatResponse response,
            Optional<DialogueState> proposedDialogueState
    ) {
        Objects.requireNonNull(conversationId, "conversationId must not be null.");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null.");
        Objects.requireNonNull(claimId, "claimId must not be null.");
        Objects.requireNonNull(response, "response must not be null.");
        Objects.requireNonNull(
                proposedDialogueState,
                "proposedDialogueState must not be null."
        );

        // Both repositories use REQUIRED transactions. Their writes therefore
        // join this boundary and cannot commit independently of one another.
        conversationRepository.completeRequest(
                conversationId,
                idempotencyKey,
                claimId,
                response
        );
        proposedDialogueState.ifPresent(state ->
                dialogueStateRepository.save(conversationId, state)
        );
    }
}
