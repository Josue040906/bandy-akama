package com.bandyakama.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolDestinationPolicyTest {
    @Test
    void allowsOnlyExplicitlyConfiguredOrigins() {
        ToolDestinationPolicy policy = new ToolDestinationPolicy(
                "http://localhost:8080,https://api.example.org:443"
        );

        assertDoesNotThrow(() ->
                policy.validate("http://localhost:8080/api/lookup"));
        assertDoesNotThrow(() ->
                policy.validate("https://api.example.org:443/v1/items/{id}"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("http://localhost:8090/api/lookup"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("https://evil.example.org/api/lookup"));
    }

    @Test
    void deniesAllDestinationsWhenAllowlistIsEmpty() {
        ToolDestinationPolicy policy = new ToolDestinationPolicy("");

        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("http://localhost:8080/api/lookup"));
    }

    @Test
    void rejectsNonHttpSchemesAndCredentials() {
        ToolDestinationPolicy policy = new ToolDestinationPolicy(
                "http://localhost:8080"
        );

        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () ->
                policy.validate("http://user:password@localhost:8080/api"));
    }

    @Test
    void permitsLoopbackOnlyForExplicitTestConstruction() {
        ToolDestinationPolicy testPolicy = new ToolDestinationPolicy("", true);
        ToolDestinationPolicy productionPolicy = new ToolDestinationPolicy("");

        assertDoesNotThrow(() ->
                testPolicy.validate("http://127.0.0.1:8080/api"));
        assertThrows(IllegalArgumentException.class, () ->
                productionPolicy.validate("http://127.0.0.1:8080/api"));
    }
}
