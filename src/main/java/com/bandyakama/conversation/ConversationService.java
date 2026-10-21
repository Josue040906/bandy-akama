package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Supplier;
import java.util.UUID;

@Service
public class ConversationService {
    private final ConversationRepository repository;
    private final GeminiAssistant assistant;
    private final ConversationIntentResolver intentResolver;
    private final PerformanceTelemetry performanceTelemetry;

    public ConversationService(
            ConversationRepository repository,
            GeminiAssistant assistant,
            ConversationIntentResolver intentResolver,
            PerformanceTelemetry performanceTelemetry
    ) {
        this.repository = repository;
        this.assistant = assistant;
        this.intentResolver = intentResolver;
        this.performanceTelemetry = performanceTelemetry;
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

    public ChatResponse sendMessage(
            UUID conversationId,
            UserIdentity identity,
            String message,
            String authorization,
            UUID idempotencyKey
    ) {
        UUID effectiveRequestId = idempotencyKey == null
                ? UUID.randomUUID()
                : idempotencyKey;
        long requestStartedAt = System.nanoTime();
        boolean succeeded = false;
        try {
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
                    authorization
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
            String authorization
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
            return request.completedResponse().withConversationId(conversationId);
        }

        ConversationIntentResolver.Decision decision = measured(
                requestId,
                "intent_resolution",
                () -> intentResolver.resolve(message, request.activeState())
        );
        if (decision == ConversationIntentResolver.Decision.CLARIFICATION_REQUIRED) {
            ChatResponse clarification = new ChatResponse(
                    intentResolver.clarificationMessage(),
                    java.util.List.of(),
                    conversationId
            );
            measured(
                    requestId,
                    "response_persistence",
                    () -> repository.completeRequest(
                            conversationId,
                            requestId,
                            request.claimId(),
                            clarification
                    )
            );
            return clarification;
        }

        boolean resetState = decision
                == ConversationIntentResolver.Decision.NEW_INTENT;
        JsonNode stateForRequest = decision
                == ConversationIntentResolver.Decision.CONTINUATION
                ? request.activeState()
                : null;
        String classifiedContext = measured(
                requestId,
                "context_preparation",
                () -> repository.contextForRequest(
                        conversationId,
                        requestId,
                        stateForRequest,
                        decision == ConversationIntentResolver.Decision.CONTINUATION
                )
        )
                + "\n\nDécision de continuité prise par Bandy : "
                + (decision == ConversationIntentResolver.Decision.CONTINUATION
                ? "CONTINUATION. Conserve les filtres actifs non modifiés."
                : "NOUVELLE INTENTION. Ignore tous les filtres et résultats précédents "
                        + "pour décider des critères de cette demande.");
        try {
            ChatResponse response = measured(
                    requestId,
                    "assistant_turn",
                    () -> assistant.respond(
                            message,
                            authorization,
                            classifiedContext,
                            requestId,
                            stateForRequest
                    )
            ).withConversationId(conversationId)
                    .withConversationStateReset(resetState);
            measured(
                    requestId,
                    "response_persistence",
                    () -> repository.completeRequest(
                            conversationId,
                            requestId,
                            request.claimId(),
                            response
                    )
            );
            return response;
        } catch (RuntimeException failure) {
            try {
                repository.failRequest(
                        conversationId,
                        requestId,
                        request.claimId()
                );
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
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
