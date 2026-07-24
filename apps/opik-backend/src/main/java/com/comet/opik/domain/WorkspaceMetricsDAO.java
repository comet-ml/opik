package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.metrics.BreakdownField;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.metrics.WorkspaceSpanMetricRequest;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.comet.opik.utils.SentinelTranslation;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.metrics.BreakdownQueryBuilder.getBreakdownGroupExpression;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(WorkspaceMetricsDAOImpl.class)
public interface WorkspaceMetricsDAO {

    Set<MetricType> SUPPORTED_SPAN_METRICS = EnumSet.of(MetricType.SPAN_TOKEN_USAGE);

    @Deprecated
    Mono<List<WorkspaceMetricsSummaryResponse.Result>> getFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);

    @Deprecated
    Mono<List<WorkspaceMetricResponse.Result>> getFeedbackScoresDaily(WorkspaceMetricRequest request);

    Mono<WorkspaceMetricsSummaryResponse.Result> getCostsSummary(WorkspaceMetricsSummaryRequest request);

    Mono<List<WorkspaceMetricResponse.Result>> getCostsDaily(WorkspaceMetricRequest request);

    Mono<List<WorkspaceMetricResponse.Result>> getSpanTokenUsage(WorkspaceSpanMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspaceMetricsDAOImpl implements WorkspaceMetricsDAO {

    private static final String GET_FEEDBACK_SCORES_SUMMARY = """
            SELECT
                AVGIf(fs.value, t.id >= :id_start AND t.id \\<= :id_end) AS current,
                AVGIf(fs.value, t.id >= :id_prior_start AND t.id \\< :id_start) AS previous,
                fs.name
            FROM feedback_scores fs final
            JOIN (
                SELECT
                    id
                FROM traces final
                WHERE workspace_id = :workspace_id
                  <if(project_ids)> AND project_id IN :project_ids <endif>
                  AND id BETWEEN :id_prior_start AND :id_end
                  AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_prior_start), 'UTC'))
                  AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                  AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_prior_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            ) t ON t.id = fs.entity_id
            WHERE workspace_id = :workspace_id
                <if(project_ids)> AND project_id IN :project_ids <endif>
                AND entity_type = 'trace'
            GROUP BY fs.name;
            """;

    private static final String GET_COSTS_SUMMARY = """
            SELECT
                SUMIf(total_estimated_cost, id >= :id_start AND id \\<= :id_end) AS current,
                SUMIf(total_estimated_cost, id >= :id_prior_start AND id \\< :id_start) AS previous,
                'cost' AS name
            FROM spans final
            WHERE workspace_id = :workspace_id
                <if(project_ids)> AND project_id IN :project_ids <endif>
                AND id BETWEEN :id_prior_start AND :id_end
                AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_prior_start), 'UTC'))
                AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_prior_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9);
            """;

    private static final String GET_FEEDBACK_SCORES_DAILY_BY_PROJECT = """
            WITH feedback_scores_daily AS (
                SELECT fs.project_id AS project_id,
                       toStartOfInterval(t.start_time, toIntervalDay(1)) AS bucket,
                       if(COUNT(1) = 0, NULL, avg(fs.value)) AS value
                FROM feedback_scores fs final
                JOIN (
                    SELECT
                        id,
                        start_time
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                      AND project_id IN :project_ids
                      AND id BETWEEN :id_start AND :id_end
                      AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_start), 'UTC'))
                      AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                      AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                ) t ON t.id = fs.entity_id
                WHERE workspace_id = :workspace_id
                  AND project_id IN :project_ids
                  AND entity_type = 'trace'
                  AND name = :name
                GROUP BY fs.project_id, bucket
                ORDER BY fs.project_id, bucket
                WITH FILL
                FROM toStartOfInterval(parseDateTimeBestEffort(:timestamp_start), toIntervalDay(1))
                    TO parseDateTimeBestEffort(:timestamp_end)
                    STEP toIntervalDay(1)
            )
            SELECT
                project_id,
                :name AS name,
                groupArray(tuple(bucket, value)) AS data
            FROM feedback_scores_daily
            GROUP BY project_id
            ;
            """;

    private static final String GET_FEEDBACK_SCORES_DAILY = """
            WITH feedback_scores_daily AS (
                SELECT toStartOfInterval(t.start_time, toIntervalDay(1)) AS bucket,
                       if(COUNT(1) = 0, NULL, avg(fs.value)) AS value
                FROM feedback_scores fs final
                JOIN (
                    SELECT
                        id,
                        start_time
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                      AND id BETWEEN :id_start AND :id_end
                      AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_start), 'UTC'))
                      AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                      AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                ) t ON t.id = fs.entity_id
                WHERE workspace_id = :workspace_id
                  AND entity_type = 'trace'
                  AND name = :name
                GROUP BY bucket
                ORDER BY bucket
                WITH FILL
                FROM toStartOfInterval(parseDateTimeBestEffort(:timestamp_start), toIntervalDay(1))
                    TO parseDateTimeBestEffort(:timestamp_end)
                    STEP toIntervalDay(1)
            )
            SELECT
                NULL AS project_id,
                :name AS name,
                groupArray(tuple(bucket, value)) AS data
            FROM feedback_scores_daily
            ;
            """;

    private static final String GET_COSTS_DAILY_BY_PROJECT = """
            WITH costs_daily AS (
                SELECT toStartOfInterval(start_time, toIntervalDay(1)) AS bucket,
                       if(COUNT(1) = 0, NULL, sum(total_estimated_cost)) AS value,
                       project_id
                FROM spans final
                WHERE workspace_id = :workspace_id
                  AND project_id IN :project_ids
                  AND id BETWEEN :id_start AND :id_end
                  AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_start), 'UTC'))
                  AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                  AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                GROUP BY project_id, bucket
                ORDER BY project_id, bucket
                WITH FILL
                FROM toStartOfInterval(parseDateTimeBestEffort(:timestamp_start), toIntervalDay(1))
                    TO parseDateTimeBestEffort(:timestamp_end)
                    STEP toIntervalDay(1)
            )
            SELECT
                project_id,
                :name AS name,
                groupArray(tuple(bucket, value)) AS data
            FROM costs_daily
            GROUP BY project_id
            ;
            """;

    private static final String GET_COSTS_DAILY = """
            WITH costs_daily AS (
                SELECT toStartOfInterval(start_time, toIntervalDay(1)) AS bucket,
                       if(COUNT(1) = 0, NULL, sum(total_estimated_cost)) AS value
                FROM spans final
                WHERE workspace_id = :workspace_id
                  AND id BETWEEN :id_start AND :id_end
                  AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_start), 'UTC'))
                  AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                  AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                GROUP BY bucket
                ORDER BY bucket
                WITH FILL
                FROM toStartOfInterval(parseDateTimeBestEffort(:timestamp_start), toIntervalDay(1))
                    TO parseDateTimeBestEffort(:timestamp_end)
                    STEP toIntervalDay(1)
            )
            SELECT
                NULL AS project_id,
                :name AS name,
                groupArray(tuple(bucket, value)) AS data
            FROM costs_daily
            ;
            """;

    // Shared with ProjectMetricsDAO via SpanMetricsQueries. Workspace aggregation queries an explicit set of
    // projects: WorkspaceMetricsService resolves the "all projects" request into every project id up front, so the
    // predicate is always a bounded `project_id IN :project_ids` list that prunes on the spans primary key
    // (workspace_id, project_id, ...) — never an unconstrained workspace-wide scan.
    private static final String SPAN_FILTERED_PREFIX = SpanMetricsQueries
            .spanFilteredPrefix("project_id IN :project_ids");

    // Span filtering is reused from ProjectMetricsDAO's SPAN_FILTERED_PREFIX (above), but the output is shaped in the
    // workspace-native style like GET_COSTS_DAILY: each row is a finished series {project_id, name, data}, where data is
    // a groupArray(tuple(bucket, value)). No breakdown => one series per usage key; with a provider/model breakdown =>
    // one series per group, mirroring how GET_COSTS_DAILY_BY_PROJECT groups by project.
    private static final String GET_SPAN_TOKEN_USAGE = """
            %s, spans_usage AS (
                SELECT span_time,
                       name,
                       value
                FROM spans_filtered s
                ARRAY JOIN mapKeys(usage) AS name, mapValues(usage) AS value
                WHERE value > 0
            )
            , series AS (
                SELECT <bucket> AS bucket,
                       name,
                       sum(value) AS value
                FROM spans_usage
                GROUP BY name, bucket
                ORDER BY name, bucket
                <if(with_fill)>WITH FILL
                    FROM <fill_from>
                    TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                    STEP <step><endif>
            )
            SELECT NULL AS project_id,
                   name,
                   groupArray(tuple(bucket, value)) AS data
            FROM series
            GROUP BY name
            SETTINGS log_comment = '<log_comment>';
            """.formatted(SPAN_FILTERED_PREFIX);

    private static final String GET_SPAN_TOKEN_USAGE_WITH_BREAKDOWN = """
            %s, spans_usage AS (
                SELECT span_time,
                       <group_expression> AS group_name,
                       value
                FROM spans_filtered s
                ARRAY JOIN mapKeys(usage) AS name, mapValues(usage) AS value
                WHERE value > 0
                AND name = :sub_metric
            )
            , series AS (
                SELECT <bucket> AS bucket,
                       group_name,
                       sum(value) AS value
                FROM spans_usage
                GROUP BY group_name, bucket
                ORDER BY group_name, bucket
            )
            SELECT NULL AS project_id,
                   group_name AS name,
                   groupArray(tuple(bucket, value)) AS data
            FROM series
            GROUP BY group_name
            SETTINGS log_comment = '<log_comment>';
            """.formatted(SPAN_FILTERED_PREFIX);

    private static final String WORKSPACE_METRIC_QUERY_NAME_PREFIX = "WorkspaceMetrics_";

    private static final Map<TimeInterval, String> INTERVAL_TO_SQL = Map.of(
            TimeInterval.WEEKLY, "toIntervalWeek(1)",
            TimeInterval.DAILY, "toIntervalDay(1)",
            TimeInterval.HOURLY, "toIntervalHour(1)");

    // Span-filter strategies with the SPAN_FILTERED_PREFIX template placeholder each renders into. Drives both the
    // template `add` pass and the statement `bind` pass so a strategy is declared once, not on two sides that can drift.
    private static final Map<FilterStrategy, String> SPAN_FILTER_TEMPLATE_PLACEHOLDERS = Map.of(
            FilterStrategy.SPAN, "span_filters",
            FilterStrategy.SPAN_FEEDBACK_SCORES, "span_feedback_scores_filters",
            FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY, "feedback_scores_empty_filters");

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;
    private final @NonNull OpikConfiguration configuration;

    private boolean spanColumnsNonNullable() {
        return configuration.getDatabaseAnalyticsDataModel().spanColumnsNonNullable();
    }

    @Override
    public Mono<List<WorkspaceMetricsSummaryResponse.Result>> getFeedbackScoresSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return getMetricsSummary(request, GET_FEEDBACK_SCORES_SUMMARY);
    }

    @Override
    public Mono<List<WorkspaceMetricResponse.Result>> getFeedbackScoresDaily(@NonNull WorkspaceMetricRequest request) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.name()),
                "For metrics request, name must be provided");
        var query = CollectionUtils
                .isEmpty(request.projectIds())
                        ? GET_FEEDBACK_SCORES_DAILY
                        : GET_FEEDBACK_SCORES_DAILY_BY_PROJECT;
        return getMetricsDaily(request, query);
    }

    @Override
    public Mono<WorkspaceMetricsSummaryResponse.Result> getCostsSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return getMetricsSummary(request, GET_COSTS_SUMMARY)
                .map(List::getFirst);
    }

    @Override
    public Mono<List<WorkspaceMetricResponse.Result>> getCostsDaily(@NonNull WorkspaceMetricRequest request) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.name()),
                "For metrics request, name must be provided");
        var query = CollectionUtils
                .isEmpty(request.projectIds())
                        ? GET_COSTS_DAILY
                        : GET_COSTS_DAILY_BY_PROJECT;
        return getMetricsDaily(request, query);
    }

    @Override
    public Mono<List<WorkspaceMetricResponse.Result>> getSpanTokenUsage(@NonNull WorkspaceSpanMetricRequest request) {
        return template.nonTransaction(connection -> getSpanMetric(request, connection,
                request.hasBreakdown() ? GET_SPAN_TOKEN_USAGE_WITH_BREAKDOWN : GET_SPAN_TOKEN_USAGE,
                "workspaceSpanTokenUsage")
                .flatMapMany(this::rowToDataPoint)
                .collectList());
    }

    private Mono<? extends Result> getSpanMetric(WorkspaceSpanMetricRequest request, Connection connection,
            String query, String segmentName) {
        // The service resolves "all projects" into the explicit project set before calling the DAO, so the query is
        // always bounded by project_id IN (...) and prunes on the spans primary key rather than scanning the workspace.
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(request.projectIds()),
                "projectIds must be resolved before querying workspace span metrics");
        return makeMonoContextAware((userName, workspaceId) -> {
            var interval = request.interval();
            var isTotal = interval == TimeInterval.TOTAL;

            var stTemplate = getSTWithLogComment(query, WORKSPACE_METRIC_QUERY_NAME_PREFIX + segmentName, workspaceId,
                    userName, request.projectIds().size());

            if (isTotal) {
                stTemplate.add("bucket", "toDateTime(UUIDv7ToDateTime(toUUID(:uuid_from_time)))");
            } else {
                stTemplate.add("step", intervalToSql(interval))
                        .add("bucket", wrapWeekly(interval,
                                "toStartOfInterval(span_time, %s)".formatted(intervalToSql(interval))))
                        .add("fill_from", wrapWeekly(interval,
                                "toStartOfInterval(UUIDv7ToDateTime(toUUID(:uuid_from_time)), %s)"
                                        .formatted(intervalToSql(interval))));
            }

            if (request.hasBreakdown()) {
                stTemplate.add("group_expression",
                        getBreakdownGroupExpression(request.metricType(), request.breakdown()));
            }

            Optional.ofNullable(request.filters())
                    .ifPresent(filters -> SPAN_FILTER_TEMPLATE_PLACEHOLDERS
                            .forEach((strategy, placeholder) -> FilterQueryBuilder
                                    .toAnalyticsDbFilters(filters, strategy,
                                            strategy == FilterStrategy.SPAN && spanColumnsNonNullable())
                                    .ifPresent(rendered -> stTemplate.add(placeholder, rendered))));

            stTemplate.add("uuid_from_time", true);
            stTemplate.add("uuid_to_time", true);
            if (!isTotal) {
                stTemplate.add("with_fill", true);
            }

            var intervalEnd = request.intervalEnd() != null ? request.intervalEnd() : Instant.now();
            var statement = connection.createStatement(stTemplate.render())
                    .bind("uuid_from_time", instantToUUIDMapper.toLowerBound(request.intervalStart()).toString())
                    .bind("uuid_to_time", instantToUUIDMapper.toUpperBound(intervalEnd).toString())
                    .bind("workspace_id", workspaceId)
                    .bind("project_ids", request.projectIds().toArray(new UUID[0]));

            if (request.hasBreakdown() && request.breakdown().field() == BreakdownField.METADATA) {
                statement.bind("metadata_key", request.breakdown().metadataKey());
            }

            // SPAN_TOKEN_USAGE breakdown sums a single token-usage entry, selected by its name (sub_metric)
            if (request.hasBreakdown() && request.metricType() == MetricType.SPAN_TOKEN_USAGE) {
                statement.bind("sub_metric", Optional.ofNullable(request.breakdown().subMetric()).orElse(""));
            }

            Optional.ofNullable(request.filters())
                    .ifPresent(filters -> SPAN_FILTER_TEMPLATE_PLACEHOLDERS.keySet()
                            .forEach(strategy -> FilterQueryBuilder.bind(statement, filters, strategy)));

            InstrumentAsyncUtils.Segment segment = startSegment(segmentName, "Clickhouse", "get");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
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

    private Mono<List<WorkspaceMetricResponse.Result>> getMetricsDaily(WorkspaceMetricRequest request,
            String query) {
        return template.nonTransaction(connection -> getMetricsDaily(connection, request, query)
                .flatMapMany(this::rowToDataPoint)
                .collectList());
    }

    private Mono<? extends Result> getMetricsDaily(Connection connection, WorkspaceMetricRequest request,
            String query) {

        var statement = connection.createStatement(query)
                .bind("timestamp_start", request.intervalStart().toString())
                .bind("timestamp_end", request.intervalEnd().toString())
                .bind("id_start",
                        idGenerator.getTimeOrderedEpoch(request.intervalStart().toEpochMilli()))
                .bind("id_end", idGenerator.getTimeOrderedEpoch(request.intervalEnd().toEpochMilli()))
                .bind("name", request.name());

        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            statement.bind("project_ids", request.projectIds());
        }

        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
    }

    private Mono<List<WorkspaceMetricsSummaryResponse.Result>> getMetricsSummary(
            WorkspaceMetricsSummaryRequest request,
            String query) {
        return template.nonTransaction(connection -> getMetricsSummary(connection, request, query)
                .flatMapMany(result -> result.map((row, rowMetadata) -> WorkspaceMetricsSummaryResponse.Result.builder()
                        .name(row.get("name", String.class))
                        .current(filterNan(row.get("current", Double.class)))
                        .previous(filterNan(row.get("previous", Double.class)))
                        .build()))
                .filter(result -> result.current() != null)
                .collectList());
    }

    private Mono<? extends Result> getMetricsSummary(Connection connection,
            WorkspaceMetricsSummaryRequest request,
            String query) {
        var template = TemplateUtils.newST(query);

        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            template.add("project_ids", request.projectIds());
        }

        var statement = connection.createStatement(template.render())
                .bind("timestamp_prior_start", getPriorStart(request.intervalStart(), request.intervalEnd()).toString())
                .bind("timestamp_end", request.intervalEnd().toString())
                .bind("id_start",
                        idGenerator.getTimeOrderedEpoch(request.intervalStart().toEpochMilli()))
                .bind("id_end", idGenerator.getTimeOrderedEpoch(request.intervalEnd().toEpochMilli()))
                .bind("id_prior_start",
                        idGenerator.getTimeOrderedEpoch(
                                getPriorStart(request.intervalStart(), request.intervalEnd()).toEpochMilli()));

        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            statement.bind("project_ids", request.projectIds());
        }

        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
    }

    private Instant getPriorStart(Instant intervalStart, Instant intervalEnd) {
        // Calculate duration between the two timestamps
        Duration duration = Duration.between(intervalStart, intervalEnd);

        // Subtract the duration from intervalStart to get prior start timestamp
        return intervalStart.minus(duration);
    }

    private Publisher<WorkspaceMetricResponse.Result> rowToDataPoint(Result result) {
        return result.map(((row, rowMetadata) -> WorkspaceMetricResponse.Result.builder()
                .projectId(row.get("project_id", UUID.class))
                .name(row.get("name", String.class))
                .data(getDailyData(row.get("data", List[].class)))
                .build()));
    }

    private List<DataPoint<Double>> getDailyData(List[] dataArray) {
        if (ArrayUtils.isEmpty(dataArray)) {
            return null;
        }

        var dataItems = Arrays.stream(dataArray)
                .filter(CollectionUtils::isNotEmpty)
                .map(dataItem -> DataPoint.<Double>builder()
                        .time(toInstant(dataItem.get(0)))
                        .value(Optional.ofNullable(dataItem.get(1)).map(Object::toString)
                                .map(Double::parseDouble)
                                .orElse(null))
                        .build())
                .toList();

        return dataItems.isEmpty() ? null : dataItems;
    }

    // Bucket timestamps come back as OffsetDateTime for DateTime64 columns (e.g. the cost query's start_time) but as
    // LocalDateTime for plain DateTime expressions (e.g. UUIDv7ToDateTime-derived span_time). Both represent UTC.
    private Instant toInstant(Object bucket) {
        return switch (bucket) {
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case LocalDateTime localDateTime -> localDateTime.toInstant(ZoneOffset.UTC);
            default -> throw new IllegalStateException(
                    "Unexpected bucket time type: " + bucket.getClass().getName());
        };
    }

    Double filterNan(Double value) {
        return SentinelTranslation.nanToNull(value);
    }
}
