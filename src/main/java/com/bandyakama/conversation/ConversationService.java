package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class ConversationService {
    private final ConversationRepository repository;
    private final GeminiAssistant assistant;
    private final ConversationIntentResolver intentResolver;

    public ConversationService(
            ConversationRepository repository,
            GeminiAssistant assistant,
            ConversationIntentResolver intentResolver
    ) {
        this.repository = repository;
        this.assistant = assistant;
        this.intentResolver = intentResolver;
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
        UUID effectiveConversationId = conversationId == null
                ? create(identity).id()
                : conversationId;
        UUID effectiveRequestId = idempotencyKey == null
                ? UUID.randomUUID()
                : idempotencyKey;

        ConversationRepository.RequestStart request = repository.beginRequest(
                effectiveConversationId,
                identity.applicationId(),
                identity.ownerId(),
                effectiveRequestId,
                message
        );
        if (request.completedResponse() != null) {
            return request.completedResponse().withConversationId(
                    effectiveConversationId
            );
        }

        ConversationIntentResolver.Decision decision =
                intentResolver.resolve(message, request.activeState());
        if (decision == ConversationIntentResolver.Decision.CLARIFICATION_REQUIRED) {
            ChatResponse clarification = new ChatResponse(
                    intentResolver.clarificationMessage(),
                    java.util.List.of(),
                    effectiveConversationId
            );
            repository.completeRequest(
                    effectiveConversationId,
                    effectiveRequestId,
                    request.claimId(),
                    clarification
            );
            return clarification;
        }

        boolean resetState = decision
                == ConversationIntentResolver.Decision.NEW_INTENT
                && request.activeState() != null
                && !request.activeState().path("toolName").asText("").isBlank();
        String classifiedContext = request.context()
                + "\n\nDécision de continuité prise par Bandy : "
                + (decision == ConversationIntentResolver.Decision.CONTINUATION
                ? "CONTINUATION. Conserve les filtres actifs non modifiés."
                : "NOUVELLE INTENTION. Ignore tous les filtres et résultats précédents "
                        + "pour décider des critères de cette demande.");
        try {
            ChatResponse response = assistant.respond(
                    message,
                    authorization,
                    classifiedContext,
                    effectiveRequestId,
                    decision == ConversationIntentResolver.Decision.CONTINUATION
                            ? request.activeState()
                            : null
            ).withConversationId(effectiveConversationId)
                    .withConversationStateReset(resetState);
            repository.completeRequest(
                    effectiveConversationId,
                    effectiveRequestId,
                    request.claimId(),
                    response
            );
            return response;
        } catch (RuntimeException failure) {
            try {
                repository.failRequest(
                        effectiveConversationId,
                        effectiveRequestId,
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
}
