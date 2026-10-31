package com.bandyakama.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantConfigurationRepositoryTest {
    @Test
    void readsProfileAndEnabledToolDefinitionsInOneLiveSnapshot() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try {
            CountingJdbcTemplate jdbcTemplate = new CountingJdbcTemplate(database);
            createSchema(jdbcTemplate);
            insertConfiguration(jdbcTemplate);

            AssistantConfigurationRepository repository =
                    new AssistantConfigurationRepository(jdbcTemplate);
            int statementsBeforeSnapshot = jdbcTemplate.queryCount();
            AssistantConfigurationSnapshot snapshot = repository.get();

            assertEquals(1, jdbcTemplate.queryCount() - statementsBeforeSnapshot);
            assertEquals(
                    new AssistantProfile("Assistant", "Reusable", "Instructions"),
                    snapshot.profile()
            );
            assertEquals(3, snapshot.enabledTools().size());
            assertFalse(snapshot.enabledTools().stream()
                    .anyMatch(tool -> "legacy_lookup".equals(tool.getName())));
            assertEquals(
                    "get_agent_profile",
                    snapshot.enabledTools().get(0).getName()
            );
            assertEquals(1, snapshot.enabledTools().get(0).getParameters().size());
            assertEquals(
                    "nom_agent",
                    snapshot.enabledTools().get(0).getParameters().get(0).getName()
            );
            assertTrue(snapshot.enabledTools().get(0).isSensitive());
            var organization = snapshot.enabledTools().stream()
                    .filter(tool -> "get_organization_info".equals(tool.getName()))
                    .findFirst().orElseThrow();
            assertEquals("DIRECTION", organization.getResultEntityType());
            assertEquals("DIRECTION", organization.getParameters().stream()
                    .filter(parameter -> "directionId".equals(parameter.getName()))
                    .findFirst().orElseThrow().getReferenceType());
            assertEquals(
                    List.of("LIST_DIRECTIONS", "COUNT_EMPLOYEES_BY_STRUCTURE"),
                    snapshot.enabledTools().stream()
                            .filter(tool -> "get_organization_info".equals(tool.getName()))
                            .findFirst().orElseThrow()
                            .getParameters().stream()
                            .filter(parameter -> "operation".equals(parameter.getName()))
                            .findFirst().orElseThrow()
                            .getAllowedValues()
            );
            assertEquals(
                    List.of("LIST", "COUNT"),
                    snapshot.enabledTools().stream()
                            .filter(tool -> "search_agents".equals(tool.getName()))
                            .findFirst().orElseThrow()
                            .getParameters().get(1).getAllowedValues()
            );

            jdbcTemplate.update(
                    "UPDATE bandy_assistant_profile SET instructions = 'Updated' WHERE id = 1"
            );
            jdbcTemplate.update(
                    "UPDATE bandy_tool SET enabled = FALSE "
                            + "WHERE name IN ('get_agent_profile', "
                            + "'search_agents', 'get_organization_info')"
            );

            statementsBeforeSnapshot = jdbcTemplate.queryCount();
            AssistantConfigurationSnapshot updated = repository.get();
            assertEquals(1, jdbcTemplate.queryCount() - statementsBeforeSnapshot);
            assertEquals("Updated", updated.profile().instructions());
            assertTrue(updated.enabledTools().isEmpty());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void returnsProfileWhenNoToolsAreEnabled() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .build();
        try {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(database);
            createSchema(jdbcTemplate);
            jdbcTemplate.update("""
                    INSERT INTO bandy_assistant_profile
                    (id, name, role, instructions) VALUES
                    (1, 'Assistant', 'Reusable', 'Instructions')
                    """);

            AssistantConfigurationSnapshot snapshot =
                    new AssistantConfigurationRepository(jdbcTemplate).get();

            assertEquals("Assistant", snapshot.profile().name());
            assertTrue(snapshot.enabledTools().isEmpty());
        } finally {
            database.shutdown();
        }
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE bandy_assistant_profile (
                    id SMALLINT PRIMARY KEY,
                    name VARCHAR(120),
                    role VARCHAR(500),
                    instructions VARCHAR(12000)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE bandy_tool (
                    name VARCHAR(64) PRIMARY KEY,
                    description VARCHAR(2000),
                    when_to_use VARCHAR(2000),
                    http_method VARCHAR(10),
                    endpoint_url VARCHAR(2048),
                    enabled BOOLEAN,
                    assistant_enabled BOOLEAN,
                    sensitive BOOLEAN,
                    result_entity_type VARCHAR(40),
                    result_collection_key VARCHAR(64)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE bandy_tool_parameter (
                    tool_name VARCHAR(64),
                    name VARCHAR(64),
                    type VARCHAR(20),
                    description VARCHAR(1000),
                    location VARCHAR(10),
                    required BOOLEAN,
                    position INTEGER,
                    allowed_values VARCHAR(2000),
                    reference_type VARCHAR(40)
                )
                """);
    }

    private void insertConfiguration(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                INSERT INTO bandy_assistant_profile
                (id, name, role, instructions) VALUES
                (1, 'Assistant', 'Reusable', 'Instructions')
                """);
        jdbcTemplate.update("""
                INSERT INTO bandy_tool
                (name, description, when_to_use, http_method, endpoint_url,
                 enabled, assistant_enabled, sensitive, result_entity_type, result_collection_key) VALUES
                ('get_agent_profile', 'Agent profile', 'For agent questions',
                 'GET', 'http://localhost/profile', TRUE, TRUE, TRUE, NULL, NULL),
                ('search_agents', 'Agent search', 'For agent searches',
                 'POST', 'http://localhost/search', TRUE, TRUE, TRUE, NULL, NULL),
                ('get_organization_info', 'Organization', 'For organization questions',
                 'GET', 'http://localhost/organization', TRUE, TRUE, FALSE, 'DIRECTION', NULL),
                ('legacy_lookup', 'Legacy lookup', 'Never selected', 'GET',
                 'http://localhost/legacy', TRUE, FALSE, TRUE, NULL, NULL)
                """);
        jdbcTemplate.update("""
                INSERT INTO bandy_tool_parameter
                (tool_name, name, type, description, location, required, position, allowed_values, reference_type)
                VALUES
                ('get_agent_profile', 'nom_agent', 'string', 'Agent name',
                 'query', TRUE, 1, NULL, NULL),
                ('search_agents', 'criteriaJson', 'string', 'Search criteria',
                 'body', TRUE, 1, NULL, NULL),
                ('search_agents', 'resultMode', 'string', 'Result mode',
                 'body', FALSE, 2, '["LIST","COUNT"]', NULL),
                ('get_organization_info', 'operation', 'string', 'Organization operation',
                 'query', TRUE, 1, '["LIST_DIRECTIONS","COUNT_EMPLOYEES_BY_STRUCTURE"]', NULL),
                ('get_organization_info', 'directionId', 'integer', 'Direction identifier',
                 'query', FALSE, 2, NULL, 'DIRECTION'),
                ('get_organization_info', 'serviceId', 'integer', 'Service identifier',
                 'query', FALSE, 3, NULL, 'SERVICE'),
                ('legacy_lookup', 'legacy', 'string', 'Legacy parameter',
                 'query', TRUE, 1, NULL, NULL)
                """);
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {
        private int queryCount;

        private CountingJdbcTemplate(EmbeddedDatabase database) {
            super(database);
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> resultSetExtractor) {
            queryCount++;
            return super.query(sql, resultSetExtractor);
        }

        private int queryCount() {
            return queryCount;
        }
    }
}
