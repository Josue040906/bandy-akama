package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationPlanProposalTest {

    @Test
    void acceptsSeveralIndependentObjectivesAndNestedSubtasks() {
        var report = operation("report", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                null, "Préparer un rapport", "Rapport de ventes", null, List.of());
        var compare = operation("compare", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                null, "Comparer avec l'année précédente", "Comparaison annuelle",
                proposed("report"), List.of());
        var travel = operation("travel", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                null, "Préparer un voyage", "Plan de voyage", null, List.of());

        assertDoesNotThrow(() -> plan(List.of(report, compare, travel), List.of()));
    }

    @Test
    void distinguishesHierarchyFromExecutionDependencies() {
        var report = operation("report", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                null, "Préparer un rapport", "Rapport", null, List.of());
        var data = operation("data", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                null, "Collecter les données", "Données régionales",
                proposed("report"), List.of());
        var synthesis = operation("synthesis", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                null, "Rédiger la synthèse", "Synthèse",
                proposed("report"), List.of(proposed("data")));

        assertDoesNotThrow(() -> plan(List.of(report, data, synthesis), List.of()));
    }

    @Test
    void rejectsUnknownProposedOperationReferences() {
        var task = operation("task", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                null, "Faire une tâche", "Résumé", proposed("missing"), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> plan(List.of(task), List.of()));
    }

    @Test
    void rejectsSelfReferencesAndDuplicateOperationIds() {
        var self = operation("self", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                null, "Faire une tâche", "Résumé", proposed("self"), List.of());
        assertThrows(IllegalArgumentException.class,
                () -> plan(List.of(self), List.of()));

        var first = operation("same", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                null, "A", "A", null, List.of());
        var second = operation("same", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                null, "B", "B", null, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> plan(List.of(first, second), List.of()));
    }

    @Test
    void acceptsLocalizedClarificationAlongsideIndependentClearOperation() {
        var clear = operation("clear", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                null, "Créer un résumé", "Résumé", null, List.of());
        var clarification = new ConversationPlanProposal.Clarification(
                "clarification-1", null, "De quel élément parlez-vous ?",
                "La référence ne peut pas être résolue."
        );

        assertDoesNotThrow(() -> plan(List.of(clear), List.of(clarification)));
    }

    @Test
    void validatesOperationPayloadByType() {
        assertThrows(IllegalArgumentException.class, () ->
                new ConversationPlanProposal.Operation(
                        "complete", ConversationPlanProposal.OperationType.COMPLETE_OBJECTIVE,
                        "objective-1", null, "Résumé interdit", null, List.of(), List.of()
                ));
        assertThrows(IllegalArgumentException.class, () ->
                new ConversationPlanProposal.Operation(
                        "new", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                        "objective-1", "But", "Résumé", null, List.of(), List.of()
                ));
    }

    private ConversationPlanProposal plan(
            List<ConversationPlanProposal.Operation> operations,
            List<ConversationPlanProposal.Clarification> clarifications
    ) {
        return new ConversationPlanProposal(
                "Interprétation multi-intention.", InterpretationConfidence.HIGH,
                operations, clarifications
        );
    }

    private ConversationPlanProposal.Operation operation(
            String id,
            ConversationPlanProposal.OperationType type,
            String target,
            String goal,
            String summary,
            ConversationPlanProposal.EntityReference parent,
            List<ConversationPlanProposal.EntityReference> dependencies
    ) {
        return new ConversationPlanProposal.Operation(
                id, type, target, goal, summary, parent, dependencies, List.of()
        );
    }

    private ConversationPlanProposal.EntityReference proposed(String id) {
        return new ConversationPlanProposal.EntityReference(
                ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION, id
        );
    }
}
