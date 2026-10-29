package com.bandyakama.service;

import com.bandyakama.assistant.AssistantConfigurationRepository;
import com.bandyakama.assistant.AssistantConfigurationSnapshot;
import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.ConversationPromptContext;
import com.bandyakama.conversation.ConversationStateMerger;
import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.gemini.GeminiClientFactory;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Optional live check of contextual follow-up interpretation.
 *
 * This test intentionally uses the real Gemini API, but a mocked HR tool.
 * It verifies that the second turn uses the first turn's conversation context
 * rather than treating "celles" as a standalone query.
 */
@EnabledIfEnvironmentVariable(
        named = "BANDY_LIVE_GEMINI_TEST",
        matches = "true"
)
class GeminiAssistantMultiTurnLiveIntegrationTest {
    @Test
    @Timeout(120)
    void followUpRetainsPreviousFiltersAndAddsTheNewAgeConstraint()
            throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "BANDY_LIVE_GEMINI_TEST=true exige GEMINI_API_KEY."
            );
        }
        String model = System.getenv().getOrDefault(
                "BANDY_LIVE_GEMINI_MODEL",
                "gemini-3.5-flash-lite"
        );

        ObjectMapper objectMapper = new ObjectMapper();
        AssistantConfigurationRepository configurationRepository =
                mock(AssistantConfigurationRepository.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        ToolDefinition search = new ToolDefinition(
                "search_agents",
                "Recherche des agents à partir de critères.",
                "POST",
                "http://localhost/api/tools/search_agents/execute",
                List.of(new ToolParameter(
                        "query", "string", "Critères complets de recherche", true
                ))
        );
        search.setWhenToUse(
                "Utilise cet outil pour chaque recherche d'agents. Dans ce test, "
                        + "la première demande « Cherche les femmes du Service du Personnel. » "
                        + "doit produire query exactement égal à « femmes du Service du Personnel ». "
                        + "Le suivi « Et uniquement celles de moins de 30 ans ? » désigne les mêmes "
                        + "femmes du Service du Personnel et ajoute le critère d'âge; il doit produire "
                        + "query exactement égal à « femmes du Service du Personnel de moins de 30 ans ». "
                        + "Ne réinitialise pas le contexte pour ce suivi. Attends le résultat de l'outil "
                        + "avant de répondre."
        );
        when(configurationRepository.get()).thenReturn(
                new AssistantConfigurationSnapshot(
                        new AssistantProfile(
                                "Bandy Akama",
                                "Assistant conversationnel RH",
                                "Interprète les suivis à partir de l'historique. Les arguments de l'outil "
                                        + "doivent contenir tous les critères qui restent pertinents. "
                                        + "N'invente pas de résultats et base chaque réponse sur le résultat "
                                        + "renvoyé par l'outil."
                        ),
                        List.of(search)
                )
        );
        when(toolExecutor.execute(
                any(ToolDefinition.class),
                any(Map.class),
                any(),
                any()
        )).thenReturn(
                Map.of(
                        "count", 3,
                        "summary", "Trois femmes du Service du Personnel correspondent."
                ),
                Map.of(
                        "count", 1,
                        "summary", "Une femme de moins de 30 ans correspond."
                )
        );

        GeminiAssistant assistant = new GeminiAssistant(
                apiKey,
                model,
                2,
                new GeminiClientFactory(30000, 1),
                configurationRepository,
                toolExecutor,
                new ConversationStateMerger(objectMapper),
                new PerformanceTelemetry(),
                objectMapper
        );
        String authorization = "Bearer live-multi-turn-test";
        String firstMessage = "Cherche les femmes du Service du Personnel.";
        ChatResponse firstResponse = assistant.respond(
                firstMessage,
                authorization,
                "",
                UUID.randomUUID(),
                null
        );

        String context = "Contexte métier précédent : recherche d'agents avec les filtres "
                + "sexe=F et service=Personnel. Résultat de l'outil précédent : "
                + "3 agents correspondent. Les résultats précédents sont contextuels, "
                + "pas une source pour inventer de nouveaux faits."
                + ConversationPromptContext.marker()
                + objectMapper.writeValueAsString(List.of(
                        Map.of("role", "user", "content", firstMessage),
                        Map.of("role", "assistant", "content", firstResponse.message())
                ));

        ChatResponse followUpResponse = assistant.respond(
                "Et uniquement celles de moins de 30 ans ?",
                authorization,
                context,
                UUID.randomUUID(),
                null
        );

        verify(toolExecutor).execute(
                eq(search),
                eq(Map.of("query", "femmes du Service du Personnel")),
                eq(authorization),
                any()
        );
        verify(toolExecutor).execute(
                eq(search),
                eq(Map.of("query", "femmes du Service du Personnel de moins de 30 ans")),
                eq(authorization),
                any()
        );
        assertFalse(firstResponse.message().isBlank());
        assertFalse(followUpResponse.message().isBlank());
        assertTrue(
                followUpResponse.message().toLowerCase(java.util.Locale.ROOT)
                        .contains("30")
                        || followUpResponse.message().toLowerCase(java.util.Locale.ROOT)
                        .contains("une"),
                "La réponse du suivi doit refléter le résultat filtré, pas répéter aveuglément le premier résultat."
        );
    }
}
