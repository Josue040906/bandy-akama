package com.bandyakama.conversation.dialogue;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Untrusted, domain-neutral plan proposed by the semantic interpreter.
 *
 * <p>The proposal may contain several operations for one message. It is a
 * description of the model's interpretation, not an executable command:
 * references, dependencies and state transitions must be validated by the
 * backend before they are applied.</p>
 */
public record ConversationPlanProposal(
        String rationale,
        InterpretationConfidence confidence,
        List<Operation> operations,
        List<Clarification> clarifications
) {
    public ConversationPlanProposal {
        rationale = requireText(rationale, "rationale");
        confidence = Objects.requireNonNull(confidence, "confidence must not be null.");
        operations = List.copyOf(Objects.requireNonNull(
                operations, "operations must not be null."
        ));
        clarifications = List.copyOf(Objects.requireNonNull(
                clarifications, "clarifications must not be null."
        ));

        if (operations.isEmpty() && clarifications.isEmpty()) {
            throw new IllegalArgumentException(
                    "A plan must contain at least one operation or clarification."
            );
        }

        Set<String> operationIds = new HashSet<>();
        for (Operation operation : operations) {
            Objects.requireNonNull(operation, "operations must not contain null.");
            if (!operationIds.add(operation.operationId())) {
                throw new IllegalArgumentException("Operation identifiers must be unique.");
            }
        }

        Set<String> clarificationIds = new HashSet<>();
        for (Clarification clarification : clarifications) {
            Objects.requireNonNull(
                    clarification, "clarifications must not contain null."
            );
            if (!clarificationIds.add(clarification.clarificationId())) {
                throw new IllegalArgumentException(
                        "Clarification identifiers must be unique."
                );
            }
        }

        for (Operation operation : operations) {
            validateReference(operation.parent(), operationIds, operation.operationId());
            for (EntityReference dependency : operation.dependsOn()) {
                validateReference(dependency, operationIds, operation.operationId());
            }
        }
        for (Clarification clarification : clarifications) {
            validateReference(
                    clarification.target(), operationIds, null
            );
        }
    }

    private static void validateReference(
            EntityReference reference,
            Set<String> operationIds,
            String ownerOperationId
    ) {
        if (reference == null) {
            return;
        }
        if (reference.scope() == ReferenceScope.PROPOSED_OPERATION) {
            if (!operationIds.contains(reference.id())) {
                throw new IllegalArgumentException(
                        "A proposed-operation reference must identify an operation in this plan."
                );
            }
            if (reference.id().equals(ownerOperationId)) {
                throw new IllegalArgumentException(
                        "An operation cannot reference itself."
                );
            }
        }
    }

    public enum OperationType {
        CREATE_OBJECTIVE,
        CREATE_SUBTASK,
        CONTINUE_OBJECTIVE,
        RESUME_OBJECTIVE,
        CORRECT_OBJECTIVE,
        COMPLETE_OBJECTIVE,
        ANSWER_CLARIFICATION,
        SOCIAL_TURN,
        NEW_TOPIC_REQUEST
    }

    public enum ReferenceScope {
        PROPOSED_OPERATION,
        EXISTING_OBJECTIVE
    }

    /**
     * A reference to either an entity proposed in this plan or an existing
     * objective supplied in the dialogue snapshot.
     */
    public record EntityReference(ReferenceScope scope, String id) {
        public EntityReference {
            scope = Objects.requireNonNull(scope, "scope must not be null.");
            id = requireText(id, "reference id");
        }
    }

    /** A durable reference selected from one objective in the dialogue snapshot. */
    public record ResolvedDialogueReference(String objectiveId, String referenceId) {
        public ResolvedDialogueReference {
            objectiveId = requireText(objectiveId, "objectiveId");
            referenceId = requireText(referenceId, "referenceId");
        }
    }

    /**
     * One semantic change or newly proposed item.
     *
     * <p>operationId is local to this proposal. The backend assigns durable
     * identifiers to newly created objectives and subtasks.</p>
     */
    public record Operation(
            String operationId,
            OperationType type,
            String targetObjectiveId,
            String goal,
            String summary,
            EntityReference parent,
            List<EntityReference> dependsOn,
            List<ProposedDialogueReference> referenceMentions,
            List<ResolvedDialogueReference> resolvedReferences,
            String clarificationId
    ) {
        public Operation(
                String operationId,
                OperationType type,
                String targetObjectiveId,
                String goal,
                String summary,
                EntityReference parent,
                List<EntityReference> dependsOn,
                List<ProposedDialogueReference> referenceMentions
        ) {
            this(operationId, type, targetObjectiveId, goal, summary, parent,
                    dependsOn, referenceMentions, List.of(), null);
        }

        public Operation(
                String operationId, OperationType type, String targetObjectiveId,
                String goal, String summary, EntityReference parent,
                List<EntityReference> dependsOn,
                List<ProposedDialogueReference> referenceMentions, String clarificationId
        ) {
            this(operationId, type, targetObjectiveId, goal, summary, parent,
                    dependsOn, referenceMentions, List.of(), clarificationId);
        }

        public Operation {
            operationId = requireText(operationId, "operationId");
            type = Objects.requireNonNull(type, "type must not be null.");
            dependsOn = List.copyOf(Objects.requireNonNull(
                    dependsOn, "dependsOn must not be null."
            ));
            referenceMentions = List.copyOf(Objects.requireNonNull(
                    referenceMentions, "referenceMentions must not be null."
            ));
            resolvedReferences = List.copyOf(Objects.requireNonNull(
                    resolvedReferences, "resolvedReferences must not be null."
            ));
            if (new HashSet<>(resolvedReferences).size() != resolvedReferences.size()) {
                throw new IllegalArgumentException("Resolved references must be unique.");
            }

            if (targetObjectiveId != null && targetObjectiveId.isBlank()) {
                throw new IllegalArgumentException(
                        "targetObjectiveId must be null or non-blank."
                );
            }
            if (goal != null && goal.isBlank()) {
                throw new IllegalArgumentException("goal must be null or non-blank.");
            }
            if (summary != null && summary.isBlank()) {
                throw new IllegalArgumentException("summary must be null or non-blank.");
            }
            if (clarificationId != null && clarificationId.isBlank()) {
                throw new IllegalArgumentException(
                        "clarificationId must be null or non-blank."
                );
            }
            if (type != OperationType.ANSWER_CLARIFICATION && clarificationId != null) {
                throw new IllegalArgumentException(
                        "clarificationId is only valid for ANSWER_CLARIFICATION."
                );
            }

            switch (type) {
                case CREATE_OBJECTIVE -> {
                    requireNoTarget(targetObjectiveId, type);
                    requireText(goal, "goal");
                    requireText(summary, "summary");
                    if (parent != null) {
                        throw new IllegalArgumentException(
                                "CREATE_OBJECTIVE cannot have a parent."
                        );
                    }
                }
                case CREATE_SUBTASK -> {
                    requireNoTarget(targetObjectiveId, type);
                    requireText(goal, "goal");
                    requireText(summary, "summary");
                    Objects.requireNonNull(parent, "CREATE_SUBTASK requires a parent.");
                }
                case CONTINUE_OBJECTIVE, RESUME_OBJECTIVE -> {
                    requireTarget(targetObjectiveId, type);
                    requireText(summary, "summary");
                    if (goal != null || parent != null) {
                        throw new IllegalArgumentException(
                                type + " cannot change the goal or parent."
                        );
                    }
                }
                case ANSWER_CLARIFICATION -> {
                    requireText(summary, "summary");
                    if (goal != null || parent != null) {
                        throw new IllegalArgumentException(
                                "ANSWER_CLARIFICATION cannot change the goal or parent."
                        );
                    }
                }
                case CORRECT_OBJECTIVE -> {
                    requireTarget(targetObjectiveId, type);
                    requireText(goal, "goal");
                    requireText(summary, "summary");
                    if (parent != null) {
                        throw new IllegalArgumentException(
                                "CORRECT_OBJECTIVE cannot change the parent."
                        );
                    }
                }
                case COMPLETE_OBJECTIVE -> {
                    requireTarget(targetObjectiveId, type);
                    if (goal != null || summary != null || parent != null
                            || !dependsOn.isEmpty() || !referenceMentions.isEmpty()) {
                        throw new IllegalArgumentException(
                                "COMPLETE_OBJECTIVE cannot contain update data."
                        );
                    }
                }
                case SOCIAL_TURN, NEW_TOPIC_REQUEST -> {
                    requireNoTarget(targetObjectiveId, type);
                    if (goal != null || summary != null || parent != null
                            || !dependsOn.isEmpty() || !referenceMentions.isEmpty()) {
                        throw new IllegalArgumentException(
                                type + " cannot contain objective mutation data."
                        );
                    }
                }
            }

            Set<EntityReference> uniqueDependencies = new HashSet<>();
            for (EntityReference dependency : dependsOn) {
                Objects.requireNonNull(
                        dependency, "dependsOn must not contain null."
                );
                if (!uniqueDependencies.add(dependency)) {
                    throw new IllegalArgumentException(
                            "An operation cannot declare the same dependency twice."
                    );
                }
            }
        }
    }

    /** A localized ambiguity; clear operations in the same message remain usable. */
    public record Clarification(
            String clarificationId,
            EntityReference target,
            String question,
            String rationale
    ) {
        public Clarification {
            clarificationId = requireText(clarificationId, "clarificationId");
            question = requireText(question, "question");
            rationale = requireText(rationale, "clarification rationale");
        }
    }

    private static void requireTarget(String target, OperationType type) {
        requireText(target, type + " requires targetObjectiveId");
    }

    private static void requireNoTarget(String target, OperationType type) {
        if (target != null) {
            throw new IllegalArgumentException(type + " must not target an existing objective.");
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
