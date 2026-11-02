package com.bandyakama.conversation.dialogue;

import java.util.Objects;

/**
 * A persisted conversational message supplied to the context engine.
 *
 * <p>Sequence numbers are conversation-local and must be strictly increasing
 * in the input history.</p>
 */
public record DialogueMessage(
        long sequenceNumber,
        Role role,
        String content
) {
    public DialogueMessage {
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException(
                    "sequenceNumber must be positive."
            );
        }
        role = Objects.requireNonNull(role, "role must not be null.");
        content = requireText(content, "content");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value;
    }

    public enum Role {
        USER,
        ASSISTANT
    }
}
