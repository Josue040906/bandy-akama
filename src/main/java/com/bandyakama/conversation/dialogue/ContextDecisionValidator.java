package com.bandyakama.conversation.dialogue;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Validates semantic proposals against the authoritative dialogue snapshot.
 * Model confidence is advisory and never bypasses structural validation.
 */
@Component
public class ContextDecisionValidator {

    public ContextDecision validate(
            ContextEngineInput input,
            ContextInterpretationProposal proposal
    ) {
        Objects.requireNonNull(input, "input must not be null.");
        Objects.requireNonNull(proposal, "proposal must not be null.");

        if (proposal.confidence() == InterpretationConfidence.LOW
                && proposal.relation() != DialogueRelation.NEEDS_CLARIFICATION) {
            PendingClarification pending =
                    input.dialogueState().pendingClarification();
            return new ContextDecision(
                    DialogueRelation.NEEDS_CLARIFICATION,
                    pending == null ? null : pending.targetObjectiveId(),
                    "L'interprétation sémantique est trop incertaine pour modifier un objectif.",
                    "Pouvez-vous préciser ce que vous souhaitez poursuivre ou modifier ?"
            );
        }

        PendingClarification pending = input.dialogueState().pendingClarification();
        if (pending != null
                && proposal.relation() != DialogueRelation.USER_CLARIFICATION
                && proposal.relation() != DialogueRelation.NEW_TASK
                && proposal.relation() != DialogueRelation.NEEDS_CLARIFICATION) {
            throw invalid(
                    "Une clarification est en attente : elle doit être résolue ou explicitement remplacée."
            );
        }

        if (proposal.relation() == DialogueRelation.NEEDS_CLARIFICATION) {
            return new ContextDecision(
                    proposal.relation(),
                    proposal.targetObjectiveId(),
                    proposal.rationale(),
                    proposal.clarificationQuestion()
            );
        }

        DialogueObjective target = findTarget(
                input.dialogueState(),
                proposal.targetObjectiveId()
        );

        switch (proposal.relation()) {
            case NEW_TASK -> {
                if (proposal.targetObjectiveId() != null) {
                    throw invalid("NEW_TASK ne doit pas cibler un objectif existant.");
                }
                if (proposal.objectiveGoal() == null || proposal.objectiveSummary() == null) {
                    throw invalid("Un nouvel objectif doit contenir un but et un résumé.");
                }
            }
            case CONTINUATION -> {
                requireTarget(target, proposal);
                if (!Objects.equals(
                        input.dialogueState().activeObjectiveId(),
                        target.id()
                )) {
                    throw invalid("CONTINUATION doit cibler l'objectif actif.");
                }
            }
            case TOPIC_RESUMPTION -> {
                requireTarget(target, proposal);
                if (target.status() != DialogueObjectiveStatus.SUSPENDED) {
                    throw invalid("TOPIC_RESUMPTION doit cibler un objectif suspendu.");
                }
            }
            case CORRECTION -> {
                requireTarget(target, proposal);
                if (target.status() == DialogueObjectiveStatus.COMPLETED) {
                    throw invalid("Un objectif terminé ne peut pas être corrigé implicitement.");
                }
                if (proposal.objectiveGoal() == null) {
                    throw invalid("CORRECTION doit préciser le but corrigé.");
                }
            }
            case USER_CLARIFICATION -> {
                requireTarget(target, proposal);
                if (pending == null) {
                    throw invalid("Aucune clarification n'est actuellement attendue.");
                }
                if (pending.targetObjectiveId() != null
                        && !pending.targetObjectiveId().equals(target.id())) {
                    throw invalid("La clarification ne correspond pas à l'objectif attendu.");
                }
                if (target.status() == DialogueObjectiveStatus.COMPLETED) {
                    throw invalid("La clarification cible un objectif terminé.");
                }
            }
            case NEEDS_CLARIFICATION -> throw new IllegalStateException(
                    "Clarification proposals are handled before target validation."
            );
        }

        return new ContextDecision(
                proposal.relation(),
                proposal.targetObjectiveId(),
                proposal.rationale(),
                null
        );
    }

    private DialogueObjective findTarget(DialogueState state, String id) {
        if (id == null) {
            return null;
        }
        return state.objectives().stream()
                .filter(objective -> objective.id().equals(id))
                .findFirst()
                .orElseThrow(() -> invalid("L'objectif cible n'existe pas."));
    }

    private void requireTarget(
            DialogueObjective target,
            ContextInterpretationProposal proposal
    ) {
        if (target == null) {
            throw invalid(proposal.relation() + " exige un objectif existant.");
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
