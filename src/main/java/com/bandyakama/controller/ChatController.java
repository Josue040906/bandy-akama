package com.bandyakama.controller;

import com.bandyakama.chat.ChatRequest;
import com.bandyakama.chat.ChatResponse;
import com.bandyakama.service.GeminiAssistant;
import com.bandyakama.security.UserTokenValidator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final GeminiAssistant assistant;
    private final UserTokenValidator userTokenValidator;

    public ChatController(
            GeminiAssistant assistant,
            UserTokenValidator userTokenValidator
    ) {
        this.assistant = assistant;
        this.userTokenValidator = userTokenValidator;
    }

    @PostMapping
    public ChatResponse chat(
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @Valid @RequestBody ChatRequest request
    ) {
        userTokenValidator.requireValid(authorization);
        return assistant.respond(request.message(), authorization);
    }
}
