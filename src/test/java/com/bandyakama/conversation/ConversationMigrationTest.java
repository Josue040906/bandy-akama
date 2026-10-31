package com.bandyakama.conversation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                CREATE TABLE bandy_tool_parameter (
                    tool_name VARCHAR(64) NOT NULL,
                    name VARCHAR(64) NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    description VARCHAR(1000) NOT NULL,
                    location VARCHAR(10),
                    required BOOLEAN NOT NULL DEFAULT FALSE,
                    position INTEGER NOT NULL,
                    PRIMARY KEY (tool_name, name)
                )
                """);
        jdbc.update("""
                INSERT INTO bandy_tool (name, description, when_to_use, http_method,
                    endpoint_url, enabled, updated_at)
                VALUES ('get_organization_info', 'Organization', 'Lookup organization',
                    'GET', 'http://localhost/organization', TRUE, CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO bandy_tool_parameter
                    (tool_name, name, type, description, location, required, position)
                VALUES
                    ('get_organization_info', 'operation', 'string', 'Operation', 'query', TRUE, 1),
                    ('get_organization_info', 'directionId', 'integer', 'Direction', 'query', FALSE, 2),
                    ('get_organization_info', 'serviceId', 'integer', 'Service', 'query', FALSE, 3),
                    ('get_organization_info', 'id', 'integer', 'Structure', 'query', FALSE, 4),
                    ('get_organization_info', 'posteId', 'integer', 'Unrelated old parameter', 'query', FALSE, 5)
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
                    conversation_id UUID NOT NULL,
                    idempotency_key UUID NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    response_json TEXT,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (conversation_id, idempotency_key)
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
        assertTrue(hasColumn(jdbc, "bandy_tool", "assistant_enabled"));
        assertTrue(hasColumn(jdbc, "bandy_tool", "result_entity_type"));
        assertTrue(hasColumn(jdbc, "bandy_tool", "result_collection_key"));
        assertTrue(hasColumn(jdbc, "bandy_tool_parameter", "allowed_values"));
        assertTrue(hasColumn(jdbc, "bandy_tool_parameter", "reference_type"));
        assertTrue(jdbc.queryForObject(
                "SELECT allowed_values FROM bandy_tool_parameter WHERE tool_name = 'get_organization_info' AND name = 'operation'",
                String.class
        ).contains("COUNT_EMPLOYEES_BY_STRUCTURE"));
        assertEquals("DIRECTION", jdbc.queryForObject(
                "SELECT reference_type FROM bandy_tool_parameter WHERE tool_name = 'get_organization_info' AND name = 'directionId'",
                String.class));
        assertEquals("SERVICE", jdbc.queryForObject(
                "SELECT reference_type FROM bandy_tool_parameter WHERE tool_name = 'get_organization_info' AND name = 'serviceId'",
                String.class));
        assertEquals("STRUCTURE", jdbc.queryForObject(
                "SELECT reference_type FROM bandy_tool_parameter WHERE tool_name = 'get_organization_info' AND name = 'id'",
                String.class));
        assertNull(jdbc.queryForObject(
                "SELECT reference_type FROM bandy_tool_parameter WHERE tool_name = 'get_organization_info' AND name = 'posteId'",
                String.class));
        assertEquals("DIRECTION", jdbc.queryForObject(
                "SELECT result_entity_type FROM bandy_tool WHERE name = 'get_organization_info'",
                String.class));
        assertNull(jdbc.queryForObject(
                "SELECT result_collection_key FROM bandy_tool WHERE name = 'get_organization_info'",
                String.class));
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
