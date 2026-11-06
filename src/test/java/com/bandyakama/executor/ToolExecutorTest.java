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
                () -> executor.execute(tool(), Map.of())
        );
    }

    @Test
    void rejectsUndeclaredArgumentsBeforeMakingTheRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of(
                        "query", "agent",
                        "unexpected", "value"
                ))
        );
    }

    @Test
    void rejectsArgumentsWithTheWrongTypeBeforeMakingTheRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of("query", 42))
        );
    }

    @Test
    void rejectsBlankValuesForRequiredStringArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(tool(), Map.of("query", "  "))
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
                    )
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
                            Map.of("query", "agent")
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
                                Map.of("query", "agent")
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
