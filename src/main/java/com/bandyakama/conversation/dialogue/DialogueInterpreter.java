package com.bandyakama.conversation.dialogue;

/**
 * Produces a semantic proposal for the relation between a new message and
 * the existing dialogue.
 *
 * <p>Implementations may use a language model, but their output is untrusted
 * and must always pass through backend validation.</p>
 */
/** @deprecated Use ConversationPlanInterpreter for all dialogue interpretation. */
@Deprecated(forRemoval = true)
public interface DialogueInterpreter {

    ContextInterpretationProposal interpret(ContextEngineInput input);
}
