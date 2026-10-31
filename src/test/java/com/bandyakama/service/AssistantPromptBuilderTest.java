package com.bandyakama.service;

import com.bandyakama.assistant.AssistantProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;

class AssistantPromptBuilderTest {
    @Test
    void buildsTheSystemInstructionFromProfileAndConversationRules() {
        String instruction = AssistantPromptBuilder.buildSystemInstruction(
                new AssistantProfile(
                        "Bandy",
                        "Assistant RH",
                        "Consignes configurées."
                ),
                List.of(
                        tool("search_agents", "Recherche les agents.", "criteriaJson"),
                        tool("get_agent_profile", "Consulte le profil actuel.", "query"),
                        tool("get_organization_info", "Consulte l’organisation.", "operation")
                )
        );

        assertTrue(instruction.contains("Tu es Bandy."));
        assertTrue(instruction.contains("Rôle : Assistant RH"));
        assertTrue(instruction.contains("Consignes configurées."));
        assertTrue(instruction.contains("get_agent_profile"));
        assertTrue(instruction.contains("search_agents"));
        assertTrue(instruction.contains("get_organization_info"));
        assertTrue(instruction.contains("Recherche les agents."));
        assertTrue(instruction.contains("Consulte le profil actuel."));
        assertTrue(instruction.contains("Paramètre de test."));
        assertTrue(instruction.contains("obligatoire"));
        assertTrue(instruction.contains("Quand l’utiliser"));
        assertTrue(instruction.contains("Ne demande jamais si l’utilisateur veut poursuivre"));
        assertTrue(instruction.contains("ne remplace pas son dossier actuel"));
        assertTrue(instruction.contains("remplace l’ancien filtre"));
        assertTrue(instruction.contains("NEW_INTENT"));
        assertTrue(instruction.contains("DIRECT_RESPONSE"));
        assertTrue(instruction.contains("arguments complets"));
        assertTrue(instruction.contains(
                "GESTION DU CONTEXTE CONVERSATIONNEL ET DES FILTRES :\n"
                        + "- CONTINUATION :"
        ));
    }
    private ToolDefinition tool(String name, String description, String parameterName) {
        ToolDefinition tool = new ToolDefinition(
                name, description, "GET", "https://example.test/api", List.of(
                        new ToolParameter(parameterName, "string", "Paramètre de test.", true)
                )
        );
        tool.setWhenToUse("Utiliser pour le cas décrit.");
        return tool;
    }

}
