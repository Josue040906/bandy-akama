package com.bandyakama.conversation;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationEntityReferenceExtractorTest {
    @Test
    void extractsOnlyStructuralIdsAndNamesWithParentRelationships() {
        Map<String, Object> result = Map.of(
                "directions", List.of(Map.of(
                        "id", 1,
                        "nom", "Direction Générale de l'Administration",
                        "services", List.of(Map.of(
                                "id", 4,
                                "nom", "Service du Personnel",
                                "postes", List.of(Map.of(
                                        "id", 9,
                                        "intitule", "Gestionnaire RH"
                                ))
                        ))
                ))
        );

        var references = ConversationEntityReferenceExtractor.extract(result);

        assertEquals(3, references.size());
        var direction = references.stream().filter(ref -> "DIRECTION".equals(ref.type()))
                .findFirst().orElseThrow();
        assertEquals("DIRECTION", direction.type());
        assertEquals(1L, direction.id());
        assertEquals("Direction Générale de l'Administration", direction.name());
        var service = references.stream().filter(ref -> "SERVICE".equals(ref.type()))
                .findFirst().orElseThrow();
        assertEquals("SERVICE", service.type());
        assertEquals(1L, service.parentId());
        var poste = references.stream().filter(ref -> "POSTE".equals(ref.type()))
                .findFirst().orElseThrow();
        assertEquals("POSTE", poste.type());
        assertEquals(4L, poste.parentId());
    }

    @Test
    void extractsConfiguredEntityCollectionWithoutDependingOnToolName() {
        Map<String, Object> result = Map.of(
                "records", List.of(
                        Map.of("id", 31, "name", "Division des systèmes"),
                        Map.of("id", 32, "name", "Division des finances")
                )
        );

        var references = ConversationEntityReferenceExtractor.extract(
                result, "DIVISION", "records"
        );

        assertEquals(2, references.size());
        assertEquals("DIVISION", references.get(0).type());
        assertEquals(31L, references.get(0).id());
    }

    @Test
    void extractsKnownNestedStructureTypesWithoutClassifyingOtherCollections() {
        Map<String, Object> result = Map.of(
                "directions", List.of(Map.of(
                        "id", 1,
                        "nom", "Direction générale",
                        "services", List.of(Map.of(
                                "id", 4,
                                "nom", "Service RH",
                                "postes", List.of(Map.of(
                                        "id", 9,
                                        "intitule", "Gestionnaire"
                                ))
                        ))
                )),
                "responsables", List.of(Map.of(
                        "id", 70,
                        "name", "Agent responsable"
                ))
        );

        var references = ConversationEntityReferenceExtractor.extract(
                result, "DIRECTION", null
        );

        assertEquals(3, references.size());
        assertEquals(List.of("DIRECTION", "SERVICE", "POSTE"),
                references.stream().map(
                        ConversationTurnDecision.ReferencedEntity::type
                ).toList());
    }

    @Test
    void findsConfiguredCollectionInsideWrappedApiResponse() {
        Map<String, Object> result = Map.of(
                "data", Map.of(
                        "records", List.of(
                                Map.of("id", 41, "name", "Direction des ressources")
                        )
                )
        );

        var references = ConversationEntityReferenceExtractor.extract(
                result, "DIRECTION", "records"
        );

        assertEquals(1, references.size());
        assertEquals(41L, references.get(0).id());
    }

    @Test
    void doesNotExtractObjectsWithoutKnownOrganizationalType() {
        var references = ConversationEntityReferenceExtractor.extract(
                Map.of("metadata", Map.of("id", 77, "name", "Not an organization"))
        );
        assertEquals(0, references.size());
    }
}
