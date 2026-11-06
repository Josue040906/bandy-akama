package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.executor.ToolExecutor;
import com.bandyakama.service.GeminiAssistant;
import com.bandyakama.tool.ToolActionLevel;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolParameter;
import com.bandyakama.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat des tâches confirmées, côté Bandy : la demande attend le clic
 * « Confirmer », s'exécute une seule fois avec le jeton de l'utilisateur et
 * la clé d'idempotence, et l'API reste juge des droits.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmationApiTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Map<String, Object> ARGUMENTS = Map.of("motif", "Congé annuel");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ToolRegistry toolRegistry;

    @MockBean
    private GeminiAssistant assistant;

    @MockBean
    private ToolExecutor toolExecutor;

    @BeforeEach
    void registerConfirmedTool() {
        ToolParameter motif = new ToolParameter("motif", "string", "Motif.", true);
        motif.setLocation("body");
        ToolDefinition tool = new ToolDefinition(
                "submit_leave_request", "Submits a leave request.", "POST",
                "https://example.test/api/conges", List.of(motif)
        );
        tool.setWhenToUse("When the user asks to submit a leave request.");
        tool.setActionLevel(ToolActionLevel.CONFIRM);
        toolRegistry.register(tool);
    }

    @AfterEach
    void resetMocks() {
        reset(assistant, toolExecutor);
    }

    @Test
    void executesOnceOnConfirmWithTheUserTokenAndTheConfirmationAsIdempotencyKey()
            throws Exception {
        UUID conversationId = createConversation("42");
        UUID confirmationId = proposeConfirmation(conversationId, "42");
        // Pendant le tour, rien n'est exécuté.
        verify(toolExecutor, never()).execute(any(), anyMap(), nullable(String.class), nullable(String.class));

        when(toolExecutor.execute(any(), anyMap(), anyString(), anyString()))
                .thenReturn(Map.of("id", 7, "statut", "ENREGISTREE"));
        for (int click = 0; click < 2; click++) {
            mockMvc.perform(post("/api/conversations/{c}/confirmations/{id}", conversationId, confirmationId)
                            .header("Authorization", bearerToken("42")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.result.statut").value("ENREGISTREE"));
        }

        // Deux clics, une seule exécution, avec le jeton et la clé d'idempotence.
        verify(toolExecutor, times(1)).execute(
                any(ToolDefinition.class), eq(ARGUMENTS),
                eq(bearerToken("42")), eq(confirmationId.toString())
        );
        assertTrue(memoryReferences(conversationId).contains("\"confirmation\":\"CONFIRMED\""));
        mockMvc.perform(delete("/api/conversations/{c}/confirmations/{id}", conversationId, confirmationId)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyTheOwnerCanConfirmAndCancelledOrExpiredRequestsNeverRun() throws Exception {
        UUID conversationId = createConversation("42");
        UUID cancelled = proposeConfirmation(conversationId, "42");

        mockMvc.perform(post("/api/conversations/{c}/confirmations/{id}", conversationId, cancelled)
                        .header("Authorization", bearerToken("43")))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/conversations/{c}/confirmations/{id}", conversationId, cancelled)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/conversations/{c}/confirmations/{id}", conversationId, cancelled)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isConflict());

        UUID expired = proposeConfirmation(conversationId, "42");
        jdbcTemplate.update(
                "UPDATE bandy_pending_confirmation SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), expired
        );
        mockMvc.perform(post("/api/conversations/{c}/confirmations/{id}", conversationId, expired)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isConflict());

        verify(toolExecutor, never()).execute(any(), anyMap(), nullable(String.class), nullable(String.class));
        assertEquals("EXPIRED", jdbcTemplate.queryForObject(
                "SELECT status FROM bandy_pending_confirmation WHERE id = ?", String.class, expired
        ));
    }

    @Test
    void apiRefusalFailsTheRequestWhileAnOutageKeepsItConfirmable() throws Exception {
        UUID conversationId = createConversation("42");
        UUID refused = proposeConfirmation(conversationId, "42");
        when(toolExecutor.execute(any(), anyMap(), anyString(), eq(refused.toString())))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "Forbidden", null,
                        "{\"message\":\"Droits insuffisants.\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                ));
        mockMvc.perform(post("/api/conversations/{c}/confirmations/{id}", conversationId, refused)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.result.status").value(403));

        UUID outage = proposeConfirmation(conversationId, "42");
        when(toolExecutor.execute(any(), anyMap(), anyString(), eq(outage.toString())))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, new byte[0], null
                ))
                .thenReturn(Map.of("statut", "ENREGISTREE"));
        mockMvc.perform(post("/api/conversations/{c}/confirmations/{id}", conversationId, outage)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isBadGateway());
        // Panne : la demande redevient confirmable, le nouvel essai réussit.
        mockMvc.perform(post("/api/conversations/{c}/confirmations/{id}", conversationId, outage)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    private UUID proposeConfirmation(UUID conversationId, String subject) throws Exception {
        when(assistant.respond(anyString(), anyString(), anyString(), any(UUID.class), nullable(JsonNode.class)))
                .thenReturn(new ChatResponse(
                        "Je prépare la demande de congé ; cliquez sur « Confirmer » pour l’envoyer.",
                        List.of(),
                        null,
                        List.of(new ChatResponse.Action("CONFIRM", "submit_leave_request", null, ARGUMENTS)),
                        List.of()
                ));
        String body = mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"dépose ma demande de congé annuel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].type").value("CONFIRM"))
                .andExpect(jsonPath("$.actions[0].fields.motif").value("Congé annuel"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body)
                .path("actions").get(0).path("confirmationId").asText());
    }

    private UUID createConversation(String subject) throws Exception {
        String body = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", bearerToken(subject)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).path("id").asText());
    }

    private String memoryReferences(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT result_references::text FROM bandy_conversation_memory WHERE conversation_id = ?",
                String.class, conversationId
        ).replace("\\\"", "\"");
    }

    private String bearerToken(String subject) {
        try {
            String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String claims = encode(objectMapper.writeValueAsBytes(Map.of(
                    "sub", subject, "iat", 1, "exp", 4_102_444_800L
            )));
            String signingInput = header + "." + claims;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "Bearer " + signingInput + "."
                    + encode(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
