package com.bandyakama.conversation.dialogue;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A user objective or subtask tracked independently from the tools used to
 * fulfil it. Hierarchy and execution prerequisites are represented separately.
 *
 * @param id stable objective identifier
 * @param goal concise description of what the user wants to achieve
 * @param summary domain-neutral summary of the relevant dialogue
 * @param status lifecycle status
 * @param parentObjectiveId optional hierarchical parent
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 * @param references entities or items that later messages may refer to
 * @param dependsOnObjectiveIds objectives that must be completed first
 */
public record DialogueObjective(
        String id,
        String goal,
        String summary,
        DialogueObjectiveStatus status,
        String parentObjectiveId,
        Instant createdAt,
        Instant updatedAt,
        List<DialogueReference> references,
        List<String> dependsOnObjectiveIds
) {
    /**
     * Compatibility constructor for callers and persisted fixtures created
     * before explicit execution dependencies were introduced.
     */
    public DialogueObjective(
            String id,
            String goal,
            String summary,
            DialogueObjectiveStatus status,
            String parentObjectiveId,
            Instant createdAt,
            Instant updatedAt,
            List<DialogueReference> references
    ) {
        this(id, goal, summary, status, parentObjectiveId, createdAt, updatedAt,
                references, List.of());
    }

    public DialogueObjective {
        id = requireText(id, "id");
        goal = requireText(goal, "goal");
        summary = Objects.requireNonNull(summary, "summary must not be null.");
        status = Objects.requireNonNull(status, "status must not be null.");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null.");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null.");
        references = List.copyOf(
                Objects.requireNonNull(references, "references must not be null.")
        );
        // A missing JSON property from a legacy snapshot is equivalent to no prerequisites.
        dependsOnObjectiveIds = dependsOnObjectiveIds == null
                ? List.of()
                : List.copyOf(dependsOnObjectiveIds);

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "updatedAt must not be earlier than createdAt."
            );
        }
        if (id.equals(parentObjectiveId)) {
            throw new IllegalArgumentException(
                    "An objective cannot be its own parent."
            );
        }
        if (new HashSet<>(references.stream().map(DialogueReference::id).toList())
                .size() != references.size()) {
            throw new IllegalArgumentException(
                    "Reference identifiers must be unique within an objective."
            );
        }
        if (parentObjectiveId != null && parentObjectiveId.isBlank()) {
            throw new IllegalArgumentException(
                    "parentObjectiveId must be null or non-blank."
            );
        }
        if (dependsOnObjectiveIds.stream().anyMatch(
                dependency -> dependency == null || dependency.isBlank())) {
            throw new IllegalArgumentException(
                    "Dependencies must contain only non-blank objective identifiers."
            );
        }
        if (dependsOnObjectiveIds.contains(id)) {
            throw new IllegalArgumentException(
                    "An objective cannot depend on itself."
            );
        }
        if (new HashSet<>(dependsOnObjectiveIds).size()
                != dependsOnObjectiveIds.size()) {
            throw new IllegalArgumentException(
                    "Objective dependencies must be unique."
            );
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value;
    }
}
