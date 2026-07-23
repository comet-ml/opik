package com.comet.opik.api.resources.utils.planshape;

import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBI {@link SqlLogger} that captures the {@code EXPLAIN FORMAT=JSON} plan of every {@code SELECT} rendered while it is
 * installed, keyed by the rendered SQL. It reuses JDBI's own {@link Argument} binding machinery to re-apply the exact
 * parameters onto the EXPLAIN prepared statement on the same live connection, so no value extraction or re-binding
 * guesswork is needed. Non-SELECT statements are ignored.
 *
 * <p>EXPLAIN is requested in JSON <b>format version 2</b> ({@code explain_json_format_version = 2}): version 1 puts the
 * table <i>alias</i> in {@code table_name} (so a base-table match like {@code prompts} silently misses {@code FROM
 * prompts AS p}), while version 2 reports the base table name and a uniform {@code operation}/{@code access_type}
 * vocabulary for both materialization and full scans. {@link MySqlPlanShapeAsserter} depends on the v2 shape.</p>
 *
 * <p>This is the shared MySQL capture foundation for the query plan-shape gate (OPIK-7448). Deduplicating by rendered
 * SQL keeps the captured set bounded even when a query runs many times. A SELECT whose EXPLAIN throws is recorded in
 * {@link CapturedQueries#failedSql()} rather than silently dropped, so the gate can surface queries it could not vet
 * instead of treating them as clean.</p>
 */
@Slf4j
public class CapturingSqlLogger implements SqlLogger {

    private final Map<String, String> plansByRenderedSql = new ConcurrentHashMap<>();
    private final Set<String> failedSql = ConcurrentHashMap.newKeySet();

    /**
     * @param plansByRenderedSql successfully-captured {@code EXPLAIN FORMAT=JSON} plans, keyed by rendered SQL.
     * @param failedSql          rendered SELECTs whose EXPLAIN threw — captured but un-vetted, surfaced so they can't
     *                           pass the gate by omission.
     */
    public record CapturedQueries(Map<String, String> plansByRenderedSql, Set<String> failedSql) {
    }

    @Override
    public void logAfterExecution(StatementContext context) {
        var renderedSql = context.getRenderedSql();
        if (renderedSql == null || !isSelect(renderedSql)) {
            return;
        }
        if (plansByRenderedSql.containsKey(renderedSql) || failedSql.contains(renderedSql)) {
            return;
        }

        var plan = explain(context, renderedSql);
        if (plan != null) {
            plansByRenderedSql.put(renderedSql, plan);
        }
    }

    private String explain(StatementContext context, String renderedSql) {
        var parsedSql = context.getParsedSql();
        var jdbcSql = parsedSql.getSql();
        var parameterNames = parsedSql.getParameters().getParameterNames();
        var binding = context.getBinding();

        try {
            forceV2ExplainFormat(context);

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
                    failedSql.add(renderedSql);
                    return null;
                }
            }
        } catch (Exception e) {
            // A query the gate cannot EXPLAIN (e.g. one referencing a session TEMPORARY TABLE that only exists
            // mid-flight) is recorded as failed rather than dropped, so the gate can report it instead of passing it
            // by omission.
            log.debug("EXPLAIN failed for query [{}]: {}", jdbcSql, e.getMessage());
            failedSql.add(renderedSql);
            return null;
        }
    }

    // v2 puts the base table name in table_name (v1 uses the alias) — required for the full-scan check to match.
    private void forceV2ExplainFormat(StatementContext context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("SET explain_json_format_version = 2");
        }
    }

    private static boolean isSelect(String sql) {
        return sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select");
    }

    public CapturedQueries captured() {
        return new CapturedQueries(Map.copyOf(plansByRenderedSql), Set.copyOf(failedSql));
    }

    public void clear() {
        plansByRenderedSql.clear();
        failedSql.clear();
    }
}
