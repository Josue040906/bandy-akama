package com.bandyakama.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GreetingResponderTest {
    @Test
    void answersShortGreetingsInFrenchAndMalagasy() {
        assertEquals(
                "Bonjour ! Comment puis-je vous aider ?",
                GreetingResponder.greetingResponse("Bonjour Bandy !").orElseThrow()
        );
        assertEquals(
                "Salama ! Inona no azoko anampiana anao ?",
                GreetingResponder.greetingResponse("Manao ahoana ?").orElseThrow()
        );
    }

    @Test
    void doesNotClassifyOrdinaryRequestsAsGreetings() {
        assertEquals(
                java.util.Optional.empty(),
                GreetingResponder.greetingResponse("Bonjour, cherche les agentes.")
        );
        assertEquals(
                java.util.Optional.empty(),
                GreetingResponder.greetingResponse("Une nouvelle question")
        );
        assertEquals(
                java.util.Optional.empty(),
                GreetingResponder.greetingResponse("Explique la procédure de reclassement.")
        );
    }
}
