package com.bandyakama.conversation.dialogue;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A user objective tracked independently from the tools used to fulfil it.
 *
 * @param id stable objective identifier
 * @param goal concise description of what the user wants to achieve
 * @param summary domain-neutral summary of the relevant dialogue for this objective
 * @param status lifecycle status
 * @param parentObjectiveId optional parent objective for a related sub-objective
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 * @param references entities or items that later messages may refer to
 */
public record DialogueObjective(
        String id,
        String goal,
        String summary,
        DialogueObjectiveStatus status,
        String parentObjectiveId,
        Instant createdAt,
        Instant updatedAt,
        List<DialogueReference> references
) {
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
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value;
    }
}
