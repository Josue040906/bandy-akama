package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialogueModelTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-11-02T08:00:00Z");

    @Test
    void createsAnEmptyVersionedDialogueState() {
        DialogueState state = DialogueState.empty();

        assertEquals(DialogueState.CURRENT_SCHEMA_VERSION, state.schemaVersion());
        assertEquals(null, state.activeObjectiveId());
        assertEquals(List.of(), state.objectives());
        assertEquals(null, state.pendingClarification());
    }

    @Test
    void allowsOneActiveObjectiveAndSeveralSuspendedObjectives() {
        DialogueObjective suspended = objective(
                "objective-1",
                DialogueObjectiveStatus.SUSPENDED,
                null
        );
        DialogueObjective active = objective(
                "objective-2",
                DialogueObjectiveStatus.ACTIVE,
                null
        );

        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "objective-2",
                List.of(suspended, active),
                null
        );

        assertEquals("objective-2", state.activeObjectiveId());
        assertEquals(2, state.objectives().size());
    }

    @Test
    void rejectsMoreThanOneActiveObjective() {
        assertThrows(IllegalArgumentException.class, () ->
                new DialogueState(
                        DialogueState.CURRENT_SCHEMA_VERSION,
                        "objective-1",
                        List.of(
                                objective("objective-1", DialogueObjectiveStatus.ACTIVE, null),
                                objective("objective-2", DialogueObjectiveStatus.ACTIVE, null)
                        ),
                        null
                )
        );
    }

    @Test
    void rejectsAnActiveIdentifierThatDoesNotMatchTheActiveObjective() {
        assertThrows(IllegalArgumentException.class, () ->
                new DialogueState(
                        DialogueState.CURRENT_SCHEMA_VERSION,
                        "missing-objective",
                        List.of(objective(
                                "objective-1",
                                DialogueObjectiveStatus.ACTIVE,
                                null
                        )),
                        null
                )
        );
    }

    @Test
    void rejectsUnsupportedSchemaVersions() {
        assertThrows(IllegalArgumentException.class, () ->
                new DialogueState(99, null, List.of(), null)
        );
    }

    @Test
    void keepsCollectionsImmutableAndDefensivelyCopied() {
        List<DialogueObjective> source = new ArrayList<>();
        source.add(objective(
                "objective-1",
                DialogueObjectiveStatus.ACTIVE,
                null
        ));

        DialogueState state = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "objective-1",
                source,
                null
        );
        source.clear();

        assertEquals(1, state.objectives().size());
        assertThrows(UnsupportedOperationException.class, () ->
                state.objectives().clear()
        );
    }

    @Test
    void rejectsClarificationTargetsThatDoNotExist() {
        PendingClarification pending = new PendingClarification(
                "missing-objective",
                "De quel élément parlez-vous ?",
                "La référence est ambiguë.",
                CREATED_AT
        );

        assertThrows(IllegalArgumentException.class, () ->
                new DialogueState(
                        DialogueState.CURRENT_SCHEMA_VERSION,
                        null,
                        List.of(),
                        pending
                )
        );
    }

    @Test
    void roundTripsTheDialogueStateThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DialogueObjective active = objective(
                "objective-1",
                DialogueObjectiveStatus.ACTIVE,
                null
        );
        DialogueState original = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "objective-1",
                List.of(active),
                new PendingClarification(
                        "objective-1",
                        "Quel format souhaitez-vous ?",
                        "Le format n'est pas encore précisé.",
                        CREATED_AT
                )
        );

        String json = mapper.writeValueAsString(original);
        DialogueState restored = mapper.readValue(json, DialogueState.class);

        assertEquals(original, restored);
    }

    @Test
    void requiresAQuestionOnlyWhenTheEngineNeedsClarification() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContextDecision(
                        DialogueRelation.NEEDS_CLARIFICATION,
                        null,
                        "La référence ne peut pas être résolue.",
                        null
                )
        );

        ContextDecision ambiguous = new ContextDecision(
                DialogueRelation.NEEDS_CLARIFICATION,
                null,
                "Deux objectifs peuvent correspondre à la demande.",
                "Souhaitez-vous reprendre le premier ou le second objectif ?"
        );
        assertEquals(
                DialogueRelation.NEEDS_CLARIFICATION,
                ambiguous.relation()
        );
    }

    @Test
    void requiresAnExistingObjectiveForContinuationAndResumption() {
        assertThrows(IllegalArgumentException.class, () ->
                new ContextDecision(
                        DialogueRelation.CONTINUATION,
                        null,
                        "Le message prolonge l'objectif actif.",
                        null
                )
        );

        ContextDecision resumed = new ContextDecision(
                DialogueRelation.TOPIC_RESUMPTION,
                "objective-older",
                "Le message fait explicitement référence à un objectif suspendu.",
                null
        );
        assertEquals("objective-older", resumed.targetObjectiveId());
    }

    private DialogueObjective objective(
            String id,
            DialogueObjectiveStatus status,
            String parentId
    ) {
        return new DialogueObjective(
                id,
                "Comparer plusieurs éléments",
                "L'utilisateur souhaite comparer des options.",
                status,
                parentId,
                CREATED_AT,
                CREATED_AT,
                List.of(new DialogueReference(
                        "reference-" + id,
                        "Option A",
                        "item",
                        1
                ))
        );
    }
}
