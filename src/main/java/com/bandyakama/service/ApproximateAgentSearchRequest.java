package com.bandyakama.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ApproximateAgentSearchRequest {
    private static final Pattern APPROXIMATE_INTENT = Pattern.compile(
            "\\b(?:approximativ\\w*|approxim\\w*|faute|erreur|"
                    + "orthograph\\w*|typo|mal\\s+ecrit)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NAME_INTRODUCER = Pattern.compile(
            "\\b(?:agent|employe\\w*|personne)\\s+"
                    + "(?:nomm\\w*\\s+)?(.+?)"
                    + "(?=\\s+(?:du\\s+service|dans\\s+le\\s+service|"
                    + "au\\s+poste|dans\\s+la\\s+direction|"
                    + "il\\s+y\\s+a|peut-?etre|avec\\s+une\\s+faute|"
                    + "dont\\s+le\\s+nom|qui\\s+travaille)|"
                    + "\\s+et\\s+(?:propose|affiche|presente|présente|"
                    + "retourne|donne|montre|liste)\\b|"
                    + "\\s+avec\\s+(?:leur|son|le)\\s+"
                    + "(?:score|pourcentage)\\b|[,;.!?]|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SEARCH_NAME_INTRODUCER = Pattern.compile(
            "\\b(?:cherche|recherche|retrouve|trouve)\\s+"
                    + "(?:approximativement\\s+)?(.+?)"
                    + "(?=\\s+(?:il\\s+y\\s+a|peut-?etre|avec\\s+une\\s+faute|"
                    + "dont\\s+le\\s+nom|qui\\s+travaille)|"
                    + "\\s+et\\s+(?:propose|affiche|presente|présente|"
                    + "retourne|donne|montre|liste)\\b|"
                    + "\\s+avec\\s+(?:leur|son|le)\\s+"
                    + "(?:score|pourcentage)\\b|[,;.!?]|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FILTER_CONTINUATION = Pattern.compile(
            "^(?:et\\b|ajoute\\b|ajouter\\b|filtre\\b|filtrer\\b|"
                    + "dans\\s+le\\s+meme\\b|avec\\s+le\\s+meme\\b)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Map<String, Pattern> FILTER_PATTERNS = Map.of(
            "service", filterPattern("service"),
            "poste", filterPattern("poste"),
            "direction", filterPattern("direction")
    );

    private ApproximateAgentSearchRequest() {
    }

    static Optional<Map<String, Object>> resolve(
            String message,
            JsonNode previousState
    ) {
        String text = message == null ? "" : message.trim();
        if (text.isBlank()) {
            return Optional.empty();
        }

        boolean continuingSearch = "searchEmployeesApproximately".equals(
                previousState == null
                        ? ""
                        : previousState.path("toolName").asText("")
        ) && FILTER_CONTINUATION.matcher(text).find();
        boolean hasNamedPerson = NAME_INTRODUCER.matcher(text).find();
        if (!continuingSearch
                && !APPROXIMATE_INTENT.matcher(text).find()
                && !hasNamedPerson) {
            return Optional.empty();
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        if (continuingSearch) {
            JsonNode previousArguments = previousState.path("arguments");
            if (previousArguments.isObject()) {
                previousArguments.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) {
                        arguments.put(entry.getKey(), entry.getValue().asText());
                    }
                });
            }
        } else {
            String query = extractQuery(text);
            if (query == null) {
                return Optional.empty();
            }
            arguments.put("query", query);
        }

        if (!(arguments.get("query") instanceof String query)
                || query.isBlank()) {
            return Optional.empty();
        }
        if (query.toLowerCase(Locale.ROOT).matches(
                "^(?:du\\s+service|dans\\s+le\\s+service|"
                        + "au\\s+poste|dans\\s+la\\s+direction)\\b.*"
        )) {
            return Optional.empty();
        }
        FILTER_PATTERNS.forEach((field, pattern) -> extractFilter(pattern, text)
                .ifPresent(value -> arguments.put(field, value)));
        return Optional.of(Map.copyOf(arguments));
    }

    private static String extractQuery(String text) {
        Matcher nameMatcher = NAME_INTRODUCER.matcher(text);
        if (nameMatcher.find()) {
            return cleanQuery(nameMatcher.group(1));
        }

        Matcher searchMatcher = SEARCH_NAME_INTRODUCER.matcher(text);
        if (!searchMatcher.find()) {
            return null;
        }
        String candidate = cleanQuery(searchMatcher.group(1));
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (normalized.matches(
                "^(?:(?:un|une|les|des)\\s+)?(?:agent\\w*|personne\\w*|"
                        + "employe\\w*)(?:\\b.*)?$"
        )) {
            return null;
        }
        return candidate;
    }

    private static String cleanQuery(String query) {
        String cleaned = query == null ? "" : query.trim();
        cleaned = cleaned.replaceFirst(
                "^(?i)(?:l['’]|le\\s+|la\\s+|un\\s+|une\\s+)",
                ""
        );
        cleaned = cleaned.replaceFirst(
                "(?i)^(?:agent|employe\\w*|personne)\\s+",
                ""
        );
        cleaned = cleaned.replaceFirst(
                "(?i)\\s+(?:approximativement|avec\\s+une\\s+faute)$",
                ""
        );
        return cleaned.trim();
    }

    private static Pattern filterPattern(String field) {
        return Pattern.compile(
                "\\b" + field + "\\s+(?:de\\s+la\\s+|des\\s+|du\\s+|"
                        + "de\\s+|d['’])?(.+?)"
                        + "(?=\\s+(?:et\\s+(?:le\\s+)?(?:service|poste|"
                        + "direction)|puis|ensuite)|[,;.!?]|$)",
                Pattern.CASE_INSENSITIVE
        );
    }

    private static Optional<String> extractFilter(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = matcher.group(1).trim();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
