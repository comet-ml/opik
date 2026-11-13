package com.comet.opik.domain.topics;

import com.comet.opik.api.Source;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.time.LocalDate;
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
 * batched inserts; {@code last_updated_at} is stamped by ClickHouse. A reader takes each trace's latest row first and
 * then drops it when that row is a tombstone ({@code is_deleted = 1}), as reads of {@code traces_local_v2} do. The
 * first build only writes: the read API and topic on arrival read the table.
 *
 * <p>The deletes are copies of {@link TopicVectorsDAO}'s (same keys, same partitioning), so a trace's topic goes with
 * the trace exactly as its vector does: by retention and by the trace-delete cascade.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TraceTopicsDAO {

    static final String TABLE = "trace_topics";

    /**
     * The Logs page's default filters, which the read API applies to the traces it counts: {@code source = sdk} (with
     * the {@code unknown} rows from before sources, {@link Source#legacyFallbackDbValue}) and
     * {@code visibility_mode = default}. Bound as {@code :logs_sources} and {@code :logs_visibility_mode}.
     */
    private static final String[] LOGS_SOURCES = {Source.SDK.getValue(), Source.UNKNOWN_VALUE};
    private static final String LOGS_VISIBILITY_MODE = VisibilityMode.DEFAULT.getValue();

    /**
     * Traces per topic and UTC day among the project's traces created in the window that Logs lists, each by its
     * latest row. Topics are built from every labelled trace, but the counts take only the traces Logs lists
     * ({@link #LOGS_SOURCES}), so a topic's count is the number of traces Logs lists for it; any version of a trace
     * that matches keeps it, as in the traces list. Every row counts, run rows and arrival rows. The latest row is the
     * greatest (last_updated_at, run_id). A trace whose latest row is a tombstone is left out ({@code HAVING}).
     */
    private static final String COUNT_BY_TOPIC_AND_DAY = """
            SELECT latest_topic AS topic_id, toDate(created_at, 'UTC') AS day, count() AS traces
            FROM (
                SELECT argMax(topic_id, (last_updated_at, run_id)) AS latest_topic, any(trace_created_at) AS created_at
                FROM trace_topics
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                AND trace_id IN (
                    SELECT id
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                    AND created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                    AND source IN :logs_sources
                    AND visibility_mode = :logs_visibility_mode
                )
                GROUP BY trace_id
                HAVING argMax(is_deleted, (last_updated_at, run_id)) = 0
            )
            GROUP BY topic_id, day
            SETTINGS max_threads = 2, max_execution_time = <max_execution_time>, max_memory_usage = <max_memory_usage>,
                max_rows_to_read = <max_rows_to_read>, log_comment = '<log_comment>'
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
    private final @NonNull @Config("topics") TopicsConfig config;

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

    /**
     * Per topic id ({@link TraceTopic#OTHER} for Other) and UTC day of {@code trace_created_at}, the project's traces
     * created in {@code [from, to)}. A trace counts by its latest row. Rows and memory are capped by the vectors job's
     * window read guards ({@code topics.vectors.find}), time by {@code maxExecutionSeconds}.
     */
    public Flux<TopicDayCount> countByTopicAndDay(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to, long maxExecutionSeconds) {
        var find = config.getVectors().getFind();
        var template = getSTWithLogComment(COUNT_BY_TOPIC_AND_DAY, "topics:count_by_topic_and_day", workspaceId, null,
                "project_id=%s, from=%s, to=%s".formatted(projectId, from, to))
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .bind("logs_sources", LOGS_SOURCES)
                        .bind("logs_visibility_mode", LOGS_VISIBILITY_MODE)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> TopicDayCount.builder()
                        .topicId(UUID.fromString(row.get("topic_id", String.class)))
                        .day(row.get("day", LocalDate.class))
                        .traces(((Number) row.get("traces", Object.class)).longValue())
                        .build()));
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

    private static long toEpochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }

    /** One row of {@link #countByTopicAndDay}. */
    @Builder(toBuilder = true)
    public record TopicDayCount(@NonNull UUID topicId, @NonNull LocalDate day, long traces) {
    }
}
