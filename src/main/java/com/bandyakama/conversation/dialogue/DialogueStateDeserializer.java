package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Reads both the legacy singular clarification property and the current list.
 * New snapshots are serialized using pendingClarifications.
 */
public final class DialogueStateDeserializer extends StdDeserializer<DialogueState> {
    public DialogueStateDeserializer() {
        super(DialogueState.class);
    }

    @Override
    public DialogueState deserialize(
            JsonParser parser,
            DeserializationContext context
    ) throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);
        int schemaVersion = required(root, "schemaVersion").asInt();
        JsonNode activeNode = root.get("activeObjectiveId");
        String activeObjectiveId = activeNode == null || activeNode.isNull()
                ? null : activeNode.asText();

        JsonNode objectivesNode = required(root, "objectives");
        DialogueObjective[] objectiveArray = parser.getCodec().treeToValue(
                objectivesNode, DialogueObjective[].class
        );

        JsonNode pluralNode = root.get("pendingClarifications");
        JsonNode legacyNode = root.get("pendingClarification");
        if (pluralNode != null && legacyNode != null) {
            throw com.fasterxml.jackson.databind.JsonMappingException.from(
                    parser,
                    "A dialogue state cannot contain both pendingClarifications "
                            + "and legacy pendingClarification."
            );
        }

        List<PendingClarification> clarifications;
        if (pluralNode != null && !pluralNode.isNull()) {
            if (!pluralNode.isArray()) {
                throw com.fasterxml.jackson.databind.JsonMappingException.from(
                        parser, "pendingClarifications must be an array."
                );
            }
            PendingClarification[] values = parser.getCodec().treeToValue(
                    pluralNode, PendingClarification[].class
            );
            clarifications = Arrays.asList(values);
        } else if (legacyNode != null && !legacyNode.isNull()) {
            clarifications = List.of(parser.getCodec().treeToValue(
                    legacyNode, PendingClarification.class
            ));
        } else {
            clarifications = List.of();
        }

        return new DialogueState(
                schemaVersion,
                activeObjectiveId,
                Arrays.asList(objectiveArray),
                clarifications
        );
    }

    private JsonNode required(JsonNode root, String field)
            throws com.fasterxml.jackson.databind.JsonMappingException {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw com.fasterxml.jackson.databind.JsonMappingException.from(
                    (JsonParser) null, "Missing required dialogue-state field: " + field
            );
        }
        return value;
    }
}
