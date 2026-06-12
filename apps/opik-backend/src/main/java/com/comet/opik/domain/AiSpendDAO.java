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
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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

    private static final Consumer<ST> NO_TEMPLATE = st -> {
    };

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull AiSpendQueryBuilder queryBuilder;
    private final @NonNull AiSpendMapper mapper;

    @Override
    public Mono<List<WorkspaceMetricsSummaryResponse.Result>> getSummary(@NonNull SpendMetricRequest request) {
        Mono<AiSpendMapper.CostRow> cost = querySingle(queryBuilder.summaryCostSql(), "ai_spend_summary_cost",
                request.resolvedProjectId(), NO_TEMPLATE,
                statement -> bindSummaryRange(statement, request),
                (row, metadata) -> new AiSpendMapper.CostRow(
                        row.get("spend_current", BigDecimal.class),
                        row.get("spend_previous", BigDecimal.class),
                        row.get("output_tokens_current", Long.class),
                        row.get("output_tokens_previous", Long.class)),
                AiSpendMapper.CostRow.empty());

        Mono<AiSpendMapper.CountsRow> counts = querySingle(queryBuilder.summaryCountsSql(), "ai_spend_summary_counts",
                request.resolvedProjectId(), NO_TEMPLATE,
                statement -> bindSummaryRange(statement, request),
                (row, metadata) -> new AiSpendMapper.CountsRow(
                        row.get("messages_current", Long.class),
                        row.get("messages_previous", Long.class),
                        row.get("active_users_current", Long.class),
                        row.get("active_users_previous", Long.class),
                        row.get("total_users", Long.class),
                        row.get("input_tokens_current", Long.class),
                        row.get("input_tokens_previous", Long.class)),
                AiSpendMapper.CountsRow.empty());

        return Mono.zip(cost, counts).map(tuple -> mapper.summaryResults(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<SpendCompositionResponse> getComposition(@NonNull SpendMetricRequest request) {
        Optional<String> userUuid = resolveUserUuid(request);

        Mono<Map<SpendLane, Long>> inputTokens = querySingle(queryBuilder.compositionTokensSql(),
                "ai_spend_composition_tokens", request.resolvedProjectId(), userFlag(userUuid),
                statement -> bindComposition(statement, request, userUuid),
                (row, metadata) -> {
                    Map<SpendLane, Long> laneTokens = new EnumMap<>(SpendLane.class);
                    for (SpendLane lane : SpendLane.values()) {
                        laneTokens.put(lane, getLong(row.get(lane.getKey(), Long.class)));
                    }
                    return laneTokens;
                },
                new EnumMap<>(SpendLane.class));

        Mono<AiSpendMapper.OutputCost> outputCost = queryList(queryBuilder.compositionOutputCostSql(),
                "ai_spend_composition_output_cost", request.resolvedProjectId(), userFlag(userUuid),
                statement -> bindComposition(statement, request, userUuid),
                (row, metadata) -> new AiSpendMapper.OutputLaneCost(
                        row.get("lane", String.class),
                        getLong(row.get("tokens", Long.class)),
                        Optional.ofNullable(row.get("lane_cost", BigDecimal.class)).orElse(BigDecimal.ZERO)))
                .map(mapper::outputCost);

        return Mono.zip(inputTokens, outputCost)
                .map(tuple -> mapper.composition(tuple.getT1(), tuple.getT2().tokens(), tuple.getT2().cost()));
    }

    @Override
    public Mono<SpendBreakdownResponse> getBreakdown(@NonNull SpendMetricRequest request, @NonNull SpendLane lane) {
        Optional<String> userUuid = resolveUserUuid(request);
        return runBreakdown(queryBuilder.breakdownSql(lane), "ai_spend_breakdown", lane.getKey(),
                lane.getLabel(), lane.getBreakdownSubtitle(), request, userUuid);
    }

    @Override
    public Mono<SpendBreakdownResponse> getOutputBreakdown(@NonNull SpendMetricRequest request,
            @NonNull OutputLane lane) {
        Optional<String> userUuid = resolveUserUuid(request);
        return runBreakdown(queryBuilder.outputBreakdownSql(lane), "ai_spend_output_breakdown", lane.getKey(),
                lane.getLabel(), lane.getBreakdownSubtitle(), request, userUuid);
    }

    private Mono<SpendBreakdownResponse> runBreakdown(String sql, String queryName, String laneKey, String title,
            String subtitle, SpendMetricRequest request, Optional<String> userUuid) {
        return queryList(sql, queryName, laneKey, userFlag(userUuid),
                statement -> {
                    bindComposition(statement, request, userUuid);
                    statement.bind("limit", queryBuilder.breakdownItemLimit());
                },
                (row, metadata) -> new AiSpendMapper.BreakdownRow(
                        row.get("label", String.class),
                        getLong(row.get("total_tokens", Long.class)),
                        getLong(row.get("grand_total", Long.class)),
                        getLong(row.get("group_count", Long.class))))
                .map(rows -> mapper.breakdown(laneKey, title, subtitle, rows));
    }

    @Override
    public Mono<SpendUserPage> getUsers(@NonNull SpendMetricRequest request,
            @NonNull List<SortingField> sortingFields, String name, int page, int size) {
        String orderBy = Optional.ofNullable(sortingQueryBuilder.toOrderBySql(sortingFields))
                .map(clause -> clause + ", total_estimated_cost DESC")
                .orElse("total_estimated_cost DESC");
        boolean hasName = StringUtils.isNotBlank(name);
        long offset = (long) (page - 1) * size;

        return queryList(queryBuilder.usersSql(orderBy), "ai_spend_users", request.resolvedProjectId(),
                st -> {
                    if (hasName) {
                        st.add("user_name", true);
                    }
                },
                statement -> {
                    bindRange(statement, request);
                    statement
                            .bind("high_spend_factor", mapper.highSpendFactor())
                            .bind("limit", size)
                            .bind("offset", offset);
                    if (hasName) {
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
                                .totalEstimatedCost(row.get("total_estimated_cost", BigDecimal.class))
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

    private <T> Mono<List<T>> queryList(String sql, String queryName, Object details, Consumer<ST> templating,
            Consumer<Statement> bind, BiFunction<Row, RowMetadata, T> mapper) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var statement = createStatement(connection, sql, queryName, workspaceId, userName, details, templating);
            bind.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map(mapper))
                    .collectList();
        }));
    }

    private <T> Mono<T> querySingle(String sql, String queryName, Object details, Consumer<ST> templating,
            Consumer<Statement> bind, BiFunction<Row, RowMetadata, T> mapper, T defaultValue) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var statement = createStatement(connection, sql, queryName, workspaceId, userName, details, templating);
            bind.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map(mapper))
                    .single(defaultValue);
        }));
    }

    private Statement createStatement(Connection connection, String sql, String queryName, String workspaceId,
            String userName, Object details, Consumer<ST> templating) {
        var st = getSTWithLogComment(withLogComment(sql), queryName, workspaceId, userName, details);
        templating.accept(st);
        return connection.createStatement(st.render()).bind("workspace_id", workspaceId);
    }

    private static String withLogComment(String sql) {
        var trimmed = sql.stripTrailing();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed + "\nSETTINGS log_comment = '<log_comment>'";
    }

    private static Consumer<ST> userFlag(Optional<String> userUuid) {
        return st -> userUuid.ifPresent(value -> st.add("user_uuid", true));
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
        return Optional.ofNullable(request.userId()).filter(StringUtils::isNotBlank);
    }

    private long getLong(Long value) {
        return value == null ? 0L : value;
    }

    private List<String> toList(String[] values) {
        return values == null ? List.of() : List.of(values);
    }

    private Instant getPriorStart(Instant intervalStart, Instant intervalEnd) {
        return intervalStart.minus(Duration.between(intervalStart, intervalEnd));
    }

    private record LeaderboardRow(SpendUserRow user, long total) {
    }
}
