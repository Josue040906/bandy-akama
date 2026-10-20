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
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class UserTokenValidator {
    private static final TypeReference<Map<String, Object>> CLAIMS_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final String secret;
    private final String defaultApplicationId;
    private final Set<String> allowedApplicationIds;

    public UserTokenValidator(
            ObjectMapper objectMapper,
            @Value("${bandy.user-token.secret:}") String secret,
            @Value("${bandy.conversations.application-id:assistant-rh}")
            String defaultApplicationId,
            @Value("${bandy.conversations.allowed-application-ids:}")
            String allowedApplicationIds
    ) {
        this.objectMapper = objectMapper;
        this.secret = secret;
        this.defaultApplicationId = defaultApplicationId;
        this.allowedApplicationIds = Arrays.stream(allowedApplicationIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String requireValid(String authorization) {
        return validate(authorization).ownerId();
    }

    public UserIdentity validate(String authorization) {
        if (secret.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "La validation des jetons utilisateur n’est pas configurée."
            );
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return validatedIdentity(authorization.substring("Bearer ".length()).trim());
    }

    private UserIdentity validatedIdentity(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        try {
            Map<String, Object> header = objectMapper.readValue(
                    decode(parts[0]),
                    CLAIMS_TYPE
            );
            if (!"HS256".equals(header.get("alg"))
                    || !"JWT".equals(header.get("typ"))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }

            String signingInput = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(
                    sign(signingInput),
                    decode(parts[2])
            )) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }

            Map<String, Object> claims = objectMapper.readValue(
                    decode(parts[1]),
                    CLAIMS_TYPE
            );
            if (!(claims.get("sub") instanceof String subject)
                    || !(claims.get("exp") instanceof Number expiration)
                    || expiration.longValue() <= Instant.now().getEpochSecond()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }
            int numericSubject = Integer.parseInt(subject);
            if (numericSubject <= 0) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }
            return new UserIdentity(
                    Integer.toString(numericSubject),
                    applicationId(claims.get("aud"))
            );
        } catch (IOException
                 | GeneralSecurityException
                 | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private String applicationId(Object audienceClaim) {
        if (audienceClaim == null) {
            return defaultApplicationId;
        }
        Set<String> allowed = allowedApplicationIds.isEmpty()
                ? Set.of(defaultApplicationId)
                : allowedApplicationIds;
        if (audienceClaim instanceof String audience
                && allowed.contains(audience)) {
            return audience;
        }
        if (audienceClaim instanceof java.util.List<?> audiences) {
            var matches = audiences.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(allowed::contains)
                    .distinct()
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0);
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
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
