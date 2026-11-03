package com.bandyakama.conversation.dialogue;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned, domain-neutral state of the dialogue.
 *
 * <p>Several objectives may be active concurrently. activeObjectiveId is the
 * conversational focus, not a global lock: it identifies the objective most
 * relevant to the current turn. Parent hierarchy and execution dependencies
 * are independent graphs.</p>
 *
 * @param schemaVersion version of this serialized dialogue-state contract
 * @param activeObjectiveId conversationally focused objective, if any
 * @param objectives known objectives and subtasks in every lifecycle state
 * @param pendingClarification unresolved clarification, if any
 */
public record DialogueState(
        int schemaVersion,
        String activeObjectiveId,
        List<DialogueObjective> objectives,
        PendingClarification pendingClarification
) {
    /**
     * The new fields are additive JSON properties. Keeping version 1 allows
     * snapshots written before dependsOnObjectiveIds existed to deserialize:
     * DialogueObjective normalizes a missing dependency list to empty.
     */
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

        Map<String, DialogueObjective> byId = new HashMap<>();
        for (DialogueObjective objective : objectives) {
            Objects.requireNonNull(objective, "objectives must not contain null.");
            if (byId.putIfAbsent(objective.id(), objective) != null) {
                throw new IllegalArgumentException("Objective identifiers must be unique.");
            }
        }

        if (activeObjectiveId != null) {
            DialogueObjective focused = byId.get(activeObjectiveId);
            if (focused == null || focused.status() != DialogueObjectiveStatus.ACTIVE) {
                throw new IllegalArgumentException(
                        "activeObjectiveId must identify an ACTIVE objective."
                );
            }
        }

        for (DialogueObjective objective : objectives) {
            if (objective.parentObjectiveId() != null
                    && !byId.containsKey(objective.parentObjectiveId())) {
                throw new IllegalArgumentException(
                        "A parent objective must reference a known objective."
                );
            }
            for (String dependencyId : objective.dependsOnObjectiveIds()) {
                if (!byId.containsKey(dependencyId)) {
                    throw new IllegalArgumentException(
                            "An execution dependency must reference a known objective."
                    );
                }
            }
        }

        rejectCycles(objectives, true);
        rejectCycles(objectives, false);

        if (pendingClarification != null
                && pendingClarification.targetObjectiveId() != null
                && !byId.containsKey(pendingClarification.targetObjectiveId())) {
            throw new IllegalArgumentException(
                    "The clarification target must reference a known objective."
            );
        }
    }

    public static DialogueState empty() {
        return new DialogueState(CURRENT_SCHEMA_VERSION, null, List.of(), null);
    }

    private static void rejectCycles(
            List<DialogueObjective> objectives,
            boolean hierarchy
    ) {
        Map<String, List<String>> edges = new HashMap<>();
        for (DialogueObjective objective : objectives) {
            String parent = objective.parentObjectiveId();
            edges.put(objective.id(), hierarchy
                    ? (parent == null ? List.of() : List.of(parent))
                    : objective.dependsOnObjectiveIds());
        }

        Map<String, Integer> marks = new HashMap<>();
        for (String id : edges.keySet()) {
            visit(id, edges, marks, hierarchy
                    ? "The objective hierarchy must be acyclic."
                    : "The objective dependency graph must be acyclic.");
        }
    }

    private static void visit(
            String id,
            Map<String, List<String>> edges,
            Map<String, Integer> marks,
            String message
    ) {
        int mark = marks.getOrDefault(id, 0);
        if (mark == 1) {
            throw new IllegalArgumentException(message);
        }
        if (mark == 2) {
            return;
        }
        marks.put(id, 1);
        for (String next : edges.getOrDefault(id, List.of())) {
            visit(next, edges, marks, message);
        }
        marks.put(id, 2);
    }
}
