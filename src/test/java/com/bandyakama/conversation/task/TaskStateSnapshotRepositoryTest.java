package com.bandyakama.conversation.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import org.springframework.jdbc.core.ConnectionCallback;
import org.mockito.ArgumentCaptor;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskStateSnapshotRepositoryTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskStateSnapshotRepository repository =
            new TaskStateSnapshotRepository(jdbcTemplate, objectMapper);

    @Test
    void missingSnapshotIsARecoverableCacheMiss() {
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
        )).thenThrow(new EmptyResultDataAccessException(1));

        Optional<TaskStateSnapshotRepository.Snapshot> result =
                repository.load(UUID.randomUUID(), "objective-1");

        assertFalse(result.isPresent());
    }

    @Test
    void legacySchemaVersionIsTreatedAsMissingSnapshot() throws Exception {
        stubSnapshotRow(1,
                "{\"toolName\":\"search_agents\"}",
                "[]",
                "[]");

        Optional<TaskStateSnapshotRepository.Snapshot> result =
                repository.load(UUID.randomUUID(), "objective-1");

        assertFalse(result.isPresent());
    }

    @Test
    void restoresCriteriaButStripsSensitiveProfileCacheAndOldResultPayloads()
            throws Exception {
        stubSnapshotRow(2,
                "{\"toolName\":\"search_agents\","
                        + "\"profiles\":{\"agent\":{\"secret\":true}}}",
                "[{\"toolName\":\"search_agents\","
                        + "\"arguments\":{\"query\":\"A\"},"
                        + "\"capturedAt\":\"2026-11-02T10:00:00Z\"}]",
                "[{\"toolName\":\"search_agents\","
                        + "\"capturedAt\":\"2026-11-02T10:00:00Z\","
                        + "\"status\":\"STALE\"}]");

        TaskStateSnapshotRepository.Snapshot snapshot = repository.load(
                UUID.randomUUID(), "objective-1"
        ).orElseThrow();

        assertEquals("search_agents", snapshot.operationalState().path("toolName").asText());
        assertFalse(snapshot.operationalState().has("profiles"));
        assertTrue(snapshot.resultReferences().get(0)
                .path("arguments").isEmpty());
        assertFalse(snapshot.resultReferences().get(0).has("result"));
        assertEquals("STALE", snapshot.freshness().get(0).path("status").asText());
    }

    @Test
    void capturePersistsOnlySafeReferenceMetadataAndMarksItStale() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskStateSnapshotRepository repository =
                new TaskStateSnapshotRepository(jdbc, objectMapper);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });
        when(jdbc.queryForObject(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("active_state")).thenReturn(
                    "{\"toolName\":\"search_agents\",\"arguments\":{"
                            + "\"criteriaJson\":\"{\\\"filters\\\":[{"
                            + "\\\"field\\\":\\\"service\\\","
                            + "\\\"operator\\\":\\\"EQ\\\","
                            + "\\\"value\\\":\\\"Informatique\\\"}]}\"}}"
            );
            when(resultSet.getString("result_references")).thenReturn("""
                    [{"toolName":"get_organization_info",
                      "capturedAt":"2026-11-02T20:00:00Z",
                      "arguments":{"query":"Rakoto RH12345678","scope":"SERVICE"},
                      "result":{"description":"private payload","matricule":"RH12345678"}}]
                    """);
            return mapper.mapRow(resultSet, 0);
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        repository.capture(UUID.randomUUID(), "objective-safe-references");

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), parameters.capture());
        JsonNode savedReferences = objectMapper.readTree((String) parameters.getValue()[4]);
        JsonNode savedFreshness = objectMapper.readTree((String) parameters.getValue()[5]);

        assertEquals(1, savedReferences.size());
        assertEquals("get_organization_info",
                savedReferences.get(0).path("toolName").asText());
        assertEquals("SERVICE",
                savedReferences.get(0).path("arguments").path("scope").asText());
        assertFalse(savedReferences.toString().contains("Rakoto"));
        assertFalse(savedReferences.toString().contains("RH12345678"));
        assertFalse(savedReferences.toString().contains("private payload"));
        assertFalse(savedReferences.get(0).has("result"));
        assertEquals(1, savedFreshness.size());
        assertEquals("STALE", savedFreshness.get(0).path("status").asText());
    }

    @Test
    void selectsPostgresOnConflictUpsertWhenRunningOnPostgresql() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        TaskStateSnapshotRepository postgresRepository =
                new TaskStateSnapshotRepository(jdbc, mapper);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<?> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });
        when(jdbc.queryForObject(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
        )).thenAnswer(invocation -> {
            RowMapper<?> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("active_state"))
                    .thenReturn("{\"toolName\":\"search_agents\",\"arguments\":{}}");
            when(resultSet.getString("result_references")).thenReturn("[]");
            return rowMapper.mapRow(resultSet, 0);
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        postgresRepository.capture(UUID.randomUUID(), "objective-postgres");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("ON CONFLICT (conversation_id, objective_id)"));
        assertTrue(sql.getValue().contains("DO UPDATE SET"));
    }

    private void stubSnapshotRow(
            int schemaVersion,
            String operationalState,
            String resultReferences,
            String freshness
    ) throws Exception {
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
        )).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getInt("schema_version")).thenReturn(schemaVersion);
            when(resultSet.getString("operational_state")).thenReturn(operationalState);
            when(resultSet.getString("result_references")).thenReturn(resultReferences);
            when(resultSet.getString("freshness")).thenReturn(freshness);
            return mapper.mapRow(resultSet, 0);
        });
    }

    @Test
    void snapshotDefensivelyCopiesItsMutableJsonValues() {
        ObjectNode state = objectMapper.createObjectNode().put("toolName", "search_agents");
        ArrayNode references = objectMapper.createArrayNode();
        references.addObject().put("toolName", "search_agents");
        ArrayNode freshness = objectMapper.createArrayNode();
        freshness.addObject().put("status", "STALE");

        TaskStateSnapshotRepository.Snapshot snapshot =
                new TaskStateSnapshotRepository.Snapshot(state, references, freshness);

        state.put("toolName", "mutated");
        references.removeAll();
        freshness.removeAll();

        assertEquals("search_agents", snapshot.operationalState().path("toolName").asText());
        assertEquals(1, snapshot.resultReferences().size());
        assertEquals("STALE", snapshot.freshness().get(0).path("status").asText());

        ObjectNode returnedState = (ObjectNode) snapshot.operationalState();
        returnedState.put("toolName", "returned-mutation");
        assertEquals("search_agents", snapshot.operationalState().path("toolName").asText());
        assertNotSame(state, snapshot.operationalState());
    }
}
