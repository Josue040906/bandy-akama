package com.bandyakama.executor;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolExecutorTest {
    private final ToolExecutor executor =
            new ToolExecutor(RestClient.builder());

    @Test
    void rejectsMissingRequiredArgumentsBeforeMakingTheRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of())
        );
    }

    @Test
    void rejectsUndeclaredArgumentsBeforeMakingTheRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of(
                        "query", "agent",
                        "unexpected", "value"
                ))
        );
    }

    @Test
    void rejectsArgumentsWithTheWrongTypeBeforeMakingTheRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of("query", 42))
        );
    }

    @Test
    void rejectsBlankValuesForRequiredStringArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of("query", "  "))
        );
    }

    private ToolDefinition tool() {
        ToolDefinition tool = new ToolDefinition(
                "searchAgents",
                "Searches for agents.",
                "GET",
                "https://example.test/api/agents",
                List.of(new ToolParameter(
                        "query",
                        "string",
                        "Text to search for.",
                        true
                ))
        );
        tool.setWhenToUse("Use when looking for agents.");
        return tool;
    }
}
