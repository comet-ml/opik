package com.comet.opik.domain.ailabels;

import com.comet.opik.utils.WeeklyPartitions;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.ANALYTICS_DELETE_BATCH_SIZE;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * The deletes {@code ai_labels} and {@code ai_label_status} share. Both are keyed by {@code trace_id} and partitioned
 * by the trace's week exactly as {@code traces_local_v2} is, so their rows are deleted with their traces: by
 * retention, with the trace sweep's own workspaces, id range, week predicate and experiment exclusion, and by the
 * trace-delete cascade.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AiLabelTableDeletes {

    /**
     * Mirrors {@code TraceDAO.DELETE_FOR_RETENTION}, on {@code trace_id} instead of {@code id}. The week bounds are
     * the partition expression of both tables, so the sweep prunes to the partitions in range; they never exclude a
     * row the id range would delete. Traces linked to experiments survive retention, so their labels do too.
     */
    private static final String DELETE_FOR_RETENTION = """
            DELETE FROM <table>
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
     * Mirrors {@code TraceDAO.DELETE_FOR_RETENTION_BOUNDED}: each workspace carries its own {@code trace_id} floor, and
     * the week bounds use the global {@code :min_lower_bound}, which is at or below every floor.
     */
    private static final String DELETE_FOR_RETENTION_BOUNDED = """
            DELETE FROM <table>
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
     * {@code WHERE} cannot, so an unscoped delete of a few rows still visits every part. {@code <partition>} is
     * interpolated because {@code IN PARTITION} takes no bound parameter; it is always a {@code long} from
     * {@link WeeklyPartitions}.
     */
    private static final String DELETE_BY_TRACE_IDS = """
            DELETE FROM <table>
            <if(partition)>IN PARTITION <partition><endif>
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND trace_id IN :trace_ids
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    Mono<Long> deleteForRetention(String table, @NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(workspaceIds),
                "Argument 'workspaceIds' must not be empty");
        log.info("Retention delete ai-labels table, table '{}', workspaces '{}', cutoffId '{}', lowerBound '{}'",
                table, workspaceIds.size(), cutoffId, lowerBound);

        var template = getSTWithLogComment(DELETE_FOR_RETENTION, "ai-labels:retention_delete_" + table, null, "",
                "workspaces_size=%s, cutoff_id=%s, lower_bound=%s".formatted(workspaceIds.size(), cutoffId,
                        lowerBound))
                .add("table", table);

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> Mono.from(connection.createStatement(template.render())
                        .bind("workspace_ids", workspaceIds.toArray(String[]::new))
                        .bind("cutoff_id", cutoffId)
                        .bind("lower_bound", lowerBound)
                        .execute()))
                .flatMap(result -> Mono.from(result.getRowsUpdated()));
    }

    Mono<Long> deleteForRetentionBounded(String table, @NonNull Map<String, UUID> workspaceMinIds,
            @NonNull UUID cutoffId, @NonNull UUID lowerBound) {
        Preconditions.checkArgument(!workspaceMinIds.isEmpty(), "Argument 'workspaceMinIds' must not be empty");
        log.info("Retention delete ai-labels table (bounded), table '{}', workspaces '{}', cutoffId '{}'",
                table, workspaceMinIds.size(), cutoffId);

        var entries = List.copyOf(workspaceMinIds.entrySet());
        var template = getSTWithLogComment(DELETE_FOR_RETENTION_BOUNDED, "ai-labels:retention_delete_bounded_" + table,
                null, "", "workspaces_size=%s, cutoff_id=%s, min_lower_bound=%s".formatted(workspaceMinIds.size(),
                        cutoffId, lowerBound))
                .add("table", table)
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
     * One statement per batch and weekly partition, or a single unscoped statement per batch when a trace id's
     * partition cannot be derived exactly (see {@link WeeklyPartitions#groupByPartition}).
     */
    Mono<Void> deleteByTraceIds(String table, @NonNull Set<UUID> traceIds, @NonNull UUID projectId) {
        if (traceIds.isEmpty()) {
            return Mono.empty();
        }
        return makeMonoContextAware((userName, workspaceId) -> Flux
                .fromIterable(Lists.partition(List.copyOf(traceIds), ANALYTICS_DELETE_BATCH_SIZE))
                .concatMap(batch -> WeeklyPartitions.groupByPartition(batch)
                        .map(grouped -> Flux.fromIterable(grouped.entrySet())
                                .concatMap(entry -> deleteByTraceIds(table, entry.getValue(), entry.getKey(),
                                        projectId, workspaceId, userName)))
                        .orElseGet(() -> Flux.from(deleteByTraceIds(table, batch, null, projectId, workspaceId,
                                userName))))
                .then());
    }

    private Mono<Void> deleteByTraceIds(String table, Collection<UUID> traceIds, Long partition, UUID projectId,
            String workspaceId, String userName) {
        var ids = traceIds.stream().map(UUID::toString).toArray(String[]::new);
        var template = getSTWithLogComment(DELETE_BY_TRACE_IDS, "ai-labels:delete_by_trace_ids_" + table,
                workspaceId, userName, "trace_ids=%s, partition=%s".formatted(ids.length, partition))
                .add("table", table);
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
}
