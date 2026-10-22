package com.bandyakama.service;

import com.google.genai.types.GenerateContentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
