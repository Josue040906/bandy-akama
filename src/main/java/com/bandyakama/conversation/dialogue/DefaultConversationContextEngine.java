package com.bandyakama.conversation.dialogue;

import org.springframework.stereotype.Service;

import java.util.Objects;

/** Default orchestration of interpretation, validation and state transition. */
@Service
public class DefaultConversationContextEngine implements ConversationContextEngine {
    private final DialogueInterpreter interpreter;
    private final ContextDecisionValidator validator;
    private final DialogueStateTransitioner transitioner;

    public DefaultConversationContextEngine(
            DialogueInterpreter interpreter,
            ContextDecisionValidator validator,
            DialogueStateTransitioner transitioner
    ) {
        this.interpreter = Objects.requireNonNull(interpreter);
        this.validator = Objects.requireNonNull(validator);
        this.transitioner = Objects.requireNonNull(transitioner);
    }

    @Override
    public ContextResolution resolve(ContextEngineInput input) {
        Objects.requireNonNull(input, "input must not be null.");
        ContextInterpretationProposal proposal = interpreter.interpret(input);
        ContextDecision decision = validator.validate(input, proposal);
        DialogueState proposedState = transitioner.transition(
                input,
                proposal,
                decision
        );
        return new ContextResolution(
                decision,
                proposal.confidence(),
                proposedState
        );
    }
}
