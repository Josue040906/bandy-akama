package com.bandyakama.observability;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class BudgetAwareJdbcTemplateTest {
    @Test
    void capsSqlStatementTimeoutToTheRemainingTurnBudget() throws Exception {
        BudgetAwareJdbcTemplate jdbcTemplate =
                new BudgetAwareJdbcTemplate(mock(DataSource.class));
        Statement statement = mock(Statement.class);

        try (RequestBudget.Scope ignored = RequestBudget.open(3_000)) {
            jdbcTemplate.applyStatementSettings(statement);
        }

        verify(statement).setQueryTimeout(intThat(
                seconds -> seconds >= 1 && seconds <= 3
        ));
    }

    @Test
    void refusesToConfigureSqlAfterTheTurnBudgetExpires() throws Exception {
        BudgetAwareJdbcTemplate jdbcTemplate =
                new BudgetAwareJdbcTemplate(mock(DataSource.class));
        Statement statement = mock(Statement.class);

        try (RequestBudget.Scope ignored = RequestBudget.open(1)) {
            Thread.sleep(5);
            assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    () -> jdbcTemplate.applyStatementSettings(statement)
            );
        }

        verify(statement, never()).setQueryTimeout(anyInt());
        verifyNoMoreInteractions(statement);
    }
}
