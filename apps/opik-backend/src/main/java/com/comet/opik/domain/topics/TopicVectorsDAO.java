package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    static final String STATUS_TABLE = "topic_vector_status";
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
     * <p>ClickHouse does not materialize a CTE, so {@code done} is read three times: for the {@code id_at} bounds (one
     * scalar with the oldest and newest trace id times, computed once and reused by both sides, sliced or not), for the
     * {@code IN} set and for the outer {@code FROM}. Measured on ClickHouse 26.3 with the real DDL and a synthetic
     * 2.7M-trace workspace with 180 days of history, the 24h window reads 1.1M rows, about 300k per {@code done} read;
     * without the {@code id_at} bound the output read alone costs more than the third read saves. The read stays one
     * query, rather than reading {@code done} into Java and binding its keys back, so the fresh window, the long window
     * and the backfill slices share one read path.
     *
     * <p>Three optional scopes narrow every side alike, so the anti-join stays exact. One project: the read then follows
     * the tables' sort keys, which have {@code project_id} right after {@code workspace_id}. Excluded projects, on
     * {@code done} only: the other sides are joined to its keys. A slice, {@code trace_created_at < :created_before}:
     * half-open slices put each key in exactly one of them; a slice also bounds {@code id_at} from above by
     * {@code done}'s newest trace id, so an old slice does not read the outputs and vectors of every newer trace.
     *
     * <p>An as-of read ({@link TopicVectorsFind#asOf}) also bounds {@code done}'s statuses by {@code :upper_bound} before
     * the dedup, and {@code id_at} from above as a slice does: the tick's read narrowed after a size guard, whose cost
     * then follows its status span.
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
                    <if(as_of)>AND updated_at \\<= parseDateTime64BestEffort(:upper_bound, 6, 'UTC')<endif>
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
                    AND id_at >= tupleElement(<id_at_bounds>, 1)
                    <if(id_at_upper)>AND id_at \\<= tupleElement(<id_at_bounds>, 2)<endif>
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
                    AND id_at >= tupleElement(<id_at_bounds>, 1)
                    <if(id_at_upper)>AND id_at \\<= tupleElement(<id_at_bounds>, 2)<endif>
                    ORDER BY workspace_id, project_id, task, trace_id, task_version, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, task, trace_id, task_version
                )
                WHERE is_deleted = 0
            )
            AND (project_id, trace_id, task_version) NOT IN (
                SELECT project_id, trace_id, task_version
                FROM (
                    SELECT workspace_id, project_id, trace_id, task_version, is_deleted
                    FROM topic_vector_status
                    WHERE workspace_id = :workspace_id
                    <if(project)>AND project_id = :project_id<endif>
                    AND task = :task
                    AND trace_created_at >= parseDateTime64BestEffort(:created_after, 6, 'UTC')
                    <if(created_before)>AND trace_created_at \\< parseDateTime64BestEffort(:created_before, 6, 'UTC')<endif>
                    AND id_at >= tupleElement(<id_at_bounds>, 1)
                    <if(id_at_upper)>AND id_at \\<= tupleElement(<id_at_bounds>, 2)<endif>
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
     * {@code done}'s oldest and newest trace id times, the {@code id_at} bounds of the output and vector sides of
     * {@link #FIND_UNVECTORED}. One scalar in one text: ClickHouse computes an identical scalar once per query. With
     * {@code done} empty, {@code min()} is 1970-01-01, which would read every partition of the three sides for no row;
     * a far-future lower bound reads none instead.
     */
    private static final String ID_AT_BOUNDS = """
            (SELECT (toStartOfSecond(if(count() = 0, toDateTime('2100-01-01 00:00:00', 'UTC'),
                    min(UUIDv7ToDateTime(toUUID(trace_id), 'UTC')))),
                max(UUIDv7ToDateTime(toUUID(trace_id), 'UTC'))) FROM done)""";

    /**
     * RE2 for "not blank" by opik-inference's rule, Python's {@code str.strip()}: a character outside the ASCII controls
     * tab to carriage return and file to unit separator, U+0085 and {@code \p{Z}}. {@code /v1/embed} rejects a whole
     * call when one of its inputs is blank, so a blank summary must never reach it. Bound as a parameter so the
     * backslashes need no escaping in the template.
     */
    static final String NON_BLANK = "[^\\t\\n\\x0B\\f\\r\\x1C-\\x1F\\x{85}\\p{Z}]";

    /**
     * The backfill's discovery, in one slice of trace creation time: the projects with a done status row written in
     * the window, each with its latest one and its count of traces created at or after the window's lower bound that
     * have such a row. A plain aggregate, no dedup and no join; {@code uniq} keeps a small fixed state per project, so
     * the memory grows with the projects, not with the traces, and a trace relabelled since its last merge still
     * counts once. The count is approximate for large projects, which the backfill's progress allows. A trace is in one
     * slice only, so the slices' counts add up. The guards are the window read's.
     */
    private static final String FIND_BACKFILL_PROJECTS = """
            SELECT project_id,
                uniq(trace_id) AS traces,
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
     * do not raise it. The run reads a trace's highest live task version, so a trace with any live vector is
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

    /** Any row of the key is enough: the trace-delete cascade and retention delete all of a trace's rows. */
    private static final String FIND_LIVE_TRACES = """
            SELECT DISTINCT project_id, trace_id
            FROM ai_label_status
            WHERE workspace_id = :workspace_id
            AND project_id IN :project_ids
            AND task = :task
            AND trace_id IN :trace_ids
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
     * A project the backfill's discovery found: about how many traces have a done status written in the backfill
     * window, created within the window read's {@code trace_created_at} bound, and the latest such status. The same
     * traces the window read returns, so the count bounds a read of the project.
     */
    @Builder(toBuilder = true)
    public record BackfillProject(@NonNull UUID projectId, long traces, @NonNull Instant lastDoneAt) {

        /** One project as two slices found it: their traces added up, the later done status. */
        static BackfillProject merge(@NonNull BackfillProject left, @NonNull BackfillProject right) {
            return left.toBuilder()
                    .traces(left.traces() + right.traces())
                    .lastDoneAt(Collections.max(List.of(left.lastDoneAt(), right.lastDoneAt())))
                    .build();
        }
    }

    /** A trace of one project: a client can give the same trace id to traces of two projects. */
    @Builder(toBuilder = true)
    record ProjectTrace(@NonNull UUID projectId, @NonNull UUID traceId) {

        static ProjectTrace of(VectorKey key) {
            return ProjectTrace.builder().projectId(key.projectId()).traceId(key.traceId()).build();
        }
    }

    /** A half-open slice of trace creation time; {@code before} is null on the newest, open-ended slice. */
    record Slice(@NonNull Instant from, @Nullable Instant before) {
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

    /** The status rows of traces that can never get a vector; the window read leaves their keys out. */
    public Mono<Long> insertStatuses(List<TopicVectorStatus> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Mono.just(0L);
        }
        return jsonBulkInsert.insert(
                STATUS_TABLE,
                getLogComment("topics:insert_vector_status", null, null, "rows=%d".formatted(rows.size())),
                rows,
                row -> {
                    var candidate = row.candidate();
                    var node = JsonUtils.createObjectNode();
                    node.put("workspace_id", candidate.workspaceId());
                    node.put("project_id", candidate.projectId().toString());
                    node.put("task", TopicEmbedding.SUMMARY_TASK);
                    node.put("trace_id", candidate.traceId().toString());
                    node.put("trace_created_at", candidate.traceCreatedAt().toString());
                    node.put("task_version", candidate.taskVersion());
                    node.put("reason", row.reason().getValue());
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
                "lower_bound=%s, created_after=%s, created_before=%s, project_id=%s, excluded=%d, limit=%d, as_of=%s"
                        .formatted(find.lowerBound(), find.createdAfter(), createdBefore, projectId,
                                excludedProjectIds.size(), find.limit(), find.asOf() ? find.upperBound() : null),
                find.maxExecutionSeconds())
                .add("id_at_bounds", ID_AT_BOUNDS)
                .add("project", projectId != null)
                .add("excluded", !excludedProjectIds.isEmpty())
                .add("created_before", createdBefore != null)
                .add("as_of", find.asOf())
                .add("id_at_upper", createdBefore != null || find.asOf());
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

    /** Of {@code keys}, the traces that still have an {@code ai_label_status} row of {@code task}: not deleted. */
    public Mono<Set<ProjectTrace>> findLiveTraces(@NonNull String workspaceId, @NonNull String task,
            @NonNull Collection<VectorKey> keys) {
        if (keys.isEmpty()) {
            return Mono.just(Set.of());
        }
        var projectIds = keys.stream().map(key -> key.projectId().toString()).distinct().toArray(String[]::new);
        var traceIds = keys.stream().map(key -> key.traceId().toString()).distinct().toArray(String[]::new);
        var template = guarded(FIND_LIVE_TRACES, "topics:find_live_trace_ids", workspaceId,
                "traces=%d".formatted(traceIds.length),
                config.getVectors().getFind().getMaxExecutionTime().toSeconds());
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("task", task)
                        .bind("project_ids", projectIds)
                        .bind("trace_ids", traceIds)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> ProjectTrace.builder()
                        .projectId(UUID.fromString(row.get("project_id", String.class)))
                        .traceId(UUID.fromString(row.get("trace_id", String.class)))
                        .build()))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The backfill's discovery of the traces created in one {@link #slices} slice, one read. */
    public Flux<BackfillProject> findBackfillProjects(@NonNull String workspaceId, @NonNull String task,
            @NonNull Instant lowerBound, @NonNull Slice slice, long maxExecutionSeconds) {
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
    }

    /**
     * Per project of {@code projectIds}, its traces created in one {@link #slices} slice with a live vector of
     * {@code task}, one read. A project without one is left out.
     */
    public Mono<Map<UUID, Long>> countTracesWithVectorsByProject(@NonNull String workspaceId, @NonNull String task,
            @NonNull Slice slice, @NonNull Collection<UUID> projectIds, long maxExecutionSeconds) {
        if (projectIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        var ids = projectIds.stream().map(UUID::toString).toArray(String[]::new);
        var template = guarded(COUNT_TRACES_WITH_VECTORS_BY_PROJECT, "topics:count_traces_with_vectors_by_project",
                workspaceId,
                "projects=%d, created_after=%s, created_before=%s".formatted(ids.length, slice.from(), slice.before()),
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
                        row.get("traces_with_vectors", Long.class))))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }

    /**
     * Slices of {@code topics.vectors.find.discoverySliceSize} back from now down to {@code createdAfter}, the newest
     * open-ended, so a trace created during the reads is still in one of them.
     */
    List<Slice> slices(@NonNull Instant createdAfter) {
        var size = config.getVectors().getFind().getDiscoverySliceSize().toJavaDuration();
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

    /**
     * Traces linked to experiments survive retention, so their vectors do too. Each delete covers both tables,
     * {@code topic_vectors} and {@code topic_vector_status}, which share the trace's keys and partitions.
     */
    public Mono<Long> deleteForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        return deletes.deleteForRetention(TABLE, QUERY_PREFIX, workspaceIds, cutoffId, lowerBound)
                .zipWith(deletes.deleteForRetention(STATUS_TABLE, QUERY_PREFIX, workspaceIds, cutoffId,
                        lowerBound), Long::sum);
    }

    public Mono<Long> deleteForRetentionBounded(@NonNull Map<String, UUID> workspaceMinIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        return deletes.deleteForRetentionBounded(TABLE, QUERY_PREFIX, workspaceMinIds, cutoffId, lowerBound)
                .zipWith(deletes.deleteForRetentionBounded(STATUS_TABLE, QUERY_PREFIX, workspaceMinIds,
                        cutoffId, lowerBound), Long::sum);
    }

    /** The trace-delete cascade; workspace from the reactive context. */
    public Mono<Void> deleteByTraceIds(@NonNull Set<UUID> traceIds, @NonNull UUID projectId) {
        return deletes.deleteByTraceIds(TABLE, QUERY_PREFIX, traceIds, projectId)
                .then(deletes.deleteByTraceIds(STATUS_TABLE, QUERY_PREFIX, traceIds, projectId));
    }

}
