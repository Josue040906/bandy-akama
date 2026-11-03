package com.bandyakama.conversation.dialogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DialogueStateRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DialogueStateRepository repository =
            new DialogueStateRepository(jdbcTemplate, objectMapper);

    @Test
    void loadsEmptyStateFromVersionedJsonSnapshot() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(
                anyString(),
                eq(String.class),
                eq(conversationId)
        )).thenReturn(objectMapper.writeValueAsString(DialogueState.empty()));

        assertEquals(DialogueState.empty(), repository.load(conversationId));
    }

    @Test
    void savesACompleteDialogueSnapshotAsJsonb() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        DialogueState state = DialogueState.empty();
        String expectedJson = objectMapper.writeValueAsString(state);
        when(jdbcTemplate.update(anyString(), any(), eq(conversationId)))
                .thenReturn(1);
        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(conversationId)
        )).thenAnswer(invocation -> {
            RowMapper<?> rowMapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getObject("id", UUID.class)).thenReturn(assistantMessageId);
            when(resultSet.getString("metadata")).thenReturn("{}");
            return List.of(rowMapper.mapRow(resultSet, 0));
        });
        when(jdbcTemplate.update(anyString(), any(), eq(assistantMessageId)))
                .thenReturn(1);

        repository.save(conversationId, state);

        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("dialogue_state"),
                eq(expectedJson),
                eq(conversationId)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("metadata"),
                any(),
                eq(assistantMessageId)
        );
    }

    @Test
    void refusesToSilentlyReplaceMissingMemoryWithAnEmptyState() {
        UUID conversationId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(
                anyString(),
                eq(String.class),
                eq(conversationId)
        )).thenThrow(new EmptyResultDataAccessException(1));

        assertThrows(
                IllegalStateException.class,
                () -> repository.load(conversationId)
        );
    }

    @Test
    void refusesToSaveWhenConversationMemoryDoesNotExist() {
        UUID conversationId = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), any(), eq(conversationId)))
                .thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> repository.save(conversationId, DialogueState.empty())
        );
    }

    @Test
    void rejectsNullStateWithoutTouchingTheDatabase() {
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.save(UUID.randomUUID(), null)
        );
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
    }

    @Test
    void rejectsUnknownSchemaVersionsWhenLoading() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(
                anyString(),
                eq(String.class),
                eq(conversationId)
        )).thenReturn("""
                {"schemaVersion":99,"activeObjectiveId":null,
                 "objectives":[],"pendingClarification":null}
                """);

        assertThrows(
                IllegalStateException.class,
                () -> repository.load(conversationId)
        );
    }
}
