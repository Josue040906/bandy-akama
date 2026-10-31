package com.bandyakama.service;

import com.bandyakama.conversation.ConversationPromptContext;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ServiceTier;
import com.google.genai.types.ThinkingLevel;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeminiAssistantOrchestrationTest {
    @Test
    void mapsOnlySelectedToolArgumentsAndCopiesTheTopLevelOperation() {
        ToolDefinition organizationTool = new ToolDefinition(
                "get_organization_info",
                "Consultation organisation",
                "GET",
                "http://localhost/api/organisation",
                List.of(
                        new ToolParameter("operation", "string", "Opération", true),
                        new ToolParameter("directionId", "integer", "Direction", false)
                )
        );
        Map<String, Object> rawDecision = Map.of(
                "intentType", "CONTINUATION",
                "toolName", "get_organization_info",
                "operation", "COUNT_EMPLOYEES_BY_STRUCTURE",
                "toolArguments", Map.of(
                        "get_organization_info", Map.of("directionId", 1)
                ),
                "needsClarification", false
        );

        var decision = GeminiAssistant.mapDecision(
                rawDecision,
                Map.of(organizationTool.getName(), organizationTool),
                new ObjectMapper()
        );

        assertEquals("get_organization_info", decision.toolName());
        assertEquals("COUNT_EMPLOYEES_BY_STRUCTURE", decision.operation());
        assertEquals("COUNT_EMPLOYEES_BY_STRUCTURE", decision.arguments().get("operation"));
        assertEquals(1, decision.arguments().get("directionId"));
        assertEquals(2, decision.arguments().size());
    }

    @Test
    void rejectsArgumentsForAToolThatWasNotSelected() {
        Map<String, Object> rawDecision = Map.of(
                "intentType", "CONTINUATION",
                "toolName", "search_agents",
                "toolArguments", Map.of(
                        "get_organization_info", Map.of("directionId", 1)
                ),
                "needsClarification", false
        );

        org.springframework.web.server.ResponseStatusException failure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> GeminiAssistant.mapDecision(
                                rawDecision, Map.of(), new ObjectMapper()
                        )
                );
        assertEquals(502, failure.getStatusCode().value());
    }

    @Test
    void rejectsNonObjectToolArgumentsInsteadOfSilentlyDiscardingThem() {
        Map<String, Object> rawDecision = Map.of(
                "intentType", "CONTINUATION",
                "toolName", "search_agents",
                "toolArguments", "not-an-object",
                "needsClarification", false
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> GeminiAssistant.mapDecision(
                        rawDecision, Map.of(), new ObjectMapper()
                )
        );
    }

    @Test
    void mapsGeminiQuotaOutageAndTimeoutToDistinctHttpStatuses() {
        var quota = GeminiAssistant.mapProviderFailure(
                new ApiException(429, "RESOURCE_EXHAUSTED", "quota exceeded")
        );
        var unavailable = GeminiAssistant.mapProviderFailure(
                new ApiException(503, "UNAVAILABLE", "high demand")
        );
        var timeout = GeminiAssistant.mapProviderFailure(
                new GenAiIOException(
                        "Failed to execute HTTP request.",
                        new SocketTimeoutException("Read timed out")
                )
        );

        assertEquals(429, quota.getStatusCode().value());
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals(504, timeout.getStatusCode().value());
        assertNull(GeminiAssistant.mapProviderFailure(
                new IllegalStateException("application error")
        ));
    }


    @Test
    void exposesOnlyTheExplicitlyAllowedToolsToGemini() {
        ToolDefinition oldSearchTool = namedTool("searchEmployees");
        ToolDefinition profileTool = namedTool("get_agent_profile");
        ToolDefinition searchTool = namedTool("search_agents");
        ToolDefinition organizationTool = namedTool("get_organization_info");
        profileTool.setAssistantEnabled(true);
        searchTool.setAssistantEnabled(true);
        organizationTool.setAssistantEnabled(true);

        assertEquals(
                List.of(profileTool, searchTool, organizationTool),
                GeminiAssistant.selectAllowedTools(
                        List.of(
                                oldSearchTool,
                                profileTool,
                                searchTool,
                                organizationTool
                        )
                )
        );
        assertTrue(GeminiAssistant.selectAllowedTools(
                List.of(oldSearchTool)
        ).isEmpty());
    }

    @Test
    void excludesToolsNotExplicitlyExposedToTheAssistant() {
        ToolDefinition exposed = namedTool("dynamic_tool");
        exposed.setAssistantEnabled(true);
        ToolDefinition storedButNotExposed = namedTool("legacy_tool");

        assertEquals(List.of(exposed), GeminiAssistant.selectAllowedTools(
                List.of(exposed, storedButNotExposed)
        ));
    }
    @Test
    void mapsPersistedAssistantHistoryToGeminiModelAndUserRoles() {
        List<Content> history = GeminiAssistant.toGeminiHistory(List.of(
                new ConversationPromptContext.Message(
                        "user",
                        "Parle-moi de Tahiry."
                ),
                new ConversationPromptContext.Message(
                        "assistant",
                        "Voici son profil."
                )
        ));

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role().orElseThrow());
        assertEquals("model", history.get(1).role().orElseThrow());
    }

    @Test
    void configuresLowThinkingAndAllowsExplicitPriorityInference() {
        GenerateContentConfig standard = GeminiAssistant.generationConfig(
                "Instructions courtes",
                Tool.builder().build(),
                "standard",
                "low",
                1024
        );
        GenerateContentConfig priority = GeminiAssistant.generationConfig(
                "Instructions courtes",
                Tool.builder().build(),
                "priority",
                "low",
                1024
        );

        assertEquals(
                ServiceTier.Known.STANDARD,
                standard.serviceTier().orElseThrow().knownEnum()
        );
        assertEquals(
                ServiceTier.Known.PRIORITY,
                priority.serviceTier().orElseThrow().knownEnum()
        );
        assertEquals(
                ThinkingLevel.Known.LOW,
                standard.thinkingConfig().orElseThrow()
                        .thinkingLevel().orElseThrow().knownEnum()
        );
        assertEquals(
                1024,
                standard.maxOutputTokens().orElseThrow()
        );
        assertEquals(1, standard.candidateCount().orElseThrow());
    }

    @Test
    void finalSynthesisHasNoToolsAndMustUseExecutedResults() {
        GenerateContentConfig config =
                GeminiAssistant.finalResponseConfig("Base instructions");

        assertTrue(config.tools().isEmpty());
        String finalInstruction = config.systemInstruction()
                .orElseThrow()
                .parts()
                .orElseThrow()
                .get(0)
                .text()
                .orElseThrow();
        assertTrue(finalInstruction.contains("sans appeler aucun outil"));
        assertTrue(finalInstruction.contains("résultats métier"));
        assertTrue(finalInstruction.contains("demande une précision"));
        assertFalse(finalInstruction.contains("annonce une réussite"));
    }

    @Test
    void ignoresLegacyIntentSpecificToolsWhenSelectingAllowedTools() {
        ToolDefinition legacySearchTool = namedTool("searchEmployeesApproximately");
        ToolDefinition legacyPersonalProfileTool = namedTool("getMyProfile");
        ToolDefinition profileTool = namedTool("get_agent_profile");
        ToolDefinition searchTool = namedTool("search_agents");
        ToolDefinition organizationTool = namedTool("get_organization_info");
        profileTool.setAssistantEnabled(true);
        searchTool.setAssistantEnabled(true);
        organizationTool.setAssistantEnabled(true);

        assertEquals(
                List.of(profileTool, searchTool, organizationTool),
                GeminiAssistant.selectAllowedTools(List.of(
                        legacySearchTool,
                        legacyPersonalProfileTool,
                        profileTool,
                        searchTool,
                        organizationTool
                ))
        );
        assertTrue(GeminiAssistant.selectAllowedTools(List.of(
                legacySearchTool,
                legacyPersonalProfileTool
        )).isEmpty());
    }

    @Test
    void translatesMissingAndAmbiguousAgentProfilesIntoUsableToolResults() {
        var missing = GeminiAssistant.profileLookupError(
                "get_agent_profile",
                HttpStatus.NOT_FOUND
        ).orElseThrow();
        var ambiguous = GeminiAssistant.profileLookupError(
                "get_agent_profile",
                HttpStatus.CONFLICT
        ).orElseThrow();

        assertEquals(false, missing.get("found"));
        assertTrue(missing.get("message").toString().contains("matricule"));
        assertEquals(true, ambiguous.get("ambiguous"));
        assertTrue(ambiguous.get("message").toString().contains("matricule"));
        assertTrue(GeminiAssistant.profileLookupError(
                "some_other_tool",
                HttpStatus.NOT_FOUND
        ).isEmpty());
        assertTrue(GeminiAssistant.profileLookupError(
                "get_agent_profile",
                HttpStatus.BAD_GATEWAY
        ).isEmpty());
    }


    private ToolDefinition namedTool(String name) {
        ToolDefinition tool = new ToolDefinition(
                name,
                "Outil RH " + name,
                "GET",
                "http://localhost/api/" + name,
                List.of()
        );
        tool.setWhenToUse("Utilisé pour les tests.");
        return tool;
    }

}
