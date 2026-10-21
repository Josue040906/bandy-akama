package com.bandyakama.gemini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeminiClientFactoryTest {
    @Test
    void configuresRequestTimeoutAndBoundedRetryBackoff() {
        GeminiClientFactory factory = new GeminiClientFactory(12_000, 2);
        var httpOptions = factory.httpOptions();

        assertEquals(12_000, httpOptions.timeout().orElseThrow());
        assertEquals(
                2,
                httpOptions.retryOptions().orElseThrow()
                        .attempts().orElseThrow()
        );
        assertEquals(
                0.5,
                httpOptions.retryOptions().orElseThrow()
                        .initialDelay().orElseThrow()
        );
        assertEquals(
                2.0,
                httpOptions.retryOptions().orElseThrow()
                        .maxDelay().orElseThrow()
        );
    }

    @Test
    void rejectsInvalidTimeoutOrRetryCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GeminiClientFactory(0, 2)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new GeminiClientFactory(30_000, 6)
        );
    }
}
