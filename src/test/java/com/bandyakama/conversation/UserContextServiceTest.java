package com.bandyakama.conversation;

import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserContextServiceTest {
    private final ToolRepository tools = mock(ToolRepository.class);
    private final ToolExecutor executor = mock(ToolExecutor.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final UserContextService service =
            new UserContextService(tools, executor, conversations);
    private final UUID conversationId = UUID.randomUUID();

    @Test
    void keepsOnlyTheFieldsTheCatalogueDesignates() {
        ToolDefinition profile = profileTool();
        when(tools.findEnabled()).thenReturn(List.of(profile));
        when(executor.execute(eq(profile), eq(Map.of()), eq("Bearer u"), anyString()))
                .thenReturn(Map.of("prenom", "Tiana", "matricule", "FIC-0105", "email", "t@x.test"));

        service.ensureLoaded(conversationId, "Bearer u", UUID.randomUUID());

        assertEquals("{\"prenom\":\"Tiana\"}", saved().toString());
    }

    @Test
    void readsTheContextOnlyOncePerConversation() {
        when(conversations.hasUserContext(conversationId)).thenReturn(true);

        service.ensureLoaded(conversationId, "Bearer u", UUID.randomUUID());

        verify(executor, never()).execute(any(), anyMap(), any(), any());
        verify(conversations, never()).saveUserContext(any(), any());
    }

    @Test
    void aFailureLeavesAnEmptyContextWithoutBlockingTheTurn() {
        ToolDefinition profile = profileTool();
        when(tools.findEnabled()).thenReturn(List.of(profile));
        when(executor.execute(any(), anyMap(), any(), any()))
                .thenThrow(new IllegalStateException("panne"));

        service.ensureLoaded(conversationId, "Bearer u", UUID.randomUUID());

        assertEquals("{}", saved().toString());
    }

    private JsonNode saved() {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(conversations).saveUserContext(eq(conversationId), captor.capture());
        return captor.getValue();
    }

    private static ToolDefinition profileTool() {
        ToolDefinition tool = new ToolDefinition(
                "get_my_profile", "Profil.", "GET", "https://rh.test/api/moi", List.of()
        );
        tool.setWhenToUse("Quand l'utilisateur parle de lui.");
        tool.setUserContextFields(List.of("prenom"));
        return tool;
    }
}
