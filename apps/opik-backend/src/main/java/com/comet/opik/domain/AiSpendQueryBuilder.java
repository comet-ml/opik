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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendQueryBuilder {

    /** A query template plus the consumer binding its SQL fragments as ST variables. */
    record TemplatedQuery(String sql, Consumer<ST> fragments) {
    }

    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    // cipx ships identity on the trace under cipx.session.identity (SNORT Item 1);
    // the per-call usage and the blocks array ride on each LLM-call span under
    // cipx.call. Composition/breakdown therefore fan out from spans; identity
    // joins to traces only when filtering by user_uuid or building the leaderboard.

    private static final String CIPX_USER_UUID_EXPR = "JSONExtractString(metadata, 'cipx', 'session', 'identity', 'user_uuid')";

    // Trace-level schema gate. cipx stamps `cipx.session.schema_version` per
    // SNORT Item 6 — v2 is the current break, the BE refuses anything older
    // (returns empty for v1 traces). Spans don't carry this directly; their
    // `JSONHas(cipx.call)` check is equivalent (cipx.call didn't exist in v1).
    private static final String CIPX_TRACE_VERSION_GUARD = "JSONExtractInt(metadata, 'cipx', 'session', 'schema_version') >= 2";

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

    // The spans table is ordered by (workspace_id, project_id, trace_id, parent_span_id, id),
    // so adding trace_id BETWEEN — the per-call span_id and the trace_id are both
    // time-ordered UUIDs from the same id generator — lets ClickHouse prune
    // before reaching the row-level filters.
    private static final String SPAN_RANGE = ID_RANGE
            + " AND trace_id BETWEEN :id_start AND :id_end"
            + " AND " + TS_RANGE;
    private static final String SPAN_WINDOW = ID_WINDOW
            + " AND trace_id BETWEEN :id_prior_start AND :id_end"
            + " AND " + TS_WINDOW;
    private static final String SPAN_CURRENT = ID_CURRENT
            + " AND trace_id BETWEEN :id_start AND :id_end"
            + " AND " + TS_CURRENT;
    private static final String SPAN_PREVIOUS = ID_PREVIOUS
            + " AND trace_id BETWEEN :id_prior_start AND :id_start"
            + " AND " + TS_PREVIOUS;

    private static final int BREAKDOWN_ITEM_LIMIT = 200;

    // Per-call usage counters lift straight out of cipx.call.usage (SNORT Item 2).
    private static final String U_INPUT = "toInt64(JSONExtractInt(metadata, 'cipx', 'call', 'usage', 'input_tokens'))";
    private static final String U_CACHE_READ = "toInt64(JSONExtractInt(metadata, 'cipx', 'call', 'usage', 'cache_read_input_tokens'))";
    private static final String U_CACHE_CREATION = "toInt64(JSONExtractInt(metadata, 'cipx', 'call', 'usage', 'cache_creation_input_tokens'))";
    private static final String U_OUTPUT = "toInt64(JSONExtractInt(metadata, 'cipx', 'call', 'usage', 'output_tokens'))";

    // Category → FE lane projection. Source of truth lives here; the SQL CASE
    // is rendered from it. `tool_io` is split by tool_server presence (Item 4),
    // so it isn't in this map — see laneCaseExpression() below.
    private static final Map<String, String> CATEGORY_TO_LANE = orderedMap()
            // Input lanes
            .put("user_prompts", "user_prompts")
            .put("prior_assistant", "prior_assistant")
            .put("mcp_tools_active", "mcp_servers")
            .put("mcp_tools_deferred", "mcp_servers")
            .put("mcp_server_instructions", "mcp_servers")
            .put("skills_menu", "skills")
            .put("skills_loaded", "skills")
            .put("custom_agents", "custom_agents")
            .put("memory", "memory")
            .put("file_attachments", "file_attachments")
            .put("system_prompt", "static_overhead")
            .put("env_info", "static_overhead")
            .put("system_tools", "static_overhead")
            .put("system_tools_deferred", "static_overhead")
            // Output lanes
            .put("thinking", "thinking")
            .put("assistant_text", "assistant_text")
            .put("built_in_tool_calls", "built_in_tool_calls")
            .put("mcp_tool_calls", "mcp_tool_calls")
            .put("skill_invocations", "skill_invocations")
            .map();

    // ClickHouse multiIf that projects each block's category to its FE lane
    // key. Anything unmapped (identity_context, slash_command, unknown,
    // future categories) falls into 'unattributed' so the residual math
    // stays consistent.
    private static final String LANE_CASE = laneCaseExpression();

    // Per-tier (cache_status × side) labels and matching call.usage column.
    // Output blocks carry no cache_status — they all belong to tier 'output'.
    private static final String TIER_CASE = """
            multiIf(
                side = 'output', 'output',
                side = 'input' AND cache_status = 'read', 'cache_read',
                side = 'input' AND cache_status = 'write', 'cache_creation',
                side = 'input' AND cache_status = 'fresh', 'input',
                ''
            )""";

    private static final String TIER_TOKENS_CASE = """
            multiIf(
                side = 'output', u_output,
                side = 'input' AND cache_status = 'read', u_cache_read,
                side = 'input' AND cache_status = 'write', u_cache_creation,
                side = 'input' AND cache_status = 'fresh', u_input,
                toInt64(0)
            )""";

    // Span fan-out CTEs reused across composition and breakdown. cipx.blocks[]
    // is ARRAY JOINed once; the proportional allocation by chars-share within
    // each (span_id, side, cache_status) tier is what makes per-call usage
    // attributable to per-block lanes. Nested identity_context blocks
    // (parent_category=identity_context) are dropped to avoid double-counting
    // when their parent's chars already include them.
    //
    // The constants that don't change at runtime (usage column expressions,
    // tier CASE, lane CASE) are baked in here at class-init time so the outer
    // query template has a flat single-pass ST substitution to do; ST does
    // not recursively expand attribute values, so nested <foo> tokens inside
    // an attribute string would otherwise survive intact.
    private static final String BLOCKS_FANOUT_CTES = ("""
                spans_in_window AS (
                    SELECT
                        id AS span_id,
                        JSONExtractString(metadata, 'cipx', 'call', 'model') AS model,
                        __U_INPUT__ AS u_input,
                        __U_CACHE_READ__ AS u_cache_read,
                        __U_CACHE_CREATION__ AS u_cache_creation,
                        __U_OUTPUT__ AS u_output,
                        JSONExtractArrayRaw(metadata, 'cipx', 'blocks') AS blocks
                    FROM spans final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND type = 'llm'
                        AND <span_range>
                        AND JSONHas(metadata, 'cipx', 'call')
                        <if(user_uuid)>
                        AND trace_id IN (
                            SELECT id FROM traces final
                            WHERE workspace_id = :workspace_id
                                AND project_id = :project_id
                                AND <range>
                                AND <version_guard>
                                AND <user_uuid_expr> = :user_uuid
                        )
                        <endif>
                ),
                blocks_flat AS (
                    SELECT
                        span_id, model, u_input, u_cache_read, u_cache_creation, u_output,
                        JSONExtractString(block, 'category') AS category,
                        JSONExtractString(block, 'side') AS side,
                        JSONExtractString(block, 'cache_status') AS cache_status,
                        JSONExtractString(block, 'parent_category') AS parent_category,
                        toInt64(JSONExtractInt(block, 'chars')) AS chars,
                        JSONExtractString(block, 'tool_name') AS tool_name,
                        JSONExtractString(block, 'tool_server') AS tool_server,
                        JSONExtractString(block, 'tool_use_id') AS tool_use_id,
                        JSONExtractString(block, 'resource') AS resource,
                        JSONExtractString(block, 'kind') AS kind
                    FROM spans_in_window
                    ARRAY JOIN blocks AS block
                    WHERE NOT (
                        JSONExtractString(block, 'category') = 'identity_context'
                        AND JSONExtractString(block, 'parent_category') = 'identity_context'
                    )
                ),
                labeled AS (
                    SELECT
                        span_id, model, side, cache_status, category, chars,
                        u_input, u_cache_read, u_cache_creation, u_output,
                        tool_name, tool_server, tool_use_id, resource, kind,
                        __TIER_CASE__ AS tier,
                        __TIER_TOKENS_CASE__ AS tier_tokens,
                        __LANE_CASE__ AS lane
                    FROM blocks_flat
                ),
                allocated AS (
                    SELECT
                        span_id, model, side, cache_status, category, lane, tier,
                        tool_name, tool_server, tool_use_id, resource, kind,
                        chars, tier_tokens,
                        SUM(chars) OVER (PARTITION BY span_id, side, cache_status) AS tier_chars
                    FROM labeled
                    WHERE tier != ''
                )
            """)
            .replace("__U_INPUT__", U_INPUT)
            .replace("__U_CACHE_READ__", U_CACHE_READ)
            .replace("__U_CACHE_CREATION__", U_CACHE_CREATION)
            .replace("__U_OUTPUT__", U_OUTPUT)
            .replace("__TIER_CASE__", TIER_CASE)
            .replace("__TIER_TOKENS_CASE__", TIER_TOKENS_CASE)
            .replace("__LANE_CASE__", LANE_CASE);

    // Summary tiers — period-over-period sums of cipx.call.usage per model.
    private static final String TIER_SUMMARY = ("""
            SELECT
                JSONExtractString(metadata, 'cipx', 'call', 'model') AS model,
                SUMIf(__U_INPUT__, <current>) AS input_current,
                SUMIf(__U_CACHE_READ__, <current>) AS cache_read_current,
                SUMIf(__U_CACHE_CREATION__, <current>) AS cache_creation_current,
                SUMIf(__U_OUTPUT__, <current>) AS output_current,
                SUMIf(__U_INPUT__, <previous>) AS input_previous,
                SUMIf(__U_CACHE_READ__, <previous>) AS cache_read_previous,
                SUMIf(__U_CACHE_CREATION__, <previous>) AS cache_creation_previous,
                SUMIf(__U_OUTPUT__, <previous>) AS output_previous
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND type = 'llm'
                AND <span_window>
                AND JSONHas(metadata, 'cipx', 'call')
            GROUP BY model
            ;
            """)
            .replace("__U_INPUT__", U_INPUT)
            .replace("__U_CACHE_READ__", U_CACHE_READ)
            .replace("__U_CACHE_CREATION__", U_CACHE_CREATION)
            .replace("__U_OUTPUT__", U_OUTPUT);

    // Counts summary — messages count LLM-call spans; user counts come from
    // trace-level cipx.session.identity (per-trace by construction).
    private static final String COUNTS_SUMMARY = """
            WITH
                span_counts AS (
                    SELECT
                        countIf(<current>) AS messages_current,
                        countIf(<previous>) AS messages_previous
                    FROM spans final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND type = 'llm'
                        AND <span_window>
                        AND JSONHas(metadata, 'cipx', 'call')
                ),
                user_counts AS (
                    SELECT
                        uniqExactIf(user_uuid, <current> AND user_uuid != '') AS active_users_current,
                        uniqExactIf(user_uuid, <previous> AND user_uuid != '') AS active_users_previous,
                        uniqExactIf(user_uuid, user_uuid != '') AS total_users
                    FROM (
                        SELECT <user_uuid_expr> AS user_uuid, id, start_time
                        FROM traces final
                        WHERE workspace_id = :workspace_id
                            AND project_id = :project_id
                            AND <window>
                            AND <version_guard>
                    )
                )
            SELECT
                s.messages_current AS messages_current,
                s.messages_previous AS messages_previous,
                u.active_users_current AS active_users_current,
                u.active_users_previous AS active_users_previous,
                u.total_users AS total_users
            FROM span_counts s, user_counts u
            ;
            """;

    // Composition — per (lane, model, tier) attributed tokens, plus per-call
    // residual into the 'unattributed' lane for tiers whose call.usage tokens
    // had no labeled blocks to absorb them.
    private static final String COMPOSITION = ("""
            WITH
            __FANOUT_CTES__,
                attributed AS (
                    SELECT
                        lane,
                        model,
                        tier,
                        SUM(if(tier_chars > 0, chars * tier_tokens / tier_chars, 0)) AS tokens
                    FROM allocated
                    GROUP BY lane, model, tier
                ),
                tier_presence AS (
                    SELECT
                        span_id,
                        countIf(side = 'input' AND cache_status = 'fresh') > 0 AS has_input,
                        countIf(side = 'input' AND cache_status = 'read') > 0 AS has_cache_read,
                        countIf(side = 'input' AND cache_status = 'write') > 0 AS has_cache_creation,
                        countIf(side = 'output') > 0 AS has_output
                    FROM blocks_flat
                    GROUP BY span_id
                ),
                residual AS (
                    SELECT 'unattributed' AS lane, model, tier, SUM(unallocated) AS tokens
                    FROM (
                        SELECT
                            s.span_id, s.model,
                            arrayJoin([
                                ('input', if(ifNull(t.has_input, 0) = 0, s.u_input, toInt64(0))),
                                ('cache_read', if(ifNull(t.has_cache_read, 0) = 0, s.u_cache_read, toInt64(0))),
                                ('cache_creation', if(ifNull(t.has_cache_creation, 0) = 0, s.u_cache_creation, toInt64(0))),
                                ('output', if(ifNull(t.has_output, 0) = 0, s.u_output, toInt64(0)))
                            ]) AS tier_unalloc,
                            tier_unalloc.1 AS tier,
                            tier_unalloc.2 AS unallocated
                        FROM spans_in_window s
                        LEFT JOIN tier_presence t ON s.span_id = t.span_id
                    )
                    WHERE unallocated > 0
                    GROUP BY model, tier
                )
            SELECT lane, model, tier, toFloat64(tokens) AS tokens FROM attributed
            UNION ALL
            SELECT lane, model, tier, toFloat64(tokens) AS tokens FROM residual
            ;
            """)
            .replace("__FANOUT_CTES__", BLOCKS_FANOUT_CTES);

    // Per-lane breakdown — one template, parameterized by lane_filter (which
    // blocks belong to the lane), label_expr (the breakdown row key), and
    // def_expr (definition vs usage split for lanes whose registry entry
    // declares definitionSplit). count(events) is the per-(label) sum of
    // usage-half occurrences; definition rows contribute 0 to count.
    private static final String BREAKDOWN_TEMPLATE = ("""
            WITH
            __FANOUT_CTES__,
                rows AS (
                    SELECT
                        <label_expr> AS label,
                        model,
                        <def_expr> AS is_definition,
                        if(tier_chars > 0, chars * tier_tokens / tier_chars, 0) AS alloc,
                        tier
                    FROM allocated
                    WHERE <lane_filter>
                ),
                agg AS (
                    SELECT
                        label,
                        model,
                        SUM(alloc) AS total_tokens,
                        SUMIf(alloc, is_definition = 1) AS definition_tokens,
                        SUMIf(alloc, is_definition = 0) AS usage_tokens,
                        SUMIf(alloc, tier = 'input') AS input_tokens,
                        SUMIf(alloc, tier = 'cache_read') AS cache_read_tokens,
                        SUMIf(alloc, tier = 'cache_creation') AS cache_creation_tokens,
                        SUMIf(alloc, tier = 'output') AS output_tokens,
                        countIf(is_definition = 0) AS events
                    FROM rows
                    GROUP BY label, model
                    HAVING label != ''
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
            """).replace("__FANOUT_CTES__", BLOCKS_FANOUT_CTES);

    // All-lane breakdown — the per-lane BREAKDOWN_TEMPLATE folded over every
    // lane in one pass. The blocks fan-out (the expensive part: read each span's
    // cipx.blocks[], ARRAY JOIN, allocate by chars-share) runs once and feeds
    // every lane, instead of re-scanning the metadata blob once per lane. Each
    // block is projected to its lane/label/is_definition via multiIfs rendered
    // from the same INPUT_LANE_CONFIGS/OUTPUT_LANE_CONFIGS the per-lane queries
    // use, so the projection stays single-source. tier_chars (the allocation
    // denominator) is the full-tier chars over all blocks just as in the
    // per-lane query, so per-lane and combined produce identical allocations.
    private static final String COMBINED_BREAKDOWN_TEMPLATE = ("""
            WITH
            __FANOUT_CTES__,
                rows AS (
                    SELECT
                        <bd_lane> AS lane,
                        <bd_label> AS label,
                        model,
                        <bd_def> AS is_definition,
                        if(tier_chars > 0, chars * tier_tokens / tier_chars, 0) AS alloc,
                        tier
                    FROM allocated
                ),
                agg AS (
                    SELECT
                        lane,
                        label,
                        model,
                        SUM(alloc) AS total_tokens,
                        SUMIf(alloc, is_definition = 1) AS definition_tokens,
                        SUMIf(alloc, is_definition = 0) AS usage_tokens,
                        SUMIf(alloc, tier = 'input') AS input_tokens,
                        SUMIf(alloc, tier = 'cache_read') AS cache_read_tokens,
                        SUMIf(alloc, tier = 'cache_creation') AS cache_creation_tokens,
                        SUMIf(alloc, tier = 'output') AS output_tokens,
                        countIf(is_definition = 0) AS events
                    FROM rows
                    WHERE lane != ''
                    GROUP BY lane, label, model
                    HAVING label != ''
                ),
                label_totals AS (
                    SELECT lane, label, SUM(total_tokens) AS lt, SUM(events) AS le
                    FROM agg
                    GROUP BY lane, label
                ),
                ranked AS (
                    SELECT lane, label, row_number() OVER (PARTITION BY lane ORDER BY lt DESC) AS rn
                    FROM label_totals
                ),
                totals AS (
                    SELECT lane, SUM(lt) AS grand_total, COUNT() AS group_count, SUM(le) AS total_events
                    FROM label_totals
                    GROUP BY lane
                )
            SELECT
                a.lane AS lane,
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
            INNER JOIN ranked r ON a.lane = r.lane AND a.label = r.label
            INNER JOIN totals t ON a.lane = t.lane
            GROUP BY lane, label, a.model
            ORDER BY lane ASC, rank ASC, total_tokens DESC
            ;
            """).replace("__FANOUT_CTES__", BLOCKS_FANOUT_CTES);

    // Users — leaderboard pivots on cipx.session.identity.user_uuid (Item 1).
    // Per-user totals come from cipx.call.usage on LLM-call spans joined to
    // their trace's identity. skills/mcps/mcp_calls derive from cipx.blocks[].
    private static final String USERS = ("""
            WITH
                windowed_traces AS (
                    SELECT
                        id AS trace_id,
                        <user_uuid_expr> AS user_uuid,
                        JSONExtractString(metadata, 'cipx', 'session', 'identity', 'email') AS user_email,
                        JSONExtractString(metadata, 'cipx', 'session', 'identity', 'display_name') AS user_display_name,
                        JSONExtractString(metadata, 'cipx', 'session', 'repository', 'remote') AS repository
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND <range>
                        AND <version_guard>
                ),
                windowed_spans AS (
                    SELECT
                        id AS span_id,
                        trace_id,
                        JSONExtractString(metadata, 'cipx', 'call', 'model') AS model,
                        __U_INPUT__ AS tok_input,
                        __U_CACHE_READ__ AS tok_cache_read,
                        __U_CACHE_CREATION__ AS tok_cache_creation,
                        __U_OUTPUT__ AS tok_output,
                        JSONExtractArrayRaw(metadata, 'cipx', 'blocks') AS blocks
                    FROM spans final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND type = 'llm'
                        AND <span_range>
                        AND JSONHas(metadata, 'cipx', 'call')
                ),
                user_agg AS (
                    -- One row per distinct user_uuid drawn from windowed traces,
                    -- so users with identity-only traces (no LLM spans yet) still
                    -- appear in the leaderboard with zero tokens.
                    SELECT
                        user_uuid,
                        anyLast(user_email) AS user_email,
                        anyLast(user_display_name) AS user_display_name,
                        arrayFilter(x -> x != '', groupUniqArray(repository)) AS repositories
                    FROM windowed_traces
                    WHERE user_uuid != ''
                    GROUP BY user_uuid
                ),
                spans_with_user AS (
                    SELECT t.user_uuid, s.model,
                           s.tok_input, s.tok_cache_read, s.tok_cache_creation, s.tok_output
                    FROM windowed_spans s
                    INNER JOIN windowed_traces t ON s.trace_id = t.trace_id
                    WHERE t.user_uuid != ''
                ),
                span_agg AS (
                    SELECT
                        user_uuid,
                        count() AS requests,
                        sum(tok_input + tok_cache_read + tok_cache_creation + tok_output) AS total_tokens,
                        -- Per-model tier arrays via sumMap: every sumMap sorts on
                        -- the same model key set, so the value arrays align by index.
                        (sumMap([model], [tok_input])).1 AS models,
                        (sumMap([model], [tok_input])).2 AS model_input,
                        (sumMap([model], [tok_cache_read])).2 AS model_cache_read,
                        (sumMap([model], [tok_cache_creation])).2 AS model_cache_creation,
                        (sumMap([model], [tok_output])).2 AS model_output
                    FROM spans_with_user
                    GROUP BY user_uuid
                ),
                block_counts AS (
                    SELECT
                        user_uuid,
                        countIf(category = 'skills_loaded') AS skills,
                        uniqExactIf(tool_server, category = 'mcp_tools_active' AND tool_server != '') AS mcps,
                        countIf(category = 'mcp_tool_calls' AND side = 'output') AS mcp_calls
                    FROM (
                        SELECT
                            t.user_uuid AS user_uuid,
                            JSONExtractString(block, 'category') AS category,
                            JSONExtractString(block, 'side') AS side,
                            JSONExtractString(block, 'tool_server') AS tool_server
                        FROM windowed_spans s
                        INNER JOIN windowed_traces t ON s.trace_id = t.trace_id
                        ARRAY JOIN s.blocks AS block
                        WHERE t.user_uuid != ''
                    )
                    GROUP BY user_uuid
                )
            SELECT
                u.user_uuid AS user_uuid,
                u.user_email AS user_email,
                u.user_display_name AS user_display_name,
                ifNull(sa.requests, 0) AS requests,
                ifNull(b.skills, 0) AS skills,
                ifNull(b.mcps, 0) AS mcps,
                ifNull(b.mcp_calls, 0) AS mcp_calls,
                u.repositories AS repositories,
                ifNull(sa.total_tokens, 0) AS total_tokens,
                ifNull(sa.models, []) AS models,
                ifNull(sa.model_input, []) AS model_input,
                ifNull(sa.model_cache_read, []) AS model_cache_read,
                ifNull(sa.model_cache_creation, []) AS model_cache_creation,
                ifNull(sa.model_output, []) AS model_output,
                toInt64(if(ifNull(sa.total_tokens, 0) >= :high_spend_factor * avg(ifNull(sa.total_tokens, 0)) OVER (), 1, 0)) AS high_spend,
                COUNT() OVER () AS total
            FROM user_agg u
            LEFT ANY JOIN span_agg sa ON u.user_uuid = sa.user_uuid
            LEFT ANY JOIN block_counts b ON u.user_uuid = b.user_uuid
            WHERE 1 = 1
                <if(user_name)> AND (ilike(u.user_email, concat('%', :user_name, '%'))
                    OR ilike(u.user_display_name, concat('%', :user_name, '%'))) <endif>
            ORDER BY <if(sort_fields)><sort_fields>, <endif>total_tokens DESC
            LIMIT :limit OFFSET :offset
            ;
            """)
            .replace("__U_INPUT__", U_INPUT)
            .replace("__U_CACHE_READ__", U_CACHE_READ)
            .replace("__U_CACHE_CREATION__", U_CACHE_CREATION)
            .replace("__U_OUTPUT__", U_OUTPUT);

    int breakdownItemLimit() {
        return BREAKDOWN_ITEM_LIMIT;
    }

    TemplatedQuery summaryTiers() {
        return new TemplatedQuery(TIER_SUMMARY, st -> {
            st.add("current", SPAN_CURRENT);
            st.add("previous", SPAN_PREVIOUS);
            st.add("span_window", SPAN_WINDOW);
        });
    }

    TemplatedQuery summaryCounts() {
        return new TemplatedQuery(COUNTS_SUMMARY, st -> {
            st.add("current", CURRENT);
            st.add("previous", PREVIOUS);
            st.add("window", WINDOW);
            st.add("span_current", SPAN_CURRENT);
            st.add("span_previous", SPAN_PREVIOUS);
            st.add("span_window", SPAN_WINDOW);
            st.add("user_uuid_expr", CIPX_USER_UUID_EXPR);
            st.add("version_guard", CIPX_TRACE_VERSION_GUARD);
        });
    }

    TemplatedQuery composition() {
        return new TemplatedQuery(COMPOSITION, st -> {
            st.add("range", RANGE);
            st.add("span_range", SPAN_RANGE);
            st.add("user_uuid_expr", CIPX_USER_UUID_EXPR);
            st.add("version_guard", CIPX_TRACE_VERSION_GUARD);
        });
    }

    TemplatedQuery breakdown(SpendLane lane) {
        LaneBreakdownConfig config = INPUT_LANE_CONFIGS.get(lane);
        if (config == null) {
            throw new IllegalArgumentException("No breakdown config for lane: " + lane);
        }
        return breakdownQuery(config);
    }

    TemplatedQuery outputBreakdown(OutputLane lane) {
        LaneBreakdownConfig config = OUTPUT_LANE_CONFIGS.get(lane);
        if (config == null) {
            throw new IllegalArgumentException("No breakdown config for lane: " + lane);
        }
        return breakdownQuery(config);
    }

    private TemplatedQuery breakdownQuery(LaneBreakdownConfig config) {
        return new TemplatedQuery(BREAKDOWN_TEMPLATE, st -> {
            st.add("range", RANGE);
            st.add("span_range", SPAN_RANGE);
            st.add("user_uuid_expr", CIPX_USER_UUID_EXPR);
            st.add("version_guard", CIPX_TRACE_VERSION_GUARD);
            st.add("lane_filter", config.filter());
            st.add("label_expr", config.labelExpr());
            st.add("def_expr", config.defExpr());
        });
    }

    TemplatedQuery allBreakdowns() {
        var laneCase = new StringBuilder("multiIf(\n");
        var labelCase = new StringBuilder("multiIf(\n");
        var defCase = new StringBuilder("multiIf(\n");
        for (SpendLane lane : SpendLane.values()) {
            appendLaneCase(laneCase, labelCase, defCase, INPUT_LANE_CONFIGS.get(lane), lane.getKey());
        }
        for (OutputLane lane : OutputLane.values()) {
            appendLaneCase(laneCase, labelCase, defCase, OUTPUT_LANE_CONFIGS.get(lane), lane.getKey());
        }
        laneCase.append("    ''\n)");
        labelCase.append("    ''\n)");
        defCase.append("    toUInt8(0)\n)");
        return new TemplatedQuery(COMBINED_BREAKDOWN_TEMPLATE, st -> {
            st.add("range", RANGE);
            st.add("span_range", SPAN_RANGE);
            st.add("user_uuid_expr", CIPX_USER_UUID_EXPR);
            st.add("version_guard", CIPX_TRACE_VERSION_GUARD);
            st.add("bd_lane", laneCase.toString());
            st.add("bd_label", labelCase.toString());
            st.add("bd_def", defCase.toString());
        });
    }

    private static void appendLaneCase(StringBuilder laneCase, StringBuilder labelCase, StringBuilder defCase,
            LaneBreakdownConfig config, String laneKey) {
        if (config == null) {
            return;
        }
        laneCase.append("    ").append(config.filter()).append(", '").append(laneKey).append("',\n");
        labelCase.append("    ").append(config.filter()).append(", ").append(config.labelExpr()).append(",\n");
        defCase.append("    ").append(config.filter()).append(", ").append(config.defExpr()).append(",\n");
    }

    TemplatedQuery users(List<SortingField> sortingFields) {
        return new TemplatedQuery(USERS, st -> {
            Optional.ofNullable(sortingQueryBuilder.toOrderBySql(sortingFields))
                    .ifPresent(clause -> st.add("sort_fields", clause));
            st.add("user_uuid_expr", CIPX_USER_UUID_EXPR);
            st.add("range", RANGE);
            st.add("span_range", SPAN_RANGE);
            st.add("version_guard", CIPX_TRACE_VERSION_GUARD);
        });
    }

    private static String laneCaseExpression() {
        var sb = new StringBuilder("multiIf(\n");
        // tool_io splits by tool_server presence (mcp_servers usage vs built_in_tools).
        sb.append("    category = 'tool_io' AND tool_server != '', 'mcp_servers',\n");
        sb.append("    category = 'tool_io', 'built_in_tools',\n");
        for (var entry : CATEGORY_TO_LANE.entrySet()) {
            sb.append("    category = '").append(entry.getKey()).append("', '")
                    .append(entry.getValue()).append("',\n");
        }
        sb.append("    'unattributed'\n)");
        return sb.toString();
    }

    private static OrderedMapBuilder orderedMap() {
        return new OrderedMapBuilder();
    }

    private static final class OrderedMapBuilder {
        private final LinkedHashMap<String, String> backing = new LinkedHashMap<>();

        OrderedMapBuilder put(String key, String value) {
            backing.put(key, value);
            return this;
        }

        Map<String, String> map() {
            return Map.copyOf(backing);
        }
    }

    /** SQL fragments that pin a breakdown to one lane's blocks. */
    private record LaneBreakdownConfig(String filter, String labelExpr, String defExpr) {
    }

    private static final Map<SpendLane, LaneBreakdownConfig> INPUT_LANE_CONFIGS = inputLaneConfigs();
    private static final Map<OutputLane, LaneBreakdownConfig> OUTPUT_LANE_CONFIGS = outputLaneConfigs();

    private static Map<SpendLane, LaneBreakdownConfig> inputLaneConfigs() {
        var m = new java.util.EnumMap<SpendLane, LaneBreakdownConfig>(SpendLane.class);

        // user_prompts: bucketed by chars; one prompt block per user turn.
        m.put(SpendLane.USER_PROMPTS, new LaneBreakdownConfig(
                "category = 'user_prompts'",
                """
                        multiIf(
                            chars < 1000, 'small',
                            chars < 10000, 'medium',
                            chars < 100000, 'large',
                            'xlarge'
                        )""",
                "toUInt8(0)"));

        // prior_assistant: split by kind (text / thinking / tool_use / tool_result).
        m.put(SpendLane.PRIOR_ASSISTANT, new LaneBreakdownConfig(
                "category = 'prior_assistant'",
                "kind",
                "toUInt8(0)"));

        // built_in_tools: tool_io blocks for non-MCP tools (Bash, Read, ...).
        m.put(SpendLane.BUILT_IN_TOOLS, new LaneBreakdownConfig(
                "category = 'tool_io' AND tool_server = ''",
                "tool_name",
                "toUInt8(0)"));

        // mcp_servers: definition (mcp_tools_active schemas) + usage
        // (tool_io blocks whose tool_server is non-empty).
        m.put(SpendLane.MCP_SERVERS, new LaneBreakdownConfig(
                "(category = 'mcp_tools_active' OR (category = 'tool_io' AND tool_server != ''))",
                "tool_server",
                "if(category = 'mcp_tools_active', 1, 0)"));

        // skills: definition (skills_menu) + usage (skills_loaded).
        m.put(SpendLane.SKILLS, new LaneBreakdownConfig(
                "category IN ('skills_menu', 'skills_loaded')",
                "resource",
                "if(category = 'skills_menu', 1, 0)"));

        // custom_agents: definition-only (subagent dispatch blurbs).
        m.put(SpendLane.CUSTOM_AGENTS, new LaneBreakdownConfig(
                "category = 'custom_agents'",
                "resource",
                "toUInt8(1)"));

        // memory: per-file resource path; all definition.
        m.put(SpendLane.MEMORY, new LaneBreakdownConfig(
                "category = 'memory'",
                "resource",
                "toUInt8(1)"));

        // file_attachments: per attachment.
        m.put(SpendLane.FILE_ATTACHMENTS, new LaneBreakdownConfig(
                "category = 'file_attachments'",
                "resource",
                "toUInt8(0)"));

        // static_overhead: 4 sub-categories surface as rows.
        m.put(SpendLane.STATIC_OVERHEAD, new LaneBreakdownConfig(
                "category IN ('system_prompt', 'env_info', 'system_tools', 'system_tools_deferred')",
                "category",
                "toUInt8(1)"));

        // unattributed has no breakdown — guarded by SpendLane.hasBreakdown().
        return java.util.Collections.unmodifiableMap(m);
    }

    private static Map<OutputLane, LaneBreakdownConfig> outputLaneConfigs() {
        var m = new java.util.EnumMap<OutputLane, LaneBreakdownConfig>(OutputLane.class);

        // thinking / assistant_text: single-row lanes (label = category).
        m.put(OutputLane.THINKING, new LaneBreakdownConfig(
                "category = 'thinking'",
                "'thinking'",
                "toUInt8(0)"));
        m.put(OutputLane.ASSISTANT_TEXT, new LaneBreakdownConfig(
                "category = 'assistant_text'",
                "'assistant_text'",
                "toUInt8(0)"));

        // built_in_tool_calls: per built-in tool (Bash, Edit, ...).
        m.put(OutputLane.BUILT_IN_TOOL_CALLS, new LaneBreakdownConfig(
                "category = 'built_in_tool_calls'",
                "tool_name",
                "toUInt8(0)"));

        // mcp_tool_calls: per MCP server.
        m.put(OutputLane.MCP_TOOL_CALLS, new LaneBreakdownConfig(
                "category = 'mcp_tool_calls'",
                "tool_server",
                "toUInt8(0)"));

        // skill_invocations: tool_name is always 'Skill'; resource carries
        // the skill name.
        m.put(OutputLane.SKILL_INVOCATIONS, new LaneBreakdownConfig(
                "category = 'skill_invocations'",
                "resource",
                "toUInt8(0)"));

        return java.util.Collections.unmodifiableMap(m);
    }

    // Visible for breakdown filtering — keeps the lane mapping single-source.
    static Map<String, String> categoryToLane() {
        return CATEGORY_TO_LANE;
    }

    /**
     * Mirror of the SQL {@code LANE_CASE} expression in pure Java. Same rules,
     * same precedence, so unit tests can pin the projection without round-
     * tripping through ClickHouse. Any change to the SQL CASE must be made
     * here too — there's a unit test that enforces this.
     */
    static String projectCategoryToLane(String category, String toolServer) {
        if ("tool_io".equals(category)) {
            return (toolServer != null && !toolServer.isEmpty()) ? "mcp_servers" : "built_in_tools";
        }
        return CATEGORY_TO_LANE.getOrDefault(category, "unattributed");
    }
}
