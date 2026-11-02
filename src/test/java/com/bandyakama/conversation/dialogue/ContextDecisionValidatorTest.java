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
    void acceptsCompletionOnlyForTheCurrentlyActiveObjective() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueObjective suspended = objective("suspended", DialogueObjectiveStatus.SUSPENDED);
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "active",
                List.of(active, suspended),
                null
        );
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.TASK_COMPLETION,
                "active",
                "Clôture explicite.",
                null,
                InterpretationConfidence.HIGH,
                null,
                null,
                List.of()
        );

        ContextDecision decision = validator.validate(input(state), proposal);

        assertEquals(DialogueRelation.TASK_COMPLETION, decision.relation());
        assertEquals("active", decision.targetObjectiveId());
    }

    @Test
    void rejectsCompletionOfSuspendedObjective() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueObjective suspended = objective("suspended", DialogueObjectiveStatus.SUSPENDED);
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "active",
                List.of(active, suspended),
                null
        );
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.TASK_COMPLETION,
                "suspended",
                "Clôture explicite.",
                null,
                InterpretationConfidence.HIGH,
                null,
                null,
                List.of()
        );

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(input(state), proposal));
    }

    @Test
    void rejectsClarificationThatTargetsUnknownObjective() {
        DialogueState state = DialogueState.empty();
        ContextEngineInput input = input(state);
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.NEEDS_CLARIFICATION, "missing-id",
                "Référence non résolue.", "De quel objectif parlez-vous ?",
                InterpretationConfidence.LOW, null, null, List.of()
        );
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(input, proposal));
    }

    @Test
    void rejectsClarificationThatTargetsCompletedObjective() {
        DialogueObjective completed = objective(
                "completed", DialogueObjectiveStatus.COMPLETED
        );
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(completed), null
        );
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.NEEDS_CLARIFICATION, "completed",
                "Référence non résolue.", "De quel élément parlez-vous ?",
                InterpretationConfidence.LOW, null, null, List.of()
        );
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(input(state), proposal));
    }

    @Test
    void socialTurnMayPreserveAnExistingPendingClarification() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "active",
                List.of(active),
                new PendingClarification(
                        "active", "Quel élément ?", "Référence ambiguë.", NOW
                )
        );
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.SOCIAL_TURN, null, "Salutation.", null,
                InterpretationConfidence.HIGH, null, null, List.of()
        );

        ContextDecision decision = validator.validate(input(state), proposal);

        assertEquals(DialogueRelation.SOCIAL_TURN, decision.relation());
        assertEquals(null, decision.targetObjectiveId());
    }

    @Test
    void newTopicRequestIsAcceptedWithoutCreatingAnArtificialObjective() {
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.NEW_TOPIC_REQUEST, null, "Demande de nouveau sujet.",
                null, InterpretationConfidence.HIGH, null, null, List.of()
        );

        ContextDecision decision = validator.validate(
                input(DialogueState.empty()), proposal
        );

        assertEquals(DialogueRelation.NEW_TOPIC_REQUEST, decision.relation());
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
