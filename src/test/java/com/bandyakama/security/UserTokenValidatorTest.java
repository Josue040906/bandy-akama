package com.bandyakama.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserTokenValidatorTest {
    private static final String SECRET =
            "0123456789abcdef0123456789abcdef";

    private final UserTokenValidator validator =
            new UserTokenValidator(new ObjectMapper(), SECRET);

    @Test
    void acceptsValidRhBearerTokens() {
        assertDoesNotThrow(() -> validator.requireValid(
                "Bearer " + token(Instant.now().getEpochSecond() + 3600, SECRET)
        ));
    }

    @Test
    void rejectsExpiredTokens() {
        assertThrows(
                ResponseStatusException.class,
                () -> validator.requireValid(
                        "Bearer " + token(Instant.now().getEpochSecond() - 1, SECRET)
                )
        );
    }

    @Test
    void rejectsTokensSignedByAnotherSecret() {
        assertThrows(
                ResponseStatusException.class,
                () -> validator.requireValid(
                        "Bearer " + token(
                                Instant.now().getEpochSecond() + 3600,
                                "abcdef0123456789abcdef0123456789"
                        )
                )
        );
    }

    @Test
    void rejectsMissingBearerTokens() {
        assertThrows(
                ResponseStatusException.class,
                () -> validator.requireValid(null)
        );
    }

    private String token(long expiresAt, String signingSecret) {
        try {
            String header = encode(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
                            .getBytes(StandardCharsets.UTF_8)
            );
            String claims = encode(new ObjectMapper().writeValueAsBytes(
                    Map.of("sub", "42", "iat", 1, "exp", expiresAt)
            ));
            String signingInput = header + "." + claims;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    signingSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            String signature = encode(mac.doFinal(
                    signingInput.getBytes(StandardCharsets.US_ASCII)
            ));
            return signingInput + "." + signature;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
