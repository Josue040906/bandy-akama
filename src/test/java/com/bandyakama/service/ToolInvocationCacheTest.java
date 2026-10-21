package com.bandyakama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInvocationCacheTest {
    private final ToolInvocationCache cache =
            new ToolInvocationCache(new ObjectMapper());

    @Test
    void reusesOnlyAnIdenticalToolInvocationWithinTheTurn() {
        Map<String, Object> arguments = Map.of(
                "criteriaJson",
                "{\"filters\":[{\"field\":\"nom\",\"operator\":\"FUZZY\","
                        + "\"value\":\"Rakoto\"}]}"
        );
        Map<String, Object> changedArguments = Map.of(
                "criteriaJson",
                "{\"filters\":[{\"field\":\"nom\",\"operator\":\"FUZZY\","
                        + "\"value\":\"Rakotoa\"}]}"
        );

        assertFalse(cache.contains("searchEmployees", arguments));
        cache.put("searchEmployees", arguments, Map.of("total", 1));

        assertTrue(cache.contains("searchEmployees", arguments));
        assertFalse(cache.contains("searchEmployees", changedArguments));
        assertFalse(cache.contains("getMyProfile", arguments));
        assertEquals(
                Map.of("total", 1),
                cache.get("searchEmployees", arguments)
        );
    }
}
