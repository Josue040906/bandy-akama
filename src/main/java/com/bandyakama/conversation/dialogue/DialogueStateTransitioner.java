package com.bandyakama.conversation.dialogue;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies validated dialogue decisions to an immutable state snapshot.
 * It has no persistence or operational-tool-state responsibilities.
 */
@Component
public class DialogueStateTransitioner {
    private final Clock clock;

    public DialogueStateTransitioner() {
        this(Clock.systemUTC());
    }

    DialogueStateTransitioner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
    }

    public DialogueState transition(
            ContextEngineInput input,
            ContextInterpretationProposal proposal,
            ContextDecision decision
    ) {
        DialogueState current = input.dialogueState();
        Instant now = clock.instant();

        if (decision.relation() == DialogueRelation.SOCIAL_TURN) {
            // A greeting is a real dialogue turn, but it must preserve both
            // objectives and any pending clarification exactly as they are.
            return current;
        }

        if (decision.relation() == DialogueRelation.NEW_TOPIC_REQUEST) {
            List<DialogueObjective> suspended = current.objectives().stream()
                    .map(objective -> withStatus(
                            objective,
                            objective.status() == DialogueObjectiveStatus.ACTIVE
                                    ? DialogueObjectiveStatus.SUSPENDED
                                    : objective.status(),
                            now
                    ))
                    .toList();
            return new DialogueState(
                    DialogueState.CURRENT_SCHEMA_VERSION,
                    null,
                    suspended,
                    null
            );
        }

        if (decision.relation() == DialogueRelation.TASK_COMPLETION) {
            DialogueObjective target = current.objectives().stream()
                    .filter(objective -> objective.id().equals(
                            decision.targetObjectiveId()
                    ))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "La décision référence un objectif absent."
                    ));
            if (target.status() != DialogueObjectiveStatus.ACTIVE
                    || !Objects.equals(current.activeObjectiveId(), target.id())) {
                throw new IllegalArgumentException(
                        "Seul l'objectif actif peut être clôturé."
                );
            }
            List<DialogueObjective> completed = current.objectives().stream()
                    .map(objective -> objective.id().equals(target.id())
                            ? withStatus(objective, DialogueObjectiveStatus.COMPLETED, now)
                            : objective)
                    .toList();
            return new DialogueState(
                    DialogueState.CURRENT_SCHEMA_VERSION,
                    null,
                    completed,
                    null
            );
        }

        if (decision.relation() == DialogueRelation.NEEDS_CLARIFICATION) {
            return new DialogueState(
                    DialogueState.CURRENT_SCHEMA_VERSION,
                    current.activeObjectiveId(),
                    current.objectives(),
                    new PendingClarification(
                            decision.targetObjectiveId(),
                            decision.clarificationQuestion(),
                            decision.rationale(),
                            now
                    )
            );
        }

        List<DialogueObjective> next = new ArrayList<>();
        DialogueObjective target = current.objectives().stream()
                .filter(objective -> objective.id().equals(
                        decision.targetObjectiveId()
                ))
                .findFirst()
                .orElse(null);

        String newActiveId;
        if (decision.relation() == DialogueRelation.NEW_TASK) {
            for (DialogueObjective objective : current.objectives()) {
                next.add(withStatus(objective,
                        objective.status() == DialogueObjectiveStatus.ACTIVE
                                ? DialogueObjectiveStatus.SUSPENDED
                                : objective.status(),
                        now));
            }
            DialogueObjective created = newObjective(
                    proposal.objectiveGoal(),
                    proposal.objectiveSummary(),
                    null,
                    input.currentMessageSequenceNumber(),
                    proposal.referenceMentions(),
                    now
            );
            next.add(created);
            newActiveId = created.id();
        } else {
            if (target == null) {
                throw new IllegalArgumentException(
                        "La décision référence un objectif absent."
                );
            }
            for (DialogueObjective objective : current.objectives()) {
                if (objective.id().equals(target.id())) {
                    next.add(updateTarget(
                            objective,
                            proposal,
                            input.currentMessageSequenceNumber(),
                            now
                    ));
                } else if (objective.status() == DialogueObjectiveStatus.ACTIVE) {
                    next.add(withStatus(
                            objective,
                            DialogueObjectiveStatus.SUSPENDED,
                            now
                    ));
                } else {
                    next.add(objective);
                }
            }
            newActiveId = target.id();
        }

        return new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                newActiveId,
                next,
                null
        );
    }

    private DialogueObjective updateTarget(
            DialogueObjective target,
            ContextInterpretationProposal proposal,
            long messageSequence,
            Instant now
    ) {
        List<DialogueReference> references =
                new ArrayList<>(target.references());
        appendReferences(references, proposal.referenceMentions(), messageSequence);

        String goal = proposal.objectiveGoal() == null
                ? target.goal()
                : proposal.objectiveGoal();
        return new DialogueObjective(
                target.id(),
                goal,
                proposal.objectiveSummary(),
                DialogueObjectiveStatus.ACTIVE,
                target.parentObjectiveId(),
                target.createdAt(),
                now.isBefore(target.createdAt()) ? target.createdAt() : now,
                references
        );
    }

    private DialogueObjective newObjective(
            String goal,
            String summary,
            String parentId,
            long messageSequence,
            List<ProposedDialogueReference> proposedReferences,
            Instant now
    ) {
        List<DialogueReference> references = new ArrayList<>();
        appendReferences(references, proposedReferences, messageSequence);
        String id = UUID.randomUUID().toString();
        return new DialogueObjective(
                id, goal, summary, DialogueObjectiveStatus.ACTIVE,
                parentId, now, now, references
        );
    }

    private void appendReferences(
            List<DialogueReference> destination,
            List<ProposedDialogueReference> proposals,
            long messageSequence
    ) {
        for (ProposedDialogueReference proposal : proposals) {
            boolean duplicate = destination.stream().anyMatch(reference ->
                    reference.kind().equalsIgnoreCase(proposal.kind())
                            && reference.label().equalsIgnoreCase(proposal.label())
            );
            if (!duplicate) {
                destination.add(new DialogueReference(
                        UUID.randomUUID().toString(),
                        proposal.label(),
                        proposal.kind(),
                        messageSequence
                ));
            }
        }
    }

    private DialogueObjective withStatus(
            DialogueObjective objective,
            DialogueObjectiveStatus status,
            Instant now
    ) {
        if (objective.status() == status) {
            return objective;
        }
        return new DialogueObjective(
                objective.id(),
                objective.goal(),
                objective.summary(),
                status,
                objective.parentObjectiveId(),
                objective.createdAt(),
                now.isBefore(objective.createdAt())
                        ? objective.createdAt()
                        : now,
                objective.references()
        );
    }
}
