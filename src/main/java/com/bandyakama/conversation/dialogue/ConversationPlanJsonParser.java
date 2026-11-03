package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses and structurally validates the untrusted JSON emitted for a conversation plan. */
@Component
public class ConversationPlanJsonParser {
    private final ObjectMapper objectMapper;

    public ConversationPlanJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ConversationPlanProposal parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<ConversationPlanProposal.Operation> operations = new ArrayList<>();
            JsonNode operationNodes = requiredArray(root, "operations");
            for (JsonNode node : operationNodes) {
                List<ConversationPlanProposal.EntityReference> dependencies =
                        parseReferences(requiredArray(node, "dependsOn"));
                List<ProposedDialogueReference> mentions = parseMentions(
                        requiredArray(node, "referenceMentions")
                );
                operations.add(new ConversationPlanProposal.Operation(
                        requiredText(node, "operationId"),
                        enumValue(ConversationPlanProposal.OperationType.class,
                                requiredText(node, "type")),
                        optionalText(node, "targetObjectiveId"),
                        optionalText(node, "goal"),
                        optionalText(node, "summary"),
                        parseOptionalReference(node.get("parent")),
                        dependencies,
                        mentions
                ));
            }

            List<ConversationPlanProposal.Clarification> clarifications =
                    new ArrayList<>();
            for (JsonNode node : requiredArray(root, "clarifications")) {
                clarifications.add(new ConversationPlanProposal.Clarification(
                        requiredText(node, "clarificationId"),
                        parseOptionalReference(node.get("target")),
                        requiredText(node, "question"),
                        requiredText(node, "rationale")
                ));
            }

            return new ConversationPlanProposal(
                    requiredText(root, "rationale"),
                    enumValue(InterpretationConfidence.class,
                            requiredText(root, "confidence")),
                    operations,
                    clarifications
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Gemini a renvoyé un plan conversationnel invalide.", exception
            );
        }
    }

    private List<ConversationPlanProposal.EntityReference> parseReferences(
            JsonNode nodes
    ) {
        List<ConversationPlanProposal.EntityReference> references = new ArrayList<>();
        for (JsonNode node : nodes) {
            references.add(parseReference(node));
        }
        return references;
    }

    private List<ProposedDialogueReference> parseMentions(JsonNode nodes) {
        List<ProposedDialogueReference> mentions = new ArrayList<>();
        for (JsonNode node : nodes) {
            mentions.add(new ProposedDialogueReference(
                    requiredText(node, "label"),
                    requiredText(node, "kind")
            ));
        }
        return mentions;
    }

    private ConversationPlanProposal.EntityReference parseOptionalReference(
            JsonNode node
    ) {
        return node == null || node.isNull() ? null : parseReference(node);
    }

    private ConversationPlanProposal.EntityReference parseReference(JsonNode node) {
        return new ConversationPlanProposal.EntityReference(
                enumValue(ConversationPlanProposal.ReferenceScope.class,
                        requiredText(node, "scope")),
                requiredText(node, "id")
        );
    }

    private JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " doit être un tableau.");
        }
        return value;
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Champ obligatoire absent ou vide : " + field
            );
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " doit être une chaîne ou null.");
        }
        return value.asText();
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Valeur enum inconnue : " + value);
        }
    }
}
