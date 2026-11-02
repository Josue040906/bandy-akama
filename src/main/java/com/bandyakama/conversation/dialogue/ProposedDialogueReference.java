package com.bandyakama.conversation.dialogue;

import java.util.Objects;

/**
 * A candidate reference extracted from the current message.
 *
 * <p>The backend, not the language model, assigns its stable identifier and
 * source message sequence when applying the transition.</p>
 */
public record ProposedDialogueReference(String label, String kind) {
    public ProposedDialogueReference {
        label = requireText(label, "label");
        kind = requireText(kind, "kind");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value;
    }
}
