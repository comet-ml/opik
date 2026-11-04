package com.comet.opik.domain.topics;

import com.comet.opik.api.Source;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * The latest row of every trace of the project. run_id is a UUID v7 string, so on an updated_at tie its text order
     * puts the later run first.
     */
    private static final String FIND_BY_PROJECT = """
            SELECT workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id
            FROM trace_topics
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            ORDER BY workspace_id, project_id, trace_id, updated_at DESC, run_id DESC
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
                ORDER BY workspace_id, project_id, trace_id, updated_at DESC, run_id DESC
                LIMIT 1 BY workspace_id, project_id, trace_id
            )
            GROUP BY topic_id
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * The ids of the project's traces created in the window that the Logs page lists: its default filters,
     * {@code source = sdk} (with the {@code unknown} rows from before sources, {@link Source#legacyFallbackDbValue})
     * and {@code visibility_mode = default}. Topics are built from every labelled trace, but the read API and a saved
     * run's counts count only these, so a topic's count is the number of traces Logs lists for it. Any version of a
     * trace that matches keeps it, as in the traces list.
     */
    private static final String LOGS_TRACE_IDS = """
            SELECT id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
            AND created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
            AND source IN ('%s', '%s')
            AND visibility_mode = '%s'
            """.formatted(Source.SDK.getValue(), Source.UNKNOWN_VALUE, VisibilityMode.DEFAULT.getValue());

    /**
     * Traces per topic and UTC day among the project's traces created in the window that Logs lists
     * ({@link #LOGS_TRACE_IDS}), each by its latest row. Rows of runs that did not save are skipped before the dedupe,
     * so their trace counts by its earlier row. The latest row is the greatest (updated_at, run_id), as ORDER BY ...
     * LIMIT 1 BY picks it; argMax ran 4 times faster on unmerged parts, in a sixth of the memory.
     */
    private static final String COUNT_BY_TOPIC_AND_DAY = """
            SELECT latest_topic AS topic_id, toDate(created_at, 'UTC') AS day, count() AS traces
            FROM (
                SELECT argMax(topic_id, (updated_at, run_id)) AS latest_topic, any(trace_created_at) AS created_at
                FROM trace_topics
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
                AND (source = 'arrival' <if(saved_run_ids)> OR run_id IN :saved_run_ids <endif>)
                AND trace_id IN (""" + LOGS_TRACE_IDS + """
                )
                GROUP BY trace_id
            )
            GROUP BY topic_id, day
            SETTINGS max_threads = 2, max_execution_time = <max_execution_time>, max_memory_usage = <max_memory_usage>,
                max_rows_to_read = <max_rows_to_read>, log_comment = '<log_comment>'
            """;

    /** {@link #LOGS_TRACE_IDS}, read for a night's counts. */
    private static final String FIND_LOGS_TRACE_IDS = LOGS_TRACE_IDS + """
            SETTINGS max_threads = 2, max_execution_time = <max_execution_time>, max_memory_usage = <max_memory_usage>,
                max_rows_to_read = <max_rows_to_read>, log_comment = '<log_comment>'
            """;

    /**
     * The ids of the project's traces whose topic matches {@code <topic_filters>}, the TOPICS filter strategy on
     * {@code topic}: the traces filter by topic. A trace's topic is its latest arrival row or row of a saved run, as in
     * {@link #COUNT_BY_TOPIC_AND_DAY}, and a topic that is no longer live is Other. A trace with no such row has no
     * topic, so no operator keeps it, {@code !=} and {@code not_in} included. The search's time window and cursor bound
     * the read to the trace ids the search can return; they never split a trace's rows, which all share its id.
     */
    private static final String TRACE_IDS_BY_TOPIC = """
            SELECT trace_id
            FROM (
                SELECT trace_id, argMax(topic_id, (updated_at, run_id)) AS latest_topic,
                    if(latest_topic IN :topic_live_ids, latest_topic, '00000000-0000-0000-0000-000000000000') AS topic
                FROM trace_topics
                WHERE workspace_id = :workspace_id
                AND project_id = :topic_project_id
                <if(uuid_from_time)> AND trace_id >= :uuid_from_time
                    AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                        >= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'), 1))) <endif>
                <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time
                    AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                        \\<= (toDate32(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'), 1))) <endif>
                <if(last_received_id)> AND trace_id \\< :last_received_id
                    AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1)))
                        \\<= (toDate32(UUIDv7ToDateTime(toUUID(:last_received_id), 'UTC')) - toIntervalDay(toDayOfWeek(UUIDv7ToDateTime(toUUID(:last_received_id), 'UTC'), 1))) <endif>
                AND (source = 'arrival' <if(saved_run_ids)> OR run_id IN :topic_saved_run_ids <endif>)
                GROUP BY trace_id
            )
            WHERE <topic_filters>
            """;

    /** The latest topic of each of the project's traces created in the window. */
    private static final String FIND_LABELS = """
            SELECT trace_id, topic_id
            FROM trace_topics
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
            AND trace_created_at \\< fromUnixTimestamp64Micro(:to_micros, 'UTC')
            AND run_id = :run_id
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

    /** The rows one run wrote: a run whose MySQL save failed takes them back. */
    private static final String DELETE_BY_RUN_ID = """
            DELETE FROM trace_topics
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND run_id = :run_id
            SETTINGS log_comment = '<log_comment>'
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

    public Mono<Long> deleteByRunId(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID runId) {
        var template = getSTWithLogComment(DELETE_BY_RUN_ID, "topics:delete_run_trace_topics", workspaceId, null,
                "project_id=%s, run_id=%s".formatted(projectId, runId));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("run_id", runId.toString())
                        .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .reduce(0L, Long::sum);
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

    /**
     * Per topic id ({@link TraceTopic#OTHER} for Other) and UTC day of {@code trace_created_at}, the project's traces
     * created in {@code [from, to)}. A trace counts by its latest arrival row or row of a run in {@code savedRunIds}.
     * Under the window read's guards.
     */
    public Flux<TopicDayCount> countByTopicAndDay(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to, @NonNull Collection<UUID> savedRunIds,
            long maxExecutionSeconds) {
        var template = TopicsQueryGuards.windowRead(getSTWithLogComment(COUNT_BY_TOPIC_AND_DAY,
                "topics:count_by_topic_and_day", workspaceId, null,
                "project_id=%s, from=%s, to=%s, saved_runs=%d".formatted(projectId, from, to, savedRunIds.size())),
                config, maxExecutionSeconds);
        if (!savedRunIds.isEmpty()) {
            template.add("saved_run_ids", true);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("project_id", projectId.toString())
                            .bind("from_micros", toEpochMicros(from))
                            .bind("to_micros", toEpochMicros(to));
                    if (!savedRunIds.isEmpty()) {
                        statement.bind("saved_run_ids",
                                savedRunIds.stream().map(UUID::toString).toArray(String[]::new));
                    }
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, metadata) -> TopicDayCount.builder()
                        .topicId(UUID.fromString(row.get("topic_id", String.class)))
                        .day(row.get("day", LocalDate.class))
                        .traces(((Number) row.get("traces", Object.class)).longValue())
                        .build()));
    }

    /**
     * The project's traces created in {@code [from, to)} that the Logs page lists ({@link #LOGS_TRACE_IDS}), for a
     * saved run's counts. Under the window read's guards.
     */
    public Mono<Set<UUID>> findLogsTraceIds(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to, long maxExecutionSeconds) {
        var template = TopicsQueryGuards.windowRead(getSTWithLogComment(FIND_LOGS_TRACE_IDS,
                "topics:find_logs_trace_ids", workspaceId, null,
                "project_id=%s, from=%s, to=%s".formatted(projectId, from, to)), config, maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> UUID.fromString(row.get("id", String.class))))
                .collect(Collectors.toSet());
    }

    /**
     * {@link #TRACE_IDS_BY_TOPIC} for a trace search with a filter by topic, to bind with {@link #bindTraceIdsByTopic}.
     * The search's {@code topicScope} must be set: the trace service reads it from MySQL before the search.
     */
    public static String traceIdsByTopic(@NonNull String topicFilters, @NonNull TraceSearchCriteria criteria) {
        var scope = scopeOf(criteria);
        var template = TemplateUtils.newST(TRACE_IDS_BY_TOPIC).add("topic_filters", topicFilters);
        if (!scope.savedRunIds().isEmpty()) {
            template.add("saved_run_ids", true);
        }
        Optional.ofNullable(criteria.uuidFromTime()).ifPresent(id -> template.add("uuid_from_time", true));
        Optional.ofNullable(criteria.uuidToTime()).ifPresent(id -> template.add("uuid_to_time", true));
        Optional.ofNullable(criteria.lastReceivedId()).ifPresent(id -> template.add("last_received_id", true));
        return template.render();
    }

    /**
     * Binds the values {@link #traceIdsByTopic} adds. The workspace, the time window and the cursor are bound by the
     * search itself.
     */
    public static void bindTraceIdsByTopic(@NonNull Statement statement, @NonNull TraceSearchCriteria criteria) {
        var scope = scopeOf(criteria);
        statement.bind("topic_project_id", criteria.projectId().toString())
                .bind("topic_live_ids", scope.liveIds().toArray(String[]::new));
        if (!scope.savedRunIds().isEmpty()) {
            statement.bind("topic_saved_run_ids", scope.savedRunIdStrings().toArray(String[]::new));
        }
    }

    private static TopicReadScope scopeOf(TraceSearchCriteria criteria) {
        Preconditions.checkState(criteria.projectId() != null && criteria.topicScope() != null,
                "A trace search filtered by topic needs its project and the project's topic scope");
        return criteria.topicScope();
    }

    /**
     * Per trace created in {@code [from, to)}, its topic id in run {@code runId} ({@link TraceTopic#OTHER} for Other).
     * Only that run's rows: a later row of another run (one that failed after its insert) must not count.
     */
    public Mono<Map<UUID, UUID>> findLabels(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull Instant from, @NonNull Instant to, @NonNull UUID runId) {
        var template = getSTWithLogComment(FIND_LABELS, "topics:find_labels", workspaceId, null,
                "project_id=%s, from=%s, to=%s, run_id=%s".formatted(projectId, from, to, runId));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("from_micros", toEpochMicros(from))
                        .bind("to_micros", toEpochMicros(to))
                        .bind("run_id", runId.toString())
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

    @Builder(toBuilder = true)
    public record TopicDayCount(@NonNull UUID topicId, @NonNull LocalDate day, long traces) {
    }
}
