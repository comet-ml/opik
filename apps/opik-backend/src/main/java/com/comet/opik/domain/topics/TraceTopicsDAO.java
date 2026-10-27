package com.comet.opik.domain.topics;

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

import java.time.Instant;
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
 * {@code trace_topics}: the topic of each trace, one row per assignment, the latest per trace wins. Writes are plain
 * batched inserts; {@code updated_at} is stamped by ClickHouse.
 *
 * <p>The deletes are copies of {@link TopicVectorsDAO}'s (same keys, same partitioning), so a trace's topic goes with
 * the trace exactly as its vector does: by retention and by the trace-delete cascade.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TraceTopicsDAO {

    static final String TABLE = "trace_topics";

    /** The latest row of every trace of the project. */
    private static final String FIND_BY_PROJECT = """
            SELECT workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id
            FROM trace_topics
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            ORDER BY workspace_id, project_id, trace_id, updated_at DESC
            LIMIT 1 BY workspace_id, project_id, trace_id
            SETTINGS log_comment = '<log_comment>'
            """;

    /** Traces per topic among the project's traces created in the window, each by its latest row. */
    private static final String COUNT_BY_TOPIC = """
            SELECT topic_id, count() AS traces
            FROM (
                SELECT trace_id, topic_id
                FROM trace_topics
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                ORDER BY workspace_id, project_id, trace_id, updated_at DESC
                LIMIT 1 BY workspace_id, project_id, trace_id
            )
            GROUP BY topic_id
            SETTINGS log_comment = '<log_comment>'
            """;

    /** The latest topic of each of the project's traces created in the window. */
    private static final String FIND_LABELS = """
            SELECT trace_id, topic_id
            FROM trace_topics
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
            AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
            ORDER BY workspace_id, project_id, trace_id, updated_at DESC
            LIMIT 1 BY workspace_id, project_id, trace_id
            SETTINGS log_comment = '<log_comment>'
            """;

    private static final String DELETE_FOR_RETENTION = """
            DELETE FROM trace_topics
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

    private static final String DELETE_FOR_RETENTION_BOUNDED = """
            DELETE FROM trace_topics
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

    private static final String DELETE_BY_TRACE_IDS = """
            DELETE FROM trace_topics
            <if(partition)>IN PARTITION <partition><endif>
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_id IN :trace_ids
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull JsonEachRowBulkInsert jsonBulkInsert;

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
                    node.put("run_id", row.runId());
                    return node;
                });
    }

    public Flux<TraceTopic> findByProject(@NonNull String workspaceId, @NonNull UUID projectId) {
        var template = getSTWithLogComment(FIND_BY_PROJECT, "topics:find_trace_topics", workspaceId, null,
                "project_id=%s".formatted(projectId));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toTraceTopic(row)));
    }

    /** Per topic id ({@link TraceTopic#OTHER} for Other), the project's traces created in {@code [from, to)}. */
    public Mono<Map<UUID, Long>> countByTopic(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to) {
        var template = getSTWithLogComment(COUNT_BY_TOPIC, "topics:count_by_topic", workspaceId, null,
                "project_id=%s, from=%s, to=%s".formatted(projectId, from, to));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(
                        UUID.fromString(row.get("topic_id", String.class)),
                        ((Number) row.get("traces", Object.class)).longValue())))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /** Per trace created in {@code [from, to)}, its latest topic id ({@link TraceTopic#OTHER} for Other). */
    public Mono<Map<UUID, UUID>> findLabels(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to) {
        var template = getSTWithLogComment(FIND_LABELS, "topics:find_labels", workspaceId, null,
                "project_id=%s, from=%s, to=%s".formatted(projectId, from, to));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(
                        UUID.fromString(row.get("trace_id", String.class)),
                        UUID.fromString(row.get("topic_id", String.class)))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    public Mono<Long> deleteForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(workspaceIds),
                "Argument 'workspaceIds' must not be empty");
        log.info("Retention delete trace topics, workspaces '{}', cutoffId '{}', lowerBound '{}'",
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
        log.info("Retention delete trace topics (bounded), workspaces '{}', cutoffId '{}'", workspaceMinIds.size(),
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

    private static TraceTopic toTraceTopic(Row row) {
        return TraceTopic.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(UUID.fromString(row.get("project_id", String.class)))
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .topicId(UUID.fromString(row.get("topic_id", String.class)))
                .source(TraceTopic.Source.valueOf(row.get("source", String.class).toUpperCase()))
                .runId(row.get("run_id", String.class))
                .build();
    }

    private static long toEpochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }
}
