package com.bandyakama.conversation.dialogue;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A uniquely addressable question that must be answered before its related
 * dialogue operation can safely progress.
 */
public record PendingClarification(
        String clarificationId,
        String targetObjectiveId,
        String question,
        String reason,
        Instant createdAt
) {
    public PendingClarification(
            String targetObjectiveId,
            String question,
            String reason,
            Instant createdAt
    ) {
        this(null, targetObjectiveId, question, reason, createdAt);
    }

    public PendingClarification {
        if (clarificationId == null || clarificationId.isBlank()) {
            clarificationId = UUID.randomUUID().toString();
        }
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
