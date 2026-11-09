package com.bandyakama.executor;

import com.bandyakama.chat.ChatResponse;
import com.bandyakama.tool.ToolDefinition;
import com.bandyakama.tool.ToolDestinationPolicy;
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
    private final ToolDestinationPolicy destinationPolicy;

    @Autowired
    public ToolExecutor(
            RestClient.Builder restClientBuilder,
            @Value("${bandy.tools.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${bandy.tools.read-timeout-seconds:15}") int readTimeoutSeconds,
            PerformanceTelemetry performanceTelemetry,
            ToolDestinationPolicy destinationPolicy
    ) {
        this.destinationPolicy = destinationPolicy;
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

    public ToolExecutor(
            RestClient.Builder restClientBuilder,
            ToolDestinationPolicy destinationPolicy
    ) {
        this(restClientBuilder, 5, 30, new PerformanceTelemetry(), destinationPolicy);
    }

    /** authorization et idempotencyKey sont facultatifs (null). */
    public Object execute(
            ToolDefinition tool,
            Map<String, Object> arguments,
            String authorization,
            String idempotencyKey
    ) {
        return send(tool, arguments, authorization, idempotencyKey, null);
    }

    /**
     * Aperçu d'une tâche confirmée : la même requête, avec le paramètre
     * d'aperçu du catalogue et sans clé d'idempotence. L'API vérifie et
     * résume ; elle n'exécute rien.
     */
    public Object preview(ToolDefinition tool, Map<String, Object> arguments, String authorization) {
        if (tool.getPreviewQuery() == null) {
            throw new IllegalStateException("L'outil " + tool.getName() + " ne déclare pas d'aperçu.");
        }
        return send(tool, arguments, authorization, null, tool.getPreviewQuery());
    }

    private Object send(
            ToolDefinition tool,
            Map<String, Object> arguments,
            String authorization,
            String idempotencyKey,
            String previewQuery
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
                Object value = arguments.get(parameter.getName());
                String bodyObject = parameter.bodyObject();
                if (bodyObject != null) {
                    // « body.donnees » : l'argument rejoint l'objet donnees du corps.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) bodyArguments
                            .computeIfAbsent(bodyObject, key -> new java.util.LinkedHashMap<String, Object>());
                    nested.put(parameter.getName(), value);
                    continue;
                }
                switch (location.toLowerCase()) {
                    case "path" -> pathArguments.put(parameter.getName(), value);
                    case "query" -> queryArguments.put(parameter.getName(), value);
                    case "body" -> bodyArguments.put(parameter.getName(), value);
                    default -> throw new IllegalArgumentException(
                            "Emplacement de paramètre non pris en charge : " + location
                    );
                }
            }
            queryArguments.forEach(uriBuilder::queryParam);
            if (previewQuery != null) {
                String[] parts = previewQuery.split("=", 2);
                uriBuilder.queryParam(parts[0], parts[1]);
            }

            java.net.URI target = uriBuilder.buildAndExpand(pathArguments).encode().toUri();
            // Barrière avant tout envoi : le jeton de l'utilisateur ne part
            // jamais vers une origine absente de bandy.tools.allowed-origins.
            if (!destinationPolicy.allows(target.toString())) {
                throw new IllegalStateException(
                        "Destination d’outil non autorisée pour " + tool.getName()
                                + " : vérifiez bandy.tools.allowed-origins."
                );
            }
            var request = requestClient.method(
                    HttpMethod.valueOf(
                            tool.getMethod().toUpperCase()
                    )
            ).uri(target);

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

    /**
     * Construit l'action d'interface d'une navigation ou d'une tâche préparée,
     * sans aucune requête. Les paramètres path et query forment la route,
     * encodée strictement : un argument ne peut ni ajouter de segment (« / »)
     * ni sortir de la route du catalogue. Les paramètres body sont les champs
     * du formulaire à préremplir.
     */
    public ChatResponse.Action interfaceAction(
            ToolDefinition tool,
            Map<String, Object> arguments
    ) {
        validateArguments(tool, arguments);
        UriComponentsBuilder route = UriComponentsBuilder.fromPath(tool.getUrl());
        Map<String, Object> variables = new HashMap<>();
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (var parameter : tool.getParameters()) {
            Object value = arguments.get(parameter.getName());
            if (value == null) {
                continue;
            }
            Object normalized = value instanceof Number number && isWholeNumber(number)
                    ? number.longValue()
                    : value;
            // Pour un formulaire, tout argument du corps est un champ, même imbriqué.
            String location = parameter.inBody()
                    ? "body"
                    : parameter.getLocation() == null ? "query" : parameter.getLocation().toLowerCase();
            switch (location) {
                case "body" -> fields.put(parameter.getName(), normalized);
                case "path" -> variables.put(parameter.getName(), normalized);
                default -> {
                    variables.put(parameter.getName(), normalized);
                    route.queryParam(parameter.getName(), "{" + parameter.getName() + "}");
                }
            }
        }
        return new ChatResponse.Action(
                tool.getActionLevel().name(),
                tool.getName(),
                route.encode().buildAndExpand(variables).toUriString(),
                fields
        );
    }

    /**
     * Prépare une tâche confirmée sans l'exécuter : les arguments sont
     * vérifiés maintenant, l'appel n'aura lieu qu'après la confirmation.
     */
    public ChatResponse.Action confirmationRequest(
            ToolDefinition tool,
            Map<String, Object> arguments
    ) {
        validateArguments(tool, arguments);
        return new ChatResponse.Action("CONFIRM", tool.getName(), null, arguments);
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