package com.bandyakama.conversation.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable operational snapshots scoped to one conversational objective.
 *
 * <p>Result references are retained for audit/refresh context, but this
 * adapter marks every reference STALE because no capability currently
 * publishes a verified freshness policy. Callers must never treat a restored
 * reference as a current business result.</p>
 */
@Repository
public class TaskStateSnapshotRepository {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskStateSnapshotRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<Snapshot> load(UUID conversationId, String objectiveId) {
        if (conversationId == null || objectiveId == null || objectiveId.isBlank()) {
            return Optional.empty();
        }
        try {
            StoredSnapshot stored = jdbcTemplate.queryForObject("""
                    SELECT schema_version, operational_state::text AS operational_state,
                           result_references::text AS result_references,
                           freshness::text AS freshness
                    FROM bandy_conversation_task_state
                    WHERE conversation_id = ? AND objective_id = ?
                    """,
                    (rs, row) -> new StoredSnapshot(
                            rs.getInt("schema_version"),
                            rs.getString("operational_state"),
                            rs.getString("result_references"),
                            rs.getString("freshness")
                    ),
                    conversationId,
                    objectiveId
            );
            if (stored == null || stored.schemaVersion() != CURRENT_SCHEMA_VERSION) {
                return Optional.empty();
            }

            JsonNode state = readJson(stored.operationalState());
            JsonNode references = readJson(stored.resultReferences());
            JsonNode freshness = readJson(stored.freshness());
            if (state == null || !state.isObject()
                    || references == null || !references.isArray()
                    || freshness == null || !freshness.isArray()) {
                return Optional.empty();
            }
            // Defense in depth: profile payloads must not be restored from a
            // task snapshot even if an older writer accidentally stored them.
            ObjectNode safeState = ((ObjectNode) state).deepCopy();
            safeState.remove("profiles");
            return Optional.of(new Snapshot(
                    safeState,
                    (ArrayNode) references.deepCopy(),
                    (ArrayNode) freshness.deepCopy()
            ));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        } catch (RuntimeException | JsonProcessingException malformed) {
            // A corrupt snapshot is a cache miss, not a reason to destroy it
            // or prevent the user from resuming by rerunning the operation.
            return Optional.empty();
        }
    }

    /**
     * Captures the successfully committed active memory for an objective.
     * The caller invokes this inside the same completion transaction.
     */
    @Transactional
    public void capture(UUID conversationId, String objectiveId) {
        if (conversationId == null || objectiveId == null || objectiveId.isBlank()) {
            return;
        }
        StoredMemory memory = jdbcTemplate.queryForObject("""
                SELECT active_state::text AS active_state,
                       result_references::text AS result_references
                FROM bandy_conversation_memory
                WHERE conversation_id = ?
                """,
                (rs, row) -> new StoredMemory(
                        rs.getString("active_state"),
                        rs.getString("result_references")
                ),
                conversationId
        );
        if (memory == null) {
            throw new IllegalStateException("Conversation memory is missing.");
        }

        JsonNode parsedState = readJson(memory.activeState());
        JsonNode parsedReferences = readJson(memory.resultReferences());
        if (parsedState == null || !parsedState.isObject()
                || parsedReferences == null || !parsedReferences.isArray()) {
            throw new IllegalStateException("Operational memory is malformed.");
        }

        ObjectNode safeState = ((ObjectNode) parsedState).deepCopy();
        // Agent profile data is sensitive and has its own bounded contributor;
        // it must not be duplicated into per-objective snapshots.
        safeState.remove("profiles");
        ArrayNode references = objectMapper.createArrayNode();
        ArrayNode freshness = objectMapper.createArrayNode();
        Instant capturedAt = Instant.now();
        for (JsonNode reference : parsedReferences) {
            if (reference == null || !reference.isObject()) {
                continue;
            }
            references.add(reference.deepCopy());
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("toolName", reference.path("toolName").asText(""));
            metadata.put("capturedAt", capturedAt.toString());
            metadata.put("status", "STALE");
            metadata.put("reason", "NO_VERIFIED_FRESHNESS_POLICY");
            freshness.add(metadata);
        }

        jdbcTemplate.update("""
                INSERT INTO bandy_conversation_task_state (
                    conversation_id, objective_id, schema_version,
                    operational_state, result_references, freshness, updated_at
                ) VALUES (?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB),
                          CAST(? AS JSONB), CURRENT_TIMESTAMP)
                ON CONFLICT (conversation_id, objective_id) DO UPDATE SET
                    schema_version = EXCLUDED.schema_version,
                    operational_state = EXCLUDED.operational_state,
                    result_references = EXCLUDED.result_references,
                    freshness = EXCLUDED.freshness,
                    updated_at = CURRENT_TIMESTAMP
                """,
                conversationId,
                objectiveId,
                CURRENT_SCHEMA_VERSION,
                writeJson(safeState),
                writeJson(references),
                writeJson(freshness)
        );
    }

    private JsonNode readJson(String value) throws JsonProcessingException {
        if (value == null || value.isBlank()) {
            return null;
        }
        JsonNode parsed = objectMapper.readTree(value);
        return parsed != null && parsed.isTextual()
                ? objectMapper.readTree(parsed.asText())
                : parsed;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Unable to serialize task snapshot.", failure);
        }
    }

    private record StoredSnapshot(
            int schemaVersion,
            String operationalState,
            String resultReferences,
            String freshness
    ) {
    }

    private record StoredMemory(String activeState, String resultReferences) {
    }

    public record Snapshot(
            JsonNode operationalState,
            ArrayNode resultReferences,
            ArrayNode freshness
    ) {
        public Snapshot {
            operationalState = operationalState == null
                    ? null : operationalState.deepCopy();
            resultReferences = resultReferences == null
                    ? null : resultReferences.deepCopy();
            freshness = freshness == null ? null : freshness.deepCopy();
        }

        @Override
        public JsonNode operationalState() {
            return operationalState == null ? null : operationalState.deepCopy();
        }

        @Override
        public ArrayNode resultReferences() {
            return resultReferences == null ? null : resultReferences.deepCopy();
        }

        @Override
        public ArrayNode freshness() {
            return freshness == null ? null : freshness.deepCopy();
        }
    }
}
