package com.bandyakama.controller;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.Conversation;
import com.bandyakama.conversation.ConversationLabelRequest;
import com.bandyakama.conversation.ConversationMessagePage;
import com.bandyakama.conversation.ConversationPage;
import com.bandyakama.conversation.ConversationService;
import com.bandyakama.conversation.SendConversationMessageRequest;
import com.bandyakama.security.UserTokenValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationService conversationService;
    private final UserTokenValidator userTokenValidator;

    public ConversationController(
            ConversationService conversationService,
            UserTokenValidator userTokenValidator
    ) {
        this.conversationService = conversationService;
        this.userTokenValidator = userTokenValidator;
    }

    @PostMapping
    public ResponseEntity<Conversation> create(
            @RequestHeader(name = "Authorization", required = false)
            String authorization
    ) {
        var identity = userTokenValidator.validate(authorization);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.create(identity));
    }

    @GetMapping
    public ConversationPage list(
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        validatePage(page, size);
        return conversationService.list(
                userTokenValidator.validate(authorization),
                page,
                size
        );
    }

    @GetMapping("/{conversationId}")
    public Conversation get(
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @PathVariable UUID conversationId
    ) {
        return conversationService.get(
                conversationId,
                userTokenValidator.validate(authorization)
        );
    }

    @GetMapping("/{conversationId}/messages")
    public ConversationMessagePage messages(
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        validatePage(page, size);
        return conversationService.messages(
                conversationId,
                userTokenValidator.validate(authorization),
                page,
                size
        );
    }

    @PatchMapping("/{conversationId}")
    public ResponseEntity<Void> rename(
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @PathVariable UUID conversationId,
            @Valid @RequestBody ConversationLabelRequest request
    ) {
        conversationService.rename(
                conversationId,
                userTokenValidator.validate(authorization),
                request.label()
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/archive")
    public ResponseEntity<Void> archive(
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @PathVariable UUID conversationId
    ) {
        conversationService.archive(
                conversationId,
                userTokenValidator.validate(authorization)
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/messages")
    public ChatResponse sendMessage(
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @RequestHeader(name = "Idempotency-Key", required = false)
            String idempotencyKey,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendConversationMessageRequest request
    ) {
        return conversationService.sendMessage(
                conversationId,
                userTokenValidator.validate(authorization),
                request.message(),
                authorization,
                parseIdempotencyKey(idempotencyKey)
        );
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Pagination invalide : size doit être entre 1 et 100."
            );
        }
    }

    private UUID parseIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key doit être un UUID."
            );
        }
    }
}
