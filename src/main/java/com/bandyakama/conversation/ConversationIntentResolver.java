package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
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
                    + "\\d+\\s+ans?\\b|"
                    + "(?:hommes|femmes)\\s*[?!.]*$)"
    );
    private static final Pattern INDEPENDENT_INTENT = Pattern.compile(
            "^(?:maintenant\\s+)?(?:explique(?:-moi)?|decris(?:-moi)?|"
                    + "presente(?:-moi)?|"
                    + "comment\\s+(?:fonctionne|marche)|"
                    + "qu[' ]est-ce|quelle\\s+est\\s+la\\s+structure|"
                    + "nouvelle\\s+recherche|changeons\\s+de\\s+sujet)\\b"
    );
    private static final Pattern NEW_SEARCH = Pattern.compile(
            "^maintenant\\s+(?:cherche|recherche|trouve|liste|compte)\\b"
    );
    private static final Pattern ORGANIZATION_SCOPE = Pattern.compile(
            "\\b(?:direction|service|departement|structure|unite|bureau|"
                    + "division|cellule)\\b"
    );
    private static final Pattern PERSONAL_PROFILE_QUESTION = Pattern.compile(
            "\\b(?:que\\s+sais-tu\\s+(?:sur|de)\\s+moi|"
                    + "qu[' ]est-ce\\s+que\\s+tu\\s+sais\\s+(?:sur|de)\\s+moi|"
                    + "mon\\s+profil|mes\\s+informations|mon\\s+identite|"
                    + "mon\\s+(?:nom|prenom|email)|"
                    + "mon\\s+adresse\\s+e-?mail|"
                    + "mon\\s+matricule|ma\\s+carriere|mon\\s+poste|"
                    + "mon\\s+service|ma\\s+direction)\\b"
    );

    public Decision resolve(String message, JsonNode activeState) {
        if (!hasActiveIntent(activeState)) {
            return Decision.NEW_INTENT;
        }

        String normalized = normalize(message);
        if (PERSONAL_PROFILE_QUESTION.matcher(normalized).find()) {
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
        if (introducesDifferentOrganizationScope(normalized, activeState)) {
            return Decision.NEW_INTENT;
        }
        if (CONTINUATION.matcher(normalized).find()) {
            return Decision.CONTINUATION;
        }
        if (INDEPENDENT_INTENT.matcher(normalized).find()
                || NEW_SEARCH.matcher(normalized).find()) {
            return Decision.NEW_INTENT;
        }
        if (looksLikeIndependentRequest(normalized)) {
            return Decision.NEW_INTENT;
        }
        return Decision.CLARIFICATION_REQUIRED;
    }

    public String clarificationMessage() {
        return "Souhaitez-vous poursuivre la recherche précédente ou poser une "
                + "nouvelle question ?";
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
                "^(?:cherche|recherche|trouve|liste|compte|donne|"
                        + "combien|qui|ou|quand|pourquoi|"
                        + "explique|decris|presente)\\b.*"
        );
    }

    private String normalize(String message) {
        String decomposed = Normalizer.normalize(
                message.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );
        return decomposed.replaceAll("\\p{M}+", "")
                .replaceAll("[,;:]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    public enum Decision {
        CONTINUATION,
        NEW_INTENT,
        CLARIFICATION_REQUIRED
    }
}
