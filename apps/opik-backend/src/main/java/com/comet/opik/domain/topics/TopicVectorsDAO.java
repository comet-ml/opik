package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.JsonEachRowBulkInsert;
import com.comet.opik.infrastructure.db.TraceKeyedTableDeletes;
import com.comet.opik.utils.JsonUtils;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * {@code topic_vectors}; migration 000149 explains its layout. Reads drop a key whose latest row has
 * {@code is_deleted = 1}. It reads ai-labels' {@code ai_label_status} and {@code ai_labels} and never writes them.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicVectorsDAO {

    static final String TABLE = "topic_vectors";
    private static final String QUERY_PREFIX = "topics";

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
     * The read returns the summary with its key, so the tick embeds it without reading {@code ai_labels} again. Every
     * side filters on {@code :task}, the task in the {@code topic_vectors} key.
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
     * half-open slices put each key in exactly one of them; a slice also bounds {@code id_at} from above by
     * {@code done}'s newest trace id, so an old slice does not read the outputs and vectors of every newer trace.
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
                    AND trace_created_at >= parseDateTime64BestEffort(:created_after, 6, 'UTC')
                    <if(created_before)>AND trace_created_at \\< parseDateTime64BestEffort(:created_before, 6, 'UTC')<endif>
                    AND updated_at >= parseDateTime64BestEffort(:lower_bound, 6, 'UTC')
                    ORDER BY workspace_id, project_id, task, trace_id, updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, task, trace_id
                )
                WHERE status = 'done'
                AND updated_at \\<= parseDateTime64BestEffort(:upper_bound, 6, 'UTC')
            ),
            usable AS (
                SELECT project_id, trace_id, task_version, summary
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
            SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, updated_at, summary
            FROM done
            INNER JOIN usable USING (project_id, trace_id, task_version)
            WHERE (project_id, trace_id, task_version) NOT IN (
                SELECT project_id, trace_id, task_version
                FROM (
                    SELECT workspace_id, project_id, trace_id, task_version, is_deleted
                    FROM topic_vectors
                    WHERE workspace_id = :workspace_id
                    <if(project)>AND project_id = :project_id<endif>
                    AND task = :task
                    AND trace_created_at >= parseDateTime64BestEffort(:created_after, 6, 'UTC')
                    <if(created_before)>AND trace_created_at \\< parseDateTime64BestEffort(:created_before, 6, 'UTC')<endif>
                    AND id_at >= (SELECT toStartOfSecond(min(UUIDv7ToDateTime(toUUID(trace_id), 'UTC'))) FROM done)
                    <if(created_before)>AND id_at \\<= (SELECT max(UUIDv7ToDateTime(toUUID(trace_id), 'UTC')) FROM done)<endif>
                    ORDER BY workspace_id, project_id, task, trace_id, task_version, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, task, trace_id, task_version
                )
                WHERE is_deleted = 0
            )
            ORDER BY updated_at, trace_id
            LIMIT :limit
            SETTINGS max_threads = <max_threads>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, max_execution_time = <max_execution_time>,
                log_comment = '<log_comment>'
            """;

    /**
     * RE2 for "not blank" by opik-inference's rule, Python's {@code str.strip()}: a character outside the ASCII controls
     * tab to carriage return and file to unit separator, U+0085 and {@code \p{Z}}. {@code /v1/embed} rejects a whole
     * call when one of its inputs is blank, so a blank summary must never reach it. Bound as a parameter so the
     * backslashes need no escaping in the template.
     */
    static final String NON_BLANK = "[^\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x{85}\\p{Z}]";

    /**
     * The backfill's discovery, in one slice of trace creation time: the projects with a done status row written in
     * the window, each with its latest one and its count of such rows of traces created at or after the window's lower
     * bound. A plain aggregate: no dedup and no join, so its memory grows with the projects, not with the traces. A
     * trace relabelled since its last merge has more than one done row and counts more than once. A trace is in one
     * slice only, so the slices' counts add up. The guards are the window read's.
     */
    private static final String FIND_BACKFILL_PROJECTS = """
            SELECT project_id,
                countIf(trace_created_at >= parseDateTime64BestEffort(:lower_bound, 6, 'UTC')) AS traces,
                max(updated_at) AS last_done_at
            FROM ai_label_status
            WHERE workspace_id = :workspace_id
            AND task = :task
            AND trace_created_at >= parseDateTime64BestEffort(:created_after, 6, 'UTC')
            <if(created_before)>AND trace_created_at \\< parseDateTime64BestEffort(:created_before, 6, 'UTC')<endif>
            AND updated_at >= parseDateTime64BestEffort(:lower_bound, 6, 'UTC')
            AND status = 'done'
            GROUP BY project_id
            SETTINGS max_threads = <max_threads>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, max_execution_time = <max_execution_time>,
                log_comment = '<log_comment>'
            """;

    /**
     * Per project of {@code :project_ids}, in one slice of trace creation time: the traces with a live vector. Each
     * (trace, task version) key counts by its latest row, a key whose latest row is a tombstone is left out, and a
     * trace counts once, so a relabelled trace's older task versions and a row a tombstone replaced, not merged yet,
     * do not raise it. The build reads a trace's highest live task version, so a trace with any live vector is
     * covered. The dedup is {@code LIMIT 1 BY} in the sort key's order, which needs far less memory than a
     * {@code GROUP BY} state per key.
     */
    private static final String COUNT_TRACES_WITH_VECTORS_BY_PROJECT = """
            SELECT project_id, uniqExact(trace_id) AS traces_with_vectors
            FROM (
                SELECT project_id, trace_id, is_deleted
                FROM topic_vectors
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND task = :task
                AND trace_created_at >= parseDateTime64BestEffort(:created_after, 6, 'UTC')
                <if(created_before)>AND trace_created_at \\< parseDateTime64BestEffort(:created_before, 6, 'UTC')<endif>
                ORDER BY workspace_id, project_id, task, trace_id, task_version, last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, task, trace_id, task_version
            )
            WHERE is_deleted = 0
            GROUP BY project_id
            SETTINGS max_threads = <max_threads>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, max_execution_time = <max_execution_time>,
                log_comment = '<log_comment>'
            """;

    /**
     * The check after a workspace's inserts: the pairs that still have a status row of the task; see
     * {@code TopicVectorsTickService.deleteVectorsOfDeletedTraces}. The guards are the window read's.
     */
    private static final String FIND_STATUS_KEYS = """
            SELECT DISTINCT project_id, trace_id
            FROM ai_label_status
            WHERE workspace_id = :workspace_id
            AND task = :task
            AND id_at >= parseDateTimeBestEffort(:min_id_at, 'UTC')
            AND (project_id, trace_id) IN arrayZip(:project_ids, :trace_ids)
            SETTINGS max_threads = <max_threads>, max_rows_to_read = <max_rows_to_read>,
                max_memory_usage = <max_memory_usage>, max_execution_time = <max_execution_time>,
                log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull JsonEachRowBulkInsert jsonBulkInsert;
    private final @NonNull TraceKeyedTableDeletes deletes;
    private final @NonNull @Config("topics") TopicsConfig config;

    /**
     * A (trace, task version) whose latest {@code ai_label_status} row is done, whose output has a usable
     * {@code summary} and that has no vector yet. {@code statusUpdatedAt} is that status row's {@code updated_at}: the
     * tick takes the oldest first.
     */
    @Builder(toBuilder = true)
    public record Candidate(
            @NonNull String workspaceId,
            @NonNull UUID projectId,
            @NonNull UUID traceId,
            @NonNull Instant traceCreatedAt,
            int taskVersion,
            @NonNull Instant statusUpdatedAt,
            @NonNull String summary) {
    }

    /**
     * A project the backfill's discovery found: {@code traces} done status rows of traces created in the backfill
     * window, and the latest done status written in it.
     */
    @Builder(toBuilder = true)
    public record BackfillProject(@NonNull UUID projectId, long traces, @NonNull Instant lastDoneAt) {
    }

    /** A trace by its project: a trace id reused in another project is another trace. */
    @Builder(toBuilder = true)
    public record ProjectTraceKey(@NonNull UUID projectId, @NonNull UUID traceId) {
    }

    /** A half-open slice of trace creation time; {@code before} is null on the newest, open-ended slice. */
    private record Slice(@NonNull Instant from, @Nullable Instant before) {
    }

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
                    node.put("task", row.task());
                    node.put("trace_id", row.traceId().toString());
                    node.put("trace_created_at", row.traceCreatedAt().toString());
                    node.put("task_version", row.taskVersion());
                    node.put("embed_model", row.embedModel());
                    node.put("embed_dimensions", row.embedDimensions());
                    node.put("text", row.text());
                    var vector = node.putArray("vector");
                    for (float value : row.vector()) {
                        vector.add(value);
                    }
                    return node;
                });
    }

    /** The window read {@code find} describes; see {@link TopicVectorsFind}. */
    public Flux<Candidate> findUnvectored(@NonNull TopicVectorsFind find) {
        var workspaceId = find.workspaceId();
        var createdBefore = find.createdBefore();
        var projectId = find.projectId();
        var excludedProjectIds = find.excludedProjectIds();
        var template = guarded(FIND_UNVECTORED, "topics:find_unvectored", workspaceId,
                "lower_bound=%s, created_after=%s, created_before=%s, project_id=%s, excluded=%d, limit=%d".formatted(
                        find.lowerBound(), find.createdAfter(), createdBefore, projectId, excludedProjectIds.size(),
                        find.limit()),
                find.maxExecutionSeconds())
                .add("project", projectId != null)
                .add("excluded", !excludedProjectIds.isEmpty())
                .add("created_before", createdBefore != null);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("task", find.task())
                            .bind("created_after", find.createdAfter().toString())
                            .bind("lower_bound", find.lowerBound().toString())
                            .bind("upper_bound", find.upperBound().toString())
                            .bind("limit", find.limit())
                            .bind("non_blank", NON_BLANK);
                    if (projectId != null) {
                        statement.bind("project_id", projectId.toString());
                    }
                    if (!excludedProjectIds.isEmpty()) {
                        statement.bind("undrained_project_ids",
                                excludedProjectIds.stream().map(UUID::toString).toArray(String[]::new));
                    }
                    if (createdBefore != null) {
                        statement.bind("created_before", createdBefore.toString());
                    }
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, metadata) -> Candidate.builder()
                        .workspaceId(row.get("workspace_id", String.class))
                        .projectId(UUID.fromString(row.get("project_id", String.class)))
                        .traceId(UUID.fromString(row.get("trace_id", String.class)))
                        .traceCreatedAt(row.get("trace_created_at", Instant.class))
                        .taskVersion(row.get("task_version", Long.class).intValue())
                        .statusUpdatedAt(row.get("updated_at", Instant.class))
                        .summary(row.get("summary", String.class))
                        .build()));
    }

    /**
     * The backfill's discovery of the traces created from {@code createdAfter} on, one read per slice of
     * {@code topics.vectors.find.sliceSize}; each read gets {@code maxExecutionSeconds}.
     */
    public Flux<BackfillProject> findBackfillProjects(@NonNull String workspaceId, @NonNull String task,
            @NonNull Instant lowerBound, @NonNull Instant createdAfter, long maxExecutionSeconds) {
        return Flux.fromIterable(slices(createdAfter))
                .concatMap(slice -> {
                    var template = guarded(FIND_BACKFILL_PROJECTS, "topics:find_backfill_projects", workspaceId,
                            "lower_bound=%s, created_after=%s, created_before=%s".formatted(lowerBound, slice.from(),
                                    slice.before()),
                            maxExecutionSeconds)
                            .add("created_before", slice.before() != null);
                    return Mono.from(connectionFactory.create())
                            .flatMapMany(connection -> {
                                var statement = connection.createStatement(template.render())
                                        .bind("workspace_id", workspaceId)
                                        .bind("task", task)
                                        .bind("created_after", slice.from().toString())
                                        .bind("lower_bound", lowerBound.toString());
                                if (slice.before() != null) {
                                    statement.bind("created_before", slice.before().toString());
                                }
                                return statement.execute();
                            })
                            .flatMap(result -> result.map((row, metadata) -> BackfillProject.builder()
                                    .projectId(UUID.fromString(row.get("project_id", String.class)))
                                    .traces(row.get("traces", Long.class))
                                    .lastDoneAt(row.get("last_done_at", Instant.class))
                                    .build()));
                })
                .collect(Collectors.toMap(BackfillProject::projectId, Function.identity(),
                        (left, right) -> left.toBuilder()
                                .traces(left.traces() + right.traces())
                                .lastDoneAt(Collections.max(List.of(left.lastDoneAt(), right.lastDoneAt())))
                                .build()))
                .flatMapIterable(Map::values);
    }

    /**
     * Per project of {@code projectIds}, its traces created from {@code createdAfter} on with a live vector of
     * {@code task}, one read per slice of {@code topics.vectors.find.sliceSize}; each read gets
     * {@code maxExecutionSeconds}. A project without one is left out.
     */
    public Mono<Map<UUID, Long>> countTracesWithVectorsByProject(@NonNull String workspaceId, @NonNull String task,
            @NonNull Instant createdAfter, @NonNull Collection<UUID> projectIds, long maxExecutionSeconds) {
        if (projectIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        var ids = projectIds.stream().map(UUID::toString).toArray(String[]::new);
        return Flux.fromIterable(slices(createdAfter))
                .concatMap(slice -> {
                    var template = guarded(COUNT_TRACES_WITH_VECTORS_BY_PROJECT,
                            "topics:count_traces_with_vectors_by_project", workspaceId,
                            "projects=%d, created_after=%s, created_before=%s".formatted(ids.length, slice.from(),
                                    slice.before()),
                            maxExecutionSeconds)
                            .add("created_before", slice.before() != null);
                    return Mono.from(connectionFactory.create())
                            .flatMapMany(connection -> {
                                var statement = connection.createStatement(template.render())
                                        .bind("workspace_id", workspaceId)
                                        .bind("task", task)
                                        .bind("project_ids", ids)
                                        .bind("created_after", slice.from().toString());
                                if (slice.before() != null) {
                                    statement.bind("created_before", slice.before().toString());
                                }
                                return statement.execute();
                            })
                            .flatMap(result -> result.map((row, metadata) -> Map.entry(
                                    UUID.fromString(row.get("project_id", String.class)),
                                    row.get("traces_with_vectors", Long.class))));
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }

    /**
     * Slices of {@code topics.vectors.find.sliceSize} back from now down to {@code createdAfter}, the newest
     * open-ended, so a trace created during the reads is still in one of them.
     */
    private List<Slice> slices(Instant createdAfter) {
        var size = config.getVectors().getFind().getSliceSize().toJavaDuration();
        var slices = new ArrayList<Slice>();
        Instant before = null;
        var start = Instant.now();
        while (true) {
            var sliceStart = (before == null ? start : before).minus(size);
            var from = sliceStart.isAfter(createdAfter) ? sliceStart : createdAfter;
            slices.add(new Slice(from, before));
            if (!from.isAfter(createdAfter)) {
                return slices;
            }
            before = from;
        }
    }

    /** The template with the guards, which go into the SETTINGS clause and can't be bound: validated config numbers. */
    private ST guarded(String query, String queryName, String workspaceId, String details,
            long maxExecutionSeconds) {
        var find = config.getVectors().getFind();
        return getSTWithLogComment(query, queryName, workspaceId, null, details)
                .add("max_threads", find.getMaxThreads())
                .add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
    }

    public Flux<ProjectTraceKey> findStatusKeys(@NonNull String workspaceId, @NonNull String task,
            @NonNull Collection<ProjectTraceKey> keys, long maxExecutionSeconds) {
        if (keys.isEmpty()) {
            return Flux.empty();
        }
        // id_at is derived from the UUIDv7 trace id, so the oldest id bounds the partitions read.
        // A non-v7 id has no time in it; UUIDv7ToDateTime gives it 1970, so its bound is the epoch as well.
        var minIdAt = Instant.ofEpochMilli(keys.stream()
                .mapToLong(key -> key.traceId().version() == 7 ? key.traceId().getMostSignificantBits() >>> 16 : 0)
                .min()
                .orElseThrow()).truncatedTo(ChronoUnit.SECONDS);
        var template = guarded(FIND_STATUS_KEYS, "topics:find_status_keys", workspaceId,
                "keys=%d".formatted(keys.size()), maxExecutionSeconds);
        var projectIds = keys.stream().map(key -> key.projectId().toString()).toArray(String[]::new);
        var traceIds = keys.stream().map(key -> key.traceId().toString()).toArray(String[]::new);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", task)
                        .bind("min_id_at", minIdAt.toString())
                        .bind("project_ids", projectIds)
                        .bind("trace_ids", traceIds)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> ProjectTraceKey.builder()
                        .projectId(UUID.fromString(row.get("project_id", String.class)))
                        .traceId(UUID.fromString(row.get("trace_id", String.class)))
                        .build()));
    }

    /** Traces linked to experiments survive retention, so their vectors do too. */
    public Mono<Long> deleteForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        return deletes.deleteForRetention(TABLE, QUERY_PREFIX, workspaceIds, cutoffId, lowerBound);
    }

    public Mono<Long> deleteForRetentionBounded(@NonNull Map<String, UUID> workspaceMinIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        return deletes.deleteForRetentionBounded(TABLE, QUERY_PREFIX, workspaceMinIds, cutoffId, lowerBound);
    }

    /** The trace-delete cascade; workspace from the reactive context. */
    public Mono<Void> deleteByTraceIds(@NonNull Set<UUID> traceIds, @NonNull UUID projectId) {
        return deletes.deleteByTraceIds(TABLE, QUERY_PREFIX, traceIds, projectId);
    }

    /** The vectors of {@code keys}, as the system user: the tick's own deletes, outside any request. */
    public Mono<Void> deleteByKeys(@NonNull String workspaceId, @NonNull Collection<ProjectTraceKey> keys) {
        var byProject = keys.stream().collect(Collectors.groupingBy(ProjectTraceKey::projectId,
                Collectors.mapping(ProjectTraceKey::traceId, Collectors.toSet())));
        return Flux.fromIterable(byProject.entrySet())
                .concatMap(entry -> deleteByTraceIds(entry.getValue(), entry.getKey()))
                .then()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER));
    }
}
