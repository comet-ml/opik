package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTableDeletes;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * {@code topic_vectors}: one row per (trace, task version). Writes are plain batched inserts; {@code updated_at} is
 * stamped by ClickHouse. It reads ai-labels' {@code ai_label_status} and {@code ai_labels} and never writes them.
 *
 * <p>The deletes are {@link AiLabelTableDeletes}' (same keys, same partitioning), so the vectors go with their traces
 * exactly as the labels do: by retention and by the trace-delete cascade.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicVectorsDAO {

    static final String TABLE = "topic_vectors";

    /**
     * The window read: the latest {@code ai_label_status} row of every (trace, task) updated in the window, kept when
     * done, minus the (trace, version) keys that already have a vector, oldest first. {@code updated_at} only grows per
     * key, so its lower bound is safe before the dedup; the status and the upper bound go after it.
     *
     * <p>Both sides are bounded by the same {@code trace_created_at >= :created_after}: {@code trace_created_at} is
     * copied from the trace and is the same on every row of a key in both tables, so the bound drops whole keys from
     * both sides alike and the anti-join stays exact. It lets the {@code idx_trace_created_at} minmax indexes skip the
     * workspace's older history, which the {@code updated_at} filter alone cannot. The guards are
     * {@code AiLabelSweepDAO}'s.
     *
     * <p>A key is kept only when its latest {@code ai_labels} output row has the status's task version and a summary
     * that isn't blank, and this is checked before the {@code LIMIT}: otherwise unusable rows, which stay in the window
     * until they age out, could fill every tick's batch and hold back newer traces. The output read takes only the
     * (project, trace) pairs of {@code done}, so it reads the window's traces and not the workspace's history, and only
     * from the {@code id_at} of {@code done}'s oldest trace id on, so it skips the older partitions and granules.
     * {@code id_at} is the id's time cut to whole seconds, so the bound is cut the same way: at the id's millisecond time,
     * the oldest trace's own row would fall under it. A tie on {@code updated_at} takes the higher task version, then the
     * greater output, so the pick doesn't change between runs.
     */
    private static final String FIND_UNVECTORED = """
            WITH done AS (
                SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, updated_at
                FROM (
                    SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, status, updated_at
                    FROM ai_label_status
                    WHERE workspace_id = :workspace_id
                    AND task = :task
                    AND trace_created_at >= fromUnixTimestamp64Micro(:created_after_micros, 'UTC')
                    AND updated_at >= fromUnixTimestamp64Micro(:lower_bound_micros, 'UTC')
                    ORDER BY workspace_id, project_id, task, trace_id, updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, task, trace_id
                )
                WHERE status = 'done'
                AND updated_at \\<= fromUnixTimestamp64Micro(:upper_bound_micros, 'UTC')
            ),
            usable AS (
                SELECT project_id, trace_id, task_version
                FROM (
                    SELECT project_id, trace_id, task_version, JSONExtractString(output, 'summary') AS summary
                    FROM ai_labels
                    WHERE workspace_id = :workspace_id
                    AND task = :task
                    AND key = 'output'
                    AND id_at >= (SELECT toStartOfSecond(min(UUIDv7ToDateTime(toUUID(trace_id), 'UTC'))) FROM done)
                    AND (project_id, trace_id) IN (SELECT project_id, trace_id FROM done)
                    ORDER BY workspace_id, project_id, task, trace_id, key, updated_at DESC, task_version DESC, output DESC
                    LIMIT 1 BY workspace_id, project_id, task, trace_id, key
                )
                WHERE match(summary, :non_blank)
            )
            SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, updated_at
            FROM done
            WHERE (project_id, trace_id, task_version) IN (SELECT project_id, trace_id, task_version FROM usable)
            AND (project_id, trace_id, task_version) NOT IN (
                SELECT project_id, trace_id, task_version
                FROM topic_vectors
                WHERE workspace_id = :workspace_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:created_after_micros, 'UTC')
            )
            ORDER BY updated_at, trace_id
            LIMIT :limit
            SETTINGS max_threads = 2, max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>,
                max_execution_time = <max_execution_time>, priority = 100, log_comment = '<log_comment>'
            """;

    /**
     * RE2 for "not {@link String#isBlank()}": a character that is not {@link Character#isWhitespace(char)}. That is
     * anything outside the ASCII controls tab to carriage return and file to unit separator and outside Unicode
     * separators ({@code \p{Z}}), except the no-break spaces, which Java does not count as whitespace. Bound as a
     * parameter so the backslashes need no escaping in the template.
     */
    static final String NON_BLANK = "[^\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\p{Z}]|[\\x{A0}\\x{2007}\\x{202F}]";

    /**
     * The summary read: each trace's latest output row, its text extracted in SQL exactly as stored. The (project,
     * trace) pairs keep the read on the sort key past {@code workspace_id}. The guards are the window read's.
     */
    private static final String FIND_SUMMARIES = """
            SELECT project_id, trace_id, task_version, JSONExtractString(output, 'summary') AS summary
            FROM ai_labels
            WHERE workspace_id = :workspace_id
            AND task = :task
            AND key = 'output'
            AND id_at >= toDateTime(:min_id_at_seconds, 'UTC')
            AND (project_id, trace_id) IN arrayZip(:project_ids, :trace_ids)
            ORDER BY workspace_id, project_id, task, trace_id, key, updated_at DESC, task_version DESC, output DESC
            LIMIT 1 BY workspace_id, project_id, task, trace_id, key
            SETTINGS max_threads = 2, max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>,
                max_execution_time = <max_execution_time>, priority = 100, log_comment = '<log_comment>'
            """;

    /**
     * The check after a workspace's inserts: the pairs that still have a status row of the task. The trace-delete
     * cascade deletes the status rows before the vectors, so a pair missing here belongs to a trace deleted while its
     * vector was embedded. The guards are the window read's.
     */
    private static final String FIND_STATUS_KEYS = """
            SELECT DISTINCT project_id, trace_id
            FROM ai_label_status
            WHERE workspace_id = :workspace_id
            AND task = :task
            AND id_at >= toDateTime(:min_id_at_seconds, 'UTC')
            AND (project_id, trace_id) IN arrayZip(:project_ids, :trace_ids)
            SETTINGS max_threads = 2, max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>,
                max_execution_time = <max_execution_time>, priority = 100, log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull JsonEachRowBulkInsert jsonBulkInsert;
    private final @NonNull AiLabelTableDeletes deletes;
    private final @NonNull @Config("topics") TopicsConfig config;

    public Mono<Long> insert(List<TopicVector> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Mono.just(0L);
        }
        return jsonBulkInsert.insert(
                TABLE,
                getLogComment("topics:insert_vectors", null, null, "rows=%d".formatted(rows.size())),
                rows,
                row -> {
                    var node = JsonUtils.createObjectNode();
                    node.put("workspace_id", row.workspaceId());
                    node.put("project_id", row.projectId().toString());
                    node.put("trace_id", row.traceId().toString());
                    node.put("trace_created_at", row.traceCreatedAt().toString());
                    node.put("task_version", row.taskVersion());
                    node.put("embed_model", row.embedModel());
                    node.put("embed_dimensions", row.embedDimensions());
                    node.put("text", row.text());
                    var vector = node.putArray("vector");
                    row.vector().forEach(vector::add);
                    return node;
                });
    }

    /**
     * Done statuses of {@code task} updated in {@code [lowerBound, upperBound]}, of traces created at or after
     * {@code createdAfter}, without a vector, oldest first.
     */
    public Flux<TopicVectorCandidate> findUnvectored(@NonNull String workspaceId, @NonNull String task,
            @NonNull Instant lowerBound, @NonNull Instant upperBound, @NonNull Instant createdAfter, int limit,
            long maxExecutionSeconds) {
        var find = config.getVectors().getFind();
        // The guards go into the SETTINGS clause, which ClickHouse can't take as bound parameters. The values are
        // validated config numbers, never input.
        var template = getSTWithLogComment(FIND_UNVECTORED, "topics:find_unvectored", workspaceId, null,
                "lower_bound=%s, created_after=%s, limit=%d".formatted(lowerBound, createdAfter, limit))
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", task)
                        .bind("created_after_micros", toEpochMicros(createdAfter))
                        .bind("lower_bound_micros", toEpochMicros(lowerBound))
                        .bind("upper_bound_micros", toEpochMicros(upperBound))
                        .bind("limit", limit)
                        .bind("non_blank", NON_BLANK)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toCandidate(row)));
    }

    /** Each key's latest output row of {@code task}, when it has one. */
    public Flux<TopicSummary> findSummaries(@NonNull String workspaceId, @NonNull String task,
            @NonNull Collection<TopicTraceKey> keys, long maxExecutionSeconds) {
        return findByKeys(FIND_SUMMARIES, "topics:find_summaries", workspaceId, task, keys, maxExecutionSeconds,
                row -> TopicSummary.builder()
                        .projectId(UUID.fromString(row.get("project_id", String.class)))
                        .traceId(UUID.fromString(row.get("trace_id", String.class)))
                        .taskVersion(row.get("task_version", Long.class).intValue())
                        .summary(row.get("summary", String.class))
                        .build());
    }

    /** The keys that still have a status row of {@code task}. */
    public Flux<TopicTraceKey> findStatusKeys(@NonNull String workspaceId, @NonNull String task,
            @NonNull Collection<TopicTraceKey> keys, long maxExecutionSeconds) {
        return findByKeys(FIND_STATUS_KEYS, "topics:find_status_keys", workspaceId, task, keys, maxExecutionSeconds,
                row -> TopicTraceKey.builder()
                        .projectId(UUID.fromString(row.get("project_id", String.class)))
                        .traceId(UUID.fromString(row.get("trace_id", String.class)))
                        .build());
    }

    private <T> Flux<T> findByKeys(String query, String queryName, String workspaceId, String task,
            Collection<TopicTraceKey> keys, long maxExecutionSeconds, Function<Row, T> toItem) {
        if (keys.isEmpty()) {
            return Flux.empty();
        }
        var find = config.getVectors().getFind();
        // id_at is derived from the UUIDv7 trace id, so the oldest id bounds the partitions read.
        long minIdAtSeconds = keys.stream().mapToLong(key -> key.traceId().getMostSignificantBits() >>> 16).min()
                .orElseThrow() / 1000;
        var template = getSTWithLogComment(query, queryName, workspaceId, null, "keys=%d".formatted(keys.size()))
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
        var projectIds = keys.stream().map(key -> key.projectId().toString()).toArray(String[]::new);
        var traceIds = keys.stream().map(key -> key.traceId().toString()).toArray(String[]::new);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", task)
                        .bind("min_id_at_seconds", minIdAtSeconds)
                        .bind("project_ids", projectIds)
                        .bind("trace_ids", traceIds)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toItem.apply(row)));
    }

    public Mono<Long> deleteForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        return deletes.deleteForRetention(TABLE, workspaceIds, cutoffId, lowerBound);
    }

    public Mono<Long> deleteForRetentionBounded(@NonNull Map<String, UUID> workspaceMinIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        return deletes.deleteForRetentionBounded(TABLE, workspaceMinIds, cutoffId, lowerBound);
    }

    /** The trace-delete cascade; workspace from the reactive context. */
    public Mono<Void> deleteByTraceIds(@NonNull Set<UUID> traceIds, @NonNull UUID projectId) {
        return deletes.deleteByTraceIds(TABLE, traceIds, projectId);
    }

    /** The vectors of {@code keys}, as the system user: the tick's own deletes, outside any request. */
    public Mono<Void> deleteByKeys(@NonNull String workspaceId, @NonNull Collection<TopicTraceKey> keys) {
        var byProject = keys.stream().collect(Collectors.groupingBy(TopicTraceKey::projectId,
                Collectors.mapping(TopicTraceKey::traceId, Collectors.toSet())));
        return Flux.fromIterable(byProject.entrySet())
                .concatMap(entry -> deleteByTraceIds(entry.getValue(), entry.getKey()))
                .then()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }

    private static TopicVectorCandidate toCandidate(Row row) {
        return TopicVectorCandidate.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(UUID.fromString(row.get("project_id", String.class)))
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .taskVersion(row.get("task_version", Long.class).intValue())
                .statusUpdatedAt(row.get("updated_at", Instant.class))
                .build();
    }

    /** Rounded down, so the window's lower bound never drops a row written in its first microsecond. */
    private static long toEpochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }
}
