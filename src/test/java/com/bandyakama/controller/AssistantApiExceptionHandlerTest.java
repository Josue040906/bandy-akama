package com.bandyakama.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistantApiExceptionHandlerTest {
    private final AssistantApiExceptionHandler handler =
            new AssistantApiExceptionHandler();

    @Test
    void exposesTheSafeProviderReasonWithItsHttpStatus() {
        var response = handler.handleResponseStatusException(
                new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini est temporairement indisponible ou surchargé."
                )
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                "Gemini est temporairement indisponible ou surchargé.",
                response.getBody().get("message")
        );
    }

    @Test
    void providesAFallbackWhenAnExceptionHasNoPublicReason() {
        var response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT)
        );

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        assertEquals(
                "Bandy n’a pas pu traiter cette demande.",
                response.getBody().get("message")
        );
    }
}
