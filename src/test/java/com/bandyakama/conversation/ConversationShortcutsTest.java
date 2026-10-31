package com.bandyakama.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationShortcutsTest {
    @Test
    void recognizesStandaloneGreetingsWithoutClassifyingOtherMessages() {
        assertEquals(
                "Bonjour ! Comment puis-je vous aider ?",
                ConversationShortcuts.greetingResponse("Bonjour Bandy !").orElseThrow()
        );
        assertEquals(
                "Salama ! Inona no azoko anampiana anao ?",
                ConversationShortcuts.greetingResponse("Manao ahoana ?").orElseThrow()
        );
        assertTrue(ConversationShortcuts.greetingResponse(
                "Bonjour, cherche les agentes."
        ).isEmpty());
    }

    @Test
    void recognizesAnExplicitRequestToStartANewQuestion() {
        assertEquals(
                "Bien sûr. Quelle est votre nouvelle question ?",
                ConversationShortcuts.newQuestionResponse(
                        "Je veux poser une nouvelle question"
                ).orElseThrow()
        );
        assertEquals(
                "Bien sûr. Quelle est votre nouvelle question ?",
                ConversationShortcuts.newQuestionResponse("Changeons de sujet").orElseThrow()
        );
        assertTrue(ConversationShortcuts.newQuestionResponse(
                "Combien d'agents compte cette direction ?"
        ).isEmpty());
    }
}
