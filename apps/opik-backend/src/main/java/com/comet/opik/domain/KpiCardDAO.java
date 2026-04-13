package com.comet.opik.domain;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.metrics.KpiCardRequest.EntityType;
import com.comet.opik.api.metrics.KpiCardResponse;
import com.comet.opik.api.metrics.KpiCardResponse.KpiMetric;
import com.comet.opik.api.metrics.KpiCardResponse.KpiMetricType;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(KpiCardDAOImpl.class)
public interface KpiCardDAO {

    Mono<KpiCardResponse> getTraceKpiCards(KpiCardCriteria criteria);

    Mono<KpiCardResponse> getSpanKpiCards(KpiCardCriteria criteria);

    Mono<KpiCardResponse> getThreadKpiCards(KpiCardCriteria criteria);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class KpiCardDAOImpl implements KpiCardDAO {

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;

    private static final String GET_TRACE_KPI_CARDS = """
            WITH feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           last_updated_by AS author
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND entity_id >= :uuid_from_time
                      AND entity_id \\<= :uuid_to_time
                    UNION ALL
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           author
                    FROM authored_feedback_scores
                    WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND entity_id >= :uuid_from_time
                      AND entity_id \\<= :uuid_to_time
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author
             ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_deduped
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
                    duration,
                    error_info
                FROM (
                    SELECT
                        id,
                        if(end_time IS NOT NULL AND start_time IS NOT NULL
                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                         NULL) AS duration,
                        error_info
                    FROM traces FINAL
                    <if(guardrails_filters)>
                    LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = traces.id
                    <endif>
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    AND id >= :uuid_from_time
                    AND id \\<= :uuid_to_time
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
            ), trace_costs AS (
                SELECT trace_id, sum(total_estimated_cost) AS cost
                FROM spans FINAL
                WHERE workspace_id = :workspace_id AND project_id = :project_id
                  AND id >= :uuid_from_time AND id \\<= :uuid_to_time
                  AND trace_id IN (SELECT id FROM traces_filtered)
                GROUP BY trace_id
            )
            SELECT
                COUNTIf(tf.id >= :id_current_start AND tf.id \\<= :id_end) AS current_count,
                COUNTIf(tf.id >= :id_prior_start AND tf.id \\< :id_current_start) AS previous_count,
                COUNTIf(length(tf.error_info) > 0 AND tf.id >= :id_current_start AND tf.id \\<= :id_end) AS current_errors,
                COUNTIf(length(tf.error_info) > 0 AND tf.id >= :id_prior_start AND tf.id \\< :id_current_start) AS previous_errors,
                AVGIf(tf.duration, tf.id >= :id_current_start AND tf.id \\<= :id_end) AS current_avg_duration,
                AVGIf(tf.duration, tf.id >= :id_prior_start AND tf.id \\< :id_current_start) AS previous_avg_duration,
                SUMIf(tc.cost, tf.id >= :id_current_start AND tf.id \\<= :id_end) AS current_total_cost,
                SUMIf(tc.cost, tf.id >= :id_prior_start AND tf.id \\< :id_current_start) AS previous_total_cost
            FROM traces_filtered tf
            LEFT JOIN trace_costs tc ON tf.id = tc.trace_id
            SETTINGS log_comment = '<log_comment>';
            """;

    private static final String GET_SPAN_KPI_CARDS = """
            WITH feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           last_updated_by AS author
                    FROM feedback_scores
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND entity_id >= :uuid_from_time
                      AND entity_id \\<= :uuid_to_time
                    UNION ALL
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           author
                    FROM authored_feedback_scores
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND entity_id >= :uuid_from_time
                      AND entity_id \\<= :uuid_to_time
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author
             ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_deduped
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
                    duration,
                    error_info,
                    total_estimated_cost
                FROM (
                    SELECT
                        id,
                        if(end_time IS NOT NULL AND start_time IS NOT NULL
                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                         NULL) AS duration,
                         error_info,
                         total_estimated_cost
                    FROM spans FINAL
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = spans.id
                    <endif>
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    AND id >= :uuid_from_time
                    AND id \\<= :uuid_to_time
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
            SELECT
                COUNTIf(id >= :id_current_start AND id \\<= :id_end) AS current_count,
                COUNTIf(id >= :id_prior_start AND id \\< :id_current_start) AS previous_count,
                COUNTIf(length(error_info) > 0 AND id >= :id_current_start AND id \\<= :id_end) AS current_errors,
                COUNTIf(length(error_info) > 0 AND id >= :id_prior_start AND id \\< :id_current_start) AS previous_errors,
                AVGIf(duration, id >= :id_current_start AND id \\<= :id_end) AS current_avg_duration,
                AVGIf(duration, id >= :id_prior_start AND id \\< :id_current_start) AS previous_avg_duration,
                SUMIf(total_estimated_cost, id >= :id_current_start AND id \\<= :id_end) AS current_total_cost,
                SUMIf(total_estimated_cost, id >= :id_prior_start AND id \\< :id_current_start) AS previous_total_cost
            FROM spans_filtered
            SETTINGS log_comment = '<log_comment>';
            """;

    private static final String GET_THREAD_KPI_CARDS = """
            WITH traces_final AS (
                SELECT
                    *
                FROM traces FINAL
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND id >= :uuid_from_time AND id \\<= :uuid_to_time
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
                AND id >= :uuid_from_time
                AND id \\<= :uuid_to_time
            ), feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        value,
                        last_updated_at,
                        last_updated_by AS author
                    FROM feedback_scores
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
                    FROM authored_feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id = :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_deduped
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
                    t.duration as duration,
                    if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id
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
            ), thread_costs AS (
                SELECT tr.thread_id AS thread_id, sum(s.total_estimated_cost) AS cost
                FROM (
                    SELECT trace_id, total_estimated_cost
                    FROM spans FINAL
                    WHERE workspace_id = :workspace_id AND project_id = :project_id
                      AND id >= :uuid_from_time AND id \\<= :uuid_to_time
                ) s
                JOIN traces_final tr ON s.trace_id = tr.id
                GROUP BY tr.thread_id
            )
            SELECT
                COUNTIf(tf.thread_model_id >= :id_current_start AND tf.thread_model_id \\<= :id_end) AS current_count,
                COUNTIf(tf.thread_model_id >= :id_prior_start AND tf.thread_model_id \\< :id_current_start) AS previous_count,
                AVGIf(tf.duration, tf.thread_model_id >= :id_current_start AND tf.thread_model_id \\<= :id_end) AS current_avg_duration,
                AVGIf(tf.duration, tf.thread_model_id >= :id_prior_start AND tf.thread_model_id \\< :id_current_start) AS previous_avg_duration,
                SUMIf(tc.cost, tf.thread_model_id >= :id_current_start AND tf.thread_model_id \\<= :id_end) AS current_total_cost,
                SUMIf(tc.cost, tf.thread_model_id >= :id_prior_start AND tf.thread_model_id \\< :id_current_start) AS previous_total_cost
            FROM threads_filtered tf
            LEFT JOIN thread_costs tc ON tf.id = tc.thread_id
            SETTINGS log_comment = '<log_comment>';
            """;

    @Override
    public Mono<KpiCardResponse> getTraceKpiCards(@NonNull KpiCardCriteria criteria) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var st = buildTemplate(GET_TRACE_KPI_CARDS, criteria, workspaceId, "getTraceKpiCards");

            addTraceFilters(st, criteria.filters());

            var statement = buildStatement(connection, st, criteria, workspaceId);
            bindTraceFilters(statement, criteria.filters());

            InstrumentAsyncUtils.Segment segment = startSegment("traceKpiCards", "Clickhouse", "kpi");

            return Mono.from(statement.execute())
                    .flatMap(r -> mapResult(r, EntityType.TRACES))
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    @Override
    public Mono<KpiCardResponse> getSpanKpiCards(@NonNull KpiCardCriteria criteria) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var st = buildTemplate(GET_SPAN_KPI_CARDS, criteria, workspaceId, "getSpanKpiCards");

            addSpanFilters(st, criteria.filters());

            var statement = buildStatement(connection, st, criteria, workspaceId);
            bindSpanFilters(statement, criteria.filters());

            InstrumentAsyncUtils.Segment segment = startSegment("spanKpiCards", "Clickhouse", "kpi");

            return Mono.from(statement.execute())
                    .flatMap(r -> mapResult(r, EntityType.SPANS))
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    @Override
    public Mono<KpiCardResponse> getThreadKpiCards(@NonNull KpiCardCriteria criteria) {
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var st = buildTemplate(GET_THREAD_KPI_CARDS, criteria, workspaceId, "getThreadKpiCards");

            addThreadFilters(st, criteria.filters());

            var statement = buildStatement(connection, st, criteria, workspaceId);
            bindThreadFilters(statement, criteria.filters());

            InstrumentAsyncUtils.Segment segment = startSegment("threadKpiCards", "Clickhouse", "kpi");

            return Mono.from(statement.execute())
                    .flatMap(r -> mapResult(r, EntityType.THREADS))
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    private ST buildTemplate(String query, KpiCardCriteria criteria, String workspaceId,
            String queryName) {
        return getSTWithLogComment(query, "KpiCards_" + queryName, workspaceId, "", criteria.projectId().toString());
    }

    private Statement buildStatement(Connection connection, ST template,
            KpiCardCriteria criteria, String workspaceId) {
        Instant priorStart = getPriorStart(criteria.intervalStart(), criteria.intervalEnd());

        return connection.createStatement(template.render())
                .bind("project_id", criteria.projectId())
                .bind("workspace_id", workspaceId)
                .bind("uuid_from_time", instantToUUIDMapper.toLowerBound(priorStart).toString())
                .bind("uuid_to_time", instantToUUIDMapper.toUpperBound(criteria.intervalEnd()).toString())
                .bind("id_current_start", instantToUUIDMapper.toLowerBound(criteria.intervalStart()).toString())
                .bind("id_prior_start", instantToUUIDMapper.toLowerBound(priorStart).toString())
                .bind("id_end", instantToUUIDMapper.toUpperBound(criteria.intervalEnd()).toString());
    }

    private void addTraceFilters(ST template, List<? extends Filter> filters) {
        Optional.ofNullable(filters).ifPresent(f -> {
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.TRACE)
                    .ifPresent(traceFilters -> template.add("trace_filters", traceFilters));
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.FEEDBACK_SCORES)
                    .ifPresent(scoresFilters -> template.add("trace_feedback_scores_filters", scoresFilters));
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                    .ifPresent(emptyFilters -> template.add("feedback_scores_empty_filters", emptyFilters));
            FilterQueryBuilder.hasGuardrailsFilter(f)
                    .ifPresent(hasGuardrails -> template.add("guardrails_filters", true));
        });
    }

    private void bindTraceFilters(Statement statement, List<? extends Filter> filters) {
        Optional.ofNullable(filters).ifPresent(f -> {
            FilterQueryBuilder.bind(statement, f, FilterStrategy.TRACE);
            FilterQueryBuilder.bind(statement, f, FilterStrategy.FEEDBACK_SCORES);
            FilterQueryBuilder.bind(statement, f, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
        });
    }

    private void addSpanFilters(ST template, List<? extends Filter> filters) {
        Optional.ofNullable(filters).ifPresent(f -> {
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.SPAN)
                    .ifPresent(spanFilters -> template.add("span_filters", spanFilters));
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.SPAN_FEEDBACK_SCORES)
                    .ifPresent(scoresFilters -> template.add("span_feedback_scores_filters", scoresFilters));
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY)
                    .ifPresent(emptyFilters -> template.add("feedback_scores_empty_filters", emptyFilters));
        });
    }

    private void bindSpanFilters(Statement statement, List<? extends Filter> filters) {
        Optional.ofNullable(filters).ifPresent(f -> {
            FilterQueryBuilder.bind(statement, f, FilterStrategy.SPAN);
            FilterQueryBuilder.bind(statement, f, FilterStrategy.SPAN_FEEDBACK_SCORES);
            FilterQueryBuilder.bind(statement, f, FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY);
        });
    }

    private void addThreadFilters(ST template, List<? extends Filter> filters) {
        Optional.ofNullable(filters).ifPresent(f -> {
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.TRACE_THREAD)
                    .ifPresent(threadFilters -> template.add("trace_thread_filters", threadFilters));
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.FEEDBACK_SCORES)
                    .ifPresent(scoresFilters -> template.add("thread_feedback_scores_filters", scoresFilters));
            FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                    .ifPresent(emptyFilters -> template.add("thread_feedback_scores_empty_filters", emptyFilters));
        });
    }

    private void bindThreadFilters(Statement statement, List<? extends Filter> filters) {
        Optional.ofNullable(filters).ifPresent(f -> {
            FilterQueryBuilder.bind(statement, f, FilterStrategy.TRACE_THREAD);
            FilterQueryBuilder.bind(statement, f, FilterStrategy.FEEDBACK_SCORES);
            FilterQueryBuilder.bind(statement, f, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
        });
    }

    private Mono<KpiCardResponse> mapResult(Result result, EntityType entityType) {
        return Mono.from(result.map((row, metadata) -> {
            var stats = new ArrayList<KpiMetric>();

            stats.add(KpiMetric.builder()
                    .type(KpiMetricType.COUNT)
                    .currentValue(filterNan(row.get("current_count", Double.class)))
                    .previousValue(filterNan(row.get("previous_count", Double.class)))
                    .build());

            stats.add(KpiMetric.builder()
                    .type(KpiMetricType.AVG_DURATION)
                    .currentValue(filterNan(row.get("current_avg_duration", Double.class)))
                    .previousValue(filterNan(row.get("previous_avg_duration", Double.class)))
                    .build());

            stats.add(KpiMetric.builder()
                    .type(KpiMetricType.TOTAL_COST)
                    .currentValue(filterNan(row.get("current_total_cost", Double.class)))
                    .previousValue(filterNan(row.get("previous_total_cost", Double.class)))
                    .build());

            if (entityType != EntityType.THREADS) {
                stats.add(KpiMetric.builder()
                        .type(KpiMetricType.ERRORS)
                        .currentValue(filterNan(row.get("current_errors", Double.class)))
                        .previousValue(filterNan(row.get("previous_errors", Double.class)))
                        .build());
            }

            return KpiCardResponse.builder().stats(stats).build();
        }));
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
}
