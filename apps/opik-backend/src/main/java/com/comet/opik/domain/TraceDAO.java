package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse.BiInformation;
import com.comet.opik.api.ExperimentItemReference;
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
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.domain.utils.DemoDataExclusionUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import io.r2dbc.spi.ConnectionFactory;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static com.comet.opik.api.ErrorInfo.ERROR_INFO_TYPE;
import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.api.TraceCountResponse.WorkspaceTraceCount;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspace;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.DatabaseUtils.bindTraceThreadSearchCriteria;
import static com.comet.opik.infrastructure.DatabaseUtils.getLogComment;
import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
import static com.comet.opik.infrastructure.DatabaseUtils.newTraceThreadFindTemplate;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.*;

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

    Mono<Map<UUID, UUID>> getProjectIdsByTraceIds(List<UUID> traceIds);

    Flux<BiInformation> getTraceBIInformation(Map<UUID, Instant> excludedProjectIds);

    Mono<ProjectStats> getStats(TraceSearchCriteria criteria);

    Mono<Long> getDailyTraces(Map<UUID, Instant> excludedProjectIds);

    Mono<Map<UUID, ProjectStats>> getStatsByProjectIds(List<UUID> projectIds, String workspaceId);

    Mono<Set<UUID>> getTraceIdsByThreadIds(UUID projectId, List<String> threadIds, Connection connection);

    Mono<Trace> getPartialById(UUID id);

    Flux<Trace> search(int limit, TraceSearchCriteria criteria);

    Mono<Long> countTraces(Set<UUID> projectIds);

    Mono<List<TraceThread>> getMinimalThreadInfoByIds(UUID projectId, Set<String> threadId);

    Mono<Void> bulkUpdate(@NonNull Set<UUID> ids, @NonNull TraceUpdate update, boolean mergeTags);
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
                truncation_threshold,
                input_slim,
                output_slim,
                ttft
            )
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
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
                        :truncation_threshold<item.index>,
                        :input_slim<item.index>,
                        :output_slim<item.index>,
                        :ttft<item.index>
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
                truncation_threshold,
                input_slim,
                output_slim,
                ttft
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
                new_trace.truncation_threshold as truncation_threshold,
                multiIf(
                    notEmpty(old_trace.input) AND notEmpty(old_trace.input_slim), old_trace.input_slim,
                    new_trace.input_slim
                ) as input_slim,
                multiIf(
                    notEmpty(old_trace.output) AND notEmpty(old_trace.output_slim), old_trace.output_slim,
                    new_trace.output_slim
                ) as output_slim,
                multiIf(
                    isNotNull(old_trace.ttft), old_trace.ttft,
                    new_trace.ttft
                ) as ttft
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
                    :truncation_threshold as truncation_threshold,
                    :input_slim as input_slim,
                    :output_slim as output_slim,
                    :ttft as ttft
            ) as new_trace
            LEFT JOIN (
                SELECT
                    *, truncated_input, truncated_output
                FROM traces
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) as old_trace
            ON new_trace.id = old_trace.id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /***
     * Handles the update of a trace when the trace already exists in the database.
     ***/
    private static final String UPDATE = """
            INSERT INTO traces (
            	id, project_id, workspace_id, name, start_time, end_time, input, output, metadata, tags, error_info, created_at, created_by, last_updated_by, thread_id, visibility_mode, truncation_threshold, input_slim, output_slim, ttft
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
                :truncation_threshold as truncation_threshold,
                <if(input)> :input_slim <else> input_slim <endif> as input_slim,
                <if(output)> :output_slim <else> output_slim <endif> as output_slim,
                <if(ttft)> :ttft <else> ttft <endif> as ttft
            FROM traces
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    // Query to get target project_ids from traces (executed separately to reduce table scans)
    private static final String SELECT_TARGET_PROJECTS_FOR_TRACES = """
            SELECT DISTINCT project_id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id IN :ids
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    // Build value_by_author map with composite keys (author_spanId) for span feedback scores.
    // The composite key format ensures uniqueness when multiple spans have the same author.
    // Format: if span_id exists, use 'author_spanId', otherwise use 'author'.
    // The tuple contains: (value, reason, category_name, source, last_updated_at, span_type, span_id)
    private static final String SELECT_BY_IDS = """
            WITH target_spans AS (
                SELECT id, trace_id, type
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND trace_id IN :ids
            ),
            feedback_scores_combined_raw AS (
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
            ), span_feedback_scores_combined_raw AS (
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
                WHERE entity_type = 'span'
                AND workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
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
                WHERE entity_type = 'span'
                AND workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
            ), span_feedback_scores_with_ranking AS (
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
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM span_feedback_scores_combined_raw
            ), span_feedback_scores_combined AS (
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
                FROM span_feedback_scores_with_ranking
                WHERE rn = 1
            ), span_feedback_scores_with_trace_id AS (
                SELECT workspace_id,
                       project_id,
                       s.trace_id,
                       s.id AS span_id,
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
                       s.type AS span_type
                FROM span_feedback_scores_combined sfs
                INNER JOIN target_spans s ON sfs.entity_id = s.id
            ), span_feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    groupArray(value) AS values,
                    groupArray(reason) AS reasons,
                    groupArray(category_name) AS categories,
                    groupArray(author) AS authors,
                    groupArray(source) AS sources,
                    groupArray(created_by) AS created_bies,
                    groupArray(last_updated_by) AS updated_bies,
                    groupArray(created_at) AS created_ats,
                    groupArray(last_updated_at) AS last_updated_ats,
                    groupArray(span_type) AS span_types,
                    groupArray(span_id) AS span_ids
                FROM span_feedback_scores_with_trace_id
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    arrayStringConcat(categories, ', ') AS category_name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value,
                    IF(length(reasons) = 1,
                        arrayElement(reasons, 1),
                        arrayStringConcat(
                            arrayFilter(x -> x != '' AND x != '\\<no reason>', reasons),
                            ', '
                        )
                    ) AS reason,
                    arrayElement(sources, 1) AS source,
                    mapFromArrays(
                            arrayMap(i -> if(span_ids[i] IS NULL OR span_ids[i] = '', authors[i], concat(authors[i], '_', toString(span_ids[i]))), arrayEnumerate(authors)),
                            arrayMap(
                                    i -> tuple(values[i], reasons[i], categories[i], sources[i], last_updated_ats[i], span_types[i], span_ids[i]),
                                    arrayEnumerate(values)
                            )
                    ) AS value_by_author,
                    arrayStringConcat(created_bies, ', ') AS created_by,
                    arrayStringConcat(updated_bies, ', ') AS last_updated_by,
                    arrayMin(created_ats) AS created_at,
                    arrayMax(last_updated_ats) AS last_updated_at
                FROM span_feedback_scores_combined_grouped
            ), experiments_agg AS (
                SELECT DISTINCT
                    ei.trace_id,
                    ei.dataset_item_id AS experiment_dataset_item_id,
                    e.id AS experiment_id,
                    e.name AS experiment_name,
                    e.dataset_id AS experiment_dataset_id
                FROM (
                    SELECT DISTINCT experiment_id, trace_id, dataset_item_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    AND trace_id IN :ids
                ) ei
                INNER JOIN (
                    SELECT id, name, dataset_id
                    FROM experiments
                    WHERE workspace_id = :workspace_id
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) e ON ei.experiment_id = e.id
            )
            SELECT
                t.*,
                t.id as id,
                t.project_id as project_id,
                sumMap(s.usage) as usage,
                sum(s.total_estimated_cost) as total_estimated_cost,
                COUNT(s.id) AS span_count,
                toInt64(countIf(s.type = 'llm')) AS llm_span_count,
                countIf(s.type = 'tool') > 0 AS has_tool_spans,
                arraySort(groupUniqArrayIf(s.provider, s.provider != '')) as providers,
                groupUniqArrayArray(c.comments_array) as comments,
                any(fs.feedback_scores_list) as feedback_scores_list,
                any(sfs.span_feedback_scores_list) as span_feedback_scores_list,
                any(gr.guardrails) as guardrails_validations,
                any(eaag.experiment_id) as experiment_id,
                any(eaag.experiment_name) as experiment_name,
                any(eaag.experiment_dataset_id) as experiment_dataset_id,
                any(eaag.experiment_dataset_item_id) as experiment_dataset_item_id
            FROM (
                SELECT
                    *,
                    duration
                FROM traces
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
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
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND trace_id IN :ids
                ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS s ON t.id = s.trace_id
            LEFT JOIN experiments_agg eaag ON eaag.trace_id = t.id
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
                    <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
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
                    trace_id,
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
                    )) AS span_feedback_scores_list
                FROM span_feedback_scores_final
                GROUP BY workspace_id, project_id, trace_id
            ) AS sfs ON t.id = sfs.trace_id
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
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_DETAILS_BY_ID = """
            SELECT DISTINCT
                workspace_id,
                project_id
            FROM traces
            WHERE id = :id
            SETTINGS log_comment = '<log_comment>'
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
                  <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                  <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                   <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                   <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ), target_spans AS (
                SELECT DISTINCT id, trace_id
                FROM spans
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)>AND trace_id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND trace_id \\<= :uuid_to_time<endif>
            ),
            span_feedback_scores_combined_raw AS (
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
                WHERE entity_type = 'span'
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
                WHERE entity_type = 'span'
                  AND workspace_id = :workspace_id
                  AND project_id = :project_id
            ), span_feedback_scores_with_ranking AS (
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
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM span_feedback_scores_combined_raw
            ), span_feedback_scores_combined AS (
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
                FROM span_feedback_scores_with_ranking
                WHERE rn = 1
            ), span_feedback_scores_with_trace_id AS (
                SELECT workspace_id,
                       project_id,
                       s.trace_id,
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
                FROM span_feedback_scores_combined sfs
                INNER JOIN target_spans s ON sfs.entity_id = s.id
            ), span_feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
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
                FROM span_feedback_scores_with_trace_id
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
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
                FROM span_feedback_scores_combined_grouped
            ), span_feedback_scores_agg AS (
                SELECT
                    trace_id,
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
                    )) AS span_feedback_scores_list
                FROM span_feedback_scores_final
                GROUP BY workspace_id, project_id, trace_id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    COUNT(DISTINCT id) as span_count,
                    toInt64(countIf(type = 'llm')) as llm_span_count,
                    countIf(type = 'tool') > 0 as has_tool_spans,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)>AND trace_id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND trace_id \\<= :uuid_to_time<endif>
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
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
            )
            <if(sort_has_experiment || !exclude_experiment)>
            , experiments_agg AS (
                SELECT DISTINCT
                    ei.trace_id,
                    ei.dataset_item_id AS experiment_dataset_item_id,
                    e.id AS experiment_id,
                    e.name AS experiment_name,
                    e.dataset_id AS experiment_dataset_id
                FROM (
                    SELECT DISTINCT experiment_id, trace_id, dataset_item_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
                ) ei
                INNER JOIN (
                    SELECT id, name, dataset_id
                    FROM experiments
                    WHERE workspace_id = :workspace_id
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) e ON ei.experiment_id = e.id
            )
            <endif>
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
             )
            <endif>
            <if(span_feedback_scores_empty_filters)>
             , sfsc AS (SELECT trace_id, COUNT(trace_id) AS span_feedback_scores_count
                 FROM span_feedback_scores_final
                 GROUP BY trace_id
                 HAVING <span_feedback_scores_empty_filters>
             )
            <endif>
            , traces_final AS (
                SELECT
                    t.* <if(exclude_fields)>EXCEPT (<exclude_fields>) <endif>,
                    truncated_input,
                    truncated_output,
                    input_length,
                    output_length,
                    duration
                FROM traces t
                <if(guardrails_filters)>
                    LEFT JOIN guardrails_agg gagg ON gagg.entity_id = t.id
                <endif>
                <if(sort_has_feedback_scores)>
                LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = t.id
                <endif>
                <if(sort_has_span_statistics)>
                LEFT JOIN spans_agg s ON t.id = s.trace_id
                <endif>
                <if(sort_has_experiment)>
                LEFT JOIN experiments_agg eaag ON eaag.trace_id = t.id
                <endif>
                <if(feedback_scores_empty_filters)>
                LEFT JOIN fsc ON fsc.entity_id = t.id
                <endif>
                <if(span_feedback_scores_empty_filters)>
                LEFT JOIN sfsc ON sfsc.trace_id = t.id
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
                    SELECT entity_id
                    FROM feedback_scores_final
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                 )
                 <endif>
                 <if(span_feedback_scores_filters)>
                 AND id IN (
                    SELECT
                        trace_id
                    FROM span_feedback_scores_final
                    GROUP BY trace_id
                    HAVING <span_feedback_scores_filters>
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
                 <if(experiment_filters)>
                 AND id IN (
                    SELECT
                        trace_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    AND <experiment_filters>
                    ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                 )
                 <endif>
                 <if(feedback_scores_empty_filters)>
                 AND (
                    id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT entity_id FROM fsc)
                 )
                 <endif>
                 <if(span_feedback_scores_empty_filters)>
                 AND (
                    id IN (SELECT trace_id FROM sfsc WHERE sfsc.span_feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT trace_id FROM sfsc)
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
                  , sfsagg.span_feedback_scores_list as span_feedback_scores_list
                  <endif>
                  <if(!exclude_usage)>, s.usage as usage<endif>
                  <if(!exclude_total_estimated_cost)>, s.total_estimated_cost as total_estimated_cost<endif>
                  <if(!exclude_comments)>, c.comments_array as comments <endif>
                  <if(!exclude_guardrails_validations)>, gagg.guardrails_list as guardrails_validations<endif>
                  <if(!exclude_span_count)>, s.span_count AS span_count<endif>
                  <if(!exclude_llm_span_count)>, s.llm_span_count AS llm_span_count<endif>
                  <if(!exclude_has_tool_spans)>, s.has_tool_spans AS has_tool_spans<endif>
                  , s.providers AS providers
                  <if(!exclude_experiment)>, eaag.experiment_id, eaag.experiment_name, eaag.experiment_dataset_id, eaag.experiment_dataset_item_id<endif>
             FROM traces_final t
             LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = t.id
             LEFT JOIN span_feedback_scores_agg sfsagg ON sfsagg.trace_id = t.id
             LEFT JOIN spans_agg s ON t.id = s.trace_id
             LEFT JOIN comments_agg c ON t.id = c.entity_id
             LEFT JOIN guardrails_agg gagg ON gagg.entity_id = t.id
             <if(sort_has_experiment || !exclude_experiment)>LEFT JOIN experiments_agg eaag ON eaag.trace_id = t.id<endif>
             ORDER BY <if(sort_fields)> <sort_fields>, id DESC <else>(workspace_id, project_id, id) DESC, last_updated_at DESC <endif>
            SETTINGS log_comment = '<log_comment>'
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
            SETTINGS log_comment = '<log_comment>'
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
            SETTINGS log_comment = '<log_comment>'
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
                  <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                  <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                   <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                   <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
            ), target_spans AS (
                SELECT DISTINCT id, trace_id
                FROM spans
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)>AND trace_id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND trace_id \\<= :uuid_to_time<endif>
            ),
            span_feedback_scores_combined_raw AS (
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
                WHERE entity_type = 'span'
                  AND workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND entity_id IN (SELECT id FROM target_spans)
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
                WHERE entity_type = 'span'
                  AND workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND entity_id IN (SELECT id FROM target_spans)
            ), span_feedback_scores_with_ranking AS (
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
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM span_feedback_scores_combined_raw
            ), span_feedback_scores_combined AS (
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
                FROM span_feedback_scores_with_ranking
                WHERE rn = 1
            ), span_feedback_scores_with_trace_id AS (
                SELECT workspace_id,
                       project_id,
                       s.trace_id,
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
                FROM span_feedback_scores_combined sfs
                INNER JOIN target_spans s ON sfs.entity_id = s.id
            ), span_feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
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
                FROM span_feedback_scores_with_trace_id
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
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
                FROM span_feedback_scores_combined_grouped
            ), span_feedback_scores_agg AS (
                SELECT
                    trace_id,
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
                    )) AS span_feedback_scores_list
                FROM span_feedback_scores_final
                GROUP BY workspace_id, project_id, trace_id
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            <if(span_feedback_scores_empty_filters)>
             , sfsc AS (SELECT trace_id, COUNT(trace_id) AS span_feedback_scores_count
                 FROM span_feedback_scores_final
                 GROUP BY trace_id
                 HAVING <span_feedback_scores_empty_filters>
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
                        duration
                    FROM traces
                        LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = traces.id
                    <endif>
                    <if(span_feedback_scores_empty_filters)>
                    LEFT JOIN sfsc ON sfsc.trace_id = traces.id
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
                    AND id IN (
                        SELECT entity_id
                        FROM feedback_scores_final
                        GROUP BY entity_id
                        HAVING <feedback_scores_filters>
                    )
                    <endif>
                    <if(span_feedback_scores_filters)>
                    AND id IN (
                        SELECT
                            trace_id
                        FROM span_feedback_scores_final
                        GROUP BY trace_id
                        HAVING <span_feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                    <if(span_feedback_scores_empty_filters)>
                    AND (
                        id IN (SELECT trace_id FROM sfsc WHERE sfsc.span_feedback_scores_count = 0)
                            OR
                        id NOT IN (SELECT trace_id FROM sfsc)
                    )
                    <endif>
                    <if(experiment_filters)>
                    AND id IN (
                        SELECT
                            trace_id
                        FROM experiment_items
                        WHERE workspace_id = :workspace_id
                        AND <experiment_filters>
                        ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
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
                    AND trace_id IN (SELECT trace_id FROM target_spans)
                    ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS s ON t.id = s.trace_id
                GROUP BY
                    t.id
                HAVING <trace_aggregation_filters>
                <endif>
            ) AS latest_rows
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String DELETE_BY_ID = """
            DELETE FROM traces
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            <if(project_id)>AND project_id = :project_id<endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_TRACE_ID_AND_WORKSPACE = """
            SELECT
                DISTINCT id, workspace_id
            FROM traces
            WHERE id IN :traceIds
            SETTINGS log_comment = '<log_comment>'
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
                id, project_id, workspace_id, name, start_time, end_time, input, output, metadata, tags, error_info, created_at, created_by, last_updated_by, thread_id, visibility_mode, truncation_threshold, input_slim, output_slim, ttft
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
                new_trace.truncation_threshold as truncation_threshold,
                multiIf(
                    notEmpty(new_trace.input_slim), new_trace.input_slim,
                    notEmpty(old_trace.input) AND notEmpty(old_trace.input_slim), old_trace.input_slim,
                    new_trace.input_slim
                ) as input_slim,
                multiIf(
                    notEmpty(new_trace.output_slim), new_trace.output_slim,
                    notEmpty(old_trace.output) AND notEmpty(old_trace.output_slim), old_trace.output_slim,
                    new_trace.output_slim
                ) as output_slim,
                multiIf(
                    isNotNull(new_trace.ttft), new_trace.ttft,
                    isNotNull(old_trace.ttft), old_trace.ttft,
                    new_trace.ttft
                ) as ttft
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
                    :truncation_threshold as truncation_threshold,
                    <if(input)> :input_slim <else> '' <endif> as input_slim,
                    <if(output)> :output_slim <else> '' <endif> as output_slim,
                    <if(ttft)> :ttft <else> null <endif> as ttft
            ) as new_trace
            LEFT JOIN (
                SELECT
                    *, truncated_input, truncated_output
                FROM traces
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) as old_trace
            ON new_trace.id = old_trace.id
            SETTINGS log_comment = '<log_comment>'
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
            SETTINGS log_comment = '<log_comment>'
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
            SETTINGS log_comment = '<log_comment>'
            ;
            """;
    private static final String SELECT_PROJECT_ID_FROM_TRACE = """
            SELECT
                DISTINCT project_id
            FROM traces
            WHERE id = :id
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_PROJECT_IDS_BY_TRACE_IDS = """
            SELECT
                id,
                any(project_id) AS project_id
            FROM traces
            WHERE id IN :trace_ids
            AND workspace_id = :workspace_id
            GROUP BY id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_TRACES_STATS = """
             WITH spans_data AS (
                SELECT
                    id,
                    trace_id,
                    usage,
                    total_estimated_cost,
                    type,
                    workspace_id,
                    project_id
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
             ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    COUNT(id) as span_count,
                    toInt64(countIf(type = 'llm')) as llm_span_count
                FROM spans_data
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
                  <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                  <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                   <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                   <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
            ),
            span_feedback_scores_combined_raw AS (
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
                WHERE entity_type = 'span'
                  AND workspace_id = :workspace_id
                  AND project_id IN :project_ids
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
                WHERE entity_type = 'span'
                  AND workspace_id = :workspace_id
                  AND project_id IN :project_ids
            ), span_feedback_scores_with_ranking AS (
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
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM span_feedback_scores_combined_raw
            ), span_feedback_scores_combined AS (
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
                FROM span_feedback_scores_with_ranking
                WHERE rn = 1
            ), span_feedback_scores_with_trace_id AS (
                SELECT workspace_id,
                       project_id,
                       s.trace_id,
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
                FROM span_feedback_scores_combined sfs
                INNER JOIN spans_data s ON sfs.entity_id = s.id
            ), span_feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
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
                FROM span_feedback_scores_with_trace_id
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
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
                FROM span_feedback_scores_combined_grouped
            ), span_feedback_scores_agg AS (
                SELECT
                    trace_id,
                    mapFromArrays(
                            groupArray(name),
                            groupArray(value)
                    ) AS span_feedback_scores,
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
                    )) AS span_feedback_scores_list
                FROM span_feedback_scores_final
                GROUP BY workspace_id, project_id, trace_id
            )
            <if(span_feedback_scores_empty_filters)>
             , sfsc AS (SELECT trace_id, COUNT(trace_id) AS span_feedback_scores_count
                 FROM span_feedback_scores_final
                 GROUP BY trace_id
                 HAVING <span_feedback_scores_empty_filters>
            )
            <endif>
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , trace_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    id,
                    thread_id,
                    if(input_length > 0, 1, 0) as input_count,
                    if(output_length > 0, 1, 0) as output_count,
                    if(metadata_length > 0, 1, 0) as metadata_count,
                    length(tags) as tags_length,
                    duration,
                    error_info
                FROM traces final
                <if(guardrails_filters)>
                LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                <endif>
                <if(feedback_scores_empty_filters)>
                LEFT JOIN fsc ON fsc.entity_id = traces.id
                <endif>
                <if(span_feedback_scores_empty_filters)>
                LEFT JOIN sfsc ON sfsc.trace_id = traces.id
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
                    SELECT entity_id
                    FROM feedback_scores_final
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(span_feedback_scores_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM span_feedback_scores_final
                    GROUP BY trace_id
                    HAVING <span_feedback_scores_filters>
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
                <if(experiment_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    AND <experiment_filters>
                    ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND fsc.feedback_scores_count = 0
                <endif>
                <if(span_feedback_scores_empty_filters)>
                AND (
                    id IN (SELECT trace_id FROM sfsc WHERE sfsc.span_feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT trace_id FROM sfsc)
                )
                <endif>
            )
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                countDistinct(t.id) AS trace_count,
                countDistinctIf(t.thread_id, t.thread_id != '') AS thread_count,
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
                avgMap(sfs.span_feedback_scores) AS span_feedback_scores,
                avg(s.llm_span_count) AS llm_span_count_avg,
                avg(s.span_count) AS span_count_avg,
                avgIf(s.total_estimated_cost, s.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sumIf(s.total_estimated_cost, s.total_estimated_cost > 0) AS total_estimated_cost_sum_,
                toDecimal128(total_estimated_cost_sum_, 12) AS total_estimated_cost_sum,
                sum(g.failed_count) AS guardrails_failed_count,
                <if(project_stats)>
                countIf(t.error_info != '' AND toDateTime(UUIDv7ToDateTime(toUUID(t.id))) BETWEEN toStartOfDay(subtractDays(now(), 7)) AND now64(9)) AS recent_error_count,
                countIf(t.error_info != '' AND toDateTime(UUIDv7ToDateTime(toUUID(t.id))) \\< toStartOfDay(subtractDays(now(), 7))) AS past_period_error_count
                <else>
                countIf(t.error_info, t.error_info != '') AS error_count
                <endif>
            FROM trace_final t
            LEFT JOIN spans_agg AS s ON t.id = s.trace_id
            LEFT JOIN feedback_scores_agg as f ON t.id = f.entity_id
            LEFT JOIN span_feedback_scores_agg as sfs ON t.id = sfs.trace_id
            LEFT JOIN guardrails_agg as g ON t.id = g.entity_id
            GROUP BY t.workspace_id, t.project_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_TRACE_IDS_BY_THREAD_IDS = """
            SELECT DISTINCT id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND thread_id IN :thread_ids
            SETTINGS log_comment = '<log_comment>'
            """;

    public static final String SELECT_COUNT_TRACES_BY_PROJECT_IDS = """
            SELECT
                count(distinct id) as count
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id IN :project_ids
            SETTINGS log_comment = '<log_comment>'
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
            SETTINGS log_comment = '<log_comment>'
            """;

    private static final String BULK_UPDATE = """
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
                truncation_threshold,
                input_slim,
                output_slim,
                ttft
            )
            SELECT
                t.id,
                t.project_id,
                t.workspace_id,
                <if(name)> :name <else> t.name <endif> as name,
                t.start_time,
                <if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> t.end_time <endif> as end_time,
                <if(input)> :input <else> t.input <endif> as input,
                <if(output)> :output <else> t.output <endif> as output,
                <if(metadata)> :metadata <else> t.metadata <endif> as metadata,
                <if(tags_to_add || tags_to_remove)>
                    <if(tags_to_add && tags_to_remove)>
                        arrayDistinct(arrayConcat(arrayFilter(x -> NOT has(:tags_to_remove, x), t.tags), :tags_to_add))
                    <elseif(tags_to_add)>
                        arrayDistinct(arrayConcat(t.tags, :tags_to_add))
                    <elseif(tags_to_remove)>
                        arrayFilter(x -> NOT has(:tags_to_remove, x), t.tags)
                    <endif>
                <elseif(tags)>
                    <if(merge_tags)>arrayDistinct(arrayConcat(t.tags, :tags))<else>:tags<endif>
                <else>
                    t.tags
                <endif> as tags,
                <if(error_info)> :error_info <else> t.error_info <endif> as error_info,
                t.created_at,
                t.created_by,
                :user_name as last_updated_by,
                <if(thread_id)> :thread_id <else> t.thread_id <endif> as thread_id,
                t.visibility_mode,
                :truncation_threshold as truncation_threshold,
                <if(input)> :input_slim <else> t.input_slim <endif> as input_slim,
                <if(output)> :output_slim <else> t.output_slim <endif> as output_slim,
                <if(ttft)> :ttft <else> t.ttft <endif> as ttft
            FROM traces t
            WHERE t.id IN :ids AND t.workspace_id = :workspace_id
            ORDER BY (t.workspace_id, t.project_id, t.id) DESC, t.last_updated_at DESC
            LIMIT 1 BY t.id
            SETTINGS log_comment = '<log_comment>';
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull TraceSortingFactory sortingFactory;
    private final @NonNull OpikConfiguration configuration;
    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    @WithSpan
    public Mono<UUID> insert(@NonNull Trace trace, @NonNull Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = buildInsertTemplate(trace, workspaceId);

            Statement statement = buildInsertStatement(trace, connection, template);
            bindUserNameAndWorkspace(statement, userName, workspaceId);

            Segment segment = startSegment("traces", "Clickhouse", "insert");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .thenReturn(trace.id());
        });

    }

    private Statement buildInsertStatement(Trace trace, Connection connection, ST template) {
        Statement statement = connection.createStatement(template.render())
                .bind("id", trace.id())
                .bind("project_id", trace.projectId())
                .bind("name", StringUtils.defaultIfBlank(trace.name(), ""))
                .bind("start_time", trace.startTime().toString())
                .bind("thread_id", StringUtils.defaultIfBlank(trace.threadId(), ""));

        bindInputOutputMetadataAndSlim(statement, trace, null);

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

        if (trace.ttft() != null) {
            statement.bind("ttft", trace.ttft());
        } else {
            statement.bindNull("ttft", Double.class);
        }

        return statement;
    }

    /**
     * Binds input, output, metadata, and their slim versions (input_slim, output_slim) to a statement.
     * Centralizes the JSON conversion and binding logic for consistency across single and batch inserts.
     *
     * @param statement the statement to bind to
     * @param trace the trace containing the values
     * @param index optional index suffix for batch operations (e.g., 0, 1, 2); pass null for single insert
     */
    private void bindInputOutputMetadataAndSlim(Statement statement, Trace trace, Integer index) {
        String suffix = index != null ? String.valueOf(index) : "";

        String inputValue = TruncationUtils.toJsonString(trace.input());
        String outputValue = TruncationUtils.toJsonString(trace.output());
        String metadataValue = TruncationUtils.toJsonString(trace.metadata());

        statement.bind("input" + suffix, inputValue)
                .bind("output" + suffix, outputValue)
                .bind("metadata" + suffix, metadataValue)
                .bind("input_slim" + suffix, TruncationUtils.createSlimJsonString(inputValue))
                .bind("output_slim" + suffix, TruncationUtils.createSlimJsonString(outputValue));
    }

    private ST buildInsertTemplate(Trace trace, String workspaceId) {
        var template = getSTWithLogComment(INSERT, "insert_trace", workspaceId, "");

        Optional.ofNullable(trace.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime));
        Optional.ofNullable(trace.ttft())
                .ifPresent(ttft -> template.add("ttft", ttft));

        return template;
    }

    @Override
    @WithSpan
    public Mono<Void> update(@NonNull TraceUpdate traceUpdate, @NonNull UUID id, @NonNull Connection connection) {
        return update(id, traceUpdate, connection).then();
    }

    private Mono<? extends Result> update(UUID id, TraceUpdate traceUpdate, Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = buildUpdateTemplate(traceUpdate, UPDATE, "update_trace", workspaceId);

            String sql = template.render();

            Statement statement = createUpdateStatement(id, traceUpdate, connection, sql);
            bindUserNameAndWorkspace(statement, userName, workspaceId);

            Segment segment = startSegment("traces", "Clickhouse", "update");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
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
                .ifPresent(input -> {
                    String inputValue = input.toString();
                    statement.bind("input", inputValue);
                    statement.bind("input_slim", TruncationUtils.createSlimJsonString(inputValue));
                });

        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> {
                    String outputValue = output.toString();
                    statement.bind("output", outputValue);
                    statement.bind("output_slim", TruncationUtils.createSlimJsonString(outputValue));
                });

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

        Optional.ofNullable(traceUpdate.ttft())
                .ifPresent(ttft -> statement.bind("ttft", ttft));

        TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold", configuration);
    }

    private ST buildUpdateTemplate(TraceUpdate traceUpdate, String update, String queryName, String workspaceId) {
        var template = getSTWithLogComment(update, queryName, workspaceId, "");

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

        Optional.ofNullable(traceUpdate.ttft())
                .ifPresent(ttft -> template.add("ttft", ttft));

        return template;
    }

    private Flux<? extends Result> getDetailsById(UUID id, Connection connection) {
        var template = getSTWithLogComment(SELECT_DETAILS_BY_ID, "get_trace_details_by_id", "", "");

        var statement = connection.createStatement(template.render())
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

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(DELETE_BY_ID, "delete_traces", workspaceId, ids.size());
            Optional.ofNullable(projectId)
                    .ifPresent(id -> template.add("project_id", id));

            var statement = connection.createStatement(template.render())
                    .bind("ids", ids.toArray(UUID[]::new));

            if (projectId != null) {
                statement.bind("project_id", projectId);
            }

            statement.bind("workspace_id", workspaceId);

            var segment = startSegment("traces", "Clickhouse", "delete");
            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .then();
        });
    }

    /**
     * Get target project IDs from traces for the given trace IDs.
     * This is executed as a separate query to reduce traces table scans in the main query.
     */
    private Mono<List<UUID>> getTargetProjectIdsForTraces(List<UUID> ids) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.from(connectionFactory.create())
                    .flatMap(connection -> {
                        var template = getSTWithLogComment(SELECT_TARGET_PROJECTS_FOR_TRACES,
                                "get_target_project_ids_for_traces", workspaceId, ids.size());

                        var statement = connection.createStatement(template.render())
                                .bind("ids", ids.toArray(UUID[]::new));

                        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
                    })
                    .flatMapMany(result -> result.map((row, metadata) -> row.get("project_id", UUID.class)))
                    .collectList();
        });
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

        return getTargetProjectIdsForTraces(ids)
                .flatMapMany(targetProjectIds -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                    var template = getSTWithLogComment(SELECT_BY_IDS, "find_traces_by_ids", workspaceId, ids.size());

                    if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                        template.add("has_target_projects", true);
                    }

                    var statement = connection.createStatement(template.render())
                            .bind("ids", ids.toArray(UUID[]::new));

                    if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                        statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
                    }

                    Segment segment = startSegment("traces", "Clickhouse", "findByIds");

                    return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                            .doFinally(signalType -> endSegment(segment));
                }))
                .flatMap(result -> mapToDto(result, Set.of()));
    }

    @Override
    public Mono<TraceDetails> getTraceDetailsById(@NonNull UUID id, @NonNull Connection connection) {
        return getDetailsById(id, connection)
                .flatMap(this::mapToTraceDetails)
                .singleOrEmpty();
    }

    /**
     * Retrieves a value from a database row for a given field.
     * <p>
     * This method handles cases where columns may not exist in the result set.
     * Some queries (e.g., trace list queries with field exclusions) may not
     * include all possible columns to optimize performance. When a column is
     * absent, this method returns null instead of throwing an exception.
     * </p>
     *
     * @param exclude Set of fields to exclude from retrieval (returns null if field is in this set)
     * @param field The trace field to retrieve
     * @param row The database row to read from
     * @param fieldName The database column name
     * @param clazz The expected class type of the value
     * @param <T> The type of the value to retrieve
     * @return The field value, or null if the field is excluded, the column doesn't exist,
     *         or the column value is null
     */
    private <T> T getValue(Set<Trace.TraceField> exclude, Trace.TraceField field, Row row, String fieldName,
            Class<T> clazz) {
        if (exclude.contains(field)) {
            return null;
        }
        // Check if column exists in result set (some queries don't include all columns)
        if (!row.getMetadata().contains(fieldName)) {
            return null;
        }
        return row.get(fieldName, clazz);
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
                        .map(tags -> Arrays.stream(tags).collect(toSet()))
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
                .spanFeedbackScores(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.SPAN_FEEDBACK_SCORES, row,
                                "span_feedback_scores_list",
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
                .hasToolSpans(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.HAS_TOOL_SPANS, row, "has_tool_spans",
                                Boolean.class))
                        .orElse(false))
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
                .ttft(getValue(exclude, Trace.TraceField.TTFT, row, "ttft", Double.class))
                .threadId(StringUtils.defaultIfBlank(
                        getValue(exclude, Trace.TraceField.THREAD_ID, row, "thread_id", String.class), null))
                .visibilityMode(Optional.ofNullable(
                        getValue(exclude, Trace.TraceField.VISIBILITY_MODE, row, "visibility_mode", String.class))
                        .flatMap(VisibilityMode::fromString)
                        .orElse(null))
                .experiment(mapExperiment(exclude, row))
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

    private ExperimentItemReference mapExperiment(Set<Trace.TraceField> exclude, Row row) {
        String experimentIdStr = getValue(exclude, Trace.TraceField.EXPERIMENT, row, "experiment_id", String.class);
        String experimentDatasetIdStr = getValue(exclude, Trace.TraceField.EXPERIMENT, row, "experiment_dataset_id",
                String.class);
        String experimentDatasetItemIdStr = getValue(exclude, Trace.TraceField.EXPERIMENT, row,
                "experiment_dataset_item_id", String.class);

        // Only check key fields - experimentName is editable and its absence doesn't indicate missing data
        if (StringUtils.isBlank(experimentIdStr) || StringUtils.isBlank(experimentDatasetIdStr)
                || StringUtils.isBlank(experimentDatasetItemIdStr)
                || CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(experimentIdStr)
                || CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(experimentDatasetIdStr)
                || CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(experimentDatasetItemIdStr)) {
            return null;
        }

        UUID experimentId = UUID.fromString(experimentIdStr);
        UUID experimentDatasetId = UUID.fromString(experimentDatasetIdStr);
        UUID experimentDatasetItemId = UUID.fromString(experimentDatasetItemIdStr);
        String experimentName = getValue(exclude, Trace.TraceField.EXPERIMENT, row, "experiment_name", String.class);

        return ExperimentItemReference.builder()
                .id(experimentId)
                .name(experimentName)
                .datasetId(experimentDatasetId)
                .datasetItemId(experimentDatasetItemId)
                .build();
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

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = buildUpdateTemplate(traceUpdate, INSERT_UPDATE, "partial_insert_trace", workspaceId);

            var statement = connection.createStatement(template.render());

            statement.bind("id", traceId);
            statement.bind("project_id", projectId);

            bindUserNameAndWorkspace(statement, userName, workspaceId);
            bindUpdateParams(traceUpdate, statement);

            Segment segment = startSegment("traces", "Clickhouse", "insert_partial");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .then();
        });
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

        return makeMonoContextAware((userName, workspaceId) -> {
            var logComment = getLogComment("find_traces_by_project_id", workspaceId,
                    "page:" + page + ":size:" + size + ":" + traceSearchCriteria.toString());
            var template = newTraceThreadFindTemplate(SELECT_BY_PROJECT_ID, traceSearchCriteria);

            bindTemplateExcludeFieldVariables(traceSearchCriteria, template);

            template.add("offset", offset);
            template.add("log_comment", logComment);

            var finalTemplate = template;
            Optional.ofNullable(
                    sortingQueryBuilder.toOrderBySql(traceSearchCriteria.sortingFields(),
                            TraceSortingFactory.EXPERIMENT_FIELD_MAPPING))
                    .ifPresent(sortFields -> {

                        if (sortFields.contains("feedback_scores")) {
                            finalTemplate.add("sort_has_feedback_scores", true);
                        }

                        if (hasSpanStatistics(sortFields)) {
                            finalTemplate.add("sort_has_span_statistics", true);
                        }

                        if (sortFields.contains("experiment_id") || sortFields.contains("eaag.experiment")) {
                            finalTemplate.add("sort_has_experiment", true);
                        }

                        finalTemplate.add("sort_fields", sortFields);
                    });

            var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(traceSearchCriteria.sortingFields());

            template = ImageUtils.addTruncateToTemplate(template, traceSearchCriteria.truncate());

            var statement = connection.createStatement(template.render())
                    .bind("project_id", traceSearchCriteria.projectId())
                    .bind("workspace_id", workspaceId)
                    .bind("limit", size)
                    .bind("offset", offset);

            if (hasDynamicKeys) {
                statement = sortingQueryBuilder.bindDynamicKeys(statement, traceSearchCriteria.sortingFields());
            }

            bindTraceThreadSearchCriteria(traceSearchCriteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "find");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
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
                            .collect(toSet());

                    Set<String> fields = exclude.stream()
                            .map(Trace.TraceField::getValue)
                            .filter(field -> !sortingFields.contains(field))
                            .collect(toSet());

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
                        template.add("exclude_has_tool_spans",
                                fields.contains(Trace.TraceField.HAS_TOOL_SPANS.getValue()));
                        template.add("exclude_experiment",
                                fields.contains(Trace.TraceField.EXPERIMENT.getValue()));
                    }
                });
    }

    private boolean isFeedBackScoresField(String field) {
        return field
                .startsWith(SortableFields.FEEDBACK_SCORES.substring(0, SortableFields.FEEDBACK_SCORES.length() - 1));
    }

    private Mono<? extends Result> countTotal(TraceSearchCriteria traceSearchCriteria, Connection connection) {
        return makeMonoContextAware((userName, workspaceId) -> {
            var logComment = getLogComment("count_traces_by_project", workspaceId, traceSearchCriteria.toString());
            var template = newTraceThreadFindTemplate(COUNT_BY_PROJECT_ID, traceSearchCriteria);
            template.add("log_comment", logComment);

            var statement = connection.createStatement(template.render())
                    .bind("project_id", traceSearchCriteria.projectId())
                    .bind("workspace_id", workspaceId);

            bindTraceThreadSearchCriteria(traceSearchCriteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "findCount");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<List<WorkspaceAndResourceId>> getTraceWorkspace(
            @NonNull Set<UUID> traceIds, @NonNull Connection connection) {

        if (traceIds.isEmpty()) {
            return Mono.just(List.of());
        }

        var template = getSTWithLogComment(SELECT_TRACE_ID_AND_WORKSPACE, "get_trace_workspace", "", traceIds.size());

        var statement = connection.createStatement(template.render())
                .bind("traceIds", traceIds.toArray(UUID[]::new));

        return Mono.from(statement.execute())
                .flatMapMany(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
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

            var logComment = getLogComment("batch_insert_traces", workspaceId, traces.size());

            var template = TemplateUtils.newST(BATCH_INSERT)
                    .add("items", queryItems)
                    .add("log_comment", logComment);

            Statement statement = connection.createStatement(template.render());

            int i = 0;
            for (Trace trace : traces) {
                statement.bind("id" + i, trace.id())
                        .bind("project_id" + i, trace.projectId())
                        .bind("name" + i, StringUtils.defaultIfBlank(trace.name(), ""))
                        .bind("start_time" + i, trace.startTime().toString())
                        .bind("tags" + i, trace.tags() != null ? trace.tags().toArray(String[]::new) : new String[]{})
                        .bind("error_info" + i,
                                trace.errorInfo() != null ? JsonUtils.readTree(trace.errorInfo()).toString() : "")
                        .bind("thread_id" + i, StringUtils.defaultIfBlank(trace.threadId(), ""));

                bindInputOutputMetadataAndSlim(statement, trace, i);

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

                if (trace.ttft() != null) {
                    statement.bind("ttft" + i, trace.ttft());
                } else {
                    statement.bindNull("ttft" + i, Double.class);
                }

                i++;
            }

            bindUserNameAndWorkspace(statement, userName, workspaceId);

            Segment segment = startSegment("traces", "Clickhouse", "batch_insert");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Flux<WorkspaceTraceCount> countTracesPerWorkspace(@NonNull Map<UUID, Instant> excludedProjectIds) {

        Optional<Instant> demoDataCreatedAt = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

        var template = getSTWithLogComment(TRACE_COUNT_BY_WORKSPACE_ID, "count_traces_per_workspace", "", "");

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

        var template = getSTWithLogComment(TRACE_DAILY_BI_INFORMATION, "get_trace_bi_information", "", "");

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
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var logComment = getLogComment("get_trace_stats", workspaceId, "");
            var statsSQL = newTraceThreadFindTemplate(SELECT_TRACES_STATS, criteria);
            statsSQL.add("log_comment", logComment);

            var statement = connection.createStatement(statsSQL.render())
                    .bind("project_ids", List.of(criteria.projectId()))
                    .bind("workspace_id", workspaceId);

            bindTraceThreadSearchCriteria(criteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "stats");

            return Flux.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(
                            result -> result
                                    .map((row, rowMetadata) -> StatsMapper.mapProjectStats(row, "trace_count")))
                    .singleOrEmpty();
        }));
    }

    @Override
    public Mono<Long> getDailyTraces(@NonNull Map<UUID, Instant> excludedProjectIds) {

        Optional<Instant> demoDataCreatedAt = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

        var template = getSTWithLogComment(TRACE_COUNT_BY_WORKSPACE_ID, "get_daily_traces_count", "", "");

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
                    var template = getSTWithLogComment(SELECT_TRACES_STATS, "get_trace_stats_by_project_ids",
                            workspaceId, projectIds.size());

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
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
                });
    }

    @Override
    public Mono<List<TraceThread>> getMinimalThreadInfoByIds(@NonNull UUID projectId, @NonNull Set<String> threadId) {
        if (threadId.isEmpty()) {
            return Mono.just(List.of());
        }

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_MINIMAL_THREAD_INFO_BY_IDS, "get_minimal_thread_info_by_ids",
                    workspaceId, threadId.size());

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("workspace_id", workspaceId)
                    .bind("thread_ids", threadId.toArray(String[]::new));

            return Mono.from(statement.execute())
                    .flatMapMany(this::mapMinimalThreadToDto)
                    .collectList();
        }));

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

    @Override
    @WithSpan
    public Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(
            @NonNull Set<UUID> projectIds, @NonNull String workspaceId, @NonNull Connection connection) {

        log.info("Getting last updated trace at for projectIds, size '{}'", projectIds.size());

        var template = getSTWithLogComment(SELECT_TRACE_LAST_UPDATED_AT, "get_last_updated_trace_at", workspaceId,
                projectIds.size());

        var statement = connection.createStatement(template.render())
                .bind("project_ids", projectIds.toArray(UUID[]::new))
                .bind("workspace_id", workspaceId);

        return Mono.from(statement.execute())
                .flatMapMany(result -> result.map((row, rowMetadata) -> Map.entry(row.get("project_id", UUID.class),
                        row.get("last_updated_at", Instant.class))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Got last updated trace at for projectIds, size '{}'", projectIds.size());
                    }
                });
    }

    @Override
    public Mono<UUID> getProjectIdFromTrace(@NonNull UUID traceId) {

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_PROJECT_ID_FROM_TRACE, "get_project_id_from_trace", workspaceId,
                    "");

            var statement = connection.createStatement(template.render())
                    .bind("id", traceId)
                    .bind("workspace_id", workspaceId);

            return Mono.from(statement.execute())
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("project_id", UUID.class)))
                    .singleOrEmpty();
        }));
    }

    @Override
    @WithSpan
    public Mono<Map<UUID, UUID>> getProjectIdsByTraceIds(@NonNull List<UUID> traceIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(traceIds), "Argument 'traceIds' must not be empty");

        log.info("Getting project_ids for '{}' trace_ids", traceIds.size());

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_PROJECT_IDS_BY_TRACE_IDS, "get_project_ids_by_trace_ids",
                    workspaceId, traceIds.size());

            var statement = connection.createStatement(template.render())
                    .bind("trace_ids", traceIds.toArray(UUID[]::new));

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> Map.entry(row.get("id", UUID.class),
                            row.get("project_id", UUID.class))))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue))
                    .doFinally(signalType -> {
                        if (signalType == SignalType.ON_COMPLETE) {
                            log.info("Got project_ids for '{}' trace_ids", traceIds.size());
                        }
                    });
        }));
    }

    @Override
    @WithSpan
    public Mono<Set<UUID>> getTraceIdsByThreadIds(@NonNull UUID projectId, @NonNull List<String> threadIds,
            @NonNull Connection connection) {
        Preconditions.checkArgument(!threadIds.isEmpty(), "threadIds must not be empty");
        log.info("Getting trace IDs by thread IDs, count '{}'", threadIds.size());

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_TRACE_IDS_BY_THREAD_IDS, "get_trace_ids_by_thread_ids",
                    workspaceId, threadIds.size());

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("workspace_id", workspaceId)
                    .bind("thread_ids", threadIds.toArray(String[]::new));

            Segment segment = startSegment("traces", "Clickhouse", "getTraceIdsByThreadIds");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("id", UUID.class)))
                    .collect(toSet());
        });
    }

    @WithSpan
    public Mono<Trace> getPartialById(@NonNull UUID id) {
        log.info("Getting partial trace by id '{}'", id);
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_PARTIAL_BY_ID, "get_partial_trace_by_id", workspaceId, "");

            var statement = connection.createStatement(template.render())
                    .bind("id", id)
                    .bind("workspace_id", workspaceId);
            var segment = startSegment("traces", "Clickhouse", "get_partial_by_id");
            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        }))
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

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_COUNT_TRACES_BY_PROJECT_IDS, "count_traces_by_project_ids",
                    workspaceId, projectIds.size());

            var statement = connection.createStatement(template.render())
                    .bind("project_ids", projectIds)
                    .bind("workspace_id", workspaceId);

            Segment segment = startSegment("traces", "Clickhouse", "countTraces");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                    .reduce(0L, Long::sum);
        }));
    }

    private Flux<? extends Result> findTraceStream(int limit, @NonNull TraceSearchCriteria criteria,
            Connection connection) {
        log.info("Searching traces by '{}'", criteria);

        return makeFluxContextAware((userName, workspaceId) -> {
            var logComment = getLogComment("find_trace_stream", workspaceId, "limit:" + limit + ":" + criteria);
            var template = newTraceThreadFindTemplate(SELECT_BY_PROJECT_ID, criteria);
            template.add("log_comment", logComment);

            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

            var statement = connection.createStatement(template.render())
                    .bind("project_id", criteria.projectId())
                    .bind("workspace_id", workspaceId)
                    .bind("limit", limit);

            bindTraceThreadSearchCriteria(criteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "findTraceStream");

            return Flux.from(statement.execute())
                    .doFinally(signalType -> {
                        log.info("Closing trace search stream");
                        endSegment(segment);
                    });
        });
    }

    @Override
    @WithSpan
    public Mono<Void> bulkUpdate(@NonNull Set<UUID> ids, @NonNull TraceUpdate update, boolean mergeTags) {
        Preconditions.checkArgument(!ids.isEmpty(), "ids must not be empty");
        log.info("Bulk updating '{}' traces", ids.size());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = newBulkUpdateTemplate(update, BULK_UPDATE, mergeTags, workspaceId);
                    var query = template.render();

                    var statement = connection.createStatement(query)
                            .bind("ids", ids)
                            .bind("workspace_id", workspaceId)
                            .bind("user_name", userName);

                    bindBulkUpdateParams(update, statement);
                    TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold", configuration);

                    Segment segment = startSegment("traces", "Clickhouse", "bulk_update");

                    return Flux.from(statement.execute())
                            .doFinally(signalType -> endSegment(segment));
                }))
                .then()
                .doOnSuccess(__ -> log.info("Completed bulk update for '{}' traces", ids.size()));
    }

    private ST newBulkUpdateTemplate(TraceUpdate traceUpdate, String sql, boolean mergeTags, String workspaceId) {
        var template = getSTWithLogComment(sql, "bulk_update_traces", workspaceId, "");

        if (StringUtils.isNotBlank(traceUpdate.name())) {
            template.add("name", traceUpdate.name());
        }
        Optional.ofNullable(traceUpdate.input())
                .ifPresent(input -> template.add("input", input.toString()));
        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> template.add("output", output.toString()));

        // New approach: tagsToAdd and tagsToRemove (takes precedence if present)
        if (traceUpdate.tagsToAdd() != null || traceUpdate.tagsToRemove() != null) {
            if (traceUpdate.tagsToAdd() != null) {
                template.add("tags_to_add", true);
            }
            if (traceUpdate.tagsToRemove() != null) {
                template.add("tags_to_remove", true);
            }
        }
        // Old approach: tags with mergeTags boolean (backwards compatible)
        else {
            Optional.ofNullable(traceUpdate.tags())
                    .ifPresent(tags -> {
                        template.add("tags", tags.toString());
                        template.add("merge_tags", mergeTags);
                    });
        }
        Optional.ofNullable(traceUpdate.metadata())
                .ifPresent(metadata -> template.add("metadata", metadata.toString()));
        Optional.ofNullable(traceUpdate.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime.toString()));
        Optional.ofNullable(traceUpdate.errorInfo())
                .ifPresent(errorInfo -> template.add("error_info", JsonUtils.readTree(errorInfo).toString()));
        if (StringUtils.isNotBlank(traceUpdate.threadId())) {
            template.add("thread_id", traceUpdate.threadId());
        }
        Optional.ofNullable(traceUpdate.ttft())
                .ifPresent(ttft -> template.add("ttft", ttft));

        return template;
    }

    private void bindBulkUpdateParams(TraceUpdate traceUpdate, Statement statement) {
        if (StringUtils.isNotBlank(traceUpdate.name())) {
            statement.bind("name", traceUpdate.name());
        }
        Optional.ofNullable(traceUpdate.input())
                .ifPresent(input -> {
                    String inputValue = input.toString();
                    statement.bind("input", inputValue);
                    statement.bind("input_slim", TruncationUtils.createSlimJsonString(inputValue));
                });
        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> {
                    String outputValue = output.toString();
                    statement.bind("output", outputValue);
                    statement.bind("output_slim", TruncationUtils.createSlimJsonString(outputValue));
                });

        // New approach: tagsToAdd and tagsToRemove (takes precedence if present)
        if (traceUpdate.tagsToAdd() != null || traceUpdate.tagsToRemove() != null) {
            if (traceUpdate.tagsToAdd() != null) {
                statement.bind("tags_to_add", traceUpdate.tagsToAdd().toArray(String[]::new));
            }
            if (traceUpdate.tagsToRemove() != null) {
                statement.bind("tags_to_remove", traceUpdate.tagsToRemove().toArray(String[]::new));
            }
        }
        // Old approach: tags (backwards compatible)
        else {
            Optional.ofNullable(traceUpdate.tags())
                    .ifPresent(tags -> statement.bind("tags", tags.toArray(String[]::new)));
        }

        Optional.ofNullable(traceUpdate.endTime())
                .ifPresent(endTime -> statement.bind("end_time", endTime.toString()));
        Optional.ofNullable(traceUpdate.metadata())
                .ifPresent(metadata -> statement.bind("metadata", metadata.toString()));
        Optional.ofNullable(traceUpdate.errorInfo())
                .ifPresent(errorInfo -> statement.bind("error_info", JsonUtils.readTree(errorInfo).toString()));
        if (StringUtils.isNotBlank(traceUpdate.threadId())) {
            statement.bind("thread_id", traceUpdate.threadId());
        }
        Optional.ofNullable(traceUpdate.ttft())
                .ifPresent(ttft -> statement.bind("ttft", ttft));
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
