package com.bandyakama.conversation.dialogue;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, domain-neutral snapshot supplied to the conversation context
 * engine for one incoming user message.
 *
 * <p>The history must be chronological and must not include the current
 * message. Operational tool state is deliberately excluded.</p>
 */
public record ContextEngineInput(
        String currentMessage,
        long currentMessageSequenceNumber,
        DialogueState dialogueState,
        List<DialogueMessage> history,
        String conversationSummary
) {
    public ContextEngineInput {
        currentMessage = requireText(currentMessage, "currentMessage");
        if (currentMessageSequenceNumber < 1) {
            throw new IllegalArgumentException(
                    "currentMessageSequenceNumber must be positive."
            );
        }
        dialogueState = Objects.requireNonNull(
                dialogueState, "dialogueState must not be null."
        );
        history = List.copyOf(
                Objects.requireNonNull(history, "history must not be null.")
        );
        conversationSummary = Objects.requireNonNull(
                conversationSummary, "conversationSummary must not be null."
        );

        long previousSequence = 0;
        for (DialogueMessage message : history) {
            Objects.requireNonNull(message, "history must not contain null messages.");
            if (message.sequenceNumber() <= previousSequence) {
                throw new IllegalArgumentException(
                        "history must be ordered by strictly increasing sequence number."
                );
            }
            if (message.sequenceNumber() >= currentMessageSequenceNumber) {
                throw new IllegalArgumentException(
                        "history messages must precede the current message."
                );
            }
            previousSequence = message.sequenceNumber();
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value;
    }
}
