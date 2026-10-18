package com.bandyakama.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ToolRegistrationGuard {
    private final String registrationKey;

    public ToolRegistrationGuard(
            @Value("${bandy.tools.registration-key:}") String registrationKey
    ) {
        this.registrationKey = registrationKey;
    }

    public void requireAuthorized(String suppliedKey) {
        if (registrationKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Configurez BANDY_REGISTRATION_KEY pour accéder aux fonctions de Bandy Akama."
            );
        }
        if (suppliedKey == null || !MessageDigest.isEqual(
                registrationKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
