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
    void doesNotExtractObjectsWithoutKnownOrganizationalType() {
        var references = ConversationEntityReferenceExtractor.extract(
                Map.of("metadata", Map.of("id", 77, "name", "Not an organization"))
        );
        assertEquals(0, references.size());
    }
}
