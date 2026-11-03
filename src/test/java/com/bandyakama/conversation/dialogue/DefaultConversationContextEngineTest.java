package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DefaultConversationContextEngineTest {
    private final ConversationPlanInterpreter interpreter =
            mock(ConversationPlanInterpreter.class);
    private final DefaultConversationContextEngine engine =
            new DefaultConversationContextEngine(
                    interpreter,
                    new ConversationPlanValidator(),
                    new ConversationPlanTransitioner()
            );

    @Test
    void greetingUsesTheSemanticPlannerAndPreservesTheCompleteState() {
        Instant now = Instant.parse("2026-11-02T08:00:00Z");
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE, now);
        PendingClarification pending = new PendingClarification(
                "clarification-1", "active", "Quel élément ?", "Référence ambiguë.", now
        );
        DialogueState state = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, "active",
                List.of(active), List.of(pending)
        );
        ConversationPlanProposal proposal = plan(List.of(operation(
                "social", ConversationPlanProposal.OperationType.SOCIAL_TURN,
                null, null, null, null
        )), List.of());
        org.mockito.Mockito.when(interpreter.interpret(org.mockito.ArgumentMatchers.any()))
                .thenReturn(proposal);

        ContextResolution resolution = engine.resolve(input("Bonjour Bandy !", state));

        assertEquals(true, resolution.isPlanBased());
        assertEquals(state, resolution.proposedState());
        assertEquals(proposal, resolution.plan().source());
        verify(interpreter).interpret(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void newTopicRequestSuspendsOnlyFocusedObjectiveAndRetainsPendingClarification() {
        Instant now = Instant.parse("2026-11-02T08:00:00Z");
        DialogueObjective focused = objective("focused", DialogueObjectiveStatus.ACTIVE, now);
        DialogueObjective other = objective("other", DialogueObjectiveStatus.ACTIVE, now);
        PendingClarification pending = new PendingClarification(
                "clarification-1", "focused", "Quel élément ?", "Référence ambiguë.", now
        );
        DialogueState state = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, "focused",
                List.of(focused, other), List.of(pending)
        );
        ConversationPlanProposal proposal = plan(List.of(operation(
                "new-topic", ConversationPlanProposal.OperationType.NEW_TOPIC_REQUEST,
                null, null, null, null
        )), List.of());
        org.mockito.Mockito.when(interpreter.interpret(org.mockito.ArgumentMatchers.any()))
                .thenReturn(proposal);

        ContextResolution resolution = engine.resolve(input("Changeons de sujet.", state));

        assertEquals(null, resolution.proposedState().activeObjectiveId());
        assertEquals(DialogueObjectiveStatus.SUSPENDED,
                find(resolution.proposedState(), "focused").status());
        assertEquals(DialogueObjectiveStatus.ACTIVE,
                find(resolution.proposedState(), "other").status());
        assertEquals(List.of(pending), resolution.proposedState().pendingClarifications());
    }

    private ContextEngineInput input(String message, DialogueState state) {
        return new ContextEngineInput(message, 2, state, List.of(), "");
    }

    private DialogueObjective find(DialogueState state, String id) {
        return state.objectives().stream().filter(item -> item.id().equals(id))
                .findFirst().orElseThrow();
    }

    private DialogueObjective objective(String id, DialogueObjectiveStatus status, Instant now) {
        return new DialogueObjective(id, "Objectif " + id, "Résumé", status,
                null, now, now, List.of());
    }

    private ConversationPlanProposal.Operation operation(
            String id, ConversationPlanProposal.OperationType type,
            String target, String goal, String summary,
            ConversationPlanProposal.EntityReference parent) {
        return new ConversationPlanProposal.Operation(
                id, type, target, goal, summary, parent, List.of(), List.of()
        );
    }

    private ConversationPlanProposal plan(
            List<ConversationPlanProposal.Operation> operations,
            List<ConversationPlanProposal.Clarification> clarifications) {
        return new ConversationPlanProposal(
                "Plan sémantique de test", InterpretationConfidence.HIGH,
                operations, clarifications
        );
    }
}
