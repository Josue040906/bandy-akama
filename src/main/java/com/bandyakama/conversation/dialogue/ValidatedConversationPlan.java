package com.bandyakama.conversation.dialogue;

import java.util.List;
import java.util.Objects;

/**
 * A structurally validated plan, ordered so proposed parents and prerequisites
 * appear before the operations that refer to them.
 *
 * <p>This type is still not an execution command. It is safe to hand to a
 * transition planner, which must separately enforce transaction and lifecycle
 * rules when applying the plan.</p>
 */
public record ValidatedConversationPlan(
        ConversationPlanProposal source,
        List<ConversationPlanProposal.Operation> orderedOperations
) {
    public ValidatedConversationPlan {
        source = Objects.requireNonNull(source, "source must not be null.");
        orderedOperations = List.copyOf(Objects.requireNonNull(
                orderedOperations, "orderedOperations must not be null."
        ));
        if (orderedOperations.size() != source.operations().size()
                || !orderedOperations.containsAll(source.operations())) {
            throw new IllegalArgumentException(
                    "The ordered plan must contain every source operation exactly once."
            );
        }
    }
}
