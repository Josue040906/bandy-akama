package com.bandyakama.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDestinationPolicyTest {
    private final ToolDestinationPolicy policy = new ToolDestinationPolicy(
            List.of("http://localhost:8080", " HTTPS://Rh.Example.test ")
    );

    @Test
    void allowsOnlyTheExactConfiguredOrigins() {
        assertTrue(policy.allows("http://localhost:8080/api/profil/agent?query=x"));
        assertTrue(policy.allows("https://rh.example.test:443/api/agents"));
        assertTrue(policy.allows("http://localhost:8080/api/agents/{id}"));

        assertFalse(policy.allows("http://localhost:9090/api"));
        assertFalse(policy.allows("https://localhost:8080/api"));
        assertFalse(policy.allows("http://rh.example.test/api"));
        assertFalse(policy.allows("https://evil.test/api"));
    }

    @Test
    void refusesUrlsThatOnlyLookAllowed() {
        assertFalse(policy.allows("http://localhost:8080@evil.test/api"));
        assertFalse(policy.allows("http://localhost:8080.evil.test/api"));
        assertFalse(policy.allows("file:///etc/passwd"));
        assertFalse(policy.allows("not a url"));
    }

    @Test
    void refusesEverythingWhenNoOriginIsConfigured() {
        assertFalse(new ToolDestinationPolicy(List.of("")).allows("http://localhost:8080/api"));
    }

    @Test
    void rejectsConfigurationEntriesThatAreNotOrigins() {
        assertThrows(IllegalStateException.class,
                () -> new ToolDestinationPolicy(List.of("http://localhost:8080/api")));
        assertThrows(IllegalStateException.class,
                () -> new ToolDestinationPolicy(List.of("localhost:8080")));
    }
}
