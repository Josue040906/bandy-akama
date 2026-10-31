package com.bandyakama.service;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.google.genai.types.FunctionCallingConfigMode;
import org.junit.jupiter.api.Test;

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
                .type().orElseThrow().toString());
    }
    private ToolDefinition organizationTool() {
        ToolDefinition tool = new ToolDefinition(
                "get_organization_info",
                "Consultation de l’organisation",
                "GET",
                "http://localhost/api/organisation",
                List.of(
                        new ToolParameter("operation", "string", "Opération", true),
                        new ToolParameter("directionId", "integer", "Identifiant de direction", false),
                        new ToolParameter("serviceId", "integer", "Identifiant de service", false),
                        new ToolParameter("query", "string", "Recherche textuelle", false)
                )
        );
        tool.setWhenToUse("Pour consulter les directions, services et postes.");
        return tool;
    }
}