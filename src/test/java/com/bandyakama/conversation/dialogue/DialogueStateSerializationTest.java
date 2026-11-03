package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueStateSerializationTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void readsLegacySnapshotsWithoutExecutionDependencyFields() throws Exception {
        String legacyJson = """
                {
                  "schemaVersion": 1,
                  "activeObjectiveId": "objective-1",
                  "objectives": [{
                    "id": "objective-1",
                    "goal": "Préparer un rapport",
                    "summary": "Rapport de ventes",
                    "status": "ACTIVE",
                    "parentObjectiveId": null,
                    "createdAt": "2026-11-02T10:15:30Z",
                    "updatedAt": "2026-11-02T10:15:30Z",
                    "references": []
                  }],
                  "pendingClarification": null
                }
                """;

        DialogueState restored = objectMapper.readValue(
                legacyJson, DialogueState.class
        );

        assertEquals(List.of(), restored.objectives().get(0).dependsOnObjectiveIds());
    }

    @Test
    void roundTripsNonEmptyStateIncludingJavaTimeFields() throws Exception {
        Instant createdAt = Instant.parse("2026-11-02T10:15:30Z");
        DialogueReference reference = new DialogueReference(
                "ref-1",
                "rapport des ventes",
                "document",
                4
        );
        DialogueObjective objective = new DialogueObjective(
                "objective-1",
                "Préparer un rapport des ventes",
                "Le rapport doit présenter les ventes par région.",
                DialogueObjectiveStatus.ACTIVE,
                null,
                createdAt,
                createdAt.plusSeconds(30),
                List.of(reference)
        );
        DialogueState expected = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                objective.id(),
                List.of(objective),
                null
        );

        String serialized = objectMapper.writeValueAsString(expected);
        DialogueState restored = objectMapper.readValue(
                serialized,
                DialogueState.class
        );

        assertEquals(expected, restored);
    }
}
