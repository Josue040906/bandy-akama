package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Conservative, domain-neutral fallback used only when semantic dialogue
 * interpretation is explicitly disabled. Production dialogue understanding
 * is owned by the DialogueStateManager and GeminiDialogueInterpreter.
 */
@Component
public class ConversationIntentResolver {
    private static final Pattern EXPLICIT_TOPIC_CHANGE = Pattern.compile(
            "^(?:nouvelle?\\s+(?:question|tache|recherche)|"
                    + "changeons\\s+de\\s+sujet|"
                    + "passons\\s+a\\s+(?:autre\\s+chose|un\\s+autre\\s+sujet)|"
                    + "parlons\\s+d['’]?(?:un\\s+)?autre\\s+sujet|"
                    + "rien\\s+a\\s+voir|sans\\s+rapport|autre\\s+chose)\\b"
    );

    private static final Pattern CORRECTION_OR_CONTINUATION = Pattern.compile(
            "^(?:en\\s+fait\\b|je\\s+crois\\s+que\\b|je\\s+pense\\s+que\\b|"
                    + "je\\s+voulais\\s+dire\\b|je\\s+me\\s+suis\\s+trompe\\b|"
                    + "correction\\b|rectification\\b|plus\\s+precisement\\b|"
                    + "plutot\\b|et\\b|aussi\\b|egalement\\b|en\\s+plus\\b|"
                    + "dans\\s+ce\\s+cas\\b|alors\\b|ensuite\\b|"
                    + "ajoute(?:r)?\\b|garde\\b|conserve\\b|filtre(?:r)?\\b|"
                    + "continue\\b|poursuis\\b|reprends\\b|complete\\b|"
                    + "precise\\b|detaille\\b|"
                    + "peux[- ]tu\\s+(?:preciser|detailler|completer|continuer)\\b|"
                    + "pourrais[- ]tu\\s+(?:preciser|detailler|completer)\\b|"
                    + "(?:affiche|montre|liste)\\s+les\\s+resultats\\b)"
    );

    private static final Pattern REFERENTIAL_LANGUAGE = Pattern.compile(
            "\\b(?:son|sa|ses|leur|leurs|celui|celle|ceux|celles|"
                    + "ceci|cela|ca|eux|elle|elles|ils|il|a-t-il|est-elle)\\b"
    );

    private static final Pattern SELF_REFERENCE = Pattern.compile(
            "\\b(?:moi|mon|ma|mes|nous|notre|nos)\\b"
    );

    private static final Pattern INDEPENDENT_REQUEST = Pattern.compile(
            "^(?:maintenant\\s+)?(?:explique(?:-moi)?|decris(?:-moi)?|"
                    + "presente(?:-moi)?|developpe(?:-moi)?|definis(?:-moi)?|"
                    + "compare|calcule|cree|redige|ecris|prepare|analyse|"
                    + "resume|traduis|cherche|recherche|trouve|liste|compte|"
                    + "genere|concois|connais[- ]tu|parle[- ]moi\\s+de|"
                    + "comment\\s+(?:fonctionne|marche)|"
                    + "combien\\b|quel\\s+est\\s+le\\s+nombre\\b|"
                    + "quelle\\s+est\\b|qu['’ ]est[- ]ce\\b|"
                    + "ou\\s+se\\s+trouve\\b)\\b"
    );

    private static final Pattern NEW_QUESTION_REPLY = Pattern.compile(
            "^(?:(?:je\\s+)?(?:veux|souhaite|voudrais)\\s+)?"
                    + "(?:poser\\s+)?(?:une\\s+)?nouvelle\\s+question\\b"
    );

    public Decision resolve(String message, JsonNode activeState) {
        if (greetingResponse(message).isPresent()) {
            return Decision.CONTINUATION;
        }
        String normalized = normalize(message);
        if (NEW_QUESTION_REPLY.matcher(normalized).find()
                || EXPLICIT_TOPIC_CHANGE.matcher(normalized).find()) {
            return Decision.NEW_INTENT;
        }
        if (!hasActiveIntent(activeState)) {
            return Decision.NEW_INTENT;
        }

        // Explicit corrections and follow-up instructions are domain-neutral.
        if (CORRECTION_OR_CONTINUATION.matcher(normalized).find()) {
            return Decision.CONTINUATION;
        }

        // "y a-t-il" is an impersonal construction, not a reference to prior context.
        String referenceProbe = normalized.replace("y a-t-il", "");
        if (REFERENTIAL_LANGUAGE.matcher(referenceProbe).find()) {
            return Decision.CONTINUATION;
        }
        if (SELF_REFERENCE.matcher(normalized).find()) {
            return Decision.NEW_INTENT;
        }
        if (INDEPENDENT_REQUEST.matcher(normalized).find()) {
            return Decision.NEW_INTENT;
        }

        // In fallback mode, ambiguity must not leak an unrelated task's state.
        return Decision.NEW_INTENT;
    }

    public static Optional<String> greetingResponse(String message) {
        if (message == null) {
            return Optional.empty();
        }
        String normalized = normalize(message);
        if (normalized.matches(
                "^(?:bonjour|bonsoir|salut|coucou|hello|hi|hey|salama|"
                        + "manao ahoana)(?:\\s+(?:bandy(?: akama)?|assistant))?"
                        + "[!.?]*$"
        )) {
            return Optional.of(
                    normalized.startsWith("salama")
                            || normalized.startsWith("manao ahoana")
                            ? "Salama ! Inona no azoko anampiana anao ?"
                            : "Bonjour ! Comment puis-je vous aider ?"
            );
        }
        return Optional.empty();
    }

    public static Optional<String> newQuestionResponse(String message) {
        if (message == null) {
            return Optional.empty();
        }
        return NEW_QUESTION_REPLY.matcher(normalize(message)).find()
                ? Optional.of("Bien sûr. Quelle est votre nouvelle question ?")
                : Optional.empty();
    }

    private boolean hasActiveIntent(JsonNode state) {
        return state != null
                && state.isObject()
                && !state.path("toolName").asText("").isBlank();
    }

    private static String normalize(String message) {
        if (message == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );
        return decomposed.replaceAll("\\p{M}+", "")
                .replaceAll("[,;:]+", " ")
                .replaceAll("\\s+([?!.])", "$1")
                .replaceAll("\\s+", " ")
                .strip();
    }

    public enum Decision {
        CONTINUATION,
        NEW_INTENT
    }
}
