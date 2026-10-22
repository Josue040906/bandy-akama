package com.bandyakama.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class BudgetAwareJdbcTemplate extends JdbcTemplate {
    public BudgetAwareJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void applyStatementSettings(Statement statement)
            throws SQLException {
        super.applyStatementSettings(statement);
        var activeBudget = RequestBudget.current();
        if (activeBudget.isPresent()) {
            int remainingMillis = activeBudget.get().remainingMillis();
            int timeoutSeconds = (int) Math.max(
                    1,
                    (remainingMillis + 999L) / 1_000
            );
            int configuredTimeout = getQueryTimeout();
            if (configuredTimeout > 0) {
                timeoutSeconds = Math.min(timeoutSeconds, configuredTimeout);
            }
            statement.setQueryTimeout(timeoutSeconds);
        }
    }
}
