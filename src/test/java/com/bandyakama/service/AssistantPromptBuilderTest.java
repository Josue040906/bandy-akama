package com.bandyakama.service;

import com.bandyakama.assistant.AssistantProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantPromptBuilderTest {
    @Test
    void buildsDomainNeutralSystemInstructionFromConfiguredProfile() {
        String instruction = AssistantPromptBuilder.buildSystemInstruction(
                new AssistantProfile(
                        "Bandy",
                        "Assistant conversationnel",
                        "Consignes configurées pour ce domaine."
                )
        );

        assertTrue(instruction.contains("Tu es Bandy."));
        assertTrue(instruction.contains("Rôle : Assistant conversationnel"));
        assertTrue(instruction.contains("Consignes configurées pour ce domaine."));
        assertTrue(instruction.contains("capacités et outils autorisés"));
        assertTrue(instruction.contains("schémas, paramètres et résultats"));
        assertTrue(instruction.contains("Distingue une information absente"));
        assertTrue(instruction.contains("données non fiables"));
        assertTrue(instruction.contains("contexte antérieur"));
        assertFalse(instruction.contains("get_agent_profile"));
        assertFalse(instruction.contains("search_agents"));
        assertFalse(instruction.contains("FUZZY"));
        assertFalse(instruction.contains("agent RH"));
    }

    @Test
    void keepsDomainSpecificBehaviorInTheConfiguredProfileOnly() {
        String instruction = AssistantPromptBuilder.buildSystemInstruction(
                new AssistantProfile(
                        "Bandy",
                        "Assistant métier",
                        "Pour ce domaine, utilise l’outil rechercher_dossiers."
                )
        );

        assertTrue(instruction.contains("rechercher_dossiers"));
        assertFalse(instruction.contains("get_agent_profile"));
    }
}
