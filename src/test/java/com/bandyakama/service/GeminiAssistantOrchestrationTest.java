package com.bandyakama.service;

import com.google.genai.types.GenerateContentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bandyakama.assistant.AssistantConfigurationRepository;
import com.bandyakama.assistant.AssistantConfigurationSnapshot;
import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.conversation.ConversationStateMerger;
import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.gemini.GeminiClientFactory;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiAssistantOrchestrationTest {
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
    void formatsStraightforwardStructuredSearchWithoutAnotherGeneration() {
        String reply = GeminiAssistant.simpleSearchReply(
                "Recherche les femmes de moins de 30 ans dans la DGA.",
                "searchEmployees",
                Map.of(
                        "total", 1,
                        "agents", List.of(Map.of(
                                "nom", "RAKOTO",
                                "prenom", "Miora",
                                "matricule", "12345",
                                "age", 28,
                                "service", "Finances",
                                "direction", "DGA"
                        ))
                ),
                new ObjectMapper()
        ).orElseThrow();

        assertTrue(reply.contains("Miora RAKOTO"));
        assertTrue(reply.contains("matricule: 12345"));
        assertTrue(reply.contains("âge: 28"));
        assertTrue(reply.contains("direction: DGA"));
        assertFalse(reply.contains("date_naissance"));
    }

    @Test
    void doesNotSkipSynthesisForComplexQuestionsOrUntrustedToolNames() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> result = Map.of("agents", List.of(
                Map.of("nom", "RAKOTO", "prenom", "Miora")
        ));

        assertTrue(GeminiAssistant.simpleSearchReply(
                "Explique pourquoi ces agents sont dans cette direction.",
                "searchEmployees",
                result,
                mapper
        ).isEmpty());
        assertTrue(GeminiAssistant.simpleSearchReply(
                "Recherche les agentes.",
                "unregisteredTool",
                result,
                mapper
        ).isEmpty());
    }

    @Test
    void formatsTopApproximateAgentsWithTheirRealSimilarityScores() {
        String reply = GeminiAssistant.simpleApproximateSearchReply(
                "Cherche l’agent Rakoto",
                "searchEmployeesApproximately",
                Map.of(
                        "total", 2,
                        "agents", List.of(
                                Map.of(
                                        "prenom", "Miora",
                                        "nom", "RAKOTO",
                                        "score_correspondance", 0.87,
                                        "service", "Finances"
                                ),
                                Map.of(
                                        "prenom", "Mialy",
                                        "nom", "RAKOTONDRABE",
                                        "score_correspondance", 0.63,
                                        "poste", "Gestionnaire"
                                )
                        )
                ),
                new ObjectMapper()
        ).orElseThrow();

        assertTrue(reply.contains("Miora RAKOTO | similarité : 87%"));
        assertTrue(reply.contains("Mialy RAKOTONDRABE | similarité : 63%"));
        assertTrue(reply.contains("Finances"));
        assertTrue(reply.contains("ce score n’est pas une probabilité d’identité"));
        assertTrue(reply.contains("affiner la recherche"));
    }

    @Test
    void asksForMoreInformationWhenApproximateSearchHasNoCandidates() {
        String reply = GeminiAssistant.simpleApproximateSearchReply(
                "Cherche l’agent Rakoto",
                "searchEmployeesApproximately",
                Map.of("total", 0, "agents", List.of()),
                new ObjectMapper()
        ).orElseThrow();

        assertTrue(reply.contains("aucun candidat"));
        assertTrue(reply.contains("service"));
    }

    @Test
    void executesExplicitApproximateSearchWithoutCallingGemini() {
        AssistantConfigurationRepository configurationRepository =
                mock(AssistantConfigurationRepository.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        GeminiClientFactory clientFactory = mock(GeminiClientFactory.class);
        when(clientFactory.timeoutMillis()).thenReturn(15_000);
        when(clientFactory.retryAttempts()).thenReturn(1);
        ToolDefinition tool = approximateSearchTool();
        when(configurationRepository.get()).thenReturn(configuration(tool));
        when(toolExecutor.execute(
                eq(tool),
                eq(Map.of(
                        "query", "Hery RANDRIAKOTOMANGAA",
                        "service", "Finances"
                )),
                eq("Bearer session-token"),
                eq("c19354f2-b803-4f62-9c74-3f6cfe6f55a1")
        )).thenReturn(Map.of(
                "total", 2,
                "agents", List.of(
                        Map.of(
                                "id", 1,
                                "prenom", "Hery",
                                "nom", "RANDRIAKOTOMANGA",
                                "service", "Finances",
                                "score_correspondance", 0.95
                        ),
                        Map.of(
                                "id", 2,
                                "prenom", "Hery",
                                "nom", "RAKOTOMALALA",
                                "service", "Finances",
                                "score_correspondance", 0.63
                        )
                )
        ));

        GeminiAssistant assistant = new GeminiAssistant(
                "",
                "model",
                2,
                clientFactory,
                configurationRepository,
                toolExecutor,
                mock(ConversationStateMerger.class),
                mock(PerformanceTelemetry.class),
                new ObjectMapper()
        );
        String message = "Recherche approximativement l’agent Hery "
                + "RANDRIAKOTOMANGAA du service Finances, il y a peut-être "
                + "une faute.";

        var response = assistant.respond(
                message,
                "Bearer session-token",
                "",
                UUID.fromString("c19354f2-b803-4f62-9c74-3f6cfe6f55a1"),
                null
        );

        assertTrue(response.message().contains("95%"));
        assertTrue(response.message().contains("63%"));
        assertEquals(1, response.toolResults().size());
        verify(toolExecutor).execute(
                eq(tool),
                eq(Map.of(
                        "query", "Hery RANDRIAKOTOMANGAA",
                        "service", "Finances"
                )),
                eq("Bearer session-token"),
                eq("c19354f2-b803-4f62-9c74-3f6cfe6f55a1")
        );
    }

    @Test
    void executesFilterFollowUpAgainstTheSameAgentQueryWithoutGemini()
            throws Exception {
        AssistantConfigurationRepository configurationRepository =
                mock(AssistantConfigurationRepository.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        GeminiClientFactory clientFactory = mock(GeminiClientFactory.class);
        when(clientFactory.timeoutMillis()).thenReturn(15_000);
        when(clientFactory.retryAttempts()).thenReturn(1);
        ToolDefinition tool = approximateSearchTool();
        when(configurationRepository.get()).thenReturn(configuration(tool));
        when(toolExecutor.execute(
                eq(tool),
                eq(Map.of(
                        "query", "Hery RANDRIAKOTOMANGAA",
                        "service", "Finances",
                        "poste", "gestionnaire"
                )),
                eq("Bearer session-token"),
                eq("46a6a20c-41b2-4f9f-90cb-562bc1fb4f0a")
        )).thenReturn(Map.of(
                "total", 1,
                "agents", List.of(Map.of(
                        "id", 1,
                        "prenom", "Hery",
                        "nom", "RANDRIAKOTOMANGA",
                        "service", "Finances",
                        "poste", "Gestionnaire",
                        "score_correspondance", 0.95
                ))
        ));

        GeminiAssistant assistant = new GeminiAssistant(
                "",
                "model",
                2,
                clientFactory,
                configurationRepository,
                toolExecutor,
                mock(ConversationStateMerger.class),
                mock(PerformanceTelemetry.class),
                new ObjectMapper()
        );
        var previousState = new ObjectMapper().readTree("""
                {
                  "toolName": "searchEmployeesApproximately",
                  "arguments": {
                    "query": "Hery RANDRIAKOTOMANGAA",
                    "service": "Finances"
                  }
                }
                """);

        var response = assistant.respond(
                "Et si tu filtres par le poste de gestionnaire ?",
                "Bearer session-token",
                "Historique d’une recherche approximative d’agent.",
                UUID.fromString("46a6a20c-41b2-4f9f-90cb-562bc1fb4f0a"),
                previousState
        );

        assertTrue(response.message().contains("95%"));
        verify(toolExecutor).execute(
                eq(tool),
                eq(Map.of(
                        "query", "Hery RANDRIAKOTOMANGAA",
                        "service", "Finances",
                        "poste", "gestionnaire"
                )),
                eq("Bearer session-token"),
                eq("46a6a20c-41b2-4f9f-90cb-562bc1fb4f0a")
        );
    }

    private AssistantConfigurationSnapshot configuration(ToolDefinition tool) {
        return new AssistantConfigurationSnapshot(
                new AssistantProfile("Bandy", "Assistant RH", "Répondre utilement."),
                List.of(tool)
        );
    }

    private ToolDefinition approximateSearchTool() {
        ToolDefinition tool = new ToolDefinition(
                "searchEmployeesApproximately",
                "Recherche approximative d’agents.",
                "GET",
                "http://localhost/api/recherche/agents/approximative",
                List.of(
                        parameter("query", true),
                        parameter("service", false),
                        parameter("poste", false),
                        parameter("direction", false)
                )
        );
        tool.setWhenToUse("Recherche de noms approximatifs.");
        return tool;
    }

    private ToolParameter parameter(String name, boolean required) {
        ToolParameter parameter = new ToolParameter(
                name,
                "string",
                "Filtre " + name,
                required
        );
        parameter.setLocation("query");
        return parameter;
    }
}
