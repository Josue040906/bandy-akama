package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextInterpretationProposalTest {

    @Test
    void requiresGoalAndSummaryForNewTask() {
        assertThrows(NullPointerException.class, () -> new ContextInterpretationProposal(
                DialogueRelation.NEW_TASK, null, "Nouvelle demande.",
                null, InterpretationConfidence.HIGH, null, null, List.of()
        ));
        var proposal = new ContextInterpretationProposal(
                DialogueRelation.NEW_TASK, null, "Nouvelle demande.",
                null, InterpretationConfidence.HIGH, "Apprendre Java",
                "L'utilisateur souhaite apprendre Java.", List.of()
        );
        assertEquals("Apprendre Java", proposal.objectiveGoal());
    }

    @Test
    void requiresSummaryForContinuationAndTarget() {
        assertThrows(IllegalArgumentException.class, () -> new ContextInterpretationProposal(
                DialogueRelation.CONTINUATION, null, "Suite logique.",
                null, InterpretationConfidence.HIGH, null, "Résumé", List.of()
        ));
        assertThrows(NullPointerException.class, () -> new ContextInterpretationProposal(
                DialogueRelation.CONTINUATION, "objective-1", "Suite logique.",
                null, InterpretationConfidence.HIGH, null, null, List.of()
        ));
    }

    @Test
    void clarificationCannotContainObjectiveMutationData() {
        assertThrows(IllegalArgumentException.class, () -> new ContextInterpretationProposal(
                DialogueRelation.NEEDS_CLARIFICATION, null, "Référence ambiguë.",
                "Quel objectif ?", InterpretationConfidence.LOW,
                "Un objectif", null, List.of()
        ));
    }

    @Test
    void copiesReferenceMentionsAndRejectsBlankLabels() {
        var references = new java.util.ArrayList<ProposedDialogueReference>();
        references.add(new ProposedDialogueReference("Projet Alpha", "project"));
        var proposal = new ContextInterpretationProposal(
                DialogueRelation.NEW_TASK, null, "Nouvelle demande.",
                null, InterpretationConfidence.HIGH, "Analyser un projet",
                "L'utilisateur veut analyser un projet.", references
        );
        references.clear();
        assertEquals(1, proposal.referenceMentions().size());
        assertThrows(IllegalArgumentException.class,
                () -> new ProposedDialogueReference(" ", "project"));
    }
}
