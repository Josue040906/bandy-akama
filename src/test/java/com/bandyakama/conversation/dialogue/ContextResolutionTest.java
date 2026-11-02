package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextResolutionTest {
    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");

    @Test
    void acceptsCompletionOnlyWhenTargetIsCompletedAndNoObjectiveRemainsActive() {
        DialogueObjective completed = objective(
                "done", DialogueObjectiveStatus.COMPLETED
        );
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(completed), null
        );
        ContextDecision decision = new ContextDecision(
                DialogueRelation.TASK_COMPLETION, "done", "Clôture.", null
        );

        ContextResolution resolution = new ContextResolution(
                decision, InterpretationConfidence.HIGH, state
        );

        assertEquals("done", resolution.decision().targetObjectiveId());
        assertEquals(null, resolution.proposedState().activeObjectiveId());
    }

    @Test
    void rejectsCompletionThatLeavesItsTargetActive() {
        DialogueObjective active = objective(
                "active", DialogueObjectiveStatus.ACTIVE
        );
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "active", List.of(active), null
        );
        ContextDecision decision = new ContextDecision(
                DialogueRelation.TASK_COMPLETION, "active", "Clôture.", null
        );

        assertThrows(IllegalArgumentException.class, () ->
                new ContextResolution(decision, InterpretationConfidence.HIGH, state)
        );
    }

    private DialogueObjective objective(String id, DialogueObjectiveStatus status) {
        return new DialogueObjective(
                id, "Objectif " + id, "Résumé.", status, null,
                NOW, NOW, List.of()
        );
    }
}
