package com.bandyakama.conversation.dialogue;

import java.time.Instant;
import java.util.Objects;

/**
 * A question that must be answered before the dialogue can safely progress.
 *
 * @param targetObjectiveId objective being clarified, or null for an
 *                          ambiguous request that has not been assigned yet
 * @param question concise question presented to the user
 * @param reason why the missing information is needed
 * @param createdAt time the clarification was requested
 */
public record PendingClarification(
        String targetObjectiveId,
        String question,
        String reason,
        Instant createdAt
) {
    public PendingClarification {
        question = requireText(question, "question");
        reason = requireText(reason, "reason");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        if (targetObjectiveId != null && targetObjectiveId.isBlank()) {
            throw new IllegalArgumentException(
                    "targetObjectiveId must be null or non-blank."
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
