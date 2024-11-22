package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanSearchCriteria;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.domain.cost.ModelPrice;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
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
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.domain.FeedbackScoreDAO.EntityType;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

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
                        toDecimal64(:total_estimated_cost<item.index>, 8),
                        :total_estimated_cost_version<item.index>,
                        :tags<item.index>,
                        mapFromArrays(:usage_keys<item.index>, :usage_values<item.index>),
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
    //TODO: refactor to implement proper conflict resolution
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
                multiIf(
                    LENGTH(old_span.workspace_id) > 0 AND notEquals(old_span.workspace_id, new_span.workspace_id), CAST(leftPad(new_span.workspace_id, 40, '*') AS FixedString(19)),
                    LENGTH(old_span.workspace_id) > 0, old_span.workspace_id,
                    new_span.workspace_id
                ) as workspace_id,
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
                    CAST(:type, 'Enum8(\\'unknown\\' = 0 , \\'general\\' = 1, \\'tool\\' = 2, \\'llm\\' = 3)') as type,
                    parseDateTime64BestEffort(:start_time, 9) as start_time,
                    <if(end_time)> parseDateTime64BestEffort(:end_time, 9) as end_time, <else> null as end_time, <endif>
                    :input as input,
                    :output as output,
                    :metadata as metadata,
                    :model as model,
                    :provider as provider,
                    toDecimal64(:total_estimated_cost, 8) as total_estimated_cost,
                    :total_estimated_cost_version as total_estimated_cost_version,
                    :tags as tags,
                    mapFromArrays(:usage_keys, :usage_values) as usage,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by
            ) as new_span
            LEFT JOIN (
                SELECT
                    *
                FROM spans
                WHERE id = :id
                ORDER BY id DESC, last_updated_at DESC
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
            	created_at,
            	created_by,
            	last_updated_by
            ) SELECT
            	id,
            	project_id,
            	workspace_id,
            	trace_id,
            	parent_span_id,
            	name,
            	type,
            	start_time,
            	<if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> end_time <endif> as end_time,
            	<if(input)> :input <else> input <endif> as input,
            	<if(output)> :output <else> output <endif> as output,
            	<if(metadata)> :metadata <else> metadata <endif> as metadata,
            	<if(model)> :model <else> model <endif> as model,
            	<if(provider)> :provider <else> provider <endif> as provider,
            	<if(total_estimated_cost)> toDecimal64(:total_estimated_cost, 8) <else> total_estimated_cost <endif> as total_estimated_cost,
            	<if(total_estimated_cost_version)> :total_estimated_cost_version <else> total_estimated_cost_version <endif> as total_estimated_cost_version,
            	<if(tags)> :tags <else> tags <endif> as tags,
            	<if(usage)> CAST((:usageKeys, :usageValues), 'Map(String, Int64)') <else> usage <endif> as usage,
            	created_at,
            	created_by,
                :user_name as last_updated_by
            FROM spans
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY last_updated_at DESC
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
                start_time, end_time, input, output, metadata, model, provider, total_estimated_cost, total_estimated_cost_version, tags, usage, created_at,
                created_by, last_updated_by
            )
            SELECT
                new_span.id as id,
                multiIf(
                    LENGTH(CAST(old_span.project_id AS Nullable(String))) > 0 AND notEquals(old_span.project_id, new_span.project_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_span.project_id AS Nullable(String))) > 0, old_span.project_id,
                    new_span.project_id
                ) as project_id,
                multiIf(
                    LENGTH(old_span.workspace_id) > 0 AND notEquals(old_span.workspace_id, new_span.workspace_id), CAST(leftPad(new_span.workspace_id, 40, '*') AS FixedString(19)),
                    LENGTH(old_span.workspace_id) > 0, old_span.workspace_id,
                    new_span.workspace_id
                ) as workspace_id,
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
                    old_span.name
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
                    '' as name,
                    CAST('unknown', 'Enum8(\\'unknown\\' = 0 , \\'general\\' = 1, \\'tool\\' = 2, \\'llm\\' = 3)') as type,
                    toDateTime64('1970-01-01 00:00:00.000', 9) as start_time,
                    <if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> null <endif> as end_time,
                    <if(input)> :input <else> '' <endif> as input,
                    <if(output)> :output <else> '' <endif> as output,
                    <if(metadata)> :metadata <else> '' <endif> as metadata,
                    <if(model)> :model <else> '' <endif> as model,
                    <if(provider)> :provider <else> '' <endif> as provider,
                    <if(total_estimated_cost)> toDecimal64(:total_estimated_cost, 8) <else> toDecimal64(0, 8) <endif> as total_estimated_cost,
                    <if(total_estimated_cost_version)> :total_estimated_cost_version <else> '' <endif> as total_estimated_cost_version,
                    <if(tags)> :tags <else> [] <endif> as tags,
                    <if(usage)> CAST((:usageKeys, :usageValues), 'Map(String, Int64)') <else>  mapFromArrays([], []) <endif> as usage,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by
            ) as new_span
            LEFT JOIN (
                SELECT
                    *
                FROM spans
                WHERE id = :id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1
            ) as old_span
            ON new_span.id = old_span.id
            ;
            """;

    private static final String SELECT_BY_ID = """
            SELECT
            *
            FROM
            spans
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SELECT_BY_PROJECT_ID = """
            SELECT
                 id,
                 workspace_id,
                 project_id,
                 trace_id,
                 parent_span_id,
                 name,
                 type,
                 start_time,
                 end_time,
                 <if(truncate)> replaceRegexpAll(input, '<truncate>', '"[image]"') as input <else> input <endif>,
                 <if(truncate)> replaceRegexpAll(output, '<truncate>', '"[image]"') as output <else> output <endif>,
                 <if(truncate)> replaceRegexpAll(metadata, '<truncate>', '"[image]"') as metadata <else> metadata <endif>,
                 model,
                 provider,
                 total_estimated_cost,
                 tags,
                 usage,
                 created_at,
                 last_updated_at,
                 created_by,
                 last_updated_by
             FROM spans
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
                    ORDER BY entity_id DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                )
                GROUP BY entity_id
                HAVING <feedback_scores_filters>
             )
             <endif>
             ORDER BY id DESC, last_updated_at DESC
             LIMIT 1 BY id
             LIMIT :limit OFFSET :offset
            ;
            """;

    private static final String COUNT_BY_PROJECT_ID = """
            SELECT
                count(id) as count
            FROM
            (
               SELECT
                    id
                FROM spans
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
                    ORDER BY entity_id DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                )
                GROUP BY entity_id
                HAVING <feedback_scores_filters>
                )
                <endif>
                ORDER BY last_updated_at DESC
                LIMIT 1 BY id
            ) AS latest_rows
            ;
            """;

    private static final String DELETE_BY_TRACE_IDS = """
            DELETE FROM spans WHERE trace_id IN :trace_ids AND workspace_id = :workspace_id;
            """;

    private static final String SELECT_SPAN_ID_AND_WORKSPACE = """
            SELECT
                id, workspace_id
            FROM spans
            WHERE id IN :spanIds
            ORDER BY last_updated_at DESC
            LIMIT 1 BY id
            ;
            """;

    public static final String SELECT_PROJECT_ID_FROM_SPANS = """
            SELECT
                  id,
                  project_id
            FROM spans
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            """;

    private static final String ESTIMATED_COST_VERSION = "1.0";
    public static final BigDecimal ZERO_COST = new BigDecimal("0.00000000");

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull FeedbackScoreDAO feedbackScoreDAO;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

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

                BigDecimal estimatedCost = calculateCost(span);

                statement.bind("id" + i, span.id())
                        .bind("project_id" + i, span.projectId())
                        .bind("trace_id" + i, span.traceId())
                        .bind("name" + i, span.name())
                        .bind("type" + i, span.type().toString())
                        .bind("start_time" + i, span.startTime().toString())
                        .bind("parent_span_id" + i, span.parentSpanId() != null ? span.parentSpanId() : "")
                        .bind("input" + i, span.input() != null ? span.input().toString() : "")
                        .bind("output" + i, span.output() != null ? span.output().toString() : "")
                        .bind("metadata" + i, span.metadata() != null ? span.metadata().toString() : "")
                        .bind("model" + i, span.model() != null ? span.model() : "")
                        .bind("provider" + i, span.provider() != null ? span.provider() : "")
                        .bind("total_estimated_cost" + i, estimatedCost.toString())
                        .bind("total_estimated_cost_version" + i,
                                estimatedCost.compareTo(ZERO_COST) > 0 ? ESTIMATED_COST_VERSION : "")
                        .bind("tags" + i, span.tags() != null ? span.tags().toArray(String[]::new) : new String[]{})
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
                .bind("name", span.name())
                .bind("type", span.type().toString())
                .bind("start_time", span.startTime().toString());
        if (span.parentSpanId() != null) {
            statement.bind("parent_span_id", span.parentSpanId());
        } else {
            statement.bind("parent_span_id", "");
        }
        if (span.endTime() != null) {
            statement.bind("end_time", span.endTime().toString());
        }
        if (span.input() != null) {
            statement.bind("input", span.input().toString());
        } else {
            statement.bind("input", "");
        }
        if (span.output() != null) {
            statement.bind("output", span.output().toString());
        } else {
            statement.bind("output", "");
        }
        if (span.metadata() != null) {
            statement.bind("metadata", span.metadata().toString());
        } else {
            statement.bind("metadata", "");
        }
        if (span.model() != null) {
            statement.bind("model", span.model());
        } else {
            statement.bind("model", "");
        }
        if (span.provider() != null) {
            statement.bind("provider", span.provider());
        } else {
            statement.bind("provider", "");
        }

        BigDecimal estimatedCost = calculateCost(span);
        statement.bind("total_estimated_cost", estimatedCost.toString());
        if (estimatedCost.compareTo(ZERO_COST) > 0) {
            statement.bind("total_estimated_cost_version", ESTIMATED_COST_VERSION);
        } else {
            statement.bind("total_estimated_cost_version", "");
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
    public Mono<Long> update(@NonNull UUID id, @NonNull SpanUpdate spanUpdate) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> update(id, spanUpdate, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    @WithSpan
    public Mono<Long> partialInsert(@NonNull UUID id, @NonNull UUID projectId, @NonNull SpanUpdate spanUpdate) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = newUpdateTemplate(spanUpdate, PARTIAL_INSERT);

                    var statement = connection.createStatement(template.render());

                    statement.bind("id", id);
                    statement.bind("project_id", projectId);
                    statement.bind("trace_id", spanUpdate.traceId());

                    if (spanUpdate.parentSpanId() != null) {
                        statement.bind("parent_span_id", spanUpdate.parentSpanId());
                    } else {
                        statement.bind("parent_span_id", "");
                    }

                    bindUpdateParams(spanUpdate, statement);

                    Segment segment = startSegment("spans", "Clickhouse", "partial_insert");

                    return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                            .doFinally(signalType -> endSegment(segment));
                })
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> update(UUID id, SpanUpdate spanUpdate, Connection connection) {
        var template = newUpdateTemplate(spanUpdate, UPDATE);
        var statement = connection.createStatement(template.render());
        statement.bind("id", id);
        bindUpdateParams(spanUpdate, statement);

        Segment segment = startSegment("spans", "Clickhouse", "update");

        return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private void bindUpdateParams(SpanUpdate spanUpdate, Statement statement) {
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
        Optional.ofNullable(spanUpdate.model())
                .ifPresent(model -> statement.bind("model", model));
        Optional.ofNullable(spanUpdate.provider())
                .ifPresent(provider -> statement.bind("provider", provider));

        if (StringUtils.isNotBlank(spanUpdate.model()) && Objects.nonNull(spanUpdate.usage())) {
            statement.bind("total_estimated_cost",
                    ModelPrice.fromString(spanUpdate.model()).calculateCost(spanUpdate.usage()).toString());
            statement.bind("total_estimated_cost_version", ESTIMATED_COST_VERSION);
        }
    }

    private ST newUpdateTemplate(SpanUpdate spanUpdate, String sql) {
        var template = new ST(sql);
        Optional.ofNullable(spanUpdate.input())
                .ifPresent(input -> template.add("input", input.toString()));
        Optional.ofNullable(spanUpdate.output())
                .ifPresent(output -> template.add("output", output.toString()));
        Optional.ofNullable(spanUpdate.tags())
                .ifPresent(tags -> template.add("tags", tags.toString()));
        Optional.ofNullable(spanUpdate.metadata())
                .ifPresent(metadata -> template.add("metadata", metadata.toString()));
        Optional.ofNullable(spanUpdate.model())
                .ifPresent(model -> template.add("model", model));
        Optional.ofNullable(spanUpdate.provider())
                .ifPresent(provider -> template.add("provider", provider));
        Optional.ofNullable(spanUpdate.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime.toString()));
        Optional.ofNullable(spanUpdate.usage())
                .ifPresent(usage -> template.add("usage", usage.toString()));
        if (StringUtils.isNotBlank(spanUpdate.model()) && Objects.nonNull(spanUpdate.usage())) {
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
                .flatMap(span -> enhanceWithFeedbackScores(List.of(span)).map(List::getFirst))
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
    public Mono<Void> deleteByTraceId(@NonNull UUID traceId, @NonNull Connection connection) {
        return deleteByTraceIds(Set.of(traceId), connection);
    }

    @WithSpan
    public Mono<Void> deleteByTraceIds(Set<UUID> traceIds, @NonNull Connection connection) {
        Preconditions.checkArgument(
                CollectionUtils.isNotEmpty(traceIds), "Argument 'traceIds' must not be empty");
        log.info("Deleting spans by traceIds, count '{}'", traceIds.size());
        var statement = connection.createStatement(DELETE_BY_TRACE_IDS)
                .bind("trace_ids", traceIds);
        var segment = startSegment("spans", "Clickhouse", "delete_by_trace_id");
        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment))
                .then();
    }

    private Publisher<Span> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> {
            var parentSpanId = row.get("parent_span_id", String.class);
            return Span.builder()
                    .id(row.get("id", UUID.class))
                    .projectId(row.get("project_id", UUID.class))
                    .traceId(row.get("trace_id", UUID.class))
                    .parentSpanId(Optional.ofNullable(parentSpanId)
                            .filter(str -> !str.isBlank())
                            .map(UUID::fromString)
                            .orElse(null))
                    .name(row.get("name", String.class))
                    .type(SpanType.fromString(row.get("type", String.class)))
                    .startTime(row.get("start_time", Instant.class))
                    .endTime(row.get("end_time", Instant.class))
                    .input(Optional.ofNullable(row.get("input", String.class))
                            .filter(str -> !str.isBlank())
                            .map(JsonUtils::getJsonNodeFromString)
                            .orElse(null))
                    .output(Optional.ofNullable(row.get("output", String.class))
                            .filter(str -> !str.isBlank())
                            .map(JsonUtils::getJsonNodeFromString)
                            .orElse(null))
                    .metadata(Optional.ofNullable(row.get("metadata", String.class))
                            .filter(str -> !str.isBlank())
                            .map(JsonUtils::getJsonNodeFromString)
                            .orElse(null))
                    .model(row.get("model", String.class))
                    .provider(row.get("provider", String.class))
                    .totalEstimatedCost(row.get("total_estimated_cost", BigDecimal.class).equals(ZERO_COST)
                            ? null
                            : row.get("total_estimated_cost", BigDecimal.class))
                    .tags(Optional.of(Arrays.stream(row.get("tags", String[].class)).collect(Collectors.toSet()))
                            .filter(set -> !set.isEmpty())
                            .orElse(null))
                    .usage(row.get("usage", Map.class))
                    .createdAt(row.get("created_at", Instant.class))
                    .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                    .createdBy(row.get("created_by", String.class))
                    .lastUpdatedBy(row.get("last_updated_by", String.class))
                    .build();
        });
    }

    @WithSpan
    public Mono<Span.SpanPage> find(int page, int size, @NonNull SpanSearchCriteria spanSearchCriteria) {
        log.info("Finding span by '{}'", spanSearchCriteria);
        return countTotal(spanSearchCriteria).flatMap(total -> find(page, size, spanSearchCriteria, total));
    }

    private Mono<Span.SpanPage> find(int page, int size, SpanSearchCriteria spanSearchCriteria, Long total) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> find(page, size, spanSearchCriteria, connection))
                .flatMap(this::mapToDto)
                .collectList()
                .flatMap(this::enhanceWithFeedbackScores)
                .map(spans -> new Span.SpanPage(page, spans.size(), total, spans));
    }

    private Mono<List<Span>> enhanceWithFeedbackScores(List<Span> spans) {
        List<UUID> spanIds = spans.stream().map(Span::id).toList();

        Segment segment = startSegment("spans", "Clickhouse", "enhance_with_feedback_scores");

        return feedbackScoreDAO.getScores(EntityType.SPAN, spanIds)
                .map(scoresMap -> spans.stream()
                        .map(span -> span.toBuilder().feedbackScores(scoresMap.get(span.id())).build())
                        .toList())
                .doFinally(signalType -> endSegment(segment));
    }

    private BigDecimal calculateCost(Span span) {
        // Later we could just use span.model(), but now it's still located inside metadata
        String model = StringUtils.isNotBlank(span.model())
                ? span.model()
                : Optional.ofNullable(span.metadata())
                        .map(metadata -> metadata.get("model"))
                        .map(JsonNode::asText).orElse("");

        return ModelPrice.fromString(model).calculateCost(span.usage());
    }

    private Publisher<? extends Result> find(int page, int size, SpanSearchCriteria spanSearchCriteria,
            Connection connection) {

        var template = newFindTemplate(SELECT_BY_PROJECT_ID, spanSearchCriteria);
        template = ImageUtils.addTruncateToTemplate(template, spanSearchCriteria.truncate());
        var statement = connection.createStatement(template.render())
                .bind("project_id", spanSearchCriteria.projectId())
                .bind("limit", size)
                .bind("offset", (page - 1) * size);

        bindSearchCriteria(statement, spanSearchCriteria);

        Segment segment = startSegment("spans", "Clickhouse", "find");

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .doFinally(signalType -> endSegment(segment));
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
                });
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
                });
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
    public Mono<Map<UUID, UUID>> getProjectIdFromSpans(@NonNull Set<UUID> spanIds) {

        if (spanIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {

                    var statement = connection.createStatement(SELECT_PROJECT_ID_FROM_SPANS)
                            .bind("ids", spanIds.toArray(UUID[]::new));

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> Map.entry(
                        row.get("id", UUID.class),
                        row.get("project_id", UUID.class))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
