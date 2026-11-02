package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ConversationRepository {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final RowMapper<Conversation> CONVERSATION_ROW_MAPPER =
            (resultSet, rowNumber) -> new Conversation(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("label"),
                    resultSet.getString("status"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "last_activity_at")
            );

    private static final RowMapper<ConversationMessage> MESSAGE_ROW_MAPPER =
            (resultSet, rowNumber) -> new ConversationMessage(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getLong("sequence_number"),
                    resultSet.getString("role"),
                    resultSet.getString("content"),
                    resultSet.getString("message_type"),
                    json(resultSet.getString("metadata")),
                    instant(resultSet, "created_at")
            );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationContextRepository contextRepository;
    private final ConversationMemoryRepository memoryRepository;

    /**
     * Spring constructor: JDBC concerns stay here while task-state rules are
     * delegated to the dedicated application service.
     */
    @Autowired
    public ConversationRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ConversationContextRepository contextRepository,
            ConversationMemoryRepository memoryRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.contextRepository = java.util.Objects.requireNonNull(
                contextRepository,
                "contextRepository must not be null."
        );
        this.memoryRepository = java.util.Objects.requireNonNull(
                memoryRepository,
                "memoryRepository must not be null."
        );
    }

    /**
     * Retained for direct repository construction in existing tests and
     * migration utilities. Production wiring always uses the Spring constructor.
     */
    public ConversationRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            int recentMessages,
            int maxSummaryLength,
            int maxContextCharacters
    ) {
        this(
                jdbcTemplate,
                objectMapper,
                new ConversationContextRepository(
                        jdbcTemplate,
                        objectMapper,
                        new ConversationContextAssembler(objectMapper),
                        recentMessages,
                        maxContextCharacters
                ),
                new ConversationMemoryRepository(
                        jdbcTemplate,
                        objectMapper,
                        new com.bandyakama.conversation.task.TaskStateManager(objectMapper),
                        recentMessages,
                        maxSummaryLength
                )
        );
    }

    @Transactional
    public Conversation create(String applicationId, String ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO bandy_conversation (
                    id, application_id, owner_id, label, status
                ) VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                id,
                applicationId,
                ownerId,
                "Nouvelle conversation"
        );
        jdbcTemplate.update("""
                INSERT INTO bandy_conversation_memory (
                    conversation_id, summary, active_state, result_references
                ) VALUES (?, '', CAST(? AS JSONB), CAST(? AS JSONB))
                """,
                id,
                "{}",
                "[]"
        );
        return findOwned(id, applicationId, ownerId).orElseThrow();
    }

    public ConversationPage list(
            String applicationId,
            String ownerId,
            int page,
            int size
    ) {
        long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bandy_conversation
                WHERE application_id = ? AND owner_id = ?
                """,
                Long.class,
                applicationId,
                ownerId
        );
        List<Conversation> items = jdbcTemplate.query("""
                SELECT id, label, status, created_at, last_activity_at
                FROM bandy_conversation
                WHERE application_id = ? AND owner_id = ?
                ORDER BY last_activity_at DESC, id
                LIMIT ? OFFSET ?
                """,
                CONVERSATION_ROW_MAPPER,
                applicationId,
                ownerId,
                size,
                (long) page * size
        );
        return new ConversationPage(items, page, size, total);
    }

    public Optional<Conversation> findOwned(
            UUID id,
            String applicationId,
            String ownerId
    ) {
        return jdbcTemplate.query("""
                        SELECT id, label, status, created_at, last_activity_at
                        FROM bandy_conversation
                        WHERE id = ? AND application_id = ? AND owner_id = ?
                        """,
                CONVERSATION_ROW_MAPPER,
                id,
                applicationId,
                ownerId
        ).stream().findFirst();
    }

    public ConversationMessagePage messages(
            UUID conversationId,
            String applicationId,
            String ownerId,
            int page,
            int size
    ) {
        requireOwned(conversationId, applicationId, ownerId);
        long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bandy_conversation_message
                WHERE conversation_id = ?
                """,
                Long.class,
                conversationId
        );
        List<ConversationMessage> items = jdbcTemplate.query("""
                SELECT id, sequence_number, role, content, message_type,
                       metadata::text AS metadata, created_at
                FROM bandy_conversation_message
                WHERE conversation_id = ?
                ORDER BY sequence_number
                LIMIT ? OFFSET ?
                """,
                MESSAGE_ROW_MAPPER,
                conversationId,
                size,
                (long) page * size
        );
        return new ConversationMessagePage(conversationId, items, page, size, total);
    }

    @Transactional
    public void rename(
            UUID id,
            String applicationId,
            String ownerId,
            String label
    ) {
        int updated = jdbcTemplate.update("""
                UPDATE bandy_conversation
                SET label = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND application_id = ? AND owner_id = ?
                """,
                label.trim(),
                id,
                applicationId,
                ownerId
        );
        if (updated == 0) {
            throw notFound();
        }
    }

    @Transactional
    public void archive(UUID id, String applicationId, String ownerId) {
        int updated = jdbcTemplate.update("""
                UPDATE bandy_conversation
                SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND application_id = ? AND owner_id = ?
                """,
                id,
                applicationId,
                ownerId
        );
        if (updated == 0) {
            throw notFound();
        }
    }

    @Transactional
    public void delete(UUID id, String applicationId, String ownerId) {
        int deleted = jdbcTemplate.update("""
                DELETE FROM bandy_conversation
                WHERE id = ? AND application_id = ? AND owner_id = ?
                    AND (
                        active_request_id IS NULL
                        OR processing_until IS NULL
                        OR processing_until < CURRENT_TIMESTAMP
                    )
                """,
                id,
                applicationId,
                ownerId
        );
        if (deleted > 0) {
            return;
        }

        boolean ownedConversationExists = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1 FROM bandy_conversation
                            WHERE id = ? AND application_id = ? AND owner_id = ?
                        )
                        """,
                        Boolean.class,
                        id,
                        applicationId,
                        ownerId
                )
        );
        if (!ownedConversationExists) {
            throw notFound();
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Une conversation en cours de traitement ne peut pas être supprimée."
        );
    }

    @Transactional
    public RequestStart beginRequest(
            UUID conversationId,
            String applicationId,
            String ownerId,
            UUID idempotencyKey,
            String userMessage
    ) {
        requireOwned(conversationId, applicationId, ownerId);
        String requestHash = requestHash(userMessage);

        Optional<RequestRecord> previous = jdbcTemplate.query("""
                        SELECT status, request_hash FROM bandy_conversation_request
                        WHERE conversation_id = ? AND idempotency_key = ?
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(new RequestRecord(
                                resultSet.getString("status"),
                                resultSet.getString("request_hash")
                        ))
                        : Optional.empty(),
                conversationId,
                idempotencyKey
        );
        if (previous.isPresent()) {
            if (previous.get().requestHash() == null) {
                verifyAndUpgradeLegacyRequest(
                        conversationId,
                        idempotencyKey,
                        requestHash,
                        userMessage
                );
            } else if (!requestHash.equals(previous.get().requestHash())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Cette clé d’idempotence a déjà été utilisée pour un autre message."
                );
            }
        }
        if (previous.isPresent()
                && "COMPLETED".equals(previous.get().status())) {
            return new RequestStart(
                    readSavedResponse(conversationId, idempotencyKey),
                    null,
                    null
            );
        }
        if (previous.isPresent()
                && "IN_PROGRESS".equals(previous.get().status())
                && isActiveRequest(conversationId, idempotencyKey)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cette requête est déjà en cours."
            );
        }

        UUID claimId = UUID.randomUUID();
        int claimed = jdbcTemplate.update("""
                UPDATE bandy_conversation
                SET active_request_id = ?, active_claim_id = ?,
                    processing_until = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND application_id = ? AND owner_id = ?
                    AND status = 'ACTIVE'
                    AND (active_request_id IS NULL OR processing_until < CURRENT_TIMESTAMP)
                """,
                idempotencyKey,
                claimId,
                Timestamp.from(Instant.now().plusSeconds(600)),
                conversationId,
                applicationId,
                ownerId
        );
        if (claimed == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Une autre requête est en cours pour cette conversation."
            );
        }
        jdbcTemplate.update("""
                UPDATE bandy_conversation_request
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ? AND status = 'IN_PROGRESS'
                    AND idempotency_key <> ?
                """,
                conversationId,
                idempotencyKey
        );
        if (previous.isPresent()) {
            jdbcTemplate.update("""
                    UPDATE bandy_conversation_request
                    SET status = 'IN_PROGRESS', active_claim_id = ?,
                        response_json = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE conversation_id = ? AND idempotency_key = ?
                    """,
                    claimId,
                    conversationId,
                    idempotencyKey
            );
        } else {
            jdbcTemplate.update("""
                    INSERT INTO bandy_conversation_request (
                        conversation_id, idempotency_key, request_hash,
                        active_claim_id, status
                    ) VALUES (?, ?, ?, ?, 'IN_PROGRESS')
                    """,
                    conversationId,
                    idempotencyKey,
                    requestHash,
                    claimId
            );
        }

        boolean userMessageExists = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM bandy_conversation_message
                    WHERE conversation_id = ? AND request_id = ? AND role = 'USER'
                )
                """,
                Boolean.class,
                conversationId,
                idempotencyKey
        ));
        if (!userMessageExists) {
            insertMessage(
                    conversationId,
                    idempotencyKey,
                    "USER",
                    userMessage,
                    "TEXT",
                    "{}"
            );
        }

        memoryRepository.ensureMemory(conversationId);
        JsonNode activeState = readStoredJson(jdbcTemplate.queryForObject("""
                SELECT active_state::text FROM bandy_conversation_memory
                WHERE conversation_id = ?
                """,
                String.class,
                conversationId
        ));
        return new RequestStart(
                null,
                claimId,
                activeState
        );
    }

    public String contextForRequest(
            UUID conversationId,
            UUID requestId,
            JsonNode activeState,
            boolean includeResultReferences
    ) {
        return contextRepository.contextForRequest(
                conversationId,
                activeState,
                includeResultReferences
        );
    }

    public String contextForModelRequest(
            UUID conversationId,
            UUID requestId,
            JsonNode activeState,
            boolean includeResultReferences
    ) {
        return contextRepository.contextForModelRequest(
                conversationId,
                requestId,
                activeState,
                includeResultReferences
        );
    }

    /**
     * Returns the domain-neutral conversational material required by the
     * dialogue interpreter. The current user message is excluded from history.
     */
    public DialogueTurnContext dialogueTurnContextForRequest(
            UUID conversationId,
            UUID requestId
    ) {
        return contextRepository.dialogueTurnContextForRequest(
                conversationId,
                requestId
        );
    }

    @Transactional
    public void completeRequest(
            UUID conversationId,
            UUID idempotencyKey,
            UUID claimId,
            ChatResponse response
    ) {
        requireActiveClaim(conversationId, idempotencyKey, claimId);
        Set<String> sensitiveToolNames = sensitiveToolNames(response.toolResults());
        List<ChatResponse.ToolResult> persistentToolResults =
                response.toolResults().stream()
                        .filter(result -> !sensitiveToolNames.contains(result.name()))
                        .toList();
        long nextSequence = nextSequence(conversationId);
        for (ChatResponse.ToolResult toolResult : persistentToolResults) {
            String content = toJson(toolResult.result());
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("name", toolResult.name());
            metadata.set("arguments", objectMapper.valueToTree(toolResult.arguments()));
            insertMessage(
                    conversationId,
                    idempotencyKey,
                    "TOOL",
                    content,
                    "TOOL_RESULT",
                    toJson(metadata),
                    nextSequence++
            );
        }
        ObjectNode assistantMetadata = objectMapper.createObjectNode();
        assistantMetadata.set(
                "toolNames",
                objectMapper.valueToTree(response.toolResults().stream()
                        .map(ChatResponse.ToolResult::name)
                        .toList())
        );
        assistantMetadata.put(
                "resetConversationState",
                response.resetConversationState()
        );
        insertMessage(
                conversationId,
                idempotencyKey,
                "ASSISTANT",
                response.message(),
                "TEXT",
                toJson(assistantMetadata),
                nextSequence
        );
        memoryRepository.updateMemory(
                conversationId,
                new com.bandyakama.conversation.task.TaskStateManager(objectMapper)
                        .updatesFor(response),
                persistentToolResults,
                response.resetConversationState()
        );

        ChatResponse persistedResponse = new ChatResponse(
                response.message(),
                persistentToolResults,
                response.conversationId(),
                response.resetConversationState()
        );
        jdbcTemplate.update("""
                UPDATE bandy_conversation_request
                SET status = 'COMPLETED', response_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ? AND idempotency_key = ?
                """,
                toJson(persistedResponse),
                conversationId,
                idempotencyKey
        );
        releaseRequest(conversationId, idempotencyKey, claimId);
    }

    private Set<String> sensitiveToolNames(
            List<ChatResponse.ToolResult> toolResults
    ) {
        List<String> toolNames = toolResults.stream()
                .map(ChatResponse.ToolResult::name)
                .distinct()
                .toList();
        if (toolNames.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(
                ", ",
                java.util.Collections.nCopies(toolNames.size(), "?")
        );
        Set<String> sensitive = new HashSet<>(jdbcTemplate.query(
                "SELECT name FROM bandy_tool WHERE sensitive = TRUE "
                        + "AND name IN (" + placeholders + ")",
                (resultSet, rowNumber) -> resultSet.getString("name"),
                toolNames.toArray()
        ));
        toolNames.stream()
                .filter(name -> Set.of(
                        "get_agent_profile",
                        "search_agents"
                ).contains(name))
                .forEach(sensitive::add);
        return sensitive;
    }

    @Transactional
    public void failRequest(UUID conversationId, UUID idempotencyKey, UUID claimId) {
        jdbcTemplate.update("""
                UPDATE bandy_conversation_request
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE conversation_id = ? AND idempotency_key = ?
                    AND active_claim_id = ? AND status = 'IN_PROGRESS'
                """,
                conversationId,
                idempotencyKey,
                claimId
        );
        releaseRequest(conversationId, idempotencyKey, claimId);
    }

    private void releaseRequest(
            UUID conversationId,
            UUID idempotencyKey,
            UUID claimId
    ) {
        jdbcTemplate.update("""
                UPDATE bandy_conversation
                SET active_request_id = NULL, active_claim_id = NULL,
                    processing_until = NULL,
                    last_activity_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND active_request_id = ? AND active_claim_id = ?
                """,
                conversationId,
                idempotencyKey,
                claimId
        );
    }

    private void requireActiveClaim(
            UUID conversationId,
            UUID idempotencyKey,
            UUID claimId
    ) {
        boolean active = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM bandy_conversation
                    WHERE id = ? AND active_request_id = ? AND active_claim_id = ?
                )
                """,
                Boolean.class,
                conversationId,
                idempotencyKey,
                claimId
        ));
        if (!active) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cette exécution n’est plus propriétaire de la conversation."
            );
        }
    }

    private void insertMessage(
            UUID conversationId,
            UUID requestId,
            String role,
            String content,
            String messageType,
            String metadata
    ) {
        insertMessage(
                conversationId,
                requestId,
                role,
                content,
                messageType,
                metadata,
                nextSequence(conversationId)
        );
    }

    private long nextSequence(UUID conversationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(sequence_number), 0) + 1
                FROM bandy_conversation_message WHERE conversation_id = ?
                """,
                Long.class,
                conversationId
        );
    }

    private void insertMessage(
            UUID conversationId,
            UUID requestId,
            String role,
            String content,
            String messageType,
            String metadata,
            long sequenceNumber
    ) {
        jdbcTemplate.update("""
                INSERT INTO bandy_conversation_message (
                    id, conversation_id, sequence_number, role, content,
                    message_type, metadata, request_id
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
                """,
                UUID.randomUUID(),
                conversationId,
                sequenceNumber,
                role,
                content,
                messageType,
                metadata,
                requestId
        );
    }

    private ChatResponse readSavedResponse(
            UUID conversationId,
            UUID idempotencyKey
    ) {
        String responseJson = jdbcTemplate.queryForObject("""
                SELECT response_json FROM bandy_conversation_request
                WHERE conversation_id = ? AND idempotency_key = ?
                """,
                String.class,
                conversationId,
                idempotencyKey
        );
        try {
            return objectMapper.readValue(responseJson, ChatResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "La réponse enregistrée est illisible.",
                    exception
            );
        }
    }

    private void requireOwned(UUID id, String applicationId, String ownerId) {
        boolean exists = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM bandy_conversation
                    WHERE id = ? AND application_id = ? AND owner_id = ?
                )
                """,
                Boolean.class,
                id,
                applicationId,
                ownerId
        ));
        if (!exists) {
            throw notFound();
        }
    }

    private boolean isActiveRequest(UUID conversationId, UUID idempotencyKey) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM bandy_conversation
                    WHERE id = ? AND active_request_id = ?
                        AND processing_until >= CURRENT_TIMESTAMP
                )
                """,
                Boolean.class,
                conversationId,
                idempotencyKey
        ));
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Conversation introuvable."
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Impossible de sérialiser les données de conversation.",
                    exception
            );
        }
    }

    private String requestHash(String message) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(message.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 n’est pas disponible.",
                    exception
            );
        }
    }

    private void verifyAndUpgradeLegacyRequest(
            UUID conversationId,
            UUID idempotencyKey,
            String requestHash,
            String userMessage
    ) {
        List<String> previousUserMessages = jdbcTemplate.query("""
                SELECT content FROM bandy_conversation_message
                WHERE conversation_id = ? AND request_id = ? AND role = 'USER'
                """,
                (resultSet, rowNumber) -> resultSet.getString("content"),
                conversationId,
                idempotencyKey
        );
        if (previousUserMessages.size() != 1
                || !userMessage.equals(previousUserMessages.get(0))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cette clé d’idempotence ne peut pas être réutilisée pour ce message."
            );
        }
        jdbcTemplate.update("""
                UPDATE bandy_conversation_request
                SET request_hash = ?
                WHERE conversation_id = ? AND idempotency_key = ?
                    AND request_hash IS NULL
                """,
                requestHash,
                conversationId,
                idempotencyKey
        );
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

    private static JsonNode json(String value) {
        try {
            JsonNode node = JSON_MAPPER.readTree(value);
            return node != null && node.isTextual()
                    ? JSON_MAPPER.readTree(node.textValue())
                    : node;
        } catch (JsonProcessingException exception) {
            throw new SQLExceptionWrapper(exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public record DialogueTurnContext(
            long currentMessageSequenceNumber,
            List<com.bandyakama.conversation.dialogue.DialogueMessage> history,
            String conversationSummary
    ) {
        public DialogueTurnContext {
            if (currentMessageSequenceNumber < 1) {
                throw new IllegalArgumentException(
                        "currentMessageSequenceNumber must be positive."
                );
            }
            history = List.copyOf(history);
            conversationSummary = conversationSummary == null
                    ? ""
                    : conversationSummary;
        }
    }

    public record RequestStart(
            ChatResponse completedResponse,
            UUID claimId,
            JsonNode activeState
    ) {
    }

    private record RequestRecord(String status, String requestHash) {
    }

    private static final class SQLExceptionWrapper extends RuntimeException {
        private SQLExceptionWrapper(Throwable cause) {
            super(cause);
        }
    }
}
