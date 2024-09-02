package com.comet.opik.domain;

import com.comet.opik.api.ExperimentItem;
import com.google.common.base.Preconditions;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentItemDAO {

    record ExperimentSummary(UUID datasetId, long experimentCount, Instant mostRecentExperimentAt) {
        public static ExperimentSummary empty(UUID datasetId) {
            return new ExperimentSummary(datasetId, 0, null);
        }
    }

    /**
     * The query validates if already exists with this id. Failing if so.
     * That way only insert is allowed, but not update.
     */
    private static final String INSERT = """
            INSERT INTO experiment_items (
                id,
                experiment_id,
                dataset_item_id,
                trace_id,
                workspace_id,
                created_by,
                last_updated_by
            )
            SELECT
                if (
                    LENGTH(CAST(old.id AS Nullable(String))) > 0,
                    leftPad('', 40, '*'),
                    new.id
                ) as id,
                new.experiment_id,
                new.dataset_item_id,
                new.trace_id,
                if (
                    LENGTH(CAST(old.id AS Nullable(String))) > 0, old.workspace_id,
                    new.workspace_id
                ) as workspace_id,
                new.created_by,
                new.last_updated_by
            FROM (
                SELECT
                    :id AS id,
                    :experiment_id AS experiment_id,
                    :dataset_item_id AS dataset_item_id,
                    :trace_id AS trace_id,
                    :workspace_id AS workspace_id,
                    :created_by AS created_by,
                    :last_updated_by AS last_updated_by
                ) AS new
            LEFT JOIN (
                SELECT
                    id, workspace_id
                FROM experiment_items
                WHERE id = :id
                ORDER BY last_updated_at DESC
                LIMIT 1 BY id
            ) AS old
            ON new.id = old.id
            ;
            """;

    private static final String SELECT = """
            SELECT
            *
            FROM experiment_items
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String DELETE = """
            DELETE FROM experiment_items
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String FIND_EXPERIMENT_SUMMARY_BY_DATASET_IDS = """
            SELECT
                e.dataset_id,
                count(distinct ei.experiment_id) as experiment_count,
                max(ei.last_updated_at) as most_recent_experiment_at
            FROM experiment_items ei
            JOIN experiments e ON ei.experiment_id = e.id AND e.workspace_id = ei.workspace_id
            WHERE e.dataset_id in :dataset_ids
            AND ei.workspace_id = :workspace_id
            GROUP BY
                e.dataset_id
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Flux<ExperimentSummary> findExperimentSummaryByDatasetIds(Collection<UUID> datasetIds) {

        if (datasetIds.isEmpty()) {
            return Flux.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(FIND_EXPERIMENT_SUMMARY_BY_DATASET_IDS);

                    statement.bind("dataset_ids", datasetIds.stream().map(UUID::toString).toArray(String[]::new));

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new ExperimentSummary(
                        row.get("dataset_id", UUID.class),
                        row.get("experiment_count", Long.class),
                        row.get("most_recent_experiment_at", Instant.class))));
    }

    public Mono<Long> insert(@NonNull Set<ExperimentItem> experimentItems) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(experimentItems),
                "Argument 'experimentItems' must not be empty");
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(experimentItems, connection))
                .reduce(0L, Long::sum);
    }

    private Flux<Long> insert(Set<ExperimentItem> experimentItems, Connection connection) {

        log.info("Inserting experiment items, count '{}'", experimentItems.size());
        var statement = connection.createStatement(INSERT);

        return makeFluxContextAware((userName, workspaceName, workspaceId) -> {

            for (var iterator = experimentItems.iterator(); iterator.hasNext();) {
                var item = iterator.next();
                statement.bind("id", item.id())
                        .bind("experiment_id", item.experimentId())
                        .bind("dataset_item_id", item.datasetItemId())
                        .bind("trace_id", item.traceId())
                        .bind("workspace_id", workspaceId)
                        .bind("created_by", userName)
                        .bind("last_updated_by", userName);

                if (iterator.hasNext()) {
                    statement.add();
                }
            }

            statement.fetchSize(experimentItems.size());

            return Flux.from(statement.execute()).flatMap(Result::getRowsUpdated);
        });
    }

    private Publisher<ExperimentItem> mapToExperimentItem(Result result) {
        return result.map((row, rowMetadata) -> ExperimentItem.builder()
                .id(row.get("id", UUID.class))
                .experimentId(row.get("experiment_id", UUID.class))
                .datasetItemId(row.get("dataset_item_id", UUID.class))
                .traceId(row.get("trace_id", UUID.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdAt(row.get("created_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build());
    }

    public Mono<ExperimentItem> get(@NonNull UUID id) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(id, connection))
                .flatMap(this::mapToExperimentItem)
                .singleOrEmpty();
    }

    private Publisher<? extends Result> get(UUID id, Connection connection) {
        log.info("Getting experiment item by id '{}'", id);

        Statement statement = connection.createStatement(SELECT)
                .bind("id", id);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    public Mono<Long> delete(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids),
                "Argument 'ids' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> delete(Set<UUID> ids, Connection connection) {
        log.info("Deleting experiment items, count '{}'", ids.size());

        Statement statement = connection.createStatement(DELETE)
                .bind("ids", ids.stream().map(UUID::toString).toArray(String[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }
}
