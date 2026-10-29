package com.bandyakama.service;

import com.bandyakama.assistant.AssistantConfigurationRepository;
import com.bandyakama.assistant.AssistantConfigurationSnapshot;
import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.ConversationStateMerger;
import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.tool.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.genai.Chat;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeminiAssistantFullLoopTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AssistantConfigurationRepository configurationRepository =
            mock(AssistantConfigurationRepository.class);
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private final Chat chat = mock(Chat.class);
    private GeminiAssistant assistant;

    @BeforeEach
    void setUp() {
        ToolDefinition search = new ToolDefinition(
                "search_agents",
                "Recherche des agents",
                "POST",
                "http://localhost/api/tools/search_agents/execute",
                List.of(new com.bandyakama.tool.ToolParameter(
                        "query", "string", "Critère de recherche", true
                ))
        );
        search.setWhenToUse("Quand une recherche d'agents est demandée.");
        search.setApprovedForGemini(true);
        when(configurationRepository.get()).thenReturn(
                new AssistantConfigurationSnapshot(
                        new AssistantProfile(
                                "Bandy Akama",
                                "Assistant RH",
                                "Réponds à partir des résultats métier."
                        ),
                        List.of(search)
                )
        );
        BiFunction<String, GenerateContentConfig, Chat> factory =
                (model, config) -> chat;
        assistant = new GeminiAssistant(
                "test-api-key",
                "test-model",
                2,
                "standard",
                "low",
                1024,
                15000,
                1,
                factory,
                configurationRepository,
                toolExecutor,
                new ConversationStateMerger(objectMapper),
                new PerformanceTelemetry(),
                objectMapper
        );
    }

    @Test
    void rejectsToolExecutionWithoutUserAuthorization() {
        FunctionCall call = mock(FunctionCall.class);
        when(call.name()).thenReturn(Optional.of("search_agents"));
        when(call.args()).thenReturn(Optional.of(Map.of("query", "agents")));
        when(call.id()).thenReturn(Optional.empty());

        Part callPart = mock(Part.class);
        when(callPart.functionCall()).thenReturn(Optional.of(call));
        GenerateContentResponse toolCallResponse = mock(GenerateContentResponse.class);
        when(toolCallResponse.parts()).thenReturn(ImmutableList.of(callPart));
        when(toolCallResponse.usageMetadata()).thenReturn(Optional.empty());
        when(chat.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(toolCallResponse);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> assistant.respond(
                        "Cherche les agents.",
                        null,
                        "",
                        java.util.UUID.randomUUID(),
                        null
                )
        );

        assertEquals(401, exception.getStatusCode().value());
        verify(toolExecutor, never()).execute(
                any(ToolDefinition.class),
                any(Map.class),
                any(),
                any()
        );
    }

    @Test
    void executesModelToolReturnsItsResultToGeminiAndUsesFinalAnswer() {
        FunctionCall call = mock(FunctionCall.class);
        when(call.name()).thenReturn(Optional.of("search_agents"));
        when(call.args()).thenReturn(Optional.of(Map.of("query", "agents du Personnel")));
        when(call.id()).thenReturn(Optional.empty());

        Part callPart = mock(Part.class);
        when(callPart.functionCall()).thenReturn(Optional.of(call));
        GenerateContentResponse toolCallResponse = mock(GenerateContentResponse.class);
        when(toolCallResponse.parts()).thenReturn(ImmutableList.of(callPart));
        when(toolCallResponse.usageMetadata()).thenReturn(Optional.empty());

        Part textPart = mock(Part.class);
        when(textPart.functionCall()).thenReturn(Optional.empty());
        GenerateContentResponse finalResponse = mock(GenerateContentResponse.class);
        when(finalResponse.parts()).thenReturn(ImmutableList.of(textPart));
        when(finalResponse.usageMetadata()).thenReturn(Optional.empty());
        when(finalResponse.text()).thenReturn(
                "J’ai trouvé deux agents dans le Service du Personnel."
        );
        when(chat.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(toolCallResponse);
        when(chat.sendMessage(any(Content.class), any(GenerateContentConfig.class)))
                .thenReturn(finalResponse);
        when(toolExecutor.execute(
                any(ToolDefinition.class),
                any(Map.class),
                any(),
                any()
        )).thenReturn(Map.of(
                "count", 2,
                "agents", List.of(
                        Map.of("matricule", "AG-001"),
                        Map.of("matricule", "AG-002")
                )
        ));

        ChatResponse response = assistant.respond(
                "Cherche les agents du Personnel.",
                "Bearer test-token",
                "",
                java.util.UUID.randomUUID(),
                null
        );

        assertEquals(
                "J’ai trouvé deux agents dans le Service du Personnel.",
                response.message()
        );
        // Search results are deliberately not returned as raw API tool payloads.
        assertTrue(response.toolResults().isEmpty());
        verify(toolExecutor).execute(
                any(ToolDefinition.class),
                org.mockito.ArgumentMatchers.eq(Map.of("query", "agents du Personnel")),
                org.mockito.ArgumentMatchers.eq("Bearer test-token"),
                any()
        );
        verify(chat).sendMessage(anyList(), any(GenerateContentConfig.class));
        var toolReply = org.mockito.ArgumentCaptor.forClass(Content.class);
        verify(chat).sendMessage(toolReply.capture(), any(GenerateContentConfig.class));
        var returnedFunctionResponse = toolReply.getValue().parts()
                .orElseThrow().get(0).functionResponse().orElseThrow();
        Map<String, Object> returnedPayload =
                returnedFunctionResponse.response().orElseThrow();
        assertTrue(returnedPayload.containsKey("output"));
        assertEquals(2, ((Map<?, ?>) returnedPayload.get("output")).get("count"));
    }

    @Test
    void resetStartsANewChatAndExecutesOnlyTheCurrentRequest()
            throws Exception {
        Chat freshChat = mock(Chat.class);

        FunctionCall resetCall = mock(FunctionCall.class);
        when(resetCall.name()).thenReturn(Optional.of("reset_conversation_context"));
        when(resetCall.args()).thenReturn(Optional.of(Map.of()));
        when(resetCall.id()).thenReturn(Optional.empty());
        Part resetPart = mock(Part.class);
        when(resetPart.functionCall()).thenReturn(Optional.of(resetCall));
        GenerateContentResponse resetResponse = mock(GenerateContentResponse.class);
        when(resetResponse.parts()).thenReturn(ImmutableList.of(resetPart));
        when(resetResponse.usageMetadata()).thenReturn(Optional.empty());
        when(chat.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(resetResponse);

        FunctionCall searchCall = mock(FunctionCall.class);
        when(searchCall.name()).thenReturn(Optional.of("search_agents"));
        when(searchCall.args()).thenReturn(Optional.of(Map.of("query", "reclassement")));
        when(searchCall.id()).thenReturn(Optional.empty());
        Part searchPart = mock(Part.class);
        when(searchPart.functionCall()).thenReturn(Optional.of(searchCall));
        GenerateContentResponse searchResponse = mock(GenerateContentResponse.class);
        when(searchResponse.parts()).thenReturn(ImmutableList.of(searchPart));
        when(searchResponse.usageMetadata()).thenReturn(Optional.empty());

        Part finalPart = mock(Part.class);
        when(finalPart.functionCall()).thenReturn(Optional.empty());
        GenerateContentResponse finalResponse = mock(GenerateContentResponse.class);
        when(finalResponse.parts()).thenReturn(ImmutableList.of(finalPart));
        when(finalResponse.usageMetadata()).thenReturn(Optional.empty());
        when(finalResponse.text()).thenReturn("Voici les résultats de la recherche.");
        when(freshChat.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(searchResponse);
        when(freshChat.sendMessage(any(Content.class), any(GenerateContentConfig.class)))
                .thenReturn(finalResponse);
        when(toolExecutor.execute(
                any(ToolDefinition.class), any(Map.class), any(), any()
        )).thenReturn(Map.of("count", 0, "agents", List.of()));

        AtomicInteger chatNumber = new AtomicInteger();
        BiFunction<String, GenerateContentConfig, Chat> factory =
                (model, config) -> chatNumber.getAndIncrement() == 0
                        ? chat
                        : freshChat;
        assistant = new GeminiAssistant(
                "test-api-key", "test-model", 2,
                "standard", "low", 1024, 15000, 1, factory,
                configurationRepository, toolExecutor,
                new ConversationStateMerger(objectMapper),
                new PerformanceTelemetry(), objectMapper
        );

        String oldContext = "Service du Personnel";
        ChatResponse response = assistant.respond(
                "Explique la procédure de reclassement.",
                "Bearer test-token",
                oldContext,
                java.util.UUID.randomUUID(),
                null
        );

        assertTrue(response.resetConversationState());
        assertEquals("Voici les résultats de la recherche.", response.message());
        // Sensitive agent-search payloads stay internal to the orchestration.
        assertTrue(response.toolResults().isEmpty());
        verify(chat).sendMessage(anyList(), any(GenerateContentConfig.class));
        verify(freshChat).sendMessage(anyList(), any(GenerateContentConfig.class));
        var freshMessages = org.mockito.ArgumentCaptor
                .forClass((Class<List<Content>>) (Class<?>) List.class);
        verify(freshChat).sendMessage(freshMessages.capture(), any(GenerateContentConfig.class));
        String resetPrompt = freshMessages.getValue().get(0)
                .parts().orElseThrow().get(0).text().orElseThrow();
        verify(freshChat).sendMessage(any(Content.class), any(GenerateContentConfig.class));
        assertTrue(resetPrompt.contains("a déjà été réinitialisé"));
        assertTrue(resetPrompt.contains("Explique la procédure de reclassement."));
        assertFalse(resetPrompt.contains("Service du Personnel"));
    }

    @Test
    void backendFailureStopsTheLoopInsteadOfReturningAFabricatedSuccess() {
        FunctionCall call = mock(FunctionCall.class);
        when(call.name()).thenReturn(Optional.of("search_agents"));
        when(call.args()).thenReturn(Optional.of(Map.of("query", "agents")));
        when(call.id()).thenReturn(Optional.empty());
        Part callPart = mock(Part.class);
        when(callPart.functionCall()).thenReturn(Optional.of(call));
        GenerateContentResponse modelResponse = mock(GenerateContentResponse.class);
        when(modelResponse.parts()).thenReturn(ImmutableList.of(callPart));
        when(modelResponse.usageMetadata()).thenReturn(Optional.empty());
        when(chat.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(modelResponse);
        when(toolExecutor.execute(
                any(ToolDefinition.class), any(Map.class), any(), any()
        )).thenThrow(new org.springframework.web.client.RestClientException(
                "Backend unavailable"
        ));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> assistant.respond(
                        "Cherche les agents.",
                        "Bearer test-token",
                        "",
                        java.util.UUID.randomUUID(),
                        null
                )
        );

        assertEquals(502, failure.getStatusCode().value());
        verify(chat).sendMessage(anyList(), any(GenerateContentConfig.class));
        verify(chat, never()).sendMessage(
                any(Content.class), any(GenerateContentConfig.class)
        );
    }

    @Test
    void refusesToExecuteAFunctionThatWasNotDeclaredAsEnabled() {
        FunctionCall call = mock(FunctionCall.class);
        when(call.name()).thenReturn(Optional.of("delete_all_employees"));
        when(call.args()).thenReturn(Optional.of(Map.of()));
        when(call.id()).thenReturn(Optional.empty());
        Part callPart = mock(Part.class);
        when(callPart.functionCall()).thenReturn(Optional.of(call));
        GenerateContentResponse modelResponse = mock(GenerateContentResponse.class);
        when(modelResponse.parts()).thenReturn(ImmutableList.of(callPart));
        when(modelResponse.usageMetadata()).thenReturn(Optional.empty());
        when(chat.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(modelResponse);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> assistant.respond(
                        "Supprime tous les agents.",
                        "Bearer test-token",
                        "",
                        java.util.UUID.randomUUID(),
                        null
                )
        );

        assertEquals(502, failure.getStatusCode().value());
        verify(toolExecutor, never()).execute(
                any(ToolDefinition.class),
                any(Map.class),
                any(),
                any()
        );
    }
}
