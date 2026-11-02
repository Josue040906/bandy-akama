package com.bandyakama.conversation;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationStateMergerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger =
            new ConversationStateMerger(objectMapper);

    @Test
    void mergesJsonObjectsAndKeyedArraysUsingParameterConfiguration()
            throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "search_records",
                  "arguments": {
                    "querySpec": "{\"filters\":[{\"field\":\"department\",\"value\":\"Finance\"},{\"field\":\"age\",\"value\":20}],\"page\":{\"size\":20,\"offset\":0}}"
                  }
                }
                """);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                jsonMergeTool("search_records", "querySpec", "field"),
                Map.of("querySpec",
                        "{\"filters\":[{\"field\":\"age\",\"value\":30}],"
                                + "\"page\":{\"offset\":20}}")
        );
        JsonNode query = objectMapper.readTree((String) merged.get("querySpec"));

        assertEquals(2, query.path("filters").size());
        assertEquals("Finance", item(query.path("filters"), "department")
                .path("value").asText());
        assertEquals(30, item(query.path("filters"), "age")
                .path("value").asInt());
        assertEquals(20, query.path("page").path("size").asInt());
        assertEquals(20, query.path("page").path("offset").asInt());
    }

    @Test
    void removesArrayItemsUsingTheConfiguredRemovalMarker() throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "catalog_search",
                  "arguments": {
                    "spec": "{\"filters\":[{\"field\":\"status\",\"value\":\"ACTIVE\"},{\"field\":\"region\",\"value\":\"NORTH\"}]}"
                  }
                }
                """);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                jsonMergeTool("catalog_search", "spec", "field"),
                Map.of("spec",
                        "{\"filters\":[{\"field\":\"region\",\"remove\":true}]}")
        );
        JsonNode spec = objectMapper.readTree((String) merged.get("spec"));

        assertEquals(1, spec.path("filters").size());
        assertEquals("status", spec.path("filters").get(0)
                .path("field").asText());
    }

    @Test
    void replacesJsonParameterByDefaultWhenNoMergePolicyIsConfigured()
            throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "document_search",
                  "arguments": {"query": "{\"scope\":\"all\",\"limit\":10}"}
                }
                """);
        ToolDefinition tool = plainTool("document_search", "query");

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                tool,
                Map.of("query", "{\"scope\":\"current\",\"limit\":5}")
        );

        assertEquals("{\"scope\":\"current\",\"limit\":5}", merged.get("query"));
    }

    @Test
    void neverCarriesArgumentsAcrossDifferentTools() throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "search_records",
                  "arguments": {"querySpec": "{\"filters\":[]}"}
                }
                """);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                jsonMergeTool("explain_policy", "querySpec", "field"),
                Map.of("topic", "leave policy")
        );

        assertEquals(Map.of("topic", "leave policy"), merged);
    }

    @Test
    void replacesArraysWhenNoMergeKeyIsConfigured() throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "search_records",
                  "arguments": {"spec": "{\"tags\":[\"a\",\"b\"]}"}
                }
                """);
        ToolDefinition tool = jsonMergeTool("search_records", "spec", null);

        Map<String, Object> merged = merger.mergeToolArguments(
                previous,
                tool,
                Map.of("spec", "{\"tags\":[\"c\"]}")
        );

        JsonNode spec = objectMapper.readTree((String) merged.get("spec"));
        assertEquals(1, spec.path("tags").size());
        assertEquals("c", spec.path("tags").get(0).asText());
    }

    @Test
    void rejectsMalformedIncomingJsonInsteadOfSilentlyDroppingPreviousState() throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "search_records",
                  "arguments": {"spec": "{\"filters\":[]}"}
                }
                """);

        assertThrows(ResponseStatusException.class, () ->
                merger.mergeToolArguments(
                        previous,
                        jsonMergeTool("search_records", "spec", "field"),
                        Map.of("spec", "{invalid")
                )
        );
    }

    private ToolDefinition jsonMergeTool(
            String name,
            String parameterName,
            String mergeKey
    ) {
        ToolParameter parameter = parameter(parameterName);
        parameter.setMergeStrategy(ToolParameter.MergeStrategy.MERGE_JSON);
        parameter.setMergeKey(mergeKey);
        parameter.setRemoveMarker("remove");
        return tool(name, parameter);
    }

    private ToolDefinition plainTool(String name, String parameterName) {
        return tool(name, parameter(parameterName));
    }

    private ToolDefinition tool(String name, ToolParameter parameter) {
        ToolDefinition tool = new ToolDefinition(
                name,
                "Generic test tool",
                "POST",
                "https://example.test/api",
                List.of(parameter)
        );
        tool.setWhenToUse("Used by generic merge tests.");
        return tool;
    }

    private ToolParameter parameter(String name) {
        return new ToolParameter(name, "string", "JSON input", false);
    }

    private JsonNode item(JsonNode array, String field) {
        for (JsonNode value : array) {
            if (field.equals(value.path("field").asText())) {
                return value;
            }
        }
        throw new AssertionError("Missing item: " + field);
    }
}
