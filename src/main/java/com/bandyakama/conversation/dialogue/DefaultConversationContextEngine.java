package com.bandyakama.conversation.dialogue;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Orchestrates generic model interpretation, structural validation and
 * deterministic application of a multi-operation dialogue plan.
 */
@Service
public class DefaultConversationContextEngine implements ConversationContextEngine {
    private final ConversationPlanInterpreter interpreter;
    private final ConversationPlanValidator validator;
    private final ConversationPlanTransitioner transitioner;

    public DefaultConversationContextEngine(
            ConversationPlanInterpreter interpreter,
            ConversationPlanValidator validator,
            ConversationPlanTransitioner transitioner
    ) {
        this.interpreter = Objects.requireNonNull(interpreter);
        this.validator = Objects.requireNonNull(validator);
        this.transitioner = Objects.requireNonNull(transitioner);
    }

    @Override
    public ContextResolution resolve(ContextEngineInput input) {
        Objects.requireNonNull(input, "input must not be null.");
        ConversationPlanProposal interpreted = interpreter.interpret(input);
        ConversationPlanProposal proposal = applyConfidencePolicy(interpreted);
        ValidatedConversationPlan validated = validator.validate(input, proposal);
        DialogueState proposedState = transitioner.transition(input, validated);
        return ContextResolution.fromPlan(validated, input.dialogueState(), proposedState);
    }

    private ConversationPlanProposal applyConfidencePolicy(
            ConversationPlanProposal proposal
    ) {
        if (proposal.confidence() != InterpretationConfidence.LOW
                || proposal.operations().stream().noneMatch(operation ->
                        operation.type() != ConversationPlanProposal.OperationType.SOCIAL_TURN)) {
            return proposal;
        }

        List<ConversationPlanProposal.Operation> socialTurns = proposal.operations().stream()
                .filter(operation -> operation.type()
                        == ConversationPlanProposal.OperationType.SOCIAL_TURN)
                .toList();
        return new ConversationPlanProposal(
                proposal.rationale(),
                InterpretationConfidence.LOW,
                socialTurns,
                List.of(new ConversationPlanProposal.Clarification(
                        "low-confidence",
                        null,
                        "Je ne suis pas certain d'avoir correctement compris votre demande. "
                                + "Pouvez-vous préciser ce que vous souhaitez faire ?",
                        "La confiance globale est insuffisante pour appliquer ce plan."
                ))
        );
    }
}
