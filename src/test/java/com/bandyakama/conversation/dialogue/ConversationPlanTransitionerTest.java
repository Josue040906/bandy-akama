package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConversationPlanTransitionerTest {
    private static final Instant NOW = Instant.parse("2026-11-03T08:00:00Z");
    private final ConversationPlanValidator validator = new ConversationPlanValidator();
    private final ConversationPlanTransitioner transitioner =
            new ConversationPlanTransitioner(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsSeveralIndependentObjectivesWithoutSuspendingEarlierOnes() {
        var first = create("first", "Préparer le rapport", null, List.of());
        var second = create("second", "Planifier le voyage", null, List.of());
        ContextEngineInput input = input(DialogueState.empty());

        DialogueState result = apply(input, plan(List.of(first, second), List.of()));

        assertEquals(2, result.objectives().size());
        assertEquals(2, result.objectives().stream()
                .filter(item -> item.status() == DialogueObjectiveStatus.ACTIVE).count());
        assertNotEquals(result.objectives().get(0).id(), result.objectives().get(1).id());
        assertEquals(result.objectives().get(1).id(), result.activeObjectiveId());
    }

    @Test
    void blocksDependentCreationAndUnblocksItWhenPrerequisiteCompletes() {
        var prerequisite = create("prepare", "Préparer les données", null, List.of());
        var dependent = create("analyze", "Analyser les données", null, List.of(proposed("prepare")));
        DialogueState created = apply(input(DialogueState.empty()),
                plan(List.of(prerequisite, dependent), List.of()));

        DialogueObjective first = byGoal(created, "Préparer les données");
        DialogueObjective second = byGoal(created, "Analyser les données");
        assertEquals(DialogueObjectiveStatus.ACTIVE, first.status());
        assertEquals(DialogueObjectiveStatus.BLOCKED, second.status());

        var completion = new ConversationPlanProposal.Operation(
                "complete-prepare", ConversationPlanProposal.OperationType.COMPLETE_OBJECTIVE,
                first.id(), null, null, null, List.of(), List.of()
        );
        DialogueState completed = apply(input(created),
                plan(List.of(completion), List.of()));

        assertEquals(DialogueObjectiveStatus.COMPLETED,
                completed.objectives().stream().filter(item -> item.id().equals(first.id()))
                        .findFirst().orElseThrow().status());
        assertEquals(DialogueObjectiveStatus.ACTIVE,
                completed.objectives().stream().filter(item -> item.id().equals(second.id()))
                        .findFirst().orElseThrow().status());
        assertEquals(second.id(), completed.activeObjectiveId());
    }

    @Test
    void appliesCorrectionAndResumptionToSeparateObjectivesInOneAtomicPlan() {
        DialogueObjective active = objective("active", DialogueObjectiveStatus.ACTIVE);
        DialogueObjective suspended = objective("suspended", DialogueObjectiveStatus.SUSPENDED);
        DialogueState initial = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "active",
                List.of(active, suspended), (PendingClarification) null
        );
        var correction = new ConversationPlanProposal.Operation(
                "correct-active", ConversationPlanProposal.OperationType.CORRECT_OBJECTIVE,
                "active", "Préparer un rapport financier", "Le rapport porte sur l'exercice 2026.",
                null, List.of(), List.of()
        );
        var resume = new ConversationPlanProposal.Operation(
                "resume-suspended", ConversationPlanProposal.OperationType.RESUME_OBJECTIVE,
                "suspended", null, "Reprise de la préparation du voyage.",
                null, List.of(), List.of()
        );

        DialogueState result = apply(input(initial), plan(List.of(correction, resume), List.of()));

        assertEquals("Préparer un rapport financier", byId(result, "active").goal());
        assertEquals(DialogueObjectiveStatus.ACTIVE, byId(result, "active").status());
        assertEquals("Reprise de la préparation du voyage.", byId(result, "suspended").summary());
        assertEquals(DialogueObjectiveStatus.ACTIVE, byId(result, "suspended").status());
        assertEquals("suspended", result.activeObjectiveId());
    }

    @Test
    void answeringOneClarificationPreservesEveryOtherPendingQuestion() {
        PendingClarification first = new PendingClarification(
                "clarify-topic", null, "Quel sujet ?", "Sujet ambigu.", NOW
        );
        PendingClarification second = new PendingClarification(
                "clarify-format", null, "Quel format ?", "Format ambigu.", NOW
        );
        DialogueState initial = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(), List.of(first, second)
        );
        var answer = new ConversationPlanProposal.Operation(
                "answer-format", ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION,
                null, null, "Le format PDF.", null, List.of(), List.of(), "clarify-format"
        );

        DialogueState result = apply(input(initial), plan(List.of(answer), List.of()));

        assertEquals(List.of(first), result.pendingClarifications());
    }

    @Test
    void appendsNewClarificationWithoutDiscardingExistingOnes() {
        PendingClarification existing = new PendingClarification(
                "existing", null, "Quel sujet ?", "Sujet ambigu.", NOW
        );
        DialogueState initial = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, null, List.of(), List.of(existing)
        );
        var created = create("new-objective", "Préparer un calendrier", null, List.of());
        var clarification = new ConversationPlanProposal.Clarification(
                "new-clarification", proposed("new-objective"),
                "Quel calendrier ?", "La portée temporelle manque."
        );

        DialogueState result = apply(input(initial),
                plan(List.of(created), List.of(clarification)));

        assertEquals(2, result.pendingClarifications().size());
        assertEquals("existing", result.pendingClarifications().get(0).clarificationId());
        String createdId = byGoal(result, "Préparer un calendrier").id();
        assertEquals(createdId, result.pendingClarifications().get(1).targetObjectiveId());
    }

    @Test
    void ambiguousHistoricalReferenceCreatesClarificationWithoutSelectingAnItem() {
        DialogueReference first = new DialogueReference(
                "first-version", "Version initiale", "DOCUMENT", 1
        );
        DialogueReference second = new DialogueReference(
                "second-version", "Version révisée", "DOCUMENT", 8
        );
        DialogueObjective objective = new DialogueObjective(
                "documents", "Comparer des versions", "Deux versions disponibles.",
                DialogueObjectiveStatus.ACTIVE, null, NOW, NOW, List.of(first, second)
        );
        DialogueState initial = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "documents", List.of(objective),
                (PendingClarification) null
        );
        var clarification = new ConversationPlanProposal.Clarification(
                "which-version", null,
                "De quelle version parlez-vous : initiale ou révisée ?",
                "L'expression « l'autre version » admet deux antécédents plausibles."
        );

        DialogueState result = apply(input(initial), plan(List.of(), List.of(clarification)));

        assertEquals(initial.objectives(), result.objectives());
        assertEquals(1, result.pendingClarifications().size());
        assertEquals("which-version", result.pendingClarifications().get(0).clarificationId());
    }

    @Test
    void newTopicRequestSuspendsOnlyTheFocusedObjectiveAndPreservesClarifications() {
        DialogueObjective focused = objective("focused", DialogueObjectiveStatus.ACTIVE);
        DialogueObjective other = objective("other", DialogueObjectiveStatus.ACTIVE);
        PendingClarification pending = new PendingClarification(
                "pending", "focused", "Quel élément ?", "Ambigu.", NOW
        );
        DialogueState initial = DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION, "focused",
                List.of(focused, other), List.of(pending)
        );
        var newTopic = new ConversationPlanProposal.Operation(
                "new-topic", ConversationPlanProposal.OperationType.NEW_TOPIC_REQUEST,
                null, null, null, null, List.of(), List.of()
        );

        DialogueState result = apply(input(initial), plan(List.of(newTopic), List.of()));

        assertNull(result.activeObjectiveId());
        assertEquals(DialogueObjectiveStatus.SUSPENDED,
                result.objectives().stream().filter(item -> item.id().equals("focused"))
                        .findFirst().orElseThrow().status());
        assertEquals(DialogueObjectiveStatus.ACTIVE,
                result.objectives().stream().filter(item -> item.id().equals("other"))
                        .findFirst().orElseThrow().status());
        assertEquals(List.of(pending), result.pendingClarifications());
    }

    private DialogueState apply(ContextEngineInput input, ConversationPlanProposal proposal) {
        ValidatedConversationPlan validated = validator.validate(input, proposal);
        return transitioner.transition(input, validated);
    }

    private ContextEngineInput input(DialogueState state) {
        return new ContextEngineInput("Message de test", 4, state, List.of(), "");
    }

    private DialogueObjective byId(DialogueState state, String id) {
        return state.objectives().stream().filter(item -> item.id().equals(id))
                .findFirst().orElseThrow();
    }

    private DialogueObjective byGoal(DialogueState state, String goal) {
        return state.objectives().stream().filter(item -> item.goal().equals(goal))
                .findFirst().orElseThrow();
    }

    private DialogueObjective objective(String id, DialogueObjectiveStatus status) {
        return new DialogueObjective(id, "Objectif " + id, "Résumé", status,
                null, NOW, NOW, List.of());
    }

    private ConversationPlanProposal.Operation create(
            String id, String goal,
            ConversationPlanProposal.EntityReference parent,
            List<ConversationPlanProposal.EntityReference> dependencies) {
        ConversationPlanProposal.OperationType type = parent == null
                ? ConversationPlanProposal.OperationType.CREATE_OBJECTIVE
                : ConversationPlanProposal.OperationType.CREATE_SUBTASK;
        return new ConversationPlanProposal.Operation(
                id, type, null, goal, "Résumé : " + goal, parent,
                dependencies, List.of()
        );
    }

    private ConversationPlanProposal.EntityReference proposed(String id) {
        return new ConversationPlanProposal.EntityReference(
                ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION, id
        );
    }

    private ConversationPlanProposal plan(
            List<ConversationPlanProposal.Operation> operations,
            List<ConversationPlanProposal.Clarification> clarifications) {
        return new ConversationPlanProposal(
                "Plan de test", InterpretationConfidence.HIGH, operations, clarifications
        );
    }
}
