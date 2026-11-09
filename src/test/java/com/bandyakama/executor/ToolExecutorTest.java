package com.bandyakama.executor;

import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.observability.RequestBudget;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolDestinationPolicy;
import com.bandyakama.tool.ToolParameter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {
    private final ToolExecutor executor =
            new ToolExecutor(RestClient.builder(), new ToolDestinationPolicy(List.of("https://example.test")));

    @Test
    void rejectsMissingRequiredArgumentsBeforeMakingTheRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of(), null, null)
        );
    }

    @Test
    void rejectsUndeclaredArgumentsBeforeMakingTheRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of(
                        "query", "agent",
                        "unexpected", "value"
                ), null, null)
        );
    }

    @Test
    void rejectsArgumentsWithTheWrongTypeBeforeMakingTheRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of("query", 42), null, null)
        );
    }

    @Test
    void rejectsBlankValuesForRequiredStringArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of("query", "  "), null, null)
        );
    }

    @Test
    void forwardsCorrelationIdToRegisteredTool() throws IOException {
        AtomicReference<String> receivedRequestId = new AtomicReference<>();
        AtomicReference<String> receivedAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/agents", exchange -> {
            receivedRequestId.set(
                    exchange.getRequestHeaders().getFirst("X-Request-ID")
            );
            receivedAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization")
            );
            byte[] responseBody = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try {
            String requestId = "60dfc6d3-04f9-4cc4-9d77-4279e0b4f06e";
            String authorization = "Bearer " + "fixture";
            executorFor(server).execute(
                    tool("http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/api/agents"),
                    Map.of("query", "agent"),
                    authorization,
                    requestId
            );
            assertEquals(requestId, receivedRequestId.get());
            assertEquals(authorization, receivedAuthorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwardsPostBodyAsJsonWithoutMovingCriteriaIntoTheUrl()
            throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/search", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            body.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] responseBody = "{\"count\":4}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try {
            ToolParameter criteria = new ToolParameter(
                    "criteriaJson",
                    "string",
                    "Structured search criteria",
                    true
            );
            criteria.setLocation("body");
            ToolParameter mode = new ToolParameter(
                    "resultMode",
                    "string",
                    "List or count",
                    false
            );
            mode.setLocation("body");
            ToolDefinition postTool = new ToolDefinition(
                    "search_agents",
                    "Search agents",
                    "POST",
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/api/search",
                    List.of(criteria, mode)
            );
            postTool.setWhenToUse("Use when searching agents.");

            Object result = executorFor(server).execute(
                    postTool,
                    Map.of(
                            "criteriaJson",
                            "{\"filters\":[{\"field\":\"age\",\"operator\":\"GT\",\"value\":40}]}",
                            "resultMode",
                            "COUNT"
                    ),
                    null,
                    null
            );

            assertEquals("POST", method.get());
            assertEquals("/api/search", path.get());
            assertTrue(body.get().contains("\"resultMode\":\"COUNT\""));
            assertTrue(body.get().contains("\"criteriaJson\":"));
            assertEquals(Map.of("count", 4), result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void placesABodyDotObjectArgumentInsideThatObjectOfTheBody() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/requests", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] responseBody = "{\"id\":7}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try {
            ToolParameter type = new ToolParameter("typeId", "integer", "Type", true);
            type.setLocation("body");
            ToolParameter reason = new ToolParameter("motif", "string", "Reason", false);
            reason.setLocation("body.donnees");
            ToolParameter start = new ToolParameter("dateDebut", "string", "Start", false);
            start.setLocation("body.donnees");
            ToolDefinition tool = new ToolDefinition("create_request", "Create a request", "POST",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/requests",
                    List.of(type, reason, start));
            tool.setWhenToUse("Use when creating a request.");
            tool.validate();

            executorFor(server).execute(tool,
                    Map.of("typeId", 3, "motif", "Congé annuel", "dateDebut", "2026-12-01"), null, null);

            assertTrue(body.get().contains("\"typeId\":3"));
            assertTrue(body.get().contains("\"donnees\":{"));
            assertTrue(body.get().contains("\"motif\":\"Congé annuel\""));
            assertTrue(!body.get().startsWith("{\"motif\""));
            assertEquals(Map.of("typeId", 3L), executorFor(server).interfaceAction(tool, Map.of("typeId", 3)).fields());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void previewsAConfirmedTaskWithTheCatalogueQueryAndNoIdempotencyKey() throws IOException {
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/requests", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] responseBody = "{\"apercu\":true,\"resume\":{\"Agent\":\"RAKOTO Jean\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try {
            ToolParameter reason = new ToolParameter("motif", "string", "Reason", true);
            reason.setLocation("body");
            ToolDefinition tool = new ToolDefinition("create_request", "Create a request", "POST",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/requests", List.of(reason));
            tool.setWhenToUse("Use when creating a request.");
            tool.setActionLevel(com.bandyakama.tool.ToolActionLevel.CONFIRM);
            tool.setPreviewQuery("apercu=true");
            tool.validate();

            Object result = executorFor(server).preview(tool, Map.of("motif", "Congé"), "Bearer user-token");

            assertEquals("apercu=true", query.get());
            assertEquals(null, idempotency.get());
            assertEquals("Bearer user-token", authorization.get());
            assertEquals(Map.of("apercu", true, "resume", Map.of("Agent", "RAKOTO Jean")), result);
        } finally {
            server.stop(0);
        }

        ToolDefinition reading = new ToolDefinition("read", "Read", "GET", "https://example.test/api", List.of());
        reading.setWhenToUse("Use when reading.");
        reading.setPreviewQuery("apercu=true");
        assertThrows(IllegalArgumentException.class, reading::validate);
    }

    @Test
    void doesNotStartToolHttpRequestAfterTurnBudgetExpires() throws IOException {
        AtomicBoolean receivedRequest = new AtomicBoolean();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/agents", exchange -> {
            receivedRequest.set(true);
            byte[] responseBody = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();

        try (RequestBudget.Scope ignored = RequestBudget.open(1)) {
            Thread.sleep(5);
            assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    () -> executorFor(server).execute(
                            tool("http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/api/agents"),
                            Map.of("query", "agent"),
                            null,
                            null
                    )
            );
            assertFalse(receivedRequest.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Le test a été interrompu.", exception);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopsWaitingForASlowToolAtTheRemainingTurnBudget() throws IOException {
        AtomicBoolean receivedRequest = new AtomicBoolean();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/agents", exchange -> {
            receivedRequest.set(true);
            try {
                Thread.sleep(2_000);
                byte[] responseBody = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, responseBody.length);
                exchange.getResponseBody().write(responseBody);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            ToolExecutor budgetedExecutor = new ToolExecutor(
                    RestClient.builder(),
                    5,
                    10,
                    new PerformanceTelemetry(),
                    allowing(server)
            );
            long startedAt = System.nanoTime();
            try (RequestBudget.Scope ignored = RequestBudget.open(700)) {
                assertThrows(
                        org.springframework.web.client.RestClientException.class,
                        () -> budgetedExecutor.execute(
                                tool("http://127.0.0.1:"
                                        + server.getAddress().getPort()
                                        + "/api/agents"),
                                Map.of("query", "agent"),
                                null,
                                null
                        )
                );
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt
            );
            assertTrue(receivedRequest.get());
            assertTrue(elapsedMillis < 1_500, "elapsed=" + elapsedMillis);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void neverSendsTheUserTokenToAnOriginOutsideTheAllowList() throws IOException {
        AtomicBoolean receivedRequest = new AtomicBoolean();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/agents", exchange -> {
            receivedRequest.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            // L'exécuteur n'autorise que https://example.test : ce serveur
            // local, pourtant joignable, ne doit recevoir aucune requête.
            assertThrows(
                    IllegalStateException.class,
                    () -> executor.execute(
                            tool("http://127.0.0.1:"
                                    + server.getAddress().getPort()
                                    + "/api/agents"),
                            Map.of("query", "agent"),
                            "Bearer " + "fixture",
                            null
                    )
            );
            assertFalse(receivedRequest.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void buildsNavigationRoutesWithoutAnyRequestAndKeepsArgumentsInsideTheRoute() {
        ToolParameter agentId = new ToolParameter("agentId", "integer", "Agent.", true);
        agentId.setLocation("path");
        ToolParameter tab = new ToolParameter("onglet", "string", "Onglet.", false);
        tab.setLocation("query");
        ToolDefinition navigation = new ToolDefinition(
                "open_agent_record", "Opens a record.", "GET",
                "/agents/{agentId}", List.of(agentId, tab)
        );

        // Gemini envoie souvent les entiers sous forme décimale (101.0).
        assertEquals("/agents/101",
                executor.interfaceAction(navigation, Map.of("agentId", 101.0)).route());
        assertEquals("/agents/7?onglet=carri%C3%A8re",
                executor.interfaceAction(navigation, Map.of("agentId", 7, "onglet", "carrière")).route());

        ToolParameter page = new ToolParameter("page", "string", "Page.", true);
        page.setLocation("path");
        ToolDefinition anyPage = new ToolDefinition(
                "open_page", "Opens a page.", "GET", "/{page}", List.of(page)
        );
        // Un argument ne peut pas ajouter de segment ni remonter l'arborescence.
        assertEquals("/..%2Fadministration",
                executor.interfaceAction(anyPage, Map.of("page", "../administration")).route());
        assertThrows(IllegalArgumentException.class,
                () -> executor.interfaceAction(navigation, Map.of("agentId", "abc")).route());

        // Des critères JSON en paramètre restent un seul paramètre de la route, entièrement encodé.
        ToolParameter search = new ToolParameter("recherche", "string", "Criteria.", false);
        search.setLocation("query");
        ToolDefinition agentsPage = new ToolDefinition(
                "open_page", "Opens a page.", "GET", "/{page}", List.of(page, search)
        );
        String route = executor.interfaceAction(agentsPage, Map.of("page", "agents",
                "recherche", "{\"must\":[{\"field\":\"service\",\"operator\":\"CONTAINS\",\"value\":\"du personnel\"}]}"
        )).route();
        assertTrue(route.startsWith("/agents?recherche=%7B%22must%22"), route);
        assertTrue(!route.contains(" ") && !route.contains("{") && !route.contains("&"), route);
    }

    @Test
    void preparesAFormWithOnlyTheProposedFieldsAndNoRequest() {
        ToolParameter agentId = new ToolParameter("agentId", "integer", "Agent.", true);
        agentId.setLocation("path");
        ToolParameter telephone = new ToolParameter("telephone", "string", "Téléphone.", false);
        telephone.setLocation("body");
        ToolParameter nom = new ToolParameter("nom", "string", "Nom.", false);
        nom.setLocation("body");
        ToolDefinition prepare = new ToolDefinition(
                "prepare_agent_update", "Prepares an update.", "GET",
                "/agents/{agentId}", List.of(agentId, telephone, nom)
        );
        prepare.setActionLevel(com.bandyakama.tool.ToolActionLevel.PREPARE);

        var action = executor.interfaceAction(
                prepare, Map.of("agentId", 101.0, "telephone", "034 12 345 67")
        );

        assertEquals("PREPARE", action.type());
        assertEquals("/agents/101", action.route());
        // Seul le champ proposé est transmis : le nom, non demandé, est absent.
        assertEquals(Map.of("telephone", "034 12 345 67"), action.fields());
    }

    @Test
    void preparesAConfirmationWithoutExecutingAndChecksItsArgumentsNow() {
        ToolDefinition confirmed = tool("https://example.test/api/conges");
        confirmed.setActionLevel(com.bandyakama.tool.ToolActionLevel.CONFIRM);

        var action = executor.confirmationRequest(confirmed, Map.of("query", "congé"));

        assertEquals("CONFIRM", action.type());
        assertEquals(Map.of("query", "congé"), action.fields());
        assertEquals(null, action.confirmationId());
        assertThrows(IllegalArgumentException.class,
                () -> executor.confirmationRequest(confirmed, Map.of()));
    }

    private static ToolDestinationPolicy allowing(HttpServer server) {
        return new ToolDestinationPolicy(List.of(
                "http://127.0.0.1:" + server.getAddress().getPort()
        ));
    }

    private static ToolExecutor executorFor(HttpServer server) {
        return new ToolExecutor(RestClient.builder(), allowing(server));
    }

    private ToolDefinition tool() {
        return tool("https://example.test/api/agents");
    }

    private ToolDefinition tool(String url) {
        ToolDefinition tool = new ToolDefinition(
                "searchAgents",
                "Searches for agents.",
                "GET",
                url,
                List.of(new ToolParameter(
                        "query",
                        "string",
                        "Text to search for.",
                        true
                ))
        );
        tool.setWhenToUse("Use when looking for agents.");
        return tool;
    }
}
