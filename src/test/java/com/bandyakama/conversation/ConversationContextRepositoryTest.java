package com.bandyakama.conversation;

import com.bandyakama.conversation.dialogue.DialogueMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationContextRepositoryTest {
    private JdbcTemplate jdbc;
    private ConversationContextRepository repository;
    private UUID conversationId;
    private UUID currentRequestId;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:context_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(url, "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE bandy_conversation_memory (
                    conversation_id UUID PRIMARY KEY,
                    summary TEXT,
                    result_references JSONB
                )
                """);
        jdbc.execute("""
                CREATE TABLE bandy_conversation_message (
                    conversation_id UUID NOT NULL,
                    sequence_number BIGINT NOT NULL,
                    request_id UUID,
                    role VARCHAR(20) NOT NULL,
                    content TEXT NOT NULL
                )
                """);

        ObjectMapper mapper = new ObjectMapper();
        repository = new ConversationContextRepository(
                jdbc,
                mapper,
                new ConversationContextAssembler(mapper),
                10,
                4_000
        );
        conversationId = UUID.randomUUID();
        currentRequestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bandy_conversation_memory
                    (conversation_id, summary, result_references)
                VALUES (?, ?, CAST(? AS JSONB))
                """,
                conversationId,
                "Earlier discussion about a quarterly report.",
                "[{\"type\":\"document\",\"id\":\"doc-1\"}]"
        );
        jdbc.update("""
                INSERT INTO bandy_conversation_message
                    (conversation_id, sequence_number, request_id, role, content)
                VALUES (?, 1, ?, 'USER', ?),
                       (?, 2, ?, 'ASSISTANT', ?),
                       (?, 3, ?, 'USER', ?)
                """,
                conversationId,
                UUID.randomUUID(),
                "Prepare a quarterly report.",
                conversationId,
                UUID.randomUUID(),
                "I can prepare the report.",
                conversationId,
                currentRequestId,
                "Add a regional comparison."
        );
    }

    @Test
    void buildsModelContextFromSummaryReferencesAndPriorHistoryExcludingCurrentTurn() {
        String context = repository.contextForModelRequest(
                conversationId,
                currentRequestId,
                new ObjectMapper().createObjectNode().put("intent", "report"),
                true
        );

        assertTrue(context.contains("quarterly report"));
        assertTrue(context.contains("doc-1"));
        assertTrue(context.contains("Prepare a quarterly report."));
        assertTrue(context.contains("I can prepare the report."));
        assertTrue(context.contains(ConversationPromptContext.marker()));
        assertTrue(!context.contains("Add a regional comparison."));
    }

    @Test
    void suppliesChronologicalDomainNeutralDialogueHistoryWithoutCurrentMessage() {
        ConversationRepository.DialogueTurnContext context =
                repository.dialogueTurnContextForRequest(
                        conversationId,
                        currentRequestId
                );

        assertEquals(3, context.currentMessageSequenceNumber());
        assertEquals(2, context.history().size());
        assertEquals(DialogueMessage.Role.USER, context.history().get(0).role());
        assertEquals(
                "Prepare a quarterly report.",
                context.history().get(0).content()
        );
        assertEquals(
                DialogueMessage.Role.ASSISTANT,
                context.history().get(1).role()
        );
        assertEquals(
                "Earlier discussion about a quarterly report.",
                context.conversationSummary()
        );
    }
}
