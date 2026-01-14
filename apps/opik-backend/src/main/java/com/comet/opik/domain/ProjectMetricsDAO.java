package com.comet.opik.domain;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.comet.opik.utils.RowUtils;
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
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
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

    Mono<BigDecimal> getTotalCost(List<UUID> projectIds, @NonNull Instant startTime, Instant endTime);

    Mono<BigDecimal> getAverageDuration(List<UUID> projectIds, @NonNull Instant startTime, Instant endTime);

    Mono<BigDecimal> getTotalTraceErrors(List<UUID> projectIds, @NonNull Instant startTime, Instant endTime);

    Mono<BigDecimal> getAverageFeedbackScore(List<UUID> projectIds, @NonNull Instant startTime, Instant endTime,
            EntityType entityType, String feedbackScoreName);

    Mono<List<Entry>> getSpanDuration(UUID projectId, ProjectMetricRequest request);

    Mono<List<Entry>> getSpanCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);

    Mono<List<Entry>> getSpanTokenUsage(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);

    Mono<List<Entry>> getSpanFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsDAOImpl implements ProjectMetricsDAO {

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;

    private static final Map<TimeInterval, String> INTERVAL_TO_SQL = Map.of(
            TimeInterval.WEEKLY, "toIntervalWeek(1)",
            TimeInterval.DAILY, "toIntervalDay(1)",
            TimeInterval.HOURLY, "toIntervalHour(1)");

    private static final EnumSet<MetricType> SPAN_METRICS = EnumSet.of(
            MetricType.SPAN_COUNT,
            MetricType.SPAN_DURATION,
            MetricType.SPAN_TOKEN_USAGE,
            MetricType.SPAN_FEEDBACK_SCORES);

    private static final String PROJECT_METRIC_QUERY_NAME_PREFIX = "ProjectMetrics_";
    private static final String ALERT_METRIC_QUERY_NAME_PREFIX = "AlertMetrics_";

    private static final String TRACE_FILTERED_PREFIX = """
            WITH feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND project_id = :project_id
                  <if(uuid_from_time)> AND entity_id >= :uuid_from_time<endif>
                  <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time<endif>
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                 FROM authored_feedback_scores FINAL
                 WHERE entity_type = 'trace'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
                   <if(uuid_from_time)> AND entity_id >= :uuid_from_time<endif>
                   <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time<endif>
             ),
             feedback_scores_with_ranking AS (
                 SELECT workspace_id,
                        project_id,
                        entity_id,
                        name,
                        value,
                        last_updated_at,
                        author,
                        ROW_NUMBER() OVER (
                            PARTITION BY workspace_id, project_id, entity_id, name, author
                            ORDER BY last_updated_at DESC
                        ) as rn
                 FROM feedback_scores_combined_raw
             ),
             feedback_scores_combined AS (
                 SELECT workspace_id,
                        project_id,
                        entity_id,
                        name,
                        value,
                        last_updated_at,
                        author
                 FROM feedback_scores_with_ranking
                 WHERE rn = 1
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
                    UUIDv7ToDateTime(toUUID(id)) as trace_time,
                    duration
                FROM (
                    SELECT
                        id,
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
                    <if(uuid_from_time)> AND id >= :uuid_from_time<endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time<endif>
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

    private static final String SPAN_FILTERED_PREFIX = """
            WITH feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'span'
                  AND workspace_id = :workspace_id
                  AND project_id = :project_id
                  <if(uuid_from_time)> AND entity_id >= :uuid_from_time<endif>
                  <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time<endif>
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                 FROM authored_feedback_scores FINAL
                 WHERE entity_type = 'span'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
                   <if(uuid_from_time)> AND entity_id >= :uuid_from_time<endif>
                   <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time<endif>
             ),
             feedback_scores_with_ranking AS (
                 SELECT workspace_id,
                        project_id,
                        entity_id,
                        name,
                        value,
                        last_updated_at,
                        author,
                        ROW_NUMBER() OVER (
                            PARTITION BY workspace_id, project_id, entity_id, name, author
                            ORDER BY last_updated_at DESC
                        ) as rn
                 FROM feedback_scores_combined_raw
             ),
             feedback_scores_combined AS (
                 SELECT workspace_id,
                        project_id,
                        entity_id,
                        name,
                        value,
                        last_updated_at,
                        author
                 FROM feedback_scores_with_ranking
                 WHERE rn = 1
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
            spans_filtered AS (
                SELECT
                    id,
                    UUIDv7ToDateTime(toUUID(id)) as span_time,
                    duration,
                    usage
                FROM (
                    SELECT
                        id,
                        if(end_time IS NOT NULL AND start_time IS NOT NULL
                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                         NULL) AS duration,
                         usage
                    FROM spans FINAL
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = spans.id
                    <endif>
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    <if(uuid_from_time)> AND id >= :uuid_from_time<endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time<endif>
                    <if(span_filters)> AND <span_filters> <endif>
                    <if(span_feedback_scores_filters)>
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
                        HAVING <span_feedback_scores_filters>
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
                <if(uuid_from_time)> AND id >= :uuid_from_time<endif>
                <if(uuid_to_time)> AND id \\<= :uuid_to_time<endif>
            ), feedback_scores_combined_raw AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    value,
                    last_updated_at,
                    last_updated_by AS author
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
                       last_updated_at,
                       author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'thread'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
                   AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
            ),
            feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ),
            feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM feedback_scores_with_ranking
                WHERE rn = 1
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
                    UUIDv7ToDateTime(toUUID(tt.thread_model_id)) as trace_time,
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
                   arrayMap(
                     v -> toDecimal64(
                            greatest(
                              least(if(isFinite(v), v, 0),  999999999.999999999),
                              -999999999.999999999
                            ),
                            9
                          ),
                     quantiles(0.5, 0.9, 0.99)(duration)
                   ) AS duration
            FROM traces_filtered
            GROUP BY bucket
            ORDER BY bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_TRACE_COUNT = """
            %s
            SELECT <bucket> AS bucket,
                   nullIf(count(DISTINCT id), 0) as count
            FROM traces_filtered
            GROUP BY bucket
            ORDER BY bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_FEEDBACK_SCORES = """
            %s, feedback_scores_deduplication AS (
                SELECT t.trace_time,
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
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_SPAN_FEEDBACK_SCORES = """
            %s, span_feedback_scores AS (
                SELECT s.span_time,
                        fs.name,
                        fs.value
                FROM feedback_scores_final fs
                JOIN spans_filtered s ON s.id = fs.entity_id
            )
            SELECT <bucket> AS bucket,
                    name,
                    nullIf(avg(value), 0) AS value
            FROM span_feedback_scores
            GROUP BY name, bucket
            ORDER BY name, bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(SPAN_FILTERED_PREFIX);

    private static final String GET_SPAN_DURATION = """
            %s
            SELECT <bucket> AS bucket,
                   arrayMap(
                     v -> toDecimal64(
                            greatest(
                              least(if(isFinite(v), v, 0),  999999999.999999999),
                              -999999999.999999999
                            ),
                            9
                          ),
                     quantiles(0.5, 0.9, 0.99)(duration)
                   ) AS duration
            FROM spans_filtered
            GROUP BY bucket
            ORDER BY bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(SPAN_FILTERED_PREFIX);

    private static final String GET_SPAN_COUNT = """
            %s
            SELECT <bucket> AS bucket,
                   nullIf(count(DISTINCT id), 0) as count
            FROM spans_filtered
            GROUP BY bucket
            ORDER BY bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(SPAN_FILTERED_PREFIX);

    private static final String GET_SPAN_TOKEN_USAGE = """
            %s, spans_usage AS (
                SELECT span_time,
                       name,
                       value
                FROM spans_filtered s
                ARRAY JOIN mapKeys(usage) AS name, mapValues(usage) AS value
                WHERE value > 0
            )
            SELECT <bucket> AS bucket,
                    name,
                    nullIf(sum(value), 0) AS value
            FROM spans_usage
            GROUP BY name, bucket
            ORDER BY name, bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(SPAN_FILTERED_PREFIX);

    private static final String GET_THREAD_FEEDBACK_SCORES = """
            %s, thread_feedback_scores AS (
                SELECT t.trace_time,
                        fs.name,
                        fs.value
                FROM feedback_scores_final fs
                JOIN (
                    SELECT
                        thread_model_id,
                        trace_time
                    FROM threads_filtered
                ) t ON t.thread_model_id = fs.entity_id
            )
            SELECT <bucket> AS bucket,
                    name,
                    nullIf(avg(value), 0) AS value
            FROM thread_feedback_scores
            GROUP BY name, bucket
            ORDER BY name, bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(THREAD_FILTERED_PREFIX);

    private static final String GET_TOKEN_USAGE = """
            %s, spans_dedup AS (
                SELECT t.trace_time as trace_time,
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
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_COST = """
            %s, spans_dedup AS (
                SELECT t.trace_time AS trace_time,
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
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
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
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(TRACE_FILTERED_PREFIX);

    private static final String GET_TOTAL_COST = """
            SELECT
                sum(total_estimated_cost) AS total_cost
            FROM spans final
            WHERE workspace_id = :workspace_id
                <if(project_ids)> AND project_id IN :project_ids <endif>
                <if(uuid_from_time)>AND trace_id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND trace_id \\<= :uuid_to_time<endif>
            SETTINGS log_comment = '<log_comment>';
            """;

    private static final String GET_AVERAGE_DURATION = """
            SELECT
                avg(
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                       AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                       (dateDiff('microsecond', start_time, end_time) / 1000.0),
                       NULL)
                ) AS avg_duration
            FROM traces final
            WHERE workspace_id = :workspace_id
                <if(project_ids)> AND project_id IN :project_ids <endif>
                <if(uuid_from_time)>AND id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
            SETTINGS log_comment = '<log_comment>';
            """;

    private static final String GET_TOTAL_TRACE_ERRORS = """
            SELECT
                COUNT(1) AS total_trace_errors
            FROM traces final
            WHERE workspace_id = :workspace_id
                AND length(error_info) > 0
                <if(project_ids)> AND project_id IN :project_ids <endif>
                <if(uuid_from_time)>AND id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
            SETTINGS log_comment = '<log_comment>';
            """;

    private static final String GET_AVERAGE_FEEDBACK_SCORE = """
            SELECT
                avg(value) AS avg_feedback_score
            FROM authored_feedback_scores final
            WHERE workspace_id = :workspace_id
                AND entity_type = :entity_type
                AND name = :feedback_score_name
                AND created_at >= parseDateTime64BestEffort(:start_time, 9)
                AND created_at \\<= parseDateTime64BestEffort(:end_time, 9)
                <if(project_ids)> AND project_id IN :project_ids <endif>
            SETTINGS log_comment = '<log_comment>';
            """;

    private static final String GET_THREAD_COUNT = """
            %s
            SELECT <bucket> AS bucket,
                   nullIf(count(DISTINCT id), 0) as count
            FROM threads_filtered
            GROUP BY bucket
            ORDER BY bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
            """.formatted(THREAD_FILTERED_PREFIX);

    private static final String GET_THREAD_DURATION = """
            %s
            SELECT <bucket> AS bucket,
                   arrayMap(
                     v -> toDecimal64(
                            greatest(
                              least(if(isFinite(v), v, 0),  999999999.999999999),
                              -999999999.999999999
                            ),
                            9
                          ),
                     quantiles(0.5, 0.9, 0.99)(duration)
                   ) AS duration
            FROM threads_filtered
            GROUP BY bucket
            ORDER BY bucket
            <if(with_fill)>WITH FILL
                FROM <fill_from>
                TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                STEP <step><endif>
            SETTINGS log_comment = '<log_comment>';
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

    @Override
    public Mono<BigDecimal> getTotalCost(List<UUID> projectIds, @NonNull Instant startTime, Instant endTime) {
        return getAlertMetric(
                GET_TOTAL_COST,
                projectIds,
                startTime,
                endTime,
                "getTotalCost",
                "total_cost");
    }

    @Override
    public Mono<BigDecimal> getAverageDuration(List<UUID> projectIds, @NonNull Instant startTime, Instant endTime) {
        return getAlertMetric(
                GET_AVERAGE_DURATION,
                projectIds,
                startTime,
                endTime,
                "getAverageDuration",
                "avg_duration");
    }

    @Override
    public Mono<BigDecimal> getTotalTraceErrors(List<UUID> projectIds, @NonNull Instant startTime, Instant endTime) {
        return getAlertMetric(
                GET_TOTAL_TRACE_ERRORS,
                projectIds,
                startTime,
                endTime,
                "getTotalTraceErrors",
                "total_trace_errors");
    }

    @Override
    public Mono<BigDecimal> getAverageFeedbackScore(List<UUID> projectIds, @NonNull Instant startTime, Instant endTime,
            EntityType entityType, String feedbackScoreName) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var stTemplate = getSTWithLogComment(GET_AVERAGE_FEEDBACK_SCORE, "get_average_feedback_score", workspaceId,
                    feedbackScoreName);

            // Add project_ids flag to template if provided
            if (projectIds != null && !projectIds.isEmpty()) {
                stTemplate.add("project_ids", true);
            }

            // Create statement once with all flags set
            var statement = connection.createStatement(stTemplate.render())
                    .bind("start_time", startTime.toString())
                    .bind("end_time", endTime.toString())
                    .bind("entity_type", entityType.getType())
                    .bind("feedback_score_name", feedbackScoreName)
                    .bind("workspace_id", workspaceId);

            // Bind project IDs if provided
            if (projectIds != null && !projectIds.isEmpty()) {
                statement.bind("project_ids", projectIds.toArray(new UUID[0]));
            }

            InstrumentAsyncUtils.Segment segment = startSegment("getAverageFeedbackScore", "Clickhouse", "get");

            return Mono.from(statement.execute())
                    .flatMapMany(result -> result
                            .map((row, metadata) -> Optional
                                    .ofNullable(
                                            RowUtils.getOptionalValue(row, "avg_feedback_score", BigDecimal.class))))
                    .next()
                    .mapNotNull(opt -> opt.orElse(null))
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    @Override
    public Mono<List<Entry>> getSpanDuration(UUID projectId, ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_SPAN_DURATION, "spanDuration")
                .flatMapMany(result -> result
                        .map((row, metadata) -> mapDuration(row, "duration")))
                .reduce(Stream::concat)
                .map(Stream::toList));
    }

    @Override
    public Mono<List<Entry>> getSpanCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_SPAN_COUNT, "spanCount")
                .flatMapMany(result -> rowToDataPoint(result, row -> "spans",
                        row -> row.get("count", Integer.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getSpanTokenUsage(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_SPAN_TOKEN_USAGE, "spanTokenUsage")
                .flatMapMany(result -> rowToDataPoint(
                        result,
                        row -> row.get("name", String.class),
                        row -> row.get("value", Long.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getSpanFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_SPAN_FEEDBACK_SCORES,
                "spanFeedbackScores")
                .flatMapMany(result -> rowToDataPoint(
                        result,
                        row -> row.get("name", String.class),
                        row -> row.get("value", BigDecimal.class)))
                .collectList());
    }

    private Mono<BigDecimal> getAlertMetric(@NonNull String query, List<UUID> projectIds, @NonNull Instant startTime,
            Instant endTime, @NonNull String segmentName, @NonNull String rowName) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var stTemplate = getSTWithLogComment(query, ALERT_METRIC_QUERY_NAME_PREFIX + segmentName, workspaceId,
                    projectIds != null ? projectIds.size() : 0);

            // Add project_ids flag to template if provided
            if (projectIds != null && !projectIds.isEmpty()) {
                stTemplate.add("project_ids", true);
            }

            // Add uuid_from_time flag
            stTemplate.add("uuid_from_time", true);
            var uuidFromTime = instantToUUIDMapper.toLowerBound(startTime).toString();

            // Add uuid_to_time flag if endTime is provided
            String uuidToTime = null;
            if (endTime != null) {
                stTemplate.add("uuid_to_time", true);
                uuidToTime = instantToUUIDMapper.toUpperBound(endTime).toString();
            }

            // Create statement once with all flags set
            var statement = connection.createStatement(stTemplate.render())
                    .bind("uuid_from_time", uuidFromTime)
                    .bind("workspace_id", workspaceId);

            // Bind uuid_to_time only if endTime is provided
            if (uuidToTime != null) {
                statement = statement.bind("uuid_to_time", uuidToTime);
            }

            // Bind project IDs if provided
            if (projectIds != null && !projectIds.isEmpty()) {
                statement.bind("project_ids", projectIds.toArray(new UUID[0]));
            }

            InstrumentAsyncUtils.Segment segment = startSegment(segmentName, "Clickhouse", "get");

            return Mono.from(statement.execute())
                    .flatMapMany(result -> result
                            .map((row, metadata) -> Optional
                                    .ofNullable(RowUtils.getOptionalValue(row, rowName, BigDecimal.class))))
                    .next()
                    .mapNotNull(opt -> opt.orElse(null))
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    private Mono<? extends Result> getMetric(
            UUID projectId, ProjectMetricRequest request, Connection connection, String query, String segmentName) {
        return makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(query, PROJECT_METRIC_QUERY_NAME_PREFIX + segmentName, workspaceId,
                    projectId.toString())
                    .add("step", intervalToSql(request.interval()))
                    .add("bucket", wrapWeekly(request.interval(),
                            "toStartOfInterval(%s, %s)".formatted(getTimeField(request.metricType()),
                                    intervalToSql(request.interval()))))
                    .add("fill_from", wrapWeekly(request.interval(),
                            "toStartOfInterval(UUIDv7ToDateTime(toUUID(:uuid_from_time)), %s)"
                                    .formatted(intervalToSql(request.interval()))));

            // Add uuid flags for conditional SQL generation
            template.add("uuid_from_time", true);
            if (request.uuidToTime() != null) {
                template.add("uuid_to_time", true);
                template.add("with_fill", true);
            }
            // Note: when uuid_to_time is null, WITH FILL clause is omitted entirely

            Optional.ofNullable(request.traceFilters())
                    .ifPresent(filters -> {
                        filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE)
                                .ifPresent(traceFilters -> template.add("trace_filters", traceFilters));
                        filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                                .ifPresent(
                                        scoresFilters -> template.add("trace_feedback_scores_filters", scoresFilters));
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
                                .ifPresent(
                                        scoresFilters -> template.add("thread_feedback_scores_filters", scoresFilters));
                        filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                                .ifPresent(
                                        feedbackScoreIsEmptyFilters -> template.add(
                                                "thread_feedback_scores_empty_filters",
                                                feedbackScoreIsEmptyFilters));
                    });

            Optional.ofNullable(request.spanFilters())
                    .ifPresent(filters -> {
                        filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.SPAN)
                                .ifPresent(spanFilters -> template.add("span_filters", spanFilters));
                        filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.SPAN_FEEDBACK_SCORES)
                                .ifPresent(
                                        scoresFilters -> template.add("span_feedback_scores_filters", scoresFilters));
                        filterQueryBuilder
                                .toAnalyticsDbFilters(filters, FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY)
                                .ifPresent(
                                        feedbackScoreIsEmptyFilters -> template.add("feedback_scores_empty_filters",
                                                feedbackScoreIsEmptyFilters));
                    });

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("uuid_from_time", request.uuidFromTime().toString())
                    .bind("workspace_id", workspaceId);

            // Bind uuid_to_time only if present
            if (request.uuidToTime() != null) {
                statement.bind("uuid_to_time", request.uuidToTime().toString());
            }

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

            Optional.ofNullable(request.spanFilters())
                    .ifPresent(filters -> {
                        filterQueryBuilder.bind(statement, filters, FilterStrategy.SPAN);
                        filterQueryBuilder.bind(statement, filters, FilterStrategy.SPAN_FEEDBACK_SCORES);
                        filterQueryBuilder.bind(statement, filters, FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY);
                    });

            InstrumentAsyncUtils.Segment segment = startSegment(segmentName, "Clickhouse", "get");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private String getTimeField(MetricType metricType) {
        return SPAN_METRICS.contains(metricType) ? "span_time" : "trace_time";
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
