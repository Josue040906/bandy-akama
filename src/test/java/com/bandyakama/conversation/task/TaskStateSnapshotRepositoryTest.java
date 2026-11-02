package com.bandyakama.conversation.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
