package com.bandyakama.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Demandes de tâches confirmées. Chaque changement de statut est conditionnel
 * (UPDATE ... WHERE status = ?) : deux clics simultanés ne peuvent pas
 * exécuter deux fois la même demande.
 */
@Repository
public class PendingConfirmationRepository {
    private final JdbcTemplate jdbcTemplate;

    public PendingConfirmationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(
            UUID id,
            UUID conversationId,
            UUID requestId,
            String toolName,
            String argumentsJson,
            Instant expiresAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO bandy_pending_confirmation (
                    id, conversation_id, request_id, tool_name, arguments_json, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, conversationId, requestId, toolName, argumentsJson, Timestamp.from(expiresAt)
        );
    }

    /** La demande n'est visible que par le propriétaire de sa conversation. */
    public Optional<PendingConfirmation> findOwned(
            UUID id,
            UUID conversationId,
            String applicationId,
            String ownerId
    ) {
        return jdbcTemplate.query("""
                SELECT p.id, p.tool_name, p.arguments_json, p.status, p.result_json,
                       CASE WHEN p.expires_at <= CURRENT_TIMESTAMP THEN TRUE ELSE FALSE END
                           AS expired
                FROM bandy_pending_confirmation p
                JOIN bandy_conversation c ON c.id = p.conversation_id
                WHERE p.id = ? AND p.conversation_id = ?
                    AND c.application_id = ? AND c.owner_id = ?
                """,
                (resultSet, rowNumber) -> new PendingConfirmation(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("tool_name"),
                        resultSet.getString("arguments_json"),
                        resultSet.getString("status"),
                        resultSet.getString("result_json"),
                        resultSet.getBoolean("expired")
                ),
                id, conversationId, applicationId, ownerId
        ).stream().findFirst();
    }

    /** PENDING -> EXECUTING, seulement si la demande n'a pas expiré. */
    public boolean claimForExecution(UUID id) {
        return jdbcTemplate.update("""
                UPDATE bandy_pending_confirmation
                SET status = 'EXECUTING', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP
                """,
                id
        ) == 1;
    }

    public boolean transition(UUID id, String fromStatus, String toStatus) {
        return jdbcTemplate.update("""
                UPDATE bandy_pending_confirmation
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """,
                toStatus, id, fromStatus
        ) == 1;
    }

    /** EXECUTING -> CONFIRMED ou FAILED, avec le résultat renvoyé par l'API. */
    public void finish(UUID id, String status, String resultJson) {
        jdbcTemplate.update("""
                UPDATE bandy_pending_confirmation
                SET status = ?, result_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'EXECUTING'
                """,
                status, resultJson, id
        );
    }

    public record PendingConfirmation(
            UUID id,
            String toolName,
            String argumentsJson,
            String status,
            String resultJson,
            boolean expired
    ) {
    }
}
