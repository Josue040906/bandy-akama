package com.bandyakama.conversation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic conversational shortcuts only.
 * Intent classification and reference resolution are delegated to Gemini.
 */
public final class ConversationShortcuts {
    private ConversationShortcuts() {
    }

    public static Optional<String> greetingResponse(String message) {
        if (message == null) {
            return Optional.empty();
        }
        String normalized = normalize(message);
        String greeting = normalized.replaceAll("[!.?]+$", "").strip();
        if (normalized.matches(
                "^(?:bonjour|bonsoir|salut|coucou|hello|hi|hey|salama|"
                        + "manao ahoana)(?:\\s+(?:bandy(?: akama)?|assistant))?"
                        + "[!.?]*$"
        )) {
            return Optional.of(
                    greeting.equals("salama") || greeting.equals("manao ahoana")
                            ? "Salama ! Inona no azoko anampiana anao ?"
                            : "Bonjour ! Comment puis-je vous aider ?"
            );
        }
        return Optional.empty();
    }

    private static String normalize(String message) {
        String decomposed = Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );
        return decomposed.replaceAll("\\p{M}+", "")
                .replaceAll("\\s+([?!.])", "$1")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
