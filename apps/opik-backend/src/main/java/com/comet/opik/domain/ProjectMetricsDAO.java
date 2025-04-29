package com.comet.opik.domain;

import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(ProjectMetricsDAOImpl.class)
public interface ProjectMetricsDAO {
    String NAME_TRACES = "traces";
    String NAME_COST = "cost";
    String NAME_GUARDRAILS_FAILED_COUNT = "failed";
    String NAME_DURATION_P50 = "duration.p50";
    String NAME_DURATION_P90 = "duration.p90";
    String NAME_DURATION_P99 = "duration.p99";

    @Builder
    record Entry(String name, Instant time, Number value) {
    }

    Mono<List<Entry>> getDuration(UUID projectId, ProjectMetricRequest request);
    Mono<List<Entry>> getTraceCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getTokenUsage(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getCost(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getGuardrailsFailedCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsDAOImpl implements ProjectMetricsDAO {

    private final @NonNull TransactionTemplateAsync template;

    private static final Map<TimeInterval, String> INTERVAL_TO_SQL = Map.of(
            TimeInterval.WEEKLY, "toIntervalWeek(1)",
            TimeInterval.DAILY, "toIntervalDay(1)",
            TimeInterval.HOURLY, "toIntervalHour(1)");

    private static final String GET_TRACE_DURATION = """
            WITH traces_dedup AS (
                SELECT
                       id,
                       start_time,
                       if(end_time IS NOT NULL AND start_time IS NOT NULL
                                AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                            (dateDiff('microsecond', start_time, end_time) / 1000.0),
                            NULL) AS duration
                FROM traces final
                WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
            )
            SELECT <bucket> AS bucket,
                   arrayMap(v -> toDecimal64(if(isNaN(v), 0, v), 9), quantiles(0.5, 0.9, 0.99)(duration)) AS duration
            FROM traces_dedup
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """;

    private static final String GET_TRACE_COUNT = """
            SELECT <bucket> AS bucket,
                   nullIf(count(DISTINCT id), 0) as count
            FROM traces
            WHERE project_id = :project_id
            AND workspace_id = :workspace_id
            AND start_time >= parseDateTime64BestEffort(:start_time, 9)
            AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """;

    private static final String GET_FEEDBACK_SCORES = """
            WITH feedback_scores_deduplication AS (
                SELECT t.start_time,
                        fs.name,
                        fs.value
                FROM feedback_scores fs final
                JOIN (
                    SELECT
                        id,
                        start_time
                    FROM traces final
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                    AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
                ) t ON t.id = fs.entity_id
                WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                AND entity_type = 'trace'
            )
            SELECT <bucket> AS bucket,
                    name,
                    nullIf(avg(value), 0) AS value
            FROM feedback_scores_deduplication
            GROUP BY name, bucket
            ORDER BY name, bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """;

    private static final String GET_TOKEN_USAGE = """
            WITH spans_dedup AS (
                SELECT t.start_time as start_time,
                       name,
                       value
                FROM (
                    SELECT
                        start_time,
                        id
                    FROM traces final
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                    AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
                ) t
                JOIN (
                    SELECT
                        trace_id,
                        usage
                    FROM spans final
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                ) s ON s.trace_id = t.id
                ARRAY JOIN mapKeys(usage) AS name, mapValues(usage) AS value
            )
            SELECT <bucket> AS bucket,
                    name,
                    nullIf(sum(value), 0) AS value
            FROM spans_dedup
            GROUP BY name, bucket
            ORDER BY name, bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """;

    private static final String GET_COST = """
            WITH spans_dedup AS (
                SELECT t.start_time AS start_time,
                       s.total_estimated_cost AS value
                FROM (
                    SELECT
                        start_time,
                        id
                    FROM traces final
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                    AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
                ) t
                JOIN (
                    SELECT
                        trace_id,
                        total_estimated_cost
                    FROM spans final
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                ) s ON s.trace_id = t.id
            )
            SELECT <bucket> AS bucket,
                    nullIf(sum(value), 0) AS value
            FROM spans_dedup
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """;

    private static final String GET_GUARDRAILS_FAILED_COUNT = """
            WITH traces_dedup AS (
                SELECT
                       id,
                       start_time,
                       if(end_time IS NOT NULL AND start_time IS NOT NULL
                                AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                            (dateDiff('microsecond', start_time, end_time) / 1000.0),
                            NULL) AS duration
                FROM traces final
                WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
            )
            SELECT <bucket> AS bucket,
                   nullIf(count(DISTINCT g.id), 0) AS failed_cnt
            FROM traces_dedup AS t
                JOIN guardrails AS g ON g.entity_id = t.id
            WHERE g.result = 'failed'
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """;

    @Override
    public Mono<List<Entry>> getDuration(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_TRACE_DURATION, "traceDuration")
                .flatMapMany(result -> result.map((row, metadata) -> mapDuration(row)))
                .reduce(Stream::concat)
                .map(Stream::toList));
    }

    private Stream<Entry> mapDuration(Row row) {
        return Optional.ofNullable(row.get("duration", List.class))
                .map(durations -> Stream.of(
                        Entry.builder().name(NAME_DURATION_P50)
                                .time(row.get("bucket", Instant.class))
                                .value(getP(durations, 0))
                                .build(),
                        Entry.builder().name(NAME_DURATION_P90)
                                .time(row.get("bucket", Instant.class))
                                .value(getP(durations, 1))
                                .build(),
                        Entry.builder().name(NAME_DURATION_P99)
                                .time(row.get("bucket", Instant.class))
                                .value(getP(durations, 2))
                                .build()))
                .orElse(Stream.empty());
    }

    private static BigDecimal getP(List durations, int index) {
        if (durations.size() <= index) {
            return null;
        }

        return (BigDecimal) durations.get(index);
    }

    @Override
    public Mono<List<Entry>> getTraceCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_TRACE_COUNT, "traceCount")
                .flatMapMany(result -> rowToDataPoint(result, row -> NAME_TRACES,
                        row -> row.get("count", Integer.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_FEEDBACK_SCORES, "feedbackScores")
                .flatMapMany(result -> rowToDataPoint(
                        result,
                        row -> row.get("name", String.class),
                        row -> row.get("value", BigDecimal.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getTokenUsage(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_TOKEN_USAGE, "token usage")
                .flatMapMany(result -> rowToDataPoint(
                        result,
                        row -> row.get("name", String.class),
                        row -> row.get("value", Long.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getCost(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_COST, "cost")
                .flatMapMany(result -> rowToDataPoint(
                        result,
                        row -> NAME_COST,
                        row -> row.get("value", BigDecimal.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getGuardrailsFailedCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_GUARDRAILS_FAILED_COUNT, "guardrailsFailedCount")
                .flatMapMany(result -> rowToDataPoint(result,
                        row -> NAME_GUARDRAILS_FAILED_COUNT,
                        row -> row.get("failed_cnt", Integer.class)))
                .collectList());
    }

    private Mono<? extends Result> getMetric(
            UUID projectId, ProjectMetricRequest request, Connection connection, String query, String segmentName) {
        var template = new ST(query)
                .add("step", intervalToSql(request.interval()))
                .add("bucket", wrapWeekly(request.interval(),
                        "toStartOfInterval(start_time, %s)".formatted(intervalToSql(request.interval()))))
                .add("fill_from", wrapWeekly(request.interval(),
                        "toStartOfInterval(parseDateTimeBestEffort(:start_time), %s)"
                                .formatted(intervalToSql(request.interval()))));

        var statement = connection.createStatement(template.render())
                .bind("project_id", projectId)
                .bind("start_time", request.intervalStart().toString())
                .bind("end_time", request.intervalEnd().toString());

        InstrumentAsyncUtils.Segment segment = startSegment(segmentName, "Clickhouse", "get");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private Publisher<Entry> rowToDataPoint(
            Result result, Function<Row, String> nameGetter, Function<Row, ? extends Number> valueGetter) {
        return result.map(((row, rowMetadata) -> Entry.builder()
                .name(nameGetter.apply(row))
                .value(valueGetter.apply(row))
                .time(row.get("bucket", Instant.class))
                .build()));
    }

    private String wrapWeekly(TimeInterval interval, String stmt) {
        if (interval == TimeInterval.WEEKLY) {
            return "toDateTime(%s)".formatted(stmt);
        }

        return stmt;
    }

    private String intervalToSql(TimeInterval interval) {
        return Optional.ofNullable(INTERVAL_TO_SQL.get(interval))
                .orElseThrow(() -> new IllegalArgumentException("Invalid interval: " + interval));
    }
}
