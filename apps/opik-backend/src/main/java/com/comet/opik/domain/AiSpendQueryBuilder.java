package com.comet.opik.domain;

import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendLane;
import jakarta.inject.Singleton;

import java.util.EnumMap;
import java.util.Map;
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

    private static final String SPAN_WINDOW = WINDOW + " AND trace_id BETWEEN :id_prior_start AND :id_end";
    private static final String SPAN_RANGE = RANGE + " AND trace_id BETWEEN :id_start AND :id_end";
    private static final String SPAN_RANGE_ALIASED = RANGE_ALIASED + " AND s.trace_id BETWEEN :id_start AND :id_end";

    private static final int BREAKDOWN_ITEM_LIMIT = 200;

    private static final Map<SpendLane, String> TOKEN_EXPRESSIONS = new EnumMap<>(Map.of(
            SpendLane.PRIOR_ASSISTANT, "JSONExtractInt(cc, 'prior_assistant', 'summary', 'total_tokens')",
            SpendLane.TOOL_RESULTS, "JSONExtractInt(cc, 'tool_results', 'summary', 'total_tokens')",
            SpendLane.USER_PROMPTS, "JSONExtractInt(cc, 'user_prompts', 'summary', 'total_tokens')",
            SpendLane.SKILLS_AVAILABLE, "JSONExtractInt(cc, 'skills', 'summary', 'menu_tokens')",
            SpendLane.SKILLS_LOADED, "JSONExtractInt(cc, 'skills', 'summary', 'loaded_tokens')",
            SpendLane.TOOLS, "JSONExtractInt(cc, 'tools', 'summary', 'schema_tokens')",
            SpendLane.MEMORY, "JSONExtractInt(cc, 'memory', 'summary', 'total_tokens')",
            SpendLane.FILE_ATTACHMENTS, "JSONExtractInt(cc, 'file_attachments', 'summary', 'total_tokens')"));

    private static final Map<SpendLane, BreakdownSpec> BREAKDOWN_SPECS = new EnumMap<>(Map.of(
            SpendLane.TOOL_RESULTS, new BreakdownSpec("'cc', 'tool_results', 'by_tool'", "name", "tokens"),
            SpendLane.SKILLS_AVAILABLE, new BreakdownSpec("'cc', 'skills', 'available'", "name", "menu_tokens"),
            SpendLane.SKILLS_LOADED, new BreakdownSpec("'cc', 'skills', 'loaded'", "name", "body_tokens"),
            SpendLane.MEMORY, new BreakdownSpec("'cc', 'memory', 'files'", "path", "body_tokens"),
            SpendLane.FILE_ATTACHMENTS,
            new BreakdownSpec("'cc', 'file_attachments', 'files'", "content_type", "body_tokens")));

    private static final Map<OutputLane, OutputSpec> OUTPUT_SPECS = new EnumMap<>(Map.of(
            OutputLane.THINKING, new OutputSpec("if(model != '', model, 'Unknown model')",
                    "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'thinking'"),
            OutputLane.ASSISTANT_TEXT, new OutputSpec("if(model != '', model, 'Unknown model')",
                    "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'text'"),
            OutputLane.BUILT_IN_TOOL_CALLS, new OutputSpec("name",
                    "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use'"
                            + " AND NOT startsWith(name, 'mcp__') AND name != 'Skill'"),
            OutputLane.MCP_TOOL_CALLS, new OutputSpec(
                    "if(splitByString('__', name)[2] != '', splitByString('__', name)[2], 'Unknown server')",
                    "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use'"
                            + " AND startsWith(name, 'mcp__')"),
            OutputLane.SKILL_INVOCATIONS, new OutputSpec(
                    "if(JSONExtractString(input, 'skill') != '', JSONExtractString(input, 'skill'), 'Unknown skill')",
                    "JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use' AND name = 'Skill'")));

    private static final String COST_SUMMARY = """
            SELECT
                SUMIf(total_estimated_cost, %1$s) AS spend_current,
                SUMIf(total_estimated_cost, %2$s) AS spend_previous
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %3$s
            ;
            """.formatted(CURRENT, PREVIOUS, SPAN_WINDOW);

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
            FROM (
                SELECT JSONExtractRaw(metadata, 'cc') AS cc
                FROM traces final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND %2$s
                    <if(user_uuid)> AND %3$s = :user_uuid <endif>
            )
            ;
            """;

    private static final String COMPOSITION_OUTPUT_COST = """
            SELECT
                multiIf(
                    block_kind = 'thinking', 'thinking',
                    block_kind = 'text', 'assistant_text',
                    block_kind = 'tool_use' AND startsWith(name, 'mcp__'), 'mcp_tool_calls',
                    block_kind = 'tool_use' AND name = 'Skill', 'skill_invocations',
                    block_kind = 'tool_use', 'built_in_tool_calls',
                    ''
                ) AS lane,
                SUM(output_tokens) AS tokens,
                SUM(cost) AS lane_cost
            FROM (
                SELECT
                    JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') AS block_kind,
                    name,
                    JSONExtractInt(metadata, 'cc', 'llm_call', 'attributed_output_tokens') AS output_tokens,
                    total_estimated_cost AS cost
                FROM spans final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND %1$s
                    <if(user_uuid)> %2$s <endif>
            )
            GROUP BY lane
            ;
            """;

    private static final String BREAKDOWN_ENVELOPE = """
            SELECT label,
                   total_tokens,
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
            SELECT JSONExtractString(item, '%2$s') AS label,
                   SUM(JSONExtractInt(item, '%3$s')) AS total_tokens
            FROM traces final
            ARRAY JOIN JSONExtractArrayRaw(metadata, %1$s) AS item
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %4$s
                <if(user_uuid)> AND %5$s = :user_uuid <endif>
            GROUP BY label
            HAVING label != ''""";

    private static final String TOOLS_BREAKDOWN = """
            SELECT label, SUM(token_value) AS total_tokens
            FROM (
                SELECT label, token_value
                FROM (
                    SELECT
                        JSONExtractArrayRaw(metadata, 'cc', 'tools', 'summary', 'by_server') AS by_server,
                        JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'schema_tokens') AS total_schema
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND %1$s
                        <if(user_uuid)> AND %2$s = :user_uuid <endif>
                )
                ARRAY JOIN
                    arrayPushBack(arrayMap(s -> JSONExtractString(s, 'server'), by_server), 'Built-in tools') AS label,
                    arrayPushBack(arrayMap(s -> JSONExtractInt(s, 'schema_tokens'), by_server),
                        total_schema - arraySum(arrayMap(s -> JSONExtractInt(s, 'schema_tokens'), by_server)))
                        AS token_value
            )
            WHERE label != ''
            GROUP BY label
            HAVING total_tokens > 0""";

    private static final String OUTPUT_BREAKDOWN = """
            SELECT %1$s AS label,
                   SUM(JSONExtractInt(metadata, 'cc', 'llm_call', 'attributed_output_tokens')) AS total_tokens
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %3$s
                AND %2$s
                <if(user_uuid)> %4$s <endif>
            GROUP BY label
            HAVING label != ''""";

    private static final String USERS = """
            WITH
                windowed_traces AS (
                    SELECT
                        id,
                        %1$s AS user_uuid,
                        JSONExtractString(metadata, 'cc', 'identity', 'user_email') AS user_email,
                        JSONExtractString(metadata, 'cc', 'identity', 'user_display_name') AS user_display_name,
                        JSONExtractInt(metadata, 'cc', 'skills', 'summary', 'loaded_count') AS skills_loaded,
                        JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'by_source', 'mcp', 'available_count') AS mcps_available,
                        JSONExtractString(metadata, 'cc', 'git', 'repository') AS repository
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
                        arrayFilter(x -> x != '', groupUniqArray(repository)) AS repositories
                    FROM windowed_traces
                    WHERE user_uuid != ''
                    GROUP BY user_uuid
                ),
                spend_agg AS (
                    SELECT
                        t.user_uuid AS user_uuid,
                        SUM(s.total_estimated_cost) AS spend,
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
                ifNull(sa.spend, 0) AS total_estimated_cost,
                ifNull(sa.mcp_calls, 0) AS mcp_calls,
                sa.model AS model,
                toInt64(if(ifNull(sa.spend, 0) >= :high_spend_factor * avg(ifNull(sa.spend, 0)) OVER (), 1, 0)) AS high_spend,
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

    String summaryCostSql() {
        return COST_SUMMARY;
    }

    String summaryCountsSql() {
        return COUNTS_SUMMARY;
    }

    String compositionTokensSql() {
        String selectList = TOKEN_EXPRESSIONS.entrySet().stream()
                .map(entry -> "SUM(%s) AS %s".formatted(entry.getValue(), entry.getKey().getKey()))
                .collect(Collectors.joining(",\n    "));
        return COMPOSITION_TOKENS.formatted(selectList, RANGE, USER_UUID_EXPR);
    }

    String compositionOutputCostSql() {
        return COMPOSITION_OUTPUT_COST.formatted(SPAN_RANGE, spanUserFilter());
    }

    String breakdownSql(SpendLane lane) {
        String inner = lane == SpendLane.TOOLS
                ? TOOLS_BREAKDOWN.formatted(RANGE, USER_UUID_EXPR)
                : breakdownInner(lane);
        return BREAKDOWN_ENVELOPE.formatted(inner);
    }

    String outputBreakdownSql(OutputLane lane) {
        OutputSpec spec = OUTPUT_SPECS.get(lane);
        String inner = OUTPUT_BREAKDOWN.formatted(spec.labelExpr(), spec.predicate(), SPAN_RANGE, spanUserFilter());
        return BREAKDOWN_ENVELOPE.formatted(inner);
    }

    String usersSql(String orderBy) {
        return USERS.formatted(USER_UUID_EXPR, RANGE, SPAN_RANGE_ALIASED, orderBy);
    }

    private String breakdownInner(SpendLane lane) {
        BreakdownSpec spec = BREAKDOWN_SPECS.get(lane);
        return BREAKDOWN.formatted(spec.arrayPath(), spec.labelField(), spec.tokenField(), RANGE, USER_UUID_EXPR);
    }

    private String spanUserFilter() {
        return """
                 AND trace_id IN (
                    SELECT id FROM traces final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND %1$s
                        AND %2$s = :user_uuid
                )""".formatted(RANGE, USER_UUID_EXPR);
    }

    private record BreakdownSpec(String arrayPath, String labelField, String tokenField) {
    }

    private record OutputSpec(String labelExpr, String predicate) {
    }
}
