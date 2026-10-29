package com.bandyakama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInvocationCacheTest {
    private final ToolInvocationCache cache =
            new ToolInvocationCache(new ObjectMapper());

    @Test
    void canonicalizesObjectKeyOrderButPreservesDifferentValues() {
        Map<String, Object> nestedFirst = new LinkedHashMap<>();
        nestedFirst.put("nom", "Rakoto");
        nestedFirst.put("service", "Personnel");
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("criteria", nestedFirst);
        first.put("limit", 10);

        Map<String, Object> nestedReordered = new LinkedHashMap<>();
        nestedReordered.put("service", "Personnel");
        nestedReordered.put("nom", "Rakoto");
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("limit", 10);
        reordered.put("criteria", nestedReordered);

        cache.put("search_agents", first, Map.of("count", 1));

        assertTrue(cache.contains("search_agents", reordered));
        assertEquals(Map.of("count", 1), cache.get("search_agents", reordered));

        nestedReordered.put("nom", "Rakotoa");
        assertFalse(cache.contains("search_agents", reordered));
    }

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
