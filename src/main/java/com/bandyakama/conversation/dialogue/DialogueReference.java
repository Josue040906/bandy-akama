package com.bandyakama.conversation.dialogue;

import java.util.Objects;

/**
 * A domain-neutral reference mentioned in a conversation objective.
 *
 * @param id stable reference identifier within the dialogue state
 * @param label human-readable label used to resolve later references
 * @param kind generic entity kind, not a business-specific type
 * @param sourceMessageSequence message sequence that introduced the reference
 */
public record DialogueReference(
        String id,
        String label,
        String kind,
        long sourceMessageSequence
) {
    public DialogueReference {
        id = requireText(id, "id");
        label = requireText(label, "label");
        kind = requireText(kind, "kind");
        if (sourceMessageSequence < 1) {
            throw new IllegalArgumentException(
                    "sourceMessageSequence must be greater than zero."
            );
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
