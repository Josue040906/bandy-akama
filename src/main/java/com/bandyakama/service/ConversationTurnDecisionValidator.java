package com.bandyakama.service;

import com.bandyakama.conversation.ConversationTurnDecision;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates Gemini's interpretation against enabled tool contracts and trusted context. */
@Component
final class ConversationTurnDecisionValidator {
    private static final String REFERENCES_HEADING =
            "Références aux résultats métier précédents :";
    private static final String SUMMARY_HEADING = "Résumé extractif des échanges plus anciens :";
    private final ObjectMapper objectMapper = new ObjectMapper();
    void validate(
            ConversationTurnDecision decision,
            Map<String, ToolDefinition> enabledTools,
            String conversationContext,
            com.fasterxml.jackson.databind.JsonNode activeState
    ) {
        try {
            decision.validate();
        } catch (IllegalArgumentException invalidDecision) {
            throw invalidDecision(invalidDecision.getMessage());
        }
        if (decision.needsClarification()) {
            return;
        }
        if (decision.intentType() == ConversationTurnDecision.IntentType.DIRECT_RESPONSE) {
            if (decision.referencedEntity() != null
                    && !isReferencedEntityGrounded(decision, conversationContext, activeState)) {
                throw clarification("Je ne parviens pas à identifier avec certitude l’entité évoquée. Peux-tu préciser son nom ?");
            }
            return;
        }

        ToolDefinition tool = enabledTools.get(decision.toolName());
        if (tool == null || !tool.isEnabled()) {
            throw invalidDecision("Gemini a sélectionné un outil qui n’est pas autorisé.");
        }
        Map<String, Object> arguments = decision.arguments();
        Set<String> declared = tool.getParameters().stream()
                .map(ToolParameter::getName)
                .collect(Collectors.toSet());
        if (!declared.containsAll(arguments.keySet())) {
            throw invalidDecision("La décision contient des arguments non déclarés pour cet outil.");
        }
        for (ToolParameter parameter : tool.getParameters()) {
            Object value = arguments.get(parameter.getName());
            if (parameter.isRequired() && (value == null
                    || value instanceof String text && text.isBlank())) {
                throw clarification("Pour effectuer cette opération, peux-tu préciser : "
                        + parameter.getDescription() + " ?");
            }
            if (value != null && !matchesType(parameter.getType(), value)) {
                throw invalidDecision("Le type de l’argument est invalide : " + parameter.getName());
            }
        }
        ToolParameter operationParameter = tool.getParameters().stream()
                .filter(parameter -> "operation".equals(parameter.getName()))
                .findFirst()
                .orElse(null);
        if (operationParameter != null) {
            Object argumentOperation = arguments.get("operation");
            if (decision.operation() == null || !decision.operation().equals(argumentOperation)) {
                throw invalidDecision("L’opération et les arguments de l’outil doivent être identiques.");
            }
        } else if (decision.operation() != null && !decision.operation().isBlank()) {
            throw invalidDecision("Une opération a été fournie pour un outil qui n’en déclare pas.");
        }

        if (decision.referencedEntity() == null) {
            if (decision.intentType() == ConversationTurnDecision.IntentType.NEW_INTENT
                    && hasScopedIdentifier(decision.arguments())) {
                throw clarification("Quelle structure souhaites-tu consulter exactement : une direction, un service ou un poste ?");
            }
            validateScopedIdsHaveKnownEntities(decision.arguments(), activeState);
        }
        if (decision.referencedEntity() != null) {
            if (!isReferencedEntityGrounded(decision, conversationContext, activeState)) {
                throw clarification("Je ne parviens pas à confirmer à quelle structure tu fais référence. Peux-tu préciser son nom ?");
            }
            Map<String, Object> entityArguments = decision.arguments();
            String scopedArgument = switch (decision.referencedEntity().type().toUpperCase()) {
                case "DIRECTION" -> "directionId";
                case "SERVICE" -> "serviceId";
                case "POSTE" -> "posteId";
                case "AGENT", "EMPLOYE" -> "employeId";
                default -> null;
            };
            if (scopedArgument != null && entityArguments.containsKey(scopedArgument)
                    && !String.valueOf(decision.referencedEntity().id())
                    .equals(String.valueOf(entityArguments.get(scopedArgument)))) {
                throw invalidDecision("L’identifiant de l’entité et le paramètre de ciblage ne correspondent pas.");
            }
        }
    }

    private boolean hasScopedIdentifier(Map<String, Object> arguments) {
        return arguments.containsKey("directionId")
                || arguments.containsKey("serviceId")
                || arguments.containsKey("posteId")
                || arguments.containsKey("employeId");
    }
    private void validateScopedIdsHaveKnownEntities(
            Map<String, Object> arguments,
            JsonNode activeState
    ) {
        Map<String, String> scopedTypes = Map.of(
                "directionId", "DIRECTION",
                "serviceId", "SERVICE",
                "posteId", "POSTE",
                "employeId", "AGENT"
        );
        for (Map.Entry<String, String> scoped : scopedTypes.entrySet()) {
            Object rawId = arguments.get(scoped.getKey());
            if (rawId == null) {
                continue;
            }
            long id;
            try {
                id = new BigDecimal(rawId.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException invalidId) {
                throw invalidDecision("Un identifiant de structure n’est pas un entier valide.");
            }
            if (!hasKnownEntity(activeState, scoped.getValue(), id)) {
                throw clarification("Je ne retrouve pas cette structure parmi les entités déjà identifiées. Peux-tu préciser son nom ?");
            }
        }
    }

    private boolean hasKnownEntity(JsonNode activeState, String type, long id) {
        if (activeState == null || activeState.isNull()) {
            return false;
        }
        JsonNode activeEntity = activeState.path("activeEntity");
        if (activeEntity.isObject()
                && type.equalsIgnoreCase(activeEntity.path("type").asText())
                && activeEntity.path("id").asLong(-1) == id) {
            return true;
        }
        JsonNode references = activeState.path("entityReferences");
        if (references.isArray()) {
            for (JsonNode reference : references) {
                if (type.equalsIgnoreCase(reference.path("type").asText())
                        && reference.path("id").asLong(-1) == id) {
                    return true;
                }
            }
        }
        return false;
    }
    private boolean isReferencedEntityGrounded(
            ConversationTurnDecision decision,
            String context,
            com.fasterxml.jackson.databind.JsonNode activeState
    ) {
        var entity = decision.referencedEntity();
        var activeEntity = activeState == null ? null : activeState.path("activeEntity");
        if (activeEntity != null && activeEntity.isObject()
                && entity.id().equals(activeEntity.path("id").isIntegralNumber()
                        ? activeEntity.path("id").longValue() : null)
                && entity.type().equalsIgnoreCase(activeEntity.path("type").asText())
                && entity.name().equalsIgnoreCase(activeEntity.path("name").asText())) {
            return true;
        }
        JsonNode knownEntities = activeState == null
                ? null : activeState.path("entityReferences");
        if (knownEntities != null && knownEntities.isArray()) {
            for (JsonNode known : knownEntities) {
                if (entity.id().equals(known.path("id").isIntegralNumber()
                        ? known.path("id").longValue() : null)
                        && entity.type().equalsIgnoreCase(known.path("type").asText())
                        && entity.name().equalsIgnoreCase(known.path("name").asText())) {
                    return true;
                }
            }
        }
        if (context == null || context.isBlank()) {
            return false;
        }
        int headingIndex = context.indexOf(REFERENCES_HEADING);
        if (headingIndex < 0) {
            return false;
        }
        int jsonStart = headingIndex + REFERENCES_HEADING.length();
        int summaryIndex = context.indexOf(SUMMARY_HEADING, jsonStart);
        String referencesJson = context.substring(
                jsonStart, summaryIndex < 0 ? context.length() : summaryIndex
        ).trim();
        try {
            JsonNode references = objectMapper.readTree(referencesJson);
            return containsEntity(references, entity.id(), entity.name());
        } catch (Exception invalidReferences) {
            return false;
        }
    }

    private boolean containsEntity(JsonNode node, Long id, String name) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            JsonNode idNode = node.get("id");
            boolean sameId = idNode != null && idNode.isIntegralNumber()
                    && idNode.longValue() == id;
            boolean sameName = false;
            for (String field : new String[]{"name", "nom", "intitule", "title", "label"}) {
                JsonNode candidate = node.get(field);
                if (candidate != null && candidate.isTextual()
                        && name.equalsIgnoreCase(candidate.asText().trim())) {
                    sameName = true;
                    break;
                }
            }
            if (sameId && sameName) {
                return true;
            }
            var fields = node.elements();
            while (fields.hasNext()) {
                if (containsEntity(fields.next(), id, name)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsEntity(child, id, name)) {
                    return true;
                }
            }
        }
        return false;
    }
    private boolean matchesType(String type, Object value) {
        return switch (type.toLowerCase()) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number number && isWholeNumber(number);
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> false;
        };
    }

    private boolean isWholeNumber(Number number) {
        try {
            return new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private ConversationClarificationRequiredException clarification(String question) {
        return new ConversationClarificationRequiredException(question);
    }
    private ResponseStatusException invalidDecision(String reason) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "La décision conversationnelle de Gemini est invalide : " + reason
        );
    }
}