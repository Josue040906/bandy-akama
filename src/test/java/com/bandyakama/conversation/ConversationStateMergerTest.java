package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ConversationStateMergerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationStateMerger merger =
            new ConversationStateMerger(objectMapper);

    @Test
    void toolArgumentsComeOnlyFromTheCurrentModelDecision() {
        Map<String, Object> previousArguments = Map.of(
                "criteriaJson", "{\"filters\":[{\"field\":\"service\",\"value\":\"Personnel\"}]}",
                "resultMode", "LIST"
        );
        Map<String, Object> currentArguments = Map.of(
                "criteriaJson", "{\"filters\":[{\"field\":\"age\",\"operator\":\"GT\",\"value\":40}]}",
                "resultMode", "COUNT"
        );

        Map<String, Object> actual = merger.argumentsForExecution(currentArguments);

        assertEquals(currentArguments, actual);
        assertNotSame(currentArguments, actual);
        JsonNode criteria = objectMapper.valueToTree(actual).path("criteriaJson");
        assertEquals(currentArguments.get("criteriaJson"), criteria.asText());
        assertEquals("COUNT", actual.get("resultMode"));
        assertEquals(2, previousArguments.size());
    }

    @Test
    void persistsTheExactArgumentsSelectedByTheModel() throws Exception {
        Map<String, Object> arguments = Map.of(
                "criteriaJson",
                "{\"filters\":[{\"field\":\"age\",\"operator\":\"GT\",\"value\":40}]}"
        );

        JsonNode state = merger.stateAfterToolCall("search_agents", arguments);

        assertEquals("search_agents", state.path("toolName").asText());
        assertEquals("GT", state.path("criteria").path("filters").get(0)
                .path("operator").asText());
        assertEquals(arguments.get("criteriaJson"),
                state.path("arguments").path("criteriaJson").asText());
    }
}
