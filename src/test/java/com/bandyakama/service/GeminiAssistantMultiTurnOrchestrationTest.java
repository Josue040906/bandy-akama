package com.bandyakama.service;

import com.bandyakama.assistant.AssistantConfigurationRepository;
import com.bandyakama.assistant.AssistantConfigurationSnapshot;
import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.conversation.ConversationStateMerger;
import com.bandyakama.conversation.ConversationPromptContext;
import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.gemini.GeminiChatGateway;
import com.bandyakama.gemini.GeminiClientFactory;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Chat;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class GeminiAssistantMultiTurnOrchestrationTest {

    @Test
    void executesModelDecisionsWithCompleteCriteriaAcrossFollowUpTurns() throws Exception {
        var factory = mock(GeminiClientFactory.class);
        var gateway = mock(GeminiChatGateway.class);
        var configurationRepository = mock(AssistantConfigurationRepository.class);
        var toolExecutor = mock(ToolExecutor.class);
        var stateMerger = new ConversationStateMerger(new ObjectMapper());
        var telemetry = new PerformanceTelemetry();
        var decisionChat = mock(Chat.class);
        var finalChat1 = mock(Chat.class);
        var finalChat2 = mock(Chat.class);

        ToolDefinition search = new ToolDefinition(
                "search_agents",
                "Recherche des agents",
                "GET",
                "http://localhost/api/agents/search",
                List.of(new ToolParameter(
                        "criteriaJson", "string", "Critères complets de recherche", false
                ))
        );
        search.setWhenToUse("Recherche filtrée des agents.");
        search.setAssistantEnabled(true);
        when(configurationRepository.get()).thenReturn(new AssistantConfigurationSnapshot(
                new AssistantProfile("Bandy Akama", "Assistant RH", "Réponds avec fiabilité."),
                List.of(search)
        ));
        when(factory.createGateway("test-key")).thenReturn(gateway);
        when(factory.timeoutMillis()).thenReturn(15000);
        when(factory.retryAttempts()).thenReturn(1);

        when(gateway.createChat(any(), any()))
                .thenReturn(decisionChat, finalChat1, decisionChat, finalChat2);

        ObjectMapper mapper = new ObjectMapper();
        String serviceCriteria = mapper.writeValueAsString(Map.of("service", "Administration générale"));
        String womenCriteria = mapper.writeValueAsString(Map.of("service", "Administration générale", "sexe", "F"));
        GenerateContentResponse firstDecision = decision(
                Map.of(
                        "intentType", "NEW_INTENT",
                        "toolName", "search_agents",
                        "toolArguments", Map.of("search_agents", Map.of(
                                "criteriaJson", serviceCriteria
                        )),
                        "needsClarification", false
                )
        );
        GenerateContentResponse secondDecision = decision(
                Map.of(
                        "intentType", "CONTINUATION",
                        "toolName", "search_agents",
                        "toolArguments", Map.of("search_agents", Map.of(
                                "criteriaJson", womenCriteria
                        )),
                        "needsClarification", false
                )
        );
        when(decisionChat.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(firstDecision, secondDecision);
        GenerateContentResponse firstFinalResponse = textResponse("Six agents trouvés.");
        GenerateContentResponse secondFinalResponse = textResponse("Deux femmes correspondent.");
        when(finalChat1.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(firstFinalResponse);
        when(finalChat2.sendMessage(anyList(), any(GenerateContentConfig.class)))
                .thenReturn(secondFinalResponse);
        when(toolExecutor.execute(eq(search), anyMap(), any(), isNull()))
                .thenReturn(Map.of("total", 6), Map.of("total", 2));

        GeminiAssistant assistant = new GeminiAssistant(
                "test-key", "gemini-test", 2, "standard", "low", 1024,
                factory, configurationRepository, toolExecutor, stateMerger,
                telemetry, new ObjectMapper()
        );

        var first = assistant.respond("Cherche les agents de l'administration générale", "Bearer test");
        String history = mapper.writeValueAsString(List.of(
                Map.of("role", "user", "content", "Cherche les agents de l'administration générale"),
                Map.of("role", "assistant", "content", "Six agents trouvés.")
        ));
        String followUpContext = "Filtres actifs : service=Administration générale"
                + ConversationPromptContext.marker() + history;
        var second = assistant.respond("Combien sont des femmes ?", "Bearer test", followUpContext);

        assertTrue(first.resetConversationState());
        assertFalse(second.resetConversationState());
        assertEquals("Six agents trouvés.", first.message());
        assertEquals("Deux femmes correspondent.", second.message());
        verify(toolExecutor).execute(eq(search), argThat(args ->
                serviceCriteria.equals(args.get("criteriaJson"))
        ), eq("Bearer test"), isNull());
        verify(toolExecutor).execute(eq(search), argThat(args ->
                womenCriteria.equals(args.get("criteriaJson"))
        ), eq("Bearer test"), any());
        verify(gateway, times(4)).createChat(eq("gemini-test"), any());
    }

    private GenerateContentResponse decision(Map<String, Object> arguments) {
        FunctionCall call = mock(FunctionCall.class);
        when(call.name()).thenReturn(Optional.of("resolve_conversation_turn"));
        when(call.args()).thenReturn(Optional.of(arguments));
        Part part = mock(Part.class);
        when(part.functionCall()).thenReturn(Optional.of(call));
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.parts()).thenReturn(ImmutableList.of(part));
        when(response.usageMetadata()).thenReturn(Optional.empty());
        return response;
    }

    private GenerateContentResponse textResponse(String text) {
        GenerateContentResponse response = mock(GenerateContentResponse.class);
        when(response.text()).thenReturn(text);
        when(response.parts()).thenReturn(ImmutableList.of());
        when(response.usageMetadata()).thenReturn(Optional.empty());
        return response;
    }
}
