package com.bandyakama.observability;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestBudgetTest {
    @Test
    void exposesRemainingBudgetOnlyInsideItsScope() {
        assertTrue(RequestBudget.current().isEmpty());
        try (RequestBudget.Scope ignored = RequestBudget.open(1_000)) {
            RequestBudget budget = RequestBudget.current().orElseThrow();
            assertEquals(1_000, budget.budgetMillis());
            assertTrue(budget.remainingMillis() > 0);
        }
        assertFalse(RequestBudget.current().isPresent());
    }

    @Test
    void refusesToStartWorkAfterTheDeadline() throws InterruptedException {
        try (RequestBudget.Scope ignored = RequestBudget.open(1)) {
            Thread.sleep(5);
            ResponseStatusException failure = assertThrows(
                    ResponseStatusException.class,
                    () -> RequestBudget.current().orElseThrow().remainingMillis()
            );
            assertEquals(504, failure.getStatusCode().value());
        }
    }
}
