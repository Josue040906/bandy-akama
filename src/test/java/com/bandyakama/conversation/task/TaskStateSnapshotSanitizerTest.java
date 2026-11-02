package com.bandyakama.conversation.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStateSnapshotSanitizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskStateSnapshotSanitizer sanitizer =
            new TaskStateSnapshotSanitizer(mapper);

    @Test
    void keepsOnlyReviewedNonIdentityAgentSearchCriteria() throws Exception {
        JsonNode arguments = mapper.readTree("""
                {
                  "criteriaJson": "{\\"filters\\":[
                    {\\"field\\":\\"service\\",\\"operator\\":\\"CONTAINS\\",\\"value\\":\\"Informatique\\"},
                    {\\"field\\":\\"classe\\",\\"operator\\":\\"EQ\\",\\"value\\":10},
                    {\\"field\\":\\"echelon\\",\\"operator\\":\\"GTE\\",\\"value\\":5},
                    {\\"field\\":\\"sexe\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"M\\"},
                    {\\"field\\":\\"matricule\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"RH12345678\\"},
                    {\\"field\\":\\"nom\\",\\"operator\\":\\"FUZZY\\",\\"value\\":\\"Rakoto\\"},
                    {\\"field\\":\\"date_naissance\\",\\"operator\\":\\"BEFORE\\",\\"value\\":\\"1990-01-01\\"},
                    {\\"field\\":\\"domaine_etude\\",\\"operator\\":\\"CONTAINS\\",\\"value\\":\\"Informatique\\"}
                  ],\\"limit\\":20,\\"offset\\":0}",
                  "resultMode":"LIST",
                  "authorization":"Bearer secret",
                  "employeeId":"RH12345678"
                }
                """);

        JsonNode safe = sanitizer.sanitizeArguments("search_agents", arguments);
        JsonNode criteria = mapper.readTree(safe.path("criteriaJson").asText());

        assertEquals("LIST", safe.path("resultMode").asText());
        assertFalse(safe.has("authorization"));
        assertFalse(safe.has("employeeId"));
        assertEquals(3, criteria.path("filters").size());
        assertEquals("service", criteria.path("filters").get(0).path("field").asText());
        assertEquals("classe", criteria.path("filters").get(1).path("field").asText());
        assertEquals("echelon", criteria.path("filters").get(2).path("field").asText());
        assertEquals(20, criteria.path("limit").asInt());
        assertEquals(0, criteria.path("offset").asInt());
        assertFalse(safe.path("criteriaJson").asText().contains("RH12345678"));
        assertFalse(safe.path("criteriaJson").asText().contains("Rakoto"));
    }

    @Test
    void removesAllArgumentsForProfileAndUnknownTools() throws Exception {
        JsonNode arguments = mapper.readTree("""
                {"query":"Rakoto","matricule":"RH12345678","email":"private@example.org"}
                """);

        assertTrue(sanitizer.sanitizeArguments("get_agent_profile", arguments).isEmpty());
        assertTrue(sanitizer.sanitizeArguments("unreviewed_tool", arguments).isEmpty());
    }

    @Test
    void organizationSnapshotKeepsScopeButNeverFreeTextQuery() throws Exception {
        JsonNode arguments = mapper.readTree("""
                {"query":"Rakoto dans le service informatique","scope":"SERVICE"}
                """);

        JsonNode safe = sanitizer.sanitizeArguments("get_organization_info", arguments);

        assertEquals("SERVICE", safe.path("scope").asText());
        assertFalse(safe.has("query"));
    }

    @Test
    void sanitizesOperationalStateInsteadOfCopyingArbitraryFields() throws Exception {
        JsonNode state = mapper.readTree("""
                {
                  "intent":"TOOL_CALL",
                  "toolName":"search_agents",
                  "profiles":{"agent":{"email":"private@example.org"}},
                  "debug":"sensitive",
                  "arguments":{
                    "criteriaJson":"{\\"filters\\":[
                      {\\"field\\":\\"service\\",\\"operator\\":\\"EQ\\",\\"value\\":\\"Informatique\\"},
                      {\\"field\\":\\"adresse\\",\\"operator\\":\\"CONTAINS\\",\\"value\\":\\"Rue privée\\"}
                    ]}"
                  }
                }
                """);

        JsonNode safe = sanitizer.sanitizeOperationalState(state);

        assertEquals("search_agents", safe.path("toolName").asText());
        assertEquals("TOOL_CALL", safe.path("intent").asText());
        assertFalse(safe.has("profiles"));
        assertFalse(safe.has("debug"));
        assertEquals(1, safe.path("criteria").path("filters").size());
        assertEquals("service", safe.path("criteria").path("filters").get(0)
                .path("field").asText());
    }
}
