package com.bandyakama.executor;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/agents", exchange -> {
            receivedRequestId.set(
                    exchange.getRequestHeaders().getFirst("X-Request-ID")
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
            executor.execute(
                    tool("http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/api/agents"),
                    Map.of("query", "agent"),
                    null,
                    requestId
            );
            assertEquals(requestId, receivedRequestId.get());
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
