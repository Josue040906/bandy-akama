package com.bandyakama.conversation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(
        named = "BANDY_POSTGRES_TEST_URL",
        matches = ".+"
)
class ConversationPostgresMigrationTest {
    @Test
    void appliesAndRevalidatesAllMigrationsAgainstPostgresql() {
        String url = System.getenv("BANDY_POSTGRES_TEST_URL");
        String username = System.getenv().getOrDefault(
                "BANDY_POSTGRES_TEST_USERNAME",
                "postgres"
        );
        String password = System.getenv().getOrDefault(
                "BANDY_POSTGRES_TEST_PASSWORD",
                ""
        );
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(url, username, password);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
        flyway.migrate();
        flyway.validate();
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(8, jdbc.queryForObject(
                "SELECT MAX(version::INTEGER) FROM flyway_schema_history "
                        + "WHERE success = TRUE",
                Integer.class
        ));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                        AND table_name = 'bandy_conversation_message'
                        AND column_name = 'metadata'
                        AND udt_name = 'jsonb'
                )
                """,
                Boolean.class
        )));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                        AND table_name = 'bandy_conversation_message'
                        AND column_name = 'context_reset'
                        AND data_type = 'boolean'
                )
                """,
                Boolean.class
        )));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = current_schema()
                        AND table_name = 'bandy_tool_example'
                )
                """,
                Boolean.class
        )));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                        AND table_name = 'bandy_tool_example'
                        AND column_name = 'example_text'
                        AND character_maximum_length = 500
                )
                """,
                Boolean.class
        )));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                        AND table_name = 'bandy_tool_example'
                        AND column_name = 'pattern_text'
                        AND character_maximum_length = 500
                )
                """,
                Boolean.class
        )));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                        AND table_name = 'bandy_tool_example'
                        AND column_name = 'expected_arguments'
                        AND data_type = 'text'
                )
                """,
                Boolean.class
        )));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_constraint constraint_definition
                    JOIN pg_class child_table
                      ON child_table.oid = constraint_definition.conrelid
                    JOIN pg_namespace child_schema
                      ON child_schema.oid = child_table.relnamespace
                    JOIN pg_class parent_table
                      ON parent_table.oid = constraint_definition.confrelid
                    WHERE constraint_definition.contype = 'f'
                      AND child_schema.nspname = current_schema()
                      AND child_table.relname = 'bandy_tool_example'
                      AND parent_table.relname = 'bandy_tool'
                      AND constraint_definition.confdeltype = 'c'
                )
                """,
                Boolean.class
        )));
    }
}
