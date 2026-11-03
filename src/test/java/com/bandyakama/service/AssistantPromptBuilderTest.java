package com.bandyakama.service;

import com.bandyakama.assistant.AssistantProfile;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantPromptBuilderTest {
    @Test
    void buildsDomainNeutralConversationRulesFromProfile() {
        String instruction = AssistantPromptBuilder.buildSystemInstruction(
                new AssistantProfile(
                        "Bandy",
                        "Assistant généraliste",
                        "Consignes configurées."
                ),
                List.of()
        );

        assertTrue(instruction.contains("Tu es Bandy."));
        assertTrue(instruction.contains("Rôle : Assistant généraliste"));
        assertTrue(instruction.contains("Consignes configurées."));
        assertTrue(instruction.contains("Traite séparément les objectifs indépendants"));
        assertTrue(instruction.contains("Aucun outil n’est disponible"));
        assertTrue(instruction.contains("réponds directement"));
        assertFalse(instruction.contains("search_agents"));
        assertFalse(instruction.contains("get_agent_profile"));
        assertFalse(instruction.contains("get_organization_info"));
        assertFalse(instruction.contains("filtres FUZZY"));
    }

    @Test
    void exposesOnlyTheEnabledCapabilitiesProvidedByTheApplication() {
        ToolDefinition calculator = new ToolDefinition(
                "calculate_total",
                "Calcule un total à partir de valeurs.",
                "POST",
                "https://example.test/calculate",
                List.of(new ToolParameter(
                        "values",
                        "string",
                        "Valeurs à additionner au format attendu.",
                        true
                ))
        );
        calculator.setWhenToUse("Lorsque l’utilisateur demande un calcul de total.");

        String instruction = AssistantPromptBuilder.buildSystemInstruction(
                new AssistantProfile("Bandy", "Assistant", "Consignes."),
                List.of(calculator)
        );

        assertTrue(instruction.contains("calculate_total"));
        assertTrue(instruction.contains("Calcule un total"));
        assertTrue(instruction.contains("Lorsque l’utilisateur demande"));
        assertTrue(instruction.contains("values (string, obligatoire)"));
        assertFalse(instruction.contains("search_agents"));
        assertFalse(instruction.contains("get_agent_profile"));
    }
}
