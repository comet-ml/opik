package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(WorkspaceMetricsDAOImpl.class)
public interface WorkspaceMetricsDAO {

    Mono<List<WorkspaceMetricsSummaryResponse.Result>> getFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspaceMetricsDAOImpl implements WorkspaceMetricsDAO {

    private static final String GET_FEEDBACK_SCORES_SUMMARY = """
            WITH parseDateTime64BestEffort(:timestamp_start, 9) AS timestamp_start,
            parseDateTime64BestEffort(:timestamp_end, 9) AS timestamp_end,
            parseDateTime64BestEffort(:timestamp_prior_start, 9) AS timestamp_prior_start,
            feedback_scores_deduplication AS (
                SELECT created_at,
                       name,
                       value
                FROM feedback_scores fs final
                WHERE workspace_id = :workspace_id
                  <if(project_ids)> AND project_id IN :project_ids <endif>
                  AND created_at >= timestamp_prior_start
                  AND created_at \\<= timestamp_end
                  AND entity_type = 'trace'
            ),
            feedback_scores_aggregated AS (
                SELECT
                    AVGIf(value, created_at >= timestamp_start AND created_at \\<= timestamp_end) AS current,
                    AVGIf(value, created_at >= timestamp_prior_start AND created_at \\< timestamp_start) AS previous,
                    name
                FROM feedback_scores_deduplication
                GROUP BY name
            )
            SELECT
                IF(isNaN(current), 0, current) AS current,
                IF(isNaN(previous), 0, previous) AS previous,
                name
            FROM feedback_scores_aggregated;
            """;

    private final @NonNull TransactionTemplateAsync template;

    @Override
    public Mono<List<WorkspaceMetricsSummaryResponse.Result>> getFeedbackScoresSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return getMetricsSummary(request, GET_FEEDBACK_SCORES_SUMMARY);
    }

    private Mono<List<WorkspaceMetricsSummaryResponse.Result>> getMetricsSummary(WorkspaceMetricsSummaryRequest request,
            String query) {
        return template.nonTransaction(connection -> getMetricsSummary(connection, request, query)
                .flatMapMany(result -> result.map((row, rowMetadata) -> WorkspaceMetricsSummaryResponse.Result.builder()
                        .name(row.get("name", String.class))
                        .current(row.get("current", Double.class))
                        .previous(row.get("previous", Double.class))
                        .build()))
                .collectList());
    }

    private Mono<? extends Result> getMetricsSummary(Connection connection, WorkspaceMetricsSummaryRequest request,
            String query) {
        ST template = new ST(query);

        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            template.add("project_ids", request.projectIds());
        }

        var statement = connection.createStatement(template.render())
                .bind("timestamp_start", request.intervalStart().toString())
                .bind("timestamp_end", request.intervalEnd().toString())
                .bind("timestamp_prior_start",
                        getPriorStart(request.intervalStart(), request.intervalEnd()).toString());

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
}
