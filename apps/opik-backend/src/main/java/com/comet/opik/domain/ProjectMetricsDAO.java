package com.comet.opik.domain;

import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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

    Mono<List<Entry>> getTraceCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
    Mono<List<Entry>> getFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsDAOImpl implements ProjectMetricsDAO {
    private final @NonNull TransactionTemplateAsync template;

    public static final String NAME_TRACES = "traces";

    private static final int WEEKS_TO_BACKFILL = 12;

    private static final String GET_TRACE_COUNT = """
            SELECT toStartOfInterval(start_time, <convert_interval>) AS bucket,
                   nullIf(count(DISTINCT id), 0) as count
            FROM traces
            WHERE project_id = :project_id
                AND workspace_id = :workspace_id
                AND start_time >= parseDateTime64BestEffort(:start_time, 9)
                AND start_time \\<= parseDateTime64BestEffort(:end_time, 9)
            GROUP BY bucket
            ORDER BY bucket
            WITH FILL
            <if(is_weekly)>
                FROM toStartOfWeek(parseDateTime64BestEffort(:backfill_start_time), 3)
                TO toDate(formatDateTime(parseDateTime64BestEffort(:end_time), '%F'))
            <else>
                FROM parseDateTimeBestEffort(:backfill_start_time)
                TO parseDateTimeBestEffort(:end_time)
            <endif>
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
                    nullIf(avg(value), 0) AS value
            FROM feedback_scores_deduplication
            WHERE created_at >= parseDateTime64BestEffort(:start_time, 9)
                AND created_at \\<= parseDateTime64BestEffort(:end_time, 9)
            GROUP BY name, bucket
            ORDER BY name, bucket
            WITH FILL
            <if(is_weekly)>
                FROM toStartOfWeek(parseDateTime64BestEffort(:backfill_start_time), 3)
                TO toDate(formatDateTime(parseDateTime64BestEffort(:end_time), '%F'))
            <else>
                FROM parseDateTimeBestEffort(:backfill_start_time)
                TO parseDateTimeBestEffort(:end_time)
            <endif>
                STEP <convert_interval>;
            """;

    @Override
    public Mono<List<Entry>> getTraceCount(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_TRACE_COUNT, "traceCount")
                .flatMapMany(result -> rowToDataPoint(result, request, row -> NAME_TRACES,
                        row -> row.get("count", Integer.class)))
                .collectList());
    }

    @Override
    public Mono<List<Entry>> getFeedbackScores(@NonNull UUID projectId, @NonNull ProjectMetricRequest request) {
        return template.nonTransaction(connection -> getMetric(projectId, request, connection,
                GET_FEEDBACK_SCORES, "feedbackScores")
                .flatMapMany(result -> rowToDataPoint(
                        result,
                        request,
                        row -> row.get("name", String.class),
                        row -> row.get("value", BigDecimal.class)))
                .collectList());
    }

    private Mono<? extends Result> getMetric(
            @NonNull UUID projectId, @NonNull ProjectMetricRequest request, @NonNull Connection connection,
            @NonNull String query, @NonNull String segmentName) {
        var template = new ST(query)
                .add("convert_interval", intervalToSql(request.interval()))
                .add("is_weekly", request.interval() == TimeInterval.WEEKLY);
        var statement = connection.createStatement(template.render())
                .bind("project_id", projectId)
                .bind("start_time", request.intervalStart().toString())
                .bind("end_time", request.intervalEnd().toString())
                .bind("backfill_start_time", request.intervalStart() == Instant.EPOCH ?
                        getAllTimeBackfillStart().toString() :
                        request.intervalStart().toString());

        InstrumentAsyncUtils.Segment segment = startSegment(segmentName, "Clickhouse", "get");

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .doFinally(signalType -> endSegment(segment));
    }

    private Publisher<Entry> rowToDataPoint(
            @NonNull Result result,
            @NonNull ProjectMetricRequest request,
            @NonNull Function<Row, String> nameGetter,
            @NonNull Function<Row, ? extends Number> valueGetter) {
        return result.map(((row, rowMetadata) -> Entry.builder()
                .name(nameGetter.apply(row))
                .value(valueGetter.apply(row))
                .time(extractBucket(request, row))
                .build()));
    }

    private static Instant extractBucket(@NonNull ProjectMetricRequest request, @NonNull Row row) {
        if (request.interval() == TimeInterval.WEEKLY) {
            var date = row.get("bucket", LocalDate.class);
            if (date == null) {
                return null;
            }

            return date.atStartOfDay(ZoneId.of("UTC")).toInstant();
        }

        return row.get("bucket", Instant.class);
    }

    private static String intervalToSql(@NonNull TimeInterval interval) {
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

    private static Instant getAllTimeBackfillStart() {
        return LocalDate.now()
                // find next Sunday
                .plusDays(DayOfWeek.SUNDAY.getValue() - LocalDate.now().getDayOfWeek().getValue())
                .atStartOfDay(ZoneId.of("UTC"))
                // subtract WEEKS_TO_BACKFILL weeks from it
                .minusDays(7 * WEEKS_TO_BACKFILL - 1).toInstant();
    }
}
