package com.bandyakama.conversation.dialogue;

import java.util.Objects;

/**
 * Validated, domain-neutral decision produced by a future context engine.
 *
 * @param relation relationship between the incoming message and dialogue
 * @param targetObjectiveId existing objective to update or resume; null when
 *                          a new objective must be created or the target is unknown
 * @param rationale concise explanation suitable for diagnostics and tests
 * @param clarificationQuestion question to ask when the relation is ambiguous
 */
public record ContextDecision(
        DialogueRelation relation,
        String targetObjectiveId,
        String rationale,
        String clarificationQuestion
) {
    public ContextDecision {
        relation = Objects.requireNonNull(relation, "relation must not be null.");
        rationale = requireText(rationale, "rationale");

        boolean requiresExistingTarget = switch (relation) {
            case CONTINUATION, TOPIC_RESUMPTION, CORRECTION, USER_CLARIFICATION -> true;
            case NEW_TASK, NEEDS_CLARIFICATION -> false;
        };
        if (requiresExistingTarget
                && (targetObjectiveId == null || targetObjectiveId.isBlank())) {
            throw new IllegalArgumentException(
                    relation + " requires a target objective."
            );
        }
        if (targetObjectiveId != null && targetObjectiveId.isBlank()) {
            throw new IllegalArgumentException(
                    "targetObjectiveId must be null or non-blank."
            );
        }

        if (relation == DialogueRelation.NEEDS_CLARIFICATION) {
            clarificationQuestion = requireText(
                    clarificationQuestion,
                    "clarificationQuestion"
            );
        } else if (clarificationQuestion != null) {
            throw new IllegalArgumentException(
                    "clarificationQuestion is only valid for NEEDS_CLARIFICATION."
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
