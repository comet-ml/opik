package com.comet.opik.domain;

import com.comet.opik.api.AggregationType;
import com.comet.opik.api.DataPoint;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(ProjectMetricsDAOImpl.class)
public interface ProjectMetricsDAO {
    @Builder(toBuilder = true)
    record MetricsCriteria(@NonNull TimeInterval interval,
                                  Instant startTimestamp,
                                  Instant endTimestamp,
                                  @NonNull AggregationType aggregation) {}

    Mono<List<DataPoint<Integer>>> getTraceCount(UUID projectId, MetricsCriteria criteria, Connection connection);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsDAOImpl implements ProjectMetricsDAO {
    private static final String GET_TRACE_COUNT = """
            SELECT toStartOfInterval(start_time, toIntervalHour(1)) AS bucket,
                   count() as count
            FROM traces
            WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                AND start_time > parseDateTime64BestEffort(:start_time, 9)
                AND end_time \\< parseDateTime64BestEffort(:end_time, 9)
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
                FROM parseDateTimeBestEffort(:start_time)
                TO parseDateTimeBestEffort(:end_time)
                STEP toIntervalHour(1);
            """;

    @Override
    public Mono<List<DataPoint<Integer>>> getTraceCount(UUID projectId, MetricsCriteria criteria, Connection connection) {
        return getTracesCountForProject(projectId, criteria, connection)
                .flatMapMany(this::mapToIntDataPoint)
                .collectList();
    }

    private Mono<? extends Result> getTracesCountForProject(
            UUID projectId, MetricsCriteria criteria, Connection connection) {
        var template = new ST(GET_TRACE_COUNT);
        var statement = connection.createStatement(template.render())
                .bind("project_id", projectId)
                .bind("start_time", criteria.startTimestamp().toString())
                .bind("end_time", criteria.endTimestamp().toString());

        InstrumentAsyncUtils.Segment segment = startSegment("traceCount", "Clickhouse", "get");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private Publisher<DataPoint<Integer>> mapToIntDataPoint(Result result) {
        return result.map(((row, rowMetadata) -> DataPoint.<Integer>builder()
                .time(row.get("bucket", Instant.class))
                .value(row.get("count", Integer.class))
                .build()));
    }
}
