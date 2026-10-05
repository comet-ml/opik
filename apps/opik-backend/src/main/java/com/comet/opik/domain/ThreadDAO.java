package com.comet.opik.domain;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.TraceThreadSortingFactory;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.comet.opik.utils.TruncationUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.FilterUtils.bindTraceThreadSearchCriteria;
import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.newTraceThreadFindTemplate;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.SentinelTranslation.epochToNull;
import static java.util.function.Predicate.not;

@ImplementedBy(ThreadDAOImpl.class)
public interface ThreadDAO {

    Mono<TraceThread.TraceThreadPage> find(int size, int page, TraceSearchCriteria threadSearchCriteria);

    Mono<TraceThread> findById(UUID projectId, String threadId, boolean truncate);

    Flux<TraceThread> search(int limit, TraceSearchCriteria criteria);

    Mono<ProjectStats> getThreadStats(TraceSearchCriteria criteria);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
// TODO: after v1 drop, remove annotation_queue_filters conditions and keep only annotation_queue_id
class ThreadDAOImpl implements ThreadDAO {

    private static final String THREAD_SEARCH_CLAUSE = """
            (ilike(thread_id, :search_text)
            OR ilike(id, :search_text)
            OR ilike(input, :search_text)
            OR ilike(output, :search_text))""";

    /***
     * When treating a list of traces as threads, many aggregations are performed to get the thread details.
     * <p>
     * Please refer to the SELECT_TRACES_THREAD_BY_ID query for more details.
     * <p>
     * {@code annotation_queue_items} is keyed {@code (workspace_id, project_id, queue_id, item_id)}, so the
     * annotation-queue CTE binds {@code queue_id} to the project's thread-scope queues before looking up
     * {@code item_id}. That is what lets the lookup use the full primary key instead of a generic scan of the
     * project's items (OPIK-5592).
     ***/
    // query_plan_join_swap_table=false: spans_agg is 1:1 in rows with traces but orders of magnitude smaller in
    // bytes, so 'auto' ranks them as a tie and can pick the traces payload as the hash build side (OPIK-8511).
    // Dedupe before <filters> so source/environment read each trace's latest row, as FINAL does in the chart and KPI.
    // The truncated copies are aliased *_preview so the thread filters read the full messages, as the count does.
    /**
     * OPIK-7035: resolves one page of thread ids for the default sort from a narrow scan of the window's traces, so
     * {@link #SELECT_TRACES_THREADS_BY_PROJECT_IDS} enriches only those threads. It runs as its own query and its ids
     * are bound into the list query: as a CTE it was re-evaluated at every reference of traces_final and
     * trace_threads_final, about seven times per page (OPIK-8335).
     */
    @VisibleForTesting
    static final String SELECT_PAGE_THREAD_IDS = """
            SELECT pt.thread_id AS thread_id
            FROM (
                SELECT
                    thread_id,
                    minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) AS start_time,
                    maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) AS end_time,
                    max(last_updated_at) AS trace_last_updated_at
                FROM (
                    SELECT id, thread_id, start_time, end_time, last_updated_at
                    FROM traces FINAL
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND thread_id \\<> ''
                      <if(uuid_from_time)> AND id >= :uuid_from_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                          >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                      <if(uuid_to_time)> AND id \\<= :uuid_to_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                          \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                      <if(filters)> AND <filters> <endif>
                      <if(search_text)> AND <search_text> <endif>
                ) AS t
                GROUP BY thread_id
            ) AS pt
            <if(uuid_from_time)>INNER<else>LEFT<endif> JOIN (
                SELECT thread_id, id, last_updated_at
                FROM trace_threads FINAL
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
            ) AS ptt ON pt.thread_id = ptt.thread_id
            ORDER BY if(ptt.last_updated_at = toDateTime64(0, 6, 'UTC'), pt.trace_last_updated_at, ptt.last_updated_at) DESC,
                pt.start_time ASC,
                nullIf(pt.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) DESC,
                ptt.id DESC,
                pt.thread_id
            LIMIT :limit <if(offset)>OFFSET :offset<endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    @VisibleForTesting
    static final String SELECT_TRACES_THREADS_BY_PROJECT_IDS = """
            WITH <if(traces_final_ids)>traces_final_ids AS (
                SELECT id, thread_id
                FROM traces FINAL
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id \\<> ''
                <if(uuid_from_time)> AND id >= :uuid_from_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                    >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                <if(uuid_to_time)> AND id \\<= :uuid_to_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                    \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
            ), <endif>traces_final AS (
                SELECT
                    id,
                    workspace_id,
                    project_id,
                    thread_id,
                    start_time,
                    end_time,
                    input,
                    output,
                    truncated_input,
                    truncated_output,
                    input_length,
                    output_length,
                    truncation_threshold,
                    last_updated_at,
                    last_updated_by,
                    created_by,
                    created_at,
                    environment
                FROM (
                    SELECT
                        *,
                        truncated_input,
                        truncated_output,
                        input_length,
                        output_length
                    FROM traces
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND thread_id \\<> ''
                      <if(page_pushdown)>
                          AND thread_id IN :page_thread_ids
                          <if(uuid_from_time)> AND id >= :uuid_from_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                              >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                          <if(uuid_to_time)> AND id \\<= :uuid_to_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                              \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                      <else>
                          <if(traces_final_ids)>
                              AND id IN (SELECT id FROM traces_final_ids)
                              <if(uuid_from_time)> AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                  >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                              <if(uuid_to_time)> AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                  \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                          <else>
                              <if(uuid_from_time)> AND id >= :uuid_from_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                  >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                              <if(uuid_to_time)> AND id \\<= :uuid_to_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                  \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                              <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                          <endif>
                      <endif>
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <if(page_pushdown)>
                WHERE 1 = 1
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
                <endif>
            ), spans_deduped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    id,
                    last_updated_at,
                    usage,
                    total_estimated_cost,
                    provider
                FROM spans
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  <if(page_pushdown)>
                      AND trace_id IN (SELECT id FROM traces_final)
                  <else>
                      <if(traces_final_ids)>
                          AND trace_id IN (SELECT id FROM traces_final_ids)
                      <else>
                          <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                          <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
                      <endif>
                  <endif>
                ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans_deduped
                GROUP BY workspace_id, project_id, trace_id
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
                    last_updated_at,
                    environment
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                -- Not the row id range: membership follows the window's traces (OPIK-8335). Narrowed to their threads, unlike the chart,
                -- because the join and the feedback-score, comment and annotation-queue lookups each read this CTE, and unnarrowed each
                -- would dedupe every thread row of the project.
                <if(page_pushdown)>
                    AND thread_id IN :page_thread_ids
                <elseif(traces_final_ids)>
                    AND thread_id IN (SELECT thread_id FROM traces_final_ids)
                <elseif(uuid_from_time || uuid_to_time)>
                    AND thread_id IN (SELECT thread_id FROM traces_final)
                <endif>
                <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_deduped AS (
                SELECT *
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        feedback_scores.last_updated_by AS author,
                        CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'thread'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_id
                      AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                    UNION ALL
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        author,
                        source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id IN :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                       <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                        arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                        arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM feedback_scores_grouped
            ), feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                            groupArray(name),
                            groupArray(value)
                    ) AS feedback_scores,
                    groupArray(tuple(
                            name,
                            category_name,
                            value,
                            reason,
                            source,
                            value_by_author,
                            created_at,
                            last_updated_at,
                            created_by,
                            last_updated_by
                               )) AS feedback_scores_list
                FROM feedback_scores_final
                GROUP BY workspace_id, project_id, entity_id
            ), comments_final AS (
              SELECT
                   entity_id,
                   groupArray(tuple(*)) AS comments
              FROM (
                SELECT
                    id,
                    text,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    source_queue_id,
                    entity_id,
                    workspace_id,
                    project_id
                FROM comments
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
              )
              GROUP BY workspace_id, project_id, entity_id
            ), thread_scope_queues AS (
                SELECT id, name
                FROM annotation_queues
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND scope = 'thread'
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), thread_annotation_queue_ids AS (
                 SELECT thread_id,
                        groupArray(id) AS annotation_queue_ids,
                        groupArray(tuple(id, name)) AS annotation_queues
                 FROM (
                    SELECT DISTINCT aqi.queue_id as id, aq.name as name, aqi.item_id as thread_id
                    FROM (
                        SELECT queue_id, item_id
                        FROM annotation_queue_items
                        WHERE workspace_id = :workspace_id
                          AND project_id = :project_id
                          AND queue_id IN (SELECT id FROM thread_scope_queues)
                          AND item_id IN (SELECT thread_model_id FROM trace_threads_final)
                    ) AS aqi
                    JOIN thread_scope_queues AS aq ON aq.id = aqi.queue_id
                 ) AS annotation_queue_ids_with_thread_id
                 GROUP BY thread_id
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                t.id as id,
                t.start_time as start_time,
                t.end_time as end_time,
                t.duration as duration,
                <if(truncate)> replaceRegexpAll(t.truncated_first_message, '<truncate>', '"[image]"') as first_message_preview <else> t.first_message as first_message<endif>,
                <if(truncate)> replaceRegexpAll(t.truncated_last_message, '<truncate>', '"[image]"') as last_message_preview <else> t.last_message as last_message<endif>,
                <if(truncate)> t.first_message_length >= t.first_message_truncation_threshold as first_message_truncated <else> false as first_message_truncated <endif>,
                <if(truncate)> t.last_message_length >= t.last_message_truncation_threshold as last_message_truncated <else> false as last_message_truncated <endif>,
                t.number_of_messages as number_of_messages,
                t.total_estimated_cost as total_estimated_cost,
                t.usage as usage,
                if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                if(tt.status = 'unknown', 'active', tt.status) as status,
                if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                tt.tags as tags,
                if(tt.environment = '', t.environment, tt.environment) as environment,
                fsagg.feedback_scores_list as feedback_scores_list,
                fsagg.feedback_scores as feedback_scores,
                c.comments AS comments
                <if(!exclude_annotation_queues)>, ttaqi.annotation_queues AS annotation_queues<endif>
            FROM (
                SELECT
                    t.thread_id as id,
                    t.workspace_id as workspace_id,
                    t.project_id as project_id,
                    minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as start_time,
                    maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as end_time,
                    if(maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) IS NOT NULL AND notEquals(maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), toDateTime64('1970-01-01 00:00:00.000', 9)) AND minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) IS NOT NULL
                           AND notEquals(minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), toDateTime64('1970-01-01 00:00:00.000', 9)),
                       (dateDiff('microsecond', minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) / 1000.0),
                       NULL) AS duration,
                    countIf(notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) > 0 as has_non_sentinel_trace,
                    if(has_non_sentinel_trace, argMinIf(t.input, t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMin(t.input, t.start_time)) as first_message,
                    if(has_non_sentinel_trace, argMaxIf(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)), notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMax(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) as last_message,
                    if(has_non_sentinel_trace, argMinIf(t.truncated_input, t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMin(t.truncated_input, t.start_time)) as truncated_first_message,
                    if(has_non_sentinel_trace, argMaxIf(t.truncated_output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)), notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMax(t.truncated_output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) as truncated_last_message,
                    if(has_non_sentinel_trace, argMinIf(t.input_length, t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMin(t.input_length, t.start_time)) as first_message_length,
                    if(has_non_sentinel_trace, argMaxIf(t.output_length, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)), notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMax(t.output_length, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) as last_message_length,
                    if(has_non_sentinel_trace, argMinIf(t.truncation_threshold, t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMin(t.truncation_threshold, t.start_time)) as first_message_truncation_threshold,
                    if(has_non_sentinel_trace, argMaxIf(t.truncation_threshold, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)), notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMax(t.truncation_threshold, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) as last_message_truncation_threshold,
                    count(DISTINCT t.id) * 2 as number_of_messages,
                    sum(s.total_estimated_cost) as total_estimated_cost,
                    sumMap(s.usage) as usage,
                    max(t.last_updated_at) as last_updated_at,
                    argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                    argMin(t.created_by, t.created_at) as created_by,
                    min(t.created_at) as created_at,
                    if(has_non_sentinel_trace, argMinIf(t.environment, t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMin(t.environment, t.start_time)) as environment
                FROM traces_final AS t
                    LEFT JOIN spans_agg AS s ON t.id = s.trace_id
                GROUP BY
                    t.workspace_id, t.project_id, t.thread_id
            ) AS t
            <if(uuid_from_time)>INNER<else>LEFT<endif> JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id
                AND t.project_id = tt.project_id
                AND t.id = tt.thread_id
            LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = tt.thread_model_id
            LEFT JOIN comments_final c ON c.entity_id = tt.thread_model_id
            <if(!exclude_annotation_queues || annotation_queue_filters || annotation_queue_id)>
            LEFT JOIN thread_annotation_queue_ids as ttaqi ON ttaqi.thread_id = tt.thread_model_id
            <endif>
            WHERE workspace_id = :workspace_id
            <if(feedback_scores_filters)>
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
                HAVING <feedback_scores_filters>
            )
            <endif>
            <if(feedback_scores_empty_filters)>
            AND (
                thread_model_id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                    OR
                thread_model_id NOT IN (SELECT entity_id FROM fsc)
            )
            <endif>
            <if(trace_thread_filters)>AND<trace_thread_filters><endif>
            <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
            <if(annotation_queue_id)> AND has(ttaqi.annotation_queue_ids, :annotation_queue_id) <endif>
            <if(last_retrieved_id)> AND thread_model_id > :last_retrieved_id<endif>
            <if(stream)>
            ORDER BY workspace_id, project_id, thread_model_id DESC
            <else>
            <if(sort_fields)> ORDER BY <sort_fields>, last_updated_at DESC, thread_model_id DESC <else> ORDER BY last_updated_at DESC, start_time ASC, nullIf(end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) DESC, thread_model_id DESC <endif>
            <endif>
            LIMIT :limit <if(page_pushdown)><else><if(offset)>OFFSET :offset<endif><endif>
            SETTINGS query_plan_join_swap_table = false, log_comment = '<log_comment>'
            ;
            """;

    /***
     * When treating a list of traces as threads, many aggregations are performed to get the thread details.
     * <p>
     * Please refer to the SELECT_TRACES_THREAD_BY_ID query for more details.
     ***/
    // Dedupe before <filters> so source/environment read each trace's latest row, as FINAL does in the chart and KPI.
    @VisibleForTesting
    static final String SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS = """
            WITH <if(traces_final_ids)>traces_final_ids AS (
                SELECT id, thread_id
                FROM traces FINAL
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id \\<> ''
                <if(uuid_from_time)> AND id >= :uuid_from_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                    >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                <if(uuid_to_time)> AND id \\<= :uuid_to_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                    \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
            ), <endif>traces_final AS (
                SELECT
                    id,
                    workspace_id,
                    project_id,
                    thread_id,
                    start_time,
                    end_time,
                    input,
                    output,
                    last_updated_at,
                    last_updated_by,
                    created_by,
                    created_at
                FROM (
                    SELECT *
                    FROM traces
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND thread_id \\<> ''
                      <if(traces_final_ids)>
                          AND id IN (SELECT id FROM traces_final_ids)
                          <if(uuid_from_time)> AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                              >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                          <if(uuid_to_time)> AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                              \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                      <else>
                          <if(uuid_from_time)> AND id >= :uuid_from_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                              >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                          <if(uuid_to_time)> AND id \\<= :uuid_to_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                              \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                          <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                      <endif>
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
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
                    last_updated_at,
                    environment
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                -- Not the row id range: membership follows the window's traces (OPIK-8335). Narrowed to their threads, unlike the chart,
                -- because the join and the feedback-score, comment and annotation-queue lookups each read this CTE, and unnarrowed each
                -- would dedupe every thread row of the project.
                <if(traces_final_ids)>
                    AND thread_id IN (SELECT thread_id FROM traces_final_ids)
                <elseif(uuid_from_time || uuid_to_time)>
                    AND thread_id IN (SELECT thread_id FROM traces_final)
                <endif>
                <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            <if(feedback_scores_needed)>
            , feedback_scores_deduped AS (
                SELECT *
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        feedback_scores.last_updated_by AS author,
                        CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id = :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                    UNION ALL
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        author,
                        source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id = :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                       <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                        arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                        arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM feedback_scores_grouped
            )
            <endif>
            <if(annotation_queue_filters || annotation_queue_id)>
            , thread_annotation_queue_ids AS (
                 SELECT thread_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as thread_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'thread'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                 ) AS annotation_queue_ids_with_thread_id
                 GROUP BY thread_id
            )
            <endif>
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            SELECT
                count(DISTINCT t.id) AS count
            FROM (
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
                        minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as start_time,
                        maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as end_time,
                        if(maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) IS NOT NULL AND notEquals(maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), toDateTime64('1970-01-01 00:00:00.000', 9)) AND minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) IS NOT NULL
                               AND notEquals(minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) / 1000.0),
                           NULL) AS duration,
                        countIf(notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) > 0 as has_non_sentinel_trace,
                        if(has_non_sentinel_trace, argMinIf(t.input, t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMin(t.input, t.start_time)) as first_message,
                        if(has_non_sentinel_trace, argMaxIf(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)), notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMax(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) as last_message,
                        count(DISTINCT t.id) * 2 as number_of_messages,
                        max(t.last_updated_at) as last_updated_at,
                        argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                        argMin(t.created_by, t.created_at) as created_by,
                        min(t.created_at) as created_at
                    FROM traces_final AS t
                    GROUP BY
                        t.workspace_id, t.project_id, t.thread_id
                ) AS t
                <if(uuid_from_time)>INNER<else>LEFT<endif> JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id
                    AND t.project_id = tt.project_id
                    AND t.id = tt.thread_id
                <if(annotation_queue_filters || annotation_queue_id)>
                LEFT JOIN thread_annotation_queue_ids as ttaqi ON ttaqi.thread_id = tt.thread_model_id
                <endif>
                WHERE workspace_id = :workspace_id
                <if(feedback_scores_filters)>
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
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND (
                    thread_model_id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    thread_model_id NOT IN (SELECT entity_id FROM fsc)
                )
                <endif>
                <if(trace_thread_filters)>AND<trace_thread_filters><endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
            <if(annotation_queue_id)> AND has(ttaqi.annotation_queue_ids, :annotation_queue_id) <endif>
            ) AS t
            SETTINGS log_comment = '<log_comment>'
            """;

    /***
     * When treating a list of traces as threads, a number of aggregation are performed to get the thread details.
     * <p>
     * Among the aggregation performed are:
     *  - The duration of the thread, which is calculated as the difference between the start_time and end_time of the first and last trace in the list.
     *  - The first message in the thread, which is the input of the first trace in the list.
     *  - The last message in the thread, which is the output of the last trace in the list.
     *  - The number of messages in the thread, which is the count of the traces in the list multiplied by 2.
     *  - The last updated time of the thread, which is the last_updated_at of the last trace in the list.
     *  - The creator of the thread, which is the created_by of the first trace in the list.
     *  - The creation time of the thread, which is the created_at of the first trace in the list.
     * <p>
     * Two phases, so input/output are read for two traces instead of all of them (OPIK-8678): traces_final keeps
     * narrow columns only, the {@code thread_aggs} scalar aggregates them once and names the first and last trace,
     * and {@code messages} reads the payloads of just those ids, with the same latest-version dedup.
     * <p>
     * The first trace is the earliest start_time and the last the latest end_time, skipping NULL / epoch end_time,
     * as the former {@code argMin(input, start_time)} / {@code argMax(output, nullIf(end_time, epoch))} chose them.
     * Ties are broken explicitly, by the largest id: that is what those first-seen argMin/argMax returned over
     * traces_final, which is sorted by id DESC. first_trace_id maximises (-start_time, id), negated as Decimal128(9)
     * so the legacy DateTime64(9) layout keeps nanosecond order. With no ended trace, last_trace_id is NULL and so is
     * the last message, as the former argMax over an all-NULL key returned. A scalar rather than a CTE keeps the aggregate to a single
     * evaluation: a CTE is inlined at every reference, and each extra pass costs a round trip to the shards.
     * <p>
     * traces_ids matches any version carrying the thread_id, so a trace whose latest version moved to another
     * thread lands in traces_final under that other thread_id. Only the requested thread is aggregated: the moved
     * trace is not part of it, and an extra group would otherwise be a second row in arbitrary order, of which
     * findById keeps the first.
     * <p>
     * Update-before-create placeholders carry the sentinel start time, so the start, end, first and last trace and the
     * environment skip them while the thread has a real trace. A thread made only of placeholders falls back to them
     * and still shows what the updates carried.
     ***/
    // query_plan_join_swap_table=false: spans_agg is 1:1 in rows with traces but orders of magnitude smaller in
    // bytes, so 'auto' ranks them as a tie and can pick the traces payload as the hash build side (OPIK-8511).
    @VisibleForTesting
    static final String SELECT_TRACES_THREAD_BY_ID = """
            WITH traces_ids AS (
                SELECT
                    id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id = :thread_id
            ), traces_final AS (
                SELECT
                    id,
                    workspace_id,
                    project_id,
                    thread_id,
                    start_time,
                    end_time,
                    last_updated_at,
                    last_updated_by,
                    created_by,
                    created_at,
                    environment
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id IN (SELECT id FROM traces_ids)
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), spans_deduped AS (
                SELECT
                    trace_id,
                    id,
                    usage,
                    total_estimated_cost,
                    provider
                FROM spans
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND trace_id IN (SELECT DISTINCT id FROM traces_ids)
                ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans_deduped
                GROUP BY trace_id
            ), (
                SELECT groupArray(tuple(thread_id, workspace_id, project_id, start_time, end_time, duration,
                    first_trace_id, last_trace_id, number_of_messages, total_estimated_cost, usage, last_updated_at,
                    last_updated_by, created_by, created_at, environment))
                FROM (
                    SELECT
                        t.thread_id as thread_id,
                        t.workspace_id as workspace_id,
                        t.project_id as project_id,
                        minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as start_time,
                        maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as end_time,
                        if(maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) IS NOT NULL AND notEquals(maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), toDateTime64('1970-01-01 00:00:00.000', 9)) AND minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) IS NOT NULL
                               AND notEquals(minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) / 1000.0),
                           NULL) AS duration,
                        countIf(notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) > 0 as has_non_sentinel_trace,
                        if(has_non_sentinel_trace,
                           argMaxIf(t.id, (-CAST(t.start_time AS Decimal128(9)), t.id), notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))),
                           argMax(t.id, (-CAST(t.start_time AS Decimal128(9)), t.id))) as first_trace_id,
                        if(has_non_sentinel_trace,
                           argMaxIf(toNullable(t.id), (t.end_time, t.id), t.end_time IS NOT NULL AND t.end_time != toDateTime64('1970-01-01 00:00:00.000', 9) AND notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))),
                           argMaxIf(toNullable(t.id), (t.end_time, t.id), t.end_time IS NOT NULL AND t.end_time != toDateTime64('1970-01-01 00:00:00.000', 9))) as last_trace_id,
                        count(DISTINCT t.id) * 2 as number_of_messages,
                        sum(s.total_estimated_cost) as total_estimated_cost,
                        sumMap(s.usage) as usage,
                        max(t.last_updated_at) as last_updated_at,
                        argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                        argMin(t.created_by, t.created_at) as created_by,
                        min(t.created_at) as created_at,
                        if(has_non_sentinel_trace, argMinIf(t.environment, t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMin(t.environment, t.start_time)) as environment
                    FROM traces_final AS t
                    LEFT JOIN spans_agg AS s ON t.id = s.trace_id
                    WHERE t.thread_id = :thread_id
                    GROUP BY t.workspace_id, t.project_id, t.thread_id
                )
            ) AS thread_aggs, messages AS (
                SELECT
                    mapFromArrays(
                        groupArray(id),
                        groupArray(<if(truncate)>tuple(truncated_input, truncated_output, input_length, output_length, truncation_threshold)<else>tuple(input, output)<endif>)
                    ) AS by_id
                FROM (
                    SELECT
                        id,
                        <if(truncate)>truncated_input, truncated_output, input_length, output_length, truncation_threshold<else>input, output<endif>
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND has(arrayConcat(arrayMap(a -> a.7, thread_aggs), arrayMap(a -> a.8, thread_aggs)), id)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
            ), trace_threads_ids AS (
                SELECT
                    id as thread_model_id
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id = :thread_id
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
                    last_updated_at,
                    environment
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id = :thread_id
                ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_deduped AS (
                SELECT *
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           category_name,
                           value,
                           reason,
                           source,
                           created_by,
                           last_updated_by,
                           created_at,
                           last_updated_at,
                           feedback_scores.last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'thread'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND entity_id IN (SELECT thread_model_id FROM trace_threads_ids)
                    UNION ALL
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        author,
                        source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id = :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_ids)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                        arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                        arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM feedback_scores_grouped
            ), feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                            groupArray(name),
                            groupArray(value)
                    ) AS feedback_scores,
                    groupArray(tuple(
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        value_by_author,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    )) AS feedback_scores_list
                FROM feedback_scores_final
                GROUP BY workspace_id, project_id, entity_id
            ), comments_final AS (
              SELECT
                   entity_id,
                   groupArray(tuple(*)) AS comments
              FROM (
                SELECT
                    id,
                    text,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    source_queue_id,
                    entity_id,
                    workspace_id,
                    project_id
                FROM comments
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT thread_model_id FROM trace_threads_ids)
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
              )
              GROUP BY workspace_id, project_id, entity_id
            ), thread_scope_queues AS (
                SELECT id, name
                FROM annotation_queues
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND scope = 'thread'
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), thread_annotation_queues AS (
                 SELECT thread_id,
                        groupArray(tuple(id, name)) AS annotation_queues
                 FROM (
                    SELECT DISTINCT aqi.queue_id as id, aq.name as name, aqi.item_id as thread_id
                    FROM (
                        SELECT queue_id, item_id
                        FROM annotation_queue_items
                        WHERE workspace_id = :workspace_id
                          AND project_id = :project_id
                          AND queue_id IN (SELECT id FROM thread_scope_queues)
                          AND item_id IN (SELECT thread_model_id FROM trace_threads_ids)
                    ) AS aqi
                    JOIN thread_scope_queues AS aq ON aq.id = aqi.queue_id
                 ) AS queues_with_thread_id
                 GROUP BY thread_id
            )
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                t.thread_id as id,
                t.start_time as start_time,
                t.end_time as end_time,
                t.duration as duration,
                tupleElement(m.by_id[t.first_trace_id], 1) as first_message,
                if(t.last_trace_id IS NULL, NULL, tupleElement(m.by_id[assumeNotNull(t.last_trace_id)], 2)) as last_message,
                <if(truncate)> tupleElement(m.by_id[t.first_trace_id], 3) >= tupleElement(m.by_id[t.first_trace_id], 5) as first_message_truncated <else> false as first_message_truncated <endif>,
                <if(truncate)> if(t.last_trace_id IS NULL, NULL, tupleElement(m.by_id[assumeNotNull(t.last_trace_id)], 4) >= tupleElement(m.by_id[assumeNotNull(t.last_trace_id)], 5)) as last_message_truncated <else> false as last_message_truncated <endif>,
                t.number_of_messages as number_of_messages,
                t.total_estimated_cost as total_estimated_cost,
                t.usage as usage,
                if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                if(tt.status = 'unknown', 'active', tt.status) as status,
                if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                tt.tags as tags,
                if(tt.environment = '', t.environment, tt.environment) as environment,
                fsagg.feedback_scores_list as feedback_scores_list,
                fsagg.feedback_scores as feedback_scores,
                c.comments AS comments,
                ttaq.annotation_queues AS annotation_queues
            FROM (
                SELECT
                    a.1 AS thread_id, a.2 AS workspace_id, a.3 AS project_id, a.4 AS start_time, a.5 AS end_time,
                    a.6 AS duration, a.7 AS first_trace_id, a.8 AS last_trace_id, a.9 AS number_of_messages,
                    a.10 AS total_estimated_cost, a.11 AS usage, a.12 AS last_updated_at, a.13 AS last_updated_by,
                    a.14 AS created_by, a.15 AS created_at, a.16 AS environment
                FROM (SELECT arrayJoin(thread_aggs) AS a)
            ) AS t
            CROSS JOIN messages AS m
            LEFT JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id AND t.project_id = tt.project_id AND t.thread_id = tt.thread_id
            LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = tt.thread_model_id
            LEFT JOIN comments_final c ON c.entity_id = tt.thread_model_id
            LEFT JOIN thread_annotation_queues ttaq ON ttaq.thread_id = tt.thread_model_id
            SETTINGS query_plan_join_swap_table = false, log_comment = '<log_comment>'
            """;

    /***
     * Calculates statistics for threads by performing two-level aggregation:
     * 1. First level: Uses the same thread aggregation as SELECT_TRACES_THREADS_BY_PROJECT_IDS (reusing the exact CTEs and aggregation logic)
     * 2. Second level: Wraps the thread results and calculates stats across all threads (AVG, SUM, quantiles)
     ***/
    // query_plan_join_swap_table=false: spans_agg is 1:1 in rows with traces but orders of magnitude smaller in
    // bytes, so 'auto' ranks them as a tie and can pick the traces payload as the hash build side (OPIK-8511).
    // Dedupe before <filters> so source/environment read each trace's latest row, as FINAL does in the chart and KPI.
    @VisibleForTesting
    static final String SELECT_TRACE_THREADS_STATS = """
            SELECT
                threads.workspace_id as workspace_id,
                threads.project_id as project_id,
                countDistinct(threads.id) AS thread_count,
                arrayMap(
                  v -> toDecimal64(
                         greatest(
                           least(if(isFinite(v), v, 0),  999999999.999999999),
                           -999999999.999999999
                         ),
                         9
                       ),
                  quantiles(0.5, 0.9, 0.99)(threads.duration)
                ) AS duration,
                toInt64(0) AS input,
                toInt64(0) AS output,
                toInt64(0) AS metadata,
                toFloat64(0) AS tags,
                avgMap(threads.usage) as usage,
                sumMap(threads.usage) as usage_sum,
                avgMap(threads.feedback_scores) AS feedback_scores,
                toFloat64(0) AS llm_span_count_avg,
                toFloat64(0) AS span_count_avg,
                avgIf(threads.total_estimated_cost, threads.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sumIf(threads.total_estimated_cost, threads.total_estimated_cost > 0) AS total_estimated_cost_sum_,
                toDecimal128(total_estimated_cost_sum_, 12) AS total_estimated_cost_sum,
                toInt64(0) AS guardrails_failed_count,
                toInt64(0) AS error_count
            FROM (
                WITH <if(traces_final_ids)>traces_final_ids AS (
                    SELECT id, thread_id
                    FROM traces FINAL
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND thread_id \\<> ''
                    <if(uuid_from_time)> AND id >= :uuid_from_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                        >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                        \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                    <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                    <if(filters)> AND <filters> <endif>
                    <if(search_text)> AND <search_text> <endif>
                ), <endif>traces_final AS (
                    SELECT
                        id,
                        workspace_id,
                        project_id,
                        thread_id,
                        start_time,
                        end_time,
                        input,
                        output,
                        last_updated_at,
                        last_updated_by,
                        created_by,
                        created_at
                    FROM (
                        SELECT *
                        FROM traces
                        WHERE workspace_id = :workspace_id
                          AND project_id = :project_id
                          AND thread_id \\<> ''
                          <if(traces_final_ids)>
                              AND id IN (SELECT id FROM traces_final_ids)
                              <if(uuid_from_time)> AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                  >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                              <if(uuid_to_time)> AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                  \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                          <else>
                              <if(uuid_from_time)> AND id >= :uuid_from_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                  >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                              <if(uuid_to_time)> AND id \\<= :uuid_to_time AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                                  \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                              <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                          <endif>
                        ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                ), spans_deduped AS (
                    SELECT
                        workspace_id,
                        project_id,
                        trace_id,
                        id,
                        last_updated_at,
                        usage,
                        total_estimated_cost,
                        provider
                    FROM spans
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      <if(traces_final_ids)>
                          AND trace_id IN (SELECT id FROM traces_final_ids)
                      <else>
                          <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                          <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
                      <endif>
                    ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ), spans_agg AS (
                    SELECT
                        trace_id,
                        sumMap(usage) as usage,
                        sum(total_estimated_cost) as total_estimated_cost,
                        arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                    FROM spans_deduped
                    GROUP BY workspace_id, project_id, trace_id
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
                        last_updated_at,
                        environment
                    FROM trace_threads
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    -- Not the row id range: membership follows the window's traces (OPIK-8335). Narrowed to their threads, unlike the chart,
                    -- because the join and the feedback-score, comment and annotation-queue lookups each read this CTE, and unnarrowed each
                    -- would dedupe every thread row of the project.
                    <if(traces_final_ids)>
                        AND thread_id IN (SELECT thread_id FROM traces_final_ids)
                    <elseif(uuid_from_time || uuid_to_time)>
                        AND thread_id IN (SELECT thread_id FROM traces_final)
                    <endif>
                    <if(traces_pushdown_filter)> AND thread_id = :thread_id_pushdown <endif>
                    ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ), feedback_scores_deduped AS (
                    SELECT *
                    FROM (
                        SELECT
                            workspace_id,
                            project_id,
                            entity_id,
                            name,
                            category_name,
                            value,
                            reason,
                            source,
                            created_by,
                            last_updated_by,
                            created_at,
                            last_updated_at,
                            feedback_scores.last_updated_by AS author,
                            CAST('' AS FixedString(36)) AS source_queue_id
                        FROM feedback_scores
                        WHERE entity_type = 'thread'
                          AND workspace_id = :workspace_id
                          AND project_id IN :project_id
                          AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                        UNION ALL
                        SELECT
                            workspace_id,
                            project_id,
                            entity_id,
                            name,
                            category_name,
                            value,
                            reason,
                            source,
                            created_by,
                            last_updated_by,
                            created_at,
                            last_updated_at,
                            author,
                            source_queue_id
                        FROM authored_feedback_scores
                        WHERE entity_type = 'thread'
                           AND workspace_id = :workspace_id
                           AND project_id IN :project_id
                           AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                           <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                    )
                    ORDER BY last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
                ), feedback_scores_grouped AS (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                    FROM feedback_scores_deduped
                    GROUP BY workspace_id, project_id, entity_id, name
                ), feedback_scores_final AS (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                        IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                        IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                        entries[1].4 AS source,
                        mapFromArrays(
                            arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                            arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                        ) AS value_by_author,
                        arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                        arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                        arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                        arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                    FROM feedback_scores_grouped
                ), feedback_scores_agg AS (
                    SELECT
                        entity_id,
                        mapFromArrays(
                                groupArray(name),
                                groupArray(value)
                        ) AS feedback_scores
                    FROM feedback_scores_final
                    GROUP BY workspace_id, project_id, entity_id
                ), thread_annotation_queue_ids AS (
                     SELECT thread_id,
                            groupArray(id) AS annotation_queue_ids
                     FROM (
                        SELECT DISTINCT aq.id as id, aqi.item_id as thread_id
                        FROM annotation_queue_items aqi
                        JOIN annotation_queues aq ON aq.id = aqi.queue_id
                        WHERE aq.scope = 'thread'
                          AND workspace_id = :workspace_id
                          AND project_id = :project_id
                     ) AS annotation_queue_ids_with_thread_id
                     GROUP BY thread_id
                )
                <if(feedback_scores_empty_filters)>
                 , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                     FROM (
                        SELECT *
                        FROM feedback_scores_final
                        ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                     )
                     GROUP BY entity_id
                     HAVING <feedback_scores_empty_filters>
                )
                <endif>
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
                    t.total_estimated_cost as total_estimated_cost,
                    t.usage as usage,
                    if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                    if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                    if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                    if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                    if(tt.status = 'unknown', 'active', tt.status) as status,
                    if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                    tt.tags as tags,
                    fsagg.feedback_scores as feedback_scores
                FROM (
                    SELECT
                        t.thread_id as id,
                        t.workspace_id as workspace_id,
                        t.project_id as project_id,
                        minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as start_time,
                        maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) as end_time,
                        if(maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) IS NOT NULL AND notEquals(maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), toDateTime64('1970-01-01 00:00:00.000', 9)) AND minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) IS NOT NULL
                               AND notEquals(minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', minIf(t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), maxIf(t.end_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) / 1000.0),
                           NULL) AS duration,
                        countIf(notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))) > 0 as has_non_sentinel_trace,
                        if(has_non_sentinel_trace, argMinIf(t.input, t.start_time, notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMin(t.input, t.start_time)) as first_message,
                        if(has_non_sentinel_trace, argMaxIf(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)), notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))), argMax(t.output, nullIf(t.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)))) as last_message,
                        count(DISTINCT t.id) * 2 as number_of_messages,
                        sum(s.total_estimated_cost) as total_estimated_cost,
                        sumMap(s.usage) as usage,
                        max(t.last_updated_at) as last_updated_at,
                        argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                        argMin(t.created_by, t.created_at) as created_by,
                        min(t.created_at) as created_at
                    FROM traces_final AS t
                        LEFT JOIN spans_agg AS s ON t.id = s.trace_id
                    GROUP BY
                        t.workspace_id, t.project_id, t.thread_id
                ) AS t
                <if(uuid_from_time)>INNER<else>LEFT<endif> JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id
                    AND t.project_id = tt.project_id
                    AND t.id = tt.thread_id
                LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = tt.thread_model_id
                <if(annotation_queue_filters || annotation_queue_id)>
                LEFT JOIN thread_annotation_queue_ids as ttaqi ON ttaqi.thread_id = tt.thread_model_id
                <endif>
                WHERE workspace_id = :workspace_id
                <if(feedback_scores_filters)>
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
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND (
                    thread_model_id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    thread_model_id NOT IN (SELECT entity_id FROM fsc)
                )
                <endif>
                <if(trace_thread_filters)>AND<trace_thread_filters><endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                <if(annotation_queue_id)> AND has(ttaqi.annotation_queue_ids, :annotation_queue_id) <endif>
            ) AS threads
            GROUP BY threads.workspace_id, threads.project_id
            SETTINGS query_plan_join_swap_table = false, log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull TraceThreadSortingFactory traceThreadSortingFactory;
    private final @NonNull OpikConfiguration configuration;

    /**
     * Sort mapping applied under {@code traceColumnsNonNullable}: a thread's {@code end_time} is
     * {@code max(traces.end_time)}, the epoch sentinel when every trace is unfinished; {@code nullIf} restores
     * {@code NULL} so it sorts last in ASC like a Nullable column did. {@code duration} needs no entry — ClickHouse
     * sorts {@code NaN} like {@code NULL}.
     */
    private static final Map<String, String> SORT_FIELD_MAPPING_END_TIME_SENTINEL = Map.of(
            SortableFields.END_TIME, "nullIf(end_time, toDateTime64('1970-01-01 00:00:00.000', 9))");

    /**
     * Determines whether to activate the traces_final_ids CTE for narrowing the raw traces / spans scans
     * in the thread list and count queries. Mirrors {@code TraceDAO#shouldUseTraceIdPrefilter}.
     *
     * <p>Only activates when there are narrowing predicates beyond the workspace/project/uuid-range filters:
     * a free-text search, a TRACE-strategy filter ({@code <filters>}), or the thread_id EQUAL pushdown
     * ({@code traces_pushdown_filter}). When none is present, the downstream CTEs apply the uuid range
     * directly, skipping the prefilter scan.
     *
     * <p>OPIK-7919: a thread_id EQUAL filter is a TRACE_THREAD-strategy filter, so it sets
     * {@code trace_thread_filters} / {@code traces_pushdown_filter} but never {@code filters}. Without the
     * third condition the prefilter stayed off for every single-thread lookup, and spans_deduped deduped
     * the whole project before being LEFT JOINed against one thread's traces — 6M rows / 1.2 GiB and ~5s
     * for a LIMIT 1 read on production. traces_final_ids already renders the same thread_id predicate, so
     * enabling it here narrows the spans scan to that thread's trace ids.
     *
     * <p>Only {@code traces_pushdown_filter} is included, not {@code trace_thread_filters} at large: the
     * other TRACE_THREAD filters (status, tags, ...) are applied by the outer query, not inside
     * traces_final_ids, so they would not narrow the prefilter scan and would pay for it for nothing.
     */
    @VisibleForTesting
    static boolean shouldUseTracesFinalIdsPrefilter(TraceSearchCriteria criteria, ST template) {
        return criteria.searchText() != null
                || template.getAttribute("filters") != null
                || template.getAttribute("traces_pushdown_filter") != null;
    }

    /**
     * OPIK-7035: template attributes that make the page-pushdown unsafe. The pushdown resolves the page in
     * one narrow {@code traces} scan ({@link #SELECT_PAGE_THREAD_IDS}: per-thread min/max sort keys + the filter,
     * joined to {@code trace_threads} for the {@code last_updated_at} coalesce, limit pushed early), then
     * enriches only that page so the wide {@code input}/{@code output} columns and spans are read for
     * ~page-size threads instead of the whole project. It is only output-equivalent when page membership
     * and ordering are fully determined by that narrow {@code traces}+{@code trace_threads} scan. Any of
     * these attributes means the page can only be resolved by the full enrichment query (filters/sorts
     * that need the spans/feedback/annotation joins), so we fall back. A time window is not one of them: the
     * resolver then reads only the window's traces and requires the thread row, as the full query's INNER JOIN
     * does, and breaks ties by the thread row id like the outer ORDER BY, so both paths pick the same page.
     */
    private static final List<String> PAGE_PUSHDOWN_DISQUALIFIERS = List.of(
            "sort_fields", "traces_pushdown_filter",
            "feedback_scores_filters", "feedback_scores_empty_filters",
            "span_feedback_scores_filters", "span_feedback_scores_empty_filters",
            "trace_aggregation_filters", "trace_thread_filters", "annotation_queue_filters",
            "annotation_queue_id", "experiment_filters", "guardrails_filters", "last_retrieved_id",
            "stream");

    /**
     * The page-pushdown is eligible only for the common listing case: default sort over {@code trace_threads}
     * recency, with no filter/sort that needs the wide-column / spans / feedback / annotation joins to
     * determine which threads land on the page. Otherwise the full scan query runs unchanged.
     */
    @VisibleForTesting
    static boolean isPagePushdownEligible(ST template) {
        return PAGE_PUSHDOWN_DISQUALIFIERS.stream().noneMatch(attr -> template.getAttribute(attr) != null);
    }

    @Override
    @WithSpan
    public Mono<TraceThread.TraceThreadPage> find(int size, int page, @NonNull TraceSearchCriteria criteria) {

        return makeMonoContextAware((userName, workspaceId) -> asyncTemplate
                .nonTransaction(connection -> countThreadTotal(criteria, connection, userName, workspaceId)
                        .flatMap(count -> {

                            int offset = (page - 1) * size;

                            var template = newTraceThreadFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS,
                                    criteria,
                                    THREAD_SEARCH_CLAUSE,
                                    traceColumnsNonNullable());

                            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
                            addExcludeFlags(template, criteria);

                            template = template.add("offset", offset)
                                    .add("log_comment", getLogComment("find_threads_by_project", workspaceId, userName,
                                            "page:" + page + ":size:" + size));

                            var finalTemplate = template;
                            Optional.ofNullable(sortingQueryBuilder.toOrderBySql(
                                    criteria.sortingFields(),
                                    traceColumnsNonNullable() ? SORT_FIELD_MAPPING_END_TIME_SENTINEL : null))
                                    .ifPresent(sortFields -> finalTemplate.add("sort_fields", sortFields));

                            var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(criteria.sortingFields());

                            // OPIK-7035: resolve the page first (one narrow filter+order scan over traces,
                            // limit pushed early), then enrich only that page so the wide input/output columns
                            // and spans are read for ~page-size threads instead of the whole project. The page
                            // resolver applies the filter itself, so it is mutually exclusive with the
                            // traces_final_ids prefilter. It runs as a separate query and its ids are bound into
                            // the list query, because a CTE is re-evaluated at every reference. Falls back to the
                            // full query for any filter/sort that needs the joined sources to determine the page.
                            if (isPagePushdownEligible(finalTemplate)) {
                                finalTemplate.add("page_pushdown", true);
                                return findPageThreadIds(criteria, size, offset, connection, workspaceId, userName)
                                        .flatMap(pageThreadIds -> pageThreadIds.isEmpty()
                                                ? Mono.just(new TraceThread.TraceThreadPage(page, 0, count, List.of(),
                                                        traceThreadSortingFactory.getSortableFields()))
                                                : findThreadPage(finalTemplate, criteria, size, page, count,
                                                        connection, workspaceId, hasDynamicKeys,
                                                        statement -> statement.bind("page_thread_ids",
                                                                pageThreadIds.toArray(String[]::new))));
                            }
                            if (shouldUseTracesFinalIdsPrefilter(criteria, finalTemplate)) {
                                finalTemplate.add("traces_final_ids", true);
                            }
                            return findThreadPage(finalTemplate, criteria, size, page, count, connection,
                                    workspaceId, hasDynamicKeys, statement -> statement.bind("offset", offset));
                        })));
    }

    private Mono<List<String>> findPageThreadIds(TraceSearchCriteria criteria, int size, int offset,
            Connection connection, String workspaceId, String userName) {
        var template = newTraceThreadFindTemplate(SELECT_PAGE_THREAD_IDS, criteria, THREAD_SEARCH_CLAUSE,
                traceColumnsNonNullable())
                .add("offset", offset)
                .add("log_comment", getLogComment("find_thread_page_ids", workspaceId, null,
                        "offset:" + offset + ":size:" + size));

        var statement = connection.createStatement(template.render())
                .bind("project_id", criteria.projectId())
                .bind("workspace_id", workspaceId)
                .bind("limit", size)
                .bind("offset", offset);
        bindTraceThreadSearchCriteria(criteria, statement);

        InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "findThreadPageIds");

        return Flux.from(statement.execute())
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("thread_id", String.class)))
                .collectList()
                .doFinally(signalType -> endSegment(segment));
    }

    private Mono<TraceThread.TraceThreadPage> findThreadPage(ST template, TraceSearchCriteria criteria, int size,
            int page, long count, Connection connection, String workspaceId, boolean hasDynamicKeys,
            UnaryOperator<Statement> bindPage) {
        var statement = bindPage.apply(connection.createStatement(template.render())
                .bind("project_id", criteria.projectId())
                .bind("limit", size)
                .bind("workspace_id", workspaceId));

        if (hasDynamicKeys) {
            statement = sortingQueryBuilder.bindDynamicKeys(statement, criteria.sortingFields());
        }

        bindTraceThreadSearchCriteria(criteria, statement);

        InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "findThreads");

        return Flux.from(statement.execute())
                .flatMap(this::mapThreadToDto)
                .collectList()
                .doFinally(signalType -> endSegment(segment))
                .map(threads -> new TraceThread.TraceThreadPage(page, threads.size(), count,
                        threads,
                        traceThreadSortingFactory.getSortableFields()))
                .defaultIfEmpty(TraceThread.TraceThreadPage.empty(page,
                        traceThreadSortingFactory.getSortableFields()));
    }

    private boolean traceColumnsNonNullable() {
        return configuration.getDatabaseAnalyticsDataModel().traceColumnsNonNullable();
    }

    private void addExcludeFlags(ST template, TraceSearchCriteria criteria) {
        var exclude = Optional.ofNullable(criteria.excludeThreadFields()).orElse(Set.of());
        if (exclude.contains(TraceThread.TraceThreadField.ANNOTATION_QUEUES)) {
            template.add("exclude_annotation_queues", true);
        }
    }

    @Override
    public Mono<TraceThread> findById(@NonNull UUID projectId, @NonNull String threadId, boolean truncate) {
        return makeMonoContextAware((userName, workspaceId) -> asyncTemplate.nonTransaction(connection -> {
            var template = TemplateUtils.newST(SELECT_TRACES_THREAD_BY_ID);
            template.add("truncate", truncate)
                    .add("log_comment",
                            getLogComment("find_thread_by_id", workspaceId, userName, threadId));

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("thread_id", threadId)
                    .bind("workspace_id", workspaceId);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "findThreadById");

            return firstThreadOrEmpty(Mono.from(statement.execute())
                    .flatMapMany(this::mapThreadToDto))
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    /**
     * Collapses the by-id thread stream to the first matching thread, or empty when none is found.
     * Using {@code reduce} rather than {@code single()}/{@code singleOrEmpty()} is deliberate:
     * SELECT_TRACES_THREAD_BY_ID can legitimately return more than one row for a single
     * {@code (workspace_id, project_id, thread_id)}. The trace_threads table's dedup/sort key includes
     * the internal thread_model_id ({@code id}), so several thread_model_ids may coexist for one
     * user-facing thread_id; trace_threads_final keeps one row per id ({@code LIMIT 1 BY id}), and the
     * final {@code LEFT JOIN ... ON (workspace_id, project_id, thread_id)} (not on id) fans the single
     * aggregated trace row out to one row per thread_model_id. Each row maps to one TraceThread, so
     * {@code singleOrEmpty()} would throw {@code IndexOutOfBoundsException} ("Source emitted more than
     * one item") and surface as an unmapped HTTP 500. Folding to the first emission tolerates the
     * multi-row result while preserving the not-found (empty) contract.
     */
    @VisibleForTesting
    static Mono<TraceThread> firstThreadOrEmpty(Flux<TraceThread> threads) {
        return threads.reduce((existingThread, ignored) -> existingThread);
    }

    @Override
    @WithSpan
    public Flux<TraceThread> search(int limit, @NonNull TraceSearchCriteria criteria) {
        Preconditions.checkArgument(limit > 0, "limit must be greater than 0");

        return makeFluxContextAware((userName, workspaceId) -> asyncTemplate.stream(connection -> {

            var template = newTraceThreadFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS,
                    criteria,
                    THREAD_SEARCH_CLAUSE,
                    traceColumnsNonNullable());
            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
            addExcludeFlags(template, criteria);

            template.add("limit", limit)
                    .add("stream", true)
                    .add("log_comment", getLogComment("search_threads", workspaceId, userName,
                            "limit:" + limit));

            if (shouldUseTracesFinalIdsPrefilter(criteria, template)) {
                template.add("traces_final_ids", true);
            }

            var statement = connection.createStatement(template.render())
                    .bind("project_id", criteria.projectId())
                    .bind("limit", limit)
                    .bind("workspace_id", workspaceId);

            bindTraceThreadSearchCriteria(criteria, statement);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "threadsSearch");

            return Flux.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        }))
                .flatMap(this::mapThreadToDto)
                .buffer(limit > 100 ? limit / 2 : limit)
                .concatWith(Mono.just(List.of()))
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(Flux::fromIterable);
    }

    @Override
    public Mono<ProjectStats> getThreadStats(@NonNull TraceSearchCriteria criteria) {
        return makeMonoContextAware((userName, workspaceId) -> asyncTemplate.nonTransaction(connection -> {

            var statsSQL = newTraceThreadFindTemplate(SELECT_TRACE_THREADS_STATS,
                    criteria,
                    THREAD_SEARCH_CLAUSE,
                    traceColumnsNonNullable());
            statsSQL.add("log_comment", getLogComment("thread_stats", workspaceId, userName, ""));

            if (shouldUseTracesFinalIdsPrefilter(criteria, statsSQL)) {
                statsSQL.add("traces_final_ids", true);
            }

            var statement = connection.createStatement(statsSQL.render())
                    .bind("project_id", criteria.projectId())
                    .bind("workspace_id", workspaceId);

            bindTraceThreadSearchCriteria(criteria, statement);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "stats");

            return Flux.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(
                            result -> result
                                    .map((row, rowMetadata) -> StatsMapper.mapProjectStats(row, "thread_count")))
                    .singleOrEmpty();
        }));
    }

    private Mono<Long> countThreadTotal(TraceSearchCriteria traceSearchCriteria, Connection connection,
            String userName, String workspaceId) {
        var template = newTraceThreadFindTemplate(SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS,
                traceSearchCriteria,
                THREAD_SEARCH_CLAUSE,
                traceColumnsNonNullable());
        template.add("log_comment", getLogComment("count_threads_by_project", workspaceId, userName, ""));

        if (shouldUseTracesFinalIdsPrefilter(traceSearchCriteria, template)) {
            template.add("traces_final_ids", true);
        }

        if (template.getAttribute("feedback_scores_filters") != null
                || template.getAttribute("feedback_scores_empty_filters") != null) {
            template.add("feedback_scores_needed", true);
        }

        var statement = connection.createStatement(template.render())
                .bind("project_id", traceSearchCriteria.projectId())
                .bind("workspace_id", workspaceId);

        bindTraceThreadSearchCriteria(traceSearchCriteria, statement);

        InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "countThreads");

        return Flux.from(statement.execute())
                .doFinally(signalType -> endSegment(segment))
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    private Publisher<TraceThread> mapThreadToDto(Result result) {
        return result.map((row, rowMetadata) -> TraceThread.builder()
                .id(row.get("id", String.class))
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .startTime(row.get("start_time", Instant.class))
                .endTime(readEpochSentinel(row, "end_time"))
                .duration(row.get("duration", Double.class))
                .firstMessage(Optional.ofNullable(row.get(messageColumn(rowMetadata, "first_message"), String.class))
                        .filter(it -> !it.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata,
                                "first_message_truncated", row, value))
                        .orElse(null))
                .lastMessage(Optional.ofNullable(row.get(messageColumn(rowMetadata, "last_message"), String.class))
                        .filter(it -> !it.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata,
                                "last_message_truncated", row, value))
                        .orElse(null))
                .numberOfMessages(row.get("number_of_messages", Long.class))
                .usage(row.get("usage", Map.class))
                .totalEstimatedCost(Optional.ofNullable(row.get("total_estimated_cost", BigDecimal.class))
                        .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                        .orElse(null))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .createdBy(row.get("created_by", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .status(TraceThreadStatus.fromValue(row.get("status", String.class)).orElse(TraceThreadStatus.ACTIVE))
                .threadModelId(Optional.ofNullable(row.get("thread_model_id", String.class))
                        .filter(StringUtils::isNotBlank)
                        .map(UUID::fromString)
                        .orElse(null))
                .feedbackScores(Optional.ofNullable(row.get("feedback_scores_list", List.class))
                        .filter(not(List::isEmpty))
                        .map(FeedbackScoreMapper::mapFeedbackScores)
                        .orElse(null))
                .comments(Optional
                        .ofNullable(row.get("comments", List[].class))
                        .map(CommentResultMapper::getComments)
                        .orElse(null))
                .tags(Optional
                        .ofNullable(row.get("tags", String[].class))
                        .map(tags -> Arrays.stream(tags).collect(Collectors.toSet()))
                        .filter(set -> !set.isEmpty())
                        .orElse(null))
                .environment(row.get("environment", String.class))
                .annotationQueues(rowMetadata.contains("annotation_queues")
                        ? Optional.ofNullable(row.get("annotation_queues", List[].class))
                                .map(AnnotationQueueReferenceMapper::map)
                                .filter(not(List::isEmpty))
                                .orElse(null)
                        : null)
                .build());
    }

    private static String messageColumn(RowMetadata rowMetadata, String column) {
        var previewColumn = column + "_preview";
        return rowMetadata.contains(previewColumn) ? previewColumn : column;
    }

    /**
     * Reads a {@code DateTime64} column, translating the epoch sentinel to {@code null} only once the columns are
     * non-nullable — symmetric with {@code TraceDAO} so a legitimate epoch value is preserved while the columns
     * are still {@code Nullable}.
     */
    private Instant readEpochSentinel(Row row, String fieldName) {
        var value = row.get(fieldName, Instant.class);
        return traceColumnsNonNullable() ? epochToNull(value) : value;
    }
}
