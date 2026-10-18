package com.bandyakama.executor;

import com.bandyakama.tool.ToolDefinition;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ToolExecutor {
    private final RestClient restClient;

    public ToolExecutor(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
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
        validateArguments(tool, arguments);

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

        var request = restClient.method(
                HttpMethod.valueOf(
                        tool.getMethod().toUpperCase()
                )
        ).uri(uriBuilder.buildAndExpand(pathArguments).encode().toUri());

        if (authorization != null && !authorization.isBlank()) {
            request.header("Authorization", authorization);
        }
        if (!bodyArguments.isEmpty()) {
            request.body(bodyArguments);
        }
        return request.retrieve().body(Object.class);
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