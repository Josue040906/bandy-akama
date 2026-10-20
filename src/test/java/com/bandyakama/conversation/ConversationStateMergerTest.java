package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationStateMergerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger =
            new ConversationStateMerger(objectMapper);

    @Test
    void replacesOnlyMentionedFieldsAndRetainsOtherActiveFilters()
            throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "searchEmployees",
                  "arguments": {
                    "criteriaJson": "{\\"filters\\":[{\\"field\\":\\"direction\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"DGA\\"},{\\"field\\":\\"sexe\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"F\\"},{\\"field\\":\\"age\\",\\"operator\\":\\"LT\\",\\"value\\":20}]}"
                  }
                }
                """);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "searchEmployees",
                Map.of("criteriaJson",
                        "{\"filters\":[{\"field\":\"age\","
                                + "\"operator\":\"LT\",\"value\":30}]}")
        );
        JsonNode criteria = objectMapper.readTree(
                (String) merged.get("criteriaJson")
        );

        assertEquals(3, criteria.path("filters").size());
        assertEquals("DGA", filter(criteria, "direction").path("value").asText());
        assertEquals("F", filter(criteria, "sexe").path("value").asText());
        assertEquals(30, filter(criteria, "age").path("value").asInt());
    }

    @Test
    void explicitlyRemovesFiltersAndReplacesSexWithoutDroppingService()
            throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "searchEmployees",
                  "arguments": {
                    "criteriaJson": "{\\"filters\\":[{\\"field\\":\\"service\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"Personnel\\"},{\\"field\\":\\"sexe\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"F\\"}]}"
                  }
                }
                """);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "searchEmployees",
                Map.of("criteriaJson",
                        "{\"filters\":[{\"field\":\"sexe\","
                                + "\"operator\":\"EQ\",\"value\":\"M\"},"
                                + "{\"field\":\"service\",\"remove\":true}]}")
        );
        JsonNode criteria = objectMapper.readTree(
                (String) merged.get("criteriaJson")
        );

        assertEquals(1, criteria.path("filters").size());
        assertEquals("sexe", criteria.path("filters").get(0)
                .path("field").asText());
        assertEquals("M", criteria.path("filters").get(0)
                .path("value").asText());
    }

    @Test
    void neverCarriesArgumentsAcrossDifferentToolIntents() throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "searchEmployees",
                  "arguments": {"criteriaJson": "{\\"filters\\":[]}"}
                }
                """);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "explainOrganization",
                Map.of("topic", "Personnel")
        );

        assertEquals(Map.of("topic", "Personnel"), merged);
    }

    private JsonNode filter(JsonNode criteria, String field) {
        for (JsonNode filter : criteria.path("filters")) {
            if (field.equals(filter.path("field").asText())) {
                return filter;
            }
        }
        throw new AssertionError("Missing filter: " + field);
    }
}
