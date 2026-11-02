package com.bandyakama.conversation.dialogue;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned, domain-neutral state of the dialogue.
 *
 * <p>It stores objectives, their lifecycle, conversational summaries and
 * references. It deliberately does not store tool names, tool arguments,
 * search filters, API payloads or other operational task state.</p>
 *
 * @param schemaVersion version of this serialized dialogue-state contract
 * @param activeObjectiveId identifier of the sole active objective, if any
 * @param objectives known objectives, including suspended objectives that may
 *                   later be resumed
 * @param pendingClarification unresolved clarification, if any
 */
public record DialogueState(
        int schemaVersion,
        String activeObjectiveId,
        List<DialogueObjective> objectives,
        PendingClarification pendingClarification
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public DialogueState {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported dialogue-state schema version: " + schemaVersion
            );
        }
        objectives = List.copyOf(
                Objects.requireNonNull(objectives, "objectives must not be null.")
        );

        Set<String> objectiveIds = new HashSet<>();
        int activeCount = 0;
        String actualActiveId = null;
        for (DialogueObjective objective : objectives) {
            Objects.requireNonNull(objective, "objectives must not contain null.");
            if (!objectiveIds.add(objective.id())) {
                throw new IllegalArgumentException(
                        "Objective identifiers must be unique."
                );
            }
            if (objective.parentObjectiveId() != null
                    && !objectiveIds.contains(objective.parentObjectiveId())) {
                // Parent objectives must precede their children in the state list.
                throw new IllegalArgumentException(
                        "A parent objective must exist before its child."
                );
            }
            if (objective.status() == DialogueObjectiveStatus.ACTIVE) {
                activeCount++;
                actualActiveId = objective.id();
            }
        }

        if (activeCount > 1) {
            throw new IllegalArgumentException(
                    "Only one dialogue objective may be active."
            );
        }
        if (!Objects.equals(activeObjectiveId, actualActiveId)) {
            throw new IllegalArgumentException(
                    "activeObjectiveId must identify the sole ACTIVE objective."
            );
        }
        if (pendingClarification != null
                && pendingClarification.targetObjectiveId() != null
                && !objectiveIds.contains(pendingClarification.targetObjectiveId())) {
            throw new IllegalArgumentException(
                    "The clarification target must reference a known objective."
            );
        }
    }

    public static DialogueState empty() {
        return new DialogueState(
                CURRENT_SCHEMA_VERSION,
                null,
                List.of(),
                null
        );
    }
}
