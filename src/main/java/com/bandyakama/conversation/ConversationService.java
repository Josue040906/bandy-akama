package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.observability.RequestBudget;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import com.bandyakama.conversation.dialogue.ContextResolution;
import com.bandyakama.conversation.dialogue.DialogueRelation;
import com.bandyakama.conversation.dialogue.DialogueStateManager;
import com.bandyakama.conversation.task.TaskStateManager;
import com.bandyakama.conversation.task.TaskStateSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.UUID;

@Service
public class ConversationService {
    private final ConversationRepository repository;
    private final GeminiAssistant assistant;
    private final DialogueStateManager dialogueStateManager;
    private final ConversationCompletionService completionService;
    private final TaskStateManager taskStateManager;
    private final TaskStateSnapshotRepository taskStateSnapshotRepository;
    private final PerformanceTelemetry performanceTelemetry;
    private final int turnBudgetMillis;

    @Autowired
    public ConversationService(
            ConversationRepository repository,
            GeminiAssistant assistant,
            DialogueStateManager dialogueStateManager,
            ConversationCompletionService completionService,
            TaskStateManager taskStateManager,
            TaskStateSnapshotRepository taskStateSnapshotRepository,
            PerformanceTelemetry performanceTelemetry,
            @Value("${bandy.ai.turn-budget-millis:30000}")
            int turnBudgetMillis
    ) {
        this.repository = repository;
        this.assistant = assistant;
        this.dialogueStateManager = java.util.Objects.requireNonNull(
                dialogueStateManager, "dialogueStateManager must not be null."
        );
        this.completionService = completionService;
        this.taskStateManager = java.util.Objects.requireNonNull(
                taskStateManager, "taskStateManager must not be null."
        );
        this.taskStateSnapshotRepository = java.util.Objects.requireNonNull(
                taskStateSnapshotRepository, "taskStateSnapshotRepository must not be null."
        );
        this.performanceTelemetry = performanceTelemetry;
        if (turnBudgetMillis < 1) {
            throw new IllegalArgumentException("Le budget de requête doit être positif.");
        }
        this.turnBudgetMillis = turnBudgetMillis;
    }

    /** Compatibility constructor for focused unit tests. */
    public ConversationService(
            ConversationRepository repository,
            GeminiAssistant assistant,
            DialogueStateManager dialogueStateManager,
            ConversationCompletionService completionService,
            TaskStateManager taskStateManager,
            PerformanceTelemetry performanceTelemetry,
            int turnBudgetMillis
    ) {
        this.repository = repository;
        this.assistant = assistant;
        this.dialogueStateManager = java.util.Objects.requireNonNull(dialogueStateManager);
        this.completionService = completionService;
        this.taskStateManager = java.util.Objects.requireNonNull(taskStateManager);
        this.taskStateSnapshotRepository = null;
        this.performanceTelemetry = performanceTelemetry;
        if (turnBudgetMillis < 1) {
            throw new IllegalArgumentException("Le budget de requête doit être positif.");
        }
        this.turnBudgetMillis = turnBudgetMillis;
    }

    public Conversation create(UserIdentity identity) {
        return repository.create(identity.applicationId(), identity.ownerId());
    }

    public ConversationPage list(UserIdentity identity, int page, int size) {
        return repository.list(
                identity.applicationId(),
                identity.ownerId(),
                page,
                size
        );
    }

    public Conversation get(UUID conversationId, UserIdentity identity) {
        return repository.findOwned(
                        conversationId,
                        identity.applicationId(),
                        identity.ownerId()
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Conversation introuvable."
                ));
    }

    public ConversationMessagePage messages(
            UUID conversationId,
            UserIdentity identity,
            int page,
            int size
    ) {
        return repository.messages(
                conversationId,
                identity.applicationId(),
                identity.ownerId(),
                page,
                size
        );
    }

    public void rename(
            UUID conversationId,
            UserIdentity identity,
            String label
    ) {
        repository.rename(
                conversationId,
                identity.applicationId(),
                identity.ownerId(),
                label
        );
    }

    public void archive(UUID conversationId, UserIdentity identity) {
        repository.archive(
                conversationId,
                identity.applicationId(),
                identity.ownerId()
        );
    }

    public void delete(UUID conversationId, UserIdentity identity) {
        repository.delete(
                conversationId,
                identity.applicationId(),
                identity.ownerId()
        );
    }

    public ChatResponse sendMessage(
            UUID conversationId,
            UserIdentity identity,
            String message,
            String authorization,
            UUID idempotencyKey
    ) {
        return sendMessage(
                conversationId,
                identity,
                message,
                authorization,
                idempotencyKey,
                null
        );
    }

    public ChatResponse sendMessageStreaming(
            UUID conversationId,
            UserIdentity identity,
            String message,
            String authorization,
            UUID idempotencyKey,
            Consumer<String> tokenConsumer
    ) {
        return sendMessage(
                conversationId,
                identity,
                message,
                authorization,
                idempotencyKey,
                tokenConsumer
        );
    }

    private ChatResponse sendMessage(
            UUID conversationId,
            UserIdentity identity,
            String message,
            String authorization,
            UUID idempotencyKey,
            Consumer<String> tokenConsumer
    ) {
        UUID effectiveRequestId = idempotencyKey == null
                ? UUID.randomUUID()
                : idempotencyKey;
        long requestStartedAt = System.nanoTime();
        boolean succeeded = false;
        try (RequestBudget.Scope ignored = RequestBudget.open(turnBudgetMillis)) {
            UUID effectiveConversationId = conversationId == null
                    ? measured(
                            effectiveRequestId,
                            "conversation_creation",
                            () -> create(identity)
                    ).id()
                    : conversationId;
            ChatResponse response = processMessage(
                    effectiveConversationId,
                    effectiveRequestId,
                    identity,
                    message,
                    authorization,
                    tokenConsumer
            );
            succeeded = true;
            return response;
        } finally {
            performanceTelemetry.stage(
                    effectiveRequestId,
                    "conversation_request",
                    requestStartedAt,
                    succeeded
            );
        }
    }

    private ChatResponse processMessage(
            UUID conversationId,
            UUID requestId,
            UserIdentity identity,
            String message,
            String authorization,
            Consumer<String> tokenConsumer
    ) {
        ConversationRepository.RequestStart request = measured(
                requestId,
                "request_claim_and_memory",
                () -> repository.beginRequest(
                        conversationId,
                        identity.applicationId(),
                        identity.ownerId(),
                        requestId,
                        message
                )
        );
        if (request.completedResponse() != null) {
            RequestBudget.current().ifPresent(RequestBudget::remainingMillis);
            return request.completedResponse().withConversationId(conversationId);
        }

        try {
            ContextResolution contextResolution;
            DialogueRelation relation;
            boolean resetState;
            JsonNode stateForRequest;
            String decisionInstruction;

            ConversationRepository.DialogueTurnContext turnContext =
                    measured(
                            requestId,
                            "dialogue_history_loading",
                            () -> repository.dialogueTurnContextForRequest(
                                    conversationId,
                                    requestId
                            )
                    );
            contextResolution = measured(
                    requestId,
                    "dialogue_interpretation",
                    () -> dialogueStateManager.prepareTurn(
                            conversationId,
                            message,
                            turnContext.currentMessageSequenceNumber(),
                            turnContext.history(),
                            turnContext.conversationSummary()
                    )
            );
            if (contextResolution.isPlanBased()) {
                return processPlanTurn(
                        conversationId, requestId, request, message, authorization,
                        tokenConsumer, contextResolution
                );
            }
            relation = contextResolution.decision().relation();
            if (relation == DialogueRelation.NEEDS_CLARIFICATION) {
                ChatResponse clarification = new ChatResponse(
                        contextResolution.decision().clarificationQuestion(),
                        java.util.List.of(),
                        conversationId
                );
                ContextResolution resolvedClarification = contextResolution;
                measured(
                        requestId,
                        "response_persistence",
                        () -> completionService.complete(
                                conversationId,
                                requestId,
                                request.claimId(),
                                clarification,
                                java.util.Optional.of(resolvedClarification.proposedState()),
                                java.util.Optional.ofNullable(
                                        resolvedClarification.proposedState().activeObjectiveId()
                                ),
                                null
                        )
                );
                return clarification;
            }

            resetState = taskStateManager.resetsOperationalState(relation);
            JsonNode resumedStateOverride = null;
            TaskStateSnapshotRepository.Snapshot resumedSnapshot = null;
            if (relation == DialogueRelation.TOPIC_RESUMPTION
                    && taskStateSnapshotRepository != null) {
                java.util.Optional<TaskStateSnapshotRepository.Snapshot> loadedSnapshot =
                        taskStateSnapshotRepository.load(
                                conversationId,
                                contextResolution.decision().targetObjectiveId()
                        );
                resumedSnapshot = loadedSnapshot.orElse(null);
                resumedStateOverride = loadedSnapshot
                        .map(TaskStateSnapshotRepository.Snapshot::operationalState)
                        .orElse(null);
            }
            stateForRequest = relation == DialogueRelation.TOPIC_RESUMPTION
                    ? taskStateManager.forDialogueRelation(relation, resumedStateOverride)
                    : taskStateManager.forDialogueRelation(relation, request.activeState());
            if (relation == DialogueRelation.SOCIAL_TURN
                    || relation == DialogueRelation.NEW_TOPIC_REQUEST
                    || relation == DialogueRelation.TASK_COMPLETION) {
                String text = switch (relation) {
                    case SOCIAL_TURN -> greetingText(message);
                    case NEW_TOPIC_REQUEST -> "Bien sûr. Quelle est votre nouvelle question ?";
                    case TASK_COMPLETION -> "D'accord, cet objectif est maintenant marqué comme terminé. "
                            + "Vous pouvez commencer un nouvel objectif ou reprendre un objectif suspendu.";
                    default -> throw new IllegalStateException(
                            "Unexpected dialogue control relation: " + relation
                    );
                };
                ChatResponse controlResponse = new ChatResponse(
                        text, java.util.List.of(), conversationId
                ).withConversationStateReset(resetState);
                measured(
                        requestId,
                        "response_persistence",
                        () -> completionService.complete(
                                conversationId,
                                requestId,
                                request.claimId(),
                                controlResponse,
                                java.util.Optional.of(contextResolution.proposedState()),
                                java.util.Optional.ofNullable(
                                        contextResolution.proposedState().activeObjectiveId()
                                ),
                                null
                        )
                );
                return controlResponse;
            }
            decisionInstruction = " — Décision de dialogue de Bandy : "
                    + relation.name()
                    + ". Raison : "
                    + contextResolution.decision().rationale()
                    + ". Utilise l'historique et les objectifs fournis comme contexte, "
                    + "mais ne réutilise aucun résultat opérationnel si l'état transmis "
                    + "est absent."
                    + (relation == DialogueRelation.TOPIC_RESUMPTION
                    ? " Les critères restaurés proviennent d'une sauvegarde d'objectif. "
                            + "Les anciens résultats ne sont volontairement pas transmis "
                            + "car leur fraîcheur n'est pas vérifiée. Relance les outils "
                            + "nécessaires avant d'affirmer un résultat."
                            + staleResultRefreshHints(resumedSnapshot)
                    : "");

            JsonNode operationalState = stateForRequest;
            boolean includeResultReferences = operationalState != null
                    && relation != DialogueRelation.TOPIC_RESUMPTION;
            String classifiedContext = measured(
                    requestId,
                    "context_preparation",
                    () -> repository.contextForModelRequest(
                            conversationId,
                            requestId,
                            operationalState,
                            includeResultReferences
                    )
            ) + decisionInstruction;
            java.util.Optional<com.bandyakama.conversation.dialogue.DialogueState>
                    proposedDialogueState = java.util.Optional.of(
                            contextResolution.proposedState()
                    );
            ChatResponse response = measured(
                    requestId,
                    "assistant_turn",
                    () -> tokenConsumer == null
                            ? assistant.respond(
                                    message,
                                    authorization,
                                    classifiedContext,
                                    requestId,
                                    stateForRequest
                            )
                            : assistant.respond(
                                    message,
                                    authorization,
                                    classifiedContext,
                                    requestId,
                                    stateForRequest,
                                    tokenConsumer
                            )
            );
            ChatResponse completedResponse = response.withConversationId(conversationId)
                    .withConversationStateReset(resetState);
            measured(
                    requestId,
                    "response_persistence",
                    () -> completionService.complete(
                            conversationId,
                            requestId,
                            request.claimId(),
                            completedResponse,
                            proposedDialogueState,
                            java.util.Optional.ofNullable(
                                    contextResolution.proposedState().activeObjectiveId()
                            ),
                            relation == DialogueRelation.TOPIC_RESUMPTION
                                    ? stateForRequest : null
                    )
            );
            return completedResponse;
        } catch (RuntimeException failure) {
            boolean budgetExpired = RequestBudget.current()
                    .map(RequestBudget::isExpired)
                    .orElse(false);
            try {
                try (RequestBudget.Scope ignored = RequestBudget.suspend()) {
                    repository.failRequest(
                            conversationId,
                            requestId,
                            request.claimId()
                    );
                }
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (budgetExpired) {
                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Le délai maximal de traitement de la demande est dépassé.",
                        failure
                );
            }
            if (failure instanceof ResponseStatusException statusFailure) {
                throw statusFailure;
            }
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Le fournisseur IA n’a pas pu traiter la demande.",
                    failure
            );
        }
    }

    /**
     * Executes a validated multi-operation plan through the normal assistant
     * and transactional completion pipeline. The plan is authoritative for
     * objective transitions; the assistant receives every operation and the
     * complete objective graph as context.
     */
    private ChatResponse processPlanTurn(
            UUID conversationId,
            UUID requestId,
            ConversationRepository.RequestStart request,
            String message,
            String authorization,
            Consumer<String> tokenConsumer,
            ContextResolution resolution
    ) {
        com.bandyakama.conversation.dialogue.ValidatedConversationPlan plan =
                java.util.Objects.requireNonNull(resolution.plan());
        com.bandyakama.conversation.dialogue.DialogueState original =
                java.util.Objects.requireNonNull(resolution.originalState());
        com.bandyakama.conversation.dialogue.DialogueState proposed =
                resolution.proposedState();
        String previousFocus = original.activeObjectiveId();
        String nextFocus = proposed.activeObjectiveId();
        boolean focusChanged = !java.util.Objects.equals(previousFocus, nextFocus);

        TaskStateSnapshotRepository.Snapshot resumedSnapshot = null;
        JsonNode resumedStateOverride = null;
        boolean explicitlyResumingFocus = plan.orderedOperations().stream().anyMatch(operation ->
                operation.type()
                        == com.bandyakama.conversation.dialogue.ConversationPlanProposal
                        .OperationType.RESUME_OBJECTIVE
                        && java.util.Objects.equals(operation.targetObjectiveId(), nextFocus)
        );
        boolean restoringExistingFocus = nextFocus != null && focusChanged
                && original.objectives().stream().anyMatch(objective ->
                        objective.id().equals(nextFocus));
        boolean restoreFocusSnapshot = explicitlyResumingFocus || restoringExistingFocus;
        if (restoreFocusSnapshot && taskStateSnapshotRepository != null) {
            resumedSnapshot = taskStateSnapshotRepository.load(conversationId, nextFocus)
                    .orElse(null);
            resumedStateOverride = resumedSnapshot == null
                    ? null : resumedSnapshot.operationalState();
        }

        JsonNode stateForRequest;
        if (restoreFocusSnapshot) {
            stateForRequest = taskStateManager.forDialogueRelation(
                    DialogueRelation.TOPIC_RESUMPTION, resumedStateOverride
            );
        } else if (!focusChanged) {
            stateForRequest = request.activeState() == null
                    ? null : request.activeState().deepCopy();
        } else {
            stateForRequest = null;
        }

        boolean resetState = focusChanged;
        String planInstruction = buildPlanInstruction(plan, proposed)
                + (restoreFocusSnapshot
                ? "\nObjectif repris : restaure uniquement ses critères sauvegardés. "
                        + "Les références de résultats sont périmées : relance les outils "
                        + "nécessaires avant toute affirmation factuelle."
                        + staleResultRefreshHints(resumedSnapshot)
                : "")
                + "\nÉtat opérationnel transmis : "
                + (stateForRequest == null ? "aucun" : "état de l'objectif focal uniquement")
                + ". Ne mélange jamais les données opérationnelles de deux objectifs.";

        boolean hasAssistantWork = plan.orderedOperations().stream().anyMatch(operation ->
                switch (operation.type()) {
                    case CREATE_OBJECTIVE, CREATE_SUBTASK, CONTINUE_OBJECTIVE,
                            RESUME_OBJECTIVE, CORRECT_OBJECTIVE, ANSWER_CLARIFICATION,
                            SOCIAL_TURN -> true;
                    case COMPLETE_OBJECTIVE, NEW_TOPIC_REQUEST -> false;
                }
        );

        String clarificationText = plan.source().clarifications().stream()
                .map(com.bandyakama.conversation.dialogue.ConversationPlanProposal.Clarification
                        ::question)
                .collect(java.util.stream.Collectors.joining("\n"));

        ChatResponse response;
        if (!hasAssistantWork && !clarificationText.isBlank()) {
            response = new ChatResponse(clarificationText, java.util.List.of(), conversationId);
        } else if (!hasAssistantWork) {
            String controlText = plan.orderedOperations().stream()
                    .map(operation -> switch (operation.type()) {
                        case NEW_TOPIC_REQUEST ->
                                "Bien sûr. Quel sujet souhaitez-vous aborder ?";
                        case COMPLETE_OBJECTIVE ->
                                "L'objectif concerné est maintenant terminé.";
                        case SOCIAL_TURN -> "";
                        default -> "";
                    })
                    .filter(text -> !text.isBlank())
                    .collect(java.util.stream.Collectors.joining("\n"));
            response = new ChatResponse(
                    controlText.isBlank()
                            ? "Bien sûr, je vous écoute."
                            : controlText,
                    java.util.List.of(),
                    conversationId
            );
        } else {
            String classifiedContext = measured(
                    requestId,
                    "context_preparation",
                    () -> repository.contextForModelRequest(
                            conversationId,
                            requestId,
                            stateForRequest,
                            stateForRequest != null && !restoreFocusSnapshot
                    )
            ) + planInstruction
                    + (clarificationText.isBlank() ? ""
                    : "\nClarifications encore nécessaires à demander à l'utilisateur :\n"
                            + clarificationText);
            JsonNode operationalState = stateForRequest;
            response = measured(
                    requestId,
                    "assistant_turn",
                    () -> tokenConsumer == null
                            ? assistant.respond(
                                    message, authorization, classifiedContext,
                                    requestId, operationalState
                            )
                            : assistant.respond(
                                    message, authorization, classifiedContext,
                                    requestId, operationalState, tokenConsumer
                            )
            ).withConversationId(conversationId);
        }

        ChatResponse completed = response.withConversationId(conversationId)
                .withConversationStateReset(resetState);
        java.util.Optional<String> previousObjectiveToSnapshot =
                focusChanged && previousFocus != null
                        ? java.util.Optional.of(previousFocus)
                        : java.util.Optional.empty();
        JsonNode previousOperationalState = previousObjectiveToSnapshot.isPresent()
                ? request.activeState() : null;

        measured(
                requestId,
                "response_persistence",
                () -> completionService.complete(
                        conversationId,
                        requestId,
                        request.claimId(),
                        completed,
                        java.util.Optional.of(proposed),
                        java.util.Optional.ofNullable(nextFocus),
                        restoreFocusSnapshot ? stateForRequest : null,
                        previousObjectiveToSnapshot,
                        previousOperationalState
                )
        );
        return completed;
    }

    private String buildPlanInstruction(
            com.bandyakama.conversation.dialogue.ValidatedConversationPlan plan,
            com.bandyakama.conversation.dialogue.DialogueState state
    ) {
        StringBuilder context = new StringBuilder(
                "\nPlan conversationnel validé par le backend (source sémantique : Gemini)."
                        + "\nApplique les intentions distinctes sans les réduire à une décision unique."
        );
        context.append("\nOpérations validées :");
        for (var operation : plan.orderedOperations()) {
            context.append("\n- id=").append(operation.operationId())
                    .append("; type=").append(operation.type())
                    .append("; cible=").append(
                            operation.targetObjectiveId() == null
                                    ? "nouvel objectif" : operation.targetObjectiveId()
                    );
            if (operation.goal() != null) {
                context.append("; objectif=").append(operation.goal());
            }
            if (operation.summary() != null) {
                context.append("; contexte=").append(operation.summary());
            }
            if (operation.clarificationId() != null) {
                context.append("; clarification=").append(operation.clarificationId());
            }
        }
        context.append("\nÉtat des objectifs après transition :");
        for (var objective : state.objectives()) {
            context.append("\n- id=").append(objective.id())
                    .append("; statut=").append(objective.status())
                    .append("; objectif=").append(objective.goal())
                    .append("; résumé=").append(objective.summary());
            if (objective.parentObjectiveId() != null) {
                context.append("; parent=").append(objective.parentObjectiveId());
            }
            if (!objective.dependsOnObjectiveIds().isEmpty()) {
                context.append("; dépendances=").append(objective.dependsOnObjectiveIds());
            }
            if (java.util.Objects.equals(state.activeObjectiveId(), objective.id())) {
                context.append("; objectif focal");
            }
        }
        context.append("\nClarifications conservées en attente :");
        if (state.pendingClarifications().isEmpty()) {
            context.append(" aucune");
        } else {
            for (var clarification : state.pendingClarifications()) {
                context.append("\n- id=").append(clarification.clarificationId())
                        .append("; question=").append(clarification.question())
                        .append("; cible=").append(clarification.targetObjectiveId());
            }
        }
        context.append("\nRaison du plan : ").append(plan.source().rationale());
        return context.toString();
    }

    private String staleResultRefreshHints(
            TaskStateSnapshotRepository.Snapshot snapshot
    ) {
        if (snapshot == null) {
            return " Aucune sauvegarde opérationnelle valide n'a été trouvée. "
                    + "Relance les opérations nécessaires à partir de la demande et des critères disponibles.";
        }
        com.fasterxml.jackson.databind.node.ArrayNode references =
                snapshot.resultReferences();
        com.fasterxml.jackson.databind.node.ArrayNode freshness = snapshot.freshness();
        if (references.isEmpty()) {
            return " Aucun résultat antérieur n'est disponible dans la sauvegarde.";
        }
        StringBuilder hints = new StringBuilder(
                " Métadonnées de résultats antérieurs (jamais des résultats fiables; "
                        + "ne pas les présenter comme actuels) :"
        );
        for (int index = 0; index < references.size(); index++) {
            com.fasterxml.jackson.databind.JsonNode reference = references.get(index);
            com.fasterxml.jackson.databind.JsonNode metadata = freshness.get(index);
            hints.append("\n- outil=")
                    .append(reference.path("toolName").asText("inconnu"))
                    .append(", arguments=")
                    .append(reference.path("arguments").toString())
                    .append(", capturé=")
                    .append(metadata.path("capturedAt").asText("UNKNOWN"))
                    .append(", statut=STALE");
        }
        return hints.toString();
    }

    private String greetingText(String message) {
        String normalized = java.text.Normalizer.normalize(
                message.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD
        ).replaceAll("\\p{M}+", "").strip();
        return normalized.startsWith("salama") || normalized.startsWith("manao ahoana")
                ? "Salama ! Inona no azoko anampiana anao ?"
                : "Bonjour ! Comment puis-je vous aider ?";
    }

    private <T> T measured(
            UUID requestId,
            String stage,
            Supplier<T> operation
    ) {
        long startedAt = System.nanoTime();
        boolean succeeded = false;
        try {
            RequestBudget.current().ifPresent(RequestBudget::remainingMillis);
            T result = operation.get();
            succeeded = true;
            return result;
        } finally {
            performanceTelemetry.stage(
                    requestId,
                    stage,
                    startedAt,
                    succeeded
            );
        }
    }

    private void measured(
            UUID requestId,
            String stage,
            Runnable operation
    ) {
        measured(requestId, stage, () -> {
            operation.run();
            return null;
        });
    }
}
