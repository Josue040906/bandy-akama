package com.bandyakama.assistant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssistantProfileRepository {
    private final JdbcTemplate jdbcTemplate;

    public AssistantProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AssistantProfile get() {
        return jdbcTemplate.queryForObject(
                "SELECT name, role, instructions FROM bandy_assistant_profile WHERE id = 1",
                (resultSet, rowNumber) -> new AssistantProfile(
                        resultSet.getString("name"),
                        resultSet.getString("role"),
                        resultSet.getString("instructions")
                )
        );
    }

    public AssistantProfile update(AssistantProfile profile) {
        int updatedRows = jdbcTemplate.update("""
                UPDATE bandy_assistant_profile
                SET name = ?, role = ?, instructions = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = 1
                """,
                profile.name(),
                profile.role(),
                profile.instructions()
        );
        if (updatedRows != 1) {
            throw new IllegalStateException("Le profil de l’assistant n’existe pas.");
        }
        return get();
    }
}
