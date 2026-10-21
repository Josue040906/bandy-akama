package com.bandyakama.service;

import com.google.genai.types.GenerateContentConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
