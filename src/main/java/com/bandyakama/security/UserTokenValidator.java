package com.bandyakama.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Component
public class UserTokenValidator {
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final String secret;

    public UserTokenValidator(
            ObjectMapper objectMapper,
            @Value("${bandy.user-token.secret:}") String secret
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret;
    }

    public void requireValid(String authorization) {
        if (secret.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "La validation des jetons utilisateur n’est pas configurée."
            );
        }
        if (authorization == null || !authorization.startsWith("Bearer ")
                || !isValid(authorization.substring("Bearer ".length()).trim())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private boolean isValid(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return false;
        }

        try {
            Map<String, Object> header = objectMapper.readValue(
                    decode(parts[0]),
                    CLAIMS_TYPE
            );
            if (!"HS256".equals(header.get("alg"))
                    || !"JWT".equals(header.get("typ"))) {
                return false;
            }

            String signingInput = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(
                    sign(signingInput),
                    decode(parts[2])
            )) {
                return false;
            }

            Map<String, Object> claims = objectMapper.readValue(
                    decode(parts[1]),
                    CLAIMS_TYPE
            );
            if (!(claims.get("sub") instanceof String subject)
                    || !(claims.get("exp") instanceof Number expiration)
                    || expiration.longValue() <= Instant.now().getEpochSecond()) {
                return false;
            }
            return Integer.parseInt(subject) > 0;
        } catch (IOException
                 | GeneralSecurityException
                 | IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] sign(String signingInput) throws GeneralSecurityException {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET doit contenir au moins 32 octets."
            );
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] decode(String part) {
        return Base64.getUrlDecoder().decode(part);
    }
}
