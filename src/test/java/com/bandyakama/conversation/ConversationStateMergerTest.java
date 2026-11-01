package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        JsonNode previous = state(
                "searchEmployees",
                """
                {"filters":[
                  {"field":"direction","operator":"EQ","value":"DGA"},
                  {"field":"sexe","operator":"EQ","value":"F"},
                  {"field":"age","operator":"LT","value":20}
                ]}
                """,
                null
        );

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "searchEmployees",
                Map.of("criteriaJson", """
                        {"filters":[{"field":"age","operator":"LT","value":30}]}
                        """)
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
        JsonNode previous = state(
                "searchEmployees",
                """
                {"filters":[
                  {"field":"service","operator":"EQ","value":"Personnel"},
                  {"field":"sexe","operator":"EQ","value":"F"}
                ]}
                """,
                null
        );

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "searchEmployees",
                Map.of("criteriaJson", """
                        {"filters":[
                          {"field":"sexe","operator":"EQ","value":"M"},
                          {"field":"service","remove":true}
                        ]}
                        """)
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
        JsonNode previous = state("searchEmployees", "{}", null);

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
    void retainsLegacyFilterMergeForBackwardCompatibility()
            throws Exception {
        JsonNode previous = state(
                "searchEmployees",
                """
                {"filters":[
                  {"field":"direction","operator":"EQ","value":"Direction X"}
                ]}
                """,
                "LIST"
        );

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "searchEmployees",
                Map.of(
                        "criteriaJson",
                        """
                        {"filters":[{"field":"age","operator":"GT","value":40}]}
                        """,
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
        assertEquals(40, filter(criteria, "age").path("value").asInt());
        assertEquals("COUNT", merged.get("resultMode"));
    }

    @Test
    void classifiesCountAndCriteriaFollowUpsButNotANewSubject() throws Exception {
        ConversationIntentResolver resolver =
                new ConversationIntentResolver();
        JsonNode activeState = state("search_agents", "{}", null);

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

    @Test
    void preservesBooleanExclusionWhenCountFollowUpContainsEmptyAst()
            throws Exception {
        JsonNode previous = state(
                "search_agents",
                """
                {
                  "must":[
                    {"field":"service","operator":"CONTAINS","value":"Personnel"}
                  ],
                  "should":[],
                  "must_not":[
                    {"field":"poste","operator":"CONTAINS","value":"Gestionnaire de dossiers"}
                  ],
                  "minimum_should_match":0
                }
                """,
                "LIST"
        );

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "search_agents",
                Map.of(
                        "criteriaJson",
                        """
                        {"must":[],"should":[],"must_not":[],"minimum_should_match":0}
                        """,
                        "resultMode",
                        "COUNT"
                )
        );
        JsonNode criteria = objectMapper.readTree(
                (String) merged.get("criteriaJson")
        );

        assertEquals(1, criteria.path("must").size());
        assertEquals(1, criteria.path("must_not").size());
        assertEquals("Personnel", criteria.path("must").get(0)
                .path("value").asText());
        assertEquals("Gestionnaire de dossiers", criteria.path("must_not").get(0)
                .path("value").asText());
        assertEquals("COUNT", merged.get("resultMode"));
    }

    @Test
    void mergesPartialBooleanAstWithoutDroppingUnmentionedGroups()
            throws Exception {
        JsonNode previous = state(
                "search_agents",
                """
                {
                  "must":[
                    {"field":"service","operator":"CONTAINS","value":"Personnel"}
                  ],
                  "should":[
                    {"field":"direction","operator":"CONTAINS","value":"Administration"}
                  ],
                  "must_not":[
                    {"field":"poste","operator":"CONTAINS","value":"Gestionnaire"}
                  ],
                  "minimum_should_match":1,
                  "limit":20,
                  "offset":0
                }
                """,
                "LIST"
        );

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "search_agents",
                Map.of(
                        "criteriaJson",
                        """
                        {
                          "must":[
                            {"field":"service","operator":"CONTAINS","value":"Personnel"},
                            {"field":"age","operator":"GT","value":40}
                          ]
                        }
                        """,
                        "resultMode",
                        "LIST"
                )
        );
        JsonNode criteria = objectMapper.readTree(
                (String) merged.get("criteriaJson")
        );

        assertEquals(2, criteria.path("must").size());
        assertEquals(1, criteria.path("should").size());
        assertEquals(1, criteria.path("must_not").size());
        assertEquals(1, criteria.path("minimum_should_match").asInt());
        assertEquals(20, criteria.path("limit").asInt());
        assertEquals(0, criteria.path("offset").asInt());
    }

    @Test
    void preservesActiveFiltersWhenListFollowUpOnlyChangesLimit() throws Exception {
        JsonNode previous = state(
                "search_agents",
                """
                {"must":[{"field":"service","operator":"CONTAINS","value":"Personnel"}],
                 "should":[],"must_not":[{"field":"poste","operator":"CONTAINS","value":"Gestionnaire de dossiers"}],
                 "minimum_should_match":0,"limit":20,"offset":0}
                """,
                "LIST"
        );
        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                "search_agents",
                Map.of(
                        "criteriaJson",
                        """
                        {"must":[],"should":[],"must_not":[],"minimum_should_match":0,
                         "limit":5,"offset":0}
                        """,
                        "resultMode",
                        "LIST"
                )
        );
        JsonNode criteria = objectMapper.readTree((String) merged.get("criteriaJson"));
        assertEquals(1, criteria.path("must").size());
        assertEquals("Personnel", criteria.path("must").get(0).path("value").asText());
        assertEquals(1, criteria.path("must_not").size());
        assertEquals(5, criteria.path("limit").asInt());
    }

    private JsonNode state(
            String toolName,
            String criteriaJson,
            String resultMode
    ) throws Exception {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("toolName", toolName);
        ObjectNode arguments = state.putObject("arguments");
        if (criteriaJson != null) {
            arguments.put("criteriaJson", criteriaJson);
        }
        if (resultMode != null) {
            arguments.put("resultMode", resultMode);
        }
        return state;
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
