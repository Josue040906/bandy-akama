package com.bandyakama.conversation.dialogue;

import java.util.List;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * Application boundary for preparing a dialogue transition.
 *
 * <p>The manager restores the durable dialogue state, builds the immutable
 * engine input and delegates interpretation/validation/transition to the
 * context engine. It deliberately does not persist the proposed state:
 * persistence must happen in the same transaction as successful response
 * completion, never when interpretation alone succeeds.</p>
 */
@Service
public class DialogueStateManager {
    private final DialogueStateRepository stateRepository;
    private final ConversationContextEngine contextEngine;

    public DialogueStateManager(
            DialogueStateRepository stateRepository,
            ConversationContextEngine contextEngine
    ) {
        this.stateRepository = Objects.requireNonNull(
                stateRepository,
                "stateRepository must not be null."
        );
        this.contextEngine = Objects.requireNonNull(
                contextEngine,
                "contextEngine must not be null."
        );
    }

    /**
     * Prepares a validated dialogue-state transition for one user message.
     *
     * @return the engine's decision and proposed state; the caller must only
     *         persist proposedState after the assistant request completes
     *         successfully, within that completion transaction
     */
    public ContextResolution prepareTurn(
            UUID conversationId,
            String currentMessage,
            long currentMessageSequenceNumber,
            List<DialogueMessage> history,
            String conversationSummary
    ) {
        Objects.requireNonNull(conversationId, "conversationId must not be null.");

        DialogueState currentState = stateRepository.load(conversationId);
        if (currentState.objectives().isEmpty()) {
            currentState = recoverActiveObjective(history, conversationSummary);
        }
        ContextEngineInput input = new ContextEngineInput(
                currentMessage,
                currentMessageSequenceNumber,
                currentState,
                history,
                conversationSummary
        );
        return contextEngine.resolve(input);
    }

    /**
     * Rehydrates a minimal active objective when the dialogue-state row was
     * lost but durable message history survived. The latest user message is
     * the safest available anchor; the interpreter decides whether the
     * incoming turn continues it or starts an independent objective.
     */
    private DialogueState recoverActiveObjective(
            List<DialogueMessage> history,
            String conversationSummary
    ) {
        DialogueMessage latestUserMessage = history.stream()
                .filter(message -> message.role() == DialogueMessage.Role.USER)
                .filter(message -> !isControlMessage(message.content()))
                .reduce((first, second) -> second)
                .orElse(null);
        if (latestUserMessage == null) {
            return DialogueState.empty();
        }

        Instant recoveredAt = Instant.EPOCH;
        DialogueObjective recovered = new DialogueObjective(
                "recovered-" + latestUserMessage.sequenceNumber(),
                latestUserMessage.content(),
                conversationSummary.isBlank()
                        ? latestUserMessage.content()
                        : conversationSummary,
                DialogueObjectiveStatus.ACTIVE,
                null,
                recoveredAt,
                recoveredAt,
                List.of()
        );
        return new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                recovered.id(),
                List.of(recovered),
                null
        );
    }

    private boolean isControlMessage(String message) {
        String normalized = java.text.Normalizer.normalize(
                message.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD
        ).replaceAll("\\p{M}+", "")
                .replaceAll("\\s+([?!.])", "$1")
                .replaceAll("\\s+", " ").strip();
        return normalized.matches(
                "^(bonjour|bonsoir|salut|coucou|hello|hi|hey|salama|manao ahoana)"
                        + "( bandy( akama)?| assistant)?[.!?]*$"
        ) || normalized.matches(
                "^(je veux |je souhaite |je voudrais )?(poser )?(une )?"
                        + "(nouvelle question|nouvelle recherche)[.!?]*$"
                        + "|^(changeons de sujet|passons a autre chose|"
                        + "passons a un autre sujet|parlons d['’]un autre sujet|"
                        + "rien a voir)[.!?]*$"
        );
    }
}
