package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.TopicsConfig.ReadGuards;
import com.google.common.base.Preconditions;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.lang.reflect.Array;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

/**
 * A topic run's reads of {@code topic_vectors}, the {@link TopicEmbedding#SUMMARY_TASK} rows: how many traces some
 * projects have in a window, one project's window, and the part of it the labelling pass has not labelled yet. A trace
 * counts once: a relabel with a higher task version adds a row, and the window keeps the highest.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicWindowDAO {

    /**
     * Each (trace, task version) key counts by its latest row, and a key whose latest row is a tombstone
     * ({@code is_deleted = 1}) is left out, as {@code traces_local_v2} reads do. A trace counts once, when any of its
     * keys is live. The count is exact.
     *
     * <p>One group per trace, in the table's sort order ({@code optimize_aggregation_in_order}): a trace's rows are
     * next to each other, so a group is done when the next trace starts and memory does not grow with the window.
     * {@code maxMap} keeps, per task version, the latest row as {@code last_updated_at * 2 + live}, and the trace is
     * live when one of those is odd. Small blocks keep those per-trace arrays small in flight: 74 MiB at 3 million
     * traces, where grouping every key and then {@code uniqExact} took 0.8 to 1.05 GiB, over the 1 GiB guard. That
     * figure scales with the parts the window spans: in-order aggregation keeps one stream per part. Two rows
     * of one key with the same {@code last_updated_at} count as live, as in the reads, whose {@code argMax} orders a
     * key's rows by {@code (last_updated_at, live)}.
     */
    private static final String COUNT_BY_PROJECT = """
            SELECT project_id, countIf(live) AS traces
            FROM (
                SELECT project_id, trace_id,
                    arrayExists(x -> x % 2 = 1, (maxMap([task_version],
                        [toUInt64(toUnixTimestamp64Micro(last_updated_at)) * 2 + (is_deleted = 0)])).2) AS live
                FROM topic_vectors
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND task = :task
                AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
                AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
                GROUP BY project_id, trace_id
            )
            GROUP BY project_id
            HAVING traces > 0
            SETTINGS max_threads = <max_threads>, max_execution_time = <max_execution_time>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, priority = <priority>, optimize_aggregation_in_order = 1,
                max_block_size = <max_block_size>, log_comment = '<log_comment>'
            """;

    /**
     * One project's live traces in the window, counted as in {@link #COUNT_BY_PROJECT} and by the same in-order scan,
     * plus the slice bounds of the labelling pass: every {@code :slice_traces}-th creation time of those traces, in
     * order. Slices cut at these bounds hold {@code :slice_traces} traces each, however the traffic is spread in time.
     *
     * <p>The bounds come from numbering the live traces in creation order, not from an array of all their times: the
     * sort spills to disk past {@code max_bytes_before_external_sort}, an eighth of the memory guard, and the frame of
     * one row lets the numbering drop each block once it is read. The ratio setting is off, as it would override the
     * byte limit. What still grows with the window is the per-trace {@code GROUP BY trace_id} before the numbering,
     * about 12 MiB per million live traces: 376 MiB at 30 million under the 1 GiB guard, so the guard is reached again
     * at about 70 to 80 million (extrapolated).
     */
    private static final String COUNT_AND_BOUNDS = """
            SELECT max(n) AS traces,
                arrayMap(t -> toUnixTimestamp64Micro(t), arraySort(groupArrayIf(created, n % :slice_traces = 0))) AS bounds
            FROM (
                SELECT created, row_number() OVER (ORDER BY created ROWS BETWEEN CURRENT ROW AND CURRENT ROW) AS n
                FROM (
                    SELECT trace_id, min(trace_created_at) AS created,
                        arrayExists(x -> x % 2 = 1, (maxMap([task_version],
                            [toUInt64(toUnixTimestamp64Micro(last_updated_at)) * 2 + (is_deleted = 0)])).2) AS live
                    FROM topic_vectors
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND task = :task
                    AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
                    AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
                    GROUP BY trace_id
                )
                WHERE live
            )
            SETTINGS max_threads = <max_threads>, max_execution_time = <max_execution_time>,
                max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>, priority = <priority>,
                optimize_aggregation_in_order = 1, max_block_size = <max_block_size>,
                max_bytes_before_external_sort = <external_sort_bytes>, max_bytes_ratio_before_external_sort = 0,
                min_external_sort_block_bytes = <external_sort_block_bytes>, log_comment = '<log_comment>'
            """;

    /**
     * At most {@code :limit} traces of the window, each with its highest live task version's text and vector. A window
     * with no more than {@code :limit} traces is read whole. A larger one keeps the {@code :limit} traces with the
     * lowest {@code cityHash64(trace_id)}: the hash mixes every character of the id, so it does not follow the id's
     * time prefix and the sample is even over the window; and a trace's hash never changes, so the same traces are
     * chosen run after run while they stay in the window and the window keeps its size. A key whose latest row is a
     * tombstone is left out first, as in {@link #COUNT_BY_PROJECT}: on a tie the live row wins. The keys are chosen without the vector and text
     * columns. A sample has rows in nearly every granule of the window, so the second step reads those columns for the
     * whole window, not only for its newest part. The caller orders the rows by time.
     *
     * <p>The keys step groups only the traces whose hash is in the lowest {@code share} of the hash range: 1.5 times
     * the limit over a {@code uniq} estimate of the window's traces, compared on the hash's top 32 bits. The lowest
     * {@code :limit} hashes are inside it, so the sample is the same, and the step holds about 1.5 times the limit of
     * keys instead of the whole window (230 MiB instead of 990 MiB at 3 million traces). A share of 1 or more keeps
     * every trace. Only a window with over a third of its traces deleted and not yet merged away can get a sample a
     * little under the limit.
     */
    private static final String READ_WINDOW = """
            WITH share AS (
                SELECT :limit * 1.5 / greatest(uniq(trace_id), 1) AS share
                FROM topic_vectors
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND task = :task
                AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
                AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
                AND is_deleted = 0
            ),
            keys AS (
                SELECT trace_id, max(task_version) AS task_version
                FROM (
                    SELECT trace_id, task_version, argMax(is_deleted, (last_updated_at, is_deleted = 0)) AS deleted
                    FROM topic_vectors
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND task = :task
                    AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
                    AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
                    AND bitShiftRight(cityHash64(trace_id), 32) \\< (SELECT share FROM share) * 4294967296
                    GROUP BY trace_id, task_version
                )
                WHERE deleted = 0
                GROUP BY trace_id
                ORDER BY cityHash64(trace_id), trace_id
                LIMIT :limit
            )
            SELECT trace_id, trace_created_at, task_version, text, CAST(vector AS Array(Float32)) AS vector
            FROM topic_vectors
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND task = :task
            AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
            AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
            AND (trace_id, task_version) IN (SELECT trace_id, task_version FROM keys)
            AND is_deleted = 0
            ORDER BY trace_id, last_updated_at DESC
            LIMIT 1 BY trace_id
            SETTINGS max_threads = <max_threads>, max_execution_time = <max_execution_time>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, max_block_size = <max_block_size>, optimize_read_in_order = 0,
                log_comment = '<log_comment>'
            """;

    /**
     * One page of the labelling pass: the first {@code :limit} traces after {@code :after}, in trace id order, of the
     * part of the window in {@code [from, to)} that has no {@code trace_topics} row of the run yet, each with its
     * highest live task version's vector. The keys are chosen as in {@link #READ_WINDOW}. A trace with any row of the
     * run is done: the run's own insert or an earlier pass wrote it, so a pass that was cut goes on where it stopped. A
     * {@code trace_topics} row is deleted only together with its trace's vector, so any row of the run counts and no
     * dedup is needed. {@code :after} bounds both reads by the sort key, so a page reads only the rest of its slice.
     */
    private static final String READ_UNLABELLED = """
            WITH keys AS (
                SELECT trace_id, max(task_version) AS task_version
                FROM (
                    SELECT trace_id, task_version, argMax(is_deleted, (last_updated_at, is_deleted = 0)) AS deleted
                    FROM topic_vectors
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND task = :task
                    AND trace_id > :after
                    AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
                    AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
                    GROUP BY trace_id, task_version
                )
                WHERE deleted = 0
                AND trace_id NOT IN (
                    SELECT trace_id
                    FROM trace_topics
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND trace_id > :after
                    AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
                    AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
                    AND source = 'run'
                    AND run_id = :run_id
                    AND is_deleted = 0
                )
                GROUP BY trace_id
                ORDER BY trace_id
                LIMIT :limit
            )
            SELECT trace_id, trace_created_at, CAST(vector AS Array(Float32)) AS vector
            FROM topic_vectors
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND task = :task
            AND trace_id > :after
            AND trace_created_at >= parseDateTime64BestEffort(:window_from, 6, 'UTC')
            AND trace_created_at \\< parseDateTime64BestEffort(:window_to, 6, 'UTC')
            AND (trace_id, task_version) IN (SELECT trace_id, task_version FROM keys)
            AND is_deleted = 0
            ORDER BY trace_id, last_updated_at DESC
            LIMIT 1 BY trace_id
            SETTINGS max_threads = <max_threads>, max_execution_time = <max_execution_time>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, priority = <priority>, max_block_size = <max_block_size>,
                optimize_read_in_order = 0, log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull @Config("topics") TopicsConfig config;

    /**
     * Per project of {@code projectIds}, the traces with a vector created in {@code [from, to)}; 0 for a project with
     * none.
     */
    public Mono<Map<UUID, Long>> countByProject(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds,
            @NonNull Instant from, @NonNull Instant to, long maxExecutionSeconds) {
        return countByProject(workspaceId, projectIds, from, to, config.getRun().getRead(), maxExecutionSeconds);
    }

    /** {@link #countByProject} under the given guards, for a caller that is not the run (the read API). */
    public Mono<Map<UUID, Long>> countByProject(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds,
            @NonNull Instant from, @NonNull Instant to, @NonNull ReadGuards guards, long maxExecutionSeconds) {
        Preconditions.checkArgument(!projectIds.isEmpty(), "a window count takes at least one project");
        var template = getSTWithLogComment(COUNT_BY_PROJECT, "topics:count_window", workspaceId, null,
                "projects=%d, from=%s, to=%s".formatted(projectIds.size(), from, to));
        withGuards(template, guards, maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", TopicEmbedding.SUMMARY_TASK)
                        .bind("project_ids", projectIds.stream().map(UUID::toString).toArray(String[]::new))
                        .bind("window_from", from.toString())
                        .bind("window_to", to.toString())
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(
                        UUID.fromString(row.get("project_id", String.class)),
                        ((Number) row.get("traces", Object.class)).longValue())))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap<UUID, Long>::new)
                .map(counts -> {
                    projectIds.forEach(projectId -> counts.putIfAbsent(projectId, 0L));
                    return counts;
                });
    }

    /**
     * The project's traces with a vector created in {@code [from, to)}, and slice bounds of {@code sliceTraces}, in the
     * workspace of the Reactor context.
     */
    public Mono<WindowBounds> countAndBounds(@NonNull UUID projectId, @NonNull Instant from, @NonNull Instant to,
            int sliceTraces, long maxExecutionSeconds) {
        Preconditions.checkArgument(sliceTraces > 0, "a slice holds at least one trace");
        return makeMonoContextAware((userName, workspaceId) -> countAndBounds(workspaceId, projectId, from, to,
                sliceTraces, maxExecutionSeconds));
    }

    private Mono<WindowBounds> countAndBounds(String workspaceId, UUID projectId, Instant from, Instant to,
            int sliceTraces, long maxExecutionSeconds) {
        var template = getSTWithLogComment(COUNT_AND_BOUNDS, "topics:count_and_bounds", workspaceId, null,
                "project_id=%s, from=%s, to=%s, slice_traces=%d".formatted(projectId, from, to, sliceTraces));
        var guards = config.getRun().getRead();
        withGuards(template, guards, maxExecutionSeconds);
        long externalSortBytes = guards.getMaxMemoryUsage() / 8;
        template.add("external_sort_bytes", externalSortBytes)
                .add("external_sort_block_bytes", externalSortBytes / 4);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", TopicEmbedding.SUMMARY_TASK)
                        .bind("project_id", projectId.toString())
                        .bind("window_from", from.toString())
                        .bind("window_to", to.toString())
                        .bind("slice_traces", sliceTraces)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> {
                    var array = row.get("bounds", Object.class);
                    var bounds = new ArrayList<Instant>(Array.getLength(array));
                    for (int i = 0; i < Array.getLength(array); i++) {
                        bounds.add(Instant.EPOCH.plus(((Number) Array.get(array, i)).longValue(), ChronoUnit.MICROS));
                    }
                    return WindowBounds.builder()
                            .traces(((Number) row.get("traces", Object.class)).longValue())
                            .bounds(bounds)
                            .build();
                }))
                .single();
    }

    /**
     * The project's traces created in {@code [from, to)}, or an even, stable sample of {@code limit} of them when there
     * are more, in no particular order, in the workspace of the Reactor context.
     */
    public Flux<WindowRow> readWindow(@NonNull UUID projectId, @NonNull Instant from, @NonNull Instant to, int limit,
            long maxExecutionSeconds) {
        return makeFluxContextAware((userName, workspaceId) -> readWindow(workspaceId, projectId, from, to, limit,
                maxExecutionSeconds));
    }

    private Flux<WindowRow> readWindow(String workspaceId, UUID projectId, Instant from, Instant to, int limit,
            long maxExecutionSeconds) {
        var template = getSTWithLogComment(READ_WINDOW, "topics:read_window", workspaceId, null,
                "project_id=%s, from=%s, to=%s, limit=%d".formatted(projectId, from, to, limit));
        withGuards(template, config.getRun().getRead(), maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", TopicEmbedding.SUMMARY_TASK)
                        .bind("project_id", projectId.toString())
                        .bind("window_from", from.toString())
                        .bind("window_to", to.toString())
                        .bind("limit", limit)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toRow(row)));
    }

    /**
     * One page of {@link #READ_UNLABELLED} in the workspace of the Reactor context; {@code after} is a trace id, or the
     * empty string for the slice's start.
     */
    public Flux<WindowVector> readUnlabelled(@NonNull UUID projectId, @NonNull UUID runId, @NonNull Instant from,
            @NonNull Instant to, @NonNull String after, int limit, long maxExecutionSeconds) {
        return makeFluxContextAware((userName, workspaceId) -> readUnlabelled(workspaceId, projectId, runId, from, to,
                after, limit, maxExecutionSeconds));
    }

    private Flux<WindowVector> readUnlabelled(String workspaceId, UUID projectId, UUID runId, Instant from,
            Instant to, String after, int limit, long maxExecutionSeconds) {
        var template = getSTWithLogComment(READ_UNLABELLED, "topics:read_unlabelled", workspaceId, null,
                "project_id=%s, run_id=%s, from=%s, to=%s, limit=%d".formatted(projectId, runId, from, to, limit));
        withGuards(template, config.getRun().getRead(), maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", TopicEmbedding.SUMMARY_TASK)
                        .bind("project_id", projectId.toString())
                        .bind("run_id", runId.toString())
                        .bind("window_from", from.toString())
                        .bind("window_to", to.toString())
                        .bind("after", after)
                        .bind("limit", limit)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> WindowVector.builder()
                        .traceId(UUID.fromString(row.get("trace_id", String.class)))
                        .traceCreatedAt(row.get("trace_created_at", Instant.class))
                        .vector(toVector(row))
                        .build()));
    }

    /** Validated config numbers, never input: {@code topics.run.read} for the run, {@code topics.read} for the API. */
    private static void withGuards(ST template, ReadGuards guards, long maxExecutionSeconds) {
        template.add("max_rows_to_read", guards.getMaxRowsToRead())
                .add("max_memory_usage", guards.getMaxMemoryUsage())
                .add("max_threads", guards.getMaxThreads())
                .add("priority", guards.getPriority())
                .add("max_block_size", guards.getMaxBlockSize())
                .add("max_execution_time", maxExecutionSeconds);
    }

    private static WindowRow toRow(Row row) {
        return WindowRow.builder()
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .taskVersion(((Number) row.get("task_version", Object.class)).intValue())
                .text(row.get("text", String.class))
                .vector(toVector(row))
                .build();
    }

    /**
     * The stored vector at unit length again: ClickHouse truncates it to BFloat16 on insert, which leaves it slightly
     * shorter. The read casts it to Float32, as the driver returns a BFloat16 array's raw bits. The driver's array is
     * the row's own and is read once, so it is scaled in place.
     */
    private static float[] toVector(Row row) {
        // The driver returns Array(Float32) as a float[]; that type isn't part of its contract, so reflection stays as
        // the fallback, as in TopicVectorsDAO.
        var array = row.get("vector", Object.class);
        float[] vector;
        if (array instanceof float[] floats) {
            vector = floats;
        } else {
            vector = new float[Array.getLength(array)];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = ((Number) Array.get(array, i)).floatValue();
            }
        }
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        if (norm > 0) {
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    /** A window's live traces and the inner bounds of its slices, ascending; empty when it fits one slice. */
    @Builder(toBuilder = true)
    record WindowBounds(long traces, @NonNull List<Instant> bounds) {
    }

    /** One trace of a run's window: its highest live task version, with that version's text and vector. */
    @Builder(toBuilder = true)
    record WindowRow(@NonNull UUID traceId, @NonNull Instant traceCreatedAt, int taskVersion,
            @NonNull String text, float @NonNull [] vector) {
    }

    /** One trace of a labelling page: its id, creation time and the vector of its highest live task version. */
    @Builder(toBuilder = true)
    record WindowVector(@NonNull UUID traceId, @NonNull Instant traceCreatedAt, float @NonNull [] vector) {
    }
}
