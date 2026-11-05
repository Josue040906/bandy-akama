package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMigrationTest {
    @Test
    void upgradesAnExistingConversationSchemaWithoutResettingData() {
        String url = "jdbc:h2:mem:legacy_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(url, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID conversationId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        jdbc.execute("""
                CREATE TABLE bandy_tool (
                    name VARCHAR(64) PRIMARY KEY,
                    description VARCHAR(2000) NOT NULL,
                    when_to_use VARCHAR(2000) NOT NULL,
                    http_method VARCHAR(10) NOT NULL,
                    endpoint_url VARCHAR(2048) NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE bandy_conversation (
                    id UUID PRIMARY KEY,
                    application_id VARCHAR(120) NOT NULL,
                    owner_id VARCHAR(200) NOT NULL,
                    label VARCHAR(200) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    archived_at TIMESTAMP WITH TIME ZONE,
                    active_request_id UUID,
                    processing_until TIMESTAMP WITH TIME ZONE
                )
                """);
        jdbc.execute("""
                CREATE TABLE bandy_conversation_request (
                    conversation_id UUID NOT NULL
                        REFERENCES bandy_conversation(id) ON DELETE CASCADE,
                    idempotency_key UUID NOT NULL,
                    status VARCHAR(20) NOT NULL
                        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
                    response_json TEXT,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (conversation_id, idempotency_key)
                )
                """);
        jdbc.execute("""
                CREATE TABLE bandy_conversation_message (
                    id UUID PRIMARY KEY,
                    conversation_id UUID NOT NULL
                        REFERENCES bandy_conversation(id) ON DELETE CASCADE,
                    sequence_number BIGINT NOT NULL,
                    role VARCHAR(20) NOT NULL
                        CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
                    content TEXT NOT NULL,
                    message_type VARCHAR(40) NOT NULL,
                    metadata JSONB NOT NULL,
                    request_id UUID,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    UNIQUE (conversation_id, sequence_number)
                )
                """);
        jdbc.execute("""
                CREATE INDEX idx_bandy_message_conversation_order
                    ON bandy_conversation_message (conversation_id, sequence_number)
                """);
        jdbc.execute("""
                CREATE INDEX idx_bandy_message_request
                    ON bandy_conversation_message (conversation_id, request_id)
                """);
        jdbc.execute("""
                CREATE TABLE bandy_conversation_memory (
                    conversation_id UUID PRIMARY KEY
                        REFERENCES bandy_conversation(id) ON DELETE CASCADE,
                    summary TEXT NOT NULL DEFAULT '',
                    active_state JSONB NOT NULL,
                    result_references JSONB NOT NULL,
                    summarized_through_sequence BIGINT NOT NULL DEFAULT 0,
                    schema_version INTEGER NOT NULL DEFAULT 1,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.update("""
                INSERT INTO bandy_conversation (
                    id, application_id, owner_id, label, status, created_at,
                    updated_at, last_activity_at
                ) VALUES (?, 'assistant-rh', '42', 'Existing', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                conversationId
        );
        jdbc.update("""
                INSERT INTO bandy_conversation_request (
                    conversation_id, idempotency_key, status,
                    created_at, updated_at
                ) VALUES (?, ?, 'COMPLETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                conversationId,
                requestId
        );

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineVersion("2")
                .load()
                .baseline();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineVersion("2")
                .load()
                .migrate();

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM bandy_conversation WHERE id = ?",
                Integer.class,
                conversationId
        ));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM bandy_conversation_request "
                        + "WHERE conversation_id = ? AND idempotency_key = ?",
                Integer.class,
                conversationId,
                requestId
        ));
        assertTrue(hasColumn(jdbc, "bandy_conversation", "active_claim_id"));
        assertTrue(hasColumn(jdbc, "bandy_conversation_request", "active_claim_id"));
        assertTrue(hasColumn(jdbc, "bandy_conversation_request", "request_hash"));
        assertTrue(hasColumn(jdbc, "bandy_tool", "sensitive"));
        assertTrue(hasTable(jdbc, "bandy_conversation_message"));
        assertTrue(hasTable(jdbc, "bandy_conversation_memory"));
        assertTrue(hasColumn(jdbc, "bandy_conversation_memory", "dialogue_state"));
        jdbc.update("""
                INSERT INTO bandy_conversation_memory (
                    conversation_id, active_state, result_references
                ) VALUES (?, CAST('{}' AS JSONB), CAST('[]' AS JSONB))
                """,
                conversationId
        );
        String dialogueState = jdbc.queryForObject("""
                SELECT CAST(dialogue_state AS VARCHAR)
                FROM bandy_conversation_memory
                WHERE conversation_id = ?
                """,
                String.class,
                conversationId
        );
        try {
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode storedValue =
                    mapper.readTree(dialogueState);

            // H2's PostgreSQL-mode JSONB compatibility layer may expose the
            // JSON document as a JSON string; PostgreSQL exposes the object.
            // Normalize both representations before asserting the contract.
            if (storedValue.isTextual()) {
                storedValue = mapper.readTree(storedValue.asText());
            }

            assertTrue(storedValue.isObject(),
                    "dialogue_state doit être un objet JSON.");
            assertEquals(1, storedValue.path("schemaVersion").asInt(),
                    "dialogue_state doit utiliser le schéma universel v1.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(
                    "La valeur dialogue_state doit être un JSON valide.",
                    exception
            );
        }
    }


    private boolean hasTable(JdbcTemplate jdbc, String tableName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
                    WHERE LOWER(TABLE_NAME) = LOWER(?)
                )
                """,
                Boolean.class,
                tableName
        ));
    }

    private boolean hasColumn(
            JdbcTemplate jdbc,
            String tableName,
            String columnName
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE LOWER(TABLE_NAME) = LOWER(?)
                        AND LOWER(COLUMN_NAME) = LOWER(?)
                )
                """,
                Boolean.class,
                tableName,
                columnName
        ));
    }
}
