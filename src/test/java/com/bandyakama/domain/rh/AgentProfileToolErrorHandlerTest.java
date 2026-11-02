package com.bandyakama.domain.rh;

import com.bandyakama.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProfileToolErrorHandlerTest {
    private final AgentProfileToolErrorHandler handler =
            new AgentProfileToolErrorHandler();

    @Test
    void translatesMissingAndAmbiguousAgentProfilesOnlyInRhAdapter() {
        ToolDefinition profileTool = new ToolDefinition(
                "get_agent_profile",
                "Agent profile",
                "GET",
                "https://rh.example.test/profile",
                List.of()
        );

        var missing = handler.handle(profileTool, HttpStatus.NOT_FOUND)
                .orElseThrow();
        var ambiguous = handler.handle(profileTool, HttpStatus.CONFLICT)
                .orElseThrow();

        assertEquals(false, ((java.util.Map<?, ?>) missing).get("found"));
        assertEquals(true, ((java.util.Map<?, ?>) ambiguous).get("ambiguous"));
        assertTrue(((java.util.Map<?, ?>) missing).get("message")
                .toString().contains("matricule"));
    }

    @Test
    void doesNotTranslateOtherToolsOrUnexpectedStatuses() {
        ToolDefinition genericTool = new ToolDefinition(
                "lookup_customer",
                "Customer lookup",
                "GET",
                "https://crm.example.test/customer",
                List.of()
        );

        assertTrue(handler.handle(genericTool, HttpStatus.NOT_FOUND).isEmpty());
        assertTrue(handler.handle(
                new ToolDefinition(
                        "get_agent_profile",
                        "Agent profile",
                        "GET",
                        "https://rh.example.test/profile",
                        List.of()
                ),
                HttpStatus.BAD_GATEWAY
        ).isEmpty());
    }
}
