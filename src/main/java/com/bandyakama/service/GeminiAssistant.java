package com.bandyakama.service;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.assistant.AssistantProfileRepository;
import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.bandyakama.tool.ToolRegistry;
import com.google.genai.Chat;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class GeminiAssistant {
    private static final int MAX_TOOL_ROUNDS = 5;

    private final Client client;
    private final String apiKey;
    private final String model;
    private final AssistantProfileRepository profileRepository;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    public GeminiAssistant(
            @Value("${GEMINI_API_KEY:}") String apiKey,
            @Value("${bandy.ai.model:gemini-2.5-flash}") String model,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            AssistantProfileRepository profileRepository
    ) {
        this.apiKey = apiKey;
        this.client = apiKey.isBlank()
                ? null
                : Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.profileRepository = profileRepository;
    }

    public ChatResponse respond(String message, String authorization) {
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Configurez GEMINI_API_KEY pour activer l’assistant."
            );
        }

        AssistantProfile profile = profileRepository.get();
        String systemInstruction = "Tu es " + profile.name() + ".\n"
                + "Rôle : " + profile.role() + "\n"
                + "Consignes :\n" + profile.instructions();
        List<ToolDefinition> registeredTools = toolRegistry.getEnabled();
        GenerateContentConfig.Builder configBuilder =
                GenerateContentConfig.builder()
                        .systemInstruction(Content.fromParts(
                                Part.fromText(systemInstruction)
                        ));
        if (!registeredTools.isEmpty()) {
            configBuilder.tools(List.of(toGeminiTool(registeredTools)));
        }

        Chat chat = client.chats.create(model, configBuilder.build());
        GenerateContentResponse response = chat.sendMessage(message);
        List<ChatResponse.ToolResult> executedResults = new ArrayList<>();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            List<FunctionCall> calls = response.parts().stream()
                    .map(Part::functionCall)
                    .flatMap(Optional::stream)
                    .toList();
            if (calls.isEmpty()) {
                return new ChatResponse(response.text(), List.copyOf(executedResults));
            }

            List<Part> resultParts = new ArrayList<>();
            for (FunctionCall call : calls) {
                String name = call.name().orElse("");
                Map<String, Object> arguments = call.args().orElse(Map.of());
                Object result = executeRegisteredTool(name, arguments, authorization);
                executedResults.add(new ChatResponse.ToolResult(name, result));

                FunctionResponse.Builder resultBuilder =
                        FunctionResponse.builder()
                                .name(name)
                                .response(Collections.singletonMap("output", result));
                call.id().ifPresent(resultBuilder::id);
                resultParts.add(Part.builder()
                        .functionResponse(resultBuilder.build())
                        .build());
            }
            response = chat.sendMessage(
                    Content.fromParts(resultParts.toArray(Part[]::new))
            );
        }

        boolean hasMoreCalls = response.parts().stream()
                .map(Part::functionCall)
                .flatMap(Optional::stream)
                .findAny()
                .isPresent();
        if (!hasMoreCalls) {
            return new ChatResponse(response.text(), List.copyOf(executedResults));
        }

        return new ChatResponse(
                "J’ai atteint la limite d’appels d’outils pour cette demande. "
                        + "Vous pouvez la préciser ou la diviser en plusieurs questions.",
                List.copyOf(executedResults)
        );
    }

    private Object executeRegisteredTool(
            String name,
            Map<String, Object> arguments,
            String authorization
    ) {
        Optional<ToolDefinition> definition = toolRegistry.findByName(name);
        if (definition.isEmpty()) {
            return Map.of("error", "Fonction non autorisée ou inconnue.");
        }

        try {
            return toolExecutor.execute(definition.get(), arguments, authorization);
        } catch (IllegalArgumentException exception) {
            return Map.of("error", exception.getMessage());
        } catch (RestClientException exception) {
            return Map.of(
                    "error",
                    "Le service associé à la fonction n’a pas pu traiter la demande."
            );
        }
    }

    private Tool toGeminiTool(List<ToolDefinition> definitions) {
        List<FunctionDeclaration> declarations = definitions.stream()
                .map(definition -> FunctionDeclaration.builder()
                        .name(definition.getName())
                        .description(definition.getDescription()
                                + "\nQuand l’utiliser : "
                                + definition.getWhenToUse())
                        .parameters(toGeminiSchema(definition.getParameters()))
                        .build())
                .toList();
        return Tool.builder().functionDeclarations(declarations).build();
    }

    private Schema toGeminiSchema(List<ToolParameter> parameters) {
        Map<String, Schema> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolParameter parameter : parameters) {
            properties.put(
                    parameter.getName(),
                    Schema.builder()
                            .type(toGeminiType(parameter.getType()))
                            .description(parameter.getDescription())
                            .build()
            );
            if (parameter.isRequired()) {
                required.add(parameter.getName());
            }
        }

        Schema.Builder schema = Schema.builder()
                .type("OBJECT")
                .properties(properties);
        if (!required.isEmpty()) {
            schema.required(required);
        }
        return schema.build();
    }

    private String toGeminiType(String type) {
        return switch (type.toLowerCase()) {
            case "string" -> "STRING";
            case "integer" -> "INTEGER";
            case "number" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            default -> throw new IllegalArgumentException(
                    "Type d’argument non pris en charge : " + type
            );
        };
    }
}
