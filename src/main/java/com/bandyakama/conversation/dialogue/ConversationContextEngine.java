package com.bandyakama.conversation.dialogue;

/**
 * Resolves a new user message against the conversation's domain-neutral
 * dialogue state.
 *
 * <p>The engine is a pure decision boundary: it returns a proposed state and
 * does not persist it, mutate operational tool state, or execute tools.</p>
 */
public interface ConversationContextEngine {

    ContextResolution resolve(ContextEngineInput input);
}
