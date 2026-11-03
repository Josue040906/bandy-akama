package com.bandyakama.conversation.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    private static final int CURRENT_SCHEMA_VERSION = 2;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskStateSnapshotSanitizer sanitizer;
    private final ToolRegistry toolRegistry;
    private volatile Boolean postgresDatabase;

    @Autowired
    public TaskStateSnapshotRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.sanitizer = new TaskStateSnapshotSanitizer(objectMapper);
    }

    /** Compatibility constructor for isolated persistence tests. */
    public TaskStateSnapshotRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.toolRegistry = null;
        this.sanitizer = new TaskStateSnapshotSanitizer(objectMapper);
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
                    || freshness == null || !freshness.isArray()
                    || references.size() != freshness.size()) {
                return Optional.empty();
            }
            // Sanitize again on read to protect legacy/manual rows.
            ObjectNode safeState = sanitizeOperationalState(state);
            ArrayNode safeReferences = sanitizeReferences((ArrayNode) references);
            return Optional.of(new Snapshot(
                    safeState,
                    safeReferences,
                    (ArrayNode) freshness.deepCopy()
            ));
        } catch (EmptyResultDataAccessException missing) {
            return Optional.empty();
        } catch (RuntimeException malformed) {
            // A corrupt snapshot is a cache miss, not a reason to destroy it
            // or prevent the user from resuming by rerunning the operation.
            return Optional.empty();
        }
    }

    /**
     * Captures an explicit pre-transition state for the objective being left.
     * Historical result references are intentionally omitted: they are not
     * needed to restore criteria and must never be treated as fresh results.
     */
    @Transactional
    public void capture(UUID conversationId, String objectiveId, JsonNode operationalState) {
        if (conversationId == null || objectiveId == null || objectiveId.isBlank()
                || operationalState == null || !operationalState.isObject()) {
            return;
        }
        persistSnapshot(
                conversationId,
                objectiveId,
                sanitizeOperationalState(operationalState),
                objectMapper.createArrayNode()
        );
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

        persistSnapshot(
                conversationId,
                objectiveId,
                sanitizer.sanitizeOperationalState(parsedState),
                (ArrayNode) parsedReferences
        );
    }

    private void persistSnapshot(
            UUID conversationId,
            String objectiveId,
            ObjectNode safeState,
            ArrayNode parsedReferences
    ) {
        ArrayNode references = objectMapper.createArrayNode();
        ArrayNode freshness = objectMapper.createArrayNode();
        for (JsonNode reference : parsedReferences) {
            if (reference == null || !reference.isObject()) {
                continue;
            }
            ObjectNode safeReference = objectMapper.createObjectNode();
            String toolName = reference.path("toolName").asText("");
            safeReference.put("toolName", toolName);
            safeReference.set(
                    "arguments",
                    sanitizeArguments(toolName, reference.path("arguments"))
            );
            String capturedAt = reference.path("capturedAt").asText("");
            safeReference.put("capturedAt", capturedAt);
            references.add(safeReference);
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("toolName", toolName);
            metadata.put("capturedAt", capturedAt.isBlank() ? "UNKNOWN" : capturedAt);
            metadata.put("status", "STALE");
            metadata.put(
                    "reason",
                    capturedAt.isBlank()
                            ? "MISSING_CAPTURE_TIMESTAMP"
                            : "NO_VERIFIED_FRESHNESS_POLICY"
            );
            freshness.add(metadata);
        }

        String sql = isPostgres()
                ? """
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
                    """
                : """
                    MERGE INTO bandy_conversation_task_state (
                        conversation_id, objective_id, schema_version,
                        operational_state, result_references, freshness, updated_at
                    ) KEY (conversation_id, objective_id)
                    VALUES (?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB),
                            CAST(? AS JSONB), CURRENT_TIMESTAMP)
                    """;
        jdbcTemplate.update(
                sql,
                conversationId,
                objectiveId,
                CURRENT_SCHEMA_VERSION,
                writeJson(safeState),
                writeJson(references),
                writeJson(freshness)
        );
    }

    private ObjectNode sanitizeOperationalState(JsonNode state) {
        String toolName = state == null ? "" : state.path("toolName").asText("");
        return sanitizer.sanitizeOperationalState(
                state,
                findToolDefinition(toolName)
        );
    }

    private ObjectNode sanitizeArguments(String toolName, JsonNode arguments) {
        return sanitizer.sanitizeArguments(
                toolName,
                arguments,
                findToolDefinition(toolName)
        );
    }

    private ToolDefinition findToolDefinition(String toolName) {
        if (toolRegistry == null || toolName == null || toolName.isBlank()) {
            return null;
        }
        return toolRegistry.findByName(toolName).orElse(null);
    }

    private ArrayNode sanitizeReferences(ArrayNode source) {
        ArrayNode safe = objectMapper.createArrayNode();
        for (JsonNode reference : source) {
            ObjectNode item = objectMapper.createObjectNode();
            String toolName = reference == null || !reference.isObject()
                    ? "" : reference.path("toolName").asText("");
            item.put("toolName", toolName);
            item.set(
                    "arguments",
                    sanitizer.sanitizeArguments(
                            toolName,
                            reference == null || !reference.isObject()
                                    ? objectMapper.createObjectNode()
                                    : reference.path("arguments")
                    )
            );
            item.put(
                    "capturedAt",
                    reference == null || !reference.isObject()
                            ? "" : reference.path("capturedAt").asText("")
            );
            safe.add(item);
        }
        return safe;
    }

    /**
     * PostgreSQL and H2 expose different upsert syntaxes. Keep the production
     * PostgreSQL ON CONFLICT statement and use H2's MERGE ... KEY in tests.
     */
    private boolean isPostgres() {
        Boolean cached = postgresDatabase;
        if (cached != null) {
            return cached;
        }
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName()
        );
        boolean detected = productName != null
                && productName.toLowerCase(java.util.Locale.ROOT).contains("postgresql");
        postgresDatabase = detected;
        return detected;
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed != null && parsed.isTextual()
                    ? objectMapper.readTree(parsed.asText())
                    : parsed;
        } catch (JsonProcessingException malformedJson) {
            throw new IllegalArgumentException(
                    "Stored conversation task state contains invalid JSON.",
                    malformedJson
            );
        }
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
