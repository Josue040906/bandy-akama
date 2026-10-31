package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Extracts non-personal entity references using tool-declared result metadata. */
public final class ConversationEntityReferenceExtractor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_REFERENCES = 200;

    private ConversationEntityReferenceExtractor() {
    }

    public static List<ConversationTurnDecision.ReferencedEntity> extract(Object result) {
        if (result == null) {
            return List.of();
        }
        JsonNode root = MAPPER.valueToTree(result);
        Map<String, ConversationTurnDecision.ReferencedEntity> found =
                new LinkedHashMap<>();
        visit(root, null, null, found);
        return List.copyOf(found.values());
    }

    public static List<ConversationTurnDecision.ReferencedEntity> extract(
            Object result,
            String rootEntityType,
            String collectionKey
    ) {
        if (result == null || rootEntityType == null || rootEntityType.isBlank()) {
            return List.of();
        }
        JsonNode root = MAPPER.valueToTree(result);
        JsonNode collection = collectionKey == null || collectionKey.isBlank()
                ? root : findCollection(root, collectionKey);
        if (collection == null || collection.isNull()) {
            return List.of();
        }
        Map<String, ConversationTurnDecision.ReferencedEntity> found =
                new LinkedHashMap<>();
        visit(collection, rootEntityType, null, found);
        return List.copyOf(found.values());
    }

    private static JsonNode findCollection(JsonNode node, String collectionKey) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(collectionKey);
            if (direct != null && !direct.isNull()) {
                return direct;
            }
            var fields = node.elements();
            while (fields.hasNext()) {
                JsonNode found = findCollection(fields.next(), collectionKey);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findCollection(child, collectionKey);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void visit(
            JsonNode node,
            String inheritedType,
            ConversationTurnDecision.ReferencedEntity parent,
            Map<String, ConversationTurnDecision.ReferencedEntity> found
    ) {
        if (node == null || node.isNull() || found.size() >= MAX_REFERENCES) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                visit(child, inheritedType, parent, found);
                if (found.size() >= MAX_REFERENCES) {
                    break;
                }
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String ownType = firstText(node, "entityType", "entity_type", "type");
        if (ownType == null) {
            ownType = inheritedType;
        }
        Long id = positiveLong(node.get("id"));
        String name = firstText(node, "name", "nom", "intitule", "libelle", "label");
        ConversationTurnDecision.ReferencedEntity current = parent;
        if (id != null && name != null && ownType != null) {
            current = new ConversationTurnDecision.ReferencedEntity(
                    ownType,
                    id,
                    name,
                    parent == null ? null : parent.type(),
                    parent == null ? null : parent.id()
            );
            found.putIfAbsent(ownType + ":" + id,
                    current);
        }

        ConversationTurnDecision.ReferencedEntity parentForChildren = current;
        node.fields().forEachRemaining(entry -> {
            String childType = typeForCollection(entry.getKey());
            visit(entry.getValue(),
                    childType,
                    parentForChildren,
                    found);
        });
    }

    private static String typeForCollection(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "directions", "direction" -> "DIRECTION";
            case "services", "service" -> "SERVICE";
            case "postes", "poste" -> "POSTE";
            default -> null;
        };
    }

    private static Long positiveLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            long value = node.isIntegralNumber()
                    ? node.longValue()
                    : Long.parseLong(node.asText());
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }
}
