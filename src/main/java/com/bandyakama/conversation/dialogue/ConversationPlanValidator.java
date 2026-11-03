package com.bandyakama.conversation.dialogue;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates a model-proposed plan against the authoritative dialogue snapshot.
 *
 * <p>This component validates identifiers, references, graph structure and
 * lifecycle invariants only. It does not infer meaning from natural language
 * and does not execute any operation.</p>
 */
@Component
public class ConversationPlanValidator {

    public ValidatedConversationPlan validate(
            ContextEngineInput input,
            ConversationPlanProposal proposal
    ) {
        Objects.requireNonNull(input, "input must not be null.");
        Objects.requireNonNull(proposal, "proposal must not be null.");

        Map<String, DialogueObjective> existing = new LinkedHashMap<>();
        for (DialogueObjective objective : input.dialogueState().objectives()) {
            existing.put(objective.id(), objective);
        }

        Map<String, ConversationPlanProposal.Operation> proposed = new LinkedHashMap<>();
        for (ConversationPlanProposal.Operation operation : proposal.operations()) {
            if (proposed.putIfAbsent(operation.operationId(), operation) != null) {
                throw invalid("Identifiant d'opération dupliqué : " + operation.operationId());
            }
        }

        validatePendingClarification(input.dialogueState(), proposal);
        validateOperations(input, proposal, existing, proposed);
        validateExistingHierarchy(input.dialogueState(), existing);
        validateProposedGraphs(proposed, existing);

        return new ValidatedConversationPlan(
                proposal,
                topologicalOrder(proposed)
        );
    }

    private void validatePendingClarification(
            DialogueState state,
            ConversationPlanProposal proposal
    ) {
        PendingClarification pending = state.pendingClarification();
        if (pending == null) {
            for (ConversationPlanProposal.Operation operation : proposal.operations()) {
                if (operation.type()
                        == ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION) {
                    throw invalid("Aucune clarification n'est actuellement en attente.");
                }
            }
            return;
        }

        boolean answersPending = proposal.operations().stream().anyMatch(operation ->
                operation.type()
                        == ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION
                        && Objects.equals(
                                pending.targetObjectiveId(), operation.targetObjectiveId()
                        )
        );
        boolean allowedWithoutAnswer = proposal.operations().stream().allMatch(operation ->
                operation.type()
                        == ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION
                        || operation.type()
                        == ConversationPlanProposal.OperationType.CREATE_OBJECTIVE
                        || operation.type()
                        == ConversationPlanProposal.OperationType.COMPLETE_OBJECTIVE
                        || operation.type()
                        == ConversationPlanProposal.OperationType.SOCIAL_TURN
                        || operation.type()
                        == ConversationPlanProposal.OperationType.NEW_TOPIC_REQUEST
        );
        if (!answersPending && (!allowedWithoutAnswer || !proposal.clarifications().isEmpty())) {
            throw invalid(
                    "Une clarification est en attente : le plan doit la résoudre "
                            + "avant d'introduire une nouvelle ambiguïté ou une modification liée."
            );
        }
    }

    private void validateOperations(
            ContextEngineInput input,
            ConversationPlanProposal proposal,
            Map<String, DialogueObjective> existing,
            Map<String, ConversationPlanProposal.Operation> proposed
    ) {
        for (ConversationPlanProposal.Operation operation : proposal.operations()) {
            validateTarget(input.dialogueState(), operation, existing);
            validateReference(operation.parent(), operation, existing, proposed, true);
            for (ConversationPlanProposal.EntityReference dependency : operation.dependsOn()) {
                validateReference(dependency, operation, existing, proposed, false);
                if (dependency.scope()
                        == ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION) {
                    ConversationPlanProposal.Operation prerequisite =
                            proposed.get(dependency.id());
                    if (prerequisite.type()
                            != ConversationPlanProposal.OperationType.CREATE_OBJECTIVE
                            && prerequisite.type()
                            != ConversationPlanProposal.OperationType.CREATE_SUBTASK) {
                        throw invalid(
                                "Une dépendance proposée doit désigner une tâche créée dans ce plan."
                        );
                    }
                }
            }
            for (ConversationPlanProposal.EntityReference reference :
                    operation.dependsOn()) {
                if (reference.scope()
                        == ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION
                        && reference.id().equals(operation.operationId())) {
                    throw invalid("Une opération ne peut pas dépendre d'elle-même.");
                }
            }
        }

        for (ConversationPlanProposal.Clarification clarification : proposal.clarifications()) {
            ConversationPlanProposal.EntityReference target = clarification.target();
            if (target != null) {
                validateReference(target, null, existing, proposed, false);
                if (target.scope()
                        == ConversationPlanProposal.ReferenceScope.EXISTING_OBJECTIVE) {
                    DialogueObjective objective = existing.get(target.id());
                    if (objective.status() == DialogueObjectiveStatus.COMPLETED) {
                        throw invalid(
                                "Une clarification ne peut pas cibler un objectif terminé."
                        );
                    }
                } else {
                    ConversationPlanProposal.Operation targetOperation =
                            proposed.get(target.id());
                    if (targetOperation.type()
                            == ConversationPlanProposal.OperationType.SOCIAL_TURN
                            || targetOperation.type()
                            == ConversationPlanProposal.OperationType.NEW_TOPIC_REQUEST) {
                        throw invalid(
                                "Une clarification ne peut pas cibler une opération sans objectif."
                        );
                    }
                }
            }
        }
    }

    private void validateTarget(
            DialogueState state,
            ConversationPlanProposal.Operation operation,
            Map<String, DialogueObjective> existing
    ) {
        var type = operation.type();
        if (type == ConversationPlanProposal.OperationType.CREATE_OBJECTIVE
                || type == ConversationPlanProposal.OperationType.CREATE_SUBTASK
                || type == ConversationPlanProposal.OperationType.SOCIAL_TURN
                || type == ConversationPlanProposal.OperationType.NEW_TOPIC_REQUEST) {
            return;
        }

        if (type == ConversationPlanProposal.OperationType.ANSWER_CLARIFICATION) {
            PendingClarification pending = state.pendingClarification();
            if (pending == null || !Objects.equals(
                    pending.targetObjectiveId(), operation.targetObjectiveId())) {
                throw invalid(
                        "ANSWER_CLARIFICATION doit cibler la clarification actuellement en attente."
                );
            }
            if (operation.targetObjectiveId() != null) {
                DialogueObjective target = existing.get(operation.targetObjectiveId());
                if (target == null) {
                    throw invalid(
                            "ANSWER_CLARIFICATION cible un objectif inexistant : "
                                    + operation.targetObjectiveId()
                    );
                }
                if (target.status() == DialogueObjectiveStatus.COMPLETED) {
                    throw invalid(
                            "Une clarification ne peut pas être résolue sur un objectif terminé."
                    );
                }
            }
            return;
        }

        DialogueObjective target = existing.get(operation.targetObjectiveId());
        if (target == null) {
            throw invalid(
                    type + " cible un objectif inexistant : " + operation.targetObjectiveId()
            );
        }

        switch (type) {
            case CONTINUE_OBJECTIVE -> {
                if (target.status() != DialogueObjectiveStatus.ACTIVE) {
                    throw invalid("CONTINUE_OBJECTIVE doit cibler un objectif actif.");
                }
            }
            case RESUME_OBJECTIVE -> {
                if (target.status() != DialogueObjectiveStatus.SUSPENDED) {
                    throw invalid("RESUME_OBJECTIVE doit cibler un objectif suspendu.");
                }
            }
            case CORRECT_OBJECTIVE -> {
                if (target.status() == DialogueObjectiveStatus.COMPLETED) {
                    throw invalid("Un objectif terminé ne peut pas être corrigé.");
                }
            }
            case COMPLETE_OBJECTIVE -> {
                if (target.status() != DialogueObjectiveStatus.ACTIVE) {
                    throw invalid("COMPLETE_OBJECTIVE doit cibler un objectif actif.");
                }
            }
            default -> throw invalid("Type d'opération non pris en charge : " + type);
        }
    }

    private void validateReference(
            ConversationPlanProposal.EntityReference reference,
            ConversationPlanProposal.Operation owner,
            Map<String, DialogueObjective> existing,
            Map<String, ConversationPlanProposal.Operation> proposed,
            boolean parentReference
    ) {
        if (reference == null) {
            return;
        }

        if (reference.scope()
                == ConversationPlanProposal.ReferenceScope.EXISTING_OBJECTIVE) {
            DialogueObjective objective = existing.get(reference.id());
            if (objective == null) {
                throw invalid("Référence à un objectif inconnu : " + reference.id());
            }
            if (parentReference
                    && objective.status() == DialogueObjectiveStatus.COMPLETED) {
                throw invalid("Un objectif terminé ne peut pas être parent d'une nouvelle tâche.");
            }
            return;
        }

        ConversationPlanProposal.Operation target = proposed.get(reference.id());
        if (target == null) {
            throw invalid("Référence à une opération inconnue : " + reference.id());
        }
        if (owner != null && owner.operationId().equals(target.operationId())) {
            throw invalid("Une opération ne peut pas se référencer elle-même.");
        }
        if (parentReference
                && target.type() != ConversationPlanProposal.OperationType.CREATE_OBJECTIVE
                && target.type() != ConversationPlanProposal.OperationType.CREATE_SUBTASK) {
            throw invalid("Le parent proposé doit être une opération de création.");
        }
    }

    private void validateExistingHierarchy(
            DialogueState state,
            Map<String, DialogueObjective> existing
    ) {
        Map<String, String> parents = new HashMap<>();
        for (DialogueObjective objective : state.objectives()) {
            String parent = objective.parentObjectiveId();
            if (parent != null) {
                if (!existing.containsKey(parent)) {
                    throw invalid("La hiérarchie existante référence un parent inconnu.");
                }
                parents.put(objective.id(), parent);
            }
        }
        Set<String> complete = new HashSet<>();
        for (String objectiveId : parents.keySet()) {
            Set<String> path = new HashSet<>();
            String current = objectiveId;
            while (current != null && parents.containsKey(current)) {
                if (!path.add(current)) {
                    throw invalid("La hiérarchie existante contient un cycle.");
                }
                current = parents.get(current);
            }
            complete.addAll(path);
        }
    }

    private void validateProposedGraphs(
            Map<String, ConversationPlanProposal.Operation> proposed,
            Map<String, DialogueObjective> existing
    ) {
        Map<String, Set<String>> parentEdges = new LinkedHashMap<>();
        Map<String, Set<String>> dependencyEdges = new LinkedHashMap<>();
        Map<String, Set<String>> combinedEdges = new LinkedHashMap<>();

        for (String id : proposed.keySet()) {
            parentEdges.put(id, new HashSet<>());
            dependencyEdges.put(id, new HashSet<>());
            combinedEdges.put(id, new HashSet<>());
        }

        for (ConversationPlanProposal.Operation operation : proposed.values()) {
            ConversationPlanProposal.EntityReference parent = operation.parent();
            if (parent != null && parent.scope()
                    == ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION) {
                parentEdges.get(operation.operationId()).add(parent.id());
                combinedEdges.get(operation.operationId()).add(parent.id());
            }
            for (ConversationPlanProposal.EntityReference dependency : operation.dependsOn()) {
                if (dependency.scope()
                        == ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION) {
                    dependencyEdges.get(operation.operationId()).add(dependency.id());
                    combinedEdges.get(operation.operationId()).add(dependency.id());
                }
            }
        }

        rejectCycles(parentEdges, "La hiérarchie proposée contient un cycle.");
        rejectCycles(dependencyEdges, "Les dépendances proposées contiennent un cycle.");
        rejectCycles(combinedEdges,
                "La hiérarchie et les dépendances forment un ordre contradictoire.");
    }

    private void rejectCycles(Map<String, Set<String>> edges, String message) {
        Map<String, Integer> states = new HashMap<>();
        for (String node : edges.keySet()) {
            visit(node, edges, states, message);
        }
    }

    private void visit(
            String node,
            Map<String, Set<String>> edges,
            Map<String, Integer> states,
            String message
    ) {
        int state = states.getOrDefault(node, 0);
        if (state == 1) {
            throw invalid(message);
        }
        if (state == 2) {
            return;
        }
        states.put(node, 1);
        for (String next : edges.getOrDefault(node, Set.of())) {
            visit(next, edges, states, message);
        }
        states.put(node, 2);
    }

    private List<ConversationPlanProposal.Operation> topologicalOrder(
            Map<String, ConversationPlanProposal.Operation> proposed
    ) {
        Map<String, Set<String>> prerequisites = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String id : proposed.keySet()) {
            prerequisites.put(id, new HashSet<>());
            dependents.put(id, new HashSet<>());
            inDegree.put(id, 0);
        }

        for (ConversationPlanProposal.Operation operation : proposed.values()) {
            Set<String> required = prerequisites.get(operation.operationId());
            if (operation.parent() != null && operation.parent().scope()
                    == ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION) {
                required.add(operation.parent().id());
            }
            for (ConversationPlanProposal.EntityReference dependency : operation.dependsOn()) {
                if (dependency.scope()
                        == ConversationPlanProposal.ReferenceScope.PROPOSED_OPERATION) {
                    required.add(dependency.id());
                }
            }
            inDegree.put(operation.operationId(), required.size());
            for (String prerequisite : required) {
                dependents.get(prerequisite).add(operation.operationId());
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        inDegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted(Comparator.naturalOrder())
                .forEach(ready::addLast);

        List<ConversationPlanProposal.Operation> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            ordered.add(proposed.get(id));
            List<String> newlyReady = new ArrayList<>();
            for (String dependent : dependents.get(id)) {
                int degree = inDegree.compute(dependent, (key, value) -> value - 1);
                if (degree == 0) {
                    newlyReady.add(dependent);
                }
            }
            newlyReady.stream().sorted().forEach(ready::addLast);
        }

        if (ordered.size() != proposed.size()) {
            throw invalid("Impossible d'ordonner les opérations : dépendance circulaire.");
        }
        return ordered;
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
