package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse.BiInformation;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceSearchCriteria;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
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
import java.util.stream.Collectors;

import static com.comet.opik.api.ErrorInfo.ERROR_INFO_TYPE;
import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.api.TraceCountResponse.WorkspaceTraceCount;
import static com.comet.opik.api.TraceThread.TraceThreadPage;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.domain.CommentResultMapper.getComments;
import static com.comet.opik.domain.FeedbackScoreDAO.EntityType;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(TraceDAOImpl.class)
interface TraceDAO {

    Mono<UUID> insert(Trace trace, Connection connection);

    Mono<Void> update(TraceUpdate traceUpdate, UUID id, Connection connection);

    Mono<Void> delete(UUID id, Connection connection);

    Mono<Void> delete(Set<UUID> ids, Connection connection);

    Mono<Trace> findById(UUID id, Connection connection);

    Mono<TracePage> find(int size, int page, TraceSearchCriteria traceSearchCriteria, Connection connection);

    Mono<Void> partialInsert(UUID projectId, TraceUpdate traceUpdate, UUID traceId, Connection connection);

    Mono<List<WorkspaceAndResourceId>> getTraceWorkspace(Set<UUID> traceIds, Connection connection);

    Mono<Long> batchInsert(List<Trace> traces, Connection connection);

    Flux<WorkspaceTraceCount> countTracesPerWorkspace(Connection connection);

    Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId, Connection connection);

    Mono<UUID> getProjectIdFromTrace(@NonNull UUID traceId);

    Flux<BiInformation> getTraceBIInformation(Connection connection);

    Mono<ProjectStats> getStats(TraceSearchCriteria criteria);

    Mono<Long> getDailyTraces();

    Mono<Map<UUID, ProjectStats>> getStatsByProjectIds(List<UUID> projectIds, String workspaceId);

    Mono<TraceThreadPage> findThreads(int size, int page, TraceSearchCriteria threadSearchCriteria);

    Mono<Long> deleteThreads(UUID uuid, List<String> threadIds);

    Mono<TraceThread> findThreadById(UUID projectId, String threadId);
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
                multiIf(
                    LENGTH(CAST(old_trace.workspace_id AS Nullable(String))) > 0 AND notEquals(old_trace.workspace_id, new_trace.workspace_id), CAST(leftPad(new_trace.workspace_id, 40, '*') AS FixedString(19)),
                    LENGTH(CAST(old_trace.workspace_id AS Nullable(String))) > 0, old_trace.workspace_id,
                    new_trace.workspace_id
                ) as workspace_id,
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
                ORDER BY id DESC, last_updated_at DESC
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
            	name,
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
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SELECT_BY_ID = """
            SELECT
                t.*,
                sumMap(s.usage) as usage,
                sum(s.total_estimated_cost) as total_estimated_cost,
                groupUniqArrayArray(c.comments_array) as comments
            FROM (
                SELECT
                    *,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                                AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                            (dateDiff('microsecond', start_time, end_time) / 1000.0),
                            NULL) AS duration_millis
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
                    total_estimated_cost
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
            GROUP BY
                t.*
            ;
            """;

    private static final String SELECT_BY_PROJECT_ID = """
            WITH spans_agg AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        trace_id,
                        usage,
                        total_estimated_cost
                    FROM spans
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
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
            SELECT
                  t.id as id,
                  t.workspace_id as workspace_id,
                  t.project_id as project_id,
                  t.name as name,
                  t.start_time as start_time,
                  t.end_time as end_time,
                  <if(truncate)> replaceRegexpAll(input, '<truncate>', '"[image]"') as input <else> input <endif>,
                  <if(truncate)> replaceRegexpAll(output, '<truncate>', '"[image]"') as output <else> output <endif>,
                  <if(truncate)> replaceRegexpAll(metadata, '<truncate>', '"[image]"') as metadata <else> metadata <endif>,
                  t.tags as tags,
                  t.error_info as error_info,
                  t.created_at as created_at,
                  t.last_updated_at as last_updated_at,
                  t.created_by as created_by,
                  t.last_updated_by as last_updated_by,
                  t.duration_millis as duration_millis,
                  t.duration_millis as duration,
                  t.thread_id as thread_id,
                  sumMap(s.usage) as usage,
                  sum(s.total_estimated_cost) as total_estimated_cost,
                  groupUniqArrayArray(c.comments_array) as comments
             FROM (
                 SELECT
                     *,
                     if(end_time IS NOT NULL AND start_time IS NOT NULL
                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                         NULL) AS duration_millis
                 FROM traces t
                 <if(trace_aggregation_filters)>
                 LEFT JOIN spans_agg s ON t.id = s.trace_id
                 <endif>
                 WHERE workspace_id = :workspace_id
                 AND project_id = :project_id
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
                 AND <trace_aggregation_filters>
                 <endif>
                 ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                 LIMIT 1 BY id
                 LIMIT :limit OFFSET :offset
             ) AS t
             LEFT JOIN spans_agg AS s ON t.id = s.trace_id
             LEFT JOIN comments_agg AS c ON t.id = c.entity_id
             GROUP BY
                t.*
             ORDER BY <if(sort_fields)> <sort_fields>, id DESC <else>(workspace_id, project_id, id) DESC, last_updated_at DESC <endif>
             SETTINGS join_algorithm = 'full_sorting_merge'
            ;
            """;

    private static final String TRACE_COUNT_BY_WORKSPACE_ID = """
                SELECT
                     workspace_id,
                     COUNT(DISTINCT id) as trace_count
                 FROM traces
                 WHERE created_at BETWEEN toStartOfDay(yesterday()) AND toStartOfDay(today())
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
                         NULL) AS duration_millis
                    FROM traces
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
                multiIf(
                    LENGTH(CAST(old_trace.workspace_id AS Nullable(String))) > 0 AND notEquals(old_trace.workspace_id, new_trace.workspace_id), CAST(leftPad(new_trace.workspace_id, 40, '*') AS FixedString(19)),
                    LENGTH(CAST(old_trace.workspace_id AS Nullable(String))) > 0, old_trace.workspace_id,
                    new_trace.workspace_id
                ) as workspace_id,
                multiIf(
                    LENGTH(new_trace.name) > 0, new_trace.name,
                    old_trace.name
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
                    '' as name,
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
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1
            ) as old_trace
            ON new_trace.id = old_trace.id
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
                    workspace_id,
                    project_id,
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        trace_id,
                        usage,
                        total_estimated_cost
                    FROM spans
                    WHERE workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                GROUP BY workspace_id, project_id, trace_id
            ), feedback_scores_agg AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) as feedback_scores
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        value
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                )
                GROUP BY workspace_id, project_id, entity_id
            )
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                countDistinct(t.id) AS trace_count,
                arrayMap(v -> toDecimal64(if(isNaN(v), 0, v), 9), quantiles(0.5, 0.9, 0.99)(t.duration_millis)) AS duration,
                sum(notEmpty(t.input)) AS input,
                sum(notEmpty(t.output)) AS output,
                sum(notEmpty(t.metadata)) AS metadata,
                avg(length(t.tags)) AS tags,
                avgMap(s.usage) as usage,
                avgMap(f.feedback_scores) AS feedback_scores,
                avgIf(s.total_estimated_cost, s.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg
            FROM (
                SELECT
                    *,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                            AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                        (dateDiff('microsecond', start_time, end_time) / 1000.0),
                        NULL) as duration_millis
                FROM traces
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
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) t
            LEFT JOIN spans_agg AS s ON t.id = s.trace_id
            LEFT JOIN feedback_scores_agg as f ON t.id = f.entity_id
            GROUP BY t.workspace_id, t.project_id
            SETTINGS join_algorithm = 'auto'
            ;
            """;

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
                           NULL) AS duration_millis,
                    <if(truncate)> replaceRegexpAll(argMin(t.input, t.start_time), '<truncate>', '"[image]"') as first_message <else> argMin(t.input, t.start_time) as first_message<endif>,
                    <if(truncate)> replaceRegexpAll(argMax(t.output, t.end_time), '<truncate>', '"[image]"') as last_message <else> argMax(t.output, t.end_time) as last_message<endif>,
                    count(DISTINCT t.id) as number_of_messages,
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
                       NULL) AS duration_millis,
                <if(truncate)> replaceRegexpAll(argMin(t.input, t.start_time), '<truncate>', '"[image]"') as first_message <else> argMin(t.input, t.start_time) as first_message<endif>,
                <if(truncate)> replaceRegexpAll(argMax(t.output, t.end_time), '<truncate>', '"[image]"') as last_message <else> argMax(t.output, t.end_time) as last_message<endif>,
                count(DISTINCT t.id) as number_of_messages,
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
                       NULL) AS duration_millis,
                argMin(t.input, t.start_time) as first_message,
                argMax(t.output, t.end_time) as last_message,
                count(DISTINCT t.id) as number_of_messages,
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

    private final @NonNull FeedbackScoreDAO feedbackScoreDAO;
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
                .bind("name", trace.name())
                .bind("start_time", trace.startTime().toString());

        if (trace.input() != null) {
            statement.bind("input", trace.input().toString());
        } else {
            statement.bind("input", "");
        }

        if (trace.output() != null) {
            statement.bind("output", trace.output().toString());
        } else {
            statement.bind("output", "");
        }

        if (trace.endTime() != null) {
            statement.bind("end_time", trace.endTime().toString());
        }

        if (trace.metadata() != null) {
            statement.bind("metadata", trace.metadata().toString());
        } else {
            statement.bind("metadata", "");
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

        if (trace.threadId() != null) {
            statement.bind("thread_id", trace.threadId());
        } else {
            statement.bind("thread_id", "");
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

        Optional.ofNullable(traceUpdate.threadId())
                .ifPresent(threadId -> statement.bind("thread_id", threadId));
    }

    private ST buildUpdateTemplate(TraceUpdate traceUpdate, String update) {
        ST template = new ST(update);

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

        Optional.ofNullable(traceUpdate.threadId())
                .ifPresent(threadId -> template.add("thread_id", threadId));

        return template;
    }

    private Flux<? extends Result> getById(UUID id, Connection connection) {
        var statement = connection.createStatement(SELECT_BY_ID)
                .bind("id", id);

        Segment segment = startSegment("traces", "Clickhouse", "getById");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
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
                .flatMap(this::mapToDto)
                .flatMap(trace -> enhanceWithFeedbackLogs(List.of(trace)))
                .flatMap(traces -> Mono.justOrEmpty(traces.stream().findFirst()))
                .singleOrEmpty();
    }

    private Publisher<Trace> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> Trace.builder()
                .id(row.get("id", UUID.class))
                .projectId(row.get("project_id", UUID.class))
                .name(row.get("name", String.class))
                .startTime(row.get("start_time", Instant.class))
                .endTime(row.get("end_time", Instant.class))
                .input(Optional.ofNullable(row.get("input", String.class))
                        .filter(it -> !it.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .output(Optional.ofNullable(row.get("output", String.class))
                        .filter(it -> !it.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .metadata(Optional.ofNullable(row.get("metadata", String.class))
                        .filter(it -> !it.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .tags(Optional.of(Arrays.stream(row.get("tags", String[].class))
                        .collect(Collectors.toSet()))
                        .filter(it -> !it.isEmpty())
                        .orElse(null))
                .comments(getComments(row.get("comments", List[].class)))
                .usage(row.get("usage", Map.class))
                .totalEstimatedCost(row.get("total_estimated_cost", BigDecimal.class).compareTo(BigDecimal.ZERO) == 0
                        ? null
                        : row.get("total_estimated_cost", BigDecimal.class))
                .errorInfo(Optional.ofNullable(row.get("error_info", String.class))
                        .filter(str -> !str.isBlank())
                        .map(errorInfo -> JsonUtils.readValue(errorInfo, ERROR_INFO_TYPE))
                        .orElse(null))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .duration(row.get("duration_millis", Double.class))
                .threadId(Optional.ofNullable(row.get("thread_id", String.class))
                        .filter(StringUtils::isNotEmpty)
                        .orElse(null))
                .build());
    }

    @Override
    @WithSpan
    public Mono<TracePage> find(
            int size, int page, @NonNull TraceSearchCriteria traceSearchCriteria, @NonNull Connection connection) {
        return countTotal(traceSearchCriteria, connection)
                .flatMap(result -> Mono.from(result.map((row, rowMetadata) -> row.get("count", Long.class))))
                .flatMap(total -> getTracesByProjectId(size, page, traceSearchCriteria, connection) //Get count then pagination
                        .flatMapMany(this::mapToDto)
                        .collectList()
                        .flatMap(this::enhanceWithFeedbackLogs)
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

    private Mono<List<Trace>> enhanceWithFeedbackLogs(List<Trace> traces) {
        List<UUID> traceIds = traces.stream().map(Trace::id).toList();

        Segment segment = startSegment("traces", "Clickhouse", "enhanceWithFeedbackLogs");

        return feedbackScoreDAO.getScores(EntityType.TRACE, traceIds)
                .map(logsMap -> traces.stream()
                        .map(trace -> trace.toBuilder().feedbackScores(logsMap.get(trace.id())).build())
                        .toList())
                .doFinally(signalType -> endSegment(segment));
    }

    private Mono<? extends Result> getTracesByProjectId(
            int size, int page, TraceSearchCriteria traceSearchCriteria, Connection connection) {
        var template = newFindTemplate(SELECT_BY_PROJECT_ID, traceSearchCriteria);

        var finalTemplate = template;
        Optional.ofNullable(sortingQueryBuilder.toOrderBySql(traceSearchCriteria.sortingFields()))
                .ifPresent(sortFields -> finalTemplate.add("sort_fields", sortFields));

        template = ImageUtils.addTruncateToTemplate(template, traceSearchCriteria.truncate());
        var statement = connection.createStatement(template.render())
                .bind("project_id", traceSearchCriteria.projectId())
                .bind("limit", size)
                .bind("offset", (page - 1) * size);
        bindSearchCriteria(traceSearchCriteria, statement);

        Segment segment = startSegment("traces", "Clickhouse", "find");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment));
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
                });
        return template;
    }

    private void bindSearchCriteria(TraceSearchCriteria traceSearchCriteria, Statement statement) {
        Optional.ofNullable(traceSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_AGGREGATION);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE_THREAD);
                });
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
                        .bind("name" + i, trace.name())
                        .bind("start_time" + i, trace.startTime().toString())
                        .bind("input" + i, getOrDefault(trace.input()))
                        .bind("output" + i, getOrDefault(trace.output()))
                        .bind("metadata" + i, getOrDefault(trace.metadata()))
                        .bind("tags" + i, trace.tags() != null ? trace.tags().toArray(String[]::new) : new String[]{})
                        .bind("error_info" + i,
                                trace.errorInfo() != null ? JsonUtils.readTree(trace.errorInfo()).toString() : "")
                        .bind("thread_id" + i, trace.threadId() != null ? trace.threadId() : "");

                if (trace.endTime() != null) {
                    statement.bind("end_time" + i, trace.endTime().toString());
                } else {
                    statement.bindNull("end_time" + i, String.class);
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

        var statement = connection.createStatement(TRACE_COUNT_BY_WORKSPACE_ID);

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
    public Mono<Long> getDailyTraces() {
        return asyncTemplate
                .nonTransaction(
                        connection -> Mono.from(connection.createStatement(TRACE_COUNT_BY_WORKSPACE_ID).execute()))
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
                    Statement statement = connection.createStatement(new ST(SELECT_TRACES_STATS).render())
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

        return asyncTemplate.nonTransaction(connection -> {
            return countThreadTotal(criteria, connection)
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
                    });
        });
    }

    private Publisher<TraceThread> mapThreadToDto(Result result) {
        return result.map((row, rowMetadata) -> TraceThread.builder()
                .id(row.get("id", String.class))
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .startTime(row.get("start_time", Instant.class))
                .endTime(row.get("end_time", Instant.class))
                .duration(row.get("duration_millis", Double.class))
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
}
