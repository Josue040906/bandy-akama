package com.bandyakama.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Origines (schéma, hôte, port) vers lesquelles un outil peut envoyer une
 * requête. Bandy y transmet le jeton de l'utilisateur : toute autre
 * destination est refusée, à l'enregistrement comme à l'exécution.
 */
@Component
public class ToolDestinationPolicy {
    private final Set<String> allowedOrigins;

    public ToolDestinationPolicy(
            @Value("${bandy.tools.allowed-origins:}") List<String> allowedOrigins
    ) {
        this.allowedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(ToolDestinationPolicy::configuredOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Accepte une URL finale ou un modèle d'URL contenant des {paramètres}. */
    public boolean allows(String url) {
        String origin = origin(url);
        return origin != null && allowedOrigins.contains(origin);
    }

    private static String configuredOrigin(String entry) {
        String origin = origin(entry);
        String path = origin == null ? null : URI.create(entry).getRawPath();
        if (origin == null || (path != null && !path.isEmpty() && !path.equals("/"))) {
            throw new IllegalStateException(
                    "bandy.tools.allowed-origins n’accepte que des origines "
                            + "(schéma://hôte[:port]) : " + entry
            );
        }
        return origin;
    }

    private static String origin(String url) {
        try {
            URI uri = URI.create(url.replaceAll("\\{[^}]+}", "x"));
            String scheme = uri.getScheme() == null
                    ? null
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null) {
                return null;
            }
            int port = uri.getPort() != -1
                    ? uri.getPort()
                    : "https".equals(scheme) ? 443 : 80;
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException invalidUrl) {
            return null;
        }
    }
}
