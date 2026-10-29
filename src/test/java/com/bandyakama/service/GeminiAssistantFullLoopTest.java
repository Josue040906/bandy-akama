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
    void executesModelToolReturnsItsResultToGeminiAndUsesFinalAnswer() {
        FunctionCall call = mock(FunctionCall.class);
        when(call.name()).thenReturn(Optional.of("search_agents"));
        when(call.args()).thenReturn(Optional.of(Map.of("query", "agents du Personnel")));
        when(call.id()).thenReturn(Optional.empty());

        Part callPart = mock(Part.class);
        when(callPart.functionCall()).thenReturn(Optional.of(call));
        GenerateContentResponse toolCallResponse = mock(GenerateContentResponse.class);
        when(toolCallResponse.parts()).thenReturn(List.of(callPart));

        Part textPart = mock(Part.class);
        when(textPart.functionCall()).thenReturn(Optional.empty());
        GenerateContentResponse finalResponse = mock(GenerateContentResponse.class);
        when(finalResponse.parts()).thenReturn(List.of(textPart));
        when(finalResponse.text()).thenReturn(
                "J’ai trouvé deux agents dans le Service du Personnel."
        );
        when(chat.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(toolCallResponse, finalResponse);
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
        assertEquals(1, response.toolResults().size());
        assertEquals("search_agents", response.toolResults().get(0).name());
        assertEquals("agents du Personnel",
                response.toolResults().get(0).arguments().get("query"));
        verify(toolExecutor).execute(
                any(ToolDefinition.class),
                org.mockito.ArgumentMatchers.eq(Map.of("query", "agents du Personnel")),
                org.mockito.ArgumentMatchers.eq("Bearer test-token"),
                any()
        );
        var sentMessages = org.mockito.ArgumentCaptor
                .forClass((Class<List<Content>>) (Class<?>) List.class);
        verify(chat, org.mockito.Mockito.times(2))
                .sendMessage(sentMessages.capture(), any(GenerateContentConfig.class));
        List<Content> toolReply = sentMessages.getAllValues().get(1);
        assertEquals(1, toolReply.size());
        var returnedFunctionResponse = toolReply.get(0).parts().orElseThrow().get(0)
                .functionResponse().orElseThrow();
        Map<String, Object> returnedPayload =
                returnedFunctionResponse.response().orElseThrow();
        assertTrue(returnedPayload.containsKey("output"));
        assertEquals(2, ((Map<?, ?>) returnedPayload.get("output")).get("count"));
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
        when(modelResponse.parts()).thenReturn(List.of(callPart));
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
