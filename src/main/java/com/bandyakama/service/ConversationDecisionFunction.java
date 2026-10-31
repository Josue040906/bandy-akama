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
        Map<String, Schema> toolArgumentSchemas = new LinkedHashMap<>();
        for (ToolDefinition tool : enabledTools) {
            Map<String, Schema> parameterProperties = new LinkedHashMap<>();
            List<String> requiredParameters = new java.util.ArrayList<>();
            for (ToolParameter parameter : tool.getParameters()) {
                if ("operation".equals(parameter.getName())) {
                    continue;
                }
                String parameterDescription = parameter.getDescription();
                if (parameter.getReferenceType() != null) {
                    parameterDescription += " Cet identifiant référence une entité de type "
                            + parameter.getReferenceType() + " déjà identifiée dans le contexte.";
                }
                Schema.Builder parameterSchema = Schema.builder()
                        .type(geminiType(parameter.getType()))
                        .description(parameterDescription);
                if (parameter.getAllowedValues() != null) {
                    parameterSchema.format("enum").enum_(parameter.getAllowedValues());
                }
                parameterProperties.put(parameter.getName(), parameterSchema.build());
                if (parameter.isRequired()) {
                    requiredParameters.add(parameter.getName());
                }
            }
            Schema.Builder toolArguments = Schema.builder()
                    .type("OBJECT")
                    .properties(parameterProperties)
                    .description("Arguments complets pour l’outil " + tool.getName() + ".");
            if (!requiredParameters.isEmpty()) {
                toolArguments.required(requiredParameters);
            }
            toolArgumentSchemas.put(tool.getName(), toolArguments.build());
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
                                .description("CONTINUATION : la demande dépend du contexte actif. NEW_INTENT : demande indépendante ou changement de sujet, même si une réponse directe ou une clarification suffit ; l’état actif doit être réinitialisé. DIRECT_RESPONSE : réponse sans outil qui reste dans le sujet actif.")
                                .build(),
                        "toolName", Schema.builder()
                                .type("STRING").format("enum")
                                .enum_(enabledTools.stream().map(ToolDefinition::getName).toList())
                                .description("Outil métier autorisé, absent si clarification ou réponse directe.")
                                .nullable(true).build(),
                        "operation", operationSchema(enabledTools),
                        "toolArguments", Schema.builder()
                                .type("OBJECT").properties(toolArgumentSchemas)
                                .description("Objet d’arguments indexé par le nom exact de l’outil sélectionné. Ne renseigner que l’objet correspondant à toolName.")
                                .build(),
                        "referencedEntity", entitySchema,
                        "needsClarification", Schema.builder().type("BOOLEAN")
                                .description("Vrai si une précision est nécessaire avant toute exécution.")
                                .build(),
                        "clarificationQuestion", stringSchema("Question à poser si needsClarification vaut true."),
                        "directResponse", stringSchema("Réponse sans outil. Pour une demande indépendante, utiliser intentType=NEW_INTENT afin de réinitialiser l’état actif.")
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

    private static Schema operationSchema(List<ToolDefinition> enabledTools) {
        List<String> operations = enabledTools.stream()
                .flatMap(tool -> tool.getParameters().stream())
                .filter(parameter -> "operation".equals(parameter.getName()))
                .filter(parameter -> parameter.getAllowedValues() != null)
                .flatMap(parameter -> parameter.getAllowedValues().stream())
                .distinct()
                .toList();
        if (operations.isEmpty()) {
            return stringSchema("Opération métier demandée, si l’outil en utilise une.");
        }
        return Schema.builder()
                .type("STRING")
                .format("enum")
                .enum_(operations)
                .description("Opération autorisée parmi les opérations déclarées par les outils activés.")
                .nullable(true)
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
