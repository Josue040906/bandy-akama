package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationPlanValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-03T06:00:00Z");
    private final ConversationPlanValidator validator = new ConversationPlanValidator();

    @Test
    void ordersProposedParentsAndPrerequisitesBeforeTheirDependents() {
        var report = create("report", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                "Rapport", null, List.of());
        var data = create("data", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                "Données", proposed("report"), List.of());
        var synthesis = create("synthesis", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                "Synthèse", proposed("report"), List.of(proposed("data")));

        var result = validator.validate(input(DialogueState.empty()),
                plan(List.of(synthesis, data, report), List.of()));

        List<String> ids = result.orderedOperations().stream()
                .map(ConversationPlanProposal.Operation::operationId).toList();
        assertEquals(List.of("report", "data", "synthesis"), ids);
    }

    @Test
    void rejectsUnknownExistingObjectiveReferences() {
        var resume = targeted(
                "resume", ConversationPlanProposal.OperationType.RESUME_OBJECTIVE,
                "missing", "Reprise"
        );
        assertThrows(IllegalArgumentException.class, () ->
                validator.validate(input(DialogueState.empty()), plan(List.of(resume), List.of())));
    }

    @Test
    void enforcesLifecycleForContinuationResumptionAndCompletion() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE, null);
        DialogueObjective suspended = objective("suspended", DialogueObjectiveStatus.SUSPENDED, null);
        DialogueState state = state("active", List.of(active, suspended), null);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(input(state),
                plan(List.of(targeted("bad-resume",
                        ConversationPlanProposal.OperationType.RESUME_OBJECTIVE,
                        "active", "Reprise")), List.of())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(input(state),
                plan(List.of(targeted("bad-complete",
                        ConversationPlanProposal.OperationType.COMPLETE_OBJECTIVE,
                        "suspended", null)), List.of())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(input(state),
                plan(List.of(targeted("bad-continue",
                        ConversationPlanProposal.OperationType.CONTINUE_OBJECTIVE,
                        "suspended", "Suite")), List.of())));
    }

    @Test
    void acceptsContinuationOfAnActiveObjectiveThatIsNotTheCurrentFocus() {
        DialogueObjective focused = objective(
                "focused", DialogueObjectiveStatus.ACTIVE, null
        );
        DialogueObjective otherActive = objective(
                "other-active", DialogueObjectiveStatus.ACTIVE, null
        );
        var continuation = targeted(
                "continue-other", ConversationPlanProposal.OperationType.CONTINUE_OBJECTIVE,
                "other-active", "Poursuivre cet objectif."
        );

        var result = validator.validate(
                input(state("focused", List.of(focused, otherActive), null)),
                plan(List.of(continuation), List.of())
        );

        assertEquals("continue-other",
                result.orderedOperations().get(0).operationId());
    }

    @Test
    void allowsActivationWhenAllExecutionPrerequisitesAreCompleted() {
        DialogueObjective completed = objective(
                "completed", DialogueObjectiveStatus.COMPLETED, null
        );
        DialogueObjective active = new DialogueObjective(
                "active", "Continuer", "Prêt à démarrer.",
                DialogueObjectiveStatus.ACTIVE, null, NOW, NOW, List.of(),
                List.of("completed")
        );
        DialogueState state = state("active", List.of(completed, active), null);
        var continuation = targeted(
                "continue-active", ConversationPlanProposal.OperationType.CONTINUE_OBJECTIVE,
                "active", "Suite."
        );

        assertEquals(1, validator.validate(
                input(state), plan(List.of(continuation), List.of())
        ).orderedOperations().size());
    }

    @Test
    void rejectsParentCyclesAndDependencyCycles() {
        var firstChild = create("first", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                "Première", proposed("second"), List.of());
        var secondChild = create("second", ConversationPlanProposal.OperationType.CREATE_SUBTASK,
                "Deuxième", proposed("first"), List.of());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input(DialogueState.empty()), plan(List.of(firstChild, secondChild), List.of())
        ));

        var a = create("a", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                "A", null, List.of(proposed("b")));
        var b = create("b", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                "B", null, List.of(proposed("a")));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input(DialogueState.empty()), plan(List.of(a, b), List.of())
        ));
    }

    @Test
    void rejectsDependenciesThatPointToNonTaskOperations() {
        var social = new ConversationPlanProposal.Operation(
                "social", ConversationPlanProposal.OperationType.SOCIAL_TURN,
                null, null, null, null, List.of(), List.of()
        );
        var task = create("task", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                "Une tâche", null, List.of(proposed("social")));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input(DialogueState.empty()), plan(List.of(social, task), List.of())
        ));
    }

    @Test
    void permitsAClarificationAnswerWithoutObjectiveTarget() {
        PendingClarification pending = new PendingClarification(
                null, "Que souhaitez-vous préciser ?", "Intention initiale ambiguë.", NOW
        );
        var answer = new ConversationPlanProposal.Operation(
                "answer", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                null, null, "La précision apportée.", null, List.of(), List.of()
        );

        var result = validator.validate(
                input(state(null, List.of(), pending)), plan(List.of(answer), List.of())
        );

        assertEquals("answer", result.orderedOperations().get(0).operationId());
    }

    @Test
    void allowsClearIndependentOperationAlongsideResolvedPendingClarification() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE, null);
        PendingClarification pending = new PendingClarification(
                "active", "Quel élément ?", "Référence à préciser.", NOW
        );
        var answer = new ConversationPlanProposal.Operation(
                "answer", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                "active", null, "Je parle du premier rapport.", null, List.of(), List.of()
        );
        var independent = create("new", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                "Préparer un planning", null, List.of());

        var result = validator.validate(
                input(state("active", List.of(active), pending)),
                plan(List.of(independent, answer), List.of())
        );

        assertEquals(2, result.orderedOperations().size());
    }

    @Test
    void rejectsAnswerForTheWrongPendingObjective() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE, null);
        PendingClarification pending = new PendingClarification(
                "active", "Quel élément ?", "Référence à préciser.", NOW
        );
        var answer = new ConversationPlanProposal.Operation(
                "answer", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                "other", null, "Réponse", null, List.of(), List.of()
        );
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input(state("active", List.of(active), pending)),
                plan(List.of(answer), List.of())
        ));
    }

    @Test
    void preservesClearOperationsWhenAnotherIntentNeedsClarification() {
        var clear = create("clear", ConversationPlanProposal.OperationType.CREATE_OBJECTIVE,
                "Préparer un résumé", null, List.of());
        var clarification = new ConversationPlanProposal.Clarification(
                "clarify-1", null, "Quel document souhaitez-vous comparer ?",
                "La référence au document est ambiguë."
        );

        var result = validator.validate(
                input(DialogueState.empty()), plan(List.of(clear), List.of(clarification))
        );

        assertEquals(1, result.orderedOperations().size());
        assertEquals(1, result.source().clarifications().size());
    }

    @Test
    void rejectsClarificationTargetingNonObjectiveControlOperation() {
        var social = new ConversationPlanProposal.Operation(
                "social", ConversationPlanProposal.OperationType.SOCIAL_TURN,
                null, null, null, null, List.of(), List.of()
        );
        var clarification = new ConversationPlanProposal.Clarification(
                "clarify-social", proposed("social"),
                "Que voulez-vous dire ?", "La cible n'est pas un objectif."
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input(DialogueState.empty()), plan(List.of(social), List.of(clarification))
        ));
    }

    @Test
    void resolvesOnlyThePendingClarificationIdentifiedByItsId() {
        PendingClarification first = new PendingClarification(
                "clarification-1", null, "Quel sujet ?", "Sujet ambigu.", NOW
        );
        PendingClarification second = new PendingClarification(
                "clarification-2", null, "Quel format ?", "Format ambigu.", NOW.plusSeconds(1)
        );
        DialogueState state = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(),
                List.of(first, second)
        );
        var answer = new ConversationPlanProposal.Operation(
                "answer-format",
                ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                null, null, "Le format PDF.", null, List.of(), List.of(),
                "clarification-2"
        );

        var result = validator.validate(input(state), plan(List.of(answer), List.of()));

        assertEquals("answer-format", result.orderedOperations().get(0).operationId());
    }

    @Test
    void requiresClarificationIdWhenSeveralQuestionsArePending() {
        PendingClarification first = new PendingClarification(
                "clarification-1", null, "Quel sujet ?", "Sujet ambigu.", NOW
        );
        PendingClarification second = new PendingClarification(
                "clarification-2", null, "Quel format ?", "Format ambigu.", NOW.plusSeconds(1)
        );
        DialogueState state = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(),
                List.of(first, second)
        );
        var answerWithoutId = new ConversationPlanProposal.Operation(
                "answer", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                null, null, "Le sujet.", null, List.of(), List.of()
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input(state), plan(List.of(answerWithoutId), List.of())
        ));
    }

    @Test
    void canResolveSeveralClarificationsIndependentlyInOnePlan() {
        PendingClarification first = new PendingClarification(
                "clarification-1", null, "Quel sujet ?", "Sujet ambigu.", NOW
        );
        PendingClarification second = new PendingClarification(
                "clarification-2", null, "Quel format ?", "Format ambigu.", NOW.plusSeconds(1)
        );
        DialogueState state = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(),
                List.of(first, second)
        );
        var answerFirst = new ConversationPlanProposal.Operation(
                "answer-subject", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                null, null, "Le sujet est le rapport.", null, List.of(), List.of(),
                "clarification-1"
        );
        var answerSecond = new ConversationPlanProposal.Operation(
                "answer-format", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                null, null, "Le format est PDF.", null, List.of(), List.of(),
                "clarification-2"
        );

        var result = validator.validate(
                input(state), plan(List.of(answerFirst, answerSecond), List.of())
        );

        assertEquals(2, result.orderedOperations().size());
    }

    @Test
    void rejectsDuplicateAnswersForTheSameClarification() {
        PendingClarification pending = new PendingClarification(
                "clarification-1", null, "Quel sujet ?", "Sujet ambigu.", NOW
        );
        DialogueState state = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(), List.of(pending)
        );
        var first = new ConversationPlanProposal.Operation(
                "answer-1", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                null, null, "Premier sujet.", null, List.of(), List.of(),
                "clarification-1"
        );
        var second = new ConversationPlanProposal.Operation(
                "answer-2", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                null, null, "Autre sujet.", null, List.of(), List.of(),
                "clarification-1"
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                input(state), plan(List.of(first, second), List.of())
        ));
    }

    private ContextEngineInput input(DialogueState state) {
        return new ContextEngineInput("Message courant", 3, state, List.of(), "");
    }

    private DialogueState state(
            String activeId,
            List<DialogueObjective> objectives,
            PendingClarification pending
    ) {
        return new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, activeId, objectives, pending
        );
    }

    private DialogueObjective objective(
            String id, DialogueObjectiveStatus status, String parentId
    ) {
        return new DialogueObjective(
                id, "Objectif " + id, "Résumé", status, parentId,
                NOW, NOW, List.of()
        );
    }

    private ConversationPlanProposal.Operation create(
            String id,
            ConversationPlanProposal.OperationType type,
            String goal,
            ConversationPlanProposal.EntityReference parent,
            List<ConversationPlanProposal.EntityReference> dependencies
    ) {
        return new ConversationPlanProposal.Operation(
                id, type, null, goal, goal + " - résumé", parent, dependencies, List.of()
        );
    }

    private ConversationPlanProposal.Operation targeted(
            String id,
            ConversationPlanProposal.OperationType type,
            String target,
            String summary
    ) {
        return new ConversationPlanProposal.Operation(
                id, type, target, null, summary, null, List.of(), List.of()
        );
    }

    private ConversationPlanProposal.EntityReference proposed(String id) {
        return new ConversationPlanProposal.EntityReference(
                ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION, id
        );
    }

    private ConversationPlanProposal plan(
            List<ConversationPlanProposal.Operation> operations,
            List<ConversationPlanProposal.Clarification> clarifications
    ) {
        return new ConversationPlanProposal(
                "Plan de test", InterpretationConfidence.HIGH, operations, clarifications
        );
    }
}
