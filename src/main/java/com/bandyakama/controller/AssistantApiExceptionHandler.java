package com.bandyakama.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {
        ChatController.class,
        ConversationController.class
})
public class AssistantApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(
            ResponseStatusException exception
    ) {
        String message = exception.getReason();
        if (message == null || message.isBlank()) {
            message = "Bandy n’a pas pu traiter cette demande.";
        }
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", message));
    }
}
