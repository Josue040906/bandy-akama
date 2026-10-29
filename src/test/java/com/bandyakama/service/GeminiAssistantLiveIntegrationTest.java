package com.bandyakama.service;

import com.bandyakama.assistant.AssistantConfigurationRepository;
import com.bandyakama.assistant.AssistantConfigurationSnapshot;
import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.chat.ChatResponse;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(
        named = "BANDY_LIVE_GEMINI_TEST",
        matches = "true"
)
class GeminiAssistantLiveIntegrationTest {
    @Test
    @Timeout(90)
    void realGeminiSelectsTheToolReceivesItsResultAndProducesAFinalAnswer() {
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

        AssistantConfigurationRepository configurationRepository =
                mock(AssistantConfigurationRepository.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        ToolDefinition search = new ToolDefinition(
                "search_agents",
                "Recherche des agents dans SYGPERS à partir de critères.",
                "POST",
                "http://localhost/api/tools/search_agents/execute",
                List.of(new ToolParameter(
                        "query", "string", "Critère de recherche", true
                ))
        );
        search.setWhenToUse(
                "Utilise cet outil pour rechercher des agents. "
                        + "Pour le test d’intégration, si la demande contient LIVECHECK, "
                        + "appelle cet outil avec query exactement égal à LIVECHECK."
        );
        when(configurationRepository.get()).thenReturn(
                new AssistantConfigurationSnapshot(
                        new AssistantProfile(
                                "Bandy Akama",
                                "Assistant RH",
                                "Pour cette validation, la demande LIVECHECK doit "
                                        + "appeler search_agents avec query exactement "
                                        + "LIVECHECK. Attends le résultat de l’outil avant "
                                        + "de répondre. Si count vaut 2, la réponse finale "
                                        + "doit contenir VALIDATION_OK_2. N’invente aucun "
                                        + "résultat et n’annonce pas de réussite avant "
                                        + "l’exécution de l’outil."
                        ),
                        List.of(search)
                )
        );
        when(toolExecutor.execute(
                any(ToolDefinition.class),
                any(Map.class),
                any(),
                any()
        )).thenReturn(Map.of(
                "count", 2,
                "agents", List.of(
                        Map.of("matricule", "LIVE-001"),
                        Map.of("matricule", "LIVE-002")
                )
        ));

        GeminiAssistant assistant = new GeminiAssistant(
                apiKey,
                model,
                2,
                new GeminiClientFactory(30000, 1),
                configurationRepository,
                toolExecutor,
                new ConversationStateMerger(new ObjectMapper()),
                new PerformanceTelemetry(),
                new ObjectMapper()
        );

        ChatResponse response = assistant.respond(
                "LIVECHECK : recherche les agents et donne-moi le résultat.",
                "Bearer live-integration-test",
                "",
                java.util.UUID.randomUUID(),
                null
        );

        verify(toolExecutor).execute(
                eq(search),
                eq(Map.of("query", "LIVECHECK")),
                eq("Bearer live-integration-test"),
                any()
        );
        assertFalse(response.message().isBlank());
        assertTrue(
                response.message().contains("VALIDATION_OK_2"),
                "La réponse finale doit refléter le résultat réellement retourné par l’outil."
        );
    }
}
