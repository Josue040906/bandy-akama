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
        assertTrue(instruction.contains("deux filtres FUZZY séparés"));
        assertTrue(instruction.contains("ordre prénom-nom"));
        assertTrue(instruction.contains("resultMode=COUNT"));
        assertTrue(instruction.contains("get_organization_info"));
        assertTrue(instruction.contains("N’invente ni agent"));
        assertTrue(instruction.contains("candidateReferences"));
        assertTrue(instruction.contains("Si plusieurs candidats sont possibles"));
        assertTrue(instruction.contains("si la liste est tronquée"));
        assertTrue(instruction.contains(
                "Ne demande jamais si l’utilisateur veut poursuivre"
        ));

        assertTrue(instruction.contains("Tu décides si chaque message est une nouvelle demande"));
        assertTrue(instruction.contains("reset_conversation_context"));
        assertTrue(instruction.contains("l’intention, et non le nom de"));
        assertTrue(instruction.contains("critères COMPLETS"));
        assertTrue(instruction.contains("N’utilise jamais de marqueur remove ni de fusion implicite"));
    }
}
