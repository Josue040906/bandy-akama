package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL persistence adapter for the domain-neutral dialogue state.
 *
 * <p>This adapter deliberately does not know about tool state, RH data,
 * filters or model prompts. Callers are responsible for loading the state
 * during request preparation and saving a proposed state only as part of the
 * successful request-completion transaction.</p>
 */
@Repository
public class DialogueStateRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DialogueStateRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DialogueState load(UUID conversationId) {
        String serialized;
        try {
            serialized = jdbcTemplate.queryForObject("""
                    SELECT dialogue_state::text
                    FROM bandy_conversation_memory
                    WHERE conversation_id = ?
                    """,
                    String.class,
                    conversationId
            );
        } catch (EmptyResultDataAccessException missingMemory) {
            throw new IllegalStateException(
                    "La mémoire de dialogue de cette conversation est introuvable.",
                    missingMemory
            );
        }

        try {
            JsonNode storedValue = readJsonValue(serialized);
            DialogueState state = storedValue == null || storedValue.isNull()
                    ? null
                    : objectMapper.treeToValue(storedValue, DialogueState.class);
            return state == null ? DialogueState.empty() : state;
        } catch (JsonProcessingException | IllegalArgumentException invalidState) {
            throw new IllegalStateException(
                    "L’état de dialogue enregistré est illisible ou incompatible.",
                    invalidState
            );
        }
    }

    /**
     * Persists a complete immutable snapshot. Both the rebuildable memory
     * projection and the append-only assistant history are updated inside the
     * durable turn-outcome transaction (successful completion or recorded failure).
     */
    @Transactional
    public void save(UUID conversationId, DialogueState state) {
        if (state == null) {
            throw new IllegalArgumentException("Dialogue state must not be null.");
        }

        final String serialized;
        try {
            serialized = objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalArgumentException(
                    "Impossible de sérialiser l’état de dialogue.",
                    serializationFailure
            );
        }

        int memoryUpdated = jdbcTemplate.update("""
                UPDATE bandy_conversation_memory
                SET dialogue_state = CAST(? AS JSONB),
                    updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ?
                """,
                serialized,
                conversationId
        );
        if (memoryUpdated != 1) {
            throw new IllegalStateException(
                    "Impossible de persister l’état de dialogue : mémoire absente."
            );
        }

        /*
         * Keep a durable recovery copy beside the assistant turn. Do not use
         * PostgreSQL-only jsonb_set here: the same persistence path is tested
         * with H2 in PostgreSQL mode. Merge metadata in Java so existing
         * metadata fields are preserved on both database engines.
         */
        List<AssistantMessage> latestAssistantMessages = jdbcTemplate.query("""
                        SELECT id, metadata::text AS metadata
                        FROM bandy_conversation_message
                        WHERE conversation_id = ? AND role = 'ASSISTANT'
                        ORDER BY sequence_number DESC
                        LIMIT 1
                        """,
                (resultSet, rowNumber) -> new AssistantMessage(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("metadata")
                ),
                conversationId
        );
        AssistantMessage latestAssistant = latestAssistantMessages.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Impossible de persister l’état de dialogue : "
                                + "aucun message assistant n’existe pour cette réponse."
                ));

        final String mergedMetadata;
        try {
            JsonNode metadataValue = readJsonValue(latestAssistant.metadata());
            ObjectNode metadata;
            if (metadataValue == null || metadataValue.isNull()) {
                metadata = objectMapper.createObjectNode();
            } else if (metadataValue.isObject()) {
                metadata = (ObjectNode) metadataValue;
            } else {
                throw new IllegalStateException(
                        "Les métadonnées du message assistant ne sont pas un objet JSON."
                );
            }
            metadata.set("dialogueState", objectMapper.valueToTree(state));
            mergedMetadata = objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException(
                    "Impossible de mettre à jour les métadonnées du message assistant.",
                    serializationFailure
            );
        }

        int messageUpdated = jdbcTemplate.update("""
                UPDATE bandy_conversation_message
                SET metadata = CAST(? AS JSONB)
                WHERE id = ?
                """,
                mergedMetadata,
                latestAssistant.id()
        );
        if (messageUpdated != 1) {
            throw new IllegalStateException(
                    "Impossible de persister l’état de dialogue dans l’historique assistant."
            );
        }
    }

    /**
     * H2's PostgreSQL-mode JSONB compatibility layer may expose JSON text as
     * a JSON string. Unwrap that representation while leaving PostgreSQL's
     * normal JSON object representation untouched.
     */
    private JsonNode readJsonValue(String serialized) throws JsonProcessingException {
        if (serialized == null || serialized.isBlank()) {
            return null;
        }
        JsonNode value = objectMapper.readTree(serialized);
        if (value != null && value.isTextual()) {
            return objectMapper.readTree(value.asText());
        }
        return value;
    }

    private record AssistantMessage(UUID id, String metadata) {
    }
}
