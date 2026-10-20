package com.bandyakama.controller;

import com.bandyakama.chat.ChatRequest;
import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.ConversationService;
import com.bandyakama.security.UserTokenValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final UserTokenValidator userTokenValidator;
    private final ConversationService conversationService;

    public ChatController(
            UserTokenValidator userTokenValidator,
            ConversationService conversationService
    ) {
        this.userTokenValidator = userTokenValidator;
        this.conversationService = conversationService;
    }

    @PostMapping
    public ChatResponse chat(
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @RequestHeader(name = "Idempotency-Key", required = false)
            String idempotencyKey,
            @Valid @RequestBody ChatRequest request
    ) {
        var identity = userTokenValidator.validate(authorization);
        return conversationService.sendMessage(
                request.conversationId(),
                identity,
                request.message(),
                authorization,
                parseIdempotencyKey(idempotencyKey)
        );
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
