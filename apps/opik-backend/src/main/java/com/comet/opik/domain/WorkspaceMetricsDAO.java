package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
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
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(WorkspaceMetricsDAOImpl.class)
public interface WorkspaceMetricsDAO {

    @Deprecated
    Mono<List<WorkspaceMetricsSummaryResponse.Result>> getFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);

    @Deprecated
    Mono<List<WorkspaceMetricResponse.Result>> getFeedbackScoresDaily(WorkspaceMetricRequest request);

    Mono<WorkspaceMetricsSummaryResponse.Result> getCostsSummary(WorkspaceMetricsSummaryRequest request);

    Mono<List<WorkspaceMetricResponse.Result>> getCostsDaily(WorkspaceMetricRequest request);
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

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull IdGenerator idGenerator;

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
                        .time(((OffsetDateTime) dataItem.get(0)).toInstant())
                        .value(Optional.ofNullable(dataItem.get(1)).map(Object::toString)
                                .map(Double::parseDouble)
                                .orElse(null))
                        .build())
                .toList();

        return dataItems.isEmpty() ? null : dataItems;
    }

    Double filterNan(Double value) {
        if (value == null) {
            return null;
        }

        return value.isNaN() ? null : value;
    }
}
