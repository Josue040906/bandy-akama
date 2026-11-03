package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned, domain-neutral state of the dialogue.
 *
 * <p>Several objectives may be active concurrently. activeObjectiveId is the
 * conversational focus, not a global lock. Clarifications are independent
 * unresolved questions and are retained until individually resolved.</p>
 */
@JsonDeserialize(using = DialogueStateDeserializer.class)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public final class DialogueState {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final int schemaVersion;
    private final String activeObjectiveId;
    private final List<DialogueObjective> objectives;
    private final List<PendingClarification> pendingClarifications;

    /** Backward-compatible constructor for a single pending clarification. */
    public DialogueState(
            int schemaVersion,
            String activeObjectiveId,
            List<DialogueObjective> objectives,
            PendingClarification pendingClarification
    ) {
        this(schemaVersion, activeObjectiveId, objectives,
                pendingClarification == null ? List.of() : List.of(pendingClarification));
    }

    /** Constructor for the multi-clarification state contract. */
    public DialogueState(
            int schemaVersion,
            String activeObjectiveId,
            List<DialogueObjective> objectives,
            List<PendingClarification> pendingClarifications
    ) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported dialogue-state schema version: " + schemaVersion
            );
        }
        this.schemaVersion = schemaVersion;
        this.activeObjectiveId = activeObjectiveId;
        this.objectives = List.copyOf(
                Objects.requireNonNull(objectives, "objectives must not be null.")
        );
        this.pendingClarifications = List.copyOf(Objects.requireNonNull(
                pendingClarifications, "pendingClarifications must not be null."
        ));

        Map<String, DialogueObjective> byId = new HashMap<>();
        for (DialogueObjective objective : this.objectives) {
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

        for (DialogueObjective objective : this.objectives) {
            if (objective.parentObjectiveId() != null
                    && !byId.containsKey(objective.parentObjectiveId())) {
                throw new IllegalArgumentException(
                        "A parent objective must reference a known objective."
                );
            }
            for (String dependencyId : objective.dependsOnObjectiveIds()) {
                DialogueObjective prerequisite = byId.get(dependencyId);
                if (prerequisite == null) {
                    throw new IllegalArgumentException(
                            "An execution dependency must reference a known objective."
                    );
                }
                if (objective.status() == DialogueObjectiveStatus.ACTIVE
                        && prerequisite.status() != DialogueObjectiveStatus.COMPLETED) {
                    throw new IllegalArgumentException(
                            "An ACTIVE objective requires all execution prerequisites to be COMPLETED."
                    );
                }
            }
        }

        rejectCycles(this.objectives, true);
        rejectCycles(this.objectives, false);

        for (PendingClarification clarification : this.pendingClarifications) {
            Objects.requireNonNull(
                    clarification, "pendingClarifications must not contain null."
            );
            if (clarification.targetObjectiveId() != null
                    && !byId.containsKey(clarification.targetObjectiveId())) {
                throw new IllegalArgumentException(
                        "The clarification target must reference a known objective."
                );
            }
        }
    }

    public int schemaVersion() { return schemaVersion; }
    public String activeObjectiveId() { return activeObjectiveId; }
    public List<DialogueObjective> objectives() { return objectives; }
    public List<PendingClarification> pendingClarifications() { return pendingClarifications; }

    /** Compatibility accessor; multi-intention code must use pendingClarifications(). */
    public PendingClarification pendingClarification() {
        return pendingClarifications.isEmpty() ? null : pendingClarifications.get(0);
    }

    public static DialogueState empty() {
        return new DialogueState(CURRENT_SCHEMA_VERSION, null, List.of(), List.of());
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
            String id, Map<String, List<String>> edges,
            Map<String, Integer> marks, String message
    ) {
        int mark = marks.getOrDefault(id, 0);
        if (mark == 1) throw new IllegalArgumentException(message);
        if (mark == 2) return;
        marks.put(id, 1);
        for (String next : edges.getOrDefault(id, List.of())) {
            visit(next, edges, marks, message);
        }
        marks.put(id, 2);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DialogueState that)) return false;
        return schemaVersion == that.schemaVersion
                && Objects.equals(activeObjectiveId, that.activeObjectiveId)
                && objectives.equals(that.objectives)
                && pendingClarifications.equals(that.pendingClarifications);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, activeObjectiveId, objectives, pendingClarifications);
    }

    @Override
    public String toString() {
        return "DialogueState[schemaVersion=" + schemaVersion
                + ", activeObjectiveId=" + activeObjectiveId
                + ", objectives=" + objectives
                + ", pendingClarifications=" + pendingClarifications + "]";
    }
}
