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
        assertTrue(instruction.contains("deux filtres FUZZY distincts"));
        assertTrue(instruction.contains("ordre prénom-nom"));
        assertTrue(instruction.contains("resultMode=COUNT"));
        assertTrue(instruction.contains("get_organization_info"));
        assertTrue(instruction.contains("COUNT_DIRECTIONS"));
        assertTrue(instruction.contains("GET_HIERARCHY"));
        assertTrue(instruction.contains("relation hiérarchique agent-supérieur"));
        assertTrue(instruction.contains("N’invente ni agent"));
        assertTrue(instruction.contains(
                "Ne demande jamais si l’utilisateur veut poursuivre"
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
