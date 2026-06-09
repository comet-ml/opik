package com.comet.opik.domain;

import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendUserRow;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(AiSpendDAOImpl.class)
public interface AiSpendDAO {

    Mono<List<WorkspaceMetricsSummaryResponse.Result>> getSummary(SpendMetricRequest request);

    Mono<SpendCompositionResponse> getComposition(SpendMetricRequest request);

    Mono<SpendBreakdownResponse> getBreakdown(SpendMetricRequest request, SpendLane lane);

    Mono<List<SpendUserRow>> getUsers(SpendMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendDAOImpl implements AiSpendDAO {

    private static final String USER_UUID_KEY = "cc.identity.user_uuid";
    private static final String USER_UUID_EXPR = "JSONExtractString(metadata, 'cc', 'identity', 'user_uuid')";
    private static final String USER_FILTER = " AND " + USER_UUID_EXPR + " = :user_uuid";

    private static final String COST_SUMMARY = """
            SELECT
                SUMIf(total_estimated_cost, id >= :id_start AND id <= :id_end) AS spend_current,
                SUMIf(total_estimated_cost, id >= :id_prior_start AND id < :id_start) AS spend_previous
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id BETWEEN :id_prior_start AND :id_end
                AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_prior_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            ;
            """;

    private static final String COUNTS_SUMMARY = """
            SELECT
                countIf(id >= :id_start AND id <= :id_end) AS messages_current,
                countIf(id >= :id_prior_start AND id < :id_start) AS messages_previous,
                uniqExactIf(%1$s, id >= :id_start AND id <= :id_end AND %1$s != '') AS active_users_current,
                uniqExactIf(%1$s, id >= :id_prior_start AND id < :id_start AND %1$s != '') AS active_users_previous,
                uniqExactIf(%1$s, %1$s != '') AS total_users
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id BETWEEN :id_prior_start AND :id_end
                AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_prior_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            ;
            """
            .formatted(USER_UUID_EXPR);

    private static final String COMPOSITION_TOKENS = """
            SELECT
                %1$s
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id BETWEEN :id_start AND :id_end
                AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                %2$s
            ;
            """;

    private static final String COMPOSITION_COST = """
            SELECT SUM(total_estimated_cost) AS total_cost
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id BETWEEN :id_start AND :id_end
                AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            ;
            """;

    private static final String COMPOSITION_COST_BY_USER = """
            SELECT SUM(s.total_estimated_cost) AS total_cost
            FROM spans s final
            INNER JOIN (
                SELECT id
                FROM traces final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id BETWEEN :id_start AND :id_end
                    AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                    AND %s = :user_uuid
            ) t ON s.trace_id = t.id
            WHERE s.workspace_id = :workspace_id
                AND s.project_id = :project_id
                AND s.id BETWEEN :id_start AND :id_end
                AND s.start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            ;
            """
            .formatted(USER_UUID_EXPR);

    private static final String BREAKDOWN = """
            SELECT label, SUM(token_value) AS total_tokens
            FROM (
                SELECT JSONExtractString(item, '%2$s') AS label,
                       JSONExtractInt(item, '%3$s') AS token_value
                FROM traces final
                ARRAY JOIN JSONExtractArrayRaw(metadata, %1$s) AS item
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id BETWEEN :id_start AND :id_end
                    AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                    %4$s
            )
            WHERE label != ''
            GROUP BY label
            ORDER BY total_tokens DESC
            LIMIT 200
            ;
            """;

    // Blended $/token rate over the window, used to estimate per-item cost for the
    // breakdown (cc.* arrays carry tokens only; cost lives on spans as a total).
    private static final String BREAKDOWN_RATE = """
            SELECT SUM(total_estimated_cost) AS total_cost,
                   SUM(usage['total_tokens']) AS total_tokens
            FROM spans final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id BETWEEN :id_start AND :id_end
                AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            ;
            """;

    private static final String USERS_TRACE_AGG = """
            SELECT
                user_uuid,
                anyLast(user_email) AS user_email,
                anyLast(user_display_name) AS user_display_name,
                count() AS requests,
                sum(skills_loaded) AS skills,
                max(mcps_available) AS mcps,
                sum(tool_results_count) AS mcp_calls,
                arrayFilter(x -> x != '', groupUniqArray(repository)) AS repositories
            FROM (
                SELECT
                    %1$s AS user_uuid,
                    JSONExtractString(metadata, 'cc', 'identity', 'user_email') AS user_email,
                    JSONExtractString(metadata, 'cc', 'identity', 'user_display_name') AS user_display_name,
                    JSONExtractInt(metadata, 'cc', 'skills', 'summary', 'loaded_count') AS skills_loaded,
                    JSONExtractInt(metadata, 'cc', 'tools', 'summary', 'by_source', 'mcp', 'available_count') AS mcps_available,
                    JSONExtractInt(metadata, 'cc', 'tool_results', 'summary', 'count') AS tool_results_count,
                    JSONExtractString(metadata, 'cc', 'git', 'repository') AS repository
                FROM traces final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id BETWEEN :id_start AND :id_end
                    AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            )
            WHERE user_uuid != ''
            GROUP BY user_uuid
            ;
            """
            .formatted(USER_UUID_EXPR);

    private static final String USERS_SPEND = """
            SELECT
                t.user_uuid AS user_uuid,
                SUM(s.total_estimated_cost) AS spend,
                anyLastIf(s.model, s.model != '') AS model
            FROM spans s final
            INNER JOIN (
                SELECT id, %1$s AS user_uuid
                FROM traces final
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND id BETWEEN :id_start AND :id_end
                    AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            ) t ON s.trace_id = t.id
            WHERE s.workspace_id = :workspace_id
                AND s.project_id = :project_id
                AND s.id BETWEEN :id_start AND :id_end
                AND s.start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            GROUP BY t.user_uuid
            ;
            """
            .formatted(USER_UUID_EXPR);

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public Mono<List<WorkspaceMetricsSummaryResponse.Result>> getSummary(@NonNull SpendMetricRequest request) {
        return Mono.zip(costSummary(request), countsSummary(request))
                .map(tuple -> buildSummaryResults(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<SpendCompositionResponse> getComposition(@NonNull SpendMetricRequest request) {
        return Mono.zip(compositionTokens(request), compositionCost(request))
                .map(tuple -> buildComposition(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<SpendBreakdownResponse> getBreakdown(@NonNull SpendMetricRequest request, @NonNull SpendLane lane) {
        Optional<String> userUuid = userUuidFromFilters(request.filters());
        String sql = BREAKDOWN.formatted(lane.getBreakdownArrayPath(), lane.getBreakdownLabelField(),
                lane.getBreakdownTokenField(), userUuid.map(u -> USER_FILTER).orElse(""));
        Mono<List<SpendBreakdownResponse.Item>> itemsMono = template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            bindCompositionRange(statement, request);
            userUuid.ifPresent(value -> statement.bind("user_uuid", value));
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> SpendBreakdownResponse.Item.builder()
                            .label(row.get("label", String.class))
                            .totalTokens(getLong(row.get("total_tokens", Long.class)))
                            .build()))
                    .collectList();
        });

        return Mono.zip(itemsMono, spendRate(request)).map(tuple -> {
            BigDecimal rate = tuple.getT2();
            // Per-item cost = item tokens × blended $/token rate (null when no cost data).
            List<SpendBreakdownResponse.Item> items = tuple.getT1().stream()
                    .map(item -> item.toBuilder()
                            .totalEstimatedCost(costFor(item.totalTokens(), rate))
                            .build())
                    .toList();
            long total = items.stream().mapToLong(SpendBreakdownResponse.Item::totalTokens).sum();
            return SpendBreakdownResponse.builder()
                    .laneKey(lane.getKey())
                    .title(lane.getLabel())
                    .subtitle(lane.getBreakdownSubtitle())
                    .totalTokens(total)
                    .totalEstimatedCost(costFor(total, rate))
                    .itemCount(items.size())
                    .items(items)
                    .build();
        });
    }

    private Mono<BigDecimal> spendRate(SpendMetricRequest request) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(BREAKDOWN_RATE);
            bindCompositionRange(statement, request);
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> {
                        BigDecimal cost = row.get("total_cost", BigDecimal.class);
                        long tokens = getLong(row.get("total_tokens", Long.class));
                        if (cost == null || tokens == 0L) {
                            return BigDecimal.ZERO;
                        }
                        return cost.divide(BigDecimal.valueOf(tokens), 12, RoundingMode.HALF_UP);
                    }))
                    .single(BigDecimal.ZERO);
        });
    }

    private BigDecimal costFor(long tokens, BigDecimal rate) {
        return rate.signum() > 0 ? rate.multiply(BigDecimal.valueOf(tokens)) : null;
    }

    @Override
    public Mono<List<SpendUserRow>> getUsers(@NonNull SpendMetricRequest request) {
        return Mono.zip(usersTraceAgg(request), usersSpend(request))
                .map(tuple -> mergeUsers(tuple.getT1(), tuple.getT2()));
    }

    private Mono<CostRow> costSummary(SpendMetricRequest request) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(COST_SUMMARY);
            bindSummaryRange(statement, request);
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> new CostRow(
                            filterNan(row.get("spend_current", Double.class)),
                            filterNan(row.get("spend_previous", Double.class)))))
                    .single(new CostRow(null, null));
        });
    }

    private Mono<CountsRow> countsSummary(SpendMetricRequest request) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(COUNTS_SUMMARY);
            bindSummaryRange(statement, request);
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> new CountsRow(
                            row.get("messages_current", Long.class),
                            row.get("messages_previous", Long.class),
                            row.get("active_users_current", Long.class),
                            row.get("active_users_previous", Long.class),
                            row.get("total_users", Long.class))))
                    .single(new CountsRow(0L, 0L, 0L, 0L, 0L));
        });
    }

    private Mono<long[]> compositionTokens(SpendMetricRequest request) {
        var lanes = List.of(SpendLane.values());
        Optional<String> userUuid = userUuidFromFilters(request.filters());
        String selectList = lanes.stream()
                .map(lane -> "SUM(%s) AS %s".formatted(lane.getTokenExpression(), lane.getKey()))
                .collect(Collectors.joining(",\n    "));
        String sql = COMPOSITION_TOKENS.formatted(selectList, userUuid.map(u -> USER_FILTER).orElse(""));
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            bindCompositionRange(statement, request);
            userUuid.ifPresent(value -> statement.bind("user_uuid", value));
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> {
                        long[] tokens = new long[lanes.size()];
                        for (int i = 0; i < lanes.size(); i++) {
                            tokens[i] = getLong(row.get(lanes.get(i).getKey(), Long.class));
                        }
                        return tokens;
                    }))
                    .single(new long[lanes.size()]);
        });
    }

    private Mono<BigDecimal> compositionCost(SpendMetricRequest request) {
        Optional<String> userUuid = userUuidFromFilters(request.filters());
        String sql = userUuid.isPresent() ? COMPOSITION_COST_BY_USER : COMPOSITION_COST;
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            bindCompositionRange(statement, request);
            userUuid.ifPresent(value -> statement.bind("user_uuid", value));
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> Optional
                            .ofNullable(row.get("total_cost", BigDecimal.class)).orElse(BigDecimal.ZERO)))
                    .single(BigDecimal.ZERO);
        });
    }

    private Mono<List<SpendUserRow>> usersTraceAgg(SpendMetricRequest request) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(USERS_TRACE_AGG);
            bindCompositionRange(statement, request);
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> SpendUserRow.builder()
                            .userUuid(row.get("user_uuid", String.class))
                            .userEmail(row.get("user_email", String.class))
                            .userDisplayName(row.get("user_display_name", String.class))
                            .requests(getLong(row.get("requests", Long.class)))
                            .skills(getLong(row.get("skills", Long.class)))
                            .mcps(getLong(row.get("mcps", Long.class)))
                            .mcpCalls(getLong(row.get("mcp_calls", Long.class)))
                            .repositories(toList(row.get("repositories", String[].class)))
                            .build()))
                    .collectList();
        });
    }

    private Mono<List<SpendUserRow>> usersSpend(SpendMetricRequest request) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(USERS_SPEND);
            bindCompositionRange(statement, request);
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> SpendUserRow.builder()
                            .userUuid(row.get("user_uuid", String.class))
                            .totalEstimatedCost(toBigDecimal(row.get("spend", Double.class)))
                            .model(row.get("model", String.class))
                            .build()))
                    .collectList();
        });
    }

    private List<SpendUserRow> mergeUsers(List<SpendUserRow> traceAgg, List<SpendUserRow> spend) {
        var spendByUser = spend.stream()
                .filter(row -> row.userUuid() != null)
                .collect(Collectors.toMap(SpendUserRow::userUuid, row -> row, (a, b) -> a));
        List<SpendUserRow> merged = new ArrayList<>();
        for (SpendUserRow agg : traceAgg) {
            SpendUserRow costRow = spendByUser.get(agg.userUuid());
            merged.add(agg.toBuilder()
                    .totalEstimatedCost(costRow != null ? costRow.totalEstimatedCost() : BigDecimal.ZERO)
                    .model(costRow != null ? costRow.model() : null)
                    .build());
        }
        return merged;
    }

    private void bindSummaryRange(Statement statement, SpendMetricRequest request) {
        Instant priorStart = getPriorStart(request.intervalStart(), request.intervalEnd());
        statement
                .bind("project_id", request.resolvedProjectId())
                .bind("timestamp_prior_start", priorStart.toString())
                .bind("timestamp_end", request.intervalEnd().toString())
                .bind("id_start", idGenerator.getTimeOrderedEpoch(request.intervalStart().toEpochMilli()))
                .bind("id_end", idGenerator.getTimeOrderedEpoch(request.intervalEnd().toEpochMilli()))
                .bind("id_prior_start", idGenerator.getTimeOrderedEpoch(priorStart.toEpochMilli()));
    }

    private void bindCompositionRange(Statement statement, SpendMetricRequest request) {
        statement
                .bind("project_id", request.resolvedProjectId())
                .bind("timestamp_start", request.intervalStart().toString())
                .bind("timestamp_end", request.intervalEnd().toString())
                .bind("id_start", idGenerator.getTimeOrderedEpoch(request.intervalStart().toEpochMilli()))
                .bind("id_end", idGenerator.getTimeOrderedEpoch(request.intervalEnd().toEpochMilli()));
    }

    private Optional<String> userUuidFromFilters(List<TraceFilter> filters) {
        if (CollectionUtils.isEmpty(filters)) {
            return Optional.empty();
        }
        return filters.stream()
                .filter(filter -> USER_UUID_KEY.equals(filter.key()))
                .map(TraceFilter::value)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private List<WorkspaceMetricsSummaryResponse.Result> buildSummaryResults(CostRow cost, CountsRow counts) {
        Double avgCurrent = average(cost.current(), counts.activeUsersCurrent());
        Double avgPrevious = average(cost.previous(), counts.activeUsersPrevious());
        return List.of(
                result("total_spend", cost.current(), cost.previous()),
                result("total_messages", toDouble(counts.messagesCurrent()), toDouble(counts.messagesPrevious())),
                result("avg_cost_per_user", avgCurrent, avgPrevious),
                result("active_users", toDouble(counts.activeUsersCurrent()), null),
                result("total_users", toDouble(counts.totalUsers()), null));
    }

    private SpendCompositionResponse buildComposition(long[] tokens, BigDecimal totalCost) {
        var lanes = SpendLane.values();
        long grandTotalTokens = 0L;
        for (long laneTokens : tokens) {
            grandTotalTokens += laneTokens;
        }
        // Blended $/token rate: distribute the native total span cost across lanes by
        // token share, so each lane shows a proportional cost estimate (null otherwise).
        BigDecimal rate = totalCost != null && totalCost.signum() > 0 && grandTotalTokens > 0L
                ? totalCost.divide(BigDecimal.valueOf(grandTotalTokens), 12, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<SpendCompositionResponse.Lane> inputLanes = new ArrayList<>();
        List<SpendCompositionResponse.Lane> outputLanes = new ArrayList<>();
        long inputTokens = 0L;
        long outputTokens = 0L;

        for (int i = 0; i < lanes.length; i++) {
            var lane = lanes[i];
            long laneTokens = tokens[i];
            var laneResponse = SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTokens)
                    .totalEstimatedCost(costFor(laneTokens, rate))
                    .hasBreakdown(lane.hasBreakdown())
                    .build();
            if (lane.getSide() == SpendLane.Side.INPUT) {
                inputLanes.add(laneResponse);
                inputTokens += laneTokens;
            } else {
                outputLanes.add(laneResponse);
                outputTokens += laneTokens;
            }
        }

        return SpendCompositionResponse.builder()
                .input(SpendCompositionResponse.Side.builder()
                        .totalTokens(inputTokens)
                        .totalEstimatedCost(costFor(inputTokens, rate))
                        .lanes(inputLanes)
                        .build())
                .output(SpendCompositionResponse.Side.builder()
                        .totalTokens(outputTokens)
                        .totalEstimatedCost(costFor(outputTokens, rate))
                        .lanes(outputLanes)
                        .build())
                .harness(List.of(SpendCompositionResponse.HarnessEntry.builder()
                        .key("claude_code")
                        .label("Claude Code")
                        .totalEstimatedCost(totalCost)
                        .build()))
                .build();
    }

    private WorkspaceMetricsSummaryResponse.Result result(String name, Double current, Double previous) {
        return WorkspaceMetricsSummaryResponse.Result.builder()
                .name(name)
                .current(current)
                .previous(previous)
                .build();
    }

    private Double average(Double total, Long count) {
        if (total == null || count == null || count == 0L) {
            return null;
        }
        return total / count;
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
        Duration duration = Duration.between(intervalStart, intervalEnd);
        return intervalStart.minus(duration);
    }

    private Double filterNan(Double value) {
        if (value == null) {
            return null;
        }
        return value.isNaN() ? null : value;
    }

    private record CostRow(Double current, Double previous) {
    }

    private record CountsRow(Long messagesCurrent, Long messagesPrevious, Long activeUsersCurrent,
            Long activeUsersPrevious, Long totalUsers) {
    }
}
