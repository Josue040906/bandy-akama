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
    void keepsApproximateSearchWhenExplicitlyRetainingAndFilteringIt() {
        JsonNode approximateSearch = objectMapper.createObjectNode()
                .put("toolName", "searchEmployeesApproximately");

        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve(
                        "Garde la recherche de Hery RANDRIAKOTOMANGA et limite "
                                + "les candidats au service du Budget.",
                        approximateSearch
                )
        );
    }

    @Test
    void treatsCorrectedAgentIdentityAsAContinuationOfProfileLookup() {
        JsonNode profileLookup = objectMapper.createObjectNode()
                .put("toolName", "get_agent_profile");

        for (String correction : new String[]{
                "Il s'appelle TSIVOERY Josué Césaire",
                "Elle s’appelle Mialy RAKOTO",
                "Je crois qu’on l’appelle TSIVOERY Josué Césaire",
                "Je crois qu'il s'appelle TSIVOERY Josué Césaire",
                "On l’appelle TSIVOERY Josué Césaire",
                "Son nom complet est TSIVOERY Josué Césaire",
                "Matricule : AG-123"
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.CONTINUATION,
                    resolver.resolve(correction, profileLookup),
                    correction
            );
        }
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve(
                        "Il s'appelle TSIVOERY Josué Césaire",
                        activeState
                )
        );
    }

    @Test
    void classifiesQuestionsAboutTheLoadedAgentAsProfileContinuations() {
        JsonNode profileLookup = objectMapper.createObjectNode()
                .put("toolName", "get_agent_profile");

        for (String followUp : new String[]{
                "Parle-moi de son parcours académique.",
                "Quels diplômes a-t-il ?",
                "Et son âge ?",
                "Son poste actuel",
                "Parcours académique",
                "Sait-tu à quelle catégorie il appartient",
                "À quel corps appartient-il ?",
                "Quel est son statut ?",
                "Explique-moi sa situation professionnelle.",
                "Quelles sont ses responsabilités actuelles ?",
                "Et concernant son ancienneté ?"
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.CONTINUATION,
                    resolver.resolve(followUp, profileLookup),
                    followUp
            );
        }

        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Changeons de sujet : explique la comptabilité.",
                        profileLookup
                )
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Maintenant, cherche les agents du service financier.",
                        profileLookup
                )
        );
    }

    @Test
    void startsIndependentRequestsAndKeepsUnclassifiedMessagesInContext() {
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
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve("Et ensuite ?", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.CONTINUATION,
                resolver.resolve(
                        "Sait-tu à quelle catégorie il appartient ?",
                        activeState
                )
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
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve("Qu'est ce que tu sais sur moi ?", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve("Que sais tu de moi ?", activeState)
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve("Connais tu un certain Josié TSIVOARY ?", activeState)
        );
    }

    @Test
    void acceptsAnExplicitNewQuestionAfterRequestingClarification() {
        for (String answer : new String[]{
                "Une nouvelle question",
                "Je veux poser une nouvelle question",
                "Nouvelle question",
                "Changeons de sujet"
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.NEW_INTENT,
                    resolver.resolve(answer, activeState),
                    answer
            );
        }
        assertEquals(
                "Bien sûr. Quelle est votre nouvelle question ?",
                ConversationIntentResolver.newQuestionResponse(
                        "Une nouvelle question"
                ).orElseThrow()
        );
    }

    @Test
    void distinguishesIndependentRequestsFromQuestionsAboutTheLoadedProfile() {
        JsonNode profileLookup = objectMapper.createObjectNode()
                .put("toolName", "get_agent_profile");

        for (String independentRequest : new String[]{
                "Explique la comptabilité.",
                "Présente le système de gestion des ressources humaines.",
                "Décris le fonctionnement du recrutement."
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.NEW_INTENT,
                    resolver.resolve(independentRequest, profileLookup),
                    independentRequest
            );
        }

        for (String profileFollowUp : new String[]{
                "Explique sa situation professionnelle.",
                "Présente ses diplômes.",
                "Décris son parcours académique."
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.CONTINUATION,
                    resolver.resolve(profileFollowUp, profileLookup),
                    profileFollowUp
            );
        }
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
