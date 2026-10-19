package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.AiLabelsConfig;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;

/**
 * The sweep (design §5): per bucket of workspaces, the eligible traces still missing a current label and which tasks
 * each one lacks, plus the headline counts built from the same CTEs. State lives only in ClickHouse; nothing here
 * keeps a cursor.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiLabelSweepDAO {

    /**
     * A task's version history as the SQL {@code required()} reads it: the highest version whose
     * {@code applies_from} is at or before the trace's {@code created_at}, or 0 when none is.
     */
    @Builder(toBuilder = true)
    record SweepTask(@NonNull String name, @NonNull List<Integer> versions, @NonNull List<Instant> appliesFrom) {

        static SweepTask of(AiLabelTask task) {
            var versions = task.versions();
            return new SweepTask(task.name(),
                    versions.stream().map(AiLabelTask.Version::version).toList(),
                    versions.stream().map(AiLabelTask.Version::appliesFrom).toList());
        }
    }

    record Candidate(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID traceId,
            @NonNull Instant createdAt, @NonNull Map<String, Integer> missing) {
    }

    record WorkspaceHealth(@NonNull String workspaceId, long unsettled, long unsettledPastSlo) {
    }

    // The latest version of each trace in the horizon; created_at is the only time filter and its minmax index does the
    // narrowing. Open traces carry the epoch end_time, not NULL.
    private static final String CANDIDATE_CTES = """
            WITH latest_traces AS (
                SELECT workspace_id, project_id, id, created_at, end_time, last_updated_at, is_deleted
                FROM traces
                WHERE workspace_id IN :workspace_ids
                AND created_at >= now64(6) - toIntervalSecond(:horizon_seconds)
                ORDER BY workspace_id, project_id, id, last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, id
            ),
            eligible AS (
                SELECT workspace_id, project_id, id, created_at
                FROM latest_traces
                WHERE is_deleted = 0
                AND end_time > toDateTime64(0, 6)
                AND last_updated_at \\< now64(6) - toIntervalSecond(:quiet_seconds)
            ),
            latest_status AS (
                SELECT workspace_id, trace_id, task, trace_created_at, task_version, status, retryable, attempt, updated_at
                FROM ai_label_status
                WHERE workspace_id IN :workspace_ids
                AND trace_created_at >= now64(6) - toIntervalSecond(:horizon_seconds)
                ORDER BY workspace_id, project_id, task, trace_id, updated_at DESC
                LIMIT 1 BY workspace_id, project_id, task, trace_id
            ),
            judged AS (
                SELECT workspace_id, trace_id, task, attempt,
                    task_version >= <required_status> AS is_current,
                    is_current AND (
                        status = 'done'
                        OR (status = 'failed' AND (NOT retryable OR attempt >= :max_attempts))
                        OR (status = 'failed' AND retryable AND updated_at > now64(6) - toIntervalSecond(:retry_delay_seconds))
                    ) AS settled
                FROM latest_status
            ),
            by_trace AS (
                SELECT workspace_id, trace_id,
                    groupArrayIf(task, settled) AS settled_tasks,
                    groupArrayIf(task, is_current AND NOT settled) AS retry_tasks,
                    groupArrayIf(attempt, is_current AND NOT settled) AS retry_attempts
                FROM judged
                GROUP BY workspace_id, trace_id
            ),
            candidates AS (
                SELECT e.workspace_id AS workspace_id, e.project_id AS project_id, e.id AS id, e.created_at AS created_at,
                    arrayFilter(t -> <required_trace> > 0 AND NOT has(s.settled_tasks, t), :task_names) AS missing,
                    arrayMap(t -> if(has(s.retry_tasks, t), toUInt32(s.retry_attempts[indexOf(s.retry_tasks, t)]), 0), missing) AS attempts
                FROM eligible AS e
                LEFT JOIN by_trace AS s ON s.workspace_id = e.workspace_id AND s.trace_id = e.id
            )
            """;

    private static final String SETTINGS = """
            SETTINGS max_threads = 2, max_rows_to_read = <max_rows_to_read>, max_memory_usage = <max_memory_usage>,
                max_execution_time = <max_execution_time>, priority = 100, join_use_nulls = 0,
                log_comment = '<log_comment>'
            """;

    // A task version older than required is never settled, but its attempts belong to the old version, so they reset.
    // Newest first (ids are UUIDv7), so under overload the newest traces are labeled and the oldest age out of H. Traces
    // in flight are left out here, before the LIMIT: the newest C would otherwise be exactly the ones in flight.
    private static final String SWEEP = CANDIDATE_CTES + """
            SELECT workspace_id, project_id, id, created_at, missing, attempts
            FROM candidates
            WHERE notEmpty(missing)
            AND id NOT IN :in_flight
            ORDER BY workspace_id, id DESC
            LIMIT :per_workspace_candidates BY workspace_id
            """ + SETTINGS;

    private static final String HEALTH = CANDIDATE_CTES + """
            SELECT workspace_id,
                sum(length(missing)) AS unsettled,
                sumIf(length(missing), created_at \\< now64(6) - toIntervalSecond(:slo_seconds)) AS unsettled_past_slo
            FROM candidates
            WHERE notEmpty(missing)
            GROUP BY workspace_id
            """ + SETTINGS;

    private static final String NEVER_ENDED = """
            SELECT count() AS never_ended
            FROM (
                SELECT end_time, is_deleted
                FROM traces
                WHERE workspace_id IN :workspace_ids
                AND created_at >= now64(6) - toIntervalSecond(:horizon_seconds)
                ORDER BY workspace_id, project_id, id, last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, id
            )
            WHERE is_deleted = 0 AND end_time = toDateTime64(0, 6)
            """ + SETTINGS;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull @Config("aiLabels") AiLabelsConfig config;

    /** @param inFlight the bucket's traces with a task in flight, which yield no candidates */
    Flux<Candidate> sweep(@NonNull List<String> workspaceIds, @NonNull List<SweepTask> tasks, int bucket,
            @NonNull Collection<String> inFlight) {
        if (workspaceIds.isEmpty() || tasks.isEmpty()) {
            return Flux.empty();
        }
        return query(SWEEP, "ai-labels:sweep", workspaceIds, tasks, bucket,
                statement -> statement.bind("per_workspace_candidates", config.getPerWorkspaceCandidates())
                        .bind("in_flight", inFlight.toArray(String[]::new)))
                .flatMap(result -> result.map((row, metadata) -> toCandidate(row)));
    }

    Flux<WorkspaceHealth> health(@NonNull List<String> workspaceIds, @NonNull List<SweepTask> tasks, int bucket) {
        if (workspaceIds.isEmpty() || tasks.isEmpty()) {
            return Flux.empty();
        }
        long sloSeconds = config.getQuietPeriod().toSeconds() + config.getSloSlack().toSeconds();
        return query(HEALTH, "ai-labels:health", workspaceIds, tasks, bucket,
                statement -> statement.bind("slo_seconds", sloSeconds))
                .flatMap(result -> result.map((row, metadata) -> new WorkspaceHealth(
                        row.get("workspace_id", String.class),
                        row.get("unsettled", Long.class),
                        row.get("unsettled_past_slo", Long.class))));
    }

    Mono<Long> neverEnded(@NonNull List<String> workspaceIds, int bucket) {
        if (workspaceIds.isEmpty()) {
            return Mono.just(0L);
        }
        var template = template(NEVER_ENDED, "ai-labels:never_ended", bucket).render();
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(template)
                        .bind("workspace_ids", workspaceIds.toArray(String[]::new))
                        .bind("horizon_seconds", config.getHorizon().toSeconds())
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> row.get("never_ended", Long.class)))
                .next()
                .defaultIfEmpty(0L);
    }

    private Flux<? extends Result> query(String sql, String queryName, List<String> workspaceIds,
            List<SweepTask> tasks, int bucket, Consumer<Statement> extraBinds) {
        var template = template(sql, queryName, bucket)
                .add("required_status", required("task", "trace_created_at", tasks.size()))
                .add("required_trace", required("t", "e.created_at", tasks.size()))
                .render();
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template)
                            .bind("workspace_ids", workspaceIds.toArray(String[]::new))
                            .bind("task_names", tasks.stream().map(SweepTask::name).toArray(String[]::new))
                            .bind("horizon_seconds", config.getHorizon().toSeconds())
                            .bind("quiet_seconds", config.getQuietPeriod().toSeconds())
                            .bind("max_attempts", config.getRetry().getMaxAttempts())
                            .bind("retry_delay_seconds", config.getRetry().getDelay().toSeconds());
                    bindTasks(statement, tasks);
                    extraBinds.accept(statement);
                    return statement.execute();
                });
    }

    /**
     * Puts the guards into the SETTINGS clause, which ClickHouse can't take as bound parameters. The values are
     * validated config numbers, never input.
     */
    private ST template(String sql, String queryName, int bucket) {
        var sweep = config.getSweep();
        return getSTWithLogComment(sql, queryName, null, null, "bucket=%d".formatted(bucket))
                .add("max_rows_to_read", sweep.getMaxRowsToRead())
                .add("max_memory_usage", sweep.getMaxMemoryUsage())
                .add("max_execution_time", sweep.getMaxExecutionTime().toSeconds());
    }

    /**
     * {@code required(task, created_at)} as SQL, one branch per enabled task bound by index: the highest version whose
     * {@code applies_from} is at or before {@code created_at}, else 0. {@code taskExpr} and {@code createdAtExpr} are
     * fixed column names from this class.
     */
    static String required(String taskExpr, String createdAtExpr, int taskCount) {
        var branches = IntStream.range(0, taskCount)
                .mapToObj(i -> ("%1$s = :task_name_%3$d, "
                        + "arrayMax(arrayPushFront(arrayFilter((v, a) -> a <= toUnixTimestamp64Micro(%2$s), "
                        + ":versions_%3$d, :applies_%3$d), 0))")
                        .formatted(taskExpr, createdAtExpr, i))
                .collect(Collectors.joining(", "));
        return "multiIf(%s, 0)".formatted(branches);
    }

    /** Binds what {@link #required} reads, by task index. */
    static Statement bindTasks(Statement statement, List<SweepTask> tasks) {
        for (int i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            statement.bind("task_name_" + i, task.name())
                    .bind("versions_" + i, task.versions().toArray(Integer[]::new))
                    .bind("applies_" + i, task.appliesFrom().stream()
                            .map(AiLabelSweepDAO::toEpochMicros)
                            .toArray(Long[]::new));
        }
        return statement;
    }

    private static long toEpochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }

    private static Candidate toCandidate(Row row) {
        var missing = row.get("missing", String[].class);
        // The driver's Java array type for UInt32 isn't part of its contract, so read the elements as numbers.
        var attempts = row.get("attempts", Object.class);
        var missingWithAttempts = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < missing.length; i++) {
            missingWithAttempts.put(missing[i], ((Number) Array.get(attempts, i)).intValue());
        }
        return new Candidate(
                row.get("workspace_id", String.class),
                UUID.fromString(row.get("project_id", String.class)),
                UUID.fromString(row.get("id", String.class)),
                row.get("created_at", Instant.class),
                Map.copyOf(missingWithAttempts));
    }
}
