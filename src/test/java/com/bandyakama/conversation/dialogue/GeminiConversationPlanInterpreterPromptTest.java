package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the generic semantic rules that guide model interpretation.
 * These checks do not replace behavioral tests of the validated state machine.
 */
class GeminiConversationPlanInterpreterPromptTest {

    @Test
    void resolvesImplicitReferencesWithoutGuessingAmbiguousAntecedents() {
        String instruction = GeminiConversationPlanInterpreter.SYSTEM_INSTRUCTION;

        assertTrue(instruction.contains("antécédent conversationnel suffisamment précis"));
        assertTrue(instruction.contains("compatibilité du sens"));
        assertTrue(instruction.contains("plusieurs antécédents restent plausibles"));
        assertTrue(instruction.contains("clarificationId"));
    }

    @Test
    void distinguishesCorrectionContinuationResumptionAndNewTopics() {
        String instruction = GeminiConversationPlanInterpreter.SYSTEM_INSTRUCTION;

        assertTrue(instruction.contains("CORRECT_OBJECTIVE"));
        assertTrue(instruction.contains("CONTINUE_OBJECTIVE"));
        assertTrue(instruction.contains("RESUME_OBJECTIVE"));
        assertTrue(instruction.contains("NEW_TOPIC_REQUEST"));
        assertTrue(instruction.contains("Un changement de thème"));
    }

    @Test
    void definesConfidenceLevelsWithoutDiscardingClearIndependentIntentions() {
        String instruction = GeminiConversationPlanInterpreter.SYSTEM_INSTRUCTION;

        assertTrue(instruction.contains(
                "MEDIUM signifie qu'au moins une intention est claire"
        ));
        assertTrue(instruction.contains(
                "LOW signifie qu'aucune opération modifiant l'état"
        ));
        assertTrue(instruction.contains(
                "N'utilise jamais LOW pour un message qui contient des opérations claires"
        ));
    }

    @Test
    void preservesIndependentIntentionsAndDoesNotAssumeTheFocusedObjective() {
        String instruction = GeminiConversationPlanInterpreter.SYSTEM_INSTRUCTION;

        assertTrue(instruction.contains("intentions distinctes"));
        assertTrue(instruction.contains("Le focus indique le sujet conversationnel privilégié"));
        assertTrue(instruction.contains("intention claire et une autre ambiguë"));
        assertFalse(instruction.contains("matricule"));
        assertFalse(instruction.contains("service RH"));
    }
}
