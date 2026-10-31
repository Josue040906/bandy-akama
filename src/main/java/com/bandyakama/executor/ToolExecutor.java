package com.bandyakama.executor;

import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.observability.PerformanceTelemetry;
import com.bandyakama.observability.RequestBudget;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ToolExecutor {
    private final RestClient restClient;
    private final RestClient.Builder restClientBuilder;
    private final HttpClient httpClient;
    private final PerformanceTelemetry performanceTelemetry;
    private final int readTimeoutMillis;

    @Autowired
    public ToolExecutor(
            RestClient.Builder restClientBuilder,
            @Value("${bandy.tools.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${bandy.tools.read-timeout-seconds:15}") int readTimeoutSeconds,
            PerformanceTelemetry performanceTelemetry
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(this.httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClientBuilder = restClientBuilder;
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
        this.performanceTelemetry = performanceTelemetry;
        this.readTimeoutMillis = Math.toIntExact(
                Duration.ofSeconds(readTimeoutSeconds).toMillis()
        );
    }

    public ToolExecutor(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, 5, 30, new PerformanceTelemetry());
    }

    public Object execute(
            ToolDefinition tool,
            Map<String, Object> arguments
    ) {
        return execute(tool, arguments, null);
    }

    public Object execute(
            ToolDefinition tool,
            Map<String, Object> arguments,
            String authorization
    ) {
        return execute(tool, arguments, authorization, null);
    }

    public Object execute(
            ToolDefinition tool,
            Map<String, Object> arguments,
            String authorization,
            String idempotencyKey
    ) {
        long startedAt = System.nanoTime();
        boolean succeeded = false;
        Integer httpStatus = null;
        try {
            validateArguments(tool, arguments);
            RestClient requestClient = requestClientWithinBudget();

            var uriBuilder = UriComponentsBuilder.fromUriString(tool.getUrl());
            Map<String, Object> pathArguments = new HashMap<>();
            Map<String, Object> queryArguments = new HashMap<>();
            Map<String, Object> bodyArguments = new HashMap<>();
            for (var parameter : tool.getParameters()) {
                if (!arguments.containsKey(parameter.getName())) {
                    continue;
                }
                String location = parameter.getLocation();
                if (location == null || location.isBlank()) {
                    location = "GET".equalsIgnoreCase(tool.getMethod())
                            ? "query"
                            : "body";
                }
                switch (location.toLowerCase()) {
                    case "path" -> pathArguments.put(
                            parameter.getName(),
                            arguments.get(parameter.getName())
                    );
                    case "query" -> queryArguments.put(
                            parameter.getName(),
                            arguments.get(parameter.getName())
                    );
                    case "body" -> bodyArguments.put(
                            parameter.getName(),
                            arguments.get(parameter.getName())
                    );
                    default -> throw new IllegalArgumentException(
                            "Emplacement de paramètre non pris en charge : " + location
                    );
                }
            }
            queryArguments.forEach(uriBuilder::queryParam);

            var request = requestClient.method(
                    HttpMethod.valueOf(
                            tool.getMethod().toUpperCase()
                    )
            ).uri(uriBuilder.buildAndExpand(pathArguments).encode().toUri());

            if (authorization != null && !authorization.isBlank()) {
                request.header("Authorization", authorization);
            }
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                request.header("Idempotency-Key", idempotencyKey);
                request.header("X-Request-ID", idempotencyKey);
            }
            if (!bodyArguments.isEmpty()) {
                request.body(bodyArguments);
            }
            Object result = request.retrieve().body(Object.class);
            succeeded = true;
            return result;
        } catch (RestClientResponseException exception) {
            httpStatus = exception.getStatusCode().value();
            throw exception;
        } finally {
            performanceTelemetry.toolCall(
                    idempotencyKey,
                    tool == null ? "unknown" : tool.getName(),
                    startedAt,
                    httpStatus,
                    succeeded
            );
        }
    }

    private RestClient requestClientWithinBudget() {
        Optional<RequestBudget> activeBudget = RequestBudget.current();
        if (activeBudget.isEmpty()) {
            return restClient;
        }
        int timeoutMillis = Math.min(
                readTimeoutMillis,
                activeBudget.get().remainingMillis()
        );
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return restClientBuilder.clone()
                .requestFactory(requestFactory)
                .build();
    }

    private void validateArguments(
            ToolDefinition tool,
            Map<String, Object> arguments
    ) {
        if (arguments == null) {
            throw new IllegalArgumentException("Les arguments de l’outil sont requis.");
        }

        Set<String> declaredNames = new HashSet<>();
        tool.getParameters().forEach(parameter -> {
            declaredNames.add(parameter.getName());
            Object value = arguments.get(parameter.getName());
            if (parameter.isRequired() && value == null) {
                throw new IllegalArgumentException(
                        "Paramètre obligatoire manquant : " + parameter.getName()
                );
            }
            if (parameter.isRequired()
                    && value instanceof String text
                    && text.isBlank()) {
                throw new IllegalArgumentException(
                        "Le paramètre obligatoire ne peut pas être vide : "
                                + parameter.getName()
                );
            }
            if (value != null && !matchesType(parameter.getType(), value)) {
                throw new IllegalArgumentException(
                        "Type invalide pour le paramètre : " + parameter.getName()
                );
            }
            if (value != null && parameter.getAllowedValues() != null
                    && !parameter.getAllowedValues().contains(value)) {
                throw new IllegalArgumentException(
                        "Valeur non autorisée pour le paramètre : " + parameter.getName()
                );
            }
        });
        if (!declaredNames.containsAll(arguments.keySet())) {
            throw new IllegalArgumentException("Un argument non déclaré a été fourni.");
        }
    }

    private boolean matchesType(String type, Object value) {
        return switch (type.toLowerCase()) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long
                    || (value instanceof Number number
                    && isWholeNumber(number));
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> false;
        };
    }

    private boolean isWholeNumber(Number number) {
        try {
            return new java.math.BigDecimal(number.toString())
                    .stripTrailingZeros()
                    .scale() <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}