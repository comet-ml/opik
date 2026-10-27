package com.comet.opik.domain.topics;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
            AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
            AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
            GROUP BY project_id
            SETTINGS max_execution_time = <max_execution_time>, log_comment = '<log_comment>'
            """;

    /**
     * The newest {@code :limit} traces of the window, each with its highest task version's text and vector. The caller
     * orders them by time.
     */
    private static final String READ_WINDOW = """
            SELECT trace_id, trace_created_at, task_version, text, vector
            FROM (
                SELECT trace_id, trace_created_at, task_version, text, vector
                FROM topic_vectors
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                ORDER BY workspace_id, project_id, trace_id, task_version DESC, updated_at DESC
                LIMIT 1 BY workspace_id, project_id, trace_id
            )
            ORDER BY trace_created_at DESC, trace_id DESC
            LIMIT :limit
            SETTINGS max_threads = 2, max_execution_time = <max_execution_time>, log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    /** Per project of the workspace, the traces with a vector created in {@code [from, to)}. */
    public Flux<ProjectCount> countByProject(@NonNull String workspaceId, @NonNull Instant from, @NonNull Instant to,
            long maxExecutionSeconds) {
        var template = getSTWithLogComment(COUNT_BY_PROJECT, "topics:count_window", workspaceId, null,
                "from=%s, to=%s".formatted(from, to))
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> new ProjectCount(
                        UUID.fromString(row.get("project_id", String.class)),
                        ((Number) row.get("traces", Object.class)).longValue())));
    }

    /** The newest {@code limit} traces of the project created in {@code [from, to)}, in no particular order. */
    public Flux<TopicWindowRow> readWindow(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to, int limit, long maxExecutionSeconds) {
        var template = getSTWithLogComment(READ_WINDOW, "topics:read_window", workspaceId, null,
                "project_id=%s, from=%s, to=%s, limit=%d".formatted(projectId, from, to, limit))
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
