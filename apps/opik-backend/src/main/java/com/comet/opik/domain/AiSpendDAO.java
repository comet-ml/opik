package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(AiSpendDAOImpl.class)
public interface AiSpendDAO {

    Mono<List<WorkspaceMetricsSummaryResponse.Result>> getSummary(SpendMetricRequest request);

    Mono<SpendCompositionResponse> getComposition(SpendMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiSpendDAOImpl implements AiSpendDAO {

    private static final String USER_UUID_EXPR = "JSONExtractString(metadata, 'cc', 'identity', 'user_uuid')";

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
                %s
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id BETWEEN :id_start AND :id_end
                AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
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
        var lanes = List.copyOf(java.util.Arrays.asList(SpendLane.values()));
        String selectList = lanes.stream()
                .map(lane -> "SUM(%s) AS %s".formatted(lane.getTokenExpression(), lane.getKey()))
                .collect(Collectors.joining(",\n    "));
        String sql = COMPOSITION_TOKENS.formatted(selectList);
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            bindCompositionRange(statement, request);
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> {
                        long[] tokens = new long[lanes.size()];
                        for (int i = 0; i < lanes.size(); i++) {
                            Long value = row.get(lanes.get(i).getKey(), Long.class);
                            tokens[i] = value == null ? 0L : value;
                        }
                        return tokens;
                    }))
                    .single(new long[lanes.size()]);
        });
    }

    private Mono<BigDecimal> compositionCost(SpendMetricRequest request) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(COMPOSITION_COST);
            bindCompositionRange(statement, request);
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> {
                        BigDecimal value = row.get("total_cost", BigDecimal.class);
                        return value == null ? BigDecimal.ZERO : value;
                    }))
                    .single(BigDecimal.ZERO);
        });
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
        List<SpendCompositionResponse.Lane> inputLanes = new java.util.ArrayList<>();
        List<SpendCompositionResponse.Lane> outputLanes = new java.util.ArrayList<>();
        long inputTokens = 0L;
        long outputTokens = 0L;

        for (int i = 0; i < lanes.length; i++) {
            var lane = lanes[i];
            long laneTokens = tokens[i];
            var laneResponse = SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTokens)
                    .hasBreakdown(lane.isHasBreakdown())
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
                .input(SpendCompositionResponse.Side.builder().totalTokens(inputTokens).lanes(inputLanes).build())
                .output(SpendCompositionResponse.Side.builder().totalTokens(outputTokens).lanes(outputLanes).build())
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
