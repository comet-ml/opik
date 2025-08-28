package com.comet.opik.domain;

import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
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
    String NAME_THREADS = "threads";
    String NAME_COST = "cost";
    String NAME_GUARDRAILS_FAILED_COUNT = "failed";

    String TRACE_DURATION_PREFIX = "duration";
    String THREAD_DURATION_PREFIX = "thread_duration";
    String P50 = "p50";
    String P90 = "p90";
    String P99 = "p99";
    String NAME_TRACE_DURATION_P50 = String.join(".", TRACE_DURATION_PREFIX, P50);
    String NAME_TRACE_DURATION_P90 = String.join(".", TRACE_DURATION_PREFIX, P90);
    String NAME_TRACE_DURATION_P99 = String.join(".", TRACE_DURATION_PREFIX, P99);
    String NAME_THREAD_DURATION_P50 = String.join(".", THREAD_DURATION_PREFIX, P50);
    String NAME_THREAD_DURATION_P90 = String.join(".", THREAD_DURATION_PREFIX, P90);
    String NAME_THREAD_DURATION_P99 = String.join(".", THREAD_DURATION_PREFIX, P99);

    @Builder
    record Entry(String name, Instant time, Number value) {
    }

    Mono<List<Entry>> getDuration(UUID projectId, ProjectMetricRequest request);
    Mono<List<Entry>> getTraceCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getThreadCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getThreadDuration(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getThreadFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getTokenUsage(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getCost(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getGuardrailsFailedCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsDAOImpl implements ProjectMetricsDAO {

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

    private static final Map<TimeInterval, String> INTERVAL_TO_SQL = Map.of(
            TimeInterval.WEEKLY, "toIntervalWeek(1)",
            TimeInterval.DAILY, "toIntervalDay(1)",
            TimeInterval.HOURLY, "toIntervalHour(1)");

    private static final String TRACE_FILTERED_PREFIX = """
            WITH feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND project_id = :project_id
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at
                 FROM authored_feedback_scores FINAL
                 WHERE entity_type = 'trace'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
             ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), guardrails_agg AS (
                SELECT
                    entity_id,
                    if(sum(failed) > 0, 'failed', 'passed') AS guardrails_result
                FROM (
                      SELECT
                          workspace_id,
                          project_id,
                          entity_id,
                          id,
                          result = 'failed' AS failed
                      FROM guardrails
                      WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                      LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_id
            ),
            <if(feedback_scores_empty_filters)>
             fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            ),
            <endif>
            traces_filtered AS (
                SELECT
                    id,
                    start_time,
                    duration
                FROM (
                    SELECT
                        id,
                        start_time,
                        if(end_time IS NOT NULL AND start_time IS NOT NULL
                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                         NULL) AS duration
                    FROM traces FINAL
                    <if(guardrails_filters)>
                    LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = traces.id
                    <endif>
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                    AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
                    <if(trace_filters)> AND <trace_filters> <endif>
                    <if(trace_feedback_scores_filters)>
                    AND id in (
                        SELECT
                            entity_id
                        FROM (
                            SELECT *
                            FROM feedback_scores_final
                            ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                            LIMIT 1 BY entity_id, name
                        )
                        GROUP BY entity_id
                        HAVING <trace_feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                ) AS t
            )
            """;

    private static final String THREAD_FILTERED_PREFIX = """
            WITH traces_final AS (
                SELECT
                    *
                FROM traces FINAL
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND thread_id \\<> ''
                  AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                  AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
            ), trace_threads_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    thread_id,
                    id as thread_model_id,
                    status,
                    tags,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at
                FROM trace_threads FINAL
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id IN (SELECT thread_id FROM traces_final)
            ), feedback_scores_combined AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    value,
                    last_updated_at
                FROM feedback_scores FINAL
                WHERE entity_type = 'thread'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
                   AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'thread'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
                   AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ),
            <if(thread_feedback_scores_empty_filters)>
               fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <thread_feedback_scores_empty_filters>
            ),
            <endif>
            threads_filtered AS (
                SELECT
                    t.workspace_id as workspace_id,
                    t.project_id as project_id,
                    t.id as id,
                    t.start_time as start_time,
                    t.end_time as end_time,
                    t.duration as duration,
                    t.first_message as first_message,
                    t.last_message as last_message,
                    t.number_of_messages as number_of_messages,
                    if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                    if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                    if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                    if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                    if(tt.status = 'unknown', 'active', tt.status) as status,
                    if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                    tt.tags as tags
                FROM (
                    SELECT
                        t.thread_id as id,
                        t.workspace_id as workspace_id,
                        t.project_id as project_id,
                        min(t.start_time) as start_time,
                        max(t.end_time) as end_time,
                        if(max(t.end_time) IS NOT NULL AND min(t.start_time) IS NOT NULL
                               AND notEquals(min(t.start_time), toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', min(t.start_time), max(t.end_time)) / 1000.0),
                           NULL) AS duration,
                        <if(truncate)> replaceRegexpAll(argMin(t.input, t.start_time), '<truncate>', '"[image]"') as first_message <else> argMin(t.input, t.start_time) as first_message<endif>,
                        <if(truncate)> replaceRegexpAll(argMax(t.output, t.end_time), '<truncate>', '"[image]"') as last_message <else> argMax(t.output, t.end_time) as last_message<endif>,
                        count(DISTINCT t.id) * 2 as number_of_messages,
                        max(t.last_updated_at) as last_updated_at,
                        argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                        argMin(t.created_by, t.created_at) as created_by,
                        min(t.created_at) as created_at
                    FROM traces_final AS t
                    GROUP BY
                        t.workspace_id, t.project_id, t.thread_id
                ) AS t
                JOIN trace_threads_final AS tt ON t.id = tt.thread_id
                WHERE workspace_id = :workspace_id
                <if(thread_feedback_scores_filters)>
                AND thread_model_id IN (
                    SELECT
                        entity_id
                    FROM (
                        SELECT *
                        FROM feedback_scores_final
                        ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                    )
                    GROUP BY entity_id
                    HAVING <thread_feedback_scores_filters>
                )
                <endif>
                <if(thread_feedback_scores_empty_filters)>
                AND (
                    thread_model_id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    thread_model_id NOT IN (SELECT entity_id FROM fsc)
                )
                <endif>
                <if(trace_thread_filters)>AND<trace_thread_filters><endif>
            )
            """;

    private static final String GET_TRACE_DURATION = """
            %s
            SELECT <bucket> AS bucket,
                   arrayMap(v -> toDecimal64(if(isNaN(v), 0, v), 9), quantiles(0.5, 0.9, 0.99)(duration)) AS duration
            FROM traces_filtered
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_TRACE_COUNT = """
            %s
            SELECT <bucket> AS bucket,
                   nullIf(count(DISTINCT id), 0) as count
            FROM traces_filtered
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_FEEDBACK_SCORES = """
            %s, feedback_scores_deduplication AS (
                SELECT t.start_time,
                        fs.name,
                        fs.value
                FROM feedback_scores_final fs
                JOIN traces_filtered t ON t.id = fs.entity_id
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
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_THREAD_FEEDBACK_SCORES = """
            %s, thread_feedback_scores AS (
                SELECT t.start_time,
                        fs.name,
                        fs.value
                FROM feedback_scores_final fs
                JOIN (
                    SELECT
                        thread_model_id,
                        start_time
                    FROM threads_filtered
                ) t ON t.thread_model_id = fs.entity_id
            )
            SELECT <bucket> AS bucket,
                    name,
                    nullIf(avg(value), 0) AS value
            FROM thread_feedback_scores
            GROUP BY name, bucket
            ORDER BY name, bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """.formatted(THREAD_FILTERED_PREFIX);

    private static final String GET_TOKEN_USAGE = """
            %s, spans_dedup AS (
                SELECT t.start_time as start_time,
                       name,
                       value
                FROM traces_filtered t
                JOIN (
                    SELECT
                        trace_id,
                        usage
                    FROM spans final
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                ) s ON s.trace_id = t.id
                ARRAY JOIN mapKeys(usage) AS name, mapValues(usage) AS value
                WHERE value > 0
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
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_COST = """
            %s, spans_dedup AS (
                SELECT t.start_time AS start_time,
                       s.total_estimated_cost AS value
                FROM traces_filtered t
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
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_GUARDRAILS_FAILED_COUNT = """
            %s
            SELECT <bucket> AS bucket,
                   nullIf(count(DISTINCT g.id), 0) AS failed_cnt
            FROM traces_filtered AS t
                JOIN guardrails AS g ON g.entity_id = t.id
            WHERE g.result = 'failed'
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_THREAD_COUNT = """
            %s
            SELECT <bucket> AS bucket,
                   nullIf(count(DISTINCT id), 0) as count
            FROM threads_filtered
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """.formatted(THREAD_FILTERED_PREFIX);

    private static final String GET_THREAD_DURATION = """
            %s
            SELECT <bucket> AS bucket,
                   arrayMap(v -> toDecimal64(if(isNaN(v), 0, v), 9), quantiles(0.5, 0.9, 0.99)(duration)) AS duration
            FROM threads_filtered
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM <fill_from>
                TO parseDateTimeBestEffort(:end_time)
                STEP <step>;
            """.formatted(THREAD_FILTERED_PREFIX);

    @Override
    public Mono<List<Entry>> getDuration(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_TRACE_DURATION, "traceDuration")
                .flatMapMany(result -> result
                        .map((row, metadata) -> mapDuration(row, TRACE_DURATION_PREFIX)))
                .reduce(Stream::concat)
                .map(Stream::toList));
    }

    private Stream<Entry> mapDuration(Row row, String prefix) {
        return Optional.ofNullable(row.get("duration", List.class))
                .map(durations -> Stream.of(
                        Entry.builder().name(String.join(".", prefix, P50))
                                .time(row.get("bucket", Instant.class))
                                .value(getP(durations, 0))
                                .build(),
                        Entry.builder().name(String.join(".", prefix, P90))
                                .time(row.get("bucket", Instant.class))
                                .value(getP(durations, 1))
                                .build(),
                        Entry.builder().name(String.join(".", prefix, P99))
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
    public Mono<List<Entry>> getThreadCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_THREAD_COUNT, "threadCount")
                .flatMapMany(result -> rowToDataPoint(result, row -> NAME_THREADS,
                        row -> row.get("count", Integer.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getThreadDuration(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_THREAD_DURATION,
                "threadDuration")
                .flatMapMany(result -> result
                        .map((row, metadata) -> mapDuration(row, THREAD_DURATION_PREFIX)))
                .reduce(Stream::concat)
                .map(Stream::toList));
    }

    @Override
    public Mono<List<Entry>> getFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_FEEDBACK_SCORES,
                "feedbackScores")
                .flatMapMany(result -> rowToDataPoint(
                        result,
                        row -> row.get("name", String.class),
                        row -> row.get("value", BigDecimal.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getThreadFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_THREAD_FEEDBACK_SCORES,
                "threadFeedbackScores")
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
                GET_GUARDRAILS_FAILED_COUNT,
                "guardrailsFailedCount")
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

        Optional.ofNullable(request.traceFilters())
                .ifPresent(filters -> {
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE)
                            .ifPresent(traceFilters -> template.add("trace_filters", traceFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(scoresFilters -> template.add("trace_feedback_scores_filters", scoresFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoreIsEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoreIsEmptyFilters));
                    filterQueryBuilder.hasGuardrailsFilter(filters)
                            .ifPresent(hasGuardrailsFilter -> template.add("guardrails_filters", true));
                });

        Optional.ofNullable(request.threadFilters())
                .ifPresent(filters -> {
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_THREAD)
                            .ifPresent(traceFilters -> template.add("trace_thread_filters", traceFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(scoresFilters -> template.add("thread_feedback_scores_filters", scoresFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(
                                    feedbackScoreIsEmptyFilters -> template.add("thread_feedback_scores_empty_filters",
                                            feedbackScoreIsEmptyFilters));
                });

        var statement = connection.createStatement(template.render())
                .bind("project_id", projectId)
                .bind("start_time", request.intervalStart().toString())
                .bind("end_time", request.intervalEnd().toString());

        Optional.ofNullable(request.traceFilters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                });

        Optional.ofNullable(request.threadFilters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_THREAD);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                });

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
