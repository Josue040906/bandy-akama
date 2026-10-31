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


}
