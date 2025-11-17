package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse.BiInformation;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.GuardrailsValidation;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceDetails;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.api.sorting.TraceThreadSortingFactory;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.domain.utils.DemoDataExclusionUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import reactor.core.publisher.SignalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.comet.opik.api.ErrorInfo.ERROR_INFO_TYPE;
import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.api.TraceCountResponse.WorkspaceTraceCount;
import static com.comet.opik.api.TraceThread.TraceThreadPage;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;
import static java.util.function.Predicate.not;

@ImplementedBy(TraceDAOImpl.class)
interface TraceDAO {

    Mono<UUID> insert(Trace trace, Connection connection);

    Mono<Void> update(TraceUpdate traceUpdate, UUID id, Connection connection);

    Mono<Void> delete(Set<UUID> ids, UUID projectId, Connection connection);

    Mono<Trace> findById(UUID id, Connection connection);

    Flux<Trace> findByIds(List<UUID> ids, Connection connection);

    Mono<TraceDetails> getTraceDetailsById(UUID id, Connection connection);

    Mono<TracePage> find(int size, int page, TraceSearchCriteria traceSearchCriteria, Connection connection);

    Mono<Void> partialInsert(UUID projectId, TraceUpdate traceUpdate, UUID traceId, Connection connection);

    Mono<List<WorkspaceAndResourceId>> getTraceWorkspace(Set<UUID> traceIds, Connection connection);

    Mono<Long> batchInsert(List<Trace> traces, Connection connection);

    Flux<WorkspaceTraceCount> countTracesPerWorkspace(Map<UUID, Instant> excludedProjectIds);

    Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId, Connection connection);

    Mono<UUID> getProjectIdFromTrace(UUID traceId);

    Flux<BiInformation> getTraceBIInformation(Map<UUID, Instant> excludedProjectIds);

    Mono<ProjectStats> getStats(TraceSearchCriteria criteria);

    Mono<Long> getDailyTraces(Map<UUID, Instant> excludedProjectIds);

    Mono<Map<UUID, ProjectStats>> getStatsByProjectIds(List<UUID> projectIds, String workspaceId);

    Mono<TraceThreadPage> findThreads(int size, int page, TraceSearchCriteria threadSearchCriteria);

    Mono<Set<UUID>> getTraceIdsByThreadIds(UUID projectId, List<String> threadIds, Connection connection);

    Mono<TraceThread> findThreadById(UUID projectId, String threadId, boolean truncate);

    Mono<Trace> getPartialById(UUID id);

    Flux<Trace> search(int limit, TraceSearchCriteria criteria);

    Mono<Long> countTraces(Set<UUID> projectIds);

    Flux<TraceThread> threadsSearch(int limit, TraceSearchCriteria criteria);

    Mono<List<TraceThread>> getMinimalThreadInfoByIds(UUID projectId, Set<String> threadId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceDAOImpl implements TraceDAO {

    private static final String BATCH_INSERT = """
            INSERT INTO traces(
                id,
                project_id,
                workspace_id,
                name,
                start_time,
                end_time,
                input,
                output,
                metadata,
                tags,
                last_updated_at,
                error_info,
                created_by,
                last_updated_by,
                thread_id,
                visibility_mode,
                truncation_threshold
            )
            VALUES
                <items:{item |
                    (
                        :id<item.index>,
                        :project_id<item.index>,
                        :workspace_id,
                        :name<item.index>,
                        parseDateTime64BestEffort(:start_time<item.index>, 9),
                        if(:end_time<item.index> IS NULL, NULL, parseDateTime64BestEffort(:end_time<item.index>, 9)),
                        :input<item.index>,
                        :output<item.index>,
                        :metadata<item.index>,
                        :tags<item.index>,
                        if(:last_updated_at<item.index> IS NULL, NULL, parseDateTime64BestEffort(:last_updated_at<item.index>, 6)),
                        :error_info<item.index>,
                        :user_name,
                        :user_name,
                        :thread_id<item.index>,
                        if(:visibility_mode<item.index> IS NULL, 'default', :visibility_mode<item.index>),
                        :truncation_threshold<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    /**
     * This query handles the insertion of a new trace into the database in two cases:
     * 1. When the trace does not exist in the database.
     * 2. When the trace exists in the database but the provided trace has different values for the fields such as end_time, input, output, metadata and tags.
     **/
    //TODO: refactor to implement proper conflict resolution
    private static final String INSERT = """
            INSERT INTO traces (
                id,
                project_id,
                workspace_id,
                name,
                start_time,
                end_time,
                input,
                output,
                metadata,
                tags,
                error_info,
                created_at,
                created_by,
                last_updated_by,
                thread_id,
                visibility_mode,
                truncation_threshold
            )
            SELECT
                new_trace.id as id,
                multiIf(
                    LENGTH(CAST(old_trace.project_id AS Nullable(String))) > 0 AND notEquals(old_trace.project_id, new_trace.project_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_trace.project_id AS Nullable(String))) > 0, old_trace.project_id,
                    new_trace.project_id
                ) as project_id,
                new_trace.workspace_id as workspace_id,
                multiIf(
                    LENGTH(old_trace.name) > 0, old_trace.name,
                    new_trace.name
                ) as name,
                multiIf(
                    notEquals(old_trace.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.start_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.start_time,
                    new_trace.start_time
                ) as start_time,
                multiIf(
                    isNotNull(old_trace.end_time), old_trace.end_time,
                    new_trace.end_time
                ) as end_time,
                multiIf(
                    LENGTH(old_trace.input) > 0, old_trace.input,
                    new_trace.input
                ) as input,
                multiIf(
                    LENGTH(old_trace.output) > 0, old_trace.output,
                    new_trace.output
                ) as output,
                multiIf(
                    LENGTH(old_trace.metadata) > 0, old_trace.metadata,
                    new_trace.metadata
                ) as metadata,
                multiIf(
                    notEmpty(old_trace.tags), old_trace.tags,
                    new_trace.tags
                ) as tags,
                multiIf(
                    LENGTH(old_trace.error_info) > 0, old_trace.error_info,
                    new_trace.error_info
                ) as error_info,
                multiIf(
                    notEquals(old_trace.created_at, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.created_at >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.created_at,
                    new_trace.created_at
                ) as created_at,
                multiIf(
                    LENGTH(old_trace.created_by) > 0, old_trace.created_by,
                    new_trace.created_by
                ) as created_by,
                new_trace.last_updated_by as last_updated_by,
                multiIf(
                    LENGTH(old_trace.thread_id) > 0, old_trace.thread_id,
                    new_trace.thread_id
                ) as thread_id,
                multiIf(
                    notEquals(old_trace.visibility_mode, 'unknown'), old_trace.visibility_mode,
                    new_trace.visibility_mode
                ) as visibility_mode,
                new_trace.truncation_threshold as truncation_threshold
            FROM (
                SELECT
                    :id as id,
                    :project_id as project_id,
                    :workspace_id as workspace_id,
                    :name as name,
                    parseDateTime64BestEffort(:start_time, 9) as start_time,
                    <if(end_time)> parseDateTime64BestEffort(:end_time, 9) as end_time, <else> null as end_time, <endif>
                    :input as input,
                    :output as output,
                    :metadata as metadata,
                    :tags as tags,
                    :error_info as error_info,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by,
                    :thread_id as thread_id,
                    if(:visibility_mode IS NULL, 'default', :visibility_mode) as visibility_mode,
                    :truncation_threshold as truncation_threshold
            ) as new_trace
            LEFT JOIN (
                SELECT
                    *
                FROM traces
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) as old_trace
            ON new_trace.id = old_trace.id
            ;
            """;

    /***
     * Handles the update of a trace when the trace already exists in the database.
     ***/
    private static final String UPDATE = """
            INSERT INTO traces (
            	id, project_id, workspace_id, name, start_time, end_time, input, output, metadata, tags, error_info, created_at, created_by, last_updated_by, thread_id, visibility_mode, truncation_threshold
            )
            SELECT
            	id,
            	project_id,
            	workspace_id,
            	<if(name)> :name <else> name <endif> as name,
            	start_time,
            	<if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> end_time <endif> as end_time,
            	<if(input)> :input <else> input <endif> as input,
            	<if(output)> :output <else> output <endif> as output,
            	<if(metadata)> :metadata <else> metadata <endif> as metadata,
            	<if(tags)> :tags <else> tags <endif> as tags,
            	<if(error_info)> :error_info <else> error_info <endif> as error_info,
            	created_at,
            	created_by,
                :user_name as last_updated_by,
                <if(thread_id)> :thread_id <else> thread_id <endif> as thread_id,
                visibility_mode,
                :truncation_threshold as truncation_threshold
            FROM traces
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SELECT_BY_IDS = """
            WITH feedback_scores_combined_raw AS (
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
                WHERE entity_type = 'trace'
                   AND workspace_id = :workspace_id
                   AND entity_id IN :ids
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
               WHERE entity_type = 'trace'
                 AND workspace_id = :workspace_id
                 AND entity_id IN :ids
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
             ),
            feedback_scores_combined_grouped AS (
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
                     IF(length(reasons) = 1, arrayElement(reasons, 1), arrayStringConcat(arrayMap(x -> if(x = '', '<no reason>', x), reasons), ', ')) AS reason,
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
            )
            SELECT
                t.*,
                t.id as id,
                t.project_id as project_id,
                sumMap(s.usage) as usage,
                sum(s.total_estimated_cost) as total_estimated_cost,
                COUNT(s.id) AS span_count,
                toInt64(countIf(s.type = 'llm')) AS llm_span_count,
                arraySort(groupUniqArrayIf(s.provider, s.provider != '')) as providers,
                groupUniqArrayArray(c.comments_array) as comments,
                any(fs.feedback_scores_list) as feedback_scores_list,
                any(gr.guardrails) as guardrails_validations
            FROM (
                SELECT
                    *,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                                AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                            (dateDiff('microsecond', start_time, end_time) / 1000.0),
                            NULL) AS duration
                FROM traces
                WHERE workspace_id = :workspace_id
                AND id IN :ids
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS t
            LEFT JOIN (
                SELECT
                    trace_id,
                    usage,
                    total_estimated_cost,
                    id,
                    type,
                    provider
                FROM spans
                WHERE workspace_id = :workspace_id
                  AND trace_id IN :ids
                ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS s ON t.id = s.trace_id
            LEFT JOIN (
                SELECT
                    entity_id,
                    groupArray(tuple(*)) AS comments_array
                FROM (
                    SELECT
                        id,
                        text,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by,
                        entity_id
                    FROM comments
                    WHERE workspace_id = :workspace_id
                    AND entity_id IN :ids
                    ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                GROUP BY entity_id
            ) AS c ON t.id = c.entity_id
            LEFT JOIN (
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
            ) AS fs ON t.id = fs.entity_id
            LEFT JOIN (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    groupArray(tuple(
                         entity_id,
                         secondary_entity_id,
                         project_id,
                         name,
                         result
                    )) as guardrails
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND entity_id IN :ids
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ) AS gr ON t.id = gr.entity_id
            GROUP BY
                t.*
            ;
            """;

    private static final String SELECT_DETAILS_BY_ID = """
            SELECT DISTINCT
                workspace_id,
                project_id
            FROM traces
            WHERE id = :id
            ;
            """;

    private static final String SELECT_BY_PROJECT_ID = """
            WITH feedback_scores_combined_raw AS (
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
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND project_id = :project_id
                UNION ALL
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
                 FROM authored_feedback_scores FINAL
                 WHERE entity_type = 'trace'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
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
             ),
             feedback_scores_combined_grouped AS (
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
            )
            , feedback_scores_agg AS (
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
            ), guardrails_agg AS (
                SELECT
                    entity_id,
                    groupArray(tuple(
                         entity_id,
                         secondary_entity_id,
                         project_id,
                         name,
                         result
                    )) as guardrails_list,
                    if(has(groupArray(result), 'failed'), 'failed', 'passed') as guardrails_result
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    COUNT(DISTINCT id) as span_count,
                    toInt64(countIf(type = 'llm')) as llm_span_count,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans final
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                GROUP BY workspace_id, project_id, trace_id
            ), comments_agg AS (
                SELECT
                    entity_id,
                    groupArray(tuple(id, text, created_at, last_updated_at, created_by, last_updated_by)) AS comments_array
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
                    FROM comments
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                GROUP BY workspace_id, project_id, entity_id
            ), trace_annotation_queue_ids AS (
                 SELECT trace_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as trace_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
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
            , traces_final AS (
                SELECT
                    t.* <if(exclude_fields)>EXCEPT (<exclude_fields>) <endif>,
                    truncated_input,
                    truncated_output,
                    input_length,
                    output_length,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                         NULL) AS duration
                FROM traces t
                    LEFT JOIN guardrails_agg gagg ON gagg.entity_id = t.id
                <if(sort_has_feedback_scores)>
                LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = t.id
                <endif>
                <if(sort_has_span_statistics)>
                LEFT JOIN spans_agg s ON t.id = s.trace_id
                <endif>
                <if(annotation_queue_filters)>
                LEFT JOIN trace_annotation_queue_ids as taqi ON taqi.trace_id = t.id
                <endif>
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)> AND id >= :uuid_from_time <endif>
                <if(uuid_to_time)> AND id \\<= :uuid_to_time <endif>
                <if(last_received_id)> AND id \\< :last_received_id <endif>
                <if(filters)> AND <filters> <endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                <if(feedback_scores_filters)>
                 AND id IN (
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
                 <if(trace_aggregation_filters)>
                 AND id IN (
                    SELECT
                        trace_id
                    FROM spans_agg
                    WHERE <trace_aggregation_filters>
                 )
                 <endif>
                 <if(feedback_scores_empty_filters)>
                 AND (
                    id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT entity_id FROM fsc)
                 )
                 <endif>
                 ORDER BY <if(sort_fields)> <sort_fields>, id DESC, last_updated_at DESC <else>(workspace_id, project_id, id) DESC, last_updated_at DESC <endif>
                 LIMIT 1 BY id
                 LIMIT :limit <if(offset)>OFFSET :offset <endif>
            )
            SELECT
                  t.* <if(exclude_fields)>EXCEPT (<exclude_fields>, input, output, metadata) <else> EXCEPT (input, output, metadata)<endif>
                  <if(!exclude_input)>, <if(truncate)> replaceRegexpAll(truncated_input, '<truncate>', '"[image]"') as input <else> input as input <endif><endif>
                  <if(!exclude_output)>, <if(truncate)> replaceRegexpAll(truncated_output, '<truncate>', '"[image]"') as output <else> output as output <endif><endif>
                  <if(!exclude_metadata)>, <if(truncate)> replaceRegexpAll(metadata, '<truncate>', '"[image]"') as metadata <else> metadata <endif><endif>
                  <if(truncate)>, input_length >= truncation_threshold as input_truncated<endif>
                  <if(truncate)>, output_length >= truncation_threshold as output_truncated<endif>
                  <if(!exclude_feedback_scores)>
                  , fsagg.feedback_scores_list as feedback_scores_list
                  , fsagg.feedback_scores as feedback_scores
                  <endif>
                  <if(!exclude_usage)>, s.usage as usage<endif>
                  <if(!exclude_total_estimated_cost)>, s.total_estimated_cost as total_estimated_cost<endif>
                  <if(!exclude_comments)>, c.comments_array as comments <endif>
                  <if(!exclude_guardrails_validations)>, gagg.guardrails_list as guardrails_validations<endif>
                  <if(!exclude_span_count)>, s.span_count AS span_count<endif>
                  <if(!exclude_llm_span_count)>, s.llm_span_count AS llm_span_count<endif>
                  , s.providers AS providers
             FROM traces_final t
             LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = t.id
             LEFT JOIN spans_agg s ON t.id = s.trace_id
             LEFT JOIN comments_agg c ON t.id = c.entity_id
             LEFT JOIN guardrails_agg gagg ON gagg.entity_id = t.id
             ORDER BY <if(sort_fields)> <sort_fields>, id DESC <else>(workspace_id, project_id, id) DESC, last_updated_at DESC <endif>
            ;
            """;

    private static final String TRACE_COUNT_BY_WORKSPACE_ID = """
            SELECT
                 workspace_id,
                 COUNT(DISTINCT id) as trace_count
             FROM traces
             WHERE created_at BETWEEN toStartOfDay(yesterday()) AND toStartOfDay(today())
             <if(excluded_project_ids)> AND id NOT IN (
                SELECT DISTINCT id FROM traces WHERE project_id IN :excluded_project_ids
                <if(demo_data_created_at)> AND created_at \\<= parseDateTime64BestEffort(:demo_data_created_at, 9)<endif>
             )
             <endif>
             GROUP BY workspace_id
            ;
            """;

    private static final String TRACE_DAILY_BI_INFORMATION = """
            SELECT
                 workspace_id,
                 created_by AS user,
                 COUNT(DISTINCT id) AS trace_count
            FROM traces
            WHERE created_at BETWEEN toStartOfDay(yesterday()) AND toStartOfDay(today())
            <if(excluded_project_ids)> AND id NOT IN (
                SELECT DISTINCT id FROM traces WHERE project_id IN :excluded_project_ids
                <if(demo_data_created_at)> AND created_at \\<= parseDateTime64BestEffort(:demo_data_created_at, 9)<endif>
            )
            <endif>
            GROUP BY workspace_id, created_by
            ;
            """;

    private static final String COUNT_BY_PROJECT_ID = """
            WITH feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                   feedback_scores.last_updated_by AS author
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
                       last_updated_at,
                       author
                 FROM authored_feedback_scores FINAL
                 WHERE entity_type = 'trace'
                   AND workspace_id = :workspace_id
                   AND project_id = :project_id
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
                     value,
                     last_updated_at
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
                    if(has(groupArray(result), 'failed'), 'failed', 'passed') as guardrails_result
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ), trace_annotation_queue_ids AS (
                 SELECT trace_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as trace_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
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
                count(id) as count
            FROM (
                SELECT
                    t.id
                    <if(trace_aggregation_filters)>
                    ,sumMap(s.usage) as usage
                    ,sum(s.total_estimated_cost) as total_estimated_cost
                    ,toInt64(countIf(s.type = 'llm')) as llm_span_count
                    <endif>
                FROM (
                    SELECT
                        id,
                        if(end_time IS NOT NULL AND start_time IS NOT NULL
                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                         NULL) AS duration
                    FROM traces
                        LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = traces.id
                    <endif>
                    <if(annotation_queue_filters)>
                    LEFT JOIN trace_annotation_queue_ids as taqi ON taqi.trace_id = traces.id
                    <endif>
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    <if(uuid_from_time)> AND id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time <endif>
                    <if(filters)> AND <filters> <endif>
                    <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                    <if(feedback_scores_filters)>
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
                        HAVING <feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS t
                <if(trace_aggregation_filters)>
                LEFT JOIN (
                    SELECT
                        trace_id,
                        usage,
                        total_estimated_cost,
                        type
                    FROM spans
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS s ON t.id = s.trace_id
                GROUP BY
                    t.id
                HAVING <trace_aggregation_filters>
                <endif>
            ) AS latest_rows
            ;
            """;

    private static final String DELETE_BY_ID = """
            DELETE FROM traces
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            <if(project_id)>AND project_id = :project_id<endif>
            ;
            """;

    private static final String SELECT_TRACE_ID_AND_WORKSPACE = """
            SELECT
                DISTINCT id, workspace_id
            FROM traces
            WHERE id IN :traceIds
            ;
            """;

    /**
     * This query is used when updates are processed before inserts, and the trace does not exist in the database.
     * <p>
     * The query will insert/update a new trace with the provided values such as end_time, input, output, metadata and tags.
     * In case the values are not provided, the query will use the default values such value are interpreted in other queries as null.
     * <p>
     * This happens because the query is used in a patch endpoint which allows partial updates, so the query will update only the provided fields.
     * The remaining fields will be updated/inserted once the POST arrives with the all mandatory fields to create the trace.
     */
    //TODO: refactor to implement proper conflict resolution
    private static final String INSERT_UPDATE = """
            INSERT INTO traces (
                id, project_id, workspace_id, name, start_time, end_time, input, output, metadata, tags, error_info, created_at, created_by, last_updated_by, thread_id, visibility_mode, truncation_threshold
            )
            SELECT
                new_trace.id as id,
                multiIf(
                    LENGTH(CAST(old_trace.project_id AS Nullable(String))) > 0 AND notEquals(old_trace.project_id, new_trace.project_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_trace.project_id AS Nullable(String))) > 0, old_trace.project_id,
                    new_trace.project_id
                ) as project_id,
                new_trace.workspace_id as workspace_id,
                multiIf(
                    LENGTH(new_trace.name) > 0, new_trace.name,
                    LENGTH(old_trace.name) > 0, old_trace.name,
                    new_trace.name
                ) as name,
                multiIf(
                    notEquals(old_trace.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.start_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.start_time,
                    new_trace.start_time
                ) as start_time,
                multiIf(
                    notEquals(new_trace.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND new_trace.end_time >= toDateTime64('1970-01-01 00:00:00.000', 9), new_trace.end_time,
                    notEquals(old_trace.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.end_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.end_time,
                    new_trace.end_time
                ) as end_time,
                multiIf(
                    LENGTH(new_trace.input) > 0, new_trace.input,
                    LENGTH(old_trace.input) > 0, old_trace.input,
                    new_trace.input
                ) as input,
                multiIf(
                    LENGTH(new_trace.output) > 0, new_trace.output,
                    LENGTH(old_trace.output) > 0, old_trace.output,
                    new_trace.output
                ) as output,
                multiIf(
                    LENGTH(new_trace.metadata) > 0, new_trace.metadata,
                    LENGTH(old_trace.metadata) > 0, old_trace.metadata,
                    new_trace.metadata
                ) as metadata,
                multiIf(
                    notEmpty(new_trace.tags), new_trace.tags,
                    notEmpty(old_trace.tags), old_trace.tags,
                    new_trace.tags
                ) as tags,
                multiIf(
                    LENGTH(new_trace.error_info) > 0, new_trace.error_info,
                    LENGTH(old_trace.error_info) > 0, old_trace.error_info,
                    new_trace.error_info
                ) as error_info,
                multiIf(
                    notEquals(old_trace.created_at, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.created_at >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.created_at,
                    new_trace.created_at
                ) as created_at,
                multiIf(
                    LENGTH(old_trace.created_by) > 0, old_trace.created_by,
                    new_trace.created_by
                ) as created_by,
                new_trace.last_updated_by as last_updated_by,
                multiIf(
                    LENGTH(old_trace.thread_id) > 0, old_trace.thread_id,
                    new_trace.thread_id
                ) as thread_id,
                multiIf(
                    notEquals(old_trace.visibility_mode, 'unknown'), old_trace.visibility_mode,
                    new_trace.visibility_mode
                ) as visibility_mode,
                new_trace.truncation_threshold as truncation_threshold
            FROM (
                SELECT
                    :id as id,
                    :project_id as project_id,
                    :workspace_id as workspace_id,
                    <if(name)> :name <else> '' <endif> as name,
                    toDateTime64('1970-01-01 00:00:00.000', 9) as start_time,
                    <if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> null <endif> as end_time,
                    <if(input)> :input <else> '' <endif> as input,
                    <if(output)> :output <else> '' <endif> as output,
                    <if(metadata)> :metadata <else> '' <endif> as metadata,
                    <if(tags)> :tags <else> [] <endif> as tags,
                    <if(error_info)> :error_info <else> '' <endif> as error_info,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by,
                    <if(thread_id)> :thread_id <else> '' <endif> as thread_id,
                    <if(visibility_mode)> :visibility_mode <else> 'unknown' <endif> as visibility_mode,
                    :truncation_threshold as truncation_threshold
            ) as new_trace
            LEFT JOIN (
                SELECT
                    *
                FROM traces
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) as old_trace
            ON new_trace.id = old_trace.id
            ;
            """;

    private static final String SELECT_PARTIAL_BY_ID = """
            SELECT
                project_id,
                start_time
            FROM traces
            WHERE id = :id
            AND workspace_id = :workspace_id
            AND id = :id
            ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SELECT_TRACE_LAST_UPDATED_AT = """
            SELECT
                t.project_id as project_id,
                MAX(t.last_updated_at) as last_updated_at
            FROM traces t
            WHERE t.workspace_id = :workspace_id
            AND t.project_id IN :project_ids
            GROUP BY t.project_id
            ;
            """;
    private static final String SELECT_PROJECT_ID_FROM_TRACE = """
            SELECT
                DISTINCT project_id
            FROM traces
            WHERE id = :id
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String SELECT_TRACES_STATS = """
             WITH spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    COUNT(DISTINCT id) as span_count,
                    toInt64(countIf(type = 'llm')) as llm_span_count
                FROM spans final
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                GROUP BY workspace_id, project_id, trace_id
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
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND project_id IN :project_ids
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
                WHERE entity_type = 'trace'
                   AND workspace_id = :workspace_id
                   AND project_id IN :project_ids
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
            ),
            guardrails_agg AS (
                SELECT
                    entity_id,
                    countIf(DISTINCT id, result = 'failed') AS failed_count,
                    if(has(groupArray(result), 'failed'), 'failed', 'passed') as guardrails_result
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ), trace_annotation_queue_ids AS (
                 SELECT trace_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as trace_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_ids
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
            )
            <if(project_stats)>
            ,    error_count_current AS (
                    SELECT
                        project_id,
                        count(error_info) AS recent_error_count
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    AND error_info != ''
                    AND start_time BETWEEN toStartOfDay(subtractDays(now(), 7)) AND now64(9)
                    GROUP BY workspace_id, project_id
                ),
                error_count_past_period AS (
                    SELECT
                        project_id,
                        count(error_info) AS past_period_error_count
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    AND error_info != ''
                    AND start_time \\< toStartOfDay(subtractDays(now(), 7))
                    GROUP BY workspace_id, project_id
                )
            <endif>
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_combined
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , trace_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    id,
                    if(input_length > 0, 1, 0) as input_count,
                    if(output_length > 0, 1, 0) as output_count,
                    if(metadata_length > 0, 1, 0) as metadata_count,
                    length(tags) as tags_length,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                            AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                        (dateDiff('microsecond', start_time, end_time) / 1000.0),
                        NULL) as duration,
                    error_info
                FROM traces final
                LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                <if(feedback_scores_empty_filters)>
                LEFT JOIN fsc ON fsc.entity_id = traces.id
                <endif>
                <if(annotation_queue_filters)>
                LEFT JOIN trace_annotation_queue_ids as taqi ON taqi.trace_id = traces.id
                <endif>
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                <if(uuid_from_time)>AND id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
                <if(filters)> AND <filters> <endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                <if(feedback_scores_filters)>
                AND id IN (
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
                <if(trace_aggregation_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM spans_agg
                    WHERE <trace_aggregation_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND fsc.feedback_scores_count = 0
                <endif>
            )
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                countDistinct(t.id) AS trace_count,
                arrayMap(
                  v -> toDecimal64(
                         greatest(
                           least(if(isFinite(v), v, 0),  999999999.999999999),
                           -999999999.999999999
                         ),
                         9
                       ),
                  quantiles(0.5, 0.9, 0.99)(t.duration)
                ) AS duration,
                sum(input_count) AS input,
                sum(output_count) AS output,
                sum(metadata_count) AS metadata,
                avg(tags_length) AS tags,
                avgMap(s.usage) as usage,
                avgMap(f.feedback_scores) AS feedback_scores,
                avg(s.llm_span_count) AS llm_span_count_avg,
                avg(s.span_count) AS span_count_avg,
                avgIf(s.total_estimated_cost, s.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sumIf(s.total_estimated_cost, s.total_estimated_cost > 0) AS total_estimated_cost_sum_,
                toDecimal128(total_estimated_cost_sum_, 12) AS total_estimated_cost_sum,
                sum(g.failed_count) AS guardrails_failed_count,
                <if(project_stats)>
                any(ec.recent_error_count) AS recent_error_count,
                any(ecl.past_period_error_count) AS past_period_error_count
                <else>
                countIf(t.error_info, t.error_info != '') AS error_count
                <endif>
            FROM trace_final t
            LEFT JOIN spans_agg AS s ON t.id = s.trace_id
            LEFT JOIN feedback_scores_agg as f ON t.id = f.entity_id
            LEFT JOIN guardrails_agg as g ON t.id = g.entity_id
            <if(project_stats)>
            LEFT JOIN error_count_current ec ON t.project_id = ec.project_id
            LEFT JOIN error_count_past_period ecl ON t.project_id = ecl.project_id
            <endif>
            GROUP BY t.workspace_id, t.project_id
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

    private static final String SELECT_TRACE_IDS_BY_THREAD_IDS = """
            SELECT DISTINCT id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND thread_id IN :thread_ids
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

    public static final String SELECT_COUNT_TRACES_BY_PROJECT_IDS = """
            SELECT
                count(distinct id) as count
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id IN :project_ids
            """;

    private static final String SELECT_MINIMAL_THREAD_INFO_BY_IDS = """
            SELECT
                t.id as id,
                if(LENGTH(CAST(tt.id AS Nullable(String))) > 0, tt.id, '') as thread_model_id,
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                t.created_by as created_by,
                t.created_at as created_at,
                tt.status as status
            FROM (
                SELECT
                    inner_t.thread_id as id,
                    inner_t.project_id as project_id,
                    inner_t.workspace_id as workspace_id,
                    argMin(inner_t.created_by, inner_t.created_at)  as created_by,
                    min(inner_t.created_at) as created_at
                FROM (
                    SELECT
                        thread_id,
                        workspace_id,
                        project_id,
                        created_by,
                        created_at
                    FROM traces
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND thread_id IN :thread_ids
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) inner_t
                GROUP BY inner_t.workspace_id, inner_t.project_id, inner_t.thread_id
            ) t
            LEFT JOIN trace_threads tt ON t.workspace_id = tt.workspace_id
              AND t.project_id = tt.project_id
              AND t.id = tt.thread_id
            """;

    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull TraceSortingFactory sortingFactory;
    private final @NonNull TraceThreadSortingFactory traceThreadSortingFactory;
    private final @NonNull OpikConfiguration configuration;

    @Override
    @WithSpan
    public Mono<UUID> insert(@NonNull Trace trace, @NonNull Connection connection) {

        var template = buildInsertTemplate(trace);

        Statement statement = buildInsertStatement(trace, connection, template);

        Segment segment = startSegment("traces", "Clickhouse", "insert");

        return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                .doFinally(signalType -> endSegment(segment))
                .thenReturn(trace.id());

    }

    private Statement buildInsertStatement(Trace trace, Connection connection, ST template) {
        Statement statement = connection.createStatement(template.render())
                .bind("id", trace.id())
                .bind("project_id", trace.projectId())
                .bind("name", StringUtils.defaultIfBlank(trace.name(), ""))
                .bind("start_time", trace.startTime().toString())
                .bind("input", Objects.toString(trace.input(), ""))
                .bind("output", Objects.toString(trace.output(), ""))
                .bind("metadata", Objects.toString(trace.metadata(), ""))
                .bind("thread_id", StringUtils.defaultIfBlank(trace.threadId(), ""));

        if (trace.endTime() != null) {
            statement.bind("end_time", trace.endTime().toString());
        }

        if (trace.tags() != null) {
            statement.bind("tags", trace.tags().toArray(String[]::new));
        } else {
            statement.bind("tags", new String[]{});
        }

        if (trace.errorInfo() != null) {
            statement.bind("error_info", JsonUtils.readTree(trace.errorInfo()).toString());
        } else {
            statement.bind("error_info", "");
        }

        if (trace.visibilityMode() != null) {
            statement.bind("visibility_mode", trace.visibilityMode().getValue());
        } else {
            statement.bindNull("visibility_mode", String.class);
        }

        TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold", configuration);

        return statement;
    }

    private ST buildInsertTemplate(Trace trace) {
        var template = TemplateUtils.newST(INSERT);

        Optional.ofNullable(trace.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime));

        return template;
    }

    @Override
    @WithSpan
    public Mono<Void> update(@NonNull TraceUpdate traceUpdate, @NonNull UUID id, @NonNull Connection connection) {
        return update(id, traceUpdate, connection).then();
    }

    private Mono<? extends Result> update(UUID id, TraceUpdate traceUpdate, Connection connection) {

        var template = buildUpdateTemplate(traceUpdate, UPDATE);

        String sql = template.render();

        Statement statement = createUpdateStatement(id, traceUpdate, connection, sql);

        Segment segment = startSegment("traces", "Clickhouse", "update");

        return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private Statement createUpdateStatement(UUID id, TraceUpdate traceUpdate, Connection connection, String sql) {
        Statement statement = connection.createStatement(sql);

        bindUpdateParams(traceUpdate, statement);

        statement.bind("id", id);
        return statement;
    }

    private void bindUpdateParams(TraceUpdate traceUpdate, Statement statement) {
        if (StringUtils.isNotBlank(traceUpdate.name())) {
            statement.bind("name", traceUpdate.name());
        }

        Optional.ofNullable(traceUpdate.input())
                .ifPresent(input -> statement.bind("input", input.toString()));

        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> statement.bind("output", output.toString()));

        Optional.ofNullable(traceUpdate.tags())
                .ifPresent(tags -> statement.bind("tags", tags.toArray(String[]::new)));

        Optional.ofNullable(traceUpdate.metadata())
                .ifPresent(metadata -> statement.bind("metadata", metadata.toString()));

        Optional.ofNullable(traceUpdate.errorInfo())
                .ifPresent(errorInfo -> statement.bind("error_info", JsonUtils.readTree(errorInfo).toString()));

        Optional.ofNullable(traceUpdate.endTime())
                .ifPresent(endTime -> statement.bind("end_time", endTime.toString()));

        if (StringUtils.isNotBlank(traceUpdate.threadId())) {
            statement.bind("thread_id", traceUpdate.threadId());
        }

        TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold", configuration);
    }

    private ST buildUpdateTemplate(TraceUpdate traceUpdate, String update) {
        var template = TemplateUtils.newST(update);

        if (StringUtils.isNotBlank(traceUpdate.name())) {
            template.add("name", traceUpdate.name());
        }

        Optional.ofNullable(traceUpdate.input())
                .ifPresent(input -> template.add("input", input.toString()));

        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> template.add("output", output.toString()));

        Optional.ofNullable(traceUpdate.tags())
                .ifPresent(tags -> template.add("tags", tags.toString()));

        Optional.ofNullable(traceUpdate.metadata())
                .ifPresent(metadata -> template.add("metadata", metadata.toString()));

        Optional.ofNullable(traceUpdate.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime.toString()));

        Optional.ofNullable(traceUpdate.errorInfo())
                .ifPresent(errorInfo -> template.add("error_info", JsonUtils.readTree(errorInfo).toString()));

        if (StringUtils.isNotBlank(traceUpdate.threadId())) {
            template.add("thread_id", traceUpdate.threadId());
        }

        return template;
    }

    private Flux<? extends Result> getDetailsById(UUID id, Connection connection) {
        var statement = connection.createStatement(SELECT_DETAILS_BY_ID)
                .bind("id", id);

        Segment segment = startSegment("traces", "Clickhouse", "getDetailsById");

        return Flux.from(statement.execute())
                .doFinally(signalType -> endSegment(segment));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids, UUID projectId, @NonNull Connection connection) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("Deleting traces, count '{}'{}", ids.size(),
                projectId != null ? " for project id '" + projectId + "'" : "");

        var template = TemplateUtils.newST(DELETE_BY_ID);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template.add("project_id", id));

        var statement = connection.createStatement(template.render())
                .bind("ids", ids.toArray(UUID[]::new));

        if (projectId != null) {
            statement.bind("project_id", projectId);
        }

        var segment = startSegment("traces", "Clickhouse", "delete");
        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment))
                .then();
    }

    @Override
    @WithSpan
    public Mono<Trace> findById(@NonNull UUID id, @NonNull Connection connection) {
        return findByIds(List.of(id), connection)
                .singleOrEmpty();
    }

    @Override
    @WithSpan
    public Flux<Trace> findByIds(@NonNull List<UUID> ids, @NonNull Connection connection) {
        Preconditions.checkArgument(!ids.isEmpty(), "ids must not be empty");
        log.info("Finding traces by IDs in batch, count '{}'", ids.size());

        var statement = connection.createStatement(SELECT_BY_IDS)
                .bind("ids", ids.toArray(UUID[]::new));

        Segment segment = startSegment("traces", "Clickhouse", "findByIds");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> endSegment(segment))
                .flatMap(result -> mapToDto(result, Set.of()));
    }

    @Override
    public Mono<TraceDetails> getTraceDetailsById(@NonNull UUID id, @NonNull Connection connection) {
        return getDetailsById(id, connection)
                .flatMap(this::mapToTraceDetails)
                .singleOrEmpty();
    }

    private <T> T getValue(Set<Trace.TraceField> exclude, Trace.TraceField field, Row row, String fieldName,
            Class<T> clazz) {
        return exclude.contains(field) ? null : row.get(fieldName, clazz);
    }

    private Publisher<Trace> mapToDto(Result result, Set<Trace.TraceField> exclude) {

        return result.map((row, rowMetadata) -> mapRowToTrace(row, rowMetadata, exclude));
    }

    private Trace mapRowToTrace(Row row, RowMetadata rowMetadata, Set<Trace.TraceField> exclude) {
        @SuppressWarnings("unchecked")
        List<String> providers = (List<String>) row.get(Trace.TraceField.PROVIDERS.getValue(), List.class);

        JsonNode metadata = getMetadataWithProviders(row, exclude, providers);

        return Trace.builder()
                .id(row.get("id", UUID.class))
                .projectId(row.get("project_id", UUID.class))
                .name(StringUtils.defaultIfBlank(
                        getValue(exclude, Trace.TraceField.NAME, row, "name", String.class), null))
                .startTime(getValue(exclude, Trace.TraceField.START_TIME, row, "start_time", Instant.class))
                .endTime(getValue(exclude, Trace.TraceField.END_TIME, row, "end_time", Instant.class))
                .input(Optional.ofNullable(getValue(exclude, Trace.TraceField.INPUT, row, "input", String.class))
                        .filter(str -> !str.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata, "input_truncated",
                                row,
                                value))
                        .orElse(null))
                .output(Optional.ofNullable(getValue(exclude, Trace.TraceField.OUTPUT, row, "output", String.class))
                        .filter(str -> !str.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata, "output_truncated",
                                row,
                                value))
                        .orElse(null))
                .metadata(metadata)
                .tags(Optional.ofNullable(getValue(exclude, Trace.TraceField.TAGS, row, "tags", String[].class))
                        .map(tags -> Arrays.stream(tags).collect(Collectors.toSet()))
                        .filter(set -> !set.isEmpty())
                        .orElse(null))
                .comments(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.COMMENTS, row, "comments", List[].class))
                        .map(CommentResultMapper::getComments)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .feedbackScores(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.FEEDBACK_SCORES, row, "feedback_scores_list",
                                List.class))
                        .filter(not(List::isEmpty))
                        .map(FeedbackScoreMapper::mapFeedbackScores)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .guardrailsValidations(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.GUARDRAILS_VALIDATIONS, row,
                                "guardrails_validations", List.class))
                        .map(this::mapGuardrails)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .spanCount(Optional
                        .ofNullable(
                                getValue(exclude, Trace.TraceField.SPAN_COUNT, row, "span_count", Integer.class))
                        .orElse(0))
                .llmSpanCount(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.LLM_SPAN_COUNT, row, "llm_span_count",
                                Integer.class))
                        .orElse(0))
                .providers(providers)
                .usage(getValue(exclude, Trace.TraceField.USAGE, row, "usage", Map.class))
                .totalEstimatedCost(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.TOTAL_ESTIMATED_COST, row,
                                "total_estimated_cost", BigDecimal.class))
                        .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                        .orElse(null))
                .errorInfo(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.ERROR_INFO, row, "error_info", String.class))
                        .filter(str -> !str.isBlank())
                        .map(errorInfo -> JsonUtils.readValue(errorInfo, ERROR_INFO_TYPE))
                        .orElse(null))
                .createdAt(getValue(exclude, Trace.TraceField.CREATED_AT, row, "created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(getValue(exclude, Trace.TraceField.CREATED_BY, row, "created_by", String.class))
                .lastUpdatedBy(
                        getValue(exclude, Trace.TraceField.LAST_UPDATED_BY, row, "last_updated_by", String.class))
                .duration(getValue(exclude, Trace.TraceField.DURATION, row, "duration", Double.class))
                .threadId(StringUtils.defaultIfBlank(
                        getValue(exclude, Trace.TraceField.THREAD_ID, row, "thread_id", String.class), null))
                .visibilityMode(Optional.ofNullable(
                        getValue(exclude, Trace.TraceField.VISIBILITY_MODE, row, "visibility_mode", String.class))
                        .flatMap(VisibilityMode::fromString)
                        .orElse(null))
                .build();
    }

    private List<GuardrailsValidation> mapGuardrails(List<List<Object>> guardrails) {
        return GuardrailsMapper.INSTANCE.mapToValidations(Optional.ofNullable(guardrails)
                .orElse(List.of())
                .stream()
                .map(guardrail -> Guardrail.builder()
                        .entityId(UUID.fromString((String) guardrail.get(0)))
                        .secondaryId(UUID.fromString((String) guardrail.get(1)))
                        .projectId(UUID.fromString((String) guardrail.get(2)))
                        .name(GuardrailType.fromString((String) guardrail.get(3)))
                        .result(GuardrailResult.fromString((String) guardrail.get(4)))
                        .config(JsonNodeFactory.instance.objectNode())
                        .details(JsonNodeFactory.instance.objectNode())
                        .build())
                .toList());
    }

    private Publisher<TraceDetails> mapToTraceDetails(Result result) {
        return result.map((row, rowMetadata) -> TraceDetails.builder()
                .projectId(row.get("project_id", String.class))
                .workspaceId(row.get("workspace_id", String.class))
                .build());
    }

    @Override
    @WithSpan
    public Mono<TracePage> find(
            int size, int page, @NonNull TraceSearchCriteria traceSearchCriteria, @NonNull Connection connection) {
        return countTotal(traceSearchCriteria, connection)
                .flatMap(result -> Mono.from(result.map((row, rowMetadata) -> row.get("count", Long.class))))
                .flatMap(total -> getTracesByProjectId(size, page, traceSearchCriteria, connection) //Get count then pagination
                        .flatMapMany(result1 -> mapToDto(result1, traceSearchCriteria.exclude()))
                        .collectList()
                        .map(traces -> new TracePage(page, traces.size(), total, traces,
                                sortingFactory.getSortableFields())));
    }

    @Override
    @WithSpan
    public Mono<Void> partialInsert(
            @NonNull UUID projectId,
            @NonNull TraceUpdate traceUpdate,
            @NonNull UUID traceId,
            @NonNull Connection connection) {

        var template = buildUpdateTemplate(traceUpdate, INSERT_UPDATE);

        var statement = connection.createStatement(template.render());

        statement.bind("id", traceId);
        statement.bind("project_id", projectId);

        bindUpdateParams(traceUpdate, statement);

        Segment segment = startSegment("traces", "Clickhouse", "insert_partial");

        return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                .doFinally(signalType -> endSegment(segment))
                .then();
    }

    private boolean hasSpanStatistics(String sortFields) {
        if (sortFields == null) {
            return false;
        }
        return sortFields.contains("usage")
                || sortFields.contains("span_count")
                || sortFields.contains("llm_span_count")
                || sortFields.contains("total_estimated_cost");
    }

    private Mono<? extends Result> getTracesByProjectId(
            int size, int page, TraceSearchCriteria traceSearchCriteria, Connection connection) {

        int offset = (page - 1) * size;

        var template = newFindTemplate(SELECT_BY_PROJECT_ID, traceSearchCriteria);

        bindTemplateExcludeFieldVariables(traceSearchCriteria, template);

        template.add("offset", offset);

        var finalTemplate = template;
        Optional.ofNullable(sortingQueryBuilder.toOrderBySql(traceSearchCriteria.sortingFields()))
                .ifPresent(sortFields -> {

                    if (sortFields.contains("feedback_scores")) {
                        finalTemplate.add("sort_has_feedback_scores", true);
                    }

                    if (hasSpanStatistics(sortFields)) {
                        finalTemplate.add("sort_has_span_statistics", true);
                    }

                    finalTemplate.add("sort_fields", sortFields);
                });

        var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(traceSearchCriteria.sortingFields());

        template = ImageUtils.addTruncateToTemplate(template, traceSearchCriteria.truncate());

        var statement = connection.createStatement(template.render())
                .bind("project_id", traceSearchCriteria.projectId())
                .bind("limit", size)
                .bind("offset", offset);

        if (hasDynamicKeys) {
            statement = sortingQueryBuilder.bindDynamicKeys(statement, traceSearchCriteria.sortingFields());
        }

        bindSearchCriteria(traceSearchCriteria, statement);

        Segment segment = startSegment("traces", "Clickhouse", "find");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private void bindTemplateExcludeFieldVariables(TraceSearchCriteria traceSearchCriteria, ST template) {
        Optional.ofNullable(traceSearchCriteria.exclude())
                .filter(Predicate.not(Set::isEmpty))
                .ifPresent(exclude -> {

                    // We need to keep the columns used for sorting in the select clause so that they are available when applying sorting.
                    Set<String> sortingFields = Optional.ofNullable(traceSearchCriteria.sortingFields())
                            .stream()
                            .flatMap(List::stream)
                            .map(SortingField::field)
                            .collect(Collectors.toSet());

                    Set<String> fields = exclude.stream()
                            .map(Trace.TraceField::getValue)
                            .filter(field -> !sortingFields.contains(field))
                            .collect(Collectors.toSet());

                    // check feedback_scores as well because it's a special case
                    if (fields.contains(Trace.TraceField.FEEDBACK_SCORES.getValue())
                            && sortingFields.stream().noneMatch(this::isFeedBackScoresField)) {

                        template.add("exclude_feedback_scores", true);
                    }

                    if (!fields.isEmpty()) {
                        template.add("exclude_fields", String.join(", ", fields));
                        template.add("exclude_input", fields.contains(Trace.TraceField.INPUT.getValue()));
                        template.add("exclude_output", fields.contains(Trace.TraceField.OUTPUT.getValue()));
                        template.add("exclude_metadata", fields.contains(Trace.TraceField.METADATA.getValue()));
                        template.add("exclude_comments", fields.contains(Trace.TraceField.COMMENTS.getValue()));

                        template.add("exclude_usage", fields.contains(Trace.TraceField.USAGE.getValue()));
                        template.add("exclude_total_estimated_cost",
                                fields.contains(Trace.TraceField.TOTAL_ESTIMATED_COST.getValue()));
                        template.add("exclude_guardrails_validations",
                                fields.contains(Trace.TraceField.GUARDRAILS_VALIDATIONS.getValue()));
                        template.add("exclude_span_count", fields.contains(Trace.TraceField.SPAN_COUNT.getValue()));
                        template.add("exclude_llm_span_count",
                                fields.contains(Trace.TraceField.LLM_SPAN_COUNT.getValue()));
                    }
                });
    }

    private boolean isFeedBackScoresField(String field) {
        return field
                .startsWith(SortableFields.FEEDBACK_SCORES.substring(0, SortableFields.FEEDBACK_SCORES.length() - 1));
    }

    private Mono<? extends Result> countTotal(TraceSearchCriteria traceSearchCriteria, Connection connection) {
        var template = newFindTemplate(COUNT_BY_PROJECT_ID, traceSearchCriteria);

        var statement = connection.createStatement(template.render())
                .bind("project_id", traceSearchCriteria.projectId());

        bindSearchCriteria(traceSearchCriteria, statement);

        Segment segment = startSegment("traces", "Clickhouse", "findCount");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private ST newFindTemplate(String query, TraceSearchCriteria traceSearchCriteria) {
        var template = TemplateUtils.newST(query);
        Optional.ofNullable(traceSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE)
                            .ifPresent(traceFilters -> template.add("filters", traceFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_AGGREGATION)
                            .ifPresent(traceAggregationFilters -> template.add("trace_aggregation_filters",
                                    traceAggregationFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(scoresFilters -> template.add("feedback_scores_filters", scoresFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.ANNOTATION_AGGREGATION)
                            .ifPresent(traceAnnotationFilters -> template.add("annotation_queue_filters",
                                    traceAnnotationFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_THREAD)
                            .ifPresent(threadFilters -> template.add("trace_thread_filters", threadFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoreIsEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoreIsEmptyFilters));
                });
        Optional.ofNullable(traceSearchCriteria.lastReceivedId())
                .ifPresent(lastReceivedTraceId -> template.add("last_received_id", lastReceivedTraceId));

        // Add UUID bounds for time-based filtering (presence of uuid_from_time triggers the conditional)
        Optional.ofNullable(traceSearchCriteria.uuidFromTime())
                .ifPresent(uuid_from_time -> template.add("uuid_from_time", uuid_from_time));
        Optional.ofNullable(traceSearchCriteria.uuidToTime())
                .ifPresent(uuid_to_time -> template.add("uuid_to_time", uuid_to_time));
        return template;
    }

    private void bindSearchCriteria(TraceSearchCriteria traceSearchCriteria, Statement statement) {
        Optional.ofNullable(traceSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_AGGREGATION);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.ANNOTATION_AGGREGATION);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_THREAD);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                });
        Optional.ofNullable(traceSearchCriteria.lastReceivedId())
                .ifPresent(lastReceivedTraceId -> statement.bind("last_received_id", lastReceivedTraceId));

        // Bind UUID BETWEEN bounds for time-based filtering
        Optional.ofNullable(traceSearchCriteria.uuidFromTime())
                .ifPresent(uuid_from_time -> statement.bind("uuid_from_time", uuid_from_time));
        Optional.ofNullable(traceSearchCriteria.uuidToTime())
                .ifPresent(uuid_to_time -> statement.bind("uuid_to_time", uuid_to_time));
    }

    @Override
    @WithSpan
    public Mono<List<WorkspaceAndResourceId>> getTraceWorkspace(
            @NonNull Set<UUID> traceIds, @NonNull Connection connection) {

        if (traceIds.isEmpty()) {
            return Mono.just(List.of());
        }

        var statement = connection.createStatement(SELECT_TRACE_ID_AND_WORKSPACE);

        return Mono.deferContextual(ctx -> {

            statement.bind("traceIds", traceIds.toArray(UUID[]::new));

            return Mono.from(statement.execute());
        }).flatMapMany(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                row.get("workspace_id", String.class),
                row.get("id", UUID.class))))
                .collectList();
    }

    @Override
    @WithSpan
    public Mono<Long> batchInsert(@NonNull List<Trace> traces, @NonNull Connection connection) {

        Preconditions.checkArgument(!traces.isEmpty(), "traces must not be empty");

        return Mono.from(insert(traces, connection))
                .flatMapMany(Result::getRowsUpdated)
                .reduce(0L, Long::sum);

    }

    private Publisher<? extends Result> insert(List<Trace> traces, Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(traces.size());

            var template = TemplateUtils.newST(BATCH_INSERT)
                    .add("items", queryItems);

            Statement statement = connection.createStatement(template.render());

            int i = 0;
            for (Trace trace : traces) {

                statement.bind("id" + i, trace.id())
                        .bind("project_id" + i, trace.projectId())
                        .bind("name" + i, StringUtils.defaultIfBlank(trace.name(), ""))
                        .bind("start_time" + i, trace.startTime().toString())
                        .bind("input" + i, getOrDefault(trace.input()))
                        .bind("output" + i, getOrDefault(trace.output()))
                        .bind("metadata" + i, getOrDefault(trace.metadata()))
                        .bind("tags" + i, trace.tags() != null ? trace.tags().toArray(String[]::new) : new String[]{})
                        .bind("error_info" + i,
                                trace.errorInfo() != null ? JsonUtils.readTree(trace.errorInfo()).toString() : "")
                        .bind("thread_id" + i, StringUtils.defaultIfBlank(trace.threadId(), ""));

                if (trace.endTime() != null) {
                    statement.bind("end_time" + i, trace.endTime().toString());
                } else {
                    statement.bindNull("end_time" + i, String.class);
                }

                if (trace.lastUpdatedAt() != null) {
                    statement.bind("last_updated_at" + i, trace.lastUpdatedAt().toString());
                } else {
                    statement.bindNull("last_updated_at" + i, String.class);
                }

                if (trace.visibilityMode() != null) {
                    statement.bind("visibility_mode" + i, trace.visibilityMode().getValue());
                } else {
                    statement.bindNull("visibility_mode" + i, String.class);
                }

                TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold" + i, configuration);

                i++;
            }

            statement
                    .bind("workspace_id", workspaceId)
                    .bind("user_name", userName);

            Segment segment = startSegment("traces", "Clickhouse", "batch_insert");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private String getOrDefault(JsonNode value) {
        return value != null ? value.toString() : "";
    }

    @Override
    @WithSpan
    public Flux<WorkspaceTraceCount> countTracesPerWorkspace(@NonNull Map<UUID, Instant> excludedProjectIds) {

        Optional<Instant> demoDataCreatedAt = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

        var template = TemplateUtils.newST(TRACE_COUNT_BY_WORKSPACE_ID);

        if (!excludedProjectIds.isEmpty()) {
            template.add("excluded_project_ids", excludedProjectIds.keySet().toArray(UUID[]::new));
        }

        if (demoDataCreatedAt.isPresent()) {
            template.add("demo_data_created_at", demoDataCreatedAt.get().toString());
        }

        return asyncTemplate
                .nonTransaction(
                        connection -> {
                            Statement statement = connection.createStatement(template.render());

                            if (!excludedProjectIds.isEmpty()) {
                                statement.bind("excluded_project_ids",
                                        excludedProjectIds.keySet().toArray(UUID[]::new));
                            }

                            if (demoDataCreatedAt.isPresent()) {
                                statement.bind("demo_data_created_at", demoDataCreatedAt.get().toString());
                            }

                            return Mono.from(statement.execute());
                        })
                .flatMapMany(result -> result.map((row, rowMetadata) -> WorkspaceTraceCount.builder()
                        .workspace(row.get("workspace_id", String.class))
                        .traceCount(row.get("trace_count", Integer.class))
                        .build()));
    }

    @Override
    @WithSpan
    public Flux<BiInformation> getTraceBIInformation(@NonNull Map<UUID, Instant> excludedProjectIds) {

        Optional<Instant> demoDataCreatedAt = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

        var template = TemplateUtils.newST(TRACE_DAILY_BI_INFORMATION);

        if (!excludedProjectIds.isEmpty()) {
            template.add("excluded_project_ids", excludedProjectIds.keySet().toArray(UUID[]::new));
        }

        if (demoDataCreatedAt.isPresent()) {
            template.add("demo_data_created_at", demoDataCreatedAt.get().toString());
        }

        return asyncTemplate.nonTransaction(connection -> {
            Statement statement = connection.createStatement(template.render());

            if (!excludedProjectIds.isEmpty()) {
                statement.bind("excluded_project_ids", excludedProjectIds.keySet().toArray(UUID[]::new));
            }

            if (demoDataCreatedAt.isPresent()) {
                statement.bind("demo_data_created_at", demoDataCreatedAt.get().toString());
            }

            return Mono.from(statement.execute());
        })
                .flatMapMany(result -> result.map((row, rowMetadata) -> BiInformation.builder()
                        .workspaceId(row.get("workspace_id", String.class))
                        .user(row.get("user", String.class))
                        .count(row.get("trace_count", Long.class)).build()));
    }

    @Override
    public Mono<ProjectStats> getStats(@NonNull TraceSearchCriteria criteria) {
        return asyncTemplate.nonTransaction(connection -> {

            var statsSQL = newFindTemplate(SELECT_TRACES_STATS, criteria);

            var statement = connection.createStatement(statsSQL.render())
                    .bind("project_ids", List.of(criteria.projectId()));

            bindSearchCriteria(criteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "stats");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(
                            result -> result.map((row, rowMetadata) -> StatsMapper.mapProjectStats(row, "trace_count")))
                    .singleOrEmpty();
        });
    }

    @Override
    public Mono<Long> getDailyTraces(@NonNull Map<UUID, Instant> excludedProjectIds) {

        Optional<Instant> demoDataCreatedAt = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

        var template = TemplateUtils.newST(TRACE_COUNT_BY_WORKSPACE_ID);

        if (!excludedProjectIds.isEmpty()) {
            template.add("excluded_project_ids", excludedProjectIds.keySet().toArray(UUID[]::new));
        }

        if (demoDataCreatedAt.isPresent()) {
            template.add("demo_data_created_at", demoDataCreatedAt.get().toString());
        }

        return asyncTemplate
                .nonTransaction(
                        connection -> {
                            Statement statement = connection.createStatement(template.render());

                            if (!excludedProjectIds.isEmpty()) {
                                statement.bind("excluded_project_ids",
                                        excludedProjectIds.keySet().toArray(UUID[]::new));
                            }

                            if (demoDataCreatedAt.isPresent()) {
                                statement.bind("demo_data_created_at", demoDataCreatedAt.get().toString());
                            }

                            return Mono.from(statement.execute());
                        })
                .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("trace_count", Long.class)))
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Map<UUID, ProjectStats>> getStatsByProjectIds(@NonNull List<UUID> projectIds,
            @NonNull String workspaceId) {

        if (projectIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        return asyncTemplate
                .nonTransaction(connection -> {
                    var template = TemplateUtils.newST(SELECT_TRACES_STATS);

                    template.add("project_stats", true);

                    Statement statement = connection.createStatement(template.render())
                            .bind("project_ids", projectIds)
                            .bind("workspace_id", workspaceId);

                    return Mono.from(statement.execute())
                            .flatMapMany(result -> result.map((row, rowMetadata) -> Map.of(
                                    row.get("project_id", UUID.class),
                                    StatsMapper.mapProjectStats(row, "trace_count"))))
                            .map(Map::entrySet)
                            .flatMap(Flux::fromIterable)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                });
    }

    private Mono<Long> countThreadTotal(TraceSearchCriteria traceSearchCriteria, Connection connection) {
        var template = newFindTemplate(SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS, traceSearchCriteria);

        var statement = connection.createStatement(template.render())
                .bind("project_id", traceSearchCriteria.projectId());

        bindSearchCriteria(traceSearchCriteria, statement);

        Segment segment = startSegment("traces", "Clickhouse", "countThreads");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment))
                .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    @Override
    @WithSpan
    public Mono<TraceThreadPage> findThreads(int size, int page, @NonNull TraceSearchCriteria criteria) {

        return asyncTemplate.nonTransaction(connection -> countThreadTotal(criteria, connection)
                .flatMap(count -> {

                    int offset = (page - 1) * size;

                    var template = newFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS, criteria);

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

                    bindSearchCriteria(criteria, statement);

                    Segment segment = startSegment("traces", "Clickhouse", "findThreads");

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                            .flatMap(this::mapThreadToDto)
                            .collectList()
                            .doFinally(signalType -> endSegment(segment))
                            .map(threads -> new TraceThreadPage(page, threads.size(), count, threads,
                                    traceThreadSortingFactory.getSortableFields()))
                            .defaultIfEmpty(TraceThreadPage.empty(page, traceThreadSortingFactory.getSortableFields()));
                }));
    }

    @Override
    @WithSpan
    public Flux<TraceThread> threadsSearch(int limit, @NonNull TraceSearchCriteria criteria) {
        Preconditions.checkArgument(limit > 0, "limit must be greater than 0");

        return asyncTemplate.stream(connection -> {

            var template = newFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS, criteria);
            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

            template.add("limit", limit)
                    .add("stream", true);

            var statement = connection.createStatement(template.render())
                    .bind("project_id", criteria.projectId())
                    .bind("limit", limit);

            bindSearchCriteria(criteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "threadsSearch");

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
    public Mono<List<TraceThread>> getMinimalThreadInfoByIds(@NonNull UUID projectId, @NonNull Set<String> threadId) {
        if (threadId.isEmpty()) {
            return Mono.just(List.of());
        }

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_MINIMAL_THREAD_INFO_BY_IDS)
                    .bind("project_id", projectId)
                    .bind("thread_ids", threadId.toArray(String[]::new));

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(this::mapMinimalThreadToDto)
                    .collectList();
        });

    }

    private Publisher<TraceThread> mapMinimalThreadToDto(Result result) {
        return result.map((row, rowMetadata) -> TraceThread.builder()
                .id(row.get("id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .threadModelId(Optional.ofNullable(row.get("thread_model_id", String.class))
                        .filter(StringUtils::isNotBlank)
                        .map(UUID::fromString)
                        .orElse(null))
                .workspaceId(row.get("workspace_id", String.class))
                .status(TraceThreadStatus.fromValue(row.get("status", String.class)).orElse(TraceThreadStatus.ACTIVE))
                .createdBy(row.get("created_by", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .build());
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

    @Override
    @WithSpan
    public Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(
            @NonNull Set<UUID> projectIds, @NonNull String workspaceId, @NonNull Connection connection) {

        log.info("Getting last updated trace at for projectIds {}", Arrays.toString(projectIds.toArray()));

        var statement = connection.createStatement(SELECT_TRACE_LAST_UPDATED_AT)
                .bind("project_ids", projectIds.toArray(UUID[]::new))
                .bind("workspace_id", workspaceId);

        return Mono.from(statement.execute())
                .flatMapMany(result -> result.map((row, rowMetadata) -> Map.entry(row.get("project_id", UUID.class),
                        row.get("last_updated_at", Instant.class))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Got last updated trace at for projectIds {}", Arrays.toString(projectIds.toArray()));
                    }
                });
    }

    @Override
    public Mono<UUID> getProjectIdFromTrace(@NonNull UUID traceId) {

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_PROJECT_ID_FROM_TRACE)
                    .bind("id", traceId);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("project_id", UUID.class)))
                    .singleOrEmpty();
        });
    }

    @Override
    @WithSpan
    public Mono<Set<UUID>> getTraceIdsByThreadIds(@NonNull UUID projectId, @NonNull List<String> threadIds,
            @NonNull Connection connection) {
        Preconditions.checkArgument(!threadIds.isEmpty(), "threadIds must not be empty");
        log.info("Getting trace IDs by thread IDs, count '{}'", threadIds.size());

        var statement = connection.createStatement(SELECT_TRACE_IDS_BY_THREAD_IDS)
                .bind("project_id", projectId)
                .bind("thread_ids", threadIds.toArray(String[]::new));

        Segment segment = startSegment("traces", "Clickhouse", "getTraceIdsByThreadIds");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment))
                .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("id", UUID.class)))
                .collect(Collectors.toSet());
    }

    @Override
    public Mono<TraceThread> findThreadById(@NonNull UUID projectId, @NonNull String threadId, boolean truncate) {
        return asyncTemplate.nonTransaction(connection -> {
            var template = TemplateUtils.newST(SELECT_TRACES_THREAD_BY_ID);
            template.add("truncate", truncate);

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("thread_id", threadId);

            Segment segment = startSegment("traces", "Clickhouse", "findThreadById");

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(this::mapThreadToDto)
                    .singleOrEmpty()
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @WithSpan
    public Mono<Trace> getPartialById(@NonNull UUID id) {
        log.info("Getting partial trace by id '{}'", id);
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_PARTIAL_BY_ID).bind("id", id);
            var segment = startSegment("traces", "Clickhouse", "get_partial_by_id");
            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .doFinally(signalType -> endSegment(segment));
        })
                .flatMapMany(this::mapToPartialDto)
                .singleOrEmpty();
    }

    private Publisher<Trace> mapToPartialDto(Result result) {
        return result.map((row, rowMetadata) -> Trace.builder()
                .startTime(row.get("start_time", Instant.class))
                .projectId(row.get("project_id", UUID.class))
                .build());
    }

    @Override
    public Flux<Trace> search(int limit, @NonNull TraceSearchCriteria criteria) {
        return asyncTemplate.stream(connection -> findTraceStream(limit, criteria, connection))
                .flatMap(result -> mapToDto(result, Set.of()))
                .buffer(limit > 100 ? limit / 2 : limit)
                .concatWith(Mono.just(List.of()))
                .flatMap(Flux::fromIterable);
    }

    @Override
    public Mono<Long> countTraces(Set<UUID> projectIds) {

        if (CollectionUtils.isEmpty(projectIds)) {
            return Mono.just(0L);
        }

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_COUNT_TRACES_BY_PROJECT_IDS)
                    .bind("project_ids", projectIds);

            Segment segment = startSegment("traces", "Clickhouse", "countTraces");

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                    .reduce(0L, Long::sum);
        });
    }

    private Flux<? extends Result> findTraceStream(int limit, @NonNull TraceSearchCriteria criteria,
            Connection connection) {
        log.info("Searching traces by '{}'", criteria);

        var template = newFindTemplate(SELECT_BY_PROJECT_ID, criteria);

        template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

        var statement = connection.createStatement(template.render())
                .bind("project_id", criteria.projectId())
                .bind("limit", limit);

        bindSearchCriteria(criteria, statement);

        Segment segment = startSegment("traces", "Clickhouse", "findTraceStream");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> {
                    log.info("Closing trace search stream");
                    endSegment(segment);
                });
    }

    private JsonNode getMetadataWithProviders(Row row, Set<Trace.TraceField> exclude, List<String> providers) {
        // Parse base metadata from database
        JsonNode baseMetadata = Optional
                .ofNullable(getValue(exclude, Trace.TraceField.METADATA, row, "metadata", String.class))
                .filter(str -> !str.isBlank())
                .map(JsonUtils::getJsonNodeFromStringWithFallback)
                .orElse(null);

        // Inject providers as first field in metadata
        return JsonUtils.prependField(
                baseMetadata, Trace.TraceField.PROVIDERS.getValue(), providers);
    }
}
