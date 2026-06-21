package com.comet.opik.domain;

import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.spend.ModelTiers;
import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendSummaryResponse;
import com.comet.opik.api.spend.SpendUserPage;
import com.comet.opik.api.spend.SpendUserRow;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

    Mono<SpendSummaryResponse> getSummary(SpendMetricRequest request);

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
    private final @NonNull AiSpendQueryBuilder queryBuilder;
    private final @NonNull AiSpendMapper mapper;

    @Override
    public Mono<SpendSummaryResponse> getSummary(@NonNull SpendMetricRequest request) {
        Mono<List<AiSpendMapper.SummaryTierRow>> tiers = queryList(queryBuilder.summaryTiers(),
                "ai_spend_summary_tiers", request.resolvedProjectId(), NO_TEMPLATE,
                statement -> bindSummaryRange(statement, request),
                (row, metadata) -> new AiSpendMapper.SummaryTierRow(
                        row.get("model", String.class),
                        getLong(row.get("input_current", Long.class)),
                        getLong(row.get("cache_read_current", Long.class)),
                        getLong(row.get("cache_creation_current", Long.class)),
                        getLong(row.get("output_current", Long.class)),
                        getLong(row.get("input_previous", Long.class)),
                        getLong(row.get("cache_read_previous", Long.class)),
                        getLong(row.get("cache_creation_previous", Long.class)),
                        getLong(row.get("output_previous", Long.class))));

        Mono<AiSpendMapper.CountsRow> counts = querySingle(queryBuilder.summaryCounts(), "ai_spend_summary_counts",
                request.resolvedProjectId(), NO_TEMPLATE,
                statement -> bindSummaryRange(statement, request),
                (row, metadata) -> new AiSpendMapper.CountsRow(
                        row.get("messages_current", Long.class),
                        row.get("messages_previous", Long.class),
                        row.get("active_users_current", Long.class),
                        row.get("active_users_previous", Long.class),
                        row.get("total_users", Long.class)),
                AiSpendMapper.CountsRow.empty());

        return Mono.zip(tiers, counts).map(tuple -> mapper.summary(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<SpendCompositionResponse> getComposition(@NonNull SpendMetricRequest request) {
        Optional<String> userUuid = resolveUserUuid(request);

        Mono<List<AiSpendMapper.ModelLanesRow>> inputTokens = queryList(queryBuilder.compositionTokens(),
                "ai_spend_composition_tokens", request.resolvedProjectId(), userFlag(userUuid),
                statement -> bindComposition(statement, request, userUuid),
                (row, metadata) -> {
                    Map<SpendLane, AiSpendMapper.LaneTiers> lanes = new EnumMap<>(SpendLane.class);
                    for (SpendLane lane : SpendLane.values()) {
                        lanes.put(lane, new AiSpendMapper.LaneTiers(
                                getLong(row.get(lane.getKey() + "_total", Long.class)),
                                getLong(row.get(lane.getKey() + "_input", Long.class)),
                                getLong(row.get(lane.getKey() + "_cache_read", Long.class)),
                                getLong(row.get(lane.getKey() + "_cache_creation", Long.class)),
                                getLong(row.get(lane.getKey() + "_output", Long.class))));
                    }
                    return new AiSpendMapper.ModelLanesRow(row.get("model", String.class), lanes);
                });

        Mono<List<AiSpendMapper.OutputModelRow>> outputTokens = queryList(queryBuilder.compositionOutput(),
                "ai_spend_composition_output", request.resolvedProjectId(), userFlag(userUuid),
                statement -> bindComposition(statement, request, userUuid),
                (row, metadata) -> new AiSpendMapper.OutputModelRow(
                        row.get("lane", String.class),
                        row.get("model", String.class),
                        getLong(row.get("tokens", Long.class))));

        return Mono.zip(inputTokens, outputTokens)
                .map(tuple -> mapper.composition(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<SpendBreakdownResponse> getBreakdown(@NonNull SpendMetricRequest request, @NonNull SpendLane lane) {
        Optional<String> userUuid = resolveUserUuid(request);
        return runBreakdown(queryBuilder.breakdown(lane), "ai_spend_breakdown", lane.getKey(),
                lane.getLabel(), lane.getDescription(), lane.getItemUnit(), request, userUuid);
    }

    @Override
    public Mono<SpendBreakdownResponse> getOutputBreakdown(@NonNull SpendMetricRequest request,
            @NonNull OutputLane lane) {
        Optional<String> userUuid = resolveUserUuid(request);
        return runBreakdown(queryBuilder.outputBreakdown(lane), "ai_spend_output_breakdown", lane.getKey(),
                lane.getLabel(), null, lane.getItemUnit(), request, userUuid);
    }

    private Mono<SpendBreakdownResponse> runBreakdown(AiSpendQueryBuilder.TemplatedQuery query, String queryName,
            String laneKey, String title, String subtitle, String itemUnit, SpendMetricRequest request,
            Optional<String> userUuid) {
        return queryList(query, queryName, laneKey, userFlag(userUuid),
                statement -> {
                    bindComposition(statement, request, userUuid);
                    statement.bind("limit", queryBuilder.breakdownItemLimit());
                },
                (row, metadata) -> new AiSpendMapper.BreakdownRow(
                        row.get("label", String.class),
                        row.get("model", String.class),
                        getLong(row.get("total_tokens", Long.class)),
                        getLong(row.get("definition_tokens", Long.class)),
                        getLong(row.get("usage_tokens", Long.class)),
                        getLong(row.get("events", Long.class)),
                        getLong(row.get("input_tokens", Long.class)),
                        getLong(row.get("cache_read_tokens", Long.class)),
                        getLong(row.get("cache_creation_tokens", Long.class)),
                        getLong(row.get("output_tokens", Long.class)),
                        getLong(row.get("grand_total", Long.class)),
                        getLong(row.get("group_count", Long.class)),
                        getLong(row.get("total_events", Long.class))))
                .map(rows -> mapper.breakdown(laneKey, title, subtitle, itemUnit, rows));
    }

    @Override
    public Mono<SpendUserPage> getUsers(@NonNull SpendMetricRequest request,
            @NonNull List<SortingField> sortingFields, String name, int page, int size) {
        boolean hasName = StringUtils.isNotBlank(name);
        long offset = (long) (page - 1) * size;

        return queryList(queryBuilder.users(sortingFields), "ai_spend_users", request.resolvedProjectId(),
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
                                .totalTokens(getLong(row.get("total_tokens", Long.class)))
                                .mcpCalls(getLong(row.get("mcp_calls", Long.class)))
                                .byModel(userByModel(row))
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

    private <T> Mono<List<T>> queryList(AiSpendQueryBuilder.TemplatedQuery query, String queryName, Object details,
            Consumer<ST> templating, Consumer<Statement> bind, BiFunction<Row, RowMetadata, T> mapper) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var statement = createStatement(connection, query, queryName, workspaceId, userName, details, templating);
            bind.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map(mapper))
                    .collectList();
        }));
    }

    private <T> Mono<T> querySingle(AiSpendQueryBuilder.TemplatedQuery query, String queryName, Object details,
            Consumer<ST> templating, Consumer<Statement> bind, BiFunction<Row, RowMetadata, T> mapper,
            T defaultValue) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var statement = createStatement(connection, query, queryName, workspaceId, userName, details, templating);
            bind.accept(statement);
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map(mapper))
                    .single(defaultValue);
        }));
    }

    private Statement createStatement(Connection connection, AiSpendQueryBuilder.TemplatedQuery query,
            String queryName, String workspaceId, String userName, Object details, Consumer<ST> templating) {
        var st = getSTWithLogComment(withLogComment(query.sql()), queryName, workspaceId, userName, details);
        query.fragments().accept(st);
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

    private List<ModelTiers> userByModel(Row row) {
        String[] models = row.get("models", String[].class);
        if (models == null) {
            return List.of();
        }
        // ClickHouse Array(Int64) maps to a primitive long[] over r2dbc.
        long[] input = row.get("model_input", long[].class);
        long[] cacheRead = row.get("model_cache_read", long[].class);
        long[] cacheCreation = row.get("model_cache_creation", long[].class);
        long[] output = row.get("model_output", long[].class);
        List<ModelTiers> byModel = new ArrayList<>();
        for (int i = 0; i < models.length; i++) {
            long in = arrayValue(input, i);
            long cacheReadValue = arrayValue(cacheRead, i);
            long cacheCreationValue = arrayValue(cacheCreation, i);
            long out = arrayValue(output, i);
            if (in + cacheReadValue + cacheCreationValue + out <= 0L) {
                continue;
            }
            byModel.add(ModelTiers.builder()
                    .model(models[i])
                    .inputTokens(in)
                    .cacheReadTokens(cacheReadValue)
                    .cacheCreationTokens(cacheCreationValue)
                    .outputTokens(out)
                    .build());
        }
        return byModel;
    }

    private long arrayValue(long[] values, int index) {
        return values == null || index >= values.length ? 0L : values[index];
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
