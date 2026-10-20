package com.bandyakama.conversation;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.service.GeminiAssistant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties =
        "bandy.conversations.allowed-application-ids=client-a,client-b")
@AutoConfigureMockMvc
class ConversationApiTest {
    private static final String SECRET =
            "0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GeminiAssistant assistant;

    @AfterEach
    void resetAssistant() {
        reset(assistant);
    }

    @Test
    void persistsConversationsAndEnforcesOwnerAndApplicationIsolation()
            throws Exception {
        long existingOwnerConversations = conversationRepository
                .list("assistant-rh", "42", 0, 100)
                .total();
        UUID conversationId = createConversation("42");
        Conversation foreignApplicationConversation =
                conversationRepository.create("another-client", "42");
        ConversationRepository restartedRepository =
                new ConversationRepository(jdbcTemplate, objectMapper, 8, 4000, 18000);
        assertNotNull(restartedRepository.findOwned(
                conversationId,
                "assistant-rh",
                "42"
        ).orElse(null));

        mockMvc.perform(get("/api/conversations/{id}", conversationId)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId.toString()));

        mockMvc.perform(get("/api/conversations/{id}", conversationId)
                        .header("Authorization", bearerToken("43")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(
                        "/api/conversations/{id}",
                        foreignApplicationConversation.id()
                )
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", bearerToken("42"))
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(
                        existingOwnerConversations + 1
                ));
    }

    @Test
    void scopesSameUserByTheSignedClientApplicationAudience() throws Exception {
        UUID clientAConversation = createConversation("42", "client-a");

        mockMvc.perform(get(
                        "/api/conversations/{id}",
                        clientAConversation
                )
                        .header("Authorization", bearerToken("42", "client-a")))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                        "/api/conversations/{id}",
                        clientAConversation
                )
                        .header("Authorization", bearerToken("42", "client-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void storesMessagesInOrderAndReplaysAnIdempotentRequest()
            throws Exception {
        UUID conversationId = createConversation("42");
        UUID requestId = UUID.randomUUID();
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenReturn(new ChatResponse(
                "Quatre agents correspondent.",
                List.of(new ChatResponse.ToolResult(
                        "searchEmployees",
                        List.of(Map.of("matricule", "A-1")),
                        Map.of("criteriaJson", "{\"filters\":[]}")))
        ));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                            .header("Authorization", bearerToken("42"))
                            .header("Idempotency-Key", requestId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"Cherche des agents\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Quatre agents correspondent."))
                    .andExpect(jsonPath("$.conversationId").value(conversationId.toString()));
        }

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken("42"))
                        .header("Idempotency-Key", requestId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Autre demande\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].role").value("USER"))
                .andExpect(jsonPath("$.items[1].role").value("TOOL"))
                .andExpect(jsonPath("$.items[2].role").value("ASSISTANT"));

        verify(assistant, times(1)).respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        );
    }

    @Test
    void suppliesPriorFiltersAndResultsForFollowUpMessages() throws Exception {
        UUID conversationId = createConversation("42");
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenReturn(
                new ChatResponse(
                        "Quatre agentes trouvées.",
                        List.of(new ChatResponse.ToolResult(
                                "searchEmployees",
                                List.of(Map.of(
                                        "matricule", "A-1",
                                        "direction", "Direction générale"
                                )),
                                Map.of("criteriaJson",
                                        "{\"filters\":[{\"field\":\"direction\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"Direction générale\"},"
                                                + "{\"field\":\"sexe\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"F\"},"
                                                + "{\"field\":\"age\","
                                                + "\"operator\":\"LT\","
                                                + "\"value\":20}]}")
                        ))
                ),
                new ChatResponse(
                        "Voici les agentes de moins de 30 ans.",
                        List.of(new ChatResponse.ToolResult(
                                "searchEmployees",
                                List.of(Map.of("matricule", "B-2")),
                                Map.of("criteriaJson",
                                        "{\"filters\":[{\"field\":\"direction\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"Direction générale\"},"
                                                + "{\"field\":\"sexe\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"F\"},"
                                                + "{\"field\":\"age\","
                                                + "\"operator\":\"LT\","
                                                + "\"value\":30}]}")
                        ))
                ),
                new ChatResponse("Le résultat précédent contient ces agentes.", List.of())
        );

        send(conversationId, "42", "Cherche des femmes de moins de 20 ans.");
        send(conversationId, "42", "Maintenant celles de moins de 30 ans.");
        send(conversationId, "42", "Montre leurs matricules.");

        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(assistant, times(3)).respond(
                anyString(),
                anyString(),
                context.capture(),
                any(UUID.class),
                nullable(JsonNode.class)
        );
        String followUpContext = context.getAllValues().get(1);
        assertTrue(followUpContext.contains("Direction générale"));
        assertTrue(followUpContext.contains("\"age\""), followUpContext);
        assertTrue(followUpContext.contains("20"));
        String resultsContext = context.getAllValues().get(2);
        assertTrue(resultsContext.contains("Direction générale"));
        assertTrue(resultsContext.contains("30"));
        assertTrue(resultsContext.contains("\"value\":\"F\""));
        assertTrue(resultsContext.contains("B-2"));
        assertTrue(followUpContext.contains("Quatre agentes trouvées."));
    }

    @Test
    void clearsActiveFiltersForAnIndependentIntentButRetainsHistory()
            throws Exception {
        UUID conversationId = createConversation("42");
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenReturn(
                new ChatResponse(
                        "J’ai trouvé l’agent.",
                        List.of(new ChatResponse.ToolResult(
                                "searchEmployees",
                                List.of(Map.of("matricule", "A-7")),
                                Map.of("criteriaJson",
                                        "{\"filters\":[{\"field\":\"service\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"Personnel\"}]}")
                        ))
                ),
                new ChatResponse(
                        "Le Service du Personnel est une unité administrative.",
                        List.of(),
                        null,
                        true
                ),
                new ChatResponse("Voici une explication complémentaire.", List.of())
        );

        send(conversationId, "42", "Cherche les agents du Personnel.");
        send(conversationId, "42", "Explique le fonctionnement de la comptabilité.");
        send(conversationId, "42", "Peux-tu préciser ?");

        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(assistant, times(3)).respond(
                anyString(),
                anyString(),
                context.capture(),
                any(UUID.class),
                nullable(JsonNode.class)
        );
        String independentFollowUpContext = context.getAllValues().get(2);
        assertTrue(independentFollowUpContext.contains(
                "État structuré précédent (à modifier seulement si cette demande "
                        + "le continue; une nouvelle intention indépendante doit "
                        + "ignorer les filtres antérieurs) :\n{}"
        ));
        assertTrue(independentFollowUpContext.contains("A-7"));
        assertTrue(independentFollowUpContext.contains("comptabilité"));
    }

    @Test
    void replacingSexKeepsTheActiveServiceFilter() throws Exception {
        UUID conversationId = createConversation("42");
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenReturn(
                new ChatResponse(
                        "Femmes du Service du Personnel.",
                        List.of(new ChatResponse.ToolResult(
                                "searchEmployees",
                                List.of(),
                                Map.of("criteriaJson",
                                        "{\"filters\":[{\"field\":\"service\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"Service du Personnel\"},"
                                                + "{\"field\":\"sexe\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"F\"}]}")
                        ))
                ),
                new ChatResponse(
                        "Hommes du Service du Personnel.",
                        List.of(new ChatResponse.ToolResult(
                                "searchEmployees",
                                List.of(Map.of("matricule", "M-1")),
                                Map.of("criteriaJson",
                                        "{\"filters\":[{\"field\":\"service\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"Service du Personnel\"},"
                                                + "{\"field\":\"sexe\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"M\"}]}")
                        ))
                ),
                new ChatResponse("Le filtre de service est conservé.", List.of())
        );

        send(conversationId, "42", "Cherche les femmes du Service du Personnel.");
        send(conversationId, "42", "Et les hommes ?");
        send(conversationId, "42", "Affiche les agents trouvés.");

        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(assistant, times(3)).respond(
                anyString(),
                anyString(),
                context.capture(),
                any(UUID.class),
                nullable(JsonNode.class)
        );
        String finalContext = context.getAllValues().get(2);
        String stateBlock = finalContext.substring(
                finalContext.indexOf(") :\n") + 4,
                finalContext.indexOf("\nRéférences aux résultats")
        );
        var structuredState = objectMapper.readTree(stateBlock);
        var filters = structuredState.path("criteria").path("filters");
        assertEquals(2, filters.size());
        assertEquals("Service du Personnel", filters.get(0).path("value").asText());
        assertEquals("M", filters.get(1).path("value").asText());
    }

    @Test
    void reconstructsMissingMemoryFromSavedToolMessages() throws Exception {
        UUID conversationId = createConversation("42");
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenReturn(
                new ChatResponse(
                        "Agent trouvé.",
                        List.of(new ChatResponse.ToolResult(
                                "searchEmployees",
                                List.of(Map.of("matricule", "R-9")),
                                Map.of("criteriaJson",
                                        "{\"filters\":[{\"field\":\"service\","
                                                + "\"operator\":\"EQ\","
                                                + "\"value\":\"Personnel\"}]}")
                        ))
                ),
                new ChatResponse("Le service est bien le Personnel.", List.of())
        );

        send(conversationId, "42", "Cherche au Personnel.");
        jdbcTemplate.update(
                "DELETE FROM bandy_conversation_memory WHERE conversation_id = ?",
                conversationId
        );
        send(conversationId, "42", "Et les matricules ?");

        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(assistant, times(2)).respond(
                anyString(),
                anyString(),
                context.capture(),
                any(UUID.class),
                nullable(JsonNode.class)
        );
        String rebuiltContext = context.getAllValues().get(1);
        assertTrue(rebuiltContext.contains("Personnel"));
        assertTrue(rebuiltContext.contains("R-9"));
    }

    @Test
    void retriesFailedProviderRequestWithoutDuplicatingUserMessage()
            throws Exception {
        UUID conversationId = createConversation("42");
        UUID requestId = UUID.randomUUID();
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenThrow(new IllegalStateException("provider unavailable"))
                .thenReturn(new ChatResponse("Réponse disponible.", List.of()));

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken("42"))
                        .header("Idempotency-Key", requestId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().is5xxServerError());

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken("42"))
                        .header("Idempotency-Key", requestId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Réponse disponible."));

        Integer userMessages = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bandy_conversation_message
                WHERE conversation_id = ? AND role = 'USER'
                """,
                Integer.class,
                conversationId
        );
        assertEquals(1, userMessages);
    }

    @Test
    void returnsBackendToolFailuresWithoutSavingASuccessfulAssistantReply()
            throws Exception {
        UUID conversationId = createConversation("42");
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "Backend indisponible."
        ));

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken("42"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Recherche RH\"}"))
                .andExpect(status().isBadGateway());

        Integer savedMessages = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM bandy_conversation_message
                WHERE conversation_id = ?
                """,
                Integer.class,
                conversationId
        );
        assertEquals(1, savedMessages);
    }

    @Test
    void rejectsConcurrentRequestsForTheSameConversation() throws Exception {
        UUID conversationId = createConversation("42");
        CountDownLatch assistantStarted = new CountDownLatch(1);
        CountDownLatch releaseAssistant = new CountDownLatch(1);
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenAnswer(invocation -> {
            assistantStarted.countDown();
            if (!releaseAssistant.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test.");
            }
            return new ChatResponse("Première réponse.", List.of());
        });

        CompletableFuture<MvcResult> firstRequest = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post(
                                "/api/conversations/{id}/messages",
                                conversationId
                        )
                                .header("Authorization", bearerToken("42"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"Première demande\"}"))
                        .andReturn();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });

        try {
            assertTrue(assistantStarted.await(5, TimeUnit.SECONDS));
            mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                            .header("Authorization", bearerToken("42"))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"Demande simultanée\"}"))
                    .andExpect(status().isConflict());
        } finally {
            releaseAssistant.countDown();
        }

        assertEquals(200, firstRequest.get(5, TimeUnit.SECONDS)
                .getResponse()
                .getStatus());
    }

    @Test
    void buildsAnIncrementalSummaryAndRejectsInvalidPaging() throws Exception {
        UUID conversationId = createConversation("42");
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenAnswer(invocation -> new ChatResponse(
                "Réponse " + invocation.getArgument(0),
                List.of()
        ));

        for (int index = 0; index < 5; index++) {
            String message = index == 0
                    ? "Ancien sujet 0 contact admin@example.org, matricule "
                            + "RH1234567, téléphone 0341234567"
                    : "Ancien sujet " + index;
            send(conversationId, "42", message);
        }
        send(conversationId, "42", "Dernière demande de suite");

        var context = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(assistant, times(6)).respond(
                anyString(),
                anyString(),
                context.capture(),
                any(UUID.class),
                nullable(JsonNode.class)
        );
        assertTrue(context.getAllValues().get(5)
                .contains("Résumé extractif des échanges plus anciens"));
        assertTrue(context.getAllValues().get(5).contains("Ancien sujet 0"));
        assertTrue(context.getAllValues().get(5).contains("[email]"));
        assertTrue(context.getAllValues().get(5).contains("[identifiant]"));
        assertTrue(context.getAllValues().get(5).contains("[numero]"));
        assertTrue(!context.getAllValues().get(5).contains("admin@example.org"));
        assertTrue(!context.getAllValues().get(5).contains("RH1234567"));
        assertTrue(!context.getAllValues().get(5).contains("0341234567"));

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", bearerToken("42"))
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renamesArchivesAndRejectsFurtherMessages() throws Exception {
        UUID conversationId = createConversation("42");
        mockMvc.perform(patch("/api/conversations/{id}", conversationId)
                        .header("Authorization", bearerToken("42"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Recherche agents\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/conversations/{id}/archive", conversationId)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken("42"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void legacyChatCreatesAndReturnsAConversationForExistingClients()
            throws Exception {
        when(assistant.respond(
                anyString(),
                anyString(),
                anyString(),
                any(UUID.class),
                nullable(JsonNode.class)
        )).thenReturn(new ChatResponse("Bonjour.", List.of()));

        MvcResult chatResult = mockMvc.perform(post("/api/chat")
                        .header("Authorization", bearerToken("42"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Bonjour."))
                .andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andReturn();
        UUID conversationId = UUID.fromString(
                objectMapper.readTree(chatResult.getResponse().getContentAsString())
                        .get("conversationId")
                        .asText()
        );
        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void rejectsBlankMessagesAndUnknownConversationIds() throws Exception {
        mockMvc.perform(post("/api/conversations/{id}/messages", UUID.randomUUID())
                        .header("Authorization", bearerToken("42"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/conversations/{id}", UUID.randomUUID())
                        .header("Authorization", bearerToken("42")))
                .andExpect(status().isNotFound());
    }

    private UUID createConversation(String subject) throws Exception {
        return createConversation(subject, null);
    }

    private UUID createConversation(String subject, String audience)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", bearerToken(subject, audience)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id")
                .traverse(objectMapper)
                .readValueAs(UUID.class);
    }

    private void send(UUID conversationId, String subject, String message)
            throws Exception {
        mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearerToken(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("message", message)
                        )))
                .andExpect(status().isOk());
    }

    private String bearerToken(String subject) {
        return bearerToken(subject, null);
    }

    private String bearerToken(String subject, String audience) {
        try {
            String header = encode(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                            .getBytes(StandardCharsets.UTF_8)
            );
            Map<String, Object> values = new java.util.HashMap<>();
            values.put("sub", subject);
            values.put("iat", 1);
            values.put("exp", Instant.now().getEpochSecond() + 3600);
            if (audience != null) {
                values.put("aud", audience);
            }
            String claims = encode(objectMapper.writeValueAsBytes(values));
            String signingInput = header + "." + claims;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    SECRET.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            return "Bearer " + signingInput + "."
                    + encode(mac.doFinal(
                            signingInput.getBytes(StandardCharsets.US_ASCII)
                    ));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
