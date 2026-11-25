package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import io.r2dbc.spi.Row;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.lang.reflect.Array;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.domain.topics.TopicVectorsFixtures.TASK;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.traceCreatedAt;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/** Direct reads and writes of the tables the topic vectors tests check: topic_vectors, ai_label_status, projects. */
@RequiredArgsConstructor
class TopicVectorsTables {

    private final @NonNull TransactionTemplate template;
    private final @NonNull TransactionTemplateAsync templateAsync;

    /**
     * The workspace's vectors of {@code traceIds}: the latest row of each (project, task, trace, task version), unless
     * deleted. The driver returns BFloat16 as raw 16-bit values, so the vector is read as Float32.
     */
    List<TopicVector> vectors(String workspaceId, Collection<UUID> traceIds) {
        return templateAsync.nonTransaction(connection -> Flux
                .from(connection.createStatement("""
                        SELECT workspace_id, project_id, task, trace_id, trace_created_at, task_version, embed_model,
                            embed_dimensions, text, CAST(vector AS Array(Float32)) AS vector
                        FROM (
                            SELECT *
                            FROM topic_vectors
                            WHERE workspace_id = :workspace_id
                            AND trace_id IN :trace_ids
                            ORDER BY workspace_id, project_id, task, trace_id, task_version, last_updated_at DESC
                            LIMIT 1 BY workspace_id, project_id, task, trace_id, task_version
                        )
                        WHERE is_deleted = 0
                        """)
                        .bind("workspace_id", workspaceId)
                        .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toVector(row)))
                .collectList())
                .block();
    }

    /** The latest {@code topic_vector_status} row of each trace that has one, by trace id: its reason. */
    Map<UUID, String> statuses(String workspaceId, Collection<UUID> traceIds) {
        return templateAsync.nonTransaction(connection -> Flux
                .from(connection.createStatement("""
                        SELECT trace_id, reason
                        FROM (
                            SELECT *
                            FROM topic_vector_status
                            WHERE workspace_id = :workspace_id
                            AND trace_id IN :trace_ids
                            ORDER BY workspace_id, project_id, task, trace_id, task_version, last_updated_at DESC
                            LIMIT 1 BY workspace_id, project_id, task, trace_id, task_version
                        )
                        WHERE is_deleted = 0
                        """)
                        .bind("workspace_id", workspaceId)
                        .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(
                        UUID.fromString(row.get("trace_id", String.class)), row.get("reason", String.class))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue))
                .block();
    }

    private static TopicVector toVector(Row row) {
        // The driver's Java array type for Array(Float32) isn't part of its contract, so read the elements as numbers.
        var array = row.get("vector", Object.class);
        var vector = new float[Array.getLength(array)];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = ((Number) Array.get(array, i)).floatValue();
        }
        return TopicVector.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(UUID.fromString(row.get("project_id", String.class)))
                .task(row.get("task", String.class))
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .taskVersion(row.get("task_version", Long.class).intValue())
                .embedModel(row.get("embed_model", String.class))
                .embedDimensions(row.get("embed_dimensions", Integer.class))
                .text(row.get("text", String.class))
                .vector(vector)
                .build();
    }

    /** The project's MySQL row, which a discovery needs to keep the project's backfill row. */
    void project(String workspaceId, UUID projectId) {
        template.inTransaction(WRITE, handle -> handle.createUpdate(
                "INSERT IGNORE INTO projects (id, name, workspace_id) VALUES (:id, :name, :workspaceId)")
                .bind("id", projectId.toString())
                .bind("name", projectId.toString())
                .bind("workspaceId", workspaceId)
                .execute());
    }

    void insertStatusAt(String workspaceId, UUID projectId, UUID traceId, int taskVersion, Instant updatedAt) {
        project(workspaceId, projectId);
        execute("""
                INSERT INTO ai_label_status (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    renderer_version, status, retryable, attempt, updated_at)
                VALUES (:workspace_id, :project_id, :task, :trace_id, fromUnixTimestamp64Micro(:trace_created_at),
                    :task_version, 1, 'done', false, 1, fromUnixTimestamp64Micro(:updated_at))
                """, Map.of("workspace_id", workspaceId, "project_id", projectId.toString(), "task", TASK,
                "trace_id", traceId.toString(), "task_version", taskVersion,
                "trace_created_at", ChronoUnit.MICROS.between(Instant.EPOCH, traceCreatedAt(traceId)),
                "updated_at", ChronoUnit.MICROS.between(Instant.EPOCH, updatedAt)));
    }

    void execute(String sql, Map<String, Object> parameters) {
        templateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            parameters.forEach(statement::bind);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated()));
        }).block();
    }
}
