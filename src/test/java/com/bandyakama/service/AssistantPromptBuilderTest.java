package com.bandyakama.service;

import com.bandyakama.assistant.AssistantProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantPromptBuilderTest {
    @Test
    void buildsTheSystemInstructionFromProfileAndConversationRules() {
        String instruction = AssistantPromptBuilder.buildSystemInstruction(
                new AssistantProfile(
                        "Bandy",
                        "Assistant RH",
                        "Consignes configurées."
                )
        );

        assertTrue(instruction.contains("Tu es Bandy."));
        assertTrue(instruction.contains("Rôle : Assistant RH"));
        assertTrue(instruction.contains("Consignes configurées."));
        assertTrue(instruction.contains("get_agent_profile"));
        assertTrue(instruction.contains("search_agents"));
        assertTrue(instruction.contains("resultMode=COUNT"));
        assertTrue(instruction.contains("get_organization_info"));
        assertTrue(instruction.contains("N’invente ni agent"));
        assertTrue(instruction.contains(
                "Ne demande jamais si l’utilisateur veut poursuivre"
        ));
    }
}
