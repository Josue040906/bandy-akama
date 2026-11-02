package com.bandyakama.conversation.dialogue;

import java.util.Objects;

/**
 * Validated result of resolving one incoming message.
 *
 * <p>The proposed state is a value to persist only after the surrounding
 * conversation request completes successfully. Creating this object does
 * not persist or mutate conversation data.</p>
 */
public record ContextResolution(
        ContextDecision decision,
        InterpretationConfidence confidence,
        DialogueState proposedState
) {
    public ContextResolution {
        decision = Objects.requireNonNull(decision, "decision must not be null.");
        confidence = Objects.requireNonNull(confidence, "confidence must not be null.");
        proposedState = Objects.requireNonNull(proposedState, "proposedState must not be null.");

        if (decision.relation() == DialogueRelation.NEEDS_CLARIFICATION) {
            PendingClarification pending = proposedState.pendingClarification();
            if (pending == null) {
                throw new IllegalArgumentException(
                        "A clarification decision requires a pending clarification in the proposed state."
                );
            }
            if (!decision.clarificationQuestion().equals(pending.question())) {
                throw new IllegalArgumentException(
                        "The pending clarification must match the decision question."
                );
            }
            if (!Objects.equals(decision.targetObjectiveId(), pending.targetObjectiveId())) {
                throw new IllegalArgumentException(
                        "The pending clarification target must match the decision target."
                );
            }
        } else if (decision.relation() == DialogueRelation.SOCIAL_TURN) {
            // Social turns intentionally preserve the complete state, including
            // an unresolved clarification; they do not resolve it.
        } else if (decision.relation() == DialogueRelation.NEW_TOPIC_REQUEST) {
            if (proposedState.pendingClarification() != null
                    || proposedState.activeObjectiveId() != null) {
                throw new IllegalArgumentException(
                        "A new-topic request must suspend active work and clear pending clarification."
                );
            }
        } else {
            if (proposedState.pendingClarification() != null) {
                throw new IllegalArgumentException(
                        "A resolved decision must not leave a pending clarification."
                );
            }
            if (decision.relation() == DialogueRelation.NEW_TASK) {
                if (proposedState.activeObjectiveId() == null) {
                    throw new IllegalArgumentException(
                            "A new task must produce an active objective."
                    );
                }
            } else if (decision.relation() != DialogueRelation.NEW_TOPIC_REQUEST) {
                boolean targetExists = false;
                for (DialogueObjective objective : proposedState.objectives()) {
                    if (objective.id().equals(decision.targetObjectiveId())) {
                        targetExists = true;
                        break;
                    }
                }
                if (!targetExists) {
                    throw new IllegalArgumentException(
                            "The decision target must exist in the proposed state."
                    );
                }
            }
        }
    }
}
