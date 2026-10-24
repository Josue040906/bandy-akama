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

    @Test
    void keepsTheOriginalNameWhenAddingApproximateSearchFilters()
            throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "searchEmployeesApproximately",
                  "arguments": {"query": "RAKOTO"}
                }
                """);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "searchEmployeesApproximately",
                Map.of("query", "RAKOTO", "service", "Finances")
        );

        assertEquals("RAKOTO", merged.get("query"));
        assertEquals("Finances", merged.get("service"));
    }

    @Test
    void mergesSearchAgentCriteriaAndAllowsTheFollowUpToSwitchToCount()
            throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "search_agents",
                  "arguments": {
                    "criteriaJson": "{\\"filters\\":[{\\"field\\":\\"direction\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"Direction X\\"}]}",
                    "resultMode": "LIST"
                  }
                }
                """);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "search_agents",
                Map.of(
                        "criteriaJson",
                        "{\"filters\":[{\"field\":\"age\",\"operator\":\"GT\","
                                + "\"value\":40}]}",
                        "resultMode",
                        "COUNT"
                )
        );
        JsonNode criteria = objectMapper.readTree(
                (String) merged.get("criteriaJson")
        );

        assertEquals(2, criteria.path("filters").size());
        assertEquals(
                "Direction X",
                filter(criteria, "direction").path("value").asText()
        );
        assertEquals(
                40,
                filter(criteria, "age").path("value").asInt()
        );
        assertEquals("COUNT", merged.get("resultMode"));
    }

    @Test
    void classifiesCountAndCriteriaFollowUpsButNotANewSubject() throws Exception {
        ConversationIntentResolver resolver =
                new ConversationIntentResolver();
        JsonNode activeState = objectMapper.readTree("""
                {"toolName":"search_agents","arguments":{"criteriaJson":"{}"}}
                """);

        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Combien sont-ils ?", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Et ceux qui ont plus de 40 ans ?", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Explique-moi la procédure de reclassement.",
                        activeState
                )
        );
    }

    @Test
    void classifiesApproximateSearchFilterFollowUpsAsContinuations()
            throws Exception {
        ConversationIntentResolver resolver =
                new ConversationIntentResolver();
        JsonNode activeState = objectMapper.readTree("""
                {
                  "toolName": "searchEmployeesApproximately",
                  "arguments": {"query": "RAKOTO"}
                }
                """);

        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve(
                        "Et si tu filtres par le poste de gestionnaire ?",
                        activeState
                )
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Explique les missions du service RH.",
                        activeState
                )
        );
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
