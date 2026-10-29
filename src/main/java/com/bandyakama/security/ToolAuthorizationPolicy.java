package com.bandyakama.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ensures that every Gemini-driven business-tool call carries the authenticated
 * user's bearer token. The destination business API remains responsible for
 * checking the user's actual roles and operation-level permissions.
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
