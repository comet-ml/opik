package com.comet.opik.domain;

import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stringtemplate.v4.ST;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendQueryBuilder {

    /** A query template plus the consumer binding its SQL fragments as ST variables. */
    record TemplatedQuery(String sql, Consumer<ST> fragments) {
    }

    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    private static final String USER_UUID_EXPR = "JSONExtractString(metadata, 'cc', 'identity', 'user_uuid')";

    // Range fragments are bound as ST variables (st.add), never spliced into template
    // text, so the '<' operators need no StringTemplate escaping.
    private static final String TS_CURRENT = "start_time >= parseDateTime64BestEffort(:ts_start, 9)"
            + " AND start_time <= parseDateTime64BestEffort(:ts_end, 9)";
    private static final String TS_PREVIOUS = "start_time >= parseDateTime64BestEffort(:ts_prior_start, 9)"
            + " AND start_time < parseDateTime64BestEffort(:ts_start, 9)";
    private static final String TS_WINDOW = "start_time BETWEEN parseDateTime64BestEffort(:ts_prior_start, 9)"
            + " AND parseDateTime64BestEffort(:ts_end, 9)";
    private static final String TS_RANGE = "start_time BETWEEN parseDateTime64BestEffort(:ts_start, 9)"
            + " AND parseDateTime64BestEffort(:ts_end, 9)";

    private static final String ID_CURRENT = "id >= :id_start AND id <= :id_end";
    private static final String ID_PREVIOUS = "id >= :id_prior_start AND id < :id_start";
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

    // ClickHouse predicates and label expressions per output lane live here so the
    // api enums carry no SQL. Output item names are `thinking`, `assistant_text`,
    // or `<lane>/<entity>` for tool calls, so the breakdown label is the suffix.
    private static final Map<OutputLane, String> OUTPUT_LANE_PREDICATES = new EnumMap<>(Map.of(
            OutputLane.THINKING, "name = 'thinking'",
            OutputLane.ASSISTANT_TEXT, "name = 'assistant_text'",
            OutputLane.BUILT_IN_TOOL_CALLS, "startsWith(name, 'built_in_tools/')",
            OutputLane.MCP_TOOL_CALLS, "startsWith(name, 'mcp_servers/')",
            OutputLane.SKILL_INVOCATIONS, "startsWith(name, 'skills/')"));

    private static final Map<OutputLane, String> OUTPUT_LANE_LABEL_EXPRESSIONS = new EnumMap<>(Map.of(
            OutputLane.THINKING, "'thinking'",
            OutputLane.ASSISTANT_TEXT, "'assistant_text'",
            OutputLane.BUILT_IN_TOOL_CALLS, "substring(name, length('built_in_tools/') + 1)",
            OutputLane.MCP_TOOL_CALLS, "substring(name, length('mcp_servers/') + 1)",
            OutputLane.SKILL_INVOCATIONS, "substring(name, length('skills/') + 1)"));

    private static final String OUTPUT_LANE_CASE = Arrays.stream(OutputLane.values())
            .map(lane -> "%s, '%s'".formatted(OUTPUT_LANE_PREDICATES.get(lane), lane.getKey()))
            .collect(Collectors.joining(",\n    ", "multiIf(\n    ", ",\n    '')"));

    // One typed JSONExtract parses cc.billing.lanes once per row; summing one
    // JSONExtractInt per lane x tier column re-parses the metadata blob for each
    // of the 50 paths (~28x more CPU at 1M traces).
    private static final String LANES_TUPLE_TYPE = Arrays.stream(SpendLane.values())
            .map(lane -> "%s Tuple(%s)".formatted(lane.getKey(),
                    TIER_COLUMNS.stream().map(col -> col + " Int64").collect(Collectors.joining(", "))))
            .collect(Collectors.joining(", ", "Tuple(", ")"));

    // The FE prices the tier columns; group by model so each model's tokens
    // carry its own rate.
    private static final String COMPOSITION_SELECT_LIST = Arrays.stream(SpendLane.values())
            .flatMap(lane -> TIER_COLUMNS.stream()
                    .map(col -> "SUM(lanes.%1$s.%2$s) AS %1$s_%2$s".formatted(lane.getKey(), col)))
            .collect(Collectors.joining(",\n    "));

    // Tier-token summary from cc.billing, grouped by model — the FE prices
    // each model at its own rate; no dollar amounts are computed in the BE.
    private static final String TIER_SUMMARY = """
            SELECT
                JSONExtractString(metadata, 'cc', 'billing', 'model') AS model,
                SUMIf(<input_expr>, <current>) AS input_current,
                SUMIf(<cache_read_expr>, <current>) AS cache_read_current,
                SUMIf(<cache_creation_expr>, <current>) AS cache_creation_current,
                SUMIf(<output_expr>, <current>) AS output_current,
                SUMIf(<input_expr>, <previous>) AS input_previous,
                SUMIf(<cache_read_expr>, <previous>) AS cache_read_previous,
                SUMIf(<cache_creation_expr>, <previous>) AS cache_creation_previous,
                SUMIf(<output_expr>, <previous>) AS output_previous
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND <window>
            GROUP BY model
            ;
            """;

    private static final String COUNTS_SUMMARY = """
            SELECT
                countIf(<current>) AS messages_current,
                countIf(<previous>) AS messages_previous,
                uniqExactIf(user_uuid, <current> AND user_uuid != '') AS active_users_current,
                uniqExactIf(user_uuid, <previous> AND user_uuid != '') AS active_users_previous,
                uniqExactIf(user_uuid, user_uuid != '') AS total_users
            FROM (
                SELECT <user_uuid_expr> AS user_uuid, id, start_time
                FROM traces final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND <window>
            )
            ;
            """;

    private static final String COMPOSITION_TOKENS = """
            SELECT
                model,
                <select_list>
            FROM (
                SELECT JSONExtract(metadata, 'cc', 'billing', 'lanes', '<lanes_type>') AS lanes,
                       JSONExtractString(metadata, 'cc', 'billing', 'model') AS model
                FROM traces final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND <range>
                    <if(user_uuid)> AND <user_uuid_expr> = :user_uuid <endif>
            )
            GROUP BY model
            ;
            """;

    // Output-side rows live in cc.billing.lanes.output.items.
    private static final String OUTPUT_ITEMS = """
            SELECT JSONExtractString(item, 'name') AS name,
                   JSONExtractInt(item, 'output') AS item_output,
                   JSONExtractInt(item, 'count') AS events,
                   JSONExtractString(metadata, 'cc', 'billing', 'model') AS model
            FROM traces final
            ARRAY JOIN JSONExtractArrayRaw(metadata, 'cc', 'billing', 'lanes', 'output', 'items') AS item
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND <range>
                <if(user_uuid)> AND <user_uuid_expr> = :user_uuid <endif>""";

    private static final String COMPOSITION_OUTPUT = """
            SELECT <lane_case> AS lane,
                   model,
                   SUM(item_output) AS tokens
            FROM (
            %s
            )
            GROUP BY lane, model
            HAVING lane != ''
            ;
            """.formatted(OUTPUT_ITEMS);

    // Items are aggregated per (label, model) so the FE can price each row
    // exactly when an entity spans multiple models. Labels are ranked by their
    // model-summed total; everything past :limit folds into a single
    // '__other__' label (still split by model). The envelope constants come
    // from the pre-fold CTEs: grand_total / total_events span all rows,
    // group_count is the distinct-label count that drives the "Other (N)" row.
    private static final String BREAKDOWN_TEMPLATE = """
            WITH agg AS (
            %s
            ),
            label_totals AS (
                SELECT label, SUM(total_tokens) AS lt, SUM(events) AS le
                FROM agg
                GROUP BY label
            ),
            ranked AS (
                SELECT label, row_number() OVER (ORDER BY lt DESC) AS rn
                FROM label_totals
            ),
            totals AS (
                SELECT SUM(lt) AS grand_total, COUNT() AS group_count, SUM(le) AS total_events
                FROM label_totals
            )
            SELECT
                if(r.rn > :limit, '__other__', a.label) AS label,
                a.model AS model,
                SUM(a.total_tokens) AS total_tokens,
                SUM(a.definition_tokens) AS definition_tokens,
                SUM(a.usage_tokens) AS usage_tokens,
                SUM(a.events) AS events,
                SUM(a.input_tokens) AS input_tokens,
                SUM(a.cache_read_tokens) AS cache_read_tokens,
                SUM(a.cache_creation_tokens) AS cache_creation_tokens,
                SUM(a.output_tokens) AS output_tokens,
                min(r.rn) AS rank,
                any(t.grand_total) AS grand_total,
                any(t.group_count) AS group_count,
                any(t.total_events) AS total_events
            FROM agg a
            INNER JOIN ranked r ON a.label = r.label
            CROSS JOIN totals t
            GROUP BY label, a.model
            ORDER BY rank ASC, total_tokens DESC
            ;
            """;

    private static final String BREAKDOWN = BREAKDOWN_TEMPLATE.formatted(
            """
                    SELECT JSONExtractString(item, 'name') AS label,
                           JSONExtractString(metadata, 'cc', 'billing', 'model') AS model,
                           SUM(JSONExtractInt(item, 'total')) AS total_tokens,
                           SUMIf(JSONExtractInt(item, 'total'), JSONExtractString(item, 'kind') = 'definition') AS definition_tokens,
                           SUMIf(JSONExtractInt(item, 'total'), JSONExtractString(item, 'kind') = 'usage') AS usage_tokens,
                           SUM(JSONExtractInt(item, 'count')) AS events,
                           SUM(JSONExtractInt(item, 'input')) AS input_tokens,
                           SUM(JSONExtractInt(item, 'cache_read')) AS cache_read_tokens,
                           SUM(JSONExtractInt(item, 'cache_creation')) AS cache_creation_tokens,
                           SUM(JSONExtractInt(item, 'output')) AS output_tokens
                    FROM traces final
                    ARRAY JOIN JSONExtractArrayRaw(metadata, 'cc', 'billing', 'lanes', '<lane_key>', 'items') AS item
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND <range>
                        <if(user_uuid)> AND <user_uuid_expr> = :user_uuid <endif>
                    GROUP BY label, model
                    HAVING label != ''""");

    private static final String OUTPUT_BREAKDOWN = BREAKDOWN_TEMPLATE.formatted("""
            SELECT <lane_label_expr> AS label,
                   model,
                   SUM(item_output) AS total_tokens,
                   toInt64(0) AS definition_tokens,
                   SUM(item_output) AS usage_tokens,
                   SUM(events) AS events,
                   toInt64(0) AS input_tokens,
                   toInt64(0) AS cache_read_tokens,
                   toInt64(0) AS cache_creation_tokens,
                   SUM(item_output) AS output_tokens
            FROM (
            %s
            )
            WHERE <lane_predicate>
            GROUP BY label, model
            HAVING label != ''""".formatted(OUTPUT_ITEMS));

    // One statement: aggregate traces and spans per user into CTEs, LEFT JOIN them, then sort + page in ClickHouse.
    // The window functions run over the full aggregated set before LIMIT, so high_spend (threshold vs the average over
    // all users) and total (COUNT() OVER ()) stay correct under pagination.
    private static final String USERS = """
            WITH
                windowed_traces AS (
                    SELECT
                        id,
                        <user_uuid_expr> AS user_uuid,
                        JSONExtractString(metadata, 'cc', 'identity', 'user_email') AS user_email,
                        JSONExtractString(metadata, 'cc', 'identity', 'user_display_name') AS user_display_name,
                        JSONExtractString(metadata, 'cc', 'billing', 'model') AS model,
                        arraySum(arrayMap(x -> JSONExtractInt(x, 'count'),
                            JSONExtractArrayRaw(metadata, 'cc', 'billing', 'lanes', 'skills', 'items'))) AS skills_loaded,
                        arrayCount(x -> JSONExtractString(x, 'kind') = 'definition',
                            JSONExtractArrayRaw(metadata, 'cc', 'billing', 'lanes', 'mcp_servers', 'items')) AS mcps_available,
                        JSONExtractString(metadata, 'cc', 'git', 'repository') AS repository,
                        <input_expr> AS tok_input,
                        <cache_read_expr> AS tok_cache_read,
                        <cache_creation_expr> AS tok_cache_creation,
                        <output_expr> AS tok_output
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND <range>
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
                        sum(tok_input + tok_cache_read + tok_cache_creation + tok_output) AS total_tokens,
                        -- Per-model tier arrays via sumMap: every sumMap sorts on
                        -- the same model key set, so the value arrays align by index.
                        (sumMap([model], [tok_input])).1 AS models,
                        (sumMap([model], [tok_input])).2 AS model_input,
                        (sumMap([model], [tok_cache_read])).2 AS model_cache_read,
                        (sumMap([model], [tok_cache_creation])).2 AS model_cache_creation,
                        (sumMap([model], [tok_output])).2 AS model_output
                    FROM windowed_traces
                    WHERE user_uuid != ''
                    GROUP BY user_uuid
                ),
                spend_agg AS (
                    SELECT
                        t.user_uuid AS user_uuid,
                        countIf(startsWith(s.name, 'mcp__')) AS mcp_calls
                    FROM spans s final
                    INNER JOIN windowed_traces t ON s.trace_id = t.id
                    WHERE s.workspace_id = :workspace_id
                        AND s.project_id = :project_id
                        AND <span_range>
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
                ta.total_tokens AS total_tokens,
                ta.models AS models,
                ta.model_input AS model_input,
                ta.model_cache_read AS model_cache_read,
                ta.model_cache_creation AS model_cache_creation,
                ta.model_output AS model_output,
                ifNull(sa.mcp_calls, 0) AS mcp_calls,
                toInt64(if(ta.total_tokens >= :high_spend_factor * avg(ta.total_tokens) OVER (), 1, 0)) AS high_spend,
                COUNT() OVER () AS total
            FROM trace_agg ta
            LEFT ANY JOIN spend_agg sa ON ta.user_uuid = sa.user_uuid
            WHERE 1 = 1
                <if(user_name)> AND (ilike(ta.user_email, concat('%', :user_name, '%'))
                    OR ilike(ta.user_display_name, concat('%', :user_name, '%'))) <endif>
            ORDER BY <if(sort_fields)><sort_fields>, <endif>total_tokens DESC
            LIMIT :limit OFFSET :offset
            ;
            """;

    int breakdownItemLimit() {
        return BREAKDOWN_ITEM_LIMIT;
    }

    TemplatedQuery summaryTiers() {
        return new TemplatedQuery(TIER_SUMMARY, st -> {
            st.add("current", CURRENT);
            st.add("previous", PREVIOUS);
            st.add("window", WINDOW);
            addTierExpressions(st);
        });
    }

    TemplatedQuery summaryCounts() {
        return new TemplatedQuery(COUNTS_SUMMARY, st -> {
            st.add("current", CURRENT);
            st.add("previous", PREVIOUS);
            st.add("window", WINDOW);
            st.add("user_uuid_expr", USER_UUID_EXPR);
        });
    }

    TemplatedQuery compositionTokens() {
        return new TemplatedQuery(COMPOSITION_TOKENS, st -> {
            st.add("select_list", COMPOSITION_SELECT_LIST);
            st.add("lanes_type", LANES_TUPLE_TYPE);
            st.add("range", RANGE);
            st.add("user_uuid_expr", USER_UUID_EXPR);
        });
    }

    TemplatedQuery compositionOutput() {
        return new TemplatedQuery(COMPOSITION_OUTPUT, st -> {
            st.add("lane_case", OUTPUT_LANE_CASE);
            st.add("range", RANGE);
            st.add("user_uuid_expr", USER_UUID_EXPR);
        });
    }

    TemplatedQuery breakdown(SpendLane lane) {
        return new TemplatedQuery(BREAKDOWN, st -> {
            st.add("lane_key", lane.getKey());
            st.add("range", RANGE);
            st.add("user_uuid_expr", USER_UUID_EXPR);
        });
    }

    TemplatedQuery outputBreakdown(OutputLane lane) {
        return new TemplatedQuery(OUTPUT_BREAKDOWN, st -> {
            st.add("lane_label_expr", OUTPUT_LANE_LABEL_EXPRESSIONS.get(lane));
            st.add("lane_predicate", OUTPUT_LANE_PREDICATES.get(lane));
            st.add("range", RANGE);
            st.add("user_uuid_expr", USER_UUID_EXPR);
        });
    }

    TemplatedQuery users(List<SortingField> sortingFields) {
        return new TemplatedQuery(USERS, st -> {
            Optional.ofNullable(sortingQueryBuilder.toOrderBySql(sortingFields))
                    .ifPresent(clause -> st.add("sort_fields", clause));
            st.add("user_uuid_expr", USER_UUID_EXPR);
            st.add("range", RANGE);
            st.add("span_range", SPAN_RANGE_ALIASED);
            addTierExpressions(st);
        });
    }

    private static void addTierExpressions(ST st) {
        st.add("input_expr", BILLING_TOTAL_EXPR.formatted("input"));
        st.add("cache_read_expr", BILLING_TOTAL_EXPR.formatted("cache_read"));
        st.add("cache_creation_expr", BILLING_TOTAL_EXPR.formatted("cache_creation"));
        st.add("output_expr", BILLING_TOTAL_EXPR.formatted("output"));
    }
}
