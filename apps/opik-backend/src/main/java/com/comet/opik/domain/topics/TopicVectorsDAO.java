package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.WeeklyPartitions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
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

import static com.comet.opik.infrastructure.FilterUtils.ANALYTICS_DELETE_BATCH_SIZE;
import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * {@code topic_vectors}: one row per (trace, task version). Writes are plain batched inserts; {@code last_updated_at}
 * is stamped by ClickHouse. Reads drop a key whose latest row has {@code is_deleted = 1}. It reads ai-labels'
 * {@code ai_label_status} and {@code ai_labels} and never writes them.
 *
 * <p>The deletes are this DAO's own, in {@code TraceDAO}'s shape on {@code trace_id}: the table has the trace's weekly
 * partition, so the vectors go with their traces, by retention and by the trace-delete cascade.
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
     *
     * <p>A key is kept only when its latest {@code ai_labels} output row has the status's task version and a summary
     * that isn't blank, and this is checked before the {@code LIMIT}: otherwise unusable rows, which stay in the window
     * until they age out, could fill every tick's batch and hold back newer traces. The output read takes only the
     * (project, trace) pairs of {@code done}, so it reads the window's traces and not the workspace's history, and only
     * from the {@code id_at} of {@code done}'s oldest trace id on, so it skips the older partitions and granules.
     * {@code id_at} is the id's time cut to whole seconds, so the bound is cut the same way: at the id's millisecond time,
     * the oldest trace's own row would fall under it. A tie on {@code updated_at} takes the higher task version, then the
     * greater output, so the pick doesn't change between runs. The {@code topic_vectors} side takes the same bound: the
     * keys it is compared with come from {@code done}. A key there counts only when its latest row is not a tombstone
     * ({@code is_deleted = 0} after the dedup), so a soft-deleted vector is made again while its trace is still done.
     *
     * <p>{@code done} itself has no {@code id_at} bound: the ai-labels sweep selects traces by {@code created_at}, and a
     * trace whose id carries an older time (an import, a client-made id) still gets a status, so a bound derived from the
     * window would drop it. For the same reason the {@code id_at} bound above depends on the data: one such trace in
     * {@code done} widens the output and vector reads back to its week.
     *
     * <p>ClickHouse does not materialize a CTE, so {@code done} is read three times: for the {@code id_at} bound (a scalar,
     * computed once and reused by both sides), for the {@code IN} set and for the outer {@code FROM}. Measured on
     * ClickHouse 26.3 with the real DDL and a synthetic 2.7M-trace workspace with 180 days of history, the 24h window
     * reads 1.1M rows, about 300k per {@code done} read; without the {@code id_at} bound the output read alone costs more
     * than the third read saves. Reading {@code done} once into Java and binding its keys back in 1,000-key chunks read
     * more (1.8M rows in 16 queries for 24h, 17M rows in 101 queries for 7d), because each chunk's ids are spread over the
     * workspace's granules.
     *
     * <p>Three optional scopes narrow every side alike, so the anti-join stays exact. One project: the read then follows
     * the tables' sort keys, which have {@code project_id} right after {@code workspace_id}. Excluded projects, on
     * {@code done} only: the other sides are joined to its keys. A slice, {@code trace_created_at < :created_before}:
     * {@code trace_created_at} is the same on every row of a key, so half-open slices put each key in exactly one of
     * them; a slice also bounds {@code id_at} from above by {@code done}'s newest trace id, so an old slice does not
     * read the outputs and vectors of every newer trace.
     */
    private static final String FIND_UNVECTORED = """
            WITH done AS (
                SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, updated_at
                FROM (
                    SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, status, updated_at
                    FROM ai_label_status
                    WHERE workspace_id = :workspace_id
                    <if(project)>AND project_id = :project_id<endif>
                    <if(excluded)>AND project_id NOT IN :undrained_project_ids<endif>
                    AND task = :task
                    AND trace_created_at >= fromUnixTimestamp64Micro(:created_after_micros, 'UTC')
                    <if(created_before)>AND trace_created_at \\< fromUnixTimestamp64Micro(:created_before_micros, 'UTC')<endif>
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
                    <if(project)>AND project_id = :project_id<endif>
                    AND task = :task
                    AND key = 'output'
                    AND id_at >= (SELECT toStartOfSecond(min(UUIDv7ToDateTime(toUUID(trace_id), 'UTC'))) FROM done)
                    <if(created_before)>AND id_at \\<= (SELECT max(UUIDv7ToDateTime(toUUID(trace_id), 'UTC')) FROM done)<endif>
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
                FROM (
                    SELECT workspace_id, project_id, trace_id, task_version, is_deleted
                    FROM topic_vectors
                    WHERE workspace_id = :workspace_id
                    <if(project)>AND project_id = :project_id<endif>
                    AND trace_created_at >= fromUnixTimestamp64Micro(:created_after_micros, 'UTC')
                    <if(created_before)>AND trace_created_at \\< fromUnixTimestamp64Micro(:created_before_micros, 'UTC')<endif>
                    AND id_at >= (SELECT toStartOfSecond(min(UUIDv7ToDateTime(toUUID(trace_id), 'UTC'))) FROM done)
                    <if(created_before)>AND id_at \\<= (SELECT max(UUIDv7ToDateTime(toUUID(trace_id), 'UTC')) FROM done)<endif>
                    ORDER BY workspace_id, project_id, trace_id, task_version, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, trace_id, task_version
                )
                WHERE is_deleted = 0
            )
            ORDER BY updated_at, trace_id
            LIMIT :limit
            SETTINGS max_threads = 2, max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>,
                max_execution_time = <max_execution_time>, priority = 100, log_comment = '<log_comment>'
            """;

    /**
     * RE2 for "not blank" as opik-inference counts it: a character that Python's {@code str.strip()} keeps. That is
     * anything outside the ASCII controls tab to carriage return and file to unit separator, U+0085 and the Unicode
     * separators ({@code \p{Z}}, the no-break spaces included). The service rejects an input that strips to nothing,
     * so such a summary must never reach an embed call. {@code TopicVectorsTickService.isBlankForEmbed} is the same rule.
     * Bound as a parameter so the backslashes need no escaping in the template.
     */
    static final String NON_BLANK = "[^\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x{85}\\p{Z}]";

    /**
     * The backfill's discovery: the projects with a done status row written in the window, each with its latest one
     * and its count of such rows of traces created at or after the window's lower bound. A plain aggregate: no dedup
     * and no join, so its memory grows with the projects, not with the traces. A trace relabelled since its last merge
     * has more than one done row and counts more than once. The guards are the window read's.
     */
    private static final String FIND_BACKFILL_PROJECTS = """
            SELECT project_id,
                countIf(trace_created_at >= fromUnixTimestamp64Micro(:lower_bound_micros, 'UTC')) AS traces,
                max(updated_at) AS last_done_at
            FROM ai_label_status
            WHERE workspace_id = :workspace_id
            AND task = :task
            AND trace_created_at >= fromUnixTimestamp64Micro(:created_after_micros, 'UTC')
            AND updated_at >= fromUnixTimestamp64Micro(:lower_bound_micros, 'UTC')
            AND status = 'done'
            GROUP BY project_id
            SETTINGS max_threads = 2, max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>,
                max_execution_time = <max_execution_time>, priority = 100, log_comment = '<log_comment>'
            """;

    /**
     * Per project, the traces with a live vector, of traces created at or after the window's lower bound: the other
     * half of the discovery's counts. As {@code TopicWindowDAO}'s count: each (trace, task version) key counts by its
     * latest row, a key whose latest row is a tombstone is left out, and a trace counts once, so a relabelled trace's
     * older task versions and a row a tombstone replaced, not merged yet, do not raise it. The build reads a trace's
     * highest live task version, so a trace with any live vector is covered.
     */
    private static final String COUNT_TRACES_WITH_VECTORS_BY_PROJECT = """
            SELECT project_id, uniqExact(trace_id) AS traces_with_vectors
            FROM (
                SELECT project_id, trace_id, task_version, argMax(is_deleted, last_updated_at) AS deleted
                FROM topic_vectors
                WHERE workspace_id = :workspace_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:lower_bound_micros, 'UTC')
                GROUP BY project_id, trace_id, task_version
            )
            WHERE deleted = 0
            GROUP BY project_id
            SETTINGS max_threads = 2, max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>,
                max_execution_time = <max_execution_time>, priority = 100, log_comment = '<log_comment>'
            """;

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

    /**
     * {@code TraceDAO.DELETE_FOR_RETENTION} on {@code trace_id}. The week bounds are the partition expression, so the
     * sweep prunes to the partitions in range; they never exclude a row the id range would delete. Traces linked to
     * experiments survive retention, so their vectors do too.
     */
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

    /**
     * {@code TraceDAO.DELETE_FOR_RETENTION_BOUNDED} on {@code trace_id}: each workspace carries its own floor, and the
     * week bounds use the global {@code :min_lower_bound}, which is at or below every floor.
     */
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
     * {@code IN PARTITION} scopes the parts the mutation is registered against, as in {@code TraceDAO}'s delete: a
     * {@code WHERE} cannot. {@code <partition>} is interpolated because {@code IN PARTITION} takes no bound parameter;
     * it is always a {@code long} from {@link WeeklyPartitions}.
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
     * {@code createdAfter}, without a vector, oldest first: the whole workspace in one read.
     */
    public Flux<TopicVectorCandidate> findUnvectored(@NonNull String workspaceId, @NonNull String task,
            @NonNull Instant lowerBound, @NonNull Instant upperBound, @NonNull Instant createdAfter, int limit,
            long maxExecutionSeconds) {
        return findUnvectored(workspaceId, task, lowerBound, upperBound, createdAfter, null, null, List.of(), limit,
                maxExecutionSeconds);
    }

    /**
     * {@link #findUnvectored(String, String, Instant, Instant, Instant, int, long)} narrowed: to the traces created
     * before {@code createdBefore} when it is not null (a slice), to {@code projectId} when it is not null, and to the
     * projects not in {@code excludedProjectIds}.
     */
    public Flux<TopicVectorCandidate> findUnvectored(@NonNull String workspaceId, @NonNull String task,
            @NonNull Instant lowerBound, @NonNull Instant upperBound, @NonNull Instant createdAfter,
            @Nullable Instant createdBefore, @Nullable UUID projectId, @NonNull Collection<UUID> excludedProjectIds,
            int limit, long maxExecutionSeconds) {
        var find = config.getVectors().getFind();
        // The guards go into the SETTINGS clause, which ClickHouse can't take as bound parameters. The values are
        // validated config numbers, never input.
        var template = getSTWithLogComment(FIND_UNVECTORED, "topics:find_unvectored", workspaceId, null,
                "lower_bound=%s, created_after=%s, created_before=%s, project_id=%s, excluded=%d, limit=%d".formatted(
                        lowerBound, createdAfter, createdBefore, projectId, excludedProjectIds.size(), limit))
                .add("project", projectId != null)
                .add("excluded", !excludedProjectIds.isEmpty())
                .add("created_before", createdBefore != null)
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("task", task)
                            .bind("created_after_micros", toEpochMicros(createdAfter))
                            .bind("lower_bound_micros", toEpochMicros(lowerBound))
                            .bind("upper_bound_micros", toEpochMicros(upperBound))
                            .bind("limit", limit)
                            .bind("non_blank", NON_BLANK);
                    if (projectId != null) {
                        statement.bind("project_id", projectId.toString());
                    }
                    if (!excludedProjectIds.isEmpty()) {
                        statement.bind("undrained_project_ids",
                                excludedProjectIds.stream().map(UUID::toString).toArray(String[]::new));
                    }
                    if (createdBefore != null) {
                        statement.bind("created_before_micros", toEpochMicros(createdBefore));
                    }
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, metadata) -> toCandidate(row)));
    }

    /**
     * The projects with a done status of {@code task} written at or after {@code lowerBound}, of traces created at or
     * after {@code createdAfter}: see {@link #FIND_BACKFILL_PROJECTS}.
     */
    public Flux<TopicBackfillProject> findBackfillProjects(@NonNull String workspaceId, @NonNull String task,
            @NonNull Instant lowerBound, @NonNull Instant createdAfter, long maxExecutionSeconds) {
        var template = guarded(FIND_BACKFILL_PROJECTS, "topics:find_backfill_projects", workspaceId,
                "lower_bound=%s, created_after=%s".formatted(lowerBound, createdAfter), maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", task)
                        .bind("created_after_micros", toEpochMicros(createdAfter))
                        .bind("lower_bound_micros", toEpochMicros(lowerBound))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> TopicBackfillProject.builder()
                        .projectId(UUID.fromString(row.get("project_id", String.class)))
                        .traces(row.get("traces", Long.class))
                        .lastDoneAt(row.get("last_done_at", Instant.class))
                        .build()));
    }

    /**
     * Per project, the traces with a live vector, created at or after {@code lowerBound}: see
     * {@link #COUNT_TRACES_WITH_VECTORS_BY_PROJECT}.
     */
    public Mono<Map<UUID, Long>> countTracesWithVectorsByProject(@NonNull String workspaceId,
            @NonNull Instant lowerBound,
            long maxExecutionSeconds) {
        var template = guarded(COUNT_TRACES_WITH_VECTORS_BY_PROJECT, "topics:count_traces_with_vectors_by_project",
                workspaceId,
                "lower_bound=%s".formatted(lowerBound), maxExecutionSeconds);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("lower_bound_micros", toEpochMicros(lowerBound))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(
                        UUID.fromString(row.get("project_id", String.class)),
                        row.get("traces_with_vectors", Long.class))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private ST guarded(String query, String queryName, String workspaceId, String details,
            long maxExecutionSeconds) {
        var find = config.getVectors().getFind();
        return getSTWithLogComment(query, queryName, workspaceId, null, details)
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
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
        // A non-v7 id has no time in it; UUIDv7ToDateTime gives it 1970, so its bound is 0 as well.
        long minIdAtSeconds = keys.stream()
                .mapToLong(key -> key.traceId().version() == 7 ? key.traceId().getMostSignificantBits() >>> 16 : 0)
                .min()
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
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(workspaceIds),
                "Argument 'workspaceIds' must not be empty");
        log.info("Retention delete topic vectors, workspaces '{}', cutoffId '{}', lowerBound '{}'",
                workspaceIds.size(), cutoffId, lowerBound);
        var template = getSTWithLogComment(DELETE_FOR_RETENTION, "topics:retention_delete_vectors", null, "",
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
        var template = getSTWithLogComment(DELETE_FOR_RETENTION_BOUNDED, "topics:retention_delete_vectors_bounded",
                null, "", "workspaces_size=%s, cutoff_id=%s, min_lower_bound=%s".formatted(workspaceMinIds.size(),
                        cutoffId, lowerBound))
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
     * The trace-delete cascade; workspace from the reactive context. One statement per batch and weekly partition, or
     * one unscoped statement per batch when a trace id's partition cannot be derived exactly.
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
                        .orElseGet(() -> Flux.from(deleteByTraceIds(batch, null, projectId, workspaceId, userName))))
                .then());
    }

    private Mono<Void> deleteByTraceIds(Collection<UUID> traceIds, Long partition, UUID projectId, String workspaceId,
            String userName) {
        var ids = traceIds.stream().map(UUID::toString).toArray(String[]::new);
        var template = getSTWithLogComment(DELETE_BY_TRACE_IDS, "topics:delete_vectors_by_trace_ids", workspaceId,
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
