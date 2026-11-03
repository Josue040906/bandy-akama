package com.bandyakama.conversation.dialogue;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies every operation in a validated plan to one immutable dialogue snapshot.
 * It is deliberately free of persistence, model calls and natural-language rules.
 */
@Component
public class ConversationPlanTransitioner {
    private final Clock clock;

    public ConversationPlanTransitioner() {
        this(Clock.systemUTC());
    }

    ConversationPlanTransitioner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
    }

    public DialogueState transition(ContextEngineInput input, ValidatedConversationPlan plan) {
        Objects.requireNonNull(input, "input must not be null.");
        Objects.requireNonNull(plan, "plan must not be null.");

        DialogueState current = input.dialogueState();
        Instant now = clock.instant();
        Map<String, DialogueObjective> objectives = new LinkedHashMap<>();
        current.objectives().forEach(item -> objectives.put(item.id(), item));
        List<PendingClarification> pending = new ArrayList<>(current.pendingClarifications());

        // Allocate all durable IDs before applying operations so forward references
        // and clarifications can safely target any proposed creation.
        Map<String, String> createdIds = new LinkedHashMap<>();
        for (ConversationPlanProposal.Operation operation : plan.orderedOperations()) {
            if (isCreation(operation.type())) {
                createdIds.put(operation.operationId(), UUID.randomUUID().toString());
            }
        }

        String focus = current.activeObjectiveId();
        for (ConversationPlanProposal.Operation operation : plan.orderedOperations()) {
            switch (operation.type()) {
                case CREATE_OBJECTIVE, CREATE_SUBTASK -> {
                    String id = createdIds.get(operation.operationId());
                    String parentId = resolve(operation.parent(), createdIds);
                    List<String> dependencies = operation.dependsOn().stream()
                            .map(reference -> resolve(reference, createdIds))
                            .toList();
                    boolean ready = dependencies.stream().allMatch(dependency ->
                            statusOf(objectives, dependency) == DialogueObjectiveStatus.COMPLETED);
                    DialogueObjective created = new DialogueObjective(
                            id, operation.goal(), operation.summary(),
                            ready ? DialogueObjectiveStatus.ACTIVE : DialogueObjectiveStatus.BLOCKED,
                            parentId, now, now,
                            toReferences(operation.referenceMentions(),
                                    input.currentMessageSequenceNumber()),
                            dependencies
                    );
                    objectives.put(id, created);
                    if (ready) {
                        // Creating an independent objective changes focus but
                        // does not implicitly suspend another active objective.
                        // Suspension is an explicit NEW_TOPIC_REQUEST operation.
                        focus = id;
                    }
                }
                case CONTINUE_OBJECTIVE, RESUME_OBJECTIVE, CORRECT_OBJECTIVE -> {
                    DialogueObjective target = requireObjective(objectives, operation.targetObjectiveId());
                    DialogueObjectiveStatus nextStatus = target.status();
                    if (operation.type() == ConversationPlanProposal.OperationType.RESUME_OBJECTIVE
                            || operation.type() == ConversationPlanProposal.OperationType.CONTINUE_OBJECTIVE) {
                        nextStatus = prerequisitesCompleted(objectives, target)
                                ? DialogueObjectiveStatus.ACTIVE : DialogueObjectiveStatus.BLOCKED;
                    }
                    String goal = operation.goal() == null ? target.goal() : operation.goal();
                    List<DialogueReference> references = new ArrayList<>(target.references());
                    appendReferences(references, operation.referenceMentions(),
                            input.currentMessageSequenceNumber());
                    DialogueObjective updated = copy(target, goal, operation.summary(),
                            nextStatus, references, now);
                    objectives.put(target.id(), updated);
                    if (nextStatus == DialogueObjectiveStatus.ACTIVE) {
                        // A standalone resume transfers the active focus. In a
                        // multi-operation plan, other explicitly updated objectives
                        // remain active; focus is a pointer, not a global mutex.
                        if (plan.orderedOperations().size() == 1
                                && operation.type()
                                == ConversationPlanProposal.OperationType.RESUME_OBJECTIVE
                                && focus != null && !focus.equals(target.id())) {
                            DialogueObjective previous = objectives.get(focus);
                            if (previous != null
                                    && previous.status() == DialogueObjectiveStatus.ACTIVE) {
                                objectives.put(focus, withStatus(
                                        previous, DialogueObjectiveStatus.SUSPENDED, now
                                ));
                            }
                        }
                        focus = target.id();
                    } else if (Objects.equals(focus, target.id())) {
                        focus = null;
                    }
                }
                case COMPLETE_OBJECTIVE -> {
                    DialogueObjective target = requireObjective(objectives, operation.targetObjectiveId());
                    objectives.put(target.id(), withStatus(target,
                            DialogueObjectiveStatus.COMPLETED, now));
                    pending.removeIf(item -> target.id().equals(item.targetObjectiveId()));
                    if (Objects.equals(focus, target.id())) focus = null;
                    unblockReadyObjectives(objectives, now);
                    if (focus == null) focus = mostRecentlyUpdatedActive(objectives);
                }
                case ANSWER_CLARIFICATION -> {
                    PendingClarification answered = pending.stream()
                            .filter(item -> operation.clarificationId() == null
                                    ? pending.size() == 1
                                    : item.clarificationId().equals(operation.clarificationId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "La clarification à résoudre n'est plus en attente."
                            ));
                    pending.remove(answered);
                    if (operation.targetObjectiveId() != null) {
                        DialogueObjective target = requireObjective(objectives, operation.targetObjectiveId());
                        List<DialogueReference> references = new ArrayList<>(target.references());
                        appendReferences(references, operation.referenceMentions(),
                                input.currentMessageSequenceNumber());
                        objectives.put(target.id(), copy(target, target.goal(), operation.summary(),
                                target.status(), references, now));
                        if (target.status() == DialogueObjectiveStatus.ACTIVE) focus = target.id();
                    }
                }
                case NEW_TOPIC_REQUEST -> {
                    if (focus != null) {
                        DialogueObjective focused = requireObjective(objectives, focus);
                        objectives.put(focus, withStatus(focused,
                                DialogueObjectiveStatus.SUSPENDED, now));
                        focus = null;
                    }
                }
                case SOCIAL_TURN -> {
                    // Intentionally no mutation.
                }
            }
        }

        // A newly created clarification is independent from all existing ones.
        for (ConversationPlanProposal.Clarification clarification : plan.source().clarifications()) {
            String targetId = resolveClarificationTarget(clarification.target(), plan, createdIds);
            pending.add(new PendingClarification(
                    clarification.clarificationId(), targetId,
                    clarification.question(), clarification.rationale(), now
            ));
        }

        if (focus != null && statusOf(objectives, focus) != DialogueObjectiveStatus.ACTIVE) {
            focus = mostRecentlyUpdatedActive(objectives);
        }
        return DialogueState.withPendingClarifications(
                DialogueState.CURRENT_SCHEMA_VERSION,
                focus,
                List.copyOf(objectives.values()),
                pending
        );
    }

    private boolean isCreation(ConversationPlanProposal.OperationType type) {
        return type == ConversationPlanProposal.OperationType.CREATE_OBJECTIVE
                || type == ConversationPlanProposal.OperationType.CREATE_SUBTASK;
    }

    private String resolve(ConversationPlanProposal.EntityReference reference,
                           Map<String, String> createdIds) {
        if (reference == null) return null;
        return reference.scope() == ConversationPlanProposal.ReferenceScope.EXISTING_OBJECTIVE
                ? reference.id() : Objects.requireNonNull(createdIds.get(reference.id()),
                "La référence proposée doit désigner une création.");
    }

    private String resolveClarificationTarget(
            ConversationPlanProposal.EntityReference reference,
            ValidatedConversationPlan plan,
            Map<String, String> createdIds) {
        if (reference == null) return null;
        if (reference.scope() == ConversationPlanProposal.ReferenceScope.EXISTING_OBJECTIVE) {
            return reference.id();
        }
        ConversationPlanProposal.Operation operation = plan.orderedOperations().stream()
                .filter(item -> item.operationId().equals(reference.id()))
                .findFirst().orElseThrow();
        return isCreation(operation.type())
                ? createdIds.get(operation.operationId())
                : operation.targetObjectiveId();
    }

    private DialogueObjective requireObjective(Map<String, DialogueObjective> objectives, String id) {
        DialogueObjective result = objectives.get(id);
        if (result == null) throw new IllegalArgumentException("Objectif absent : " + id);
        return result;
    }

    private DialogueObjectiveStatus statusOf(Map<String, DialogueObjective> objectives, String id) {
        return requireObjective(objectives, id).status();
    }

    private boolean prerequisitesCompleted(Map<String, DialogueObjective> objectives,
                                            DialogueObjective objective) {
        return objective.dependsOnObjectiveIds().stream().allMatch(id ->
                statusOf(objectives, id) == DialogueObjectiveStatus.COMPLETED);
    }

    private void unblockReadyObjectives(Map<String, DialogueObjective> objectives, Instant now) {
        for (DialogueObjective objective : new ArrayList<>(objectives.values())) {
            if (objective.status() == DialogueObjectiveStatus.BLOCKED
                    && prerequisitesCompleted(objectives, objective)) {
                objectives.put(objective.id(), withStatus(objective,
                        DialogueObjectiveStatus.ACTIVE, now));
            }
        }
    }

    private String mostRecentlyUpdatedActive(Map<String, DialogueObjective> objectives) {
        return objectives.values().stream()
                .filter(item -> item.status() == DialogueObjectiveStatus.ACTIVE)
                .max(java.util.Comparator.comparing(DialogueObjective::updatedAt)
                        .thenComparing(DialogueObjective::id))
                .map(DialogueObjective::id).orElse(null);
    }

    private DialogueObjective copy(DialogueObjective source, String goal, String summary,
                                   DialogueObjectiveStatus status,
                                   List<DialogueReference> references, Instant now) {
        return new DialogueObjective(source.id(), goal, summary, status,
                source.parentObjectiveId(), source.createdAt(),
                now.isBefore(source.createdAt()) ? source.createdAt() : now,
                references, source.dependsOnObjectiveIds());
    }

    private DialogueObjective withStatus(DialogueObjective source,
                                         DialogueObjectiveStatus status, Instant now) {
        if (source.status() == status) return source;
        return copy(source, source.goal(), source.summary(), status, source.references(), now);
    }

    private List<DialogueReference> toReferences(List<ProposedDialogueReference> proposals,
                                                  long sequence) {
        List<DialogueReference> references = new ArrayList<>();
        appendReferences(references, proposals, sequence);
        return List.copyOf(references);
    }

    private void appendReferences(List<DialogueReference> destination,
                                  List<ProposedDialogueReference> proposals,
                                  long sequence) {
        for (ProposedDialogueReference proposal : proposals) {
            boolean duplicate = destination.stream().anyMatch(reference ->
                    reference.kind().equalsIgnoreCase(proposal.kind())
                            && reference.label().equalsIgnoreCase(proposal.label()));
            if (!duplicate) destination.add(new DialogueReference(
                    UUID.randomUUID().toString(), proposal.label(), proposal.kind(), sequence));
        }
    }
}
