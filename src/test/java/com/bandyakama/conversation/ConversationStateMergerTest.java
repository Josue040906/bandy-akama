package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationStateMergerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger =
            new ConversationStateMerger(objectMapper);

    @Test
    void returnsOnlyTheCompleteArgumentsProducedForTheCurrentAction()
            throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "search_agents",
                  "arguments": {
                    "criteriaJson": "{\"filters\":[{\"field\":\"direction\",\"operator\":\"EQ\",\"value\":\"Direction X\"}]}",
                    "resultMode": "LIST"
                  }
                }
                """);
        Map<String, Object> currentArguments = Map.of(
                "criteriaJson",
                "{\"filters\":[{\"field\":\"age\",\"operator\":\"GT\",\"value\":40}]}",
                "resultMode",
                "COUNT"
        );

        Map<String, Object> actual = merger.mergeToolArguments(
                previous, "search_agents", currentArguments
        );

        assertEquals(currentArguments, actual);
        JsonNode criteria = objectMapper.readTree((String) actual.get("criteriaJson"));
        assertEquals(1, criteria.path("filters").size());
        assertEquals("age", criteria.path("filters").get(0).path("field").asText());
    }

    @Test
    void doesNotCarryArgumentsFromTheSameToolWhenTheNewRequestOmitsThem() {
        JsonNode previous = objectMapper.createObjectNode()
                .put("toolName", "search_agents")
                .set("arguments", objectMapper.valueToTree(
                        Map.of("criteriaJson", "{\"filters\":[]}", "resultMode", "LIST")
                ));

        Map<String, Object> actual = merger.mergeToolArguments(
                previous, "search_agents", Map.of("resultMode", "COUNT")
        );

        assertEquals(Map.of("resultMode", "COUNT"), actual);
    }

    @Test
    void rejectsMissingCurrentArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> merger.mergeToolArguments(null, "search_agents", null));
    }
}
