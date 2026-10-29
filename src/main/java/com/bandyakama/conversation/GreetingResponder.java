package com.bandyakama.conversation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Handles only short greetings that can safely be answered without model inference.
 * Conversational intent and follow-up classification belong to the language model.
 */
public final class GreetingResponder {
    private static final Pattern GREETING = Pattern.compile(
            "^(?:bonjour|bonsoir|salut|coucou|hello|hi|hey|salama|"
                    + "manao ahoana)(?:\\s+(?:bandy(?: akama)?|assistant))?"
                    + "[!.?]*$"
    );

    private GreetingResponder() {
    }

    public static Optional<String> greetingResponse(String message) {
        if (message == null) {
            return Optional.empty();
        }
        String normalized = normalize(message);
        String greeting = normalized.replaceAll("[!.?]+$", "").strip();
        if (!GREETING.matcher(normalized).matches()) {
            return Optional.empty();
        }
        return Optional.of(
                greeting.equals("salama") || greeting.equals("manao ahoana")
                        ? "Salama ! Inona no azoko anampiana anao ?"
                        : "Bonjour ! Comment puis-je vous aider ?"
        );
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
