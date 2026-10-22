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
                    new PerformanceTelemetry()
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
