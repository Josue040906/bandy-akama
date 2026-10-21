package com.bandyakama.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PerformanceTelemetry {
    private static final Pattern API_KEY_PARAMETER =
            Pattern.compile("(?i)([?&]key=)[^&\\s]+");
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern GOOGLE_API_KEY =
            Pattern.compile("AIza[0-9A-Za-z_-]{20,}");
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    private static final int MAX_CAUSE_DEPTH = 5;
    private static final Logger LOGGER =
            LoggerFactory.getLogger(PerformanceTelemetry.class);

    public void stage(
            UUID requestId,
            String stage,
            long startedAtNanos,
            boolean succeeded
    ) {
        LOGGER.info(
                "bandy_perf event=stage request_id={} stage={} duration_us={} outcome={}",
                requestId,
                stage,
                elapsedMicros(startedAtNanos),
                outcome(succeeded)
        );
    }

    public void modelCallFailure(
            UUID requestId,
            int callNumber,
            Throwable failure,
            String apiKey
    ) {
        LOGGER.warn(
                "bandy_perf event=model_call_failure request_id={} "
                        + "call_number={} exception_class={} error_message={} "
                        + "cause_chain={}",
                requestId,
                callNumber,
                failure.getClass().getName(),
                safeErrorMessage(failure.getMessage(), apiKey),
                safeCauseChain(failure, apiKey)
        );
    }

    static String safeCauseChain(Throwable failure, String apiKey) {
        StringBuilder chain = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Throwable cause = failure.getCause();
        int depth = 0;
        while (cause != null && depth < MAX_CAUSE_DEPTH && seen.add(cause)) {
            if (!chain.isEmpty()) {
                chain.append(" -> ");
            }
            chain.append(cause.getClass().getName())
                    .append(": ")
                    .append(safeErrorMessage(cause.getMessage(), apiKey));
            cause = cause.getCause();
            depth++;
        }
        return chain.isEmpty() ? "none" : chain.toString();
    }

    static String safeErrorMessage(String message, String apiKey) {
        if (message == null || message.isBlank()) {
            return "No error message provided";
        }
        String safeMessage = message.replace('\n', ' ').replace('\r', ' ');
        if (apiKey != null && !apiKey.isBlank()) {
            safeMessage = safeMessage.replace(apiKey, "[REDACTED]");
        }
        safeMessage = API_KEY_PARAMETER.matcher(safeMessage)
                .replaceAll("$1[REDACTED]");
        safeMessage = BEARER_TOKEN.matcher(safeMessage)
                .replaceAll("$1[REDACTED]");
        safeMessage = GOOGLE_API_KEY.matcher(safeMessage)
                .replaceAll("[REDACTED]");
        if (safeMessage.length() > MAX_ERROR_MESSAGE_LENGTH) {
            safeMessage = safeMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
        }
        return safeMessage;
    }

    public void modelCall(
            UUID requestId,
            int callNumber,
            long startedAtNanos,
            boolean succeeded
    ) {
        LOGGER.info(
                "bandy_perf event=model_call request_id={} call_number={} "
                        + "duration_us={} outcome={}",
                requestId,
                callNumber,
                elapsedMicros(startedAtNanos),
                outcome(succeeded)
        );
    }

    public void toolCall(
            String requestId,
            String toolName,
            long startedAtNanos,
            boolean succeeded
    ) {
        LOGGER.info(
                "bandy_perf event=tool_call request_id={} tool={} "
                        + "duration_us={} outcome={}",
                requestId == null ? "untracked" : requestId,
                toolName,
                elapsedMicros(startedAtNanos),
                outcome(succeeded)
        );
    }

    public void toolCacheHit(UUID requestId, String toolName) {
        LOGGER.info(
                "bandy_perf event=tool_cache_hit request_id={} tool={}",
                requestId == null ? "untracked" : requestId,
                toolName
        );
    }

    public void contextSize(
            UUID requestId,
            int historyCharacters,
            int systemInstructionCharacters,
            int currentMessageCharacters,
            int toolCount,
            int toolDescriptionCharacters
    ) {
        LOGGER.info(
                "bandy_perf event=context_size request_id={} history_chars={} "
                        + "system_instruction_chars={} current_message_chars={} "
                        + "tool_count={} tool_description_chars={}",
                requestId,
                historyCharacters,
                systemInstructionCharacters,
                currentMessageCharacters,
                toolCount,
                toolDescriptionCharacters
        );
    }

    public void assistantTurn(
            UUID requestId,
            long startedAtNanos,
            int modelCalls,
            int toolCalls,
            int toolCacheHits,
            int toolRounds,
            int timeoutMillis,
            int retryAttempts,
            int maxToolRounds,
            int turnBudgetMillis,
            boolean succeeded
    ) {
        LOGGER.info(
                "bandy_perf event=assistant_turn request_id={} duration_us={} "
                        + "model_calls={} tool_calls={} tool_cache_hits={} "
                        + "tool_rounds={} "
                        + "gemini_timeout_ms={} gemini_max_attempts={} "
                        + "max_tool_rounds={} turn_budget_ms={} outcome={}",
                requestId,
                elapsedMicros(startedAtNanos),
                modelCalls,
                toolCalls,
                toolCacheHits,
                toolRounds,
                timeoutMillis,
                retryAttempts,
                maxToolRounds,
                turnBudgetMillis,
                outcome(succeeded)
        );
    }

    private long elapsedMicros(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000);
    }

    private String outcome(boolean succeeded) {
        return succeeded ? "success" : "error";
    }
}
