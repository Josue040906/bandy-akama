package com.bandyakama.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertEquals(4, snapshot.enabledTools().size());
            assertEquals(
                    "get_agent_profile",
                    snapshot.enabledTools().get(0).getName()
            );
            assertEquals(1, snapshot.enabledTools().get(0).getParameters().size());
            assertEquals(
                    "query",
                    snapshot.enabledTools().get(0).getParameters().get(0).getName()
            );
            assertTrue(snapshot.enabledTools().get(0).isSensitive());
            assertTrue(snapshot.enabledTools().stream()
                    .anyMatch(tool -> tool.getName().equals("new_registered_tool")));

            jdbcTemplate.update(
                    "UPDATE bandy_assistant_profile SET instructions = 'Updated' WHERE id = 1"
            );
            jdbcTemplate.update("UPDATE bandy_tool SET enabled = FALSE");

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
                    approved_for_gemini BOOLEAN DEFAULT FALSE,
                    sensitive BOOLEAN
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
                    position INTEGER
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
                 enabled, approved_for_gemini, sensitive) VALUES
                ('get_agent_profile', 'Agent profile', 'For agent questions',
                 'GET', 'http://localhost/profile', TRUE, TRUE, TRUE),
                ('search_agents', 'Agent search', 'For agent searches',
                 'POST', 'http://localhost/search', TRUE, TRUE, TRUE),
                ('get_organization_info', 'Organization', 'For organization questions',
                 'GET', 'http://localhost/organization', TRUE, TRUE, FALSE),
                ('legacy_lookup', 'Legacy lookup', 'Never selected', 'GET',
                 'http://localhost/legacy', TRUE, FALSE, TRUE),
                ('new_registered_tool', 'New tool', 'Newly approved tool', 'GET',
                 'http://localhost/new', TRUE, TRUE, FALSE)
                """);
        jdbcTemplate.update("""
                INSERT INTO bandy_tool_parameter
                (tool_name, name, type, description, location, required, position)
                VALUES
                ('get_agent_profile', 'query', 'string', 'Matricule or exact name',
                 'query', TRUE, 1),
                ('search_agents', 'criteriaJson', 'string', 'Search criteria',
                 'body', TRUE, 1),
                ('search_agents', 'resultMode', 'string', 'Result mode',
                 'body', FALSE, 2),
                ('get_organization_info', 'query', 'string', 'Organization query',
                 'query', TRUE, 1),
                ('get_organization_info', 'scope', 'string', 'Scope',
                 'query', FALSE, 2),
                ('legacy_lookup', 'legacy', 'string', 'Legacy parameter',
                 'query', TRUE, 1)
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
