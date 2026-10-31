package com.bandyakama.conversation;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/**
 * Structured interpretation of one user turn, produced by Gemini and
 * validated by the application before any business tool is executed.
 */
public record ConversationTurnDecision(
        IntentType intentType,
        String toolName,
        String operation,
        Map<String, Object> arguments,
        ReferencedEntity referencedEntity,
        boolean needsClarification,
        String clarificationQuestion,
        String directResponse
) {
    public ConversationTurnDecision {
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public void validate() {
        if (intentType == null) {
            throw new IllegalArgumentException("Le type d’intention est obligatoire.");
        }
        if (needsClarification) {
            if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
                throw new IllegalArgumentException(
                        "Une question de clarification est obligatoire quand la demande est ambiguë."
                );
            }
            if (toolName != null || !arguments.isEmpty()) {
                throw new IllegalArgumentException(
                        "Aucun outil ne peut être exécuté avant la clarification."
                );
            }
            return;
        }
        if (intentType == IntentType.DIRECT_RESPONSE) {
            if (directResponse == null || directResponse.isBlank()) {
                throw new IllegalArgumentException(
                        "Une réponse directe non vide est obligatoire pour DIRECT_RESPONSE."
                );
            }
            if (toolName != null || !arguments.isEmpty()) {
                throw new IllegalArgumentException(
                        "Une réponse directe ne doit pas déclencher d’outil métier."
                );
            }
            return;
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException(
                    "Un outil autorisé doit être sélectionné pour cette intention."
            );
        }
        if (directResponse != null && !directResponse.isBlank()) {
            throw new IllegalArgumentException(
                    "Une intention avec outil ne doit pas contenir de réponse directe."
            );
        }
    }

    public enum IntentType {
        CONTINUATION,
        NEW_INTENT,
        DIRECT_RESPONSE
    }

    public record ReferencedEntity(
            String type,
            Long id,
            String name,
            String parentType,
            Long parentId
    ) {
        public ReferencedEntity {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException(
                        "Le type de l’entité référencée est obligatoire."
                );
            }
            if (id == null || id < 1) {
                throw new IllegalArgumentException(
                        "L’identifiant de l’entité référencée doit être positif."
                );
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "Le nom de l’entité référencée est obligatoire."
                );
            }
            if (parentId != null && parentId < 1) {
                throw new IllegalArgumentException(
                        "L’identifiant de l’entité parente doit être positif."
                );
            }
        }
    }
}