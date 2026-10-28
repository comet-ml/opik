package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
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
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * The nightly run's reads of {@code topic_vectors}: how many traces each project has in a window, and one project's
 * window. A trace counts once: a relabel with a higher task version adds a row, and the window keeps the highest.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicWindowDAO {

    private static final String COUNT_BY_PROJECT = """
            SELECT project_id, uniqExact(trace_id) AS traces
            FROM topic_vectors
            WHERE workspace_id = :workspace_id
            <if(project_id)> AND project_id = :project_id <endif>
            AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
            AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
            GROUP BY project_id
            SETTINGS max_execution_time = <max_execution_time>, max_memory_usage = <max_memory_usage>,
                log_comment = '<log_comment>'
            """;

    /**
     * The newest {@code :limit} traces of the window, each with its highest task version's text and vector. The keys
     * are chosen first, without the vector and text columns, so a large window reads those only for the traces kept.
     * The caller orders the rows by time.
     */
    private static final String READ_WINDOW = """
            WITH keys AS (
                SELECT trace_id, max(task_version) AS task_version, max(trace_created_at) AS created_at
                FROM topic_vectors
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                GROUP BY trace_id
                ORDER BY created_at DESC, trace_id DESC
                LIMIT :limit
            )
            SELECT trace_id, trace_created_at, task_version, text, vector
            FROM topic_vectors
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
            AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
            AND (trace_id, task_version) IN (SELECT trace_id, task_version FROM keys)
            ORDER BY trace_id, updated_at DESC
            LIMIT 1 BY trace_id
            SETTINGS max_threads = 2, max_execution_time = <max_execution_time>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull @Config("topics") TopicsConfig config;

    /**
     * Per project of the workspace, or only for {@code projectId} when set, the traces with a vector created in
     * {@code [from, to)}. Under the window read's memory guard.
     */
    public Flux<ProjectCount> countByProject(@NonNull String workspaceId, UUID projectId, @NonNull Instant from,
            @NonNull Instant to, long maxExecutionSeconds) {
        var template = getSTWithLogComment(COUNT_BY_PROJECT, "topics:count_window", workspaceId, null,
                "project_id=%s, from=%s, to=%s".formatted(projectId, from, to))
                .add("max_memory_usage", config.getVectors().getFind().getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
        if (projectId != null) {
            template.add("project_id", true);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("from_micros", toEpochMicros(from))
                            .bind("to_micros", toEpochMicros(to));
                    if (projectId != null) {
                        statement.bind("project_id", projectId.toString());
                    }
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, metadata) -> new ProjectCount(
                        UUID.fromString(row.get("project_id", String.class)),
                        ((Number) row.get("traces", Object.class)).longValue())));
    }

    /** The newest {@code limit} traces of the project created in {@code [from, to)}, in no particular order. */
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

    private static TopicWindowRow toRow(Row row) {
        // As TopicVectorsDAO: the driver's Java array type for Array(Float32) isn't part of its contract.
        var array = row.get("vector", Object.class);
        var vector = new float[Array.getLength(array)];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = ((Number) Array.get(array, i)).floatValue();
        }
        return TopicWindowRow.builder()
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .taskVersion(((Number) row.get("task_version", Object.class)).longValue())
                .text(row.get("text", String.class))
                .vector(vector)
                .build();
    }

    private static long toEpochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }

    public record ProjectCount(@NonNull UUID projectId, long traces) {
    }
}
