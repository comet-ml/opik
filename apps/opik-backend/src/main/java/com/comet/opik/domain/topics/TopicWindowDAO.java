package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.google.common.base.Preconditions;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * A topic build's reads of {@code topic_vectors}: how many traces some projects have in a window, one project's
 * window, and the part of it the labelling pass has not labelled yet. A trace counts once: a relabel with a higher
 * task version adds a row, and the window keeps the highest.
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
     * traces, where grouping every key and then {@code uniqExact} took 0.8 to 1.05 GiB, over the 1 GiB guard. Two rows
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
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                GROUP BY project_id, trace_id
            )
            GROUP BY project_id
            HAVING traces > 0
            SETTINGS max_threads = 2, max_execution_time = <max_execution_time>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, priority = 100, optimize_aggregation_in_order = 1,
                max_block_size = 8192, log_comment = '<log_comment>'
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
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                AND is_deleted = 0
            ),
            keys AS (
                SELECT trace_id, max(task_version) AS task_version
                FROM (
                    SELECT trace_id, task_version, argMax(is_deleted, (last_updated_at, is_deleted = 0)) AS deleted
                    FROM topic_vectors
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                    AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                    AND bitShiftRight(cityHash64(trace_id), 32) \\< (SELECT share FROM share) * 4294967296
                    GROUP BY trace_id, task_version
                )
                WHERE deleted = 0
                GROUP BY trace_id
                ORDER BY cityHash64(trace_id), trace_id
                LIMIT :limit
            )
            SELECT trace_id, trace_created_at, task_version, text, vector
            FROM topic_vectors
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
            AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
            AND (trace_id, task_version) IN (SELECT trace_id, task_version FROM keys)
            AND is_deleted = 0
            ORDER BY trace_id, last_updated_at DESC
            LIMIT 1 BY trace_id
            SETTINGS max_threads = 2, max_execution_time = <max_execution_time>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, log_comment = '<log_comment>'
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
                    AND trace_id > :after
                    AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                    AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                    GROUP BY trace_id, task_version
                )
                WHERE deleted = 0
                AND trace_id NOT IN (
                    SELECT trace_id
                    FROM trace_topics
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND trace_id > :after
                    AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                    AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                    AND source = 'run'
                    AND run_id = :run_id
                )
                GROUP BY trace_id
                ORDER BY trace_id
                LIMIT :limit
            )
            SELECT trace_id, trace_created_at, vector
            FROM topic_vectors
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_id > :after
            AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
            AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
            AND (trace_id, task_version) IN (SELECT trace_id, task_version FROM keys)
            AND is_deleted = 0
            ORDER BY trace_id, last_updated_at DESC
            LIMIT 1 BY trace_id
            SETTINGS max_threads = 2, max_execution_time = <max_execution_time>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, priority = 100, log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull @Config("topics") TopicsConfig config;

    /**
     * Per project of {@code projectIds}, the traces with a vector created in {@code [from, to)}; a project with none is
     * left out. Under the window read's row and memory guards, on 2 threads at a background priority.
     */
    public Flux<ProjectCount> countByProject(@NonNull String workspaceId, @NonNull Collection<UUID> projectIds,
            @NonNull Instant from, @NonNull Instant to, long maxExecutionSeconds) {
        Preconditions.checkArgument(!projectIds.isEmpty(), "a window count takes at least one project");
        var find = config.getVectors().getFind();
        var template = getSTWithLogComment(COUNT_BY_PROJECT, "topics:count_window", workspaceId, null,
                "projects=%d, from=%s, to=%s".formatted(projectIds.size(), from, to))
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_ids", projectIds.stream().map(UUID::toString).toArray(String[]::new))
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> new ProjectCount(
                        UUID.fromString(row.get("project_id", String.class)),
                        ((Number) row.get("traces", Object.class)).longValue())));
    }

    /**
     * The project's traces created in {@code [from, to)}, or an even, stable sample of {@code limit} of them when there
     * are more, in no particular order.
     */
    public Flux<TopicWindowRow> readWindow(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to, int limit, long maxExecutionSeconds) {
        // The guards of the vectors job's window read: validated config numbers, never input.
        var find = config.getVectors().getFind();
        var template = getSTWithLogComment(READ_WINDOW, "topics:read_window", workspaceId, null,
                "project_id=%s, from=%s, to=%s, limit=%d".formatted(projectId, from, to, limit))
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .bind("limit", limit)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toRow(row)));
    }

    /**
     * One page of the labelling pass over {@code [from, to)}, a slice of the window: up to {@code limit} traces after
     * {@code after} (a trace id, or the empty string for the slice's start) without a {@code trace_topics} row of
     * {@code runId}, in trace id order, with their vectors. Under the window read's guards, on 2 threads at a
     * background priority.
     */
    public Flux<WindowVector> readUnlabelled(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull UUID runId, @NonNull Instant from, @NonNull Instant to, @NonNull String after, int limit,
            long maxExecutionSeconds) {
        var find = config.getVectors().getFind();
        var template = getSTWithLogComment(READ_UNLABELLED, "topics:read_unlabelled", workspaceId, null,
                "project_id=%s, run_id=%s, from=%s, to=%s, limit=%d".formatted(projectId, runId, from, to, limit))
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("run_id", runId.toString())
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .bind("after", after)
                        .bind("limit", limit)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> new WindowVector(
                        UUID.fromString(row.get("trace_id", String.class)),
                        row.get("trace_created_at", Instant.class),
                        toVector(row))));
    }

    private static TopicWindowRow toRow(Row row) {
        return TopicWindowRow.builder()
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .taskVersion(((Number) row.get("task_version", Object.class)).longValue())
                .text(row.get("text", String.class))
                .vector(toVector(row))
                .build();
    }

    private static float[] toVector(Row row) {
        // As TopicVectorsDAO: the driver's Java array type for Array(Float32) isn't part of its contract.
        var array = row.get("vector", Object.class);
        var vector = new float[Array.getLength(array)];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = ((Number) Array.get(array, i)).floatValue();
        }
        return vector;
    }

    private static long toEpochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }

    public record ProjectCount(@NonNull UUID projectId, long traces) {
    }

    /** One trace of a labelling page: its id, creation time and the vector of its highest live task version. */
    public record WindowVector(@NonNull UUID traceId, @NonNull Instant traceCreatedAt, @NonNull float[] vector) {
    }
}
