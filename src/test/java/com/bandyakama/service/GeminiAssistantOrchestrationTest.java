package com.bandyakama.service;

import com.bandyakama.conversation.ConversationPromptContext;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ServiceTier;
import com.google.genai.types.ThinkingLevel;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
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
    void passesApiRefusalsToGeminiAsDataWithoutInterpretingThem() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        var forbidden = GeminiAssistant.apiRefusal(
                403, "{\"code\":\"FORBIDDEN\",\"message\":\"Droits insuffisants.\"}", mapper
        );
        assertEquals(false, forbidden.get("ok"));
        assertEquals(403, forbidden.get("status"));
        assertEquals(
                "Droits insuffisants.",
                ((Map<?, ?>) forbidden.get("message")).get("message")
        );

        assertEquals("Texte brut", GeminiAssistant.apiRefusal(409, "Texte brut", mapper)
                .get("message"));
        assertEquals(404, GeminiAssistant.apiRefusal(404, "", mapper).get("status"));
    }

    @Test
    void readsResponsesSavedBeforeClarificationStateWasRemoved() throws Exception {
        var saved = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"message\":\"Bonjour.\",\"toolResults\":[],\"needClarification\":true}",
                com.bandyakama.chat.ChatResponse.class
        );

        assertEquals("Bonjour.", saved.message());
        // Les réponses antérieures au canal d'actions n'en contiennent pas.
        assertTrue(saved.actions().isEmpty());
    }

    @Test
    void serializesFormFieldsOnlyForPreparedTasks() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String navigation = mapper.writeValueAsString(
                new com.bandyakama.chat.ChatResponse.Action("NAVIGATE", "open", "/agents/1", null)
        );
        String prepared = mapper.writeValueAsString(
                new com.bandyakama.chat.ChatResponse.Action(
                        "PREPARE", "prepare", "/agents/1", Map.of("telephone", "034")
                )
        );

        assertFalse(navigation.contains("fields"));
        assertTrue(prepared.contains("\"fields\":{\"telephone\":\"034\"}"));
    }


}
