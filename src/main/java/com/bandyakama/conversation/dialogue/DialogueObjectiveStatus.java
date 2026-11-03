package com.bandyakama.conversation.dialogue;

/**
 * Lifecycle of a domain-neutral dialogue objective.
 *
 * BLOCKED means at least one declared execution prerequisite is not complete;
 * SUSPENDED means the user or dialogue flow temporarily interrupted the work.
 */
public enum DialogueObjectiveStatus {
    ACTIVE,
    BLOCKED,
    SUSPENDED,
    COMPLETED
}
