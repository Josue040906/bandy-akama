package com.bandyakama.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolDestinationPolicyTest {
    @Test
    void allowsOnlyConfiguredOrigin() {
        ToolDestinationPolicy policy = new ToolDestinationPolicy(
                "http://localhost:8080,https://hr.example.org:443"
        );

        assertDoesNotThrow(() ->
                policy.validate("http://localhost:8080/api/agents"));
        assertDoesNotThrow(() ->
                policy.validate("https://hr.example.org:443/api/agents/{id}"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("http://localhost:8090/api/agents"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("http://evil.example.org/api/agents"));
    }

    @Test
    void deniesEveryDestinationWhenProductionAllowlistIsEmpty() {
        ToolDestinationPolicy policy = new ToolDestinationPolicy("");

        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("http://localhost:8080/api/agents"));
    }

    @Test
    void rejectsNonHttpSchemesAndUrlCredentials() {
        ToolDestinationPolicy policy = new ToolDestinationPolicy(
                "http://localhost:8080"
        );

        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("http://user:password@localhost:8080/api"));
    }
}
