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
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeminiAssistantOrchestrationTest {
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
    void declaresContextResetAsAModelControlledConversationAction() {
        Tool tool = GeminiAssistant.toGeminiTool(
                List.of(namedTool("search_agents"))
        );

        List<String> functionNames = tool.functionDeclarations()
                .orElseThrow()
                .stream()
                .map(declaration -> declaration.name().orElseThrow())
                .toList();

        assertTrue(functionNames.contains("search_agents"));
        assertTrue(functionNames.contains("reset_conversation_context"));
        assertFalse(functionNames.contains("ConversationIntentResolver"));
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
    void ignoresLeadingAssistantMessagesInHistory() {
        List<Content> history = GeminiAssistant.toGeminiHistory(List.of(
                new ConversationPromptContext.Message(
                        "assistant", "Ancienne réponse isolée."
                ),
                new ConversationPromptContext.Message(
                        "user", "Ma question."
                ),
                new ConversationPromptContext.Message(
                        "assistant", "La réponse."
                )
        ));

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role().orElseThrow());
        assertEquals("model", history.get(1).role().orElseThrow());
    }

    @Test
    void returnsEmptyHistoryWhenThereIsNoUserMessage() {
        List<Content> history = GeminiAssistant.toGeminiHistory(List.of(
                new ConversationPromptContext.Message(
                        "assistant", "Réponse isolée."
                )
        ));

        assertTrue(history.isEmpty());
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
