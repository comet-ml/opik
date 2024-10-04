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
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.QueryItem;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

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
            VALUES
                  <items:{item |
                     (
                        :id<item.index>,
                        :experiment_id<item.index>,
                        :dataset_item_id<item.index>,
                        :trace_id<item.index>,
                        :workspace_id,
                        :created_by<item.index>,
                        :last_updated_by<item.index>
                    )
                     <if(item.hasNext)>
                        ,
                     <endif>
                  }>
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

    private static final String STREAM = """
            SELECT
                *
            FROM experiment_items
            WHERE workspace_id = :workspace_id
            AND experiment_id IN :experiment_ids
            <if(lastRetrievedId)> AND id \\< :lastRetrievedId <endif>
            ORDER BY experiment_id DESC, id DESC, last_updated_at DESC
            LIMIT 1 BY id
            LIMIT :limit
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

    private static final String DELETE_BY_EXPERIMENT_IDS = """
            DELETE FROM experiment_items
            WHERE experiment_id IN :experiment_ids
            AND workspace_id = :workspace_id
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

        log.info("Inserting experiment items, count '{}'", experimentItems.size());

        if (experimentItems.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> insert(experimentItems, connection));
    }

    private Mono<Long> insert(Collection<ExperimentItem> experimentItems, Connection connection) {

        List<QueryItem> queryItems = getQueryItemPlaceHolder(experimentItems.size());

        var template = new ST(INSERT)
                .add("items", queryItems);

        String sql = template.render();

        var statement = connection.createStatement(sql);

        return makeMonoContextAware((userName, workspaceName, workspaceId) -> {

            statement.bind("workspace_id", workspaceId);

            int index = 0;
            for (ExperimentItem item : experimentItems) {
                statement.bind("id" + index, item.id());
                statement.bind("experiment_id" + index, item.experimentId());
                statement.bind("dataset_item_id" + index, item.datasetItemId());
                statement.bind("trace_id" + index, item.traceId());
                statement.bind("created_by" + index, userName);
                statement.bind("last_updated_by" + index, userName);
                index++;
            }

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum);
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

    public Flux<ExperimentItem> getItems(@NonNull Set<UUID> experimentIds, int limit, UUID lastRetrievedId) {
        if (experimentIds.isEmpty()) {
            log.info("Getting experiment items by empty experimentIds, limit '{}', lastRetrievedId '{}'",
                    limit, lastRetrievedId);
            return Flux.empty();
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> getItems(experimentIds, limit, lastRetrievedId, connection))
                .flatMap(this::mapToExperimentItem);
    }

    private Publisher<? extends Result> getItems(
            Set<UUID> experimentIds, int limit, UUID lastRetrievedId, Connection connection) {
        log.info("Getting experiment items by experimentIds count '{}', limit '{}', lastRetrievedId '{}'",
                experimentIds.size(), limit, lastRetrievedId);
        var template = new ST(STREAM);
        if (lastRetrievedId != null) {
            template.add("lastRetrievedId", lastRetrievedId);
        }
        var statement = connection.createStatement(template.render())
                .bind("experiment_ids", experimentIds.toArray(UUID[]::new))
                .bind("limit", limit);
        if (lastRetrievedId != null) {
            statement.bind("lastRetrievedId", lastRetrievedId);
        }
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

    public Mono<Long> deleteByExperimentIds(Set<UUID> experimentIds) {

        Preconditions.checkArgument(CollectionUtils.isNotEmpty(experimentIds),
                "Argument 'experimentIds' must not be empty");

        log.info("Deleting experiment items by experiment ids [{}]", Arrays.toString(experimentIds.toArray()));

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> deleteByExperimentIds(experimentIds, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Deleted experiment items by experiment ids [{}]",
                                Arrays.toString(experimentIds.toArray()));
                    }
                });
    }

    private Flux<? extends Result> deleteByExperimentIds(Set<UUID> ids, Connection connection) {
        Statement statement = connection.createStatement(DELETE_BY_EXPERIMENT_IDS)
                .bind("experiment_ids", ids.toArray(UUID[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }
}
