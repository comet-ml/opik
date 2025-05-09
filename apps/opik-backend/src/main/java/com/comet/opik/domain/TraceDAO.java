package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse.BiInformation;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.GuardrailsValidation;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceDetails;
import com.comet.opik.api.TraceSearchCriteria;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
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
import java.time.OffsetDateTime;
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
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;
import static java.util.function.Predicate.not;

@ImplementedBy(TraceDAOImpl.class)
interface TraceDAO {

    Mono<UUID> insert(Trace trace, Connection connection);

    Mono<Void> update(TraceUpdate traceUpdate, UUID id, Connection connection);

    Mono<Void> delete(UUID id, Connection connection);

    Mono<Void> delete(Set<UUID> ids, Connection connection);

    Mono<Trace> findById(UUID id, Connection connection);

    Mono<TraceDetails> getTraceDetailsById(UUID id, Connection connection);

    Mono<TracePage> find(int size, int page, TraceSearchCriteria traceSearchCriteria, Connection connection);

    Mono<Void> partialInsert(UUID projectId, TraceUpdate traceUpdate, UUID traceId, Connection connection);

    Mono<List<WorkspaceAndResourceId>> getTraceWorkspace(Set<UUID> traceIds, Connection connection);

    Mono<Long> batchInsert(List<Trace> traces, Connection connection);

    Flux<WorkspaceTraceCount> countTracesPerWorkspace(Connection connection);

    Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId, Connection connection);

    Mono<UUID> getProjectIdFromTrace(@NonNull UUID traceId);

    Flux<BiInformation> getTraceBIInformation(Connection connection);

    Mono<ProjectStats> getStats(TraceSearchCriteria criteria);

    Mono<Long> getDailyTraces(List<UUID> excludedProjectIds);

    Mono<Map<UUID, ProjectStats>> getStatsByProjectIds(List<UUID> projectIds, String workspaceId);

    Mono<TraceThreadPage> findThreads(int size, int page, TraceSearchCriteria threadSearchCriteria);

    Mono<Long> deleteThreads(UUID uuid, List<String> threadIds);

    Mono<TraceThread> findThreadById(UUID projectId, String threadId);

    Mono<Trace> getPartialById(@NonNull UUID id);

    Flux<Trace> search(int limit, @NonNull TraceSearchCriteria criteria);

    Mono<Long> countTraces(Set<UUID> projectIds);
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
                thread_id
            ) VALUES
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
                        :thread_id<item.index>
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
                error_info,
                created_at,
                created_by,
                last_updated_by,
                thread_id
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
                ) as thread_id
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
                    :thread_id as thread_id
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
            	id, project_id, workspace_id, name, start_time, end_time, input, output, metadata, tags, error_info, created_at, created_by, last_updated_by, thread_id
            ) SELECT
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
                <if(thread_id)> :thread_id <else> thread_id <endif> as thread_id
            FROM traces
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SELECT_BY_ID = """
            SELECT
                t.*,
                t.id as id,
                t.project_id as project_id,
                sumMap(s.usage) as usage,
                sum(s.total_estimated_cost) as total_estimated_cost,
                COUNT(s.id) AS span_count,
                groupUniqArrayArray(c.comments_array) as comments,
                any(fs.feedback_scores) as feedback_scores_list,
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
                AND id = :id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS t
            LEFT JOIN (
                SELECT
                    trace_id,
                    usage,
                    total_estimated_cost,
                    id
                FROM spans
                WHERE workspace_id = :workspace_id
                  AND trace_id = :id
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
                    AND entity_id = :id
                    ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                GROUP BY entity_id
            ) AS c ON t.id = c.entity_id
            LEFT JOIN (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    groupArray(tuple(
                         name,
                         category_name,
                         value,
                         reason,
                         source,
                         created_at,
                         last_updated_at,
                         created_by,
                         last_updated_by
                    )) as feedback_scores
                FROM (
                    SELECT
                        *
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND entity_id = :id
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                )
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
                    AND entity_id = :id
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
            WITH feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) as feedback_scores,
                    groupArray(tuple(
                         name,
                         category_name,
                         value,
                         reason,
                         source,
                         created_at,
                         last_updated_at,
                         created_by,
                         last_updated_by
                    )) as feedback_scores_list
                FROM feedback_scores final
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
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
                    COUNT(DISTINCT id) as span_count
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
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (
                 SELECT
                    entity_id,
                    COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , traces_final AS (
                SELECT
                    t.* <if(exclude_fields)>EXCEPT (<exclude_fields>) <endif>,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                         NULL) AS duration
                FROM traces t
                    LEFT JOIN guardrails_agg gagg ON gagg.entity_id = t.id
                <if(sort_has_feedback_scores)>
                LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = t.id
                <endif>
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(last_received_trace_id)> AND id \\< :last_received_trace_id <endif>
                <if(filters)> AND <filters> <endif>
                <if(feedback_scores_filters)>
                 AND id IN (
                    SELECT
                        entity_id
                    FROM (
                        SELECT *
                        FROM feedback_scores
                        WHERE entity_type = 'trace'
                        AND workspace_id = :workspace_id
                        AND project_id = :project_id
                        ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
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
                  <if(!exclude_input)>, <if(truncate)> replaceRegexpAll(input, '<truncate>', '"[image]"') as input <else> input <endif><endif>
                  <if(!exclude_output)>, <if(truncate)> replaceRegexpAll(output, '<truncate>', '"[image]"') as output <else> output <endif><endif>
                  <if(!exclude_metadata)>, <if(truncate)> replaceRegexpAll(metadata, '<truncate>', '"[image]"') as metadata <else> metadata <endif><endif>
                  <if(!exclude_feedback_scores)>
                  , fsagg.feedback_scores_list as feedback_scores_list
                  , fsagg.feedback_scores as feedback_scores
                  <endif>
                  <if(!exclude_usage)>, s.usage as usage<endif>
                  <if(!exclude_total_estimated_cost)>, s.total_estimated_cost as total_estimated_cost<endif>
                  <if(!exclude_comments)>, c.comments_array as comments <endif>
                  <if(!exclude_guardrails_validations)>, gagg.guardrails_list as guardrails_validations<endif>
                  <if(!exclude_span_count)>, s.span_count AS span_count<endif>
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
                 <if(excluded_project_ids)>AND project_id NOT IN :excluded_project_ids<endif>
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
                GROUP BY workspace_id, created_by
            ;
            """;

    private static final String COUNT_BY_PROJECT_ID = """
            WITH guardrails_agg AS (
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
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
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
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    <if(filters)> AND <filters> <endif>
                    <if(feedback_scores_filters)>
                    AND id in (
                        SELECT
                            entity_id
                        FROM (
                            SELECT *
                            FROM feedback_scores
                            WHERE entity_type = 'trace'
                            AND workspace_id = :workspace_id
                            AND project_id = :project_id
                            ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
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
                        total_estimated_cost
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
                id, project_id, workspace_id, name, start_time, end_time, input, output, metadata, tags, error_info, created_at, created_by, last_updated_by, thread_id
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
                ) as thread_id
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
                    <if(thread_id)> :thread_id <else> '' <endif> as thread_id
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
                    sum(total_estimated_cost) as total_estimated_cost
                FROM spans final
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                GROUP BY workspace_id, project_id, trace_id
            ), feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) as feedback_scores
                FROM feedback_scores final
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id IN :project_ids
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
            )
            <if(feedback_scores_empty_filters)>
            , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
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
                        NULL) as duration
                FROM traces final
                LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                <if(feedback_scores_empty_filters)>
                LEFT JOIN fsc ON fsc.entity_id = traces.id
                <endif>
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                <if(filters)> AND <filters> <endif>
                <if(feedback_scores_filters)>
                AND id IN (
                    SELECT
                        entity_id
                    FROM (
                        SELECT *
                        FROM feedback_scores
                        WHERE entity_type = 'trace'
                        AND workspace_id = :workspace_id
                        AND project_id IN :project_ids
                        ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
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
                arrayMap(v -> toDecimal64(if(isNaN(v), 0, v), 9), quantiles(0.5, 0.9, 0.99)(t.duration)) AS duration,
                sum(input_count) AS input,
                sum(output_count) AS output,
                sum(metadata_count) AS metadata,
                avg(tags_length) AS tags,
                avgMap(s.usage) as usage,
                avgMap(f.feedback_scores) AS feedback_scores,
                avgIf(s.total_estimated_cost, s.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sum(g.failed_count) AS guardrails_failed_count
            FROM trace_final t
            LEFT JOIN spans_agg AS s ON t.id = s.trace_id
            LEFT JOIN feedback_scores_agg as f ON t.id = f.entity_id
            LEFT JOIN guardrails_agg as g ON t.id = g.entity_id
            GROUP BY t.workspace_id, t.project_id
            ;
            """;

    /***
     * When treating a list of traces as threads, a number of aggregation are performed to get the thread details.
     * <p>
     * Please refer to the SELECT_TRACES_THREAD_BY_ID query for more details.
     ***/
    private static final String SELECT_COUNT_TRACES_THREADS_BY_PROJECT_IDS = """
            SELECT
                countDistinct(id) as count
            FROM (
                SELECT
                    t.thread_id as id,
                    t.workspace_id as workspace_id,
                    t.project_id as project_id
                    <if(trace_thread_filters)>,
                    min(t.start_time) as start_time,
                    max(t.end_time) as end_time,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                               AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', start_time, end_time) / 1000.0),
                           NULL) AS duration,
                    <if(truncate)> replaceRegexpAll(argMin(t.input, t.start_time), '<truncate>', '"[image]"') as first_message <else> argMin(t.input, t.start_time) as first_message<endif>,
                    <if(truncate)> replaceRegexpAll(argMax(t.output, t.end_time), '<truncate>', '"[image]"') as last_message <else> argMax(t.output, t.end_time) as last_message<endif>,
                    count(DISTINCT t.id) * 2 as number_of_messages,
                    max(t.last_updated_at) as last_updated_at,
                    argMin(t.created_by, t.created_at) as created_by,
                    min(t.created_at) as created_at
                    <endif>
                 FROM (
                     SELECT
                         *
                     FROM traces t
                     WHERE workspace_id = :workspace_id
                     AND project_id = :project_id
                     AND thread_id IS NOT NULL
                     AND thread_id \\<> ''
                     ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                     LIMIT 1 BY id
                 ) AS t
                 GROUP BY
                    t.workspace_id, t.project_id, t.thread_id
                 <if(trace_thread_filters)> HAVING <trace_thread_filters> <endif>
             )
            ;
            """;

    /***
     * When treating a list of traces as threads, a number of aggregation are performed to get the thread details.
     * <p>
     * Please refer to the SELECT_TRACES_THREAD_BY_ID query for more details.
     ***/
    private static final String SELECT_TRACES_THREADS_BY_PROJECT_IDS = """
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
                <if(truncate)> replaceRegexpAll(argMin(t.input, t.start_time), '<truncate>', '"[image]"') as first_message <else> argMin(t.input, t.start_time) as first_message<endif>,
                <if(truncate)> replaceRegexpAll(argMax(t.output, t.end_time), '<truncate>', '"[image]"') as last_message <else> argMax(t.output, t.end_time) as last_message<endif>,
                count(DISTINCT t.id) * 2 as number_of_messages,
                max(t.last_updated_at) as last_updated_at,
                argMin(t.created_by, t.created_at) as created_by,
                min(t.created_at) as created_at
             FROM (
                 SELECT
                     *
                 FROM traces t
                 WHERE workspace_id = :workspace_id
                 AND project_id = :project_id
                 AND thread_id IS NOT NULL
                 AND thread_id \\<> ''
                 ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                 LIMIT 1 BY id
             ) AS t
             GROUP BY
                t.workspace_id, t.project_id, t.thread_id
             <if(trace_thread_filters)> HAVING <trace_thread_filters> <endif>
             ORDER BY last_updated_at DESC, start_time ASC, end_time DESC
             LIMIT :limit OFFSET :offset
             SETTINGS join_algorithm = 'full_sorting_merge'
            ;
            """;

    private static final String DELETE_THREADS_BY_PROJECT_ID = """
            DELETE FROM traces
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
                max(t.last_updated_at) as last_updated_at,
                argMin(t.created_by, t.created_at) as created_by,
                min(t.created_at) as created_at
             FROM (
                 SELECT
                     *
                 FROM traces t
                 WHERE workspace_id = :workspace_id
                 AND project_id = :project_id
                 AND thread_id = :thread_id
                 ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                 LIMIT 1 BY id
             ) AS t
             GROUP BY
                t.workspace_id, t.project_id, t.thread_id
            ;
            """;
    public static final String SELECT_COUNT_TRACES_BY_PROJECT_IDS = """
            SELECT
                count(distinct id) as count
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id IN :project_ids
            """;

    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull TraceSortingFactory sortingFactory;

    @Override
    @WithSpan
    public Mono<UUID> insert(@NonNull Trace trace, @NonNull Connection connection) {

        ST template = buildInsertTemplate(trace);

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

        return statement;
    }

    private ST buildInsertTemplate(Trace trace) {
        ST template = new ST(INSERT);

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

        ST template = buildUpdateTemplate(traceUpdate, UPDATE);

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
    }

    private ST buildUpdateTemplate(TraceUpdate traceUpdate, String update) {
        ST template = new ST(update);

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

    private Flux<? extends Result> getById(UUID id, Connection connection) {
        var statement = connection.createStatement(SELECT_BY_ID)
                .bind("id", id);

        Segment segment = startSegment("traces", "Clickhouse", "getById");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> endSegment(segment));
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
    public Mono<Void> delete(@NonNull UUID id, @NonNull Connection connection) {
        return delete(Set.of(id), connection);
    }

    @Override
    @WithSpan
    public Mono<Void> delete(Set<UUID> ids, @NonNull Connection connection) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("Deleting traces, count '{}'", ids.size());
        var statement = connection.createStatement(DELETE_BY_ID)
                .bind("ids", ids.toArray(UUID[]::new));
        var segment = startSegment("traces", "Clickhouse", "delete");
        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment))
                .then();
    }

    @Override
    @WithSpan
    public Mono<Trace> findById(@NonNull UUID id, @NonNull Connection connection) {
        return getById(id, connection)
                .flatMap(result -> mapToDto(result, Set.of()))
                .singleOrEmpty();
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

        return result.map((row, rowMetadata) -> Trace.builder()
                .id(row.get("id", UUID.class))
                .projectId(row.get("project_id", UUID.class))
                .name(StringUtils.defaultIfBlank(
                        getValue(exclude, Trace.TraceField.NAME, row, "name", String.class), null))
                .startTime(getValue(exclude, Trace.TraceField.START_TIME, row, "start_time", Instant.class))
                .endTime(getValue(exclude, Trace.TraceField.END_TIME, row, "end_time", Instant.class))
                .input(Optional.ofNullable(getValue(exclude, Trace.TraceField.INPUT, row, "input", String.class))
                        .filter(str -> !str.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .output(Optional.ofNullable(getValue(exclude, Trace.TraceField.OUTPUT, row, "output", String.class))
                        .filter(str -> !str.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .metadata(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.METADATA, row, "metadata", String.class))
                        .filter(str -> !str.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
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
                        .map(this::mapFeedbackScores)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .guardrailsValidations(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.GUARDRAILS_VALIDATIONS, row,
                                "guardrails_validations", List.class))
                        .map(this::mapGuardrails)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .spanCount(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.SPAN_COUNT, row, "span_count", Integer.class))
                        .orElse(0))
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
                .build());
    }

    private List<FeedbackScore> mapFeedbackScores(List<List<Object>> feedbackScores) {
        return Optional.ofNullable(feedbackScores)
                .orElse(List.of())
                .stream()
                .map(feedbackScore -> FeedbackScore.builder()
                        .name((String) feedbackScore.get(0))
                        .categoryName(getIfNotEmpty(feedbackScore.get(1)))
                        .value((BigDecimal) feedbackScore.get(2))
                        .reason(getIfNotEmpty(feedbackScore.get(3)))
                        .source(ScoreSource.fromString((String) feedbackScore.get(4)))
                        .createdAt(((OffsetDateTime) feedbackScore.get(5)).toInstant())
                        .lastUpdatedAt(((OffsetDateTime) feedbackScore.get(6)).toInstant())
                        .createdBy((String) feedbackScore.get(7))
                        .lastUpdatedBy((String) feedbackScore.get(8))
                        .build())
                .toList();
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

    private String getIfNotEmpty(Object value) {
        return Optional.ofNullable((String) value)
                .filter(StringUtils::isNotEmpty)
                .orElse(null);
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
        var template = new ST(query);
        Optional.ofNullable(traceSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE)
                            .ifPresent(traceFilters -> template.add("filters", traceFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_AGGREGATION)
                            .ifPresent(traceAggregationFilters -> template.add("trace_aggregation_filters",
                                    traceAggregationFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(scoresFilters -> template.add("feedback_scores_filters", scoresFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE_THREAD)
                            .ifPresent(threadFilters -> template.add("trace_thread_filters", threadFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoreIsEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoreIsEmptyFilters));
                });
        Optional.ofNullable(traceSearchCriteria.lastReceivedTraceId())
                .ifPresent(lastReceivedTraceId -> template.add("last_received_trace_id", lastReceivedTraceId));
        return template;
    }

    private void bindSearchCriteria(TraceSearchCriteria traceSearchCriteria, Statement statement) {
        Optional.ofNullable(traceSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_AGGREGATION);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_THREAD);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                });
        Optional.ofNullable(traceSearchCriteria.lastReceivedTraceId())
                .ifPresent(lastReceivedTraceId -> statement.bind("last_received_trace_id", lastReceivedTraceId));
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

            var template = new ST(BATCH_INSERT)
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
    public Flux<WorkspaceTraceCount> countTracesPerWorkspace(Connection connection) {

        var statement = connection.createStatement(new ST(TRACE_COUNT_BY_WORKSPACE_ID).render());
        return Mono.from(statement.execute())
                .flatMapMany(result -> result.map((row, rowMetadata) -> WorkspaceTraceCount.builder()
                        .workspace(row.get("workspace_id", String.class))
                        .traceCount(row.get("trace_count", Integer.class)).build()));
    }

    @Override
    @WithSpan
    public Flux<BiInformation> getTraceBIInformation(Connection connection) {

        var statement = connection.createStatement(TRACE_DAILY_BI_INFORMATION);

        return Mono.from(statement.execute())
                .flatMapMany(result -> result.map((row, rowMetadata) -> BiInformation.builder()
                        .workspaceId(row.get("workspace_id", String.class))
                        .user(row.get("user", String.class))
                        .count(row.get("trace_count", Long.class)).build()));
    }

    @Override
    public Mono<ProjectStats> getStats(@NonNull TraceSearchCriteria criteria) {
        return asyncTemplate.nonTransaction(connection -> {

            ST statsSQL = newFindTemplate(SELECT_TRACES_STATS, criteria);

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
    public Mono<Long> getDailyTraces(@NonNull List<UUID> excludedProjectIds) {
        ST sql = new ST(TRACE_COUNT_BY_WORKSPACE_ID);

        if (!excludedProjectIds.isEmpty()) {
            sql.add("excluded_project_ids", excludedProjectIds);
        }

        return asyncTemplate
                .nonTransaction(
                        connection -> {
                            Statement statement = connection.createStatement(sql.render());

                            if (!excludedProjectIds.isEmpty()) {
                                statement.bind("excluded_project_ids", excludedProjectIds);
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
                    ST template = new ST(SELECT_TRACES_STATS);

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
    public Mono<TraceThreadPage> findThreads(int size, int page, @NonNull TraceSearchCriteria criteria) {

        return asyncTemplate.nonTransaction(connection -> countThreadTotal(criteria, connection)
                .flatMap(count -> {

                    ST template = newFindTemplate(SELECT_TRACES_THREADS_BY_PROJECT_IDS, criteria);

                    template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

                    var statement = connection.createStatement(template.render())
                            .bind("project_id", criteria.projectId())
                            .bind("limit", size)
                            .bind("offset", (page - 1) * size);

                    bindSearchCriteria(criteria, statement);

                    Segment segment = startSegment("traces", "Clickhouse", "findThreads");

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                            .flatMap(this::mapThreadToDto)
                            .collectList()
                            .doFinally(signalType -> endSegment(segment))
                            .map(threads -> new TraceThreadPage(page, threads.size(), count, threads));
                }));
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
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .lastMessage(Optional.ofNullable(row.get("last_message", String.class))
                        .filter(it -> !it.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .numberOfMessages(row.get("number_of_messages", Long.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .createdAt(row.get("created_at", Instant.class))
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
    public Mono<Long> deleteThreads(@NonNull UUID projectId, @NonNull List<String> threadIds) {
        Preconditions.checkArgument(!threadIds.isEmpty(), "threadIds must not be empty");

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(DELETE_THREADS_BY_PROJECT_ID)
                    .bind("project_id", projectId)
                    .bind("thread_ids", threadIds.toArray(String[]::new));

            Segment segment = startSegment("traces", "Clickhouse", "deleteThreads");

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMapMany(Result::getRowsUpdated)
                    .reduce(0L, Long::sum);
        });
    }

    @Override
    public Mono<TraceThread> findThreadById(@NonNull UUID projectId, @NonNull String threadId) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_TRACES_THREAD_BY_ID)
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
}
