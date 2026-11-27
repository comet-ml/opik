package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.infrastructure.db.JsonRowValues;
import com.comet.opik.infrastructure.db.TraceKeyedTableDeletes;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.WeeklyPartitions;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * {@code ai_labels}: one row per (trace, task, key). Writes are plain batched inserts, one per tick;
 * {@code updated_at} is stamped by ClickHouse. Reads dedupe explicitly with {@code LIMIT 1 BY}, taking each key's
 * latest write: the same row a merge keeps, so a read never changes once parts merge. A relabel writes its higher
 * {@code task_version} later, so the latest write is also the latest version.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AiLabelsDAO {

    static final String TABLE = "ai_labels";

    private static final Map<String, AiLabel.AnswerType> ANSWER_TYPES = Arrays.stream(AiLabel.AnswerType.values())
            .collect(Collectors.toUnmodifiableMap(AiLabel.AnswerType::getValue, Function.identity()));

    private static final String FIND_LATEST_BY_TRACE_IDS = """
            SELECT workspace_id, project_id, task, trace_id, key, task_version, answer_type, noul, score, choice,
                confidence, probabilities, legend, output
            FROM ai_labels
            WHERE workspace_id = :workspace_id
            AND trace_id IN :trace_ids
            ORDER BY workspace_id, project_id, task, trace_id, key, updated_at DESC
            LIMIT 1 BY workspace_id, project_id, task, trace_id, key
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * A trace's latest version, as the renderer reads it. The week bound is {@code TraceDAO}'s: an {@code id IN} list
     * alone opens every weekly partition. Sound only because a trace delete returns once every replica applied it
     * ({@code lightweight_deletes_sync = 2}, set on {@code TraceDAO}'s delete by trace ids): the cascade starts after
     * that, so a check that reads the trace as live ran before the cascade, which then finds the rows.
     */
    private static final String FIND_LIVE_TRACE_IDS = """
            SELECT id
            FROM (
                SELECT id, is_deleted
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id IN :trace_ids
                <if(id_weeks)>AND toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) IN :id_weeks<endif>
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            WHERE is_deleted = 0
            SETTINGS log_comment = '<log_comment>', max_threads = 2, priority = 100, max_memory_usage = 2147483648,
                max_execution_time = <max_execution_time>
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull JsonEachRowBulkInsert jsonBulkInsert;
    private final @NonNull TraceKeyedTableDeletes deletes;

    public Mono<Long> insert(List<AiLabel> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Mono.just(0L);
        }
        return jsonBulkInsert.insert(
                TABLE,
                getLogComment("ai-labels:insert_labels", null, null, "rows=%d".formatted(rows.size())),
                rows,
                row -> {
                    var node = JsonUtils.createObjectNode();
                    node.put("workspace_id", row.workspaceId());
                    node.put("project_id", row.projectId().toString());
                    node.put("task", row.task());
                    node.put("trace_id", row.traceId().toString());
                    node.put("key", row.key());
                    node.put("task_version", row.taskVersion());
                    node.put("answer_type", row.answerType().getValue());
                    node.put("noul", row.noul());
                    node.put("score", row.score());
                    JsonRowValues.putStringOrNull(node, "choice", row.choice());
                    node.put("confidence", row.confidence());
                    var probabilities = node.putObject("probabilities");
                    row.probabilities().forEach(probabilities::put);
                    JsonRowValues.putStringMap(node, "legend", row.legend());
                    node.put("output", row.output());
                    return node;
                });
    }

    public Flux<AiLabel> findLatestByTraceIds(@NonNull String workspaceId, @NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Flux.empty();
        }
        var template = getSTWithLogComment(FIND_LATEST_BY_TRACE_IDS, "ai-labels:find_latest_labels", workspaceId,
                null, "trace_ids=%d".formatted(traceIds.size()));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toLabel(row)));
    }

    /** Which of {@code traceIds} still have a row in {@code traces}, in the given workspace and project. */
    public Mono<Set<UUID>> findLiveTraceIds(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Collection<UUID> traceIds, long maxExecutionSeconds) {
        if (traceIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        var idWeeks = WeeklyPartitions.weeksOf(traceIds).map(weeks -> weeks.toArray(Long[]::new));
        var template = getSTWithLogComment(FIND_LIVE_TRACE_IDS, "ai-labels:find_live_trace_ids", workspaceId, null,
                "trace_ids=%d".formatted(traceIds.size()))
                .add("id_weeks", idWeeks.isPresent())
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("project_id", projectId.toString())
                            .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new));
                    idWeeks.ifPresent(weeks -> statement.bind("id_weeks", weeks));
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, metadata) -> UUID.fromString(row.get("id", String.class))))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Mono<Long> deleteForRetention(List<String> workspaceIds, UUID cutoffId, UUID lowerBound) {
        return deletes.deleteForRetention(TABLE, "ai-labels", workspaceIds, cutoffId, lowerBound);
    }

    public Mono<Long> deleteForRetentionBounded(Map<String, UUID> workspaceMinIds, UUID cutoffId, UUID lowerBound) {
        return deletes.deleteForRetentionBounded(TABLE, "ai-labels", workspaceMinIds, cutoffId, lowerBound);
    }

    /** The trace-delete cascade; workspace from the reactive context. */
    public Mono<Void> deleteByTraceIds(Set<UUID> traceIds, UUID projectId) {
        return deletes.deleteByTraceIds(TABLE, "ai-labels", traceIds, projectId);
    }

    @SuppressWarnings("unchecked")
    private static AiLabel toLabel(Row row) {
        return AiLabel.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(UUID.fromString(row.get("project_id", String.class)))
                .task(row.get("task", String.class))
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .key(row.get("key", String.class))
                .taskVersion(row.get("task_version", Long.class).intValue())
                .answerType(Objects.requireNonNull(ANSWER_TYPES.get(row.get("answer_type", String.class))))
                .noul(row.get("noul", Float.class))
                .score(row.get("score", Float.class))
                .choice(row.get("choice", String.class))
                .confidence(row.get("confidence", Float.class))
                // The driver reads an empty Map column as null.
                .probabilities(Objects.requireNonNullElse(row.get("probabilities", Map.class), Map.of()))
                .legend(Objects.requireNonNullElse(row.get("legend", Map.class), Map.of()))
                .output(row.get("output", String.class))
                .build();
    }
}
