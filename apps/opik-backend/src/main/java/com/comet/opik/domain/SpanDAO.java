package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchCriteria;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.sorting.SpanSortingFactory;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.ErrorInfo.ERROR_INFO_TYPE;
import static com.comet.opik.api.Span.SpanField;
import static com.comet.opik.api.Span.SpanPage;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;
import static java.util.function.Predicate.not;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class SpanDAO {

    private static final String BULK_INSERT = """
            INSERT INTO spans(
                id,
                project_id,
                workspace_id,
                trace_id,
                parent_span_id,
                name,
                type,
                start_time,
                end_time,
                input,
                output,
                metadata,
                model,
                provider,
                total_estimated_cost,
                total_estimated_cost_version,
                tags,
                usage,
                last_updated_at,
                error_info,
                created_by,
                last_updated_by
            ) VALUES
                <items:{item |
                    (
                        :id<item.index>,
                        :project_id<item.index>,
                        :workspace_id,
                        :trace_id<item.index>,
                        :parent_span_id<item.index>,
                        :name<item.index>,
                        :type<item.index>,
                        parseDateTime64BestEffort(:start_time<item.index>, 9),
                        if(:end_time<item.index> IS NULL, NULL, parseDateTime64BestEffort(:end_time<item.index>, 9)),
                        :input<item.index>,
                        :output<item.index>,
                        :metadata<item.index>,
                        :model<item.index>,
                        :provider<item.index>,
                        toDecimal128(:total_estimated_cost<item.index>, 12),
                        :total_estimated_cost_version<item.index>,
                        :tags<item.index>,
                        mapFromArrays(:usage_keys<item.index>, :usage_values<item.index>),
                        if(:last_updated_at<item.index> IS NULL, NULL, parseDateTime64BestEffort(:last_updated_at<item.index>, 6)),
                        :error_info<item.index>,
                        :created_by<item.index>,
                        :last_updated_by<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    /**
     * This query handles the insertion of a new span into the database in two cases:
     * 1. When the span does not exist in the database.
     * 2. When the span exists in the database but the provided span has different values for the fields such as end_time, input, output, metadata and tags.
     **/
    private static final String INSERT = """
            INSERT INTO spans(
                id,
                project_id,
                workspace_id,
                trace_id,
                parent_span_id,
                name,
                type,
                start_time,
                end_time,
                input,
                output,
                metadata,
                model,
                provider,
                total_estimated_cost,
                total_estimated_cost_version,
                tags,
                usage,
                error_info,
                created_at,
                created_by,
                last_updated_by
            )
            SELECT
                new_span.id as id,
                multiIf(
                    LENGTH(CAST(old_span.project_id AS Nullable(String))) > 0 AND notEquals(old_span.project_id, new_span.project_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_span.project_id AS Nullable(String))) > 0, old_span.project_id,
                    new_span.project_id
                ) as project_id,
                new_span.workspace_id as workspace_id,
                multiIf(
                    LENGTH(CAST(old_span.trace_id AS Nullable(String))) > 0 AND notEquals(old_span.trace_id, new_span.trace_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_span.trace_id AS Nullable(String))) > 0, old_span.trace_id,
                    new_span.trace_id
                ) as trace_id,
                multiIf(
                    LENGTH(CAST(old_span.parent_span_id AS Nullable(String))) > 0 AND notEquals(old_span.parent_span_id, new_span.parent_span_id), CAST(leftPad(new_span.parent_span_id, 40, '*') AS FixedString(19)),
                    LENGTH(CAST(old_span.parent_span_id AS Nullable(String))) > 0, old_span.parent_span_id,
                    new_span.parent_span_id
                ) as parent_span_id,
                multiIf(
                    LENGTH(old_span.name) > 0, old_span.name,
                    new_span.name
                ) as name,
                multiIf(
                    CAST(old_span.type, 'Int8') > 0, old_span.type,
                    new_span.type
                ) as type,
                multiIf(
                    notEquals(old_span.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_span.start_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_span.start_time,
                    new_span.start_time
                ) as start_time,
                multiIf(
                    isNotNull(old_span.end_time), old_span.end_time,
                    new_span.end_time
                ) as end_time,
                multiIf(
                    LENGTH(old_span.input) > 0, old_span.input,
                    new_span.input
                ) as input,
                multiIf(
                    LENGTH(old_span.output) > 0, old_span.output,
                    new_span.output
                ) as output,
                multiIf(
                    LENGTH(old_span.metadata) > 0, old_span.metadata,
                    new_span.metadata
                ) as metadata,
                multiIf(
                    LENGTH(old_span.model) > 0, old_span.model,
                    new_span.model
                ) as model,
                multiIf(
                    LENGTH(old_span.provider) > 0, old_span.provider,
                    new_span.provider
                ) as provider,
                multiIf(
                    old_span.total_estimated_cost > 0, old_span.total_estimated_cost,
                    new_span.total_estimated_cost
                ) as total_estimated_cost,
                multiIf(
                    LENGTH(old_span.total_estimated_cost_version) > 0, old_span.total_estimated_cost_version,
                    new_span.total_estimated_cost_version
                ) as total_estimated_cost_version,
                multiIf(
                    notEmpty(old_span.tags), old_span.tags,
                    new_span.tags
                ) as tags,
                multiIf(
                    notEmpty(mapKeys(old_span.usage)), old_span.usage,
                    new_span.usage
                ) as usage,
                multiIf(
                    LENGTH(old_span.error_info) > 0, old_span.error_info,
                    new_span.error_info
                ) as error_info,
                multiIf(
                    notEquals(old_span.created_at, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_span.created_at >= toDateTime64('1970-01-01 00:00:00.000', 9), old_span.created_at,
                    new_span.created_at
                ) as created_at,
                multiIf(
                    LENGTH(old_span.created_by) > 0, old_span.created_by,
                    new_span.created_by
                ) as created_by,
                new_span.last_updated_by as last_updated_by
            FROM (
                SELECT
                    :id as id,
                    :project_id as project_id,
                    :workspace_id as workspace_id,
                    :trace_id as trace_id,
                    :parent_span_id as parent_span_id,
                    :name as name,
                    CAST(:type, 'Enum8(\\'unknown\\' = 0 , \\'general\\' = 1, \\'tool\\' = 2, \\'llm\\' = 3, \\'guardrail\\' = 4)') as type,
                    parseDateTime64BestEffort(:start_time, 9) as start_time,
                    <if(end_time)> parseDateTime64BestEffort(:end_time, 9) as end_time, <else> null as end_time, <endif>
                    :input as input,
                    :output as output,
                    :metadata as metadata,
                    :model as model,
                    :provider as provider,
                    toDecimal128(:total_estimated_cost, 12) as total_estimated_cost,
                    :total_estimated_cost_version as total_estimated_cost_version,
                    :tags as tags,
                    mapFromArrays(:usage_keys, :usage_values) as usage,
                    :error_info as error_info,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by
            ) as new_span
            LEFT JOIN (
                SELECT
                    *
                FROM spans
                WHERE workspace_id = :workspace_id
                AND id = :id
                ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) as old_span
            ON new_span.id = old_span.id
            ;
            """;

    /***
     * Handles the update of a span when the span already exists in the database.
     ***/
    //TODO: refactor to implement proper conflict resolution
    private static final String UPDATE = """
            INSERT INTO spans (
            	id,
            	project_id,
            	workspace_id,
            	trace_id,
            	parent_span_id,
            	name,
            	type,
            	start_time,
            	end_time,
            	input,
            	output,
            	metadata,
            	model,
            	provider,
            	total_estimated_cost,
                total_estimated_cost_version,
            	tags,
            	usage,
            	error_info,
            	created_at,
            	created_by,
            	last_updated_by
            ) SELECT
            	id,
            	project_id,
            	workspace_id,
            	trace_id,
            	parent_span_id,
                <if(name)> :name <else> name <endif> as name,
            	type,
            	start_time,
            	<if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> end_time <endif> as end_time,
            	<if(input)> :input <else> input <endif> as input,
            	<if(output)> :output <else> output <endif> as output,
            	<if(metadata)> :metadata <else> metadata <endif> as metadata,
            	<if(model)> :model <else> model <endif> as model,
            	<if(provider)> :provider <else> provider <endif> as provider,
            	<if(total_estimated_cost)> toDecimal128(:total_estimated_cost, 12) <else> total_estimated_cost <endif> as total_estimated_cost,
            	<if(total_estimated_cost_version)> :total_estimated_cost_version <else> total_estimated_cost_version <endif> as total_estimated_cost_version,
            	<if(tags)> :tags <else> tags <endif> as tags,
            	<if(usage)> CAST((:usageKeys, :usageValues), 'Map(String, Int64)') <else> usage <endif> as usage,
            	<if(error_info)> :error_info <else> error_info <endif> as error_info,
            	created_at,
            	created_by,
                :user_name as last_updated_by
            FROM spans
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    /**
     * This query is used when updates are processed before inserts, and the span does not exist in the database.
     * <p>
     * The query will insert/update a new span with the provided values such as end_time, input, output, metadata, tags etc.
     * In case the values are not provided, the query will use the default values such value are interpreted in other queries as null.
     * <p>
     * This happens because the query is used in a patch endpoint which allows partial updates, so the query will update only the provided fields.
     * The remaining fields will be updated/inserted once the POST arrives with the all mandatory fields to create the trace.
     */
    //TODO: refactor to implement proper conflict resolution
    private static final String PARTIAL_INSERT = """
            INSERT INTO spans(
                id, project_id, workspace_id, trace_id, parent_span_id, name, type,
                start_time, end_time, input, output, metadata, model, provider, total_estimated_cost, total_estimated_cost_version, tags, usage, error_info, created_at,
                created_by, last_updated_by
            )
            SELECT
                new_span.id as id,
                multiIf(
                    LENGTH(CAST(old_span.project_id AS Nullable(String))) > 0 AND notEquals(old_span.project_id, new_span.project_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_span.project_id AS Nullable(String))) > 0, old_span.project_id,
                    new_span.project_id
                ) as project_id,
                new_span.workspace_id as workspace_id,
                multiIf(
                    LENGTH(CAST(old_span.trace_id AS Nullable(String))) > 0 AND notEquals(old_span.trace_id, new_span.trace_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_span.trace_id AS Nullable(String))) > 0, old_span.trace_id,
                    new_span.trace_id
                ) as trace_id,
                multiIf(
                    LENGTH(CAST(old_span.parent_span_id AS Nullable(String))) > 0 AND notEquals(old_span.parent_span_id, new_span.parent_span_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_span.parent_span_id AS Nullable(String))) > 0, old_span.parent_span_id,
                    new_span.parent_span_id
                ) as parent_span_id,
                multiIf(
                    LENGTH(new_span.name) > 0, new_span.name,
                    LENGTH(old_span.name) > 0, old_span.name,
                    new_span.name
                ) as name,
                multiIf(
                    CAST(new_span.type, 'Int8') > 0 , new_span.type,
                    old_span.type
                ) as type,
                multiIf(
                    notEquals(old_span.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_span.start_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_span.start_time,
                    new_span.start_time
                ) as start_time,
                multiIf(
                    notEquals(new_span.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND new_span.end_time >= toDateTime64('1970-01-01 00:00:00.000', 9), new_span.end_time,
                    notEquals(old_span.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_span.end_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_span.end_time,
                    new_span.end_time
                ) as end_time,
                multiIf(
                    LENGTH(new_span.input) > 0, new_span.input,
                    LENGTH(old_span.input) > 0, old_span.input,
                    new_span.input
                ) as input,
                multiIf(
                    LENGTH(new_span.output) > 0, new_span.output,
                    LENGTH(old_span.output) > 0, old_span.output,
                    new_span.output
                ) as output,
                multiIf(
                    LENGTH(new_span.metadata) > 0, new_span.metadata,
                    LENGTH(old_span.metadata) > 0, old_span.metadata,
                    new_span.metadata
                ) as metadata,
                multiIf(
                    LENGTH(new_span.model) > 0, new_span.model,
                    LENGTH(old_span.model) > 0, old_span.model,
                    new_span.model
                ) as model,
                multiIf(
                    LENGTH(new_span.provider) > 0, new_span.provider,
                    LENGTH(old_span.provider) > 0, old_span.provider,
                    new_span.provider
                ) as provider,
                multiIf(
                    new_span.total_estimated_cost > 0, new_span.total_estimated_cost,
                    old_span.total_estimated_cost > 0, old_span.total_estimated_cost,
                    new_span.total_estimated_cost
                ) as total_estimated_cost,
                multiIf(
                    LENGTH(new_span.total_estimated_cost_version) > 0, new_span.total_estimated_cost_version,
                    LENGTH(old_span.total_estimated_cost_version) > 0, old_span.total_estimated_cost_version,
                    new_span.total_estimated_cost_version
                ) as total_estimated_cost_version,
                multiIf(
                    notEmpty(new_span.tags), new_span.tags,
                    notEmpty(old_span.tags), old_span.tags,
                    new_span.tags
                ) as tags,
                multiIf(
                    notEmpty(mapKeys(new_span.usage)), new_span.usage,
                    notEmpty(mapKeys(old_span.usage)), old_span.usage,
                    new_span.usage
                ) as usage,
                multiIf(
                    LENGTH(new_span.error_info) > 0, new_span.error_info,
                    LENGTH(old_span.error_info) > 0, old_span.error_info,
                    new_span.error_info
                ) as error_info,
                multiIf(
                    notEquals(old_span.created_at, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_span.created_at >= toDateTime64('1970-01-01 00:00:00.000', 9), old_span.created_at,
                    new_span.created_at
                ) as created_at,
                multiIf(
                    LENGTH(old_span.created_by) > 0, old_span.created_by,
                    new_span.created_by
                ) as created_by,
                new_span.last_updated_by as last_updated_by
            FROM (
                SELECT
                    :id as id,
                    :project_id as project_id,
                    :workspace_id as workspace_id,
                    :trace_id as trace_id,
                    :parent_span_id as parent_span_id,
                    <if(name)> :name <else> '' <endif> as name,
                    CAST('unknown', 'Enum8(\\'unknown\\' = 0 , \\'general\\' = 1, \\'tool\\' = 2, \\'llm\\' = 3)') as type,
                    toDateTime64('1970-01-01 00:00:00.000', 9) as start_time,
                    <if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> null <endif> as end_time,
                    <if(input)> :input <else> '' <endif> as input,
                    <if(output)> :output <else> '' <endif> as output,
                    <if(metadata)> :metadata <else> '' <endif> as metadata,
                    <if(model)> :model <else> '' <endif> as model,
                    <if(provider)> :provider <else> '' <endif> as provider,
                    <if(total_estimated_cost)> toDecimal128(:total_estimated_cost, 12) <else> toDecimal128(0, 12) <endif> as total_estimated_cost,
                    <if(total_estimated_cost_version)> :total_estimated_cost_version <else> '' <endif> as total_estimated_cost_version,
                    <if(tags)> :tags <else> [] <endif> as tags,
                    <if(usage)> CAST((:usageKeys, :usageValues), 'Map(String, Int64)') <else>  mapFromArrays([], []) <endif> as usage,
                    <if(error_info)> :error_info <else> '' <endif> as error_info,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by
            ) as new_span
            LEFT JOIN (
                SELECT
                    *
                FROM spans
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) as old_span
            ON new_span.id = old_span.id
            ;
            """;

    private static final String SELECT_BY_ID = """
            SELECT
                s.*,
                s.project_id as project_id,
                groupArray(tuple(c.*)) AS comments,
                any(fs.feedback_scores) as feedback_scores_list
            FROM (
                SELECT
                    *,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                                AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                            (dateDiff('microsecond', start_time, end_time) / 1000.0),
                            NULL) AS duration
                FROM spans
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) AS s
            LEFT JOIN (
                SELECT
                    id AS comment_id,
                    text,
                    created_at AS comment_created_at,
                    last_updated_at AS comment_last_updated_at,
                    created_by AS comment_created_by,
                    last_updated_by AS comment_last_updated_by,
                    entity_id
                FROM comments
                WHERE workspace_id = :workspace_id
                AND entity_id = :id
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS c ON s.id = c.entity_id
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
                    WHERE entity_type = 'span'
                    AND workspace_id = :workspace_id
                    AND entity_id = :id
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                )
                GROUP BY workspace_id, project_id, entity_id
            ) AS fs ON s.id = fs.entity_id
            GROUP BY
                s.*
            ;
            """;

    private static final String SELECT_PARTIAL_BY_ID = """
            SELECT
                type,
                start_time
            FROM spans
            WHERE workspace_id = :workspace_id
            AND id = :id
            ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SELECT_BY_PROJECT_ID = """
            WITH comments_final AS (
              SELECT
                   entity_id,
                   groupArray(tuple(
                       id AS comment_id,
                       text,
                       created_at AS comment_created_at,
                       last_updated_at AS comment_last_updated_at,
                       created_by AS comment_created_by,
                       last_updated_by AS comment_last_updated_by
                   )) as comments
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
            ), feedback_scores_agg AS (
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
                WHERE entity_type = 'span'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
                GROUP BY workspace_id, project_id, entity_id
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores
                    WHERE entity_type = 'span'
                    AND workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , spans_final AS (
                SELECT
                      s.* <if(exclude_fields)>EXCEPT (<exclude_fields>) <endif>,
                      if(end_time IS NOT NULL AND start_time IS NOT NULL
                               AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                           (dateDiff('microsecond', start_time, end_time) / 1000.0),
                           NULL) AS duration
                FROM spans s
                <if(sort_has_feedback_scores)>
                LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = s.id
                <endif>
                WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                <if(last_received_span_id)> AND id \\< :last_received_span_id <endif>
                <if(trace_id)> AND trace_id = :trace_id <endif>
                <if(type)> AND type = :type <endif>
                <if(filters)> AND <filters> <endif>
                <if(feedback_scores_filters)>
                AND id in (
                  SELECT
                      entity_id
                  FROM (
                      SELECT *
                      FROM feedback_scores
                      WHERE entity_type = 'span'
                      AND project_id = :project_id
                      ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                      LIMIT 1 BY entity_id, name
                  )
                  GROUP BY entity_id
                  HAVING <feedback_scores_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                 AND (
                    id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT entity_id FROM fsc)
                 )
                <endif>
                <if(stream)>
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                <else>
                ORDER BY <if(sort_fields)> <sort_fields>, id DESC <else>(workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC <endif>
                <endif>
                LIMIT 1 BY id
                LIMIT :limit <if(offset)>OFFSET :offset <endif>
            )
            SELECT
                s.* <if(exclude_fields)>EXCEPT (<exclude_fields>, input, output, metadata) <else> EXCEPT (input, output, metadata)<endif>
                <if(!exclude_input)>, <if(truncate)> replaceRegexpAll(s.input, '<truncate>', '"[image]"') as input <else> s.input as input<endif> <endif>
                <if(!exclude_output)>, <if(truncate)> replaceRegexpAll(s.output, '<truncate>', '"[image]"') as output <else> s.output as output<endif> <endif>
                <if(!exclude_metadata)>, <if(truncate)> replaceRegexpAll(s.metadata, '<truncate>', '"[image]"') as metadata <else> s.metadata as metadata<endif> <endif>
                <if(!exclude_feedback_scores)>
                , fsa.feedback_scores_list as feedback_scores_list
                , fsa.feedback_scores as feedback_scores
                <endif>
                <if(!exclude_comments)>, c.comments AS comments <endif>
            FROM spans_final s
            LEFT JOIN comments_final c ON s.id = c.entity_id
            LEFT JOIN feedback_scores_agg fsa ON fsa.entity_id = s.id
            <if(stream)>
            ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
            <else>
            ORDER BY <if(sort_fields)> <sort_fields>, id DESC <else>(workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC <endif>
            <endif>
            ;
            """;

    private static final String COUNT_BY_PROJECT_ID = """
            <if(feedback_scores_empty_filters)>
             WITH fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores
                    WHERE entity_type = 'span'
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
            FROM
            (
               SELECT
                    id,
                    if(end_time IS NOT NULL AND start_time IS NOT NULL
                                         AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                                     (dateDiff('microsecond', start_time, end_time) / 1000.0),
                                     NULL) AS duration
                FROM spans
                <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = spans.id
                <endif>
                WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                <if(trace_id)> AND trace_id = :trace_id <endif>
                <if(type)> AND type = :type <endif>
                <if(filters)> AND <filters> <endif>
                <if(feedback_scores_filters)>
                AND id in (
                    SELECT
                        entity_id
                    FROM (
                        SELECT *
                        FROM feedback_scores
                        WHERE entity_type = 'span'
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
                ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS latest_rows
            ;
            """;

    private static final String DELETE_BY_TRACE_IDS = """
            DELETE FROM spans WHERE trace_id IN :trace_ids AND workspace_id = :workspace_id;
            """;

    private static final String SELECT_SPAN_ID_AND_WORKSPACE = """
            SELECT
                DISTINCT id, workspace_id
            FROM spans
            WHERE id IN :spanIds
            ;
            """;

    public static final String SELECT_PROJECT_ID_FROM_SPAN = """
            SELECT
                  DISTINCT project_id
            FROM spans
            WHERE id = :id
            AND workspace_id = :workspace_id
            """;

    private static final String SELECT_SPANS_STATS = """
            WITH feedback_scores_agg AS (
                SELECT
                    project_id,
                    entity_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) as feedback_scores
                FROM feedback_scores final
                WHERE entity_type = 'span'
                AND workspace_id = :workspace_id
                AND project_id = :project_id
                GROUP BY workspace_id, project_id, entity_id
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores
                    WHERE entity_type = 'span'
                    AND workspace_id = :workspace_id
                    AND project_id = :project_id
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , spans_final AS (
                SELECT
                     workspace_id,
                     project_id,
                     id,
                     if(end_time IS NOT NULL AND start_time IS NOT NULL
                                 AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                             (dateDiff('microsecond', start_time, end_time) / 1000.0),
                             NULL) AS duration,
                     if(input_length > 0, 1, 0) as input_count,
                     if(output_length > 0, 1, 0) as output_count,
                     if(metadata_length > 0, 1, 0) as metadata_count,
                     length(tags) as tags_count,
                     usage,
                     total_estimated_cost
                FROM spans final
                <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = spans.id
                <endif>
                WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                <if(trace_id)> AND trace_id = :trace_id <endif>
                <if(type)> AND type = :type <endif>
                <if(filters)> AND <filters> <endif>
                <if(feedback_scores_filters)>
                AND id in (
                    SELECT
                        entity_id
                    FROM (
                        SELECT *
                        FROM feedback_scores
                        WHERE entity_type = 'span'
                        AND project_id = :project_id
                        AND workspace_id = :workspace_id
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
            )
            SELECT
                project_id as project_id,
                count(DISTINCT id) as span_count,
                arrayMap(v -> toDecimal64(if(isNaN(v), 0, v), 9), quantiles(0.5, 0.9, 0.99)(duration)) AS duration,
                sum(input_count) as input,
                sum(output_count) as output,
                sum(metadata_count) as metadata,
                avg(tags_count) as tags,
                avgMap(usage) as usage,
                avgMap(feedback_scores) AS feedback_scores,
                avgIf(total_estimated_cost, total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg
            FROM spans_final s
            LEFT JOIN feedback_scores_agg AS f ON s.id = f.entity_id
            GROUP BY project_id
            ;
            """;

    public static final String SELECT_SPAN_IDS_BY_TRACE_ID = """
            SELECT
                  DISTINCT id
            FROM spans
            WHERE trace_id IN :trace_ids
            AND workspace_id = :workspace_id
            """;

    private static final String SPAN_COUNT_BY_WORKSPACE_ID = """
                SELECT
                     workspace_id,
                     COUNT(DISTINCT id) as span_count
                 FROM spans
                 WHERE created_at BETWEEN toStartOfDay(yesterday()) AND toStartOfDay(today())
                 GROUP BY workspace_id
            ;
            """;

    // ESTIMATED COST CHANGE
    // 1.1 - Added cached tokens for OpenAI
    private static final String ESTIMATED_COST_VERSION = "1.1";

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull SpanSortingFactory sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    @WithSpan
    public Mono<Void> insert(@NonNull Span span) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(span, connection))
                .then();
    }

    @WithSpan
    public Mono<Long> batchInsert(@NonNull List<Span> spans) {

        Preconditions.checkArgument(!spans.isEmpty(), "Spans list must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(spans, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> insert(List<Span> spans, Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(spans.size());

            var template = new ST(BULK_INSERT)
                    .add("items", queryItems);

            Statement statement = connection.createStatement(template.render());

            int i = 0;
            for (Span span : spans) {

                statement.bind("id" + i, span.id())
                        .bind("project_id" + i, span.projectId())
                        .bind("trace_id" + i, span.traceId())
                        .bind("name" + i, StringUtils.defaultIfBlank(span.name(), ""))
                        .bind("type" + i, span.type().toString())
                        .bind("start_time" + i, span.startTime().toString())
                        .bind("parent_span_id" + i, span.parentSpanId() != null ? span.parentSpanId() : "")
                        .bind("input" + i, span.input() != null ? span.input().toString() : "")
                        .bind("output" + i, span.output() != null ? span.output().toString() : "")
                        .bind("metadata" + i, span.metadata() != null ? span.metadata().toString() : "")
                        .bind("model" + i, StringUtils.defaultIfBlank(span.model(), ""))
                        .bind("provider" + i, StringUtils.defaultIfBlank(span.provider(), ""))
                        .bind("tags" + i, span.tags() != null ? span.tags().toArray(String[]::new) : new String[]{})
                        .bind("error_info" + i,
                                span.errorInfo() != null ? JsonUtils.readTree(span.errorInfo()).toString() : "")
                        .bind("created_by" + i, userName)
                        .bind("last_updated_by" + i, userName);

                if (span.endTime() != null) {
                    statement.bind("end_time" + i, span.endTime().toString());
                } else {
                    statement.bindNull("end_time" + i, String.class);
                }

                if (span.usage() != null) {
                    Stream.Builder<String> keys = Stream.builder();
                    Stream.Builder<Integer> values = Stream.builder();

                    span.usage().forEach((key, value) -> {
                        if (Objects.nonNull(value)) {
                            keys.add(key);
                            values.add(value);
                        }
                    });

                    statement.bind("usage_keys" + i, keys.build().toArray(String[]::new));
                    statement.bind("usage_values" + i, values.build().toArray(Integer[]::new));
                } else {
                    statement.bind("usage_keys" + i, new String[]{});
                    statement.bind("usage_values" + i, new Integer[]{});
                }

                if (span.lastUpdatedAt() != null) {
                    statement.bind("last_updated_at" + i, span.lastUpdatedAt().toString());
                } else {
                    statement.bindNull("last_updated_at" + i, String.class);
                }

                bindCost(span, statement, String.valueOf(i));

                i++;
            }

            statement.bind("workspace_id", workspaceId);

            Segment segment = startSegment("spans", "Clickhouse", "batch_insert");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private Publisher<? extends Result> insert(Span span, Connection connection) {
        var template = newInsertTemplate(span);
        var statement = connection.createStatement(template.render())
                .bind("id", span.id())
                .bind("project_id", span.projectId())
                .bind("trace_id", span.traceId())
                .bind("name", StringUtils.defaultIfBlank(span.name(), ""))
                .bind("type", span.type().toString())
                .bind("start_time", span.startTime().toString())
                .bind("input", Objects.toString(span.input(), ""))
                .bind("output", Objects.toString(span.output(), ""))
                .bind("metadata", Objects.toString(span.metadata(), ""))
                .bind("model", StringUtils.defaultIfBlank(span.model(), ""))
                .bind("provider", StringUtils.defaultIfBlank(span.provider(), ""));
        if (span.parentSpanId() != null) {
            statement.bind("parent_span_id", span.parentSpanId());
        } else {
            statement.bind("parent_span_id", "");
        }
        if (span.endTime() != null) {
            statement.bind("end_time", span.endTime().toString());
        }

        if (span.tags() != null) {
            statement.bind("tags", span.tags().toArray(String[]::new));
        } else {
            statement.bind("tags", new String[]{});
        }
        if (span.usage() != null) {

            Stream.Builder<String> keys = Stream.builder();
            Stream.Builder<Integer> values = Stream.builder();

            span.usage().forEach((key, value) -> {
                if (Objects.nonNull(value)) {
                    keys.add(key);
                    values.add(value);
                }
            });

            statement.bind("usage_keys", keys.build().toArray(String[]::new));
            statement.bind("usage_values", values.build().toArray(Integer[]::new));
        } else {
            statement.bind("usage_keys", new String[]{});
            statement.bind("usage_values", new Integer[]{});
        }

        if (span.errorInfo() != null) {
            statement.bind("error_info", JsonUtils.readTree(span.errorInfo()).toString());
        } else {
            statement.bind("error_info", "");
        }

        bindCost(span, statement, "");

        Segment segment = startSegment("spans", "Clickhouse", "insert");

        return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private ST newInsertTemplate(Span span) {
        var template = new ST(INSERT);
        Optional.ofNullable(span.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime));
        return template;
    }

    @WithSpan
    public Mono<Long> update(@NonNull UUID id, @NonNull SpanUpdate spanUpdate, Span existingSpan) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> update(id, spanUpdate, connection, existingSpan))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    @WithSpan
    public Mono<Long> partialInsert(@NonNull UUID id, @NonNull UUID projectId, @NonNull SpanUpdate spanUpdate) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = newUpdateTemplate(spanUpdate, PARTIAL_INSERT, false);

                    var statement = connection.createStatement(template.render());

                    statement.bind("id", id);
                    statement.bind("project_id", projectId);
                    statement.bind("trace_id", spanUpdate.traceId());

                    if (spanUpdate.parentSpanId() != null) {
                        statement.bind("parent_span_id", spanUpdate.parentSpanId());
                    } else {
                        statement.bind("parent_span_id", "");
                    }

                    bindUpdateParams(spanUpdate, statement, false);

                    Segment segment = startSegment("spans", "Clickhouse", "partial_insert");

                    return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                            .doFinally(signalType -> endSegment(segment));
                })
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> update(UUID id, SpanUpdate spanUpdate, Connection connection,
            Span existingSpan) {
        if (spanUpdate.model() != null || spanUpdate.usage() != null || spanUpdate.provider() != null) {
            spanUpdate = spanUpdate.toBuilder()
                    .model(spanUpdate.model() != null ? spanUpdate.model() : existingSpan.model())
                    .provider(spanUpdate.provider() != null ? spanUpdate.provider() : existingSpan.provider())
                    .usage(spanUpdate.usage() != null ? spanUpdate.usage() : existingSpan.usage())
                    .build();
        }

        var template = newUpdateTemplate(spanUpdate, UPDATE, isManualCost(existingSpan));
        var statement = connection.createStatement(template.render());
        statement.bind("id", id);

        bindUpdateParams(spanUpdate, statement, isManualCost(existingSpan));

        Segment segment = startSegment("spans", "Clickhouse", "update");

        return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private void bindUpdateParams(SpanUpdate spanUpdate, Statement statement, boolean isManualCostExist) {
        if (StringUtils.isNotBlank(spanUpdate.name())) {
            statement.bind("name", spanUpdate.name());
        }
        Optional.ofNullable(spanUpdate.input())
                .ifPresent(input -> statement.bind("input", input.toString()));
        Optional.ofNullable(spanUpdate.output())
                .ifPresent(output -> statement.bind("output", output.toString()));
        Optional.ofNullable(spanUpdate.tags())
                .ifPresent(tags -> statement.bind("tags", tags.toArray(String[]::new)));
        Optional.ofNullable(spanUpdate.usage())
                .ifPresent(usage -> {
                    // Need to convert the map to two arrays to bind to the statement
                    var usageKeys = new ArrayList<String>();
                    var usageValues = new ArrayList<Integer>();
                    for (var entry : usage.entrySet()) {
                        usageKeys.add(entry.getKey());
                        usageValues.add(entry.getValue());
                    }
                    statement.bind("usageKeys", usageKeys.toArray(String[]::new));
                    statement.bind("usageValues", usageValues.toArray(Integer[]::new));
                });
        Optional.ofNullable(spanUpdate.endTime())
                .ifPresent(endTime -> statement.bind("end_time", endTime.toString()));
        Optional.ofNullable(spanUpdate.metadata())
                .ifPresent(metadata -> statement.bind("metadata", metadata.toString()));
        if (StringUtils.isNotBlank(spanUpdate.model())) {
            statement.bind("model", spanUpdate.model());
        }
        if (StringUtils.isNotBlank(spanUpdate.provider())) {
            statement.bind("provider", spanUpdate.provider());
        }
        Optional.ofNullable(spanUpdate.errorInfo())
                .ifPresent(errorInfo -> statement.bind("error_info", JsonUtils.readTree(errorInfo).toString()));

        if (spanUpdate.totalEstimatedCost() != null) {
            // Update with new manually set cost
            statement.bind("total_estimated_cost", spanUpdate.totalEstimatedCost().toString());
            statement.bind("total_estimated_cost_version", "");
        } else if (!isManualCostExist && isUpdateCostRecalculationAvailable(spanUpdate)) {
            // Calculate estimated cost only in case Span doesn't have manually set cost
            BigDecimal estimatedCost = CostService.calculateCost(spanUpdate.model(), spanUpdate.provider(),
                    spanUpdate.usage(), spanUpdate.metadata());
            statement.bind("total_estimated_cost", estimatedCost.toString());
            statement.bind("total_estimated_cost_version",
                    estimatedCost.compareTo(BigDecimal.ZERO) > 0 ? ESTIMATED_COST_VERSION : "");
        }
    }

    private ST newUpdateTemplate(SpanUpdate spanUpdate, String sql, boolean isManualCostExist) {
        var template = new ST(sql);

        if (StringUtils.isNotBlank(spanUpdate.name())) {
            template.add("name", spanUpdate.name());
        }

        Optional.ofNullable(spanUpdate.input())
                .ifPresent(input -> template.add("input", input.toString()));
        Optional.ofNullable(spanUpdate.output())
                .ifPresent(output -> template.add("output", output.toString()));
        Optional.ofNullable(spanUpdate.tags())
                .ifPresent(tags -> template.add("tags", tags.toString()));
        Optional.ofNullable(spanUpdate.metadata())
                .ifPresent(metadata -> template.add("metadata", metadata.toString()));
        if (StringUtils.isNotBlank(spanUpdate.model())) {
            template.add("model", spanUpdate.model());
        }
        if (StringUtils.isNotBlank(spanUpdate.provider())) {
            template.add("provider", spanUpdate.provider());
        }
        Optional.ofNullable(spanUpdate.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime.toString()));
        Optional.ofNullable(spanUpdate.usage())
                .ifPresent(usage -> template.add("usage", usage.toString()));
        Optional.ofNullable(spanUpdate.errorInfo())
                .ifPresent(errorInfo -> template.add("error_info", JsonUtils.readTree(errorInfo).toString()));

        // If we have manual cost in update OR if we can calculate it and user didn't set manual cost before
        boolean shouldRecalculateEstimatedCost = !isManualCostExist && isUpdateCostRecalculationAvailable(spanUpdate);
        if (spanUpdate.totalEstimatedCost() != null || shouldRecalculateEstimatedCost) {
            template.add("total_estimated_cost", "total_estimated_cost");
            template.add("total_estimated_cost_version", "total_estimated_cost_version");
        }
        return template;
    }

    @WithSpan
    public Mono<Span> getById(@NonNull UUID id) {
        log.info("Getting span by id '{}'", id);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> getById(id, connection))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    private Publisher<? extends Result> getById(UUID id, Connection connection) {
        var statement = connection.createStatement(SELECT_BY_ID)
                .bind("id", id);

        Segment segment = startSegment("spans", "Clickhouse", "get_by_id");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    @WithSpan
    public Mono<Span> getPartialById(@NonNull UUID id) {
        log.info("Getting partial span by id '{}'", id);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> getPartialById(id, connection))
                .flatMap(this::mapToPartialDto)
                .singleOrEmpty();
    }

    private Publisher<? extends Result> getPartialById(UUID id, Connection connection) {
        var statement = connection.createStatement(SELECT_PARTIAL_BY_ID).bind("id", id);
        var segment = startSegment("spans", "Clickhouse", "get_partial_by_id");
        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    @WithSpan
    public Mono<Long> deleteByTraceIds(Set<UUID> traceIds) {
        Preconditions.checkArgument(
                CollectionUtils.isNotEmpty(traceIds), "Argument 'traceIds' must not be empty");
        log.info("Deleting spans by traceIds, count '{}'", traceIds.size());
        var segment = startSegment("spans", "Clickhouse", "delete_by_trace_id");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(DELETE_BY_TRACE_IDS)
                            .bind("trace_ids", traceIds);

                    return makeMonoContextAware(bindWorkspaceIdToMono(statement));
                })
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum)
                .doFinally(signalType -> endSegment(segment));
    }

    private Publisher<Span> mapToDto(Result result) {
        return mapToDto(result, Set.of());
    }

    private <T> T getValue(Set<SpanField> exclude, SpanField field, Row row, String fieldName, Class<T> clazz) {
        return exclude.contains(field) ? null : row.get(fieldName, clazz);
    }

    private Publisher<Span> mapToDto(Result result, Set<SpanField> exclude) {

        return result.map((row, rowMetadata) -> Span.builder()
                .id(row.get("id", UUID.class))
                .projectId(row.get("project_id", UUID.class))
                .traceId(row.get("trace_id", UUID.class))
                .parentSpanId(Optional.ofNullable(row.get("parent_span_id", String.class))
                        .filter(str -> !str.isBlank())
                        .map(UUID::fromString)
                        .orElse(null))
                .name(StringUtils.defaultIfBlank(getValue(exclude, SpanField.NAME, row, "name", String.class), null))
                .type(Optional.ofNullable(
                        getValue(exclude, SpanField.TYPE, row, "type", String.class))
                        .map(SpanType::fromString)
                        .orElse(null))
                .startTime(getValue(exclude, SpanField.START_TIME, row, "start_time", Instant.class))
                .endTime(getValue(exclude, SpanField.END_TIME, row, "end_time", Instant.class))
                .input(Optional.ofNullable(getValue(exclude, SpanField.INPUT, row, "input", String.class))
                        .filter(str -> !str.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .output(Optional.ofNullable(getValue(exclude, SpanField.OUTPUT, row, "output", String.class))
                        .filter(str -> !str.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .metadata(Optional
                        .ofNullable(getValue(exclude, SpanField.METADATA, row, "metadata", String.class))
                        .filter(str -> !str.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .model(StringUtils.defaultIfBlank(getValue(exclude, SpanField.MODEL, row, "model", String.class), null))
                .provider(StringUtils.defaultIfBlank(
                        getValue(exclude, SpanField.PROVIDER, row, "provider", String.class), null))
                .totalEstimatedCost(
                        Optional.ofNullable(getValue(exclude, SpanField.TOTAL_ESTIMATED_COST, row,
                                "total_estimated_cost", BigDecimal.class))
                                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                                .orElse(null))
                .totalEstimatedCostVersion(getValue(exclude, SpanField.TOTAL_ESTIMATED_COST_VERSION, row,
                        "total_estimated_cost_version", String.class))
                .feedbackScores(Optional
                        .ofNullable(getValue(exclude, SpanField.FEEDBACK_SCORES, row, "feedback_scores_list",
                                List.class))
                        .filter(not(List::isEmpty))
                        .map(this::mapFeedbackScores)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .tags(Optional.ofNullable(getValue(exclude, SpanField.TAGS, row, "tags", String[].class))
                        .map(tags -> Arrays.stream(tags).collect(Collectors.toSet()))
                        .filter(set -> !set.isEmpty())
                        .orElse(null))
                .usage(getValue(exclude, SpanField.USAGE, row, "usage", Map.class))
                .comments(Optional
                        .ofNullable(getValue(exclude, SpanField.COMMENTS, row, "comments", List[].class))
                        .map(CommentResultMapper::getComments)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .errorInfo(Optional
                        .ofNullable(getValue(exclude, SpanField.ERROR_INFO, row, "error_info", String.class))
                        .filter(str -> !str.isBlank())
                        .map(errorInfo -> JsonUtils.readValue(errorInfo, ERROR_INFO_TYPE))
                        .orElse(null))
                .createdAt(getValue(exclude, SpanField.CREATED_AT, row, "created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(getValue(exclude, SpanField.CREATED_BY, row, "created_by", String.class))
                .lastUpdatedBy(
                        getValue(exclude, SpanField.LAST_UPDATED_BY, row, "last_updated_by", String.class))
                .duration(getValue(exclude, SpanField.DURATION, row, "duration", Double.class))
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

    private String getIfNotEmpty(Object value) {
        return Optional.ofNullable((String) value)
                .filter(StringUtils::isNotEmpty)
                .orElse(null);
    }

    private Publisher<Span> mapToPartialDto(Result result) {
        return result.map((row, rowMetadata) -> Span.builder()
                .type(SpanType.fromString(row.get("type", String.class)))
                .startTime(row.get("start_time", Instant.class))
                .build());
    }

    @WithSpan
    public Mono<SpanPage> find(int page, int size, @NonNull SpanSearchCriteria spanSearchCriteria) {
        log.info("Finding span by '{}'", spanSearchCriteria);
        return countTotal(spanSearchCriteria).flatMap(total -> find(page, size, spanSearchCriteria, total));
    }

    private Mono<SpanPage> find(int page, int size, SpanSearchCriteria spanSearchCriteria, Long total) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> find(page, size, spanSearchCriteria, connection))
                .flatMap(result -> mapToDto(result, spanSearchCriteria.exclude()))
                .collectList()
                .map(spans -> new SpanPage(page, spans.size(), total, spans, sortingFactory.getSortableFields()));
    }

    @WithSpan
    public Flux<Span> search(int limit, @NonNull SpanSearchCriteria criteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> findSpanStream(limit, criteria, connection))
                .flatMap(this::mapToDto)
                .buffer(limit > 100 ? limit / 2 : limit)
                .concatWith(Mono.just(List.of()))
                .filter(CollectionUtils::isNotEmpty)
                .flatMap(Flux::fromIterable);
    }

    private BigDecimal calculateCost(Span span) {
        // Later we could just use span.model(), but now it's still located inside metadata
        String model = StringUtils.isNotBlank(span.model())
                ? span.model()
                : Optional.ofNullable(span.metadata())
                        .map(metadata -> metadata.get("model"))
                        .map(JsonNode::asText).orElse("");

        return CostService.calculateCost(model, span.provider(), span.usage(), span.metadata());
    }

    private Flux<? extends Result> findSpanStream(int limit, SpanSearchCriteria criteria, Connection connection) {
        log.info("Searching spans by '{}'", criteria);
        var template = newFindTemplate(SELECT_BY_PROJECT_ID, criteria);

        template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

        template = template.add("stream", true);

        var statement = connection.createStatement(template.render())
                .bind("project_id", criteria.projectId())
                .bind("limit", limit);
        bindSearchCriteria(statement, criteria);

        Segment segment = startSegment("spans", "Clickhouse", "findSpanStream");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> {
                    log.info("Closing span search stream");
                    endSegment(segment);
                });
    }

    private Publisher<? extends Result> find(Integer page, int size, SpanSearchCriteria spanSearchCriteria,
            Connection connection) {

        var template = newFindTemplate(SELECT_BY_PROJECT_ID, spanSearchCriteria);
        template.add("offset", (page - 1) * size);

        template = ImageUtils.addTruncateToTemplate(template, spanSearchCriteria.truncate());

        bindTemplateExcludeFieldVariables(spanSearchCriteria, template);

        var finalTemplate = template;
        Optional.ofNullable(sortingQueryBuilder.toOrderBySql(spanSearchCriteria.sortingFields()))
                .ifPresent(sortFields -> {

                    if (sortFields.contains("feedback_scores")) {
                        finalTemplate.add("sort_has_feedback_scores", true);
                    }

                    finalTemplate.add("sort_fields", sortFields);
                });

        var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(spanSearchCriteria.sortingFields());

        var statement = connection.createStatement(template.render())
                .bind("project_id", spanSearchCriteria.projectId())
                .bind("limit", size)
                .bind("offset", (page - 1) * size);

        if (hasDynamicKeys) {
            statement = sortingQueryBuilder.bindDynamicKeys(statement, spanSearchCriteria.sortingFields());
        }

        bindSearchCriteria(statement, spanSearchCriteria);

        Segment segment = startSegment("spans", "Clickhouse", "stats");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private void bindTemplateExcludeFieldVariables(SpanSearchCriteria spanSearchCriteria, ST template) {
        Optional.ofNullable(spanSearchCriteria.exclude())
                .filter(Predicate.not(Set::isEmpty))
                .ifPresent(exclude -> {

                    // We need to keep the columns used for sorting in the select clause so that they are available when applying sorting.
                    Set<String> sortingFields = Optional.ofNullable(spanSearchCriteria.sortingFields())
                            .stream()
                            .flatMap(List::stream)
                            .map(SortingField::field)
                            .collect(Collectors.toSet());

                    Set<String> fields = exclude.stream()
                            .map(SpanField::getValue)
                            .filter(field -> !sortingFields.contains(field))
                            .collect(Collectors.toSet());

                    // check feedback_scores as well because it's a special case
                    if (fields.contains(SpanField.FEEDBACK_SCORES.getValue())
                            && sortingFields.stream().noneMatch(this::isFeedBackScoresField)) {

                        template.add("exclude_feedback_scores", true);
                    }

                    if (!fields.isEmpty()) {
                        template.add("exclude_fields", String.join(", ", fields));
                        template.add("exclude_input", fields.contains(SpanField.INPUT.getValue()));
                        template.add("exclude_output", fields.contains(SpanField.OUTPUT.getValue()));
                        template.add("exclude_metadata", fields.contains(SpanField.METADATA.getValue()));
                        template.add("exclude_comments", fields.contains(SpanField.COMMENTS.getValue()));
                    }
                });
    }

    private boolean isFeedBackScoresField(String field) {
        return field
                .startsWith(SortableFields.FEEDBACK_SCORES.substring(0, SortableFields.FEEDBACK_SCORES.length() - 1));
    }

    private Mono<Long> countTotal(SpanSearchCriteria spanSearchCriteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> countTotal(spanSearchCriteria, connection))
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> countTotal(SpanSearchCriteria spanSearchCriteria, Connection connection) {
        var template = newFindTemplate(COUNT_BY_PROJECT_ID, spanSearchCriteria);
        var statement = connection.createStatement(template.render())
                .bind("project_id", spanSearchCriteria.projectId());

        bindSearchCriteria(statement, spanSearchCriteria);

        Segment segment = startSegment("spans", "Clickhouse", "count_total");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private ST newFindTemplate(String query, SpanSearchCriteria spanSearchCriteria) {
        var template = new ST(query);
        Optional.ofNullable(spanSearchCriteria.traceId())
                .ifPresent(traceId -> template.add("trace_id", traceId));
        Optional.ofNullable(spanSearchCriteria.type())
                .ifPresent(type -> template.add("type", type.toString()));
        Optional.ofNullable(spanSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.SPAN)
                            .ifPresent(spanFilters -> template.add("filters", spanFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(scoresFilters -> template.add("feedback_scores_filters", scoresFilters));
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoreIsEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoreIsEmptyFilters));
                });
        Optional.ofNullable(spanSearchCriteria.lastReceivedSpanId())
                .ifPresent(lastReceivedSpanId -> template.add("last_received_span_id", lastReceivedSpanId));
        return template;
    }

    private void bindSearchCriteria(Statement statement, SpanSearchCriteria spanSearchCriteria) {
        Optional.ofNullable(spanSearchCriteria.traceId())
                .ifPresent(traceId -> statement.bind("trace_id", traceId));
        Optional.ofNullable(spanSearchCriteria.type())
                .ifPresent(type -> statement.bind("type", type.toString()));
        Optional.ofNullable(spanSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.SPAN);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                });
        Optional.ofNullable(spanSearchCriteria.lastReceivedSpanId())
                .ifPresent(lastReceivedSpanId -> statement.bind("last_received_span_id", lastReceivedSpanId));
    }

    @WithSpan
    public Mono<List<WorkspaceAndResourceId>> getSpanWorkspace(@NonNull Set<UUID> spanIds) {
        if (spanIds.isEmpty()) {
            return Mono.just(List.of());
        }

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {

                    var statement = connection.createStatement(SELECT_SPAN_ID_AND_WORKSPACE)
                            .bind("spanIds", spanIds.toArray(UUID[]::new));

                    return Mono.from(statement.execute());
                })
                .flatMapMany(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                        row.get("workspace_id", String.class),
                        row.get("id", UUID.class))))
                .collectList();
    }

    @WithSpan
    public Mono<UUID> getProjectIdFromSpan(@NonNull UUID spanId) {

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {

                    var statement = connection.createStatement(SELECT_PROJECT_ID_FROM_SPAN)
                            .bind("id", spanId);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("project_id", UUID.class)))
                .singleOrEmpty();
    }

    @WithSpan
    public Mono<ProjectStats> getStats(@NonNull SpanSearchCriteria searchCriteria) {

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = newFindTemplate(SELECT_SPANS_STATS, searchCriteria);

                    var statement = connection.createStatement(template.render())
                            .bind("project_id", searchCriteria.projectId());

                    bindSearchCriteria(statement, searchCriteria);

                    Segment segment = startSegment("spans", "Clickhouse", "stats");

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                            .doFinally(signalType -> endSegment(segment));
                })
                .flatMap(result -> result.map(((row, rowMetadata) -> StatsMapper.mapProjectStats(row, "span_count"))))
                .singleOrEmpty();
    }

    @WithSpan
    public Mono<Set<UUID>> getSpanIdsForTraces(@NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Mono.just(Set.of());
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(SELECT_SPAN_IDS_BY_TRACE_ID)
                            .bind("trace_ids", traceIds);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("id", UUID.class)))
                .collect(Collectors.toSet());
    }

    @WithSpan
    public Flux<SpansCountResponse.WorkspaceSpansCount> countSpansPerWorkspace() {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(SPAN_COUNT_BY_WORKSPACE_ID);
                    return Flux.from(statement.execute());
                })
                .flatMap(result -> result.map((row, rowMetadata) -> SpansCountResponse.WorkspaceSpansCount.builder()
                        .workspace(row.get("workspace_id", String.class))
                        .spanCount(row.get("span_count", Integer.class))
                        .build()));
    }

    private boolean isManualCost(Span span) {
        return span.totalEstimatedCost() != null && StringUtils.isBlank(span.totalEstimatedCostVersion());
    }

    private boolean isUpdateCostRecalculationAvailable(SpanUpdate spanUpdate) {
        return CostService.calculateCost(spanUpdate.model(), spanUpdate.provider(), spanUpdate.usage(),
                spanUpdate.metadata()).compareTo(BigDecimal.ZERO) > 0;
    }

    private void bindCost(Span span, Statement statement, String index) {
        if (span.totalEstimatedCost() != null) {
            // Cost is set manually by the user
            statement.bind("total_estimated_cost" + index, span.totalEstimatedCost().toString());
            statement.bind("total_estimated_cost_version" + index, "");
        } else {
            BigDecimal estimatedCost = calculateCost(span);
            statement.bind("total_estimated_cost" + index, estimatedCost.toString());
            statement.bind("total_estimated_cost_version" + index,
                    estimatedCost.compareTo(BigDecimal.ZERO) > 0 ? ESTIMATED_COST_VERSION : "");
        }
    }
}
