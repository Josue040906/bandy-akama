package com.bandyakama.gemini;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeminiClientFactory {
    private final int timeoutMillis;
    private final int retryAttempts;

    public GeminiClientFactory(
            @Value("${bandy.ai.timeout-millis:15000}") int timeoutMillis,
            @Value("${bandy.ai.retry-attempts:1}") int retryAttempts
    ) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "Le délai maximal Gemini doit être positif."
            );
        }
        if (retryAttempts < 1 || retryAttempts > 5) {
            throw new IllegalArgumentException(
                    "Le nombre de tentatives Gemini doit être compris entre 1 et 5."
            );
        }
        this.timeoutMillis = timeoutMillis;
        this.retryAttempts = retryAttempts;
    }

    public Client create(String apiKey) {
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(httpOptions())
                .build();
    }

    HttpOptions httpOptions() {
        HttpRetryOptions retryOptions = HttpRetryOptions.builder()
                .attempts(retryAttempts)
                .initialDelay(0.5)
                .maxDelay(2.0)
                .expBase(2.0)
                .jitter(0.2)
                .build();
        HttpOptions httpOptions = HttpOptions.builder()
                .timeout(timeoutMillis)
                .retryOptions(retryOptions)
                .build();
        return httpOptions;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    public int retryAttempts() {
        return retryAttempts;
    }
}
