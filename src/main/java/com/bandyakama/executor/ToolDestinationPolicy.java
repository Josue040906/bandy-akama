package com.bandyakama.executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Restricts configured tool URLs to explicitly approved HTTP origins.
 * The allowlist is fail-closed: an empty production configuration denies calls.
 */
@Component
public final class ToolDestinationPolicy {
    private final Set<String> allowedOrigins;

    public ToolDestinationPolicy(
            @Value("${bandy.tools.allowed-destinations:}") String configuredOrigins
    ) {
        this.allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(ToolDestinationPolicy::normalizeOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String configuredUrl) {
        URI uri;
        try {
            uri = URI.create(configuredUrl.replaceAll(
                    "\\{[A-Za-z][A-Za-z0-9_]{0,63}}", "path-value"
            ));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Destination d’outil invalide.");
        }
        if (uri.getScheme() == null
                || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Destination HTTP(S) non autorisée.");
        }
        String origin = normalizeOrigin(
                uri.getScheme() + "://" + uri.getRawAuthority()
        );
        if (!allowedOrigins.contains(origin)) {
            throw new IllegalArgumentException(
                    "La destination de cet outil n’est pas dans la liste des destinations autorisées."
            );
        }
    }

    private static String normalizeOrigin(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Destination autorisée mal configurée.");
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawPath() != null
                && !uri.getRawPath().isEmpty() || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Chaque destination autorisée doit contenir uniquement schéma, hôte et port."
            );
        }
        int port = uri.getPort();
        String scheme = uri.getScheme().toLowerCase();
        String host = uri.getHost().toLowerCase();
        return scheme + "://" + host + (port == -1 ? "" : ":" + port);
    }
}
