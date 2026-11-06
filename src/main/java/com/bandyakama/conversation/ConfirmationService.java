package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import com.bandyakama.tool.ToolActionLevel;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tâches confirmées. Pendant le tour, Gemini ne fait que proposer : la demande
 * est enregistrée en attente. Elle n'est exécutée qu'au clic « Confirmer »,
 * une seule fois, avec le jeton de l'utilisateur ; l'API décide des droits.
 */
@Service
public class ConfirmationService {
    private final PendingConfirmationRepository confirmations;
    private final ConversationRepository conversations;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;
    private final Duration timeToLive;

    public ConfirmationService(
            PendingConfirmationRepository confirmations,
            ConversationRepository conversations,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ObjectMapper objectMapper,
            @Value("${bandy.confirmations.ttl-minutes:15}") long ttlMinutes
    ) {
        this.confirmations = confirmations;
        this.conversations = conversations;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
        this.timeToLive = Duration.ofMinutes(ttlMinutes);
    }

    /** Enregistre chaque demande à confirmer du tour et lui donne son identifiant. */
    public ChatResponse registerPending(
            UUID conversationId,
            UUID requestId,
            ChatResponse response
    ) {
        if (response.actions().stream().noneMatch(ConfirmationService::awaitsRegistration)) {
            return response;
        }
        List<ChatResponse.Action> actions = response.actions().stream()
                .map(action -> {
                    if (!awaitsRegistration(action)) {
                        return action;
                    }
                    UUID id = UUID.randomUUID();
                    confirmations.create(
                            id, conversationId, requestId, action.tool(),
                            toJson(action.fields()), Instant.now().plus(timeToLive)
                    );
                    return action.withConfirmationId(id);
                })
                .toList();
        return response.withActions(actions);
    }

    public Outcome confirm(
            UUID conversationId,
            UUID confirmationId,
            UserIdentity identity,
            String authorization
    ) {
        PendingConfirmationRepository.PendingConfirmation pending =
                findOwned(conversationId, confirmationId, identity);
        // Un second clic renvoie l'issue déjà obtenue, sans réexécuter.
        if ("CONFIRMED".equals(pending.status()) || "FAILED".equals(pending.status())) {
            return new Outcome(confirmationId, pending.status(), readJson(pending.resultJson()));
        }
        if ("PENDING".equals(pending.status()) && pending.expired()) {
            confirmations.transition(confirmationId, "PENDING", "EXPIRED");
            throw notConfirmable("Cette demande a expiré ; reformulez-la pour en préparer une nouvelle.");
        }
        if (!confirmations.claimForExecution(confirmationId)) {
            throw notConfirmable("Cette demande n’est plus confirmable (déjà en cours, annulée ou expirée).");
        }

        Map<String, Object> arguments = readArguments(pending.argumentsJson());
        ToolDefinition tool = toolRegistry.findByName(pending.toolName())
                .filter(definition -> definition.getActionLevel() == ToolActionLevel.CONFIRM)
                .orElse(null);
        if (tool == null) {
            return finish(conversationId, confirmationId, pending.toolName(), arguments,
                    "FAILED", Map.of("ok", false, "message",
                            "Cette action n’est plus disponible ; rien n’a été exécuté."), false);
        }
        try {
            // La clé d'idempotence permet à l'API d'ignorer un renvoi.
            Object result = toolExecutor.execute(
                    tool, arguments, authorization, confirmationId.toString()
            );
            return finish(conversationId, confirmationId, tool.getName(), arguments,
                    "CONFIRMED", result, tool.isSensitive());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                // C'est l'API qui refuse (droits, données invalides…).
                return finish(conversationId, confirmationId, tool.getName(), arguments,
                        "FAILED", GeminiAssistant.apiRefusal(
                                exception.getStatusCode().value(),
                                exception.getResponseBodyAsString(),
                                objectMapper
                        ), false);
            }
            throw retryable(confirmationId, exception);
        } catch (RestClientException | IllegalStateException exception) {
            throw retryable(confirmationId, exception);
        }
    }

    public void cancel(UUID conversationId, UUID confirmationId, UserIdentity identity) {
        PendingConfirmationRepository.PendingConfirmation pending =
                findOwned(conversationId, confirmationId, identity);
        if ("CANCELLED".equals(pending.status())) {
            return;
        }
        if (!confirmations.transition(confirmationId, "PENDING", "CANCELLED")) {
            throw notConfirmable("Cette demande ne peut plus être annulée.");
        }
    }

    private Outcome finish(
            UUID conversationId,
            UUID confirmationId,
            String toolName,
            Map<String, Object> arguments,
            String status,
            Object result,
            boolean sensitive
    ) {
        confirmations.finish(confirmationId, status, toJson(result));
        conversations.recordConfirmationOutcome(
                conversationId, toolName, arguments, status, sensitive ? null : result
        );
        return new Outcome(confirmationId, status, result);
    }

    /**
     * Panne ou délai dépassé : on ne sait pas si l'API a exécuté. La demande
     * redevient confirmable ; un nouvel essai porte la même clé d'idempotence.
     */
    private ResponseStatusException retryable(UUID confirmationId, Exception cause) {
        confirmations.transition(confirmationId, "EXECUTING", "PENDING");
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Le service métier n’a pas pu exécuter la demande ; vous pouvez réessayer.",
                cause
        );
    }

    private PendingConfirmationRepository.PendingConfirmation findOwned(
            UUID conversationId,
            UUID confirmationId,
            UserIdentity identity
    ) {
        return confirmations.findOwned(
                confirmationId, conversationId, identity.applicationId(), identity.ownerId()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Demande de confirmation introuvable."
        ));
    }

    private static boolean awaitsRegistration(ChatResponse.Action action) {
        return "CONFIRM".equals(action.type()) && action.confirmationId() == null;
    }

    private static ResponseStatusException notConfirmable(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Valeur non sérialisable.", exception);
        }
    }

    private Object readJson(String value) {
        try {
            return value == null ? null : objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Résultat enregistré illisible.", exception);
        }
    }

    private Map<String, Object> readArguments(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Arguments enregistrés illisibles.", exception);
        }
    }

    /** Issue d'une confirmation : CONFIRMED ou FAILED, avec la réponse de l'API. */
    public record Outcome(UUID confirmationId, String status, Object result) {
    }
}
