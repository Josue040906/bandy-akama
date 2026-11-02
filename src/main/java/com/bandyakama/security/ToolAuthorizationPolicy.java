package com.bandyakama.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Requires the authenticated user's bearer token before any Gemini-driven
 * business-tool execution, including cache hits. The destination API remains
 * responsible for authorization of the requested business operation.
 */
public final class ToolAuthorizationPolicy {
    private ToolAuthorizationPolicy() {
    }

    public static void requireUserAuthorization(String authorization) {
        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.substring("Bearer ".length()).isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Une authentification utilisateur est requise pour exécuter cet outil."
            );
        }
    }
}
