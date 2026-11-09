package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.chat.UserAttachment;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.observability.RequestBudget;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
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
    private final PerformanceTelemetry performanceTelemetry;
    private final ConfirmationService confirmationService;
    private final UserContextService userContextService;
    private final int turnBudgetMillis;

    public ConversationService(
            ConversationRepository repository,
            GeminiAssistant assistant,
            PerformanceTelemetry performanceTelemetry,
            ConfirmationService confirmationService,
            UserContextService userContextService,
            @Value("${bandy.ai.turn-budget-millis:30000}")
            int turnBudgetMillis
    ) {
        this.repository = repository;
        this.assistant = assistant;
        this.performanceTelemetry = performanceTelemetry;
        this.confirmationService = confirmationService;
        this.userContextService = userContextService;
        if (turnBudgetMillis < 1) {
            throw new IllegalArgumentException(
                    "Le budget de requête doit être positif."
            );
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
        return sendMessage(conversationId, identity, message, authorization, idempotencyKey, (UserAttachment) null);
    }

    /** attachment (facultatif) part au modèle pour ce tour ; l'historique n'en garde que la mention. */
    public ChatResponse sendMessage(
            UUID conversationId,
            UserIdentity identity,
            String message,
            String authorization,
            UUID idempotencyKey,
            UserAttachment attachment
    ) {
        return send(conversationId, identity, message, authorization, idempotencyKey, null, attachment);
    }

    public ChatResponse sendMessageStreaming(
            UUID conversationId,
            UserIdentity identity,
            String message,
            String authorization,
            UUID idempotencyKey,
            Consumer<String> tokenConsumer
    ) {
        return sendMessageStreaming(conversationId, identity, message, authorization, idempotencyKey, null, tokenConsumer);
    }

    public ChatResponse sendMessageStreaming(
            UUID conversationId,
            UserIdentity identity,
            String message,
            String authorization,
            UUID idempotencyKey,
            UserAttachment attachment,
            Consumer<String> tokenConsumer
    ) {
        return send(conversationId, identity, message, authorization, idempotencyKey, tokenConsumer, attachment);
    }

    private ChatResponse send(
            UUID conversationId,
            UserIdentity identity,
            String message,
            String authorization,
            UUID idempotencyKey,
            Consumer<String> tokenConsumer,
            UserAttachment attachment
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
                    tokenConsumer,
                    attachment
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
            String typedMessage,
            String authorization,
            Consumer<String> tokenConsumer,
            UserAttachment attachment
    ) {
        // Le message enregistré (et son empreinte anti-doublon) porte la mention
        // de la pièce jointe, jamais son contenu.
        String message = attachment == null
                ? typedMessage
                : typedMessage.isBlank() ? attachment.mention() : typedMessage + "\n" + attachment.mention();
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
            // Gemini reçoit toujours l'état actif et le fil complet : c'est lui
            // qui décide si le message poursuit le sujet ou en ouvre un nouveau.
            JsonNode activeState = request.activeState();
            measured(
                    requestId,
                    "user_context",
                    () -> userContextService.ensureLoaded(
                            conversationId,
                            authorization,
                            requestId
                    )
            );
            String context = measured(
                    requestId,
                    "context_preparation",
                    () -> repository.contextForModelRequest(
                            conversationId,
                            requestId,
                            activeState
                    )
            );
            ChatResponse response = measured(
                    requestId,
                    "assistant_turn",
                    () -> assistant.respond(
                            message,
                            authorization,
                            context,
                            requestId,
                            tokenConsumer,
                            attachment
                    )
            );
            // Les tâches à confirmer reçoivent leur identifiant avant la
            // persistance : un rejeu idempotent renvoie les mêmes.
            ChatResponse completedResponse = confirmationService.registerPending(
                    conversationId,
                    requestId,
                    response.withConversationId(conversationId)
            );
            measured(
                    requestId,
                    "response_persistence",
                    () -> repository.completeRequest(
                            conversationId,
                            requestId,
                            request.claimId(),
                            completedResponse
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
            // Les pannes de Gemini ou de l'API métier arrivent déjà avec leur
            // statut précis ; ce qui reste est une erreur interne de Bandy.
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Bandy n’a pas pu traiter la demande à cause d’une erreur interne.",
                    failure
            );
        }
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
