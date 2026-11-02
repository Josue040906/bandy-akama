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

    /** The user explicitly closes the currently active objective. */
    TASK_COMPLETION,

    /** The message answers a clarification requested by the assistant. */
    USER_CLARIFICATION,

    /** A social turn (for example a greeting) that must not alter dialogue state. */
    SOCIAL_TURN,

    /** The user explicitly asks to start a fresh question without giving it yet. */
    NEW_TOPIC_REQUEST,

    /** The message cannot safely be attached to an objective without asking. */
    NEEDS_CLARIFICATION
}
