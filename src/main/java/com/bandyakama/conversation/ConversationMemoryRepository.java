package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.task.TaskStateManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence boundary for long-lived conversation memory.
 *
 * <p>Owns summaries, operational-state snapshots and bounded references to
 * previous results. Domain-specific interpretation of tool state is delegated
 * to TaskStateManager. This component never decides whether a user message
 * continues a task; that decision belongs to the dialogue layer.</p>
 */
@Repository
public class ConversationMemoryRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskStateManager taskStateManager;
    private final int recentMessages;
    private final int maxSummaryLength;

    public ConversationMemoryRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TaskStateManager taskStateManager,
            @Value("${bandy.conversations.context.recent-messages:10}")
            int recentMessages,
            @Value("${bandy.conversations.context.max-summary-characters:4000}")
            int maxSummaryLength
    ) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate,
                "jdbcTemplate must not be null."
        );
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null."
        );
        this.taskStateManager = Objects.requireNonNull(
                taskStateManager,
                "taskStateManager must not be null."
        );
        if (recentMessages < 1 || maxSummaryLength < 1) {
            throw new IllegalArgumentException(
                    "Les limites de mémoire doivent être positives."
            );
        }
        this.recentMessages = recentMessages;
        this.maxSummaryLength = maxSummaryLength;
    }

    @Transactional
    public void updateMemory(
            UUID conversationId,
            ChatResponse response,
            List<ChatResponse.ToolResult> persistentToolResults
    ) {
        Objects.requireNonNull(response, "response must not be null.");
        List<ChatResponse.ToolState> toolStates =
                taskStateManager.updatesFor(response);
        boolean clearConversationState = response.resetConversationState();
        MemorySnapshot memory = jdbcTemplate.queryForObject("""
                        SELECT summary, active_state::text AS active_state,
                               result_references::text AS result_references,
                               summarized_through_sequence
                        FROM bandy_conversation_memory
                        WHERE conversation_id = ?
                        """,
                (resultSet, rowNumber) -> new MemorySnapshot(
                        resultSet.getString("summary"),
                        resultSet.getString("active_state"),
                        resultSet.getString("result_references"),
                        resultSet.getLong("summarized_through_sequence")
                ),
                conversationId
        );
        JsonNode previousState = readStoredJson(memory.activeState());
        String state = toJson(taskStateManager.applyToolUpdates(
                previousState,
                toolStates,
                clearConversationState
        ));
        ArrayNode references = readArray(memory.resultReferences());
        if (clearConversationState) {
            references.removeAll();
        }
        for (ChatResponse.ToolResult toolResult : persistentToolResults) {
            ObjectNode reference = objectMapper.createObjectNode();
            reference.put("toolName", toolResult.name());
            reference.set("arguments", objectMapper.valueToTree(toolResult.arguments()));
            reference.set("result", objectMapper.valueToTree(toolResult.result()));
            references.add(reference);
            while (references.size() > 5) {
                references.remove(0);
            }
        }

        String summary = memory.summary();
        long summarizedThrough = memory.summarizedThroughSequence();
        long cutoff = summaryCutoff(conversationId);
        if (cutoff > summarizedThrough) {
            List<String> olderMessages = jdbcTemplate.query("""
                    SELECT role, content FROM bandy_conversation_message
                    WHERE conversation_id = ? AND sequence_number > ?
                        AND sequence_number <= ? AND role IN ('USER', 'ASSISTANT')
                    ORDER BY sequence_number
                    """,
                    (resultSet, rowNumber) -> resultSet.getString("role")
                            + ": " + resultSet.getString("content"),
                    conversationId,
                    summarizedThrough,
                    cutoff
            );
            for (String olderMessage : olderMessages) {
                summary = appendSummary(summary, olderMessage);
            }
            summarizedThrough = cutoff;
        }

        jdbcTemplate.update("""
                UPDATE bandy_conversation_memory
                SET summary = ?, active_state = CAST(? AS JSONB),
                    result_references = CAST(? AS JSONB),
                    summarized_through_sequence = ?, updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ?
                """,
                summary,
                state,
                toJson(references),
                summarizedThrough,
                conversationId
        );
    }

    /**
     * Creates memory for legacy conversations and reconstructs its snapshot
     * from durable messages without resetting the conversation history.
     */
    @Transactional
    public void ensureMemory(UUID conversationId) {
        boolean memoryExists = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM bandy_conversation_memory
                    WHERE conversation_id = ?
                )
                """,
                Boolean.class,
                conversationId
        ));
        if (memoryExists) {
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO bandy_conversation_memory (
                    conversation_id, summary, active_state, result_references
                ) VALUES (?, '', CAST(? AS JSONB), CAST(? AS JSONB))
                """,
                conversationId,
                "{}",
                "[]"
        );
        List<HistoricalMessage> history = jdbcTemplate.query("""
                SELECT sequence_number, request_id, role, content,
                       metadata::text AS metadata
                FROM bandy_conversation_message
                WHERE conversation_id = ? AND role IN ('USER', 'ASSISTANT', 'TOOL')
                ORDER BY sequence_number
                """,
                (resultSet, rowNumber) -> new HistoricalMessage(
                        resultSet.getLong("sequence_number"),
                        resultSet.getObject("request_id", UUID.class),
                        resultSet.getString("role"),
                        resultSet.getString("content"),
                        readStoredJson(resultSet.getString("metadata"))
                ),
                conversationId
        );
        HistoricalMessage latestReset = history.stream()
                .filter(message -> "ASSISTANT".equals(message.role()))
                .filter(message -> message.metadata()
                        .path("resetConversationState").asBoolean(false))
                .max(java.util.Comparator.comparingLong(
                        HistoricalMessage::sequenceNumber
                ))
                .orElse(null);
        long latestResetSequence = latestReset == null
                ? 0
                : latestReset.sequenceNumber();
        UUID resetRequestId = latestReset == null
                ? null
                : latestReset.requestId();
        ArrayNode references = objectMapper.createArrayNode();
        String state = "{}";
        for (HistoricalMessage message : history) {
            if (!"TOOL".equals(message.role())
                    || (message.sequenceNumber() <= latestResetSequence
                    && !Objects.equals(message.requestId(), resetRequestId))) {
                continue;
            }
            JsonNode metadata = message.metadata();
            String name = metadata.path("name").asText("");
            JsonNode arguments = metadata.path("arguments");
            state = toJson(taskStateManager.fromHistoricalToolResult(
                    name,
                    arguments
            ));

            ObjectNode reference = objectMapper.createObjectNode();
            reference.put("toolName", name);
            reference.set("arguments", arguments);
            try {
                reference.set("result", objectMapper.readTree(message.content()));
            } catch (JsonProcessingException nonJsonToolResult) {
                reference.put("resultText", message.content());
            }
            references.add(reference);
            while (references.size() > 5) {
                references.remove(0);
            }
        }

        long summarizedThrough = summaryCutoff(conversationId);
        String summary = "";
        for (HistoricalMessage message : history) {
            if (message.sequenceNumber() > summarizedThrough) {
                break;
            }
            if ("USER".equals(message.role()) || "ASSISTANT".equals(message.role())) {
                summary = appendSummary(
                        summary,
                        message.role() + ": " + message.content()
                );
            }
        }
        jdbcTemplate.update("""
                UPDATE bandy_conversation_memory
                SET summary = ?, active_state = CAST(? AS JSONB),
                    result_references = CAST(? AS JSONB),
                    summarized_through_sequence = ?, updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ?
                """,
                summary,
                state,
                toJson(references),
                summarizedThrough,
                conversationId
        );
    }

    private String appendSummary(String summary, String message) {
        String compact = redactSummaryIdentifiers(message);
        compact = compact.length() > 600 ? compact.substring(0, 600) : compact;
        String combined = summary.isBlank() ? compact : summary + "\n" + compact;
        return combined.length() > maxSummaryLength
                ? combined.substring(combined.length() - maxSummaryLength)
                : combined;
    }

    private String redactSummaryIdentifiers(String message) {
        return message
                .replaceAll(
                        "(?i)\\b[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}\\b",
                        "[email]"
                )
                .replaceAll("(?:\\+?\\d[\\d\\s().-]{6,}\\d)", "[numero]")
                .replaceAll(
                        "(?i)\\b(?=[A-Z0-9-]{6,}\\b)"
                                + "(?=[A-Z0-9-]*\\d)[A-Z0-9-]+\\b",
                        "[identifiant]"
                );
    }

    private long summaryCutoff(UUID conversationId) {
        int retainedMessages = Math.max(1, recentMessages);
        List<Long> recentSequences = jdbcTemplate.query("""
                SELECT sequence_number
                FROM bandy_conversation_message
                WHERE conversation_id = ? AND role IN ('USER', 'ASSISTANT')
                ORDER BY sequence_number DESC
                LIMIT ?
                """,
                (resultSet, rowNumber) -> resultSet.getLong("sequence_number"),
                conversationId,
                retainedMessages
        );
        if (recentSequences.size() < retainedMessages) {
            return 0;
        }
        return Math.max(
                0,
                recentSequences.get(recentSequences.size() - 1) - 1
        );
    }

    private ArrayNode readArray(String value) {
        JsonNode node = readStoredJson(value);
        return node instanceof ArrayNode array
                ? array
                : objectMapper.createArrayNode();
    }

    private JsonNode readStoredJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            return node != null && node.isTextual()
                    ? objectMapper.readTree(node.textValue())
                    : node;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Une valeur JSON de la mémoire est illisible.",
                    exception
            );
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Impossible de sérialiser la mémoire conversationnelle.",
                    exception
            );
        }
    }

    private record MemorySnapshot(
            String summary,
            String activeState,
            String resultReferences,
            long summarizedThroughSequence
    ) {
    }

    private record HistoricalMessage(
            long sequenceNumber,
            UUID requestId,
            String role,
            String content,
            JsonNode metadata
    ) {
    }
}
