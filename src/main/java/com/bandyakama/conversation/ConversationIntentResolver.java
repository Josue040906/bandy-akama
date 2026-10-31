package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class ConversationIntentResolver {
    private static final Pattern CONTINUATION = Pattern.compile(
            "^(?:et\\s+(?:les\\s+)?(?:hommes|femmes|agents|agentes|"
                    + "ceux|celles|matricules?)\\b|"
                    + "leurs?\\b|ceux\\b|celles\\b|eux\\b|elles\\b|"
                    + "premier\\b|premiere\\b|deuxieme\\b|dernier\\b|"
                    + "matricules?\\b|lesquels?\\b|lesquelles\\b|"
                    + "affiche\\s+les\\s+agents\\s+trouves\\b|"
                    + "donne-moi\\s+leurs?\\b|"
                    + "meme\\b|maintenant\\s+(?:ceux|celles|les|la|le|"
                    + "hommes|femmes)\\b|"
                    + "(?:moins|plus)\\s+de\\s+\\d+\\b|"
                    + "(?:au\\s+moins|au\\s+plus)\\s+\\d+\\b|"
                    + "\\d+\\s+ans?\\b|"
                    + "(?:hommes|femmes)\\s*[?!.]*$)"
    );
    private static final Pattern COUNT_FOLLOW_UP = Pattern.compile(
            "^(?:et\\s+)?(?:combien\\b|quel\\s+est\\s+le\\s+nombre\\b|"
                    + "donne[- ]moi\\s+le\\s+nombre\\b|compte[- ]les\\b|"
                    + "quel\\s+est\\s+leur\\s+effectif\\b)"
    );
    private static final Pattern INDEPENDENT_INTENT = Pattern.compile(
            "^(?:maintenant\\s+)?(?:explique(?:-moi)?|decris(?:-moi)?|"
                    + "presente(?:-moi)?|"
                    + "comment\\s+(?:fonctionne|marche)|"
                    + "nouvelle\\s+(?:question|recherche)|"
                    + "changeons\\s+de\\s+sujet|"
                    + "passons\\s+a\\s+(?:autre\\s+chose|un\\s+autre\\s+sujet)|"
                    + "parlons\\s+d['’]?(?:un\\s+)?autre\\s+sujet|"
                    + "rien\\s+a\\s+voir)\\b"
    );
    private static final Pattern EXPLICIT_TOPIC_CHANGE = Pattern.compile(
            "^(?:nouvelle\\s+(?:question|recherche)|"
                    + "changeons\\s+de\\s+sujet|"
                    + "passons\\s+a\\s+(?:autre\\s+chose|un\\s+autre\\s+sujet)|"
                    + "parlons\\s+d['’]?(?:un\\s+)?autre\\s+sujet|"
                    + "rien\\s+a\\s+voir)\\b"
    );
    private static final Pattern NEW_SEARCH = Pattern.compile(
            "^maintenant\\s+(?:cherche|recherche|trouve|liste|compte)\\b"
    );
    private static final Pattern NEW_QUESTION_REPLY = Pattern.compile(
            "^(?:(?:je\\s+)?(?:veux|souhaite|voudrais)\\s+)?"
                    + "(?:poser\\s+)?(?:une\\s+)?nouvelle\\s+question\\b"
    );
    private static final Pattern APPROXIMATE_SEARCH_FOLLOW_UP = Pattern.compile(
            "^(?:et\\b|ajoute\\b|ajouter\\b|filtre\\b|filtrer\\b|"
                    + "garde\\b|conserve\\b|poursuis\\b|continue\\b|"
                    + "dans\\s+le\\s+meme\\b|avec\\s+le\\s+meme\\b).*"
                    + "\\b(?:service|poste|direction)\\b.*$"
    );
    private static final Pattern ORGANIZATION_SCOPE = Pattern.compile(
            "\\b(?:direction|service|departement|structure|unite|bureau|"
                    + "division|cellule)\\b"
    );
    private static final Pattern ORGANIZATION_DATA_QUESTION = Pattern.compile(
            "^(?:(?:combien\\b.*\\b(?:directions?|services?|postes?|agents?|effectifs?)\\b)|"
                    + "(?:quel(?:s|les)?\\b.*\\b(?:directions?|services?|postes?|responsables?|hierarchie|organigramme)\\b)|"
                    + "(?:liste|affiche|presente|decris|explique|donne[- ]moi)\\b.*"
                    + "\\b(?:directions?|services?|postes?|responsables?|hierarchie|organigramme)\\b|"
                    + "(?:qui\\s+(?:est|sont)\\b.*\\b(?:responsable|chef|directeur|directrice)\\b)"
                    + ").*$"
    );
    private static final Pattern PERSONAL_PROFILE_QUESTION = Pattern.compile(
            "\\b(?:que\\s+sais[- ]tu\\s+(?:sur|de)\\s+moi|"
                    + "qu['’ ]est[- ]ce\\s+que\\s+tu\\s+sais\\s+(?:sur|de)\\s+moi|"
                    + "mon\\s+profil|mes\\s+informations|mon\\s+identite|"
                    + "mon\\s+(?:nom|prenom|email)|"
                    + "mon\\s+adresse\\s+e-?mail|"
                    + "mon\\s+matricule|ma\\s+carriere|mon\\s+poste|"
                    + "mon\\s+service|ma\\s+direction)\\b"
    );

    public Decision resolve(String message, JsonNode activeState) {
        if (greetingResponse(message).isPresent()) {
            return Decision.CONTINUATION;
        }
        if (!hasActiveIntent(activeState)) {
            return Decision.NEW_INTENT;
        }

        String normalized = normalize(message);
        if (PERSONAL_PROFILE_QUESTION.matcher(normalized).find()) {
            return Decision.NEW_INTENT;
        }
        if (NEW_QUESTION_REPLY.matcher(normalized).find()) {
            return Decision.NEW_INTENT;
        }
        if (ORGANIZATION_DATA_QUESTION.matcher(normalized).matches()
                && !"get_organization_info".equals(
                        activeState.path("toolName").asText()
                )) {
            return Decision.NEW_INTENT;
        }
        if ("get_agent_profile".equals(
                activeState.path("toolName").asText()
        )) {
            return NEW_SEARCH.matcher(normalized).find()
                    || EXPLICIT_TOPIC_CHANGE.matcher(normalized).find()
                    ? Decision.NEW_INTENT
                    : Decision.CONTINUATION;
        }
        if ("search_agents".equals(activeState.path("toolName").asText())) {
            if (EXPLICIT_TOPIC_CHANGE.matcher(normalized).find()
                    || INDEPENDENT_INTENT.matcher(normalized).find()
                    || NEW_SEARCH.matcher(normalized).find()) {
                return Decision.NEW_INTENT;
            }
            if (COUNT_FOLLOW_UP.matcher(normalized).find()
                    || CONTINUATION.matcher(normalized).find()
                    || APPROXIMATE_SEARCH_FOLLOW_UP
                    .matcher(normalized)
                    .matches()) {
                return Decision.CONTINUATION;
            }
            return Decision.NEW_INTENT;
        }
        if (INDEPENDENT_INTENT.matcher(normalized).find()) {
            return Decision.NEW_INTENT;
        }
        if (normalized.contains("meme direction")
                || normalized.contains("meme service")
                || normalized.contains("meme departement")
                || normalized.contains("leurs matricules")
                || normalized.contains("leur matricule")
                || normalized.contains("qui est le plus age")
                || normalized.contains("la plus agee")) {
            return Decision.CONTINUATION;
        }
        if ("searchEmployeesApproximately".equals(
                activeState.path("toolName").asText()
        ) && APPROXIMATE_SEARCH_FOLLOW_UP
                .matcher(normalized)
                .matches()) {
            return Decision.CONTINUATION;
        }
        if (introducesDifferentOrganizationScope(normalized, activeState)) {
            return Decision.NEW_INTENT;
        }
        if (CONTINUATION.matcher(normalized).find()) {
            return Decision.CONTINUATION;
        }
        if (NEW_SEARCH.matcher(normalized).find()) {
            return Decision.NEW_INTENT;
        }
        if (looksLikeIndependentRequest(normalized)) {
            return Decision.NEW_INTENT;
        }
        return Decision.CONTINUATION;
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

    private boolean introducesDifferentOrganizationScope(
            String message,
            JsonNode activeState
    ) {
        if (!ORGANIZATION_SCOPE.matcher(message).find()) {
            return false;
        }
        JsonNode filters = activeState.path("criteria").path("filters");
        if (!filters.isArray()) {
            return false;
        }
        for (JsonNode filter : filters) {
            String field = normalize(filter.path("field").asText(""));
            if (field.matches(".*(direction|service|departement|structure|unite|"
                    + "bureau|division|cellule).*")) {
                String value = normalize(filter.path("value").asText(""));
                if (!value.isBlank() && message.contains(value)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean looksLikeIndependentRequest(String message) {
        return message.matches(
                "^(?:maintenant\\s+)?(?:cherche|recherche|trouve|liste|compte|donne|"
                        + "combien|qui|ou|quand|pourquoi|"
                        + "explique|decris|presente|connais[- ]tu)\\b.*"
        );
    }

    private static String normalize(String message) {
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
