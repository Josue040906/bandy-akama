package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateRepository;
import com.bandyakama.conversation.task.TaskStateSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final TaskStateSnapshotRepository taskStateSnapshotRepository;

    @Autowired
    public ConversationCompletionService(
            ConversationRepository conversationRepository,
            DialogueStateRepository dialogueStateRepository,
            TaskStateSnapshotRepository taskStateSnapshotRepository
    ) {
        this.conversationRepository = Objects.requireNonNull(
                conversationRepository,
                "conversationRepository must not be null."
        );
        this.dialogueStateRepository = Objects.requireNonNull(
                dialogueStateRepository,
                "dialogueStateRepository must not be null."
        );
        this.taskStateSnapshotRepository = Objects.requireNonNull(
                taskStateSnapshotRepository,
                "taskStateSnapshotRepository must not be null."
        );
    }

    /** Compatibility constructor for isolated unit tests. */
    public ConversationCompletionService(
            ConversationRepository conversationRepository,
            DialogueStateRepository dialogueStateRepository
    ) {
        this.conversationRepository = Objects.requireNonNull(conversationRepository);
        this.dialogueStateRepository = Objects.requireNonNull(dialogueStateRepository);
        this.taskStateSnapshotRepository = null;
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
        complete(
                conversationId, idempotencyKey, claimId, response,
                proposedDialogueState, Optional.empty(), null
        );
    }

    @Transactional
    public void complete(
            UUID conversationId,
            UUID idempotencyKey,
            UUID claimId,
            ChatResponse response,
            Optional<DialogueState> proposedDialogueState,
            Optional<String> activeObjectiveId,
            JsonNode resumedStateOverride
    ) {
        Objects.requireNonNull(conversationId, "conversationId must not be null.");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null.");
        Objects.requireNonNull(claimId, "claimId must not be null.");
        Objects.requireNonNull(response, "response must not be null.");
        Objects.requireNonNull(
                proposedDialogueState,
                "proposedDialogueState must not be null."
        );
        complete(
                conversationId, idempotencyKey, claimId, response,
                proposedDialogueState, activeObjectiveId, resumedStateOverride,
                Optional.empty(), null
        );
    }

    /**
     * Completes a plan-based turn while preserving the operational state of
     * the objective being left and capturing the newly focused objective.
     * All snapshots, memory, response, dialogue state and claim updates share
     * the same REQUIRED transaction.
     */
    @Transactional
    public void complete(
            UUID conversationId,
            UUID idempotencyKey,
            UUID claimId,
            ChatResponse response,
            Optional<DialogueState> proposedDialogueState,
            Optional<String> activeObjectiveId,
            JsonNode resumedStateOverride,
            Optional<String> previousObjectiveId,
            JsonNode previousOperationalState
    ) {
        complete(
                conversationId, idempotencyKey, claimId, response,
                proposedDialogueState, activeObjectiveId, resumedStateOverride,
                previousObjectiveId, previousOperationalState, java.util.Map.of()
        );
    }

    @Transactional
    public void complete(
            UUID conversationId,
            UUID idempotencyKey,
            UUID claimId,
            ChatResponse response,
            Optional<DialogueState> proposedDialogueState,
            Optional<String> activeObjectiveId,
            JsonNode resumedStateOverride,
            Optional<String> previousObjectiveId,
            JsonNode previousOperationalState,
            java.util.Map<String, JsonNode> objectiveStates
    ) {
        Objects.requireNonNull(conversationId, "conversationId must not be null.");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null.");
        Objects.requireNonNull(claimId, "claimId must not be null.");
        Objects.requireNonNull(response, "response must not be null.");
        Objects.requireNonNull(proposedDialogueState, "proposedDialogueState must not be null.");
        Objects.requireNonNull(activeObjectiveId, "activeObjectiveId must not be null.");
        Objects.requireNonNull(previousObjectiveId, "previousObjectiveId must not be null.");

        // Persist the state being left before completeRequest changes the
        // conversation's operational memory. This write is still uncommitted.
        if (taskStateSnapshotRepository != null && previousOperationalState != null) {
            previousObjectiveId.filter(id -> !id.isBlank()).ifPresent(id ->
                    taskStateSnapshotRepository.capture(
                            conversationId, id, previousOperationalState
                    )
            );
        }

        // Both repositories use REQUIRED transactions. Their writes therefore
        // join this boundary and cannot commit independently of one another.
        if (resumedStateOverride == null) {
            conversationRepository.completeRequest(
                    conversationId,
                    idempotencyKey,
                    claimId,
                    response
            );
        } else {
            conversationRepository.completeRequest(
                    conversationId,
                    idempotencyKey,
                    claimId,
                    response,
                    resumedStateOverride
            );
        }
        proposedDialogueState.ifPresent(state ->
                dialogueStateRepository.save(conversationId, state)
        );
        if (taskStateSnapshotRepository != null) {
            java.util.Map<String, JsonNode> safeObjectiveStates =
                    objectiveStates == null ? java.util.Map.of() : objectiveStates;
            safeObjectiveStates.forEach((objectiveId, state) -> {
                if (objectiveId != null && !objectiveId.isBlank()
                        && state != null && state.isObject()) {
                    taskStateSnapshotRepository.capture(
                            conversationId, objectiveId, state
                    );
                }
            });
            activeObjectiveId
                    .filter(id -> !id.isBlank())
                    .filter(id -> !safeObjectiveStates.containsKey(id))
                    .ifPresent(id -> taskStateSnapshotRepository.capture(conversationId, id));
        }
    }
}
