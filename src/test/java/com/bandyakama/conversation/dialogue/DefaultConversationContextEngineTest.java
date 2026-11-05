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
        assertEquals(state, resolution.originalState());
        assertEquals(state, resolution.proposedState());
        assertEquals(proposal, resolution.plan().source());
        verify(interpreter).interpret(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lowConfidenceDoesNotApplyAnUncertainObjectiveMutation() {
        ConversationPlanProposal proposal = new ConversationPlanProposal(
                "La demande reste incertaine.", InterpretationConfidence.LOW,
                List.of(operation(
                        "create", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                        null, "Préparer un rapport", "Rapport en préparation", null
                )), List.of()
        );
        org.mockito.Mockito.when(interpreter.interpret(org.mockito.ArgumentMatchers.any()))
                .thenReturn(proposal);

        ContextResolution resolution = engine.resolve(
                input("Prépare le rapport demandé.", DialogueState.empty())
        );

        assertEquals(List.of(), resolution.proposedState().objectives());
        assertEquals(1, resolution.proposedState().pendingClarifications().size());
        assertEquals("low-confidence",
                resolution.proposedState().pendingClarifications().get(0).clarificationId());
        assertEquals(List.of(), resolution.plan().orderedOperations());
    }

    @Test
    void mediumConfidencePreservesClearIntentBesideLocalizedAmbiguity() {
        ConversationPlanProposal proposal = new ConversationPlanProposal(
                "Une intention est claire, une autre nécessite une précision.",
                InterpretationConfidence.MEDIUM,
                List.of(operation(
                        "create", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                        null, "Préparer un planning", "Planning à préparer", null
                )),
                List.of(new ConversationPlanProposal.Clarification(
                        "clarify", null, "Quel document souhaitez-vous comparer ?",
                        "La référence au document est ambiguë."
                ))
        );
        org.mockito.Mockito.when(interpreter.interpret(org.mockito.ArgumentMatchers.any()))
                .thenReturn(proposal);

        ContextResolution resolution = engine.resolve(
                input("Prépare un planning et compare-le à celui-ci.", DialogueState.empty())
        );

        assertEquals(1, resolution.proposedState().objectives().size());
        assertEquals(1, resolution.proposedState().pendingClarifications().size());
        assertEquals(1, resolution.plan().orderedOperations().size());
    }

    @Test
    void lowConfidenceSocialTurnDoesNotCreateAnUnnecessaryClarification() {
        ConversationPlanProposal proposal = new ConversationPlanProposal(
                "Échange social.", InterpretationConfidence.LOW,
                List.of(operation(
                        "social", ConversationPlanProposal.OperationType.SOCIAL_TURN,
                        null, null, null, null
                )), List.of()
        );
        org.mockito.Mockito.when(interpreter.interpret(org.mockito.ArgumentMatchers.any()))
                .thenReturn(proposal);
        DialogueState state = DialogueState.empty();

        ContextResolution resolution = engine.resolve(input("Bonjour.", state));

        assertEquals(state, resolution.proposedState());
        assertEquals(List.of(), resolution.proposedState().pendingClarifications());
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
