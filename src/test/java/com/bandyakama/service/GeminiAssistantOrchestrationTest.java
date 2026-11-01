package com.bandyakama.service;

import com.bandyakama.chat.ChatResponse;
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
import java.util.Map;
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


    @Test
    void returnsOnlyNavigableAndDisplaySafeFieldsForSensitiveAgentSearch() {
        Map<String, Object> projected = GeminiAssistant.clientSafeSearchResult(
                Map.of(
                        "total", 1,
                        "limit", 20,
                        "offset", 0,
                        "hasMore", false,
                        "agents", List.of(
                                Map.of(
                                        "id", 42,
                                        "matricule", "RH-0042",
                                        "nom", "RAKOTO",
                                        "prenom", "Miora",
                                        "poste", "Gestionnaire",
                                        "service", "Finances",
                                        "direction", "Administration",
                                        "score_correspondance", 0.87,
                                        "telephone", "0600000000",
                                        "adresse", "Adresse privée"
                                ),
                                Map.of(
                                        "nom", "Sans identifiant",
                                        "prenom", "Agent"
                                )
                        )
                )
        );

        assertEquals(1, projected.get("total"));
        List<?> agents = (List<?>) projected.get("agents");
        assertEquals(1, agents.size());
        Map<?, ?> agent = (Map<?, ?>) agents.get(0);
        assertEquals(42L, agent.get("id"));
        assertEquals("RAKOTO", agent.get("nom"));
        assertEquals("Miora", agent.get("prenom"));
        assertEquals(0.87, agent.get("score_correspondance"));
        assertFalse(agent.containsKey("telephone"));
        assertFalse(agent.containsKey("adresse"));
        assertFalse(agent.containsKey("matricule"));
    }

    @Test
    void modelGeneratedClarificationPersistsPendingSearchState() {
        ChatResponse response = GeminiAssistant.responseWithClarificationState(
                "Quel service souhaitez-vous cibler ?",
                List.of(),
                List.of(),
                true
        );

        assertTrue(response.needClarification());
        assertEquals("Quel service souhaitez-vous cibler ?", response.message());
        assertTrue(response.toolResults().isEmpty());
        assertEquals(1, response.toolStates().size());
        assertEquals("clarification_pending", response.toolStates().get(0).name());
        assertEquals("search_agents",
                response.toolStates().get(0).arguments().get("targetTool"));
    }

    @Test
    void clarificationResponseIsStructuredAndContainsNoToolResults() {
        ChatResponse response = ChatResponse.clarification("Précisez un service.");
        assertTrue(response.needClarification());
        assertTrue(response.toolResults().isEmpty());
        assertEquals(1, response.toolStates().size());
        assertEquals("clarification_pending", response.toolStates().get(0).name());
        assertEquals("Précisez un service.", response.message());
        assertTrue(response.withConversationId(java.util.UUID.randomUUID())
                .needClarification());
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
