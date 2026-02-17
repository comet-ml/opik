package com.comet.opik.domain;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.sorting.TraceThreadSortingFactory;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.comet.opik.utils.TruncationUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.DatabaseUtils.bindTraceThreadSearchCriteria;
import static com.comet.opik.infrastructure.DatabaseUtils.newTraceThreadFindTemplate;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
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
class ThreadDAOImpl implements ThreadDAO {

    /***
     * When treating a list of traces as threads, many aggregations are performed to get the thread details.
     * <p>
     * Please refer to the SELECT_TRACES_THREAD_BY_ID query for more details.
     ***/
    private static final String SELECT_TRACES_THREADS_BY_PROJECT_IDS = """
            WITH traces_final AS (
                SELECT
                    *,
                    truncated_input,
                    truncated_output,
                    input_length,
                    output_length
                FROM traces final
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND thread_id \\<> ''
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans final
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND trace_id IN (SELECT DISTINCT id FROM traces_final)
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
                    last_updated_at
                FROM trace_threads final
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)>
                    AND id >= :uuid_from_time
                    <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
                <else>
                AND thread_id IN (SELECT thread_id FROM traces_final)
                <endif>
            ), feedback_scores_combined_raw AS (
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
                    feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
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
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'thread'
                   AND workspace_id = :workspace_id
                   AND project_id IN :project_id
                   AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
             ),
             feedback_scores_with_ranking AS (
                 SELECT workspace_id,
                        *,
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
                     category_name,
                     value,
                     reason,
                     source,
                     created_by,
                     last_updated_by,
                     created_at,
                     last_updated_at,
                     author
                 FROM feedback_scores_with_ranking
                 WHERE rn = 1
             ), feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(value) AS values,
                    groupArray(reason) AS reasons,
                    groupArray(category_name) AS categories,
                    groupArray(author) AS authors,
                    groupArray(source) AS sources,
                    groupArray(created_by) AS created_bies,
                    groupArray(last_updated_by) AS updated_bies,
                    groupArray(created_at) AS created_ats,
                    groupArray(last_updated_at) AS last_updated_ats
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(categories, ', ') AS category_name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value,
                    IF(length(reasons) = 1, arrayElement(reasons, 1), arrayStringConcat(arrayMap(x -> if(x = '', '\\<no reason>', x), reasons), ', ')) AS reason,
                    arrayElement(sources, 1) AS source,
                    mapFromArrays(
                        authors,
                        arrayMap(
                            i -> tuple(values[i], reasons[i], categories[i], sources[i], last_updated_ats[i]),
                            arrayEnumerate(values)
                        )
                    ) AS value_by_author,
                    arrayStringConcat(created_bies, ', ') AS created_by,
                    arrayStringConcat(updated_bies, ', ') AS last_updated_by,
                    arrayMin(created_ats) AS created_at,
                    arrayMax(last_updated_ats) AS last_updated_at
                FROM feedback_scores_combined_grouped
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
                    entity_id,
                    workspace_id,
                    project_id
                FROM comments final
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
              )
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
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
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
                <if(truncate)> replaceRegexpAll(t.truncated_first_message, '<truncate>', '"[image]"') as first_message <else> t.first_message as first_message<endif>,
                <if(truncate)> replaceRegexpAll(t.truncated_last_message, '<truncate>', '"[image]"') as last_message <else> t.last_message as last_message<endif>,
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
                fsagg.feedback_scores_list as feedback_scores_list,
                fsagg.feedback_scores as feedback_scores,
                c.comments AS comments
            FROM (
                SELECT
                    t.thread_id as id,
                    t.workspace_id as workspace_id,
                    t.project_id as project_id,
                    min(t.start_time) as start_time,
                    max(t.end_time) as end_time,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                           AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                       (dateDiff('microsecond', start_time, end_time) / 1000.0),
                       NULL) AS duration,
                    argMin(t.input, t.start_time) as first_message,
                    argMax(t.output, t.end_time) as last_message,
                    argMin(t.truncated_input, t.start_time) as truncated_first_message,
                    argMax(t.truncated_output, t.end_time) as truncated_last_message,
                    argMin(t.input_length, t.start_time) as first_message_length,
                    argMax(t.output_length, t.end_time) as last_message_length,
                    argMin(t.truncation_threshold, t.start_time) as first_message_truncation_threshold,
                    argMax(t.truncation_threshold, t.end_time) as last_message_truncation_threshold,
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
            LEFT JOIN comments_final c ON c.entity_id = tt.thread_model_id
            <if(annotation_queue_filters)>
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
            <if(last_retrieved_id)> AND thread_model_id > :last_retrieved_id<endif>
            <if(stream)>
            ORDER BY workspace_id, project_id, thread_model_id DESC
            <else>
            <if(sort_fields)> ORDER BY <sort_fields>, last_updated_at DESC <else> ORDER BY last_updated_at DESC, start_time ASC, end_time DESC <endif>
            <endif>
            LIMIT :limit <if(offset)>OFFSET :offset<endif>
            ;
            """;

    /***
     * When treating a list of traces as threads, many aggregations are performed to get the thread details.
     * <p>
     * Please refer to the SELECT_TRACES_THREAD_BY_ID query for more details.
     ***/
    private static final String SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS = """
            WITH traces_final AS (
                SELECT
                    *
                FROM traces final
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND thread_id \\<> ''
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans final
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND trace_id IN (SELECT DISTINCT id FROM traces_final)
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
                    last_updated_at
                FROM trace_threads final
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)>
                    AND id >= :uuid_from_time
                    <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
                <else>
                AND thread_id IN (SELECT thread_id FROM traces_final)
                <endif>
            ), feedback_scores_combined_raw AS (
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
                    feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
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
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'thread'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
                   AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
             ),
             feedback_scores_with_ranking AS (
                 SELECT workspace_id,
                        *,
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
                     category_name,
                     value,
                     reason,
                     source,
                     created_by,
                     last_updated_by,
                     created_at,
                     last_updated_at,
                     author
                 FROM feedback_scores_with_ranking
                 WHERE rn = 1
             ), feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(value) AS values,
                    groupArray(reason) AS reasons,
                    groupArray(category_name) AS categories,
                    groupArray(author) AS authors,
                    groupArray(source) AS sources,
                    groupArray(created_by) AS created_bies,
                    groupArray(last_updated_by) AS updated_bies,
                    groupArray(created_at) AS created_ats,
                    groupArray(last_updated_at) AS last_updated_ats
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(categories, ', ') AS category_name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value,
                    IF(length(reasons) = 1, arrayElement(reasons, 1), arrayStringConcat(arrayMap(x -> if(x = '', '\\<no reason>', x), reasons), ', ')) AS reason,
                    arrayElement(sources, 1) AS source,
                    mapFromArrays(
                        authors,
                        arrayMap(
                            i -> tuple(values[i], reasons[i], categories[i], sources[i], last_updated_ats[i]),
                            arrayEnumerate(values)
                        )
                    ) AS value_by_author,
                    arrayStringConcat(created_bies, ', ') AS created_by,
                    arrayStringConcat(updated_bies, ', ') AS last_updated_by,
                    arrayMin(created_ats) AS created_at,
                    arrayMax(last_updated_ats) AS last_updated_at
                FROM feedback_scores_combined_grouped
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
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
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
                    t.total_estimated_cost as total_estimated_cost,
                    t.usage as usage,
                    if(tt.created_by = '', t.created_by, tt.created_by) as created_by,
                    if(tt.last_updated_by = '', t.last_updated_by, tt.last_updated_by) as last_updated_by,
                    if(tt.last_updated_at == toDateTime64(0, 6, 'UTC'), t.last_updated_at, tt.last_updated_at) as last_updated_at,
                    if(tt.created_at = toDateTime64(0, 9, 'UTC'), t.created_at, tt.created_at) as created_at,
                    if(tt.status = 'unknown', 'active', tt.status) as status,
                    if(LENGTH(CAST(tt.thread_model_id AS Nullable(String))) > 0, tt.thread_model_id, NULL) as thread_model_id,
                    tt.tags as tags,
                    fsagg.feedback_scores_list as feedback_scores_list,
                    fsagg.feedback_scores as feedback_scores
                FROM (
                    SELECT
                        t.thread_id as id,
                        t.workspace_id as workspace_id,
                        t.project_id as project_id,
                        min(t.start_time) as start_time,
                        max(t.end_time) as end_time,
                        if(end_time IS NOT NULL AND start_time IS NOT NULL
                               AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', start_time, end_time) / 1000.0),
                           NULL) AS duration,
                        argMin(t.input, t.start_time) as first_message,
                        argMax(t.output, t.end_time) as last_message,
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
                <if(annotation_queue_filters)>
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
            ) AS t
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
     ***/
    private static final String SELECT_TRACES_THREAD_BY_ID = """
            WITH traces_final AS (
                SELECT
                    *,
                    truncated_input,
                    truncated_output,
                    input_length,
                    output_length,
                    truncation_threshold
                FROM traces final
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND thread_id = :thread_id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans final
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND trace_id IN (SELECT DISTINCT id FROM traces_final)
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
                    last_updated_at
                FROM trace_threads final
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND thread_id = :thread_id
            ), feedback_scores_combined_raw AS (
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
                       feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
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
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'thread'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
                   AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
             ),
             feedback_scores_with_ranking AS (
                 SELECT workspace_id,
                        *,
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
                     category_name,
                     value,
                     reason,
                     source,
                     created_by,
                     last_updated_by,
                     created_at,
                     last_updated_at,
                     author
                 FROM feedback_scores_with_ranking
                 WHERE rn = 1
             ), feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(value) AS values,
                    groupArray(reason) AS reasons,
                    groupArray(category_name) AS categories,
                    groupArray(author) AS authors,
                    groupArray(source) AS sources,
                    groupArray(created_by) AS created_bies,
                    groupArray(last_updated_by) AS updated_bies,
                    groupArray(created_at) AS created_ats,
                    groupArray(last_updated_at) AS last_updated_ats
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(categories, ', ') AS category_name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value,
                    IF(length(reasons) = 1, arrayElement(reasons, 1), arrayStringConcat(arrayMap(x -> if(x = '', '\\<no reason>', x), reasons), ', ')) AS reason,
                    arrayElement(sources, 1) AS source,
                    mapFromArrays(
                        authors,
                        arrayMap(
                            i -> tuple(values[i], reasons[i], categories[i], sources[i], last_updated_ats[i]),
                            arrayEnumerate(values)
                        )
                    ) AS value_by_author,
                    arrayStringConcat(created_bies, ', ') AS created_by,
                    arrayStringConcat(updated_bies, ', ') AS last_updated_by,
                    arrayMin(created_ats) AS created_at,
                    arrayMax(last_updated_ats) AS last_updated_at
                 FROM feedback_scores_combined_grouped
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
                    entity_id,
                    workspace_id,
                    project_id
                FROM comments final
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
              )
              GROUP BY workspace_id, project_id, entity_id
            )
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                t.thread_id as id,
                t.start_time as start_time,
                t.end_time as end_time,
                t.duration as duration,
                <if(truncate)> t.truncated_first_message as first_message <else> t.first_message as first_message<endif>,
                <if(truncate)> t.truncated_last_message as last_message <else> t.last_message as last_message<endif>,
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
                fsagg.feedback_scores_list as feedback_scores_list,
                fsagg.feedback_scores as feedback_scores,
                c.comments AS comments
            FROM (
                SELECT
                    t.thread_id as thread_id,
                    t.workspace_id as workspace_id,
                    t.project_id as project_id,
                    min(t.start_time) as start_time,
                    max(t.end_time) as end_time,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                           AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                       (dateDiff('microsecond', start_time, end_time) / 1000.0),
                       NULL) AS duration,
                    argMin(t.input, t.start_time) as first_message,
                    argMax(t.output, t.end_time) as last_message,
                    argMin(t.truncated_input, t.start_time) as truncated_first_message,
                    argMax(t.truncated_output, t.end_time) as truncated_last_message,
                    argMin(t.input_length, t.start_time) as first_message_length,
                    argMax(t.output_length, t.end_time) as last_message_length,
                    argMin(t.truncation_threshold, t.start_time) as first_message_truncation_threshold,
                    argMax(t.truncation_threshold, t.end_time) as last_message_truncation_threshold,
                    count(DISTINCT t.id) * 2 as number_of_messages,
                    sum(s.total_estimated_cost) as total_estimated_cost,
                    sumMap(s.usage) as usage,
                    max(t.last_updated_at) as last_updated_at,
                    argMax(t.last_updated_by, t.last_updated_at) as last_updated_by,
                    argMin(t.created_by, t.created_at) as created_by,
                    min(t.created_at) as created_at
                FROM traces_final AS t
                LEFT JOIN spans_agg AS s ON t.id = s.trace_id
                GROUP BY t.workspace_id, t.project_id, t.thread_id
            ) AS t
            LEFT JOIN trace_threads_final AS tt ON t.workspace_id = tt.workspace_id AND t.project_id = tt.project_id AND t.thread_id = tt.thread_id
            LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = tt.thread_model_id
            LEFT JOIN comments_final c ON c.entity_id = tt.thread_model_id
            """;

    /***
     * Calculates statistics for threads by performing two-level aggregation:
     * 1. First level: Uses the same thread aggregation as SELECT_TRACES_THREADS_BY_PROJECT_IDS (reusing the exact CTEs and aggregation logic)
     * 2. Second level: Wraps the thread results and calculates stats across all threads (AVG, SUM, quantiles)
     ***/
    private static final String SELECT_TRACE_THREADS_STATS = """
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
                WITH traces_final AS (
                    SELECT
                        *
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND thread_id \\<> ''
                    <if(uuid_from_time)>AND id >= :uuid_from_time<endif>
                    <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
                ), spans_agg AS (
                    SELECT
                        trace_id,
                        sumMap(usage) as usage,
                        sum(total_estimated_cost) as total_estimated_cost,
                        arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                    FROM spans final
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND trace_id IN (SELECT DISTINCT id FROM traces_final)
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
                        last_updated_at
                    FROM trace_threads final
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND thread_id IN (SELECT thread_id FROM traces_final)
                ), feedback_scores_combined_raw AS (
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
                        feedback_scores.last_updated_by AS author
                    FROM feedback_scores FINAL
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
                        author
                    FROM authored_feedback_scores FINAL
                    WHERE entity_type = 'thread'
                       AND workspace_id = :workspace_id
                       AND project_id IN :project_id
                       AND entity_id IN (SELECT thread_model_id FROM trace_threads_final)
                 ),
                 feedback_scores_with_ranking AS (
                     SELECT workspace_id,
                            *,
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
                         category_name,
                         value,
                         reason,
                         source,
                         created_by,
                         last_updated_by,
                         created_at,
                         last_updated_at,
                         author
                     FROM feedback_scores_with_ranking
                     WHERE rn = 1
                 ), feedback_scores_combined_grouped AS (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        groupArray(value) AS values,
                        groupArray(reason) AS reasons,
                        groupArray(category_name) AS categories,
                        groupArray(author) AS authors,
                        groupArray(source) AS sources,
                        groupArray(created_by) AS created_bies,
                        groupArray(last_updated_by) AS updated_bies,
                        groupArray(created_at) AS created_ats,
                        groupArray(last_updated_at) AS last_updated_ats
                    FROM feedback_scores_combined
                    GROUP BY workspace_id, project_id, entity_id, name
                ), feedback_scores_final AS (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        arrayStringConcat(categories, ', ') AS category_name,
                        IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value,
                        IF(length(reasons) = 1, arrayElement(reasons, 1), arrayStringConcat(arrayMap(x -> if(x = '', '\\<no reason>', x), reasons), ', ')) AS reason,
                        arrayElement(sources, 1) AS source,
                        mapFromArrays(
                            authors,
                            arrayMap(
                                i -> tuple(values[i], reasons[i], categories[i], sources[i], last_updated_ats[i]),
                                arrayEnumerate(values)
                            )
                        ) AS value_by_author,
                        arrayStringConcat(created_bies, ', ') AS created_by,
                        arrayStringConcat(updated_bies, ', ') AS last_updated_by,
                        arrayMin(created_ats) AS created_at,
                        arrayMax(last_updated_ats) AS last_updated_at
                     FROM feedback_scores_combined_grouped
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
                          <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                          <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
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
                        min(t.start_time) as start_time,
                        max(t.end_time) as end_time,
                        if(max(t.end_time) IS NOT NULL AND min(t.start_time) IS NOT NULL
                               AND notEquals(min(t.start_time), toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', min(t.start_time), max(t.end_time)) / 1000.0),
                           NULL) AS duration,
                        argMin(t.input, t.start_time) as first_message,
                        argMax(t.output, t.end_time) as last_message,
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
                <if(annotation_queue_filters)>
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
            ) AS threads
            GROUP BY threads.workspace_id, threads.project_id
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull TraceThreadSortingFactory traceThreadSortingFactory;

    @Override
    @WithSpan
    public Mono<TraceThread.TraceThreadPage> find(int size, int page, @NonNull TraceSearchCriteria criteria) {

        return asyncTemplate.nonTransaction(connection -> countThreadTotal(criteria, connection)
                .flatMap(count -> {

                    int offset = (page - 1) * size;

                    var template = newTraceThreadFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS, criteria);

                    template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

                    template = template.add("offset", offset);

                    var finalTemplate = template;
                    Optional.ofNullable(sortingQueryBuilder.toOrderBySql(criteria.sortingFields()))
                            .ifPresent(sortFields -> finalTemplate.add("sort_fields", sortFields));

                    var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(criteria.sortingFields());

                    var statement = connection.createStatement(template.render())
                            .bind("project_id", criteria.projectId())
                            .bind("limit", size)
                            .bind("offset", offset);

                    if (hasDynamicKeys) {
                        statement = sortingQueryBuilder.bindDynamicKeys(statement, criteria.sortingFields());
                    }

                    bindTraceThreadSearchCriteria(criteria, statement);

                    InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "findThreads");

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                            .flatMap(this::mapThreadToDto)
                            .collectList()
                            .doFinally(signalType -> endSegment(segment))
                            .map(threads -> new TraceThread.TraceThreadPage(page, threads.size(), count, threads,
                                    traceThreadSortingFactory.getSortableFields()))
                            .defaultIfEmpty(TraceThread.TraceThreadPage.empty(page,
                                    traceThreadSortingFactory.getSortableFields()));
                }));
    }

    @Override
    public Mono<TraceThread> findById(@NonNull UUID projectId, @NonNull String threadId, boolean truncate) {
        return asyncTemplate.nonTransaction(connection -> {
            var template = TemplateUtils.newST(SELECT_TRACES_THREAD_BY_ID);
            template.add("truncate", truncate);

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("thread_id", threadId);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "findThreadById");

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(this::mapThreadToDto)
                    .singleOrEmpty()
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Flux<TraceThread> search(int limit, @NonNull TraceSearchCriteria criteria) {
        Preconditions.checkArgument(limit > 0, "limit must be greater than 0");

        return asyncTemplate.stream(connection -> {

            var template = newTraceThreadFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS, criteria);
            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

            template.add("limit", limit)
                    .add("stream", true);

            var statement = connection.createStatement(template.render())
                    .bind("project_id", criteria.projectId())
                    .bind("limit", limit);

            bindTraceThreadSearchCriteria(criteria, statement);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "threadsSearch");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment));
        })
                .flatMap(this::mapThreadToDto)
                .buffer(limit > 100 ? limit / 2 : limit)
                .concatWith(Mono.just(List.of()))
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(Flux::fromIterable);
    }

    @Override
    public Mono<ProjectStats> getThreadStats(@NonNull TraceSearchCriteria criteria) {
        return asyncTemplate.nonTransaction(connection -> {

            var statsSQL = newTraceThreadFindTemplate(SELECT_TRACE_THREADS_STATS, criteria);

            var statement = connection.createStatement(statsSQL.render())
                    .bind("project_id", criteria.projectId());

            bindTraceThreadSearchCriteria(criteria, statement);

            InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "stats");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(
                            result -> result
                                    .map((row, rowMetadata) -> StatsMapper.mapProjectStats(row, "thread_count")))
                    .singleOrEmpty();
        });
    }

    private Mono<Long> countThreadTotal(TraceSearchCriteria traceSearchCriteria, Connection connection) {
        var template = newTraceThreadFindTemplate(SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS, traceSearchCriteria);

        var statement = connection.createStatement(template.render())
                .bind("project_id", traceSearchCriteria.projectId());

        bindTraceThreadSearchCriteria(traceSearchCriteria, statement);

        InstrumentAsyncUtils.Segment segment = startSegment("threads", "Clickhouse", "countThreads");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment))
                .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    private Publisher<TraceThread> mapThreadToDto(Result result) {
        return result.map((row, rowMetadata) -> TraceThread.builder()
                .id(row.get("id", String.class))
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .startTime(row.get("start_time", Instant.class))
                .endTime(row.get("end_time", Instant.class))
                .duration(row.get("duration", Double.class))
                .firstMessage(Optional.ofNullable(row.get("first_message", String.class))
                        .filter(it -> !it.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata,
                                "first_message_truncated", row, value))
                        .orElse(null))
                .lastMessage(Optional.ofNullable(row.get("last_message", String.class))
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
                .build());
    }
}
