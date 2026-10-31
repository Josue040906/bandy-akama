package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
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
    private final ConversationIntentResolver intentResolver;
    private final PerformanceTelemetry performanceTelemetry;
    private final int turnBudgetMillis;

    public ConversationService(
            ConversationRepository repository,
            GeminiAssistant assistant,
            ConversationIntentResolver intentResolver,
            PerformanceTelemetry performanceTelemetry,
            @Value("${bandy.ai.turn-budget-millis:30000}")
            int turnBudgetMillis
    ) {
        this.repository = repository;
        this.assistant = assistant;
        this.intentResolver = intentResolver;
        this.performanceTelemetry = performanceTelemetry;
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
            var greeting = ConversationIntentResolver.greetingResponse(message);
            if (greeting.isPresent()) {
                long greetingStartedAt = System.nanoTime();
                ChatResponse response = new ChatResponse(
                        greeting.get(),
                        java.util.List.of(),
                        conversationId
                );
                measured(
                        requestId,
                        "response_persistence",
                        () -> repository.completeRequest(
                                conversationId, requestId, request.claimId(), response
                        )
                );
                performanceTelemetry.stage(
                        requestId, "deterministic_greeting", greetingStartedAt, true
                );
                return response;
            }

            var newQuestion = ConversationIntentResolver.newQuestionResponse(message);
            if (newQuestion.isPresent()) {
                ChatResponse response = new ChatResponse(
                        newQuestion.get(), java.util.List.of(), conversationId
                ).withConversationStateReset(true);
                measured(
                        requestId, "response_persistence",
                        () -> repository.completeRequest(
                                conversationId, requestId, request.claimId(), response
                        )
                );
                return response;
            }

            String conversationContext = measured(
                    requestId,
                    "context_preparation",
                    () -> repository.contextForModelRequest(
                            conversationId, requestId, request.activeState(), true
                    )
            );
            ChatResponse response = measured(
                    requestId,
                    "assistant_turn",
                    () -> tokenConsumer == null
                            ? assistant.respond(
                                    message,
                                    authorization,
                                    conversationContext,
                                    requestId,
                                    request.activeState()
                            )
                            : assistant.respond(
                                    message,
                                    authorization,
                                    conversationContext,
                                    requestId,
                                    request.activeState(),
                                    tokenConsumer
                            )
            );
            ChatResponse completedResponse = response.withConversationId(conversationId);
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
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Le fournisseur IA n’a pas pu traiter la demande.",
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
