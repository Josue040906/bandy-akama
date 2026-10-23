package com.bandyakama.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApproximateAgentSearchRequestTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsTheOriginalNameAndAnyExplicitFilters() {
        Map<String, Object> arguments = ApproximateAgentSearchRequest.resolve(
                "Recherche approximativement l’agent Hery "
                        + "RANDRIAKOTOMANGAA du service Finances, "
                        + "il y a peut-être une faute.",
                null
        ).orElseThrow();

        assertEquals("Hery RANDRIAKOTOMANGAA", arguments.get("query"));
        assertEquals("Finances", arguments.get("service"));
    }

    @Test
    void doesNotIncludeTheWordAgentInTheApproximateName() {
        Map<String, Object> arguments = ApproximateAgentSearchRequest.resolve(
                "Recherche approximativement l’agent Hery RAKOTO.",
                null
        ).orElseThrow();

        assertEquals("Hery RAKOTO", arguments.get("query"));
    }

    @Test
    void usesFuzzySearchForNamedAgentLookupsEvenWithoutAnExplicitTypo() {
        Map<String, Object> arguments = ApproximateAgentSearchRequest.resolve(
                "Cherche l’agent Hery RAKOTO.",
                null
        ).orElseThrow();

        assertEquals("Hery RAKOTO", arguments.get("query"));
    }

    @Test
    void excludesResponseInstructionsFromThePersonName() {
        Map<String, Object> arguments = ApproximateAgentSearchRequest.resolve(
                "Cherche l'agent Hery RANDRIAKOTOMANGAA et propose les deux "
                        + "meilleurs candidats avec leur score de similarité.",
                null
        ).orElseThrow();

        assertEquals("Hery RANDRIAKOTOMANGAA", arguments.get("query"));
    }

    @Test
    void retainsTheOriginalNameAndPreviousFiltersWhenAddingAPost() throws Exception {
        JsonNode previous = objectMapper.readTree("""
                {
                  "toolName": "searchEmployeesApproximately",
                  "arguments": {
                    "query": "Hery RANDRIAKOTOMANGAA",
                    "service": "Finances"
                  }
                }
                """);

        Map<String, Object> arguments = ApproximateAgentSearchRequest.resolve(
                "Et si tu filtres par le poste de gestionnaire ?",
                previous
        ).orElseThrow();

        assertEquals("Hery RANDRIAKOTOMANGAA", arguments.get("query"));
        assertEquals("Finances", arguments.get("service"));
        assertEquals("gestionnaire", arguments.get("poste"));
    }

    @Test
    void leavesGeneralChatAndUnspecifiedNamesForTheModel() {
        assertTrue(ApproximateAgentSearchRequest.resolve(
                "Explique les missions du service RH.",
                null
        ).isEmpty());
        assertTrue(ApproximateAgentSearchRequest.resolve(
                "Cherche approximativement un agent, il y a une faute.",
                null
        ).isEmpty());
        assertTrue(ApproximateAgentSearchRequest.resolve(
                "Cherche un agent du service Finances.",
                null
        ).isEmpty());
    }
}
