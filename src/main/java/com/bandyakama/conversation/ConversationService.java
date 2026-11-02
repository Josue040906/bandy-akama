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
                    || relation == DialogueRelation.NEW_TOPIC_REQUEST) {
                String text = relation == DialogueRelation.SOCIAL_TURN
                        ? greetingText(message)
                        : "Bien sûr. Quelle est votre nouvelle question ?";
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
