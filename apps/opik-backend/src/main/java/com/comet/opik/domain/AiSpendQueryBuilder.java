package com.comet.opik.domain;

import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendLane;
import jakarta.inject.Singleton;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
class AiSpendQueryBuilder {

    private static final String USER_UUID_EXPR = "JSONExtractString(metadata, 'cc', 'identity', 'user_uuid')";

    // Queries are rendered through StringTemplate (getSTWithLogComment), so the '<' operator must be escaped as
    // '\\<' (see clickhouse.md / WorkspaceMetricsDAO). Only the current/previous predicates use '<'/'<='.
    private static final String TS_CURRENT = "start_time >= parseDateTime64BestEffort(:ts_start, 9)"
            + " AND start_time \\<= parseDateTime64BestEffort(:ts_end, 9)";
    private static final String TS_PREVIOUS = "start_time >= parseDateTime64BestEffort(:ts_prior_start, 9)"
            + " AND start_time \\< parseDateTime64BestEffort(:ts_start, 9)";
    private static final String TS_WINDOW = "start_time BETWEEN parseDateTime64BestEffort(:ts_prior_start, 9)"
            + " AND parseDateTime64BestEffort(:ts_end, 9)";
    private static final String TS_RANGE = "start_time BETWEEN parseDateTime64BestEffort(:ts_start, 9)"
            + " AND parseDateTime64BestEffort(:ts_end, 9)";

    private static final String ID_CURRENT = "id >= :id_start AND id \\<= :id_end";
    private static final String ID_PREVIOUS = "id >= :id_prior_start AND id \\< :id_start";
    private static final String ID_WINDOW = "id BETWEEN :id_prior_start AND :id_end";
    private static final String ID_RANGE = "id BETWEEN :id_start AND :id_end";

    private static final String CURRENT = ID_CURRENT + " AND " + TS_CURRENT;
    private static final String PREVIOUS = ID_PREVIOUS + " AND " + TS_PREVIOUS;
    private static final String WINDOW = ID_WINDOW + " AND " + TS_WINDOW;
    private static final String RANGE = ID_RANGE + " AND " + TS_RANGE;
    private static final String RANGE_ALIASED = "s.id BETWEEN :id_start AND :id_end"
            + " AND s.start_time BETWEEN parseDateTime64BestEffort(:ts_start, 9)"
            + " AND parseDateTime64BestEffort(:ts_end, 9)";

    private static final String SPAN_RANGE_ALIASED = RANGE_ALIASED + " AND s.trace_id BETWEEN :id_start AND :id_end";

    private static final int BREAKDOWN_ITEM_LIMIT = 200;

    private static final List<String> TIER_COLUMNS = List.of("total", "input", "cache_read", "cache_creation",
            "output");

    private static final String BILLING_TOTAL_EXPR = "JSONExtractInt(metadata, 'cc', 'billing', 'totals', '%s')";

    // Tier-token summary from cc.billing — the FE prices these (hardcoded
    // Claude rates); no dollar amounts are computed in the backend.
    private static final String TIER_SUMMARY = """
            SELECT
                SUMIf(%3$s, %1$s) AS input_current,
                SUMIf(%4$s, %1$s) AS cache_read_current,
                SUMIf(%5$s, %1$s) AS cache_creation_current,
                SUMIf(%6$s, %1$s) AS output_current,
                SUMIf(%3$s, %2$s) AS input_previous,
                SUMIf(%4$s, %2$s) AS cache_read_previous,
                SUMIf(%5$s, %2$s) AS cache_creation_previous,
                SUMIf(%6$s, %2$s) AS output_previous
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %7$s
            ;
            """.formatted(CURRENT, PREVIOUS,
            BILLING_TOTAL_EXPR.formatted("input"), BILLING_TOTAL_EXPR.formatted("cache_read"),
            BILLING_TOTAL_EXPR.formatted("cache_creation"), BILLING_TOTAL_EXPR.formatted("output"),
            WINDOW);

    private static final String COUNTS_SUMMARY = """
            SELECT
                countIf(%2$s) AS messages_current,
                countIf(%3$s) AS messages_previous,
                uniqExactIf(user_uuid, %2$s AND user_uuid != '') AS active_users_current,
                uniqExactIf(user_uuid, %3$s AND user_uuid != '') AS active_users_previous,
                uniqExactIf(user_uuid, user_uuid != '') AS total_users
            FROM (
                SELECT %1$s AS user_uuid, id, start_time
                FROM traces final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND %4$s
            )
            ;
            """.formatted(USER_UUID_EXPR, CURRENT, PREVIOUS, WINDOW);

    private static final String COMPOSITION_TOKENS = """
            SELECT
                %1$s
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %2$s
                <if(user_uuid)> AND %3$s = :user_uuid <endif>
            ;
            """;

    // Output-side rows live in cc.billing.lanes.output.items with names
    // `thinking`, `assistant_text`, or `<lane>/<entity>` for tool calls.
    private static final String OUTPUT_ITEMS = """
            SELECT JSONExtractString(item, 'name') AS name,
                   JSONExtractInt(item, 'output') AS output_tokens,
                   JSONExtractInt(item, 'count') AS events
            FROM traces final
            ARRAY JOIN JSONExtractArrayRaw(metadata, 'cc', 'billing', 'lanes', 'output', 'items') AS item
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %1$s
                <if(user_uuid)> AND %2$s = :user_uuid <endif>""";

    private static final String COMPOSITION_OUTPUT = """
            SELECT %1$s AS lane,
                   SUM(output_tokens) AS tokens
            FROM (
            %2$s
            )
            GROUP BY lane
            HAVING lane != ''
            ;
            """;

    private static final String BREAKDOWN_ENVELOPE = """
            SELECT label,
                   total_tokens,
                   definition_tokens,
                   usage_tokens,
                   events,
                   SUM(total_tokens) OVER () AS grand_total,
                   COUNT() OVER () AS group_count
            FROM (
            %1$s
            )
            ORDER BY total_tokens DESC
            LIMIT :limit
            ;
            """;

    private static final String BREAKDOWN = """
            SELECT JSONExtractString(item, 'name') AS label,
                   SUM(JSONExtractInt(item, 'total')) AS total_tokens,
                   SUMIf(JSONExtractInt(item, 'total'), JSONExtractString(item, 'kind') = 'definition') AS definition_tokens,
                   SUMIf(JSONExtractInt(item, 'total'), JSONExtractString(item, 'kind') = 'usage') AS usage_tokens,
                   SUM(JSONExtractInt(item, 'count')) AS events
            FROM traces final
            ARRAY JOIN JSONExtractArrayRaw(metadata, 'cc', 'billing', 'lanes', '%1$s', 'items') AS item
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %2$s
                <if(user_uuid)> AND %3$s = :user_uuid <endif>
            GROUP BY label
            HAVING label != ''""";

    private static final String OUTPUT_BREAKDOWN = """
            SELECT %1$s AS label,
                   SUM(output_tokens) AS total_tokens,
                   toInt64(0) AS definition_tokens,
                   SUM(output_tokens) AS usage_tokens,
                   SUM(events) AS events
            FROM (
            %3$s
            )
            WHERE %2$s
            GROUP BY label
            HAVING label != ''""";

    // One statement: aggregate traces and spans per user into CTEs, LEFT JOIN them, then sort + page in ClickHouse.
    // The window functions run over the full aggregated set before LIMIT, so high_spend (threshold vs the average over
    // all users) and total (COUNT() OVER ()) stay correct under pagination.
    private static final String USERS = """
            WITH
                windowed_traces AS (
                    SELECT
                        id,
                        %1$s AS user_uuid,
                        JSONExtractString(metadata, 'cc', 'identity', 'user_email') AS user_email,
                        JSONExtractString(metadata, 'cc', 'identity', 'user_display_name') AS user_display_name,
                        arraySum(arrayMap(x -> JSONExtractInt(x, 'count'),
                            JSONExtractArrayRaw(metadata, 'cc', 'billing', 'lanes', 'skills', 'items'))) AS skills_loaded,
                        arrayCount(x -> JSONExtractString(x, 'kind') = 'definition',
                            JSONExtractArrayRaw(metadata, 'cc', 'billing', 'lanes', 'mcp_servers', 'items')) AS mcps_available,
                        JSONExtractString(metadata, 'cc', 'git', 'repository') AS repository,
                        %5$s AS tok_input,
                        %6$s AS tok_cache_read,
                        %7$s AS tok_cache_creation,
                        %8$s AS tok_output
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND %2$s
                ),
                trace_agg AS (
                    SELECT
                        user_uuid,
                        anyLast(user_email) AS user_email,
                        anyLast(user_display_name) AS user_display_name,
                        count() AS requests,
                        sum(skills_loaded) AS skills,
                        max(mcps_available) AS mcps,
                        arrayFilter(x -> x != '', groupUniqArray(repository)) AS repositories,
                        sum(tok_input) AS input_tokens,
                        sum(tok_cache_read) AS cache_read_tokens,
                        sum(tok_cache_creation) AS cache_creation_tokens,
                        sum(tok_output) AS output_tokens,
                        sum(tok_input + tok_cache_read + tok_cache_creation + tok_output) AS total_tokens
                    FROM windowed_traces
                    WHERE user_uuid != ''
                    GROUP BY user_uuid
                ),
                spend_agg AS (
                    SELECT
                        t.user_uuid AS user_uuid,
                        countIf(startsWith(s.name, 'mcp__')) AS mcp_calls,
                        anyLastIf(s.model, s.model != '') AS model
                    FROM spans s final
                    INNER JOIN windowed_traces t ON s.trace_id = t.id
                    WHERE s.workspace_id = :workspace_id
                        AND s.project_id = :project_id
                        AND %3$s
                    GROUP BY t.user_uuid
                )
            SELECT
                ta.user_uuid AS user_uuid,
                ta.user_email AS user_email,
                ta.user_display_name AS user_display_name,
                ta.requests AS requests,
                ta.skills AS skills,
                ta.mcps AS mcps,
                ta.repositories AS repositories,
                ta.input_tokens AS input_tokens,
                ta.cache_read_tokens AS cache_read_tokens,
                ta.cache_creation_tokens AS cache_creation_tokens,
                ta.output_tokens AS output_tokens,
                ta.total_tokens AS total_tokens,
                ifNull(sa.mcp_calls, 0) AS mcp_calls,
                sa.model AS model,
                toInt64(if(ta.total_tokens >= :high_spend_factor * avg(ta.total_tokens) OVER (), 1, 0)) AS high_spend,
                COUNT() OVER () AS total
            FROM trace_agg ta
            LEFT ANY JOIN spend_agg sa ON ta.user_uuid = sa.user_uuid
            WHERE 1 = 1
                <if(user_name)> AND (ilike(ta.user_email, concat('%%', :user_name, '%%'))
                    OR ilike(ta.user_display_name, concat('%%', :user_name, '%%'))) <endif>
            ORDER BY %4$s
            LIMIT :limit OFFSET :offset
            ;
            """;

    int breakdownItemLimit() {
        return BREAKDOWN_ITEM_LIMIT;
    }

    List<String> tierColumns() {
        return TIER_COLUMNS;
    }

    String summaryTiersSql() {
        return TIER_SUMMARY;
    }

    String summaryCountsSql() {
        return COUNTS_SUMMARY;
    }

    String compositionTokensSql() {
        String selectList = Arrays.stream(SpendLane.values())
                .map(lane -> TIER_COLUMNS.stream()
                        .map(col -> "SUM(%s) AS %s_%s".formatted(lane.getTierExpression(col), lane.getKey(), col))
                        .collect(Collectors.joining(",\n    ")))
                .collect(Collectors.joining(",\n    "));
        // The FE prices the tier columns; ship the distinct models too.
        selectList += ",\n    groupUniqArrayIf(JSONExtractString(metadata, 'cc', 'billing', 'model'),"
                + " JSONExtractString(metadata, 'cc', 'billing', 'model') != '') AS models";
        return COMPOSITION_TOKENS.formatted(selectList, RANGE, USER_UUID_EXPR);
    }

    String compositionOutputSql() {
        String laneExpr = Arrays.stream(OutputLane.values())
                .map(lane -> "%s, '%s'".formatted(lane.getNamePredicate(), lane.getKey()))
                .collect(Collectors.joining(",\n    ", "multiIf(\n    ", ",\n    '')"));
        return COMPOSITION_OUTPUT.formatted(laneExpr, outputItemsSql());
    }

    String breakdownSql(SpendLane lane) {
        String inner = BREAKDOWN.formatted(lane.getKey(), RANGE, USER_UUID_EXPR);
        return BREAKDOWN_ENVELOPE.formatted(inner);
    }

    String outputBreakdownSql(OutputLane lane) {
        String inner = OUTPUT_BREAKDOWN.formatted(lane.getLabelExpression(), lane.getNamePredicate(),
                outputItemsSql());
        return BREAKDOWN_ENVELOPE.formatted(inner);
    }

    String usersSql(String orderBy) {
        return USERS.formatted(USER_UUID_EXPR, RANGE, SPAN_RANGE_ALIASED, orderBy,
                BILLING_TOTAL_EXPR.formatted("input"), BILLING_TOTAL_EXPR.formatted("cache_read"),
                BILLING_TOTAL_EXPR.formatted("cache_creation"), BILLING_TOTAL_EXPR.formatted("output"));
    }

    private String outputItemsSql() {
        return OUTPUT_ITEMS.formatted(RANGE, USER_UUID_EXPR);
    }
}
