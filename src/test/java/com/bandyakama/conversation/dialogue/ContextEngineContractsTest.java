package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextEngineContractsTest {
    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");

    @Test
    void acceptsChronologicalHistoryAndCopiesItDefensively() {
        List<DialogueMessage> history = new ArrayList<>(List.of(
                new DialogueMessage(1, DialogueMessage.Role.USER, "Bonjour"),
                new DialogueMessage(2, DialogueMessage.Role.ASSISTANT, "Bonjour !")
        ));
        ContextEngineInput input = input(history);
        history.clear();
        assertEquals(2, input.history().size());
        assertThrows(UnsupportedOperationException.class, () -> input.history().clear());
    }

    @Test
    void rejectsHistoryWithDuplicateOrDescendingSequenceNumbers() {
        assertThrows(IllegalArgumentException.class, () -> input(List.of(
                new DialogueMessage(2, DialogueMessage.Role.USER, "Premier"),
                new DialogueMessage(2, DialogueMessage.Role.ASSISTANT, "Réponse")
        )));
        assertThrows(IllegalArgumentException.class, () -> input(List.of(
                new DialogueMessage(3, DialogueMessage.Role.USER, "Premier"),
                new DialogueMessage(1, DialogueMessage.Role.ASSISTANT, "Réponse")
        )));
    }

    @Test
    void rejectsBlankCurrentMessageAndNullDialogueState() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContextEngineInput(" ", DialogueState.empty(), List.of(), ""));
        assertThrows(NullPointerException.class, () ->
                new ContextEngineInput("Bonjour", null, List.of(), ""));
    }

    @Test
    void acceptsAValidatedNewTaskResolution() {
        DialogueObjective objective = objective("task-1", DialogueObjectiveStatus.ACTIVE);
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "task-1", List.of(objective), null
        );
        ContextResolution resolution = new ContextResolution(
                new ContextDecision(
                        DialogueRelation.NEW_TASK, null,
                        "Le message exprime un objectif indépendant.", null
                ),
                InterpretationConfidence.HIGH,
                state
        );
        assertEquals("task-1", resolution.proposedState().activeObjectiveId());
    }

    @Test
    void rejectsAResolvedDecisionThatLeavesPendingClarification() {
        DialogueObjective objective = objective("task-1", DialogueObjectiveStatus.ACTIVE);
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "task-1",
                List.of(objective),
                new PendingClarification(
                        "task-1", "Pouvez-vous préciser ?",
                        "La demande est ambiguë.", NOW
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new ContextResolution(
                        new ContextDecision(
                                DialogueRelation.CONTINUATION, "task-1",
                                "Le message poursuit la tâche.", null
                        ),
                        InterpretationConfidence.MEDIUM,
                        state
                )
        );
    }

    @Test
    void requiresPendingClarificationToMatchDecision() {
        DialogueState empty = DialogueState.empty();
        assertThrows(IllegalArgumentException.class, () ->
                new ContextResolution(
                        new ContextDecision(
                                DialogueRelation.NEEDS_CLARIFICATION, null,
                                "Deux objectifs correspondent.",
                                "Quel objectif souhaitez-vous reprendre ?"
                        ),
                        InterpretationConfidence.LOW,
                        empty
                )
        );
    }

    private ContextEngineInput input(List<DialogueMessage> history) {
        return new ContextEngineInput("Et la suite ?", DialogueState.empty(), history, "");
    }

    private DialogueObjective objective(String id, DialogueObjectiveStatus status) {
        return new DialogueObjective(
                id, "Comparer des options",
                "L'utilisateur compare plusieurs options.",
                status, null, NOW, NOW, List.of()
        );
    }
}
