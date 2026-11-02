package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DialogueStateTransitionerTest {
    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");
    private final DialogueStateTransitioner transitioner =
            new DialogueStateTransitioner(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void newTaskSuspendsPreviousActiveObjectiveAndCreatesNewOne() {
        DialogueObjective active = objective("old", DialogueObjectiveStatus.ACTIVE);
        DialogueState state = state("old", active);
        ContextEngineInput input = input(state);
        ContextInterpretationProposal proposal = proposal(
                DialogueRelation.NEW_TASK, null, "Apprendre Python",
                "L'utilisateur veut apprendre Python."
        );
        ContextDecision decision = new ContextDecision(
                DialogueRelation.NEW_TASK, null, "Nouvel objectif.", null
        );

        DialogueState next = transitioner.transition(input, proposal, decision);

        assertNotEquals("old", next.activeObjectiveId());
        assertEquals(DialogueObjectiveStatus.SUSPENDED, find(next, "old").status());
        assertEquals("Apprendre Python", find(next, next.activeObjectiveId()).goal());
    }

    @Test
    void resumptionActivatesSuspendedTargetAndSuspendsCurrentObjective() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueObjective suspended = objective("suspended", DialogueObjectiveStatus.SUSPENDED);
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "active",
                List.of(active, suspended),
                null
        );
        ContextEngineInput input = input(state);
        ContextInterpretationProposal proposal = proposal(
                DialogueRelation.TOPIC_RESUMPTION, "suspended",
                "Reprise de l'objectif suspendu."
        );
        ContextDecision decision = new ContextDecision(
                DialogueRelation.TOPIC_RESUMPTION, "suspended", "Reprise explicite.", null
        );

        DialogueState next = transitioner.transition(input, proposal, decision);

        assertEquals("suspended", next.activeObjectiveId());
        assertEquals(DialogueObjectiveStatus.SUSPENDED, find(next, "active").status());
        assertEquals(DialogueObjectiveStatus.ACTIVE, find(next, "suspended").status());
    }

    @Test
    void explicitCompletionMarksOnlyActiveObjectiveCompletedAndClearsActivePointer() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueObjective suspended = objective("suspended", DialogueObjectiveStatus.SUSPENDED);
        PendingClarification pending = new PendingClarification(
                "active", "Quel élément ?", "Référence ambiguë.", NOW
        );
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "active",
                List.of(active, suspended),
                pending
        );
        ContextEngineInput input = input(state);
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.TASK_COMPLETION,
                "active",
                "L'utilisateur termine explicitement cet objectif.",
                null,
                InterpretationConfidence.HIGH,
                null,
                null,
                List.of()
        );
        ContextDecision decision = new ContextDecision(
                DialogueRelation.TASK_COMPLETION,
                "active",
                "Clôture explicite.",
                null
        );

        DialogueState next = transitioner.transition(input, proposal, decision);

        assertEquals(null, next.activeObjectiveId());
        assertEquals(DialogueObjectiveStatus.COMPLETED, find(next, "active").status());
        assertEquals(DialogueObjectiveStatus.SUSPENDED, find(next, "suspended").status());
        assertEquals(null, next.pendingClarification());
    }

    @Test
    void clarificationDoesNotMutateExistingObjectives() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueState state = state("active", active);
        ContextEngineInput input = input(state);
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.NEEDS_CLARIFICATION, null, "Référence ambiguë.",
                "De quel élément parlez-vous ?", InterpretationConfidence.LOW,
                null, null, List.of()
        );
        ContextDecision decision = new ContextDecision(
                DialogueRelation.NEEDS_CLARIFICATION, null, "Référence ambiguë.",
                "De quel élément parlez-vous ?"
        );

        DialogueState next = transitioner.transition(input, proposal, decision);

        assertEquals(state.objectives(), next.objectives());
        assertEquals(state.activeObjectiveId(), next.activeObjectiveId());
        assertEquals("De quel élément parlez-vous ?",
                next.pendingClarification().question());
    }

    @Test
    void socialTurnPreservesObjectivesAndPendingClarificationExactly() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        PendingClarification pending = new PendingClarification(
                "active", "Quel élément ?", "Référence ambiguë.", NOW
        );
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "active", List.of(active), pending
        );
        ContextEngineInput input = input(state);
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.SOCIAL_TURN, null, "Salutation.", null,
                InterpretationConfidence.HIGH, null, null, List.of()
        );
        ContextDecision decision = new ContextDecision(
                DialogueRelation.SOCIAL_TURN, null, "Salutation.", null
        );

        DialogueState next = transitioner.transition(input, proposal, decision);

        assertEquals(state, next);
    }

    @Test
    void newTopicRequestSuspendsActiveObjectiveAndClearsPendingClarification() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "active", List.of(active),
                new PendingClarification("active", "Quel élément ?", "Ambigu.", NOW)
        );
        ContextEngineInput input = input(state);
        ContextInterpretationProposal proposal = new ContextInterpretationProposal(
                DialogueRelation.NEW_TOPIC_REQUEST, null, "Nouveau sujet.", null,
                InterpretationConfidence.HIGH, null, null, List.of()
        );
        ContextDecision decision = new ContextDecision(
                DialogueRelation.NEW_TOPIC_REQUEST, null, "Nouveau sujet.", null
        );

        DialogueState next = transitioner.transition(input, proposal, decision);

        assertEquals(null, next.activeObjectiveId());
        assertEquals(DialogueObjectiveStatus.SUSPENDED, next.objectives().get(0).status());
        assertEquals(null, next.pendingClarification());
    }

    private ContextEngineInput input(DialogueState state) {
        return new ContextEngineInput("Message courant", 2, state, List.of(), "");
    }

    private DialogueState state(String activeId, DialogueObjective objective) {
        return new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, activeId, List.of(objective), null
        );
    }

    private DialogueObjective objective(String id, DialogueObjectiveStatus status) {
        return new DialogueObjective(
                id, "Objectif " + id, "Résumé initial.", status, null,
                NOW, NOW, List.of()
        );
    }

    private DialogueObjective find(DialogueState state, String id) {
        return state.objectives().stream()
                .filter(objective -> objective.id().equals(id))
                .findFirst().orElseThrow();
    }

    private ContextInterpretationProposal proposal(
            DialogueRelation relation, String target, String summary
    ) {
        return proposal(relation, target, null, summary);
    }

    private ContextInterpretationProposal proposal(
            DialogueRelation relation, String target, String goal, String summary
    ) {
        return new ContextInterpretationProposal(
                relation, target, "Interprétation test.", null,
                InterpretationConfidence.HIGH, goal, summary, List.of()
        );
    }
}
