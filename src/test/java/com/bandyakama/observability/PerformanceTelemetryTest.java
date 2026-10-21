package com.bandyakama.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceTelemetryTest {
    @Test
    void safeErrorMessageRedactsCredentialsAndLimitsLength() {
        String apiKey = "private-api-key";
        String message = "Invalid key=" + apiKey
                + " Bearer abc.def_123"
                + " AIza123456789012345678901234567890"
                + "\n" + "x".repeat(600);

        String safeMessage =
                PerformanceTelemetry.safeErrorMessage(message, apiKey);

        assertFalse(safeMessage.contains(apiKey));
        assertFalse(safeMessage.contains("abc.def_123"));
        assertFalse(safeMessage.contains("AIza123456789012345678901234567890"));
        assertFalse(safeMessage.contains("\n"));
        assertTrue(safeMessage.length() <= 500);
        assertTrue(safeMessage.contains("[REDACTED]"));
    }

    @Test
    void safeErrorMessageProvidesFallbackForMissingMessage() {
        assertEquals(
                "No error message provided",
                PerformanceTelemetry.safeErrorMessage(null, null)
        );
    }

    @Test
    void safeCauseChainReportsNestedTransportFailureWithoutCredentials() {
        String apiKey = "private-api-key";
        Throwable failure = new RuntimeException(
                "SDK request failed",
                new java.net.SocketTimeoutException(
                        "Timed out using key=" + apiKey
                )
        );

        String causeChain =
                PerformanceTelemetry.safeCauseChain(failure, apiKey);

        assertTrue(causeChain.contains("SocketTimeoutException"));
        assertTrue(causeChain.contains("[REDACTED]"));
        assertFalse(causeChain.contains(apiKey));
    }
}
