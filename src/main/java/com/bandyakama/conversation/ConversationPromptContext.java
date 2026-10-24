package com.bandyakama.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class ConversationPromptContext {
    private static final String HISTORY_MARKER = "\n\nBANDY_HISTORY_JSON:\n";

    private ConversationPromptContext() {
    }

    public static Parsed parse(String serialized, ObjectMapper objectMapper) {
        int markerIndex = serialized.lastIndexOf(HISTORY_MARKER);
        if (markerIndex < 0) {
            return new Parsed(serialized, List.of());
        }

        String historyJson = serialized.substring(
                markerIndex + HISTORY_MARKER.length()
        );
        try {
            JsonNode history = objectMapper.readTree(historyJson);
            if (!history.isArray()) {
                throw new IllegalStateException(
                        "L’historique de conversation doit être un tableau JSON."
                );
            }

            List<Message> messages = new ArrayList<>();
            for (JsonNode item : history) {
                String role = item.path("role").asText();
                String content = item.path("content").asText();
                if (!role.equals("user") && !role.equals("assistant")) {
                    throw new IllegalStateException(
                            "Rôle invalide dans l’historique de conversation."
                    );
                }
                messages.add(new Message(role, content));
            }
            return new Parsed(
                    serialized.substring(0, markerIndex),
                    List.copyOf(messages)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "L’historique de conversation n’est pas un JSON valide.",
                    exception
            );
        }
    }

    public static String marker() {
        return HISTORY_MARKER;
    }

    public record Message(String role, String content) {
    }

    public record Parsed(String context, List<Message> history) {
    }
}
