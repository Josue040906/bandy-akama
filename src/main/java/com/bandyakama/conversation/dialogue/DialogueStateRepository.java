package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
            /*
             * PostgreSQL returns JSONB::text as a JSON object. H2's
             * PostgreSQL-mode JSONB compatibility layer can instead return
             * the same document wrapped as a JSON string. Normalize both
             * representations before binding the domain model.
             */
            com.fasterxml.jackson.databind.JsonNode storedValue =
                    objectMapper.readTree(serialized);
            if (storedValue != null && storedValue.isTextual()) {
                storedValue = objectMapper.readTree(storedValue.asText());
            }

            DialogueState state = storedValue == null
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
     * Persists a complete immutable snapshot. When called from the successful
     * request-completion transaction, this update participates in that same
     * transaction and rolls back with the assistant response if anything fails.
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

        int updated = jdbcTemplate.update("""
                UPDATE bandy_conversation_memory
                SET dialogue_state = CAST(? AS JSONB),
                    updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ?
                """,
                serialized,
                conversationId
        );
        if (updated != 1) {
            throw new IllegalStateException(
                    "Impossible de persister l’état de dialogue : mémoire absente."
            );
        }
    }
}
