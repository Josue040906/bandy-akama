package com.bandyakama.conversation.dialogue;

/**
 * Qualitative confidence reported by the semantic interpreter.
 *
 * <p>This value is advisory only. It is not proof that a proposed target
 * exists or that a state transition is valid; the backend validator remains
 * authoritative.</p>
 */
public enum InterpretationConfidence {
    HIGH,
    MEDIUM,
    LOW
}
