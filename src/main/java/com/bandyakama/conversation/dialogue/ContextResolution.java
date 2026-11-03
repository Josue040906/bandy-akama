package com.bandyakama.conversation.dialogue;

import java.util.Objects;

/**
 * Result of preparing one conversation turn.
 *
 * <p>Legacy mono-decision resolutions remain readable during migration.
 * Plan-based resolutions carry the validated multi-operation plan and its
 * proposed immutable state. Neither form persists state.</p>
 */
public record ContextResolution(
        ContextDecision decision,
        InterpretationConfidence confidence,
        DialogueState proposedState,
        ValidatedConversationPlan plan,
        DialogueState originalState
) {
    /** Compatibility constructor for the legacy mono-decision path. */
    public ContextResolution(
            ContextDecision decision,
            InterpretationConfidence confidence,
            DialogueState proposedState
    ) {
        this(decision, confidence, proposedState, null, null);
    }

    public ContextResolution {
        confidence = Objects.requireNonNull(confidence, "confidence must not be null.");
        proposedState = Objects.requireNonNull(
                proposedState, "proposedState must not be null."
        );
        if (decision == null && plan == null) {
            throw new IllegalArgumentException(
                    "A context resolution must carry a decision or a validated plan."
            );
        }
        if (decision != null && plan != null) {
            throw new IllegalArgumentException(
                    "A resolution cannot mix the legacy decision and plan contracts."
            );
        }
        if (decision != null) {
            validateLegacyDecision(decision, proposedState);
        }
    }

    public static ContextResolution fromPlan(
            ValidatedConversationPlan plan,
            DialogueState originalState,
            DialogueState proposedState
    ) {
        Objects.requireNonNull(plan, "plan must not be null.");
        return new ContextResolution(
                null, plan.source().confidence(), proposedState, plan,
                Objects.requireNonNull(originalState, "originalState must not be null.")
        );
    }

    public ContextDecision decision() {
        if (decision == null) {
            throw new IllegalStateException(
                    "This resolution carries a multi-operation plan, not a mono-decision."
            );
        }
        return decision;
    }

    public boolean isPlanBased() {
        return plan != null;
    }

    private static void validateLegacyDecision(ContextDecision decision, DialogueState state) {
        if (decision.relation() == DialogueRelation.NEEDS_CLARIFICATION) {
            PendingClarification pending = state.pendingClarification();
            if (pending == null
                    || !decision.clarificationQuestion().equals(pending.question())
                    || !Objects.equals(decision.targetObjectiveId(), pending.targetObjectiveId())) {
                throw new IllegalArgumentException(
                        "A clarification decision must match a pending clarification."
                );
            }
        } else if (decision.relation() == DialogueRelation.SOCIAL_TURN) {
            // Social turns preserve all objectives and pending clarifications.
        } else if (decision.relation() == DialogueRelation.NEW_TOPIC_REQUEST) {
            if (!state.pendingClarifications().isEmpty() || state.activeObjectiveId() != null) {
                throw new IllegalArgumentException(
                        "A legacy new-topic request must leave no active objective or clarification."
                );
            }
        } else if (decision.relation() == DialogueRelation.TASK_COMPLETION) {
            if (state.activeObjectiveId() != null || !state.pendingClarifications().isEmpty()) {
                throw new IllegalArgumentException(
                        "A legacy completed task must leave no active objective or clarification."
                );
            }
            DialogueObjective target = state.objectives().stream()
                    .filter(item -> item.id().equals(decision.targetObjectiveId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "The completed objective must exist in the proposed state."
                    ));
            if (target.status() != DialogueObjectiveStatus.COMPLETED) {
                throw new IllegalArgumentException(
                        "TASK_COMPLETION must mark its target as COMPLETED."
                );
            }
        } else {
            if (!state.pendingClarifications().isEmpty()) {
                throw new IllegalArgumentException(
                        "A resolved legacy decision must not leave a pending clarification."
                );
            }
            if (decision.relation() == DialogueRelation.NEW_TASK) {
                if (state.activeObjectiveId() == null) {
                    throw new IllegalArgumentException(
                            "A new task must produce an active objective."
                    );
                }
            } else {
                boolean targetExists = state.objectives().stream()
                        .anyMatch(item -> item.id().equals(decision.targetObjectiveId()));
                if (!targetExists) {
                    throw new IllegalArgumentException(
                            "The decision target must exist in the proposed state."
                    );
                }
            }
        }
    }
}
