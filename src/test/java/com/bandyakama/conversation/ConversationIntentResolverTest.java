package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Maintenant, explique la structure du Service du Personnel.",
                        activeState
                )
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Maintenant, cherche les hommes du Service Financier.",
                        activeState
                )
        );
        assertEquals(
                ConversationIntentResolver.Decision.CLARIFICATION_REQUIRED,
                resolver.resolve("Et ensuite ?", activeState)
        );
    }

    @Test
    void treatsAnExplicitlyDifferentOrganizationAsANewSearch() throws Exception {
        JsonNode state = objectMapper.readTree("""
                {
                  "toolName": "searchEmployees",
                  "criteria": {
                    "filters": [
                      {
                        "field": "direction",
                        "operator": "EQ",
                        "value": "Direction Générale de l'Administration"
                      },
                      {
                        "field": "sexe",
                        "operator": "EQ",
                        "value": "F"
                      }
                    ]
                  }
                }
                """);

        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Cherche les hommes dans le Service du Personnel.",
                        state
                )
        );
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Et les hommes ?", state)
        );
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve(
                        "Maintenant, cherche celles dans la même direction.",
                        state
                )
        );
    }

    @Test
    void classifiesPersonalProfileQuestionsAsIndependentIntents() {
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve("Que sais-tu sur moi ?", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve("Parle-moi de ma carrière.", activeState)
        );
    }

    @Test
    void keepsActiveSearchStateWhenTheUserSendsOnlyAGreeting() {
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Bonjour Bandy !", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Salama", activeState)
        );
        assertEquals(
                "Bonjour ! Comment puis-je vous aider ?",
                ConversationIntentResolver.greetingResponse("Bonjour Bandy !")
                        .orElseThrow()
        );
        assertEquals(
                "Salama ! Inona no azoko anampiana anao ?",
                ConversationIntentResolver.greetingResponse("Manao ahoana ?")
                        .orElseThrow()
        );
        assertEquals(
                java.util.Optional.empty(),
                ConversationIntentResolver.greetingResponse(
                        "Bonjour, cherche les agentes."
                )
        );
    }
}
