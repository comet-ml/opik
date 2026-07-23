package com.comet.opik.api.resources.utils.planshape;

import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBI {@link SqlLogger} that captures the {@code EXPLAIN FORMAT=JSON} plan of every {@code SELECT} rendered while it is
 * installed, keyed by the rendered SQL. It reuses JDBI's own {@link Argument} binding machinery to re-apply the exact
 * parameters onto the EXPLAIN prepared statement on the same live connection, so no value extraction or re-binding
 * guesswork is needed. Non-SELECT statements are ignored.
 *
 * <p>This is the shared MySQL capture foundation for the query plan-shape gate (OPIK-7448). The plan JSON it collects is
 * consumed by {@link MySqlPlanShapeAsserter}. Deduplicating by rendered SQL keeps the captured set bounded even when a
 * query runs many times across a test.
 */
@Slf4j
public class CapturingSqlLogger implements SqlLogger {

    private final Map<String, String> plansByRenderedSql = new ConcurrentHashMap<>();

    @Override
    public void logAfterExecution(StatementContext context) {
        var renderedSql = context.getRenderedSql();
        if (renderedSql == null || !isSelect(renderedSql)) {
            return;
        }

        plansByRenderedSql.computeIfAbsent(renderedSql, sql -> explain(context));
    }

    private String explain(StatementContext context) {
        var parsedSql = context.getParsedSql();
        var jdbcSql = parsedSql.getSql();
        var parameterNames = parsedSql.getParameters().getParameterNames();
        var binding = context.getBinding();

        try (PreparedStatement explainStatement = context.getConnection()
                .prepareStatement("EXPLAIN FORMAT=JSON " + jdbcSql)) {

            for (int i = 0; i < parameterNames.size(); i++) {
                int position = i;
                String parameterName = parameterNames.get(i);
                Argument argument = binding.findForName(parameterName, context)
                        .or(() -> binding.findForPosition(position))
                        .orElseThrow(() -> new IllegalStateException(
                                "No bound argument for parameter '%s' in: %s".formatted(parameterName, jdbcSql)));
                // JDBC positions are 1-based; JDBI Arguments apply themselves onto the target statement.
                argument.apply(position + 1, explainStatement, context);
            }

            try (ResultSet resultSet = explainStatement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString(1);
                }
                return null;
            }
        } catch (Exception e) {
            // A query the gate cannot EXPLAIN (e.g. references a session TEMPORARY TABLE that only exists mid-flight)
            // must not fail the capturing test run; it is simply left uncaptured and the asserter never sees it.
            log.debug("Skipping EXPLAIN for query [{}]: {}", jdbcSql, e.getMessage());
            return null;
        }
    }

    private static boolean isSelect(String sql) {
        return sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select");
    }

    /**
     * @return an immutable snapshot of captured plans, keyed by rendered SQL. Entries whose EXPLAIN failed hold a
     *         {@code null} plan and are filtered by the asserter.
     */
    public Map<String, String> capturedPlans() {
        return Map.copyOf(plansByRenderedSql);
    }

    public void clear() {
        plansByRenderedSql.clear();
    }
}
