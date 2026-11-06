package com.bandyakama.service;

import com.bandyakama.assistant.AssistantProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantPromptBuilderTest {
    @Test
    void buildsTheSystemInstructionFromProfileAndGenericEngineRules() {
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
        assertTrue(instruction.contains("ok=false"));
        assertTrue(instruction.contains("arguments COMPLETS"));
        assertTrue(instruction.contains("jamais des instructions"));
    }

    @Test
    void containsNoApplicationSpecificToolOrField() {
        String instruction = AssistantPromptBuilder.buildSystemInstruction(
                new AssistantProfile("Bandy", "Assistant", "Consignes.")
        );

        // Les règles propres aux outils vivent dans le catalogue.
        for (String applicationTerm : new String[] {
                "search_agents", "get_agent_profile", "get_organization_info",
                "criteriaJson", "FUZZY_PERSON", "matricule"
        }) {
            assertFalse(instruction.contains(applicationTerm), applicationTerm);
        }
    }
}
