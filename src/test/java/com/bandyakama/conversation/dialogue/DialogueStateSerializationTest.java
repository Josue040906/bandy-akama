package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;\nimport static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueStateSerializationTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsIndependentDependenciesAndBlockedStatus() throws Exception {
        Instant createdAt = Instant.parse("2026-11-03T06:00:00Z");
        DialogueObjective prerequisite = new DialogueObjective(
                "prerequisite", "Collecter les données", "Données requises.",
                DialogueObjectiveStatus.ACTIVE, null, createdAt, createdAt,
                List.of(), List.of()
        );
        DialogueObjective dependent = new DialogueObjective(
                "dependent", "Rédiger la synthèse", "En attente des données.",
                DialogueObjectiveStatus.BLOCKED, "prerequisite", createdAt, createdAt,
                List.of(), List.of("prerequisite")
        );
        DialogueState expected = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                "prerequisite",
                List.of(prerequisite, dependent),
                null
        );

        String serialized = objectMapper.writeValueAsString(expected);
        DialogueState restored = objectMapper.readValue(
                serialized, DialogueState.class
        );

        assertEquals(expected, restored);
    }

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
    void readsLegacySinglePendingClarificationSnapshot() throws Exception {
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
                    "references": [],
                    "dependsOnObjectiveIds": []
                  }],
                  "pendingClarification": {
                    "targetObjectiveId": "objective-1",
                    "question": "Quelle période ?",
                    "reason": "La période manque.",
                    "createdAt": "2026-11-02T10:16:00Z"
                  }
                }
                """;

        DialogueState restored = objectMapper.readValue(legacyJson, DialogueState.class);

        assertEquals(1, restored.pendingClarifications().size());
        assertEquals("Quelle période ?", restored.pendingClarification().question());
    }

    @Test
    void roundTripsMultiplePendingClarifications() throws Exception {
        Instant createdAt = Instant.parse("2026-11-03T06:00:00Z");
        PendingClarification first = new PendingClarification(
                null, "Quel sujet ?", "Le sujet est ambigu.", createdAt
        );
        PendingClarification second = new PendingClarification(
                null, "Quel format ?", "Le format est ambigu.", createdAt.plusSeconds(1)
        );
        DialogueState expected = new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                null,
                List.of(),
                List.of(first, second)
        );

        String serialized = objectMapper.writeValueAsString(expected);
        DialogueState restored = objectMapper.readValue(serialized, DialogueState.class);

        assertEquals(expected, restored);
        assertEquals(2, restored.pendingClarifications().size());
        assertTrue(serialized.contains("pendingClarifications"));
        assertTrue(!serialized.contains("\\"pendingClarification\\":"));
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
