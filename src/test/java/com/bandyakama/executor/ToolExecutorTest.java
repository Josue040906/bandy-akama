package com.bandyakama.executor;

import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.observability.RequestBudget;
import com.bandyakama.tool.ToolDefinition;
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
            new ToolExecutor(RestClient.builder());

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
            executor.execute(
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

            Object result = executor.execute(
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
    void callsTheActualProfileEndpointWithTheQueryParameterAndBearerToken()
            throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/profil/agent", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization")
            );
            byte[] responseBody = "{\"matricule\":\"AG-71\"}"
                    .getBytes(StandardCharsets.UTF_8);
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
            ToolParameter queryParameter = new ToolParameter(
                    "query",
                    "string",
                    "Matricule or exact full name",
                    true
            );
            queryParameter.setLocation("query");
            ToolDefinition profileTool = new ToolDefinition(
                    "get_agent_profile",
                    "Load an identified agent profile",
                    "GET",
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/api/profil/agent",
                    List.of(queryParameter)
            );
            profileTool.setWhenToUse("After identifying a candidate.");

            Object result = executor.execute(
                    profileTool,
                    Map.of("query", "AG-71"),
                    "Auth-Test-Value"
            );

            assertEquals("GET", method.get());
            assertEquals("/api/profil/agent", path.get());
            assertEquals("query=AG-71", query.get());
            assertEquals("Auth-Test-Value", authorization.get());
            assertEquals(Map.of("matricule", "AG-71"), result);
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
                    () -> executor.execute(
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
                    new ToolDestinationPolicy("", true)
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
