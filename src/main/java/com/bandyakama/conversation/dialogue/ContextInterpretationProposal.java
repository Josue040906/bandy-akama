package com.bandyakama.conversation.dialogue;

import java.util.List;
import java.util.Objects;

/**
 * Untrusted semantic interpretation proposed by the language model.
 *
 * <p>The proposal describes what the model believes the user means. It must
 * be validated against the supplied dialogue state before any transition is
 * applied.</p>
 */
public record ContextInterpretationProposal(
        DialogueRelation relation,
        String targetObjectiveId,
        String rationale,
        String clarificationQuestion,
        InterpretationConfidence confidence,
        String objectiveGoal,
        String objectiveSummary,
        List<ProposedDialogueReference> referenceMentions
) {
    public ContextInterpretationProposal {
        relation = Objects.requireNonNull(relation, "relation must not be null.");
        rationale = requireText(rationale, "rationale");
        confidence = Objects.requireNonNull(confidence, "confidence must not be null.");
        referenceMentions = List.copyOf(Objects.requireNonNull(
                referenceMentions, "referenceMentions must not be null."
        ));

        boolean requiresTarget = switch (relation) {
            case CONTINUATION, TOPIC_RESUMPTION, CORRECTION, USER_CLARIFICATION,
                    TASK_COMPLETION -> true;
            case NEW_TASK, NEW_TOPIC_REQUEST, SOCIAL_TURN, NEEDS_CLARIFICATION -> false;
        };
        if (requiresTarget && (targetObjectiveId == null || targetObjectiveId.isBlank())) {
            throw new IllegalArgumentException(relation + " requires a target objective.");
        }
        if (targetObjectiveId != null && targetObjectiveId.isBlank()) {
            throw new IllegalArgumentException("targetObjectiveId must be null or non-blank.");
        }

        if (relation == DialogueRelation.TASK_COMPLETION) {
            if (clarificationQuestion != null || objectiveGoal != null
                    || objectiveSummary != null || !referenceMentions.isEmpty()) {
                throw new IllegalArgumentException(
                        "TASK_COMPLETION must not propose objective updates or references."
                );
            }
        } else if (relation == DialogueRelation.SOCIAL_TURN
                || relation == DialogueRelation.NEW_TOPIC_REQUEST) {
            if (targetObjectiveId != null || clarificationQuestion != null
                    || objectiveGoal != null || objectiveSummary != null
                    || !referenceMentions.isEmpty()) {
                throw new IllegalArgumentException(
                        relation + " must not propose objective or clarification data."
                );
            }
        } else if (relation == DialogueRelation.NEEDS_CLARIFICATION) {
            clarificationQuestion = requireText(clarificationQuestion, "clarificationQuestion");
            if (objectiveGoal != null || objectiveSummary != null
                    || !referenceMentions.isEmpty()) {
                throw new IllegalArgumentException(
                        "A clarification proposal must not propose an objective update."
                );
            }
        } else {
            if (clarificationQuestion != null) {
                throw new IllegalArgumentException(
                        "clarificationQuestion is only valid for NEEDS_CLARIFICATION."
                );
            }
            objectiveSummary = requireText(objectiveSummary, "objectiveSummary");
            if ((relation == DialogueRelation.NEW_TASK || relation == DialogueRelation.CORRECTION)
                    && (objectiveGoal == null || objectiveGoal.isBlank())) {
                throw new IllegalArgumentException(
                        relation + " requires an objectiveGoal."
                );
            }
            if (objectiveGoal != null && objectiveGoal.isBlank()) {
                throw new IllegalArgumentException("objectiveGoal must be null or non-blank.");
            }
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
