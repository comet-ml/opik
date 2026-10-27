package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.ANALYTICS_DELETE_BATCH_SIZE;
import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * {@code topic_vectors}: one row per (trace, task version). Writes are plain batched inserts; {@code updated_at} is
 * stamped by ClickHouse. It reads ai-labels' {@code ai_label_status} and {@code ai_labels} and never writes them.
 *
 * <p>The deletes are copies of {@code AiLabelTableDeletes}' (same keys, same partitioning), so the vectors go with their
 * traces exactly as the labels do: by retention and by the trace-delete cascade.
 */
@Slf4j
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
            )
            SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, updated_at
            FROM done
            WHERE (project_id, trace_id, task_version) NOT IN (
                SELECT project_id, trace_id, task_version
                FROM topic_vectors
                WHERE workspace_id = :workspace_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:created_after_micros, 'UTC')
                AND trace_id IN (SELECT trace_id FROM done)
            )
            ORDER BY updated_at, trace_id
            LIMIT :limit
            SETTINGS max_threads = 2, max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>,
                max_execution_time = <max_execution_time>, priority = 100, log_comment = '<log_comment>'
            """;

    /** The runbook's summary read: each trace's latest output row, its text extracted in SQL exactly as stored. */
    private static final String FIND_SUMMARIES = """
            SELECT trace_id, task_version, JSONExtractString(output, 'summary') AS summary
            FROM ai_labels
            WHERE workspace_id = :workspace_id
            AND task = :task
            AND key = 'output'
            AND trace_id IN :trace_ids
            ORDER BY workspace_id, project_id, task, trace_id, key, updated_at DESC
            LIMIT 1 BY workspace_id, project_id, task, trace_id, key
            SETTINGS log_comment = '<log_comment>', max_execution_time = <max_execution_time>
            """;

    private static final String FIND_BY_TRACE_IDS = """
            SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, embed_model, embed_dimensions,
                text, vector
            FROM topic_vectors
            WHERE workspace_id = :workspace_id
            AND trace_id IN :trace_ids
            ORDER BY workspace_id, project_id, trace_id, task_version, updated_at DESC
            LIMIT 1 BY workspace_id, project_id, trace_id, task_version
            SETTINGS log_comment = '<log_comment>'
            """;

    /** As {@code AiLabelTableDeletes.DELETE_FOR_RETENTION}: {@code TraceDAO}'s, on {@code trace_id}. */
    private static final String DELETE_FOR_RETENTION = """
            DELETE FROM topic_vectors
            WHERE workspace_id IN :workspace_ids
            AND trace_id >= :lower_bound
            AND trace_id \\< :cutoff_id
            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                >= (toDate32(UUIDv7ToDateTime(toUUID(:lower_bound), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:lower_bound), 'UTC'), 1)))
            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                \\< addWeeks(toDate32(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC'), 1)), 1)
            AND trace_id NOT IN (
                SELECT trace_id FROM experiment_items
                WHERE workspace_id IN :workspace_ids
                AND trace_id >= :lower_bound
                AND trace_id \\< :cutoff_id
            )
            SETTINGS log_comment = '<log_comment>', lightweight_deletes_sync = 1, allow_nondeterministic_mutations = 1
            ;
            """;

    /** As {@code AiLabelTableDeletes.DELETE_FOR_RETENTION_BOUNDED}: each workspace with its own floor. */
    private static final String DELETE_FOR_RETENTION_BOUNDED = """
            DELETE FROM topic_vectors
            WHERE (
                <items:{item |
                    (workspace_id = :ws_<item.index> AND trace_id >= :lb_<item.index> AND trace_id \\< :cutoff_id)
                    <if(item.hasNext)>OR<endif>
                }>
            )
            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                >= (toDate32(UUIDv7ToDateTime(toUUID(:min_lower_bound), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:min_lower_bound), 'UTC'), 1)))
            AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                \\< addWeeks(toDate32(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC'), 1)), 1)
            AND trace_id NOT IN (
                SELECT trace_id FROM experiment_items
                WHERE workspace_id IN :workspace_ids_flat
                AND trace_id >= :min_lower_bound
                AND trace_id \\< :cutoff_id
            )
            SETTINGS log_comment = '<log_comment>', lightweight_deletes_sync = 1, allow_nondeterministic_mutations = 1
            ;
            """;

    /**
     * As {@code AiLabelTableDeletes.DELETE_BY_TRACE_IDS}: {@code IN PARTITION} scopes the parts the mutation visits.
     * {@code <partition>} is always a {@code long} from {@link WeeklyPartitions}.
     */
    private static final String DELETE_BY_TRACE_IDS = """
            DELETE FROM topic_vectors
            <if(partition)>IN PARTITION <partition><endif>
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_id IN :trace_ids
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull JsonEachRowBulkInsert jsonBulkInsert;
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
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toCandidate(row)));
    }

    public Flux<TopicSummary> findSummaries(@NonNull String workspaceId, @NonNull String task,
            @NonNull Set<UUID> traceIds, long maxExecutionSeconds) {
        if (traceIds.isEmpty()) {
            return Flux.empty();
        }
        var template = getSTWithLogComment(FIND_SUMMARIES, "topics:find_summaries", workspaceId, null,
                "trace_ids=%d".formatted(traceIds.size()))
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", task)
                        .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> TopicSummary.builder()
                        .traceId(UUID.fromString(row.get("trace_id", String.class)))
                        .taskVersion(row.get("task_version", Long.class).intValue())
                        .summary(row.get("summary", String.class))
                        .build()));
    }

    public Flux<TopicVector> findByTraceIds(@NonNull String workspaceId, @NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Flux.empty();
        }
        var template = getSTWithLogComment(FIND_BY_TRACE_IDS, "topics:find_vectors", workspaceId, null,
                "trace_ids=%d".formatted(traceIds.size()));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toVector(row)));
    }

    public Mono<Long> deleteForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(workspaceIds),
                "Argument 'workspaceIds' must not be empty");
        log.info("Retention delete topic vectors, workspaces '{}', cutoffId '{}', lowerBound '{}'",
                workspaceIds.size(), cutoffId, lowerBound);

        var template = getSTWithLogComment(DELETE_FOR_RETENTION, "topics:retention_delete_" + TABLE, null, "",
                "workspaces_size=%s, cutoff_id=%s, lower_bound=%s".formatted(workspaceIds.size(), cutoffId,
                        lowerBound));

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> Mono.from(connection.createStatement(template.render())
                        .bind("workspace_ids", workspaceIds.toArray(String[]::new))
                        .bind("cutoff_id", cutoffId)
                        .bind("lower_bound", lowerBound)
                        .execute()))
                .flatMap(result -> Mono.from(result.getRowsUpdated()));
    }

    public Mono<Long> deleteForRetentionBounded(@NonNull Map<String, UUID> workspaceMinIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        Preconditions.checkArgument(!workspaceMinIds.isEmpty(), "Argument 'workspaceMinIds' must not be empty");
        log.info("Retention delete topic vectors (bounded), workspaces '{}', cutoffId '{}'", workspaceMinIds.size(),
                cutoffId);

        var entries = List.copyOf(workspaceMinIds.entrySet());
        var template = getSTWithLogComment(DELETE_FOR_RETENTION_BOUNDED,
                "topics:retention_delete_bounded_" + TABLE, null, "",
                "workspaces_size=%s, cutoff_id=%s, min_lower_bound=%s".formatted(workspaceMinIds.size(), cutoffId,
                        lowerBound))
                .add("items", getQueryItemPlaceHolder(entries.size()));

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("cutoff_id", cutoffId)
                            .bind("workspace_ids_flat", workspaceMinIds.keySet().toArray(String[]::new))
                            .bind("min_lower_bound", lowerBound);
                    for (int i = 0; i < entries.size(); i++) {
                        statement.bind("ws_" + i, entries.get(i).getKey());
                        statement.bind("lb_" + i, entries.get(i).getValue());
                    }
                    return Mono.from(statement.execute());
                })
                .flatMap(result -> Mono.from(result.getRowsUpdated()));
    }

    /**
     * The trace-delete cascade; workspace from the reactive context. One statement per batch and weekly partition, or a
     * single unscoped statement per batch when a trace id's partition cannot be derived exactly.
     */
    public Mono<Void> deleteByTraceIds(@NonNull Set<UUID> traceIds, @NonNull UUID projectId) {
        if (traceIds.isEmpty()) {
            return Mono.empty();
        }
        return makeMonoContextAware((userName, workspaceId) -> Flux
                .fromIterable(Lists.partition(List.copyOf(traceIds), ANALYTICS_DELETE_BATCH_SIZE))
                .concatMap(batch -> WeeklyPartitions.groupByPartition(batch)
                        .map(grouped -> Flux.fromIterable(grouped.entrySet())
                                .concatMap(entry -> deleteByTraceIds(entry.getValue(), entry.getKey(), projectId,
                                        workspaceId, userName)))
                        .orElseGet(() -> Flux.from(deleteByTraceIds(batch, null, projectId, workspaceId,
                                userName))))
                .then());
    }

    private Mono<Void> deleteByTraceIds(Collection<UUID> traceIds, Long partition, UUID projectId,
            String workspaceId, String userName) {
        var ids = traceIds.stream().map(UUID::toString).toArray(String[]::new);
        var template = getSTWithLogComment(DELETE_BY_TRACE_IDS, "topics:delete_by_trace_ids_" + TABLE, workspaceId,
                userName, "trace_ids=%s, partition=%s".formatted(ids.length, partition));
        if (partition != null) {
            template.add("partition", partition);
        }

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> Mono.from(connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("trace_ids", ids)
                        .execute()))
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .then();
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

    private static TopicVector toVector(Row row) {
        // The driver's Java array type for Array(Float32) isn't part of its contract, so read the elements as numbers.
        var array = row.get("vector", Object.class);
        var vector = new ArrayList<Float>(Array.getLength(array));
        for (int i = 0; i < Array.getLength(array); i++) {
            vector.add(((Number) Array.get(array, i)).floatValue());
        }
        return TopicVector.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(UUID.fromString(row.get("project_id", String.class)))
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .taskVersion(row.get("task_version", Long.class).intValue())
                .embedModel(row.get("embed_model", String.class))
                .embedDimensions(row.get("embed_dimensions", Integer.class))
                .text(row.get("text", String.class))
                .vector(vector)
                .build();
    }

    /** Rounded down, so the window's lower bound never drops a row written in its first microsecond. */
    private static long toEpochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }
}
