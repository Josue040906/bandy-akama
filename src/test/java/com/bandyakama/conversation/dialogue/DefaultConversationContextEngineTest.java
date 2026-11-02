package com.bandyakama.conversation.dialogue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DefaultConversationContextEngineTest {
    private final DialogueInterpreter interpreter = mock(DialogueInterpreter.class);
    private final DefaultConversationContextEngine engine =
            new DefaultConversationContextEngine(
                    interpreter,
                    new ContextDecisionValidator(),
                    new DialogueStateTransitioner()
            );

    @Test
    void greetingPreservesActiveObjectiveAndPendingClarificationWithoutCallingModel() {
        Instant now = Instant.parse("2026-11-02T08:00:00Z");
        DialogueObjective active = new DialogueObjective(
                "active", "Objectif actif", "Résumé", DialogueObjectiveStatus.ACTIVE,
                null, now, now, List.of()
        );
        PendingClarification pending = new PendingClarification(
                "active", "Quel élément ?", "Référence ambiguë.", now
        );
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "active", List.of(active), pending
        );

        ContextResolution resolution = engine.resolve(new ContextEngineInput(
                "Bonjour Bandy !", 2, state, List.of(), ""
        ));

        assertEquals(DialogueRelation.SOCIAL_TURN, resolution.decision().relation());
        assertEquals(state, resolution.proposedState());
        verifyNoInteractions(interpreter);
    }

    @Test
    void explicitNewQuestionSuspendsOldObjectiveAndClearsPendingClarification() {
        Instant now = Instant.parse("2026-11-02T08:00:00Z");
        DialogueObjective active = new DialogueObjective(
                "active", "Ancien objectif", "Résumé", DialogueObjectiveStatus.ACTIVE,
                null, now, now, List.of()
        );
        PendingClarification pending = new PendingClarification(
                "active", "Quel élément ?", "Référence ambiguë.", now
        );
        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION, "active", List.of(active), pending
        );

        ContextResolution resolution = engine.resolve(new ContextEngineInput(
                "Je veux poser une nouvelle question.", 2, state, List.of(), ""
        ));

        assertEquals(DialogueRelation.NEW_TOPIC_REQUEST, resolution.decision().relation());
        assertEquals(null, resolution.proposedState().activeObjectiveId());
        assertEquals(DialogueObjectiveStatus.SUSPENDED,
                resolution.proposedState().objectives().get(0).status());
        assertEquals(null, resolution.proposedState().pendingClarification());
        verifyNoInteractions(interpreter);
    }
}
