package com.bandyakama.service;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.google.genai.types.FunctionCallingConfigMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationDecisionFunctionTest {
    @Test
    void exposesOnlyTheInternalDecisionFunctionToGemini() {
        var tool = ConversationDecisionFunction.declaration(List.of(
                organizationTool()
        ));

        var declarations = tool.functionDeclarations().orElseThrow();
        assertEquals(1, declarations.size());
        assertEquals(
                ConversationDecisionFunction.NAME,
                declarations.get(0).name().orElseThrow()
        );
        assertTrue(declarations.get(0).parameters().orElseThrow()
                .properties().orElseThrow().containsKey("toolArguments"));
    }

    @Test
    void keepsOperationAtDecisionLevelAndOtherArgumentsInsideSelectedTool() {
        var schema = ConversationDecisionFunction.declaration(List.of(organizationTool()))
                .functionDeclarations().orElseThrow().get(0)
                .parameters().orElseThrow();
        var properties = schema.properties().orElseThrow();
        assertTrue(properties.containsKey("operation"));
        assertTrue(properties.get("intentType").description().orElseThrow()
                .contains("l’état actif doit être réinitialisé"));
        assertTrue(properties.get("directResponse").description().orElseThrow()
                .contains("NEW_INTENT"));
        var organizationArguments = properties.get("toolArguments")
                .properties().orElseThrow().get("get_organization_info")
                .properties().orElseThrow();
        assertTrue(organizationArguments.containsKey("directionId"));
        assertTrue(organizationArguments.get("directionId")
                .description().orElseThrow().contains("DIRECTION"));
        org.junit.jupiter.api.Assertions.assertFalse(organizationArguments.containsKey("operation"));
    }
    @Test
    void declaresAllowedValuesForOperationsAndToolParameters() {
        ToolDefinition tool = organizationTool();
        tool.getParameters().get(0).setAllowedValues(List.of(
                "LIST_DIRECTIONS", "COUNT_DIRECTIONS"
        ));
        ToolParameter resultMode = new ToolParameter(
                "resultMode", "string", "List or count", false
        );
        resultMode.setAllowedValues(List.of("LIST", "COUNT"));
        tool.getParameters().add(resultMode);

        var properties = ConversationDecisionFunction.declaration(List.of(tool))
                .functionDeclarations().orElseThrow().get(0)
                .parameters().orElseThrow().properties().orElseThrow();

        assertEquals(
                List.of("LIST_DIRECTIONS", "COUNT_DIRECTIONS"),
                properties.get("operation").enum_().orElseThrow()
        );
        var arguments = properties.get("toolArguments").properties().orElseThrow()
                .get("get_organization_info").properties().orElseThrow();
        assertEquals(
                List.of("LIST", "COUNT"),
                arguments.get("resultMode").enum_().orElseThrow()
        );
    }

    @Test
    void forcesGeminiToReturnTheStructuredDecisionFunction() {
        var config = ConversationDecisionFunction.forcedFunctionConfig();
        var functionConfig = config.functionCallingConfig().orElseThrow();

        assertEquals(
                FunctionCallingConfigMode.Known.ANY,
                functionConfig.mode().orElseThrow().knownEnum()
        );
        assertEquals(
                List.of(ConversationDecisionFunction.NAME),
                functionConfig.allowedFunctionNames().orElseThrow()
        );
    }

    @Test
    void supportsDifferentArgumentTypesForDifferentDatabaseTools() {
        ToolDefinition first = new ToolDefinition(
                "first_tool", "Premier outil", "GET",
                "http://localhost/first",
                List.of(new ToolParameter("shared", "string", "Texte", false))
        );
        first.setWhenToUse("Test");
        ToolDefinition second = new ToolDefinition(
                "second_tool", "Deuxième outil", "GET",
                "http://localhost/second",
                List.of(new ToolParameter("shared", "integer", "Nombre", false))
        );
        second.setWhenToUse("Test");

        var declaration = ConversationDecisionFunction.declaration(List.of(first, second))
                .functionDeclarations().orElseThrow().get(0);
        var toolSchemas = declaration.parameters().orElseThrow()
                .properties().orElseThrow().get("toolArguments")
                .properties().orElseThrow();
        assertEquals("STRING", toolSchemas.get("first_tool")
                .properties().orElseThrow().get("shared")
                .type().orElseThrow().knownEnum().toString());
        assertEquals("INTEGER", toolSchemas.get("second_tool")
                .properties().orElseThrow().get("shared")
                .type().orElseThrow().knownEnum().toString());
    }
    private ToolDefinition organizationTool() {
        ToolDefinition tool = new ToolDefinition(
                "get_organization_info",
                "Consultation de l’organisation",
                "GET",
                "http://localhost/api/organisation",
                new ArrayList<>(List.of(
                        new ToolParameter("operation", "string", "Opération", true),
                        new ToolParameter("directionId", "integer", "Identifiant de direction", false),
                        new ToolParameter("serviceId", "integer", "Identifiant de service", false),
                        new ToolParameter("query", "string", "Recherche textuelle", false)
                ))
        );
        tool.getParameters().get(1).setReferenceType("DIRECTION");
        tool.setWhenToUse("Pour consulter les directions, services et postes.");
        return tool;
    }
}