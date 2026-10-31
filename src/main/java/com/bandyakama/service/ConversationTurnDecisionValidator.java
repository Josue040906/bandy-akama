package com.bandyakama.service;

import com.bandyakama.conversation.ConversationTurnDecision;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
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
        if (decision.needsClarification()
                || decision.intentType() == ConversationTurnDecision.IntentType.DIRECT_RESPONSE) {
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
                throw invalidDecision("Un argument obligatoire est absent : " + parameter.getName());
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

        if (decision.referencedEntity() != null
                && !isReferencedEntityGrounded(decision, conversationContext, activeState)) {
            throw invalidDecision("L’entité référencée n’est pas confirmée par le contexte précédent.");
        }
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
                && entity.type().equalsIgnoreCase(activeEntity.path("type").asText())) {
            return true;
        }
        if (context == null || context.isBlank()) {
            return false;
        }
        String idToken = "\"id\":" + entity.id();
        String nameToken = entity.name();
        return context.contains(idToken) && context.contains(nameToken);
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

    private ResponseStatusException invalidDecision(String reason) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "La décision conversationnelle de Gemini est invalide : " + reason
        );
    }
}