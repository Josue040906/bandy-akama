package com.bandyakama.gemini;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GeminiClientFactory {
    private final int timeoutMillis;
    private final int retryAttempts;

    public GeminiClientFactory(
            @Value("${bandy.ai.timeout-millis:15000}") int timeoutMillis,
            @Value("${bandy.ai.retry-attempts:2}") int retryAttempts
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
        return HttpOptions.builder()
                .timeout(timeoutMillis)
                .retryOptions(retryOptions())
                .build();
    }

    /**
     * Seule politique de nouvelle tentative de Bandy : uniquement sur une
     * surcharge passagère de Gemini (503). Un refus de quota (429) n'est pas
     * retenté, car il échouerait de nouveau en consommant le quota.
     */
    public HttpRetryOptions retryOptions() {
        return HttpRetryOptions.builder()
                .attempts(retryAttempts)
                .initialDelay(0.5)
                .maxDelay(2.0)
                .expBase(2.0)
                .jitter(0.2)
                .httpStatusCodes(List.of(503))
                .build();
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    public int retryAttempts() {
        return retryAttempts;
    }
}
