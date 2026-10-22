package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.observability.RequestBudget;
import com.bandyakama.security.UserIdentity;
import com.bandyakama.service.GeminiAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceBudgetTest {
    @Test
    void expiredTurnIsReportedAsTimeoutAndReleasesItsRequestClaim()
            throws Exception {
        ConversationRepository repository = mock(ConversationRepository.class);
        GeminiAssistant assistant = mock(GeminiAssistant.class);
        UUID claimId = UUID.randomUUID();
        when(repository.beginRequest(
                any(),
                anyString(),
                anyString(),
                any(),
                anyString()
        )).thenReturn(new ConversationRepository.RequestStart(
                null,
                claimId,
                new ObjectMapper().createObjectNode()
        ));
        when(repository.contextForRequest(
                any(),
                any(),
                any(),
                anyBoolean()
        )).thenReturn("");
        when(assistant.respond(
                anyString(),
                nullable(String.class),
                anyString(),
                any(),
                any()
        )).thenAnswer(invocation -> {
            Thread.sleep(250);
            return new ChatResponse("late response", java.util.List.of());
        });
        AtomicBoolean cleanupRanOutsideBudget = new AtomicBoolean();
        doAnswer(invocation -> {
            cleanupRanOutsideBudget.set(
                    RequestBudget.current().isEmpty()
            );
            return null;
        }).when(repository).failRequest(any(), any(), any());

        ConversationService service = new ConversationService(
                repository,
                assistant,
                new ConversationIntentResolver(),
                new PerformanceTelemetry(),
                100
        );

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.sendMessage(
                        UUID.randomUUID(),
                        new UserIdentity("42", "assistant-rh"),
                        "Cherche les agents du service.",
                        null,
                        UUID.randomUUID()
                )
        );

        assertEquals(504, failure.getStatusCode().value());
        assertTrue(cleanupRanOutsideBudget.get());
        verify(repository).failRequest(any(), any(), any());
    }
}
