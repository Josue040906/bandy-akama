package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextDecisionValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");
    private final ContextDecisionValidator validator = new ContextDecisionValidator();

    @Test
    void rejectsContinuationThatTargetsSuspendedObjective() {
        DialogueObjective suspended = objective("old", DialogueObjectiveStatus.SUSPENDED);
        ContextEngineInput input = input(new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(suspended), null
        ));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input,
                proposal(DialogueRelation.CONTINUATION, "old",
                        InterpretationConfidence.HIGH, "Poursuite")
        ));
    }

    @Test
    void rejectsResumptionOfAnObjectiveThatIsNotSuspended() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        ContextEngineInput input = input(new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "active", List.of(active), null
        ));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input,
                proposal(DialogueRelation.TOPIC_RESUMPTION, "active",
                        InterpretationConfidence.HIGH, "Reprise")
        ));
    }

    @Test
    void lowConfidenceIsConvertedToClarificationWithoutChangingTarget() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        ContextEngineInput input = input(new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "active", List.of(active), null
        ));
        ContextDecision decision = validator.validate(
                input,
                proposal(DialogueRelation.CONTINUATION, "active",
                        InterpretationConfidence.LOW, "Peut-être la suite")
        );
        assertEquals(DialogueRelation.NEEDS_CLARIFICATION, decision.relation());
        assertEquals(null, decision.targetObjectiveId());
        assertEquals(0, input.dialogueState().pendingClarification() == null ? 0 : 1);
    }

    @Test
    void doesNotAllowAContinuationToBypassPendingClarification() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "active",
                List.of(active),
                new PendingClarification(
                        "active", "Quel élément ?", "Référence ambiguë.", NOW
                )
        );
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input(state),
                proposal(DialogueRelation.CONTINUATION, "active",
                        InterpretationConfidence.HIGH, "Suite")
        ));
    }

    private ContextEngineInput input(DialogueState state) {
        return new ContextEngineInput("Message de test", 2, state, List.of(), "");
    }

    private ContextInterpretationProposal proposal(
            DialogueRelation relation,
            String target,
            InterpretationConfidence confidence,
            String summary
    ) {
        return new ContextInterpretationProposal(
                relation, target, "Justification test.", null, confidence,
                null, summary, List.of()
        );
    }

    private DialogueObjective objective(String id, DialogueObjectiveStatus status) {
        return new DialogueObjective(
                id, "Objectif " + id, "Résumé initial.", status, null,
                NOW, NOW, List.of()
        );
    }
}
