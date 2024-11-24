package com.comet.opik.domain;

import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.google.common.collect.ImmutableMap;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(ProjectMetricsDAOImpl.class)
public interface ProjectMetricsDAO {
    @Builder
    record Entry(String name, Instant time, Number value) {}
    @Builder
    record DataPointMultiValue(Instant time, Map<String, Number> values) {}

    Mono<List<Entry>> getTraceCount(UUID projectId, ProjectMetricRequest request);
    Mono<List<Entry>> getFeedbackScores(UUID projectId, ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsDAOImpl implements ProjectMetricsDAO {
    private final @NonNull TransactionTemplateAsync template;

    public static final String NAME_TRACES = "traces";

    private static final String GET_TRACE_COUNT = """
            SELECT toStartOfInterval(start_time, <convert_interval>) AS bucket,
                   count(DISTINCT id) as count
            FROM traces
            WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM parseDateTimeBestEffort(:start_time)
                TO parseDateTimeBestEffort(:end_time)
                STEP <convert_interval>;
            """;

    private static final String GET_FEEDBACK_SCORES = """
            WITH feedback_scores_deduplication AS (
                SELECT created_at,
                        name,
                        value
                FROM feedback_scores
                WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    AND entity_type = 'trace'
                ORDER BY entity_id DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
            )
            SELECT toStartOfInterval(created_at, <convert_interval>) AS bucket,
                    name,
                    avg(value) AS value
            FROM feedback_scores_deduplication
            WHERE created_at >= parseDateTime64BestEffort(:start_time, 9)
                AND created_at \\<= parseDateTime64BestEffort(:end_time, 9)
            GROUP BY name, bucket
            ORDER BY name, bucket
            WITH FILL
                FROM parseDateTimeBestEffort(:start_time)
                TO parseDateTimeBestEffort(:end_time)
                STEP <convert_interval>;
            """;

    @Override
    public Mono<List<Entry>> getTraceCount(UUID projectId, ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getTracesCountForProject(projectId, request, connection)
                .flatMapMany(result -> rowToDataPoint(result, row -> NAME_TRACES,
                        row -> row.get("count", Integer.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getFeedbackScores(UUID projectId, ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getFeedbackScoresForProject(projectId, request, connection)
                .flatMapMany(result -> rowToDataPoint(
                        result,
                        row -> row.get("name", String.class),
                        row -> row.get("value", BigDecimal.class)))
                .collectList());
    }

    private Mono<? extends Result> getTracesCountForProject(
            UUID projectId, ProjectMetricRequest request, Connection connection) {
        var template = new ST(GET_TRACE_COUNT)
                .add("convert_interval", intervalToSql(request.interval()));
        var statement = connection.createStatement(template.render())
                .bind("project_id", projectId)
                .bind("start_time", request.intervalStart().toString())
                .bind("end_time", request.intervalEnd().toString());

        InstrumentAsyncUtils.Segment segment = startSegment("traceCount", "Clickhouse", "get");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private Mono<? extends Result> getFeedbackScoresForProject(
            UUID projectId, ProjectMetricRequest request, Connection connection) {
        var template = new ST(GET_FEEDBACK_SCORES)
                .add("convert_interval", intervalToSql(request.interval()));
        var statement = connection.createStatement(template.render())
                .bind("project_id", projectId)
                .bind("start_time", request.intervalStart().toString())
                .bind("end_time", request.intervalEnd().toString());

        InstrumentAsyncUtils.Segment segment = startSegment("feedbackScores", "Clickhouse", "get");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private Publisher<Entry> rowToDataPoint(
            Result result, Function<Row, String> nameGetter, Function<Row, ? extends Number> valueGetter) {
        return result.map(((row, rowMetadata) -> Entry.builder()
                .name(nameGetter.apply(row))
                .value(valueGetter.apply(row))
                .time(row.get("bucket", Instant.class))
                .build()));
    }

    private String intervalToSql(TimeInterval interval) {
        if (interval == TimeInterval.WEEKLY) {
               return "toIntervalWeek(1)";
        }
        if (interval == TimeInterval.DAILY) {
            return "toIntervalDay(1)";
        }
        if (interval == TimeInterval.HOURLY) {
            return "toIntervalHour(1)";
        }

        throw new IllegalArgumentException("Invalid interval: " + interval);
    }
}
