package com.bandyakama.conversation.dialogue;

/** Produces an untrusted multi-operation semantic plan for one user message. */
public interface ConversationPlanInterpreter {
    ConversationPlanProposal interpret(ContextEngineInput input);
}
