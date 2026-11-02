package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationIntentResolverTest {
    private final ConversationIntentResolver resolver =
            new ConversationIntentResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonNode activeState =
            objectMapper.createObjectNode().put("toolName", "lookup_records");

    @Test
    void recognizesGenericFollowUpsAcrossDifferentToolDomains() {
        for (String followUp : new String[]{
                "Et ajoute une comparaison par région.",
                "Peux-tu préciser ?",
                "Je voulais dire le trimestre précédent.",
                "En fait, utilise la période de janvier.",
                "Et celui de l'année dernière ?",
                "Montre leurs résultats.",
                "Ensuite, détaille cette partie."
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.CONTINUATION,
                    resolver.resolve(followUp, activeState),
                    followUp
            );
        }
    }

    @Test
    void recognizesCorrectionsWithoutKnowingTheToolOrItsArguments() {
        for (String correction : new String[]{
                "Je crois que la référence correcte est DOC-204.",
                "Correction : utilise la deuxième version.",
                "Plus précisément, la période doit commencer en avril.",
                "Je me suis trompé, je voulais dire le rapport trimestriel."
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.CONTINUATION,
                    resolver.resolve(correction, activeState),
                    correction
            );
        }
    }

    @Test
    void startsIndependentRequestsAcrossDifferentDomains() {
        for (String independentRequest : new String[]{
                "Explique la comptabilité.",
                "Recherche les factures impayées.",
                "Prépare un rapport de ventes.",
                "Compare les performances des deux projets.",
                "Que sais-tu sur moi ?",
                "Parle-moi de ma formation."
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.NEW_INTENT,
                    resolver.resolve(independentRequest, activeState),
                    independentRequest
            );
        }
    }

    @Test
    void explicitTopicChangesAlwaysStartANewIntent() {
        for (String topicChange : new String[]{
                "Une nouvelle question",
                "Je veux poser une nouvelle question",
                "Changeons de sujet.",
                "Passons à autre chose.",
                "Rien à voir avec le sujet précédent.",
                "Sans rapport : explique les bases de la cryptographie."
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.NEW_INTENT,
                    resolver.resolve(topicChange, activeState),
                    topicChange
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
    void doesNotReuseStateWhenTheFallbackCannotEstablishAReference() {
        for (String ambiguousMessage : new String[]{
                "Le calendrier.",
                "Un autre document.",
                "La semaine prochaine."
        }) {
            assertEquals(
                    ConversationIntentResolver.Decision.NEW_INTENT,
                    resolver.resolve(ambiguousMessage, activeState),
                    ambiguousMessage
            );
        }
    }

    @Test
    void treatsMessagesWithoutAnActiveToolStateAsNewIntents() {
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve("Et ajoute une comparaison.", null)
        );
        assertEquals(
                ConversationIntentResolver.Decision.NEW_INTENT,
                resolver.resolve(
                        "Continue.",
                        objectMapper.createObjectNode()
                )
        );
    }

    @Test
    void keepsActiveStateForAStandaloneGreeting() {
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
                        "Bonjour, prépare un rapport de ventes."
                )
        );
    }
}
