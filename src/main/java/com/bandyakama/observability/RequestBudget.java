package com.bandyakama.observability;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class RequestBudget {
    private static final ThreadLocal<RequestBudget> CURRENT =
            new ThreadLocal<>();

    private final long deadlineNanos;
    private final int budgetMillis;

    private RequestBudget(int budgetMillis) {
        if (budgetMillis < 1) {
            throw new IllegalArgumentException(
                    "Le budget de requête doit être positif."
            );
        }
        this.budgetMillis = budgetMillis;
        this.deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    }

    public static Scope open(int budgetMillis) {
        RequestBudget previous = CURRENT.get();
        RequestBudget current = new RequestBudget(budgetMillis);
        CURRENT.set(current);
        return new Scope(previous);
    }

    public static Optional<RequestBudget> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public int budgetMillis() {
        return budgetMillis;
    }

    public int remainingMillis() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Le délai maximal de traitement de la demande est dépassé."
            );
        }
        return (int) Math.max(
                1,
                Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(
                        remainingNanos
                ))
        );
    }

    public static final class Scope implements AutoCloseable {
        private final RequestBudget previous;

        private Scope(RequestBudget previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
