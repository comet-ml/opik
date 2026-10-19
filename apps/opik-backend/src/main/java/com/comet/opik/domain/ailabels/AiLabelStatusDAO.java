package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.utils.JsonUtils;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * {@code ai_label_status}: the outcome of each (trace, task), whose latest row is authoritative. Writes are
 * plain batched inserts; {@code updated_at} is stamped by ClickHouse. Reads dedupe explicitly with {@code LIMIT 1 BY}
 * and never rely on merges. The sweep and headline queries live with the sweep.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AiLabelStatusDAO {

    static final String TABLE = "ai_label_status";

    /**
     * The latest row of every (trace, task) of the given traces. Filters on mutable columns go after the dedup, so an
     * older row can never stand in for the latest.
     */
    private static final String FIND_LATEST_BY_TRACE_IDS = """
            SELECT workspace_id, project_id, task, trace_id, trace_created_at, task_version, renderer_version, status,
                retryable, attempt, error_code, job_id, compaction_level
            FROM ai_label_status
            WHERE workspace_id = :workspace_id
            AND trace_id IN :trace_ids
            ORDER BY workspace_id, project_id, task, trace_id, updated_at DESC
            LIMIT 1 BY workspace_id, project_id, task, trace_id
            SETTINGS log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull JsonEachRowBulkInsert jsonBulkInsert;
    private final @NonNull AiLabelTableDeletes deletes;

    public Mono<Long> insert(List<AiLabelStatus> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Mono.just(0L);
        }
        return jsonBulkInsert.insert(
                TABLE,
                getLogComment("ai-labels:insert_status", null, null, "rows=%d".formatted(rows.size())),
                rows,
                row -> {
                    var node = JsonUtils.createObjectNode();
                    node.put("workspace_id", row.workspaceId());
                    node.put("project_id", row.projectId().toString());
                    node.put("task", row.task());
                    node.put("trace_id", row.traceId().toString());
                    node.put("trace_created_at", row.traceCreatedAt().toString());
                    node.put("task_version", row.taskVersion());
                    node.put("renderer_version", row.rendererVersion());
                    node.put("status", row.status().getValue());
                    node.put("retryable", row.retryable());
                    node.put("attempt", row.attempt());
                    node.put("error_code", row.errorCode());
                    node.put("job_id", row.jobId());
                    node.put("compaction_level", row.compactionLevel());
                    return node;
                });
    }

    public Flux<AiLabelStatus> findLatestByTraceIds(@NonNull String workspaceId, @NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Flux.empty();
        }
        var template = getSTWithLogComment(FIND_LATEST_BY_TRACE_IDS, "ai-labels:find_latest_status", workspaceId,
                null, "trace_ids=%d".formatted(traceIds.size()));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toStatus(row)));
    }

    public Mono<Long> deleteForRetention(List<String> workspaceIds, UUID cutoffId, UUID lowerBound) {
        return deletes.deleteForRetention(TABLE, workspaceIds, cutoffId, lowerBound);
    }

    public Mono<Long> deleteForRetentionBounded(Map<String, UUID> workspaceMinIds, UUID cutoffId, UUID lowerBound) {
        return deletes.deleteForRetentionBounded(TABLE, workspaceMinIds, cutoffId, lowerBound);
    }

    /** The trace-delete cascade; workspace from the reactive context. */
    public Mono<Void> deleteByTraceIds(Set<UUID> traceIds, UUID projectId) {
        return deletes.deleteByTraceIds(TABLE, traceIds, projectId);
    }

    private static AiLabelStatus toStatus(Row row) {
        var status = row.get("status", String.class);
        return AiLabelStatus.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(UUID.fromString(row.get("project_id", String.class)))
                .task(row.get("task", String.class))
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .taskVersion(row.get("task_version", Long.class).intValue())
                .rendererVersion(row.get("renderer_version", Long.class).intValue())
                .status(Arrays.stream(AiLabelStatus.Status.values())
                        .filter(value -> value.getValue().equals(status))
                        .findFirst()
                        .orElseThrow())
                .retryable(row.get("retryable", Boolean.class))
                .attempt(row.get("attempt", Integer.class))
                .errorCode(row.get("error_code", String.class))
                .jobId(row.get("job_id", String.class))
                .compactionLevel(row.get("compaction_level", String.class))
                .build();
    }
}
