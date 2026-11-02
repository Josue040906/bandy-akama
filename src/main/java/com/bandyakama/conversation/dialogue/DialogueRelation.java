package com.bandyakama.conversation.dialogue;

/**
 * Domain-independent relationship between a new user message and the
 * conversation's existing objectives.
 */
public enum DialogueRelation {
    /** The message advances the currently active objective. */
    CONTINUATION,

    /** The message starts an independent objective. */
    NEW_TASK,

    /** The message returns to an objective that was previously suspended. */
    TOPIC_RESUMPTION,

    /** The message corrects information or direction within an objective. */
    CORRECTION,

    /** The message answers a clarification requested by the assistant. */
    USER_CLARIFICATION,

    /** The message cannot safely be attached to an objective without asking. */
    NEEDS_CLARIFICATION
}
