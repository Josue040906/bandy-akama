package com.bandyakama.conversation.dialogue;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Default orchestration of interpretation, validation and state transition. */
@Service
public class DefaultConversationContextEngine implements ConversationContextEngine {
    private static final Pattern GREETING = Pattern.compile(
            "^(bonjour|bonsoir|salut|coucou|hello|hi|hey|salama|manao ahoana)"
                    + "( bandy( akama)?| assistant)?[.!?]*$"
    );
    private static final Pattern NEW_TOPIC_REQUEST = Pattern.compile(
            "^(je veux |je souhaite |je voudrais )?(poser )?(une )?"
                    + "(nouvelle question|nouvelle recherche)[.!?]*$"
                    + "|^(changeons de sujet|passons a autre chose|"
                    + "passons a un autre sujet|parlons d['’]un autre sujet|"
                    + "rien a voir)[.!?]*$"
    );
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
        ContextInterpretationProposal proposal = deterministicSocialProposal(input);
        if (proposal == null) {
            proposal = interpreter.interpret(input);
        }
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

    /** Deterministic shortcuts live inside the central engine, never beside it. */
    private ContextInterpretationProposal deterministicSocialProposal(
            ContextEngineInput input
    ) {
        String normalized = Normalizer.normalize(
                input.currentMessage().toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        ).replaceAll("\\p{M}+", "").replaceAll("\\s+", " ").strip();
        DialogueRelation relation;
        if (GREETING.matcher(normalized).matches()) {
            relation = DialogueRelation.SOCIAL_TURN;
        } else if (NEW_TOPIC_REQUEST.matcher(normalized).matches()) {
            relation = DialogueRelation.NEW_TOPIC_REQUEST;
        } else {
            return null;
        }
        return new ContextInterpretationProposal(
                relation, null, "Classification déterministe d'un message de contrôle conversationnel.",
                null, InterpretationConfidence.HIGH, null, null, java.util.List.of()
        );
    }
}
