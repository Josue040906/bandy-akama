package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-side persistence boundary for conversational context.
 *
 * <p>Owns retrieval of prior messages, summaries and result references. It
 * delegates prompt assembly and budget compaction to
 * {@link ConversationContextAssembler}; it does not classify intent, interpret
 * dialogue, mutate operational state or execute tools.</p>
 */
@Repository
public class ConversationContextRepository {
    private static final int MAX_RECENT_CONTENT_LENGTH = 1_800;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationContextAssembler contextAssembler;
    private final int recentMessages;
    private final int maxContextCharacters;

    public ConversationContextRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ConversationContextAssembler contextAssembler,
            @Value("${bandy.conversations.context.recent-messages:10}")
            int recentMessages,
            @Value("${bandy.conversations.context.max-characters:18000}")
            int maxContextCharacters
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate must not be null."
        );
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null."
        );
        this.contextAssembler = Objects.requireNonNull(
                contextAssembler,
                "contextAssembler must not be null."
        );
        if (recentMessages < 1 || maxContextCharacters < 1) {
            throw new IllegalArgumentException(
                    "Les limites de contexte doivent être positives."
            );
        }
        this.recentMessages = recentMessages;
        this.maxContextCharacters = maxContextCharacters;
    }

    public String contextForRequest(
            UUID conversationId,
            JsonNode activeState,
            boolean includeResultReferences
    ) {
        ContextMemory memory = loadMemory(conversationId);
        return contextAssembler.buildContext(
                activeState,
                includeResultReferences
                        ? memory.resultReferences()
                        : objectMapper.createArrayNode(),
                memory.summary(),
                maxContextCharacters
        );
    }

    public String contextForModelRequest(
            UUID conversationId,
            UUID requestId,
            JsonNode activeState,
            boolean includeResultReferences
    ) {
        ContextMemory memory = loadMemory(conversationId);
        List<ConversationPromptContext.Message> history =
                historyForRequest(conversationId, requestId);
        return contextAssembler.buildModelContext(
                activeState,
                includeResultReferences
                        ? memory.resultReferences()
                        : objectMapper.createArrayNode(),
                memory.summary(),
                history,
                maxContextCharacters
        );
    }

    /**
     * Returns domain-neutral conversational material for the dialogue engine.
     * The current user message is deliberately excluded from its own history.
     */
    public ConversationRepository.DialogueTurnContext dialogueTurnContextForRequest(
            UUID conversationId,
            UUID requestId
    ) {
        Long currentSequence = jdbcTemplate.queryForObject("""
                SELECT sequence_number
                FROM bandy_conversation_message
                WHERE conversation_id = ? AND request_id = ? AND role = 'USER'
                """,
                Long.class,
                conversationId,
                requestId
        );
        if (currentSequence == null) {
            throw new IllegalStateException(
                    "Le message courant est absent de l'historique de conversation."
            );
        }

        List<com.bandyakama.conversation.dialogue.DialogueMessage> history =
                jdbcTemplate.query("""
                        SELECT sequence_number, role, content
                        FROM bandy_conversation_message
                        WHERE conversation_id = ?
                            AND role IN ('USER', 'ASSISTANT')
                            AND (request_id IS NULL OR request_id <> ?)
                        ORDER BY sequence_number DESC
                        LIMIT ?
                        """,
                        (resultSet, rowNumber) ->
                                new com.bandyakama.conversation.dialogue.DialogueMessage(
                                        resultSet.getLong("sequence_number"),
                                        "USER".equals(resultSet.getString("role"))
                                                ? com.bandyakama.conversation.dialogue.DialogueMessage.Role.USER
                                                : com.bandyakama.conversation.dialogue.DialogueMessage.Role.ASSISTANT,
                                        truncate(
                                                resultSet.getString("content"),
                                                MAX_RECENT_CONTENT_LENGTH
                                        )
                                ),
                        conversationId,
                        requestId,
                        recentMessages
                );
        Collections.reverse(history);

        String summary = jdbcTemplate.queryForObject("""
                SELECT summary FROM bandy_conversation_memory
                WHERE conversation_id = ?
                """,
                String.class,
                conversationId
        );
        return new ConversationRepository.DialogueTurnContext(
                currentSequence,
                history,
                summary == null ? "" : summary
        );
    }

    private List<ConversationPromptContext.Message> historyForRequest(
            UUID conversationId,
            UUID requestId
    ) {
        List<ConversationPromptContext.Message> messages = jdbcTemplate.query("""
                        SELECT role, content
                        FROM bandy_conversation_message
                        WHERE conversation_id = ?
                            AND role IN ('USER', 'ASSISTANT')
                            AND (request_id IS NULL OR request_id <> ?)
                        ORDER BY sequence_number DESC
                        LIMIT ?
                        """,
                (resultSet, rowNumber) -> new ConversationPromptContext.Message(
                        "USER".equals(resultSet.getString("role"))
                                ? "user"
                                : "assistant",
                        truncate(
                                resultSet.getString("content"),
                                MAX_RECENT_CONTENT_LENGTH
                        )
                ),
                conversationId,
                requestId,
                recentMessages
        );
        Collections.reverse(messages);
        return List.copyOf(messages);
    }

    private ContextMemory loadMemory(UUID conversationId) {
        return jdbcTemplate.queryForObject("""
                        SELECT summary, result_references::text AS result_references
                        FROM bandy_conversation_memory
                        WHERE conversation_id = ?
                        """,
                (resultSet, rowNumber) -> new ContextMemory(
                        resultSet.getString("summary"),
                        readJson(resultSet.getString("result_references"))
                ),
                conversationId
        );
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            // H2 in PostgreSQL compatibility mode may expose JSONB as a
            // JSON-encoded string, while PostgreSQL returns the JSON value.
            if (parsed != null && parsed.isTextual()) {
                parsed = objectMapper.readTree(parsed.asText());
            }
            return parsed == null ? objectMapper.createArrayNode() : parsed;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Les références de contexte enregistrées ne sont pas un JSON valide.",
                    exception
            );
        }
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record ContextMemory(String summary, JsonNode resultReferences) {
    }
}
