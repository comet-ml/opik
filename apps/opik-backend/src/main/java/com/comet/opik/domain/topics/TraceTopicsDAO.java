package com.comet.opik.domain.topics;

import com.comet.opik.api.Source;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsConfig.ReadGuards;
import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.infrastructure.db.TraceKeyedTableDeletes;
import com.comet.opik.utils.JsonUtils;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * {@code trace_topics}: the topic of each trace, one row per assignment, the latest per trace wins, a run row over an
 * arrival row stamped at the same time. Writes are plain batched inserts; {@code last_updated_at} is stamped by
 * ClickHouse. A reader takes each trace's latest row first and then drops it when that row is a tombstone
 * ({@code is_deleted = 1}), as reads of {@code traces_local_v2} do. The
 * first build writes the table, and reads it once per run for the run's trace counts; the read API and topic on
 * arrival read it too.
 *
 * <p>The deletes are {@link TraceKeyedTableDeletes}', as {@link TopicVectorsDAO}'s (same keys, same partitioning), so a
 * trace's topic goes with the trace exactly as its vector does: by retention and by the trace-delete cascade.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TraceTopicsDAO {

    static final String TABLE = "trace_topics";
    private static final String QUERY_PREFIX = "topics";

    /**
     * The Logs page's default filters, which the counts apply to the traces they count: {@code source = sdk} (with the
     * {@code unknown} rows from before sources, {@link Source#loggingSourceDbValues}) and
     * {@code visibility_mode = default}. Bound as {@code :logs_sources} and {@code :logs_visibility_mode}.
     */
    private static final String LOGS_VISIBILITY_MODE = VisibilityMode.DEFAULT.getValue();

    /**
     * Traces per topic among the project's traces created in the window that Logs lists, each by its latest topic row.
     * A trace counts when any of its {@code traces} rows matches the Logs filters, as the traces list counts it (its
     * filters apply before dedup), so a topic's count is what the list shows for it. Routing reads the latest row
     * instead ({@code TraceDAO.getLoggingSourceIds}). Every topic row counts, run rows and arrival rows.
     *
     * <p>Both sides dedupe in sort-key order ({@code LIMIT 1 BY}) and meet in a merge join on that whole key, so the
     * join reuses the order instead of sorting either side again, and the query streams by trace id instead of holding
     * a state per trace. Both sides hold one workspace and project, so only the trace id tells rows apart; the other
     * two keys are in the join for the order. Its memory still grows with the traces it covers, so
     * {@link #countByTopic} runs it once per {@code topics.build.read.countSlice} of the window, and
     * {@link #countByTopicAndDay} once per UTC day.
     */
    private static final String COUNT_BY_TOPIC = """
            SELECT t.topic_id AS topic_id, count() AS traces
            FROM (
                SELECT workspace_id, project_id, trace_id, topic_id
                FROM (
                    SELECT workspace_id, project_id, trace_id, topic_id, is_deleted
                    FROM trace_topics
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
                    AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
                    ORDER BY workspace_id, project_id, trace_id, last_updated_at DESC, source = 'run' DESC
                    LIMIT 1 BY workspace_id, project_id, trace_id
                )
                WHERE is_deleted = 0
            ) AS t
            INNER JOIN (
                SELECT workspace_id, project_id, id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
                AND created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
                AND source IN :logs_sources
                AND visibility_mode = :logs_visibility_mode
                ORDER BY workspace_id, project_id, id
                LIMIT 1 BY workspace_id, project_id, id
            ) AS tr ON t.workspace_id = tr.workspace_id AND t.project_id = tr.project_id AND t.trace_id = tr.id
            GROUP BY topic_id
            SETTINGS join_algorithm = 'full_sorting_merge', max_threads = <max_threads>,
                max_execution_time = <max_execution_time>, max_memory_usage = <max_memory_usage>,
                max_rows_to_read = <max_rows_to_read>, log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull JsonEachRowBulkInsert jsonBulkInsert;
    private final @NonNull TraceKeyedTableDeletes deletes;
    private final @NonNull @Config("topics") TopicsConfig config;

    public Mono<Long> insert(List<TraceTopic> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Mono.just(0L);
        }
        return jsonBulkInsert.insert(
                TABLE,
                getLogComment("topics:insert_trace_topics", null, null, "rows=%d".formatted(rows.size())),
                rows,
                row -> {
                    var node = JsonUtils.createObjectNode();
                    node.put("workspace_id", row.workspaceId());
                    node.put("project_id", row.projectId().toString());
                    node.put("trace_id", row.traceId().toString());
                    node.put("trace_created_at", row.traceCreatedAt().toString());
                    node.put("topic_id", row.topicId().toString());
                    node.put("source", row.source().getValue());
                    node.put("run_id", row.runId() == null ? null : row.runId().toString());
                    return node;
                });
    }

    /**
     * Per topic id ({@link TraceTopic#OTHER} for Other) and UTC day of {@code trace_created_at}, the project's traces
     * created in {@code [from, to)}: {@link #COUNT_BY_TOPIC} once per UTC day, one after another, under the
     * {@code topics.read} guards. The days share {@code topics.read.budget}: each query may run for what is left of it
     * (at least a second), so a window that does not fit trips the time guard instead of running on.
     */
    public Flux<TopicDayCount> countByTopicAndDay(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to) {
        var read = config.getRead();
        return Flux.defer(() -> {
            long deadline = System.nanoTime() + read.getBudget().toNanoseconds();
            return slices(from, to, TraceTopicsDAO::nextUtcDay)
                    .concatMap(dayFrom -> {
                        var day = LocalDate.ofInstant(dayFrom, ZoneOffset.UTC);
                        long seconds = Math.max(1, Math.ceilDiv(deadline - System.nanoTime(), 1_000_000_000L));
                        return countSlice(workspaceId, projectId, dayFrom, ObjectUtils.min(nextUtcDay(dayFrom), to),
                                read, seconds)
                                .map(count -> TopicDayCount.builder()
                                        .topicId(count.topicId())
                                        .day(day)
                                        .traces(count.traces())
                                        .build());
                    });
        });
    }

    /**
     * Per topic id ({@link TraceTopic#OTHER} for Other), the project's traces created in {@code [from, to)}: one query
     * per {@code topics.build.read.countSlice}, one after another, summed: a trace falls in exactly one slice by its
     * creation time. {@code maxExecutionSeconds} bounds each query.
     */
    public Flux<TopicCount> countByTopic(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull Instant from,
            @NonNull Instant to, long maxExecutionSeconds) {
        var read = config.getBuild().getRead();
        var slice = read.getCountSlice().toJavaDuration();
        return slices(from, to, sliceFrom -> sliceFrom.plus(slice))
                .concatMap(sliceFrom -> countSlice(workspaceId, projectId, sliceFrom,
                        ObjectUtils.min(sliceFrom.plus(slice), to), read, maxExecutionSeconds))
                .collect(LinkedHashMap<UUID, Long>::new,
                        (traces, count) -> traces.merge(count.topicId(), count.traces(), Long::sum))
                .flatMapIterable(Map::entrySet)
                .map(entry -> TopicCount.builder().topicId(entry.getKey()).traces(entry.getValue()).build());
    }

    /** The starts of the slices of {@code [from, to)}, each slice ending where {@code next} puts the next one. */
    private static Flux<Instant> slices(Instant from, Instant to, UnaryOperator<Instant> next) {
        return Flux.generate(() -> from, (sliceFrom, sink) -> {
            if (!sliceFrom.isBefore(to)) {
                sink.complete();
                return sliceFrom;
            }
            sink.next(sliceFrom);
            return next.apply(sliceFrom);
        });
    }

    private static Instant nextUtcDay(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private Flux<TopicCount> countSlice(String workspaceId, UUID projectId, Instant from, Instant to,
            ReadGuards read, long maxExecutionSeconds) {
        var template = getSTWithLogComment(COUNT_BY_TOPIC, "topics:count_by_topic", workspaceId, null,
                "project_id=%s, from=%s, to=%s".formatted(projectId, from, to))
                .add("max_rows_to_read", read.getMaxRowsToRead())
                .add("max_memory_usage", read.getMaxMemoryUsage())
                .add("max_threads", read.getMaxThreads())
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("window_from", from.toString())
                        .bind("window_to", to.toString())
                        .bind("logs_sources", Source.loggingSourceDbValues())
                        .bind("logs_visibility_mode", LOGS_VISIBILITY_MODE)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> TopicCount.builder()
                        .topicId(UUID.fromString(row.get("topic_id", String.class)))
                        .traces(((Number) row.get("traces", Object.class)).longValue())
                        .build()));
    }

    public Mono<Long> deleteForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        return deletes.deleteForRetention(TABLE, QUERY_PREFIX, workspaceIds, cutoffId, lowerBound);
    }

    public Mono<Long> deleteForRetentionBounded(@NonNull Map<String, UUID> workspaceMinIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        return deletes.deleteForRetentionBounded(TABLE, QUERY_PREFIX, workspaceMinIds, cutoffId, lowerBound);
    }

    /** The trace-delete cascade; workspace from the reactive context. */
    public Mono<Void> deleteByTraceIds(@NonNull Set<UUID> traceIds, @NonNull UUID projectId) {
        return deletes.deleteByTraceIds(TABLE, QUERY_PREFIX, traceIds, projectId);
    }

    @Builder(toBuilder = true)
    public record TopicDayCount(@NonNull UUID topicId, @NonNull LocalDate day, long traces) {
    }

    @Builder(toBuilder = true)
    record TopicCount(@NonNull UUID topicId, long traces) {
    }
}
