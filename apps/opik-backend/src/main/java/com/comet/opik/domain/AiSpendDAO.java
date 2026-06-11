package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendUserPage;
import com.comet.opik.api.spend.SpendUserRow;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(AiSpendDAOImpl.class)
public interface AiSpendDAO {

    Mono<List<WorkspaceMetricsSummaryResponse.Result>> getSummary(SpendMetricRequest request);

    Mono<SpendCompositionResponse> getComposition(SpendMetricRequest request);

    Mono<SpendBreakdownResponse> getBreakdown(SpendMetricRequest request, SpendLane lane);

    Mono<SpendBreakdownResponse> getOutputBreakdown(SpendMetricRequest request, OutputLane lane);

    Mono<SpendUserPage> getUsers(SpendMetricRequest request, List<SortingField> sortingFields, String name, int page,
            int size);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendDAOImpl implements AiSpendDAO {

    private static final String USER_UUID_EXPR = "JSONExtractString(metadata, 'cc', 'identity', 'user_uuid')";
    private static final String TRACE_USER_FILTER = " AND " + USER_UUID_EXPR + " = :user_uuid";

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

    // The UUIDv7 id is the time-ordered tail of the (workspace_id, project_id, id) sort key: an id range prunes
    // granules via the primary-key index, while start_time gives the exact window boundary. Mirrors
    // WorkspaceMetricsDAO/ProjectMetricsDAO; without the id range every query full-scans the project partition.
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

    private static final int BREAKDOWN_ITEM_LIMIT = 200;

    private static final String OUTPUT_LANE_EXPR = """
            multiIf(
                JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'thinking', 'thinking',
                JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'text', 'assistant_text',
                JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use' AND startsWith(name, 'mcp__'), 'mcp_tool_calls',
                JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use' AND name = 'Skill', 'skill_invocations',
                JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') = 'tool_use', 'built_in_tool_calls',
                ''
            )""";

    private static final String COST_SUMMARY = """
            SELECT
                SUMIf(total_estimated_cost, %1$s) AS spend_current,
                SUMIf(total_estimated_cost, %2$s) AS spend_previous
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %3$s
            ;
            """.formatted(CURRENT, PREVIOUS, WINDOW);

    private static final String COUNTS_SUMMARY = """
            SELECT
                countIf(%2$s) AS messages_current,
                countIf(%3$s) AS messages_previous,
                uniqExactIf(%1$s, %2$s AND %1$s != '') AS active_users_current,
                uniqExactIf(%1$s, %3$s AND %1$s != '') AS active_users_previous,
                uniqExactIf(%1$s, %1$s != '') AS total_users
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %4$s
            ;
            """.formatted(USER_UUID_EXPR, CURRENT, PREVIOUS, WINDOW);

    private static final String COMPOSITION_TOKENS = """
            SELECT
                %1$s
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %2$s
                %3$s
            ;
            """;

    private static final String COMPOSITION_OUTPUT = """
            SELECT %1$s AS lane,
                   SUM(JSONExtractInt(metadata, 'cc', 'llm_call', 'attributed_output_tokens')) AS tokens
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %2$s
                AND JSONExtractString(metadata, 'cc', 'llm_call', 'block_kind') != ''
                %3$s
            GROUP BY lane
            HAVING lane != ''
            ;
            """;

    private static final String COMPOSITION_COST = """
            SELECT SUM(total_estimated_cost) AS total_cost
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND %1$s
                %2$s
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
                %5$s
            GROUP BY label
            HAVING label != ''""";

    private static final String TOOLS_BREAKDOWN = """
            SELECT label, SUM(token_value) AS total_tokens
            FROM (
                SELECT JSONExtractString(item, 'server') AS label,
                       JSONExtractInt(item, 'schema_tokens') AS token_value
                FROM traces final
                ARRAY JOIN JSONExtractArrayRaw(metadata, 'cc', 'tools', 'summary', 'by_server') AS item
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND %1$s
                    %2$s
                UNION ALL
                SELECT 'Built-in tools' AS label,
                       JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'schema_tokens')
                           - arraySum(arrayMap(x -> JSONExtractInt(x, 'schema_tokens'),
                               JSONExtractArrayRaw(metadata, 'cc', 'tools', 'summary', 'by_server'))) AS token_value
                FROM traces final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND %1$s
                    %2$s
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
                %4$s
            GROUP BY label""";

    private static final double HIGH_SPEND_FACTOR = 2.0;

    // One statement: aggregate traces and spans per user into CTEs, LEFT JOIN them, then sort + page in ClickHouse.
    // The window functions run over the full aggregated set before LIMIT, so high_spend (threshold vs the average over
    // all users) and total (COUNT() OVER ()) stay correct under pagination. %1$s=user_uuid expr, %2$s=range (traces),
    // %3$s=range (spans alias), %4$s=order by. Mirrors TraceDAO.find's <sort_fields>-then-default ORDER BY.
    private static final String USERS = """
            WITH
                trace_agg AS (
                    SELECT
                        user_uuid,
                        anyLast(user_email) AS user_email,
                        anyLast(user_display_name) AS user_display_name,
                        count() AS requests,
                        sum(skills_loaded) AS skills,
                        max(mcps_available) AS mcps,
                        arrayFilter(x -> x != '', groupUniqArray(repository)) AS repositories
                    FROM (
                        SELECT
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
                    )
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
                    INNER JOIN (
                        SELECT id, %1$s AS user_uuid
                        FROM traces final
                        WHERE workspace_id = :workspace_id
                            AND project_id = :project_id
                            AND %2$s
                    ) t ON s.trace_id = t.id
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
            LEFT JOIN spend_agg sa ON ta.user_uuid = sa.user_uuid
            WHERE 1 = 1 %5$s
            ORDER BY %4$s
            LIMIT :limit OFFSET :offset
            ;
            """;

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    @Override
    public Mono<List<WorkspaceMetricsSummaryResponse.Result>> getSummary(@NonNull SpendMetricRequest request) {
        Mono<CostRow> cost = querySingle(COST_SUMMARY, "ai_spend_summary_cost", request.resolvedProjectId(),
                statement -> bindSummaryRange(statement, request),
                (row, metadata) -> new CostRow(
                        filterNan(row.get("spend_current", Double.class)),
                        filterNan(row.get("spend_previous", Double.class))),
                new CostRow(null, null));

        Mono<CountsRow> counts = querySingle(COUNTS_SUMMARY, "ai_spend_summary_counts", request.resolvedProjectId(),
                statement -> bindSummaryRange(statement, request),
                (row, metadata) -> new CountsRow(
                        row.get("messages_current", Long.class),
                        row.get("messages_previous", Long.class),
                        row.get("active_users_current", Long.class),
                        row.get("active_users_previous", Long.class),
                        row.get("total_users", Long.class)),
                new CountsRow(0L, 0L, 0L, 0L, 0L));

        return Mono.zip(cost, counts).map(tuple -> buildSummaryResults(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<SpendCompositionResponse> getComposition(@NonNull SpendMetricRequest request) {
        Optional<String> userUuid = resolveUserUuid(request);

        String selectList = List.of(SpendLane.values()).stream()
                .map(lane -> "SUM(%s) AS %s".formatted(lane.getTokenExpression(), lane.getKey()))
                .collect(Collectors.joining(",\n    "));
        String tokensSql = COMPOSITION_TOKENS.formatted(selectList, RANGE,
                userUuid.map(value -> TRACE_USER_FILTER).orElse(""));

        Mono<Map<SpendLane, Long>> inputTokens = querySingle(tokensSql, "ai_spend_composition_tokens",
                request.resolvedProjectId(),
                statement -> bindComposition(statement, request, userUuid),
                (row, metadata) -> {
                    Map<SpendLane, Long> laneTokens = new EnumMap<>(SpendLane.class);
                    for (SpendLane lane : SpendLane.values()) {
                        laneTokens.put(lane, getLong(row.get(lane.getKey(), Long.class)));
                    }
                    return laneTokens;
                },
                new EnumMap<>(SpendLane.class));

        String outputSql = COMPOSITION_OUTPUT.formatted(OUTPUT_LANE_EXPR, RANGE, spanUserFilter(userUuid));
        Mono<Map<String, Long>> outputTokens = queryList(outputSql, "ai_spend_composition_output",
                request.resolvedProjectId(),
                statement -> bindComposition(statement, request, userUuid),
                (row, metadata) -> Map.entry(row.get("lane", String.class), getLong(row.get("tokens", Long.class))))
                .map(entries -> entries.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

        String costSql = COMPOSITION_COST.formatted(RANGE, spanUserFilter(userUuid));
        Mono<BigDecimal> cost = querySingle(costSql, "ai_spend_composition_cost", request.resolvedProjectId(),
                statement -> bindComposition(statement, request, userUuid),
                (row, metadata) -> Optional.ofNullable(row.get("total_cost", BigDecimal.class)).orElse(BigDecimal.ZERO),
                BigDecimal.ZERO);

        return Mono.zip(inputTokens, outputTokens, cost)
                .map(tuple -> buildComposition(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    @Override
    public Mono<SpendBreakdownResponse> getBreakdown(@NonNull SpendMetricRequest request, @NonNull SpendLane lane) {
        Optional<String> userUuid = resolveUserUuid(request);
        String userFilter = userUuid.map(value -> TRACE_USER_FILTER).orElse("");
        String inner = lane == SpendLane.TOOLS
                ? TOOLS_BREAKDOWN.formatted(RANGE, userFilter)
                : BREAKDOWN.formatted(lane.getBreakdownArrayPath(), lane.getBreakdownLabelField(),
                        lane.getBreakdownTokenField(), RANGE, userFilter);

        return runBreakdown(BREAKDOWN_ENVELOPE.formatted(inner), "ai_spend_breakdown", lane.getKey(),
                lane.getLabel(), lane.getBreakdownSubtitle(), request, userUuid);
    }

    @Override
    public Mono<SpendBreakdownResponse> getOutputBreakdown(@NonNull SpendMetricRequest request,
            @NonNull OutputLane lane) {
        Optional<String> userUuid = resolveUserUuid(request);
        String inner = OUTPUT_BREAKDOWN.formatted(lane.getBreakdownLabelExpr(), lane.getBreakdownPredicate(),
                RANGE, spanUserFilter(userUuid));

        return runBreakdown(BREAKDOWN_ENVELOPE.formatted(inner), "ai_spend_output_breakdown", lane.getKey(),
                lane.getLabel(), lane.getBreakdownSubtitle(), request, userUuid);
    }

    private Mono<SpendBreakdownResponse> runBreakdown(String sql, String queryName, String laneKey, String title,
            String subtitle, SpendMetricRequest request, Optional<String> userUuid) {
        return queryList(sql, queryName, laneKey,
                statement -> {
                    bindComposition(statement, request, userUuid);
                    statement.bind("limit", BREAKDOWN_ITEM_LIMIT);
                },
                (row, metadata) -> new BreakdownRow(
                        row.get("label", String.class),
                        getLong(row.get("total_tokens", Long.class)),
                        getLong(row.get("grand_total", Long.class)),
                        getLong(row.get("group_count", Long.class))))
                .map(rows -> buildBreakdown(laneKey, title, subtitle, rows));
    }

    private SpendBreakdownResponse buildBreakdown(String laneKey, String title, String subtitle,
            List<BreakdownRow> rows) {
        List<SpendBreakdownResponse.Item> items = rows.stream()
                .map(row -> SpendBreakdownResponse.Item.builder()
                        .label(row.label())
                        .totalTokens(row.totalTokens())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));

        long grandTotal = rows.isEmpty() ? 0L : rows.getFirst().grandTotal();
        long groupCount = rows.isEmpty() ? 0L : rows.getFirst().groupCount();
        long hidden = groupCount - items.size();
        if (hidden > 0) {
            long shown = items.stream().mapToLong(SpendBreakdownResponse.Item::totalTokens).sum();
            items.add(SpendBreakdownResponse.Item.builder()
                    .label("Other (%d items)".formatted(hidden))
                    .totalTokens(grandTotal - shown)
                    .build());
        }

        return SpendBreakdownResponse.builder()
                .laneKey(laneKey)
                .title(title)
                .subtitle(subtitle)
                .totalTokens(grandTotal)
                .itemCount((int) groupCount)
                .items(items)
                .build();
    }

    @Override
    public Mono<SpendUserPage> getUsers(@NonNull SpendMetricRequest request,
            @NonNull List<SortingField> sortingFields, String name, int page, int size) {
        String orderBy = Optional.ofNullable(sortingQueryBuilder.toOrderBySql(sortingFields))
                .map(clause -> clause + ", total_estimated_cost DESC")
                .orElse("total_estimated_cost DESC");
        String sql = USERS.formatted(USER_UUID_EXPR, RANGE, RANGE_ALIASED, orderBy, userNameFilter(name));
        long offset = (long) (page - 1) * size;

        return queryList(sql, "ai_spend_users", request.resolvedProjectId(),
                statement -> {
                    bindRange(statement, request);
                    statement
                            .bind("high_spend_factor", HIGH_SPEND_FACTOR)
                            .bind("limit", size)
                            .bind("offset", offset);
                    if (name != null && !name.isBlank()) {
                        statement.bind("user_name", name);
                    }
                },
                (row, metadata) -> new LeaderboardRow(
                        SpendUserRow.builder()
                                .userUuid(row.get("user_uuid", String.class))
                                .userEmail(row.get("user_email", String.class))
                                .userDisplayName(row.get("user_display_name", String.class))
                                .requests(getLong(row.get("requests", Long.class)))
                                .skills(getLong(row.get("skills", Long.class)))
                                .mcps(getLong(row.get("mcps", Long.class)))
                                .repositories(toList(row.get("repositories", String[].class)))
                                .totalEstimatedCost(toBigDecimal(row.get("total_estimated_cost", Double.class)))
                                .mcpCalls(getLong(row.get("mcp_calls", Long.class)))
                                .model(row.get("model", String.class))
                                .flags(getLong(row.get("high_spend", Long.class)) == 1L
                                        ? List.of("high_spend")
                                        : List.of())
                                .build(),
                        getLong(row.get("total", Long.class))))
                .map(rows -> SpendUserPage.builder()
                        .page(page)
                        .size(rows.size())
                        .total(rows.isEmpty() ? 0L : rows.getFirst().total())
                        .content(rows.stream().map(LeaderboardRow::user).toList())
                        .build());
    }

    private <T> Mono<List<T>> queryList(String sql, String queryName, Object details, Consumer<Statement> bind,
            BiFunction<Row, RowMetadata, T> mapper) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var statement = createStatement(connection, sql, queryName, workspaceId, userName, details);
            bind.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map(mapper))
                    .collectList();
        }));
    }

    private <T> Mono<T> querySingle(String sql, String queryName, Object details, Consumer<Statement> bind,
            BiFunction<Row, RowMetadata, T> mapper, T defaultValue) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var statement = createStatement(connection, sql, queryName, workspaceId, userName, details);
            bind.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map(mapper))
                    .single(defaultValue);
        }));
    }

    private Statement createStatement(Connection connection, String sql, String queryName, String workspaceId,
            String userName, Object details) {
        var query = getSTWithLogComment(withLogComment(sql), queryName, workspaceId, userName, details).render();
        return connection.createStatement(query).bind("workspace_id", workspaceId);
    }

    // The query constants render through StringTemplate, so append the standard log_comment SETTINGS (resolved by
    // getSTWithLogComment) before the trailing ';' to keep ClickHouse query attribution, as the other DAOs do.
    private static String withLogComment(String sql) {
        var trimmed = sql.stripTrailing();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed + "\nSETTINGS log_comment = '<log_comment>'";
    }

    private String spanUserFilter(Optional<String> userUuid) {
        if (userUuid.isEmpty()) {
            return "";
        }
        return """
                 AND trace_id IN (
                    SELECT id FROM traces final
                    WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        AND %1$s
                        AND %2$s = :user_uuid
                )""".formatted(RANGE, USER_UUID_EXPR);
    }

    private String userNameFilter(String name) {
        return name == null || name.isBlank()
                ? ""
                : " AND (ilike(ta.user_email, concat('%', :user_name, '%'))"
                        + " OR ilike(ta.user_display_name, concat('%', :user_name, '%')))";
    }

    private void bindRange(Statement statement, SpendMetricRequest request) {
        statement
                .bind("project_id", request.resolvedProjectId().toString())
                .bind("ts_start", request.intervalStart().toString())
                .bind("ts_end", request.intervalEnd().toString())
                .bind("id_start", idGenerator.getTimeOrderedEpoch(request.intervalStart().toEpochMilli()))
                .bind("id_end", idGenerator.getTimeOrderedEpoch(request.intervalEnd().toEpochMilli()));
    }

    private void bindSummaryRange(Statement statement, SpendMetricRequest request) {
        Instant priorStart = getPriorStart(request.intervalStart(), request.intervalEnd());
        bindRange(statement, request);
        statement
                .bind("ts_prior_start", priorStart.toString())
                .bind("id_prior_start", idGenerator.getTimeOrderedEpoch(priorStart.toEpochMilli()));
    }

    private void bindComposition(Statement statement, SpendMetricRequest request, Optional<String> userUuid) {
        bindRange(statement, request);
        userUuid.ifPresent(value -> statement.bind("user_uuid", value));
    }

    private Optional<String> resolveUserUuid(SpendMetricRequest request) {
        return Optional.ofNullable(request.userId()).filter(value -> !value.isBlank());
    }

    private List<WorkspaceMetricsSummaryResponse.Result> buildSummaryResults(CostRow cost, CountsRow counts) {
        return List.of(
                result("total_spend", cost.current(), cost.previous()),
                result("total_messages", toDouble(counts.messagesCurrent()), toDouble(counts.messagesPrevious())),
                result("avg_cost_per_user", average(cost.current(), counts.activeUsersCurrent()),
                        average(cost.previous(), counts.activeUsersPrevious())),
                result("active_users", toDouble(counts.activeUsersCurrent()), null),
                result("total_users", toDouble(counts.totalUsers()), null));
    }

    private SpendCompositionResponse buildComposition(Map<SpendLane, Long> inputTokens, Map<String, Long> outputTokens,
            BigDecimal totalCost) {
        List<SpendCompositionResponse.Lane> inputLanes = new ArrayList<>();
        long inputTotal = 0L;
        for (SpendLane lane : SpendLane.values()) {
            long laneTokens = inputTokens.getOrDefault(lane, 0L);
            inputLanes.add(SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTokens)
                    .hasBreakdown(lane.hasBreakdown())
                    .build());
            inputTotal += laneTokens;
        }

        List<SpendCompositionResponse.Lane> outputLanes = new ArrayList<>();
        long outputTotal = 0L;
        for (OutputLane lane : OutputLane.values()) {
            long laneTokens = outputTokens.getOrDefault(lane.getKey(), 0L);
            outputLanes.add(SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTokens)
                    .hasBreakdown(lane.hasBreakdown())
                    .build());
            outputTotal += laneTokens;
        }

        return SpendCompositionResponse.builder()
                .input(SpendCompositionResponse.Side.builder().totalTokens(inputTotal).lanes(inputLanes).build())
                .output(SpendCompositionResponse.Side.builder().totalTokens(outputTotal).lanes(outputLanes).build())
                .harness(List.of(SpendCompositionResponse.HarnessEntry.builder()
                        .key("claude_code")
                        .label("Claude Code")
                        .totalEstimatedCost(totalCost)
                        .build()))
                .build();
    }

    private WorkspaceMetricsSummaryResponse.Result result(String name, Double current, Double previous) {
        return WorkspaceMetricsSummaryResponse.Result.builder().name(name).current(current).previous(previous).build();
    }

    private Double average(Double total, Long count) {
        return total == null || count == null || count == 0L ? null : total / count;
    }

    private Double toDouble(Long value) {
        return value == null ? null : value.doubleValue();
    }

    private long getLong(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal toBigDecimal(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private List<String> toList(String[] values) {
        return values == null ? List.of() : List.of(values);
    }

    private Instant getPriorStart(Instant intervalStart, Instant intervalEnd) {
        return intervalStart.minus(Duration.between(intervalStart, intervalEnd));
    }

    private Double filterNan(Double value) {
        return value == null || value.isNaN() ? null : value;
    }

    private record BreakdownRow(String label, long totalTokens, long grandTotal, long groupCount) {
    }

    private record LeaderboardRow(SpendUserRow user, long total) {
    }

    private record CostRow(Double current, Double previous) {
    }

    private record CountsRow(Long messagesCurrent, Long messagesPrevious, Long activeUsersCurrent,
            Long activeUsersPrevious, Long totalUsers) {
    }
}
