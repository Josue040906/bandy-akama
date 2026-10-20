package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationIntentResolverTest {
    private final ConversationIntentResolver resolver =
            new ConversationIntentResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final com.fasterxml.jackson.databind.JsonNode activeState =
            objectMapper.createObjectNode().put("toolName", "searchEmployees");

    @Test
    void classifiesSameDepartmentAndFilterFollowUpsAsContinuation() {
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve(
                        "Maintenant, cherche celles de moins de 30 ans dans la même direction.",
                        activeState
                )
        );
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Et les hommes ?", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Montre leurs matricules.", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Affiche les agents trouvés.", activeState)
        );
    }

    @Test
    void startsIndependentRequestsAndClarifiesAmbiguousShortReferences() {
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Explique la structure du Service du Personnel.",
                        activeState
                )
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Recherche les agents du Service Financier.",
                        activeState
                )
        );
        assertEquals(
                ConversationIntentResolver.Decision.CLARIFICATION_REQUIRED,
                resolver.resolve("Et ensuite ?", activeState)
        );
    }
}
