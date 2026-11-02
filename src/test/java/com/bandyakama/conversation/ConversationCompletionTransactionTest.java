package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.conversation.dialogue.DialogueObjective;
import com.bandyakama.conversation.dialogue.DialogueObjectiveStatus;
import com.bandyakama.conversation.dialogue.DialogueReference;
import com.bandyakama.conversation.dialogue.DialogueState;
import com.bandyakama.conversation.dialogue.DialogueStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "bandy.conversations.allowed-application-ids=client-a,client-b",
        "bandy.conversations.context.recent-messages=10"
})
class ConversationCompletionTransactionTest {

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationCompletionService completionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private DialogueStateRepository dialogueStateRepository;

    @Test
    void rollsBackAssistantMessageRequestCompletionAndClaimWhenDialogueSaveFails() {
        Conversation conversation = conversationRepository.create("client-a", "rollback-owner");
        UUID conversationId = conversation.id();
        UUID requestId = UUID.randomUUID();
        ConversationRepository.RequestStart request = conversationRepository.beginRequest(
                conversationId,
                "client-a",
                "rollback-owner",
                requestId,
                "Prépare un rapport."
        );
        assertEquals(1, countMessages(conversationId));

        doThrow(new IllegalStateException("simulated dialogue persistence failure"))
                .when(dialogueStateRepository)
                .save(any(UUID.class), any(DialogueState.class));

        assertThrows(
                IllegalStateException.class,
                () -> completionService.complete(
                        conversationId,
                        requestId,
                        request.claimId(),
                        new ChatResponse("Voici le rapport.", List.of()),
                        java.util.Optional.of(nonEmptyState())
                )
        );

        assertEquals(1, countMessages(conversationId),
                "Le message assistant ajouté pendant completeRequest doit être annulé.");
        assertEquals("IN_PROGRESS", jdbcTemplate.queryForObject("""
                SELECT status FROM bandy_conversation_request
                WHERE conversation_id = ? AND idempotency_key = ?
                """, String.class, conversationId, requestId));
        assertEquals(requestId, jdbcTemplate.queryForObject("""
                SELECT active_request_id FROM bandy_conversation WHERE id = ?
                """, UUID.class, conversationId));
        assertNotNull(jdbcTemplate.queryForObject("""
                SELECT active_claim_id FROM bandy_conversation WHERE id = ?
                """, UUID.class, conversationId));
    }

    @Test
    void commitsAssistantCompletionAndNonEmptyDialogueSnapshotTogether() {
        Conversation conversation = conversationRepository.create("client-a", "success-owner");
        UUID conversationId = conversation.id();
        UUID requestId = UUID.randomUUID();
        ConversationRepository.RequestStart request = conversationRepository.beginRequest(
                conversationId,
                "client-a",
                "success-owner",
                requestId,
                "Continue le rapport."
        );

        completionService.complete(
                conversationId,
                requestId,
                request.claimId(),
                new ChatResponse("Rapport terminé.", List.of()),
                java.util.Optional.of(nonEmptyState())
        );

        assertEquals("COMPLETED", jdbcTemplate.queryForObject("""
                SELECT status FROM bandy_conversation_request
                WHERE conversation_id = ? AND idempotency_key = ?
                """, String.class, conversationId, requestId));
        assertEquals(2, countMessages(conversationId));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bandy_conversation_memory
                WHERE conversation_id = ?
                    AND dialogue_state::text LIKE '%objective-rollback-test%'
                """, Integer.class, conversationId));
        assertEquals(null, jdbcTemplate.queryForObject("""
                SELECT active_request_id FROM bandy_conversation WHERE id = ?
                """, UUID.class, conversationId));
    }

    private int countMessages(UUID conversationId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bandy_conversation_message
                WHERE conversation_id = ?
                """, Integer.class, conversationId);
    }

    private DialogueState nonEmptyState() {
        Instant now = Instant.parse("2026-11-02T11:00:00Z");
        DialogueReference reference = new DialogueReference(
                "reference-rollback-test",
                "rapport",
                "document",
                1
        );
        DialogueObjective objective = new DialogueObjective(
                "objective-rollback-test",
                "Terminer le rapport",
                "Le rapport est en cours de finalisation.",
                DialogueObjectiveStatus.ACTIVE,
                null,
                now,
                now,
                List.of(reference)
        );
        return new DialogueState(
                DialogueState.CURRENT_SCHEMA_VERSION,
                objective.id(),
                List.of(objective),
                null
        );
    }
}
