package com.bandyakama.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolAuthorizationPolicyTest {
    @Test
    void rejectsMissingOrMalformedAuthorization() {
        assertUnauthorized(null);
        assertUnauthorized("");
        assertUnauthorized("Basic abc");
        assertUnauthorized("Bearer ");
        assertUnauthorized("Bearer    ");
    }

    @Test
    void acceptsNonEmptyBearerTokenAndLeavesTokenValidationToTheApiBoundary() {
        assertDoesNotThrow(() ->
                ToolAuthorizationPolicy.requireUserAuthorization("Bearer signed-token"));
    }

    private void assertUnauthorized(String authorization) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> ToolAuthorizationPolicy.requireUserAuthorization(authorization)
        );
        assertEquals(401, exception.getStatusCode().value());
    }
}
