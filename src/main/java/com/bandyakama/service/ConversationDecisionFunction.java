package com.bandyakama.service;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionCallingConfigMode;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the internal Gemini-only decision function from enabled tool metadata. */
final class ConversationDecisionFunction {
    static final String NAME = "resolve_conversation_turn";

    private ConversationDecisionFunction() {
    }

    static Tool declaration(List<ToolDefinition> enabledTools) {
        Map<String, Schema> argumentProperties = new LinkedHashMap<>();
        Map<String, String> argumentTypes = new LinkedHashMap<>();
        for (ToolDefinition tool : enabledTools) {
            for (ToolParameter parameter : tool.getParameters()) {
                String type = geminiType(parameter.getType());
                String previousType = argumentTypes.putIfAbsent(
                        parameter.getName(), type
                );
                if (previousType != null && !previousType.equals(type)) {
                    throw new IllegalStateException(
                            "Les outils déclarent des types incompatibles pour "
                                    + "le paramètre partagé : " + parameter.getName()
                    );
                }
                argumentProperties.putIfAbsent(
                        parameter.getName(),
                        Schema.builder()
                                .type(type)
                                .description(parameter.getDescription())
                                .build()
                );
            }
        }

        Schema entitySchema = Schema.builder()
                .type("OBJECT")
                .properties(Map.of(
                        "type", stringSchema("Type de l’entité : DIRECTION, SERVICE, POSTE, AGENT..."),
                        "id", Schema.builder().type("INTEGER")
                                .description("Identifiant réel retourné par une source métier.").build(),
                        "name", stringSchema("Nom exact de l’entité identifiée."),
                        "parentType", stringSchema("Type de l’entité parente, si connu."),
                        "parentId", Schema.builder().type("INTEGER")
                                .description("Identifiant de l’entité parente, si connu.")
                                .nullable(true).build()
                ))
                .required(List.of("type", "id", "name"))
                .nullable(true)
                .build();

        Schema decisionSchema = Schema.builder()
                .type("OBJECT")
                .properties(Map.of(
                        "intentType", Schema.builder()
                                .type("STRING").format("enum")
                                .enum_("CONTINUATION", "NEW_INTENT", "DIRECT_RESPONSE")
                                .description("Interprétation de la relation avec les échanges précédents.")
                                .build(),
                        "toolName", Schema.builder()
                                .type("STRING").format("enum")
                                .enum_(enabledTools.stream().map(ToolDefinition::getName).toList())
                                .description("Outil métier autorisé, absent si clarification ou réponse directe.")
                                .nullable(true).build(),
                        "operation", stringSchema("Opération métier demandée, si l’outil en utilise une."),
                        "arguments", Schema.builder()
                                .type("OBJECT").properties(argumentProperties)
                                .description("Arguments complets de l’action actuelle, sans fusion implicite avec les anciens arguments.")
                                .build(),
                        "referencedEntity", entitySchema,
                        "needsClarification", Schema.builder().type("BOOLEAN")
                                .description("Vrai si une précision est nécessaire avant toute exécution.")
                                .build(),
                        "clarificationQuestion", stringSchema("Question à poser si needsClarification vaut true."),
                        "directResponse", stringSchema("Réponse directe si aucun outil métier n’est nécessaire.")
                ))
                .required(List.of("intentType", "needsClarification"))
                .build();

        FunctionDeclaration function = FunctionDeclaration.builder()
                .name(NAME)
                .description(
                        "Interprète le message actuel à partir de l’historique et de l’état "
                                + "structuré. Décide continuation, nouveau sujet ou réponse directe. "
                                + "Résous les références aux entités et demande une clarification "
                                + "si le référent ou les arguments ne sont pas suffisamment certains. "
                                + "Ne déclenche jamais directement un outil métier."
                )
                .parameters(decisionSchema)
                .build();
        return Tool.builder().functionDeclarations(function).build();
    }

    static ToolConfig forcedFunctionConfig() {
        return ToolConfig.builder()
                .functionCallingConfig(FunctionCallingConfig.builder()
                        .mode(FunctionCallingConfigMode.Known.ANY)
                        .allowedFunctionNames(NAME)
                        .build())
                .build();
    }

    private static Schema stringSchema(String description) {
        return Schema.builder().type("STRING").description(description).nullable(true).build();
    }

    private static String geminiType(String type) {
        return switch (type.toLowerCase()) {
            case "string" -> "STRING";
            case "integer" -> "INTEGER";
            case "number" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            default -> throw new IllegalArgumentException(
                    "Type de paramètre non pris en charge : " + type
            );
        };
    }
}