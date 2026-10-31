package com.bandyakama.service;

import com.bandyakama.conversation.ConversationTurnDecision;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTurnDecisionValidatorTest {
    private final ConversationTurnDecisionValidator validator =
            new ConversationTurnDecisionValidator();
    private final ToolDefinition organizationTool = organizationTool();

    @Test
    void acceptsACompleteAllowedOrganizationAction() {
        var decision = decision("get_organization_info",
                "COUNT_EMPLOYEES_BY_STRUCTURE", Map.of("operation",
                        "COUNT_EMPLOYEES_BY_STRUCTURE", "directionId", 1));

        assertDoesNotThrow(() -> validator.validate(
                decision, Map.of(organizationTool.getName(), organizationTool),
                "", new ObjectMapper().createObjectNode()
        ));
    }

    @Test
    void rejectsAnUnknownTool() {
        var decision = decision("delete_everything", null, Map.of());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> validator.validate(decision, Map.of(), "",
                        new ObjectMapper().createObjectNode()));
    }

    @Test
    void rejectsAnUndeclaredArgument() {
        var decision = decision("get_organization_info",
                "COUNT_EMPLOYEES_BY_STRUCTURE", Map.of(
                        "operation", "COUNT_EMPLOYEES_BY_STRUCTURE",
                        "directionId", 1, "admin", true));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> validator.validate(decision,
                        Map.of(organizationTool.getName(), organizationTool),
                        "", new ObjectMapper().createObjectNode()));
    }

    @Test
    void rejectsMissingRequiredOperation() {
        var decision = decision("get_organization_info",
                "COUNT_EMPLOYEES_BY_STRUCTURE", Map.of("directionId", 1));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> validator.validate(decision,
                        Map.of(organizationTool.getName(), organizationTool),
                        "", new ObjectMapper().createObjectNode()));
    }

    @Test
    void clarificationDoesNotRequireAnAvailableTool() {
        var decision = new ConversationTurnDecision(
                ConversationTurnDecision.IntentType.CONTINUATION,
                null, null, Map.of(), null, true,
                "De quelle direction parlez-vous ?", null);
        assertDoesNotThrow(() -> validator.validate(
                decision, Map.of(), "", new ObjectMapper().createObjectNode()));
    }

    private ConversationTurnDecision decision(
            String toolName, String operation, Map<String, Object> arguments) {
        return new ConversationTurnDecision(
                ConversationTurnDecision.IntentType.NEW_INTENT,
                toolName, operation, arguments, null, false, null, null);
    }

    private ToolDefinition organizationTool() {
        ToolDefinition tool = new ToolDefinition(
                "get_organization_info", "Consultation organisation", "GET",
                "http://localhost/api/organisation", List.of(
                        new ToolParameter("operation", "string", "Opération", true),
                        new ToolParameter("directionId", "integer", "Direction", false),
                        new ToolParameter("serviceId", "integer", "Service", false)
                ));
        tool.setWhenToUse("Consulter les structures du MEF.");
        return tool;
    }
}