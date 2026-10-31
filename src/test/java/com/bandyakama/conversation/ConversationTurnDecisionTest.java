package com.bandyakama.conversation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversationTurnDecisionTest {
    @Test
    void acceptsAValidatedContinuationWithAReferencedDirection() {
        ConversationTurnDecision decision = new ConversationTurnDecision(
                ConversationTurnDecision.IntentType.CONTINUATION,
                "get_organization_info",
                "COUNT_EMPLOYEES_BY_STRUCTURE",
                Map.of("directionId", 1),
                new ConversationTurnDecision.ReferencedEntity(
                        "DIRECTION",
                        1L,
                        "Direction Générale de l'Administration",
                        null,
                        null
                ),
                false,
                null,
                null
        );

        assertDoesNotThrow(decision::validate);
    }

    @Test
    void clarificationCannotCarryAnExecutableTool() {
        ConversationTurnDecision decision = new ConversationTurnDecision(
                ConversationTurnDecision.IntentType.CONTINUATION,
                "get_organization_info",
                "COUNT_EMPLOYEES_BY_STRUCTURE",
                Map.of("directionId", 1),
                null,
                true,
                "De quelle direction parlez-vous ?",
                null
        );

        assertThrows(IllegalArgumentException.class, decision::validate);
    }

    @Test
    void clarificationRequiresAQuestion() {
        ConversationTurnDecision decision = new ConversationTurnDecision(
                ConversationTurnDecision.IntentType.CONTINUATION,
                null,
                null,
                Map.of(),
                null,
                true,
                " ",
                null
        );

        assertThrows(IllegalArgumentException.class, decision::validate);
    }

    @Test
    void directResponseCannotSelectABusinessTool() {
        ConversationTurnDecision decision = new ConversationTurnDecision(
                ConversationTurnDecision.IntentType.DIRECT_RESPONSE,
                "search_agents",
                null,
                Map.of(),
                null,
                false,
                null,
                "Bonjour !"
        );

        assertThrows(IllegalArgumentException.class, decision::validate);
    }

    @Test
    void directResponseMustContainText() {
        ConversationTurnDecision decision = new ConversationTurnDecision(
                ConversationTurnDecision.IntentType.DIRECT_RESPONSE,
                null,
                null,
                Map.of(),
                null,
                false,
                null,
                null
        );

        assertThrows(IllegalArgumentException.class, decision::validate);
    }

    @Test
    void referencedEntityRequiresVerifiedPositiveIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
                new ConversationTurnDecision.ReferencedEntity(
                        "DIRECTION", 0L, "Direction X", null, null
                )
        );
    }
}