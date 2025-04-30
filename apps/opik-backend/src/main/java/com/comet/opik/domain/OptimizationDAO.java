package com.comet.opik.domain;

import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationSearchCriteria;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationUpdate;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
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
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.ExperimentDAO.getFeedbackScores;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.JsonUtils.getJsonNodeOrDefault;
import static com.comet.opik.utils.JsonUtils.getStringOrDefault;

@ImplementedBy(OptimizationDAOImpl.class)
public interface OptimizationDAO {

    Mono<Void> insert(Optimization experiment);

    Mono<Optimization> getById(UUID id);

    Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids);

    Mono<Long> delete(Set<UUID> ids);

    Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds);

    Mono<Long> update(UUID id, OptimizationUpdate update);

    Mono<Optimization.OptimizationPage> find(int page, int size, @NonNull OptimizationSearchCriteria searchCriteria);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationDAOImpl implements OptimizationDAO {

    /**
     * The query validates if already exists with this id. Failing if so.
     * That way only insert is allowed, but not update.
     */
    private static final String INSERT = """
            INSERT INTO optimizations (
                id,
                dataset_id,
                name,
                workspace_id,
                objective_name,
                status,
                metadata,
                created_by,
                last_updated_by
            )
            SELECT
                if(
                    LENGTH(CAST(old.id AS Nullable(String))) > 0,
                    leftPad('', 40, '*'),
                    new.id
                ) as id,
                new.dataset_id,
                new.name,
                new.workspace_id,
                new.objective_name,
                new.status,
                new.metadata,
                new.created_by,
                new.last_updated_by
            FROM (
                SELECT
                :id AS id,
                :dataset_id AS dataset_id,
                :name AS name,
                :workspace_id AS workspace_id,
                :objective_name AS objective_name,
                :status AS status,
                :metadata AS metadata,
                :created_by AS created_by,
                :last_updated_by AS last_updated_by
            ) AS new
            LEFT JOIN (
                SELECT
                id
                FROM optimizations
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY last_updated_at DESC
                LIMIT 1 BY id
            ) AS old
            ON new.id = old.id
            ;
            """;

    private static final String FIND = """
            WITH trace_final AS (
                SELECT
                    id
                FROM traces
                WHERE workspace_id = :workspace_id
            ),
            feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    if(
                        notEmpty(arrayFilter(x -> length(x) > 0, groupArray(name))),
                        mapFromArrays(
                            arrayDistinct(arrayFilter(x -> length(x) > 0, groupArray(name))),
                            arrayMap(
                                vName -> if(
                                    arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, count_value, trace_id))
                                            )
                                        )
                                    ) = 0,
                                    0,
                                    arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, total_value, trace_id))
                                            )
                                        )
                                    ) / arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, count_value, trace_id))
                                            )
                                        )
                                    )
                                ),
                                arrayDistinct(arrayFilter(x -> length(x) > 0, groupArray(name)))
                            )
                        ),
                        map()
                    ) as feedback_scores
                FROM (
                    SELECT
                        ei.experiment_id,
                        tfs.name,
                        tfs.total_value,
                        tfs.count_value,
                        tfs.trace_id as trace_id
                    FROM experiment_items ei
                    JOIN (
                        SELECT
                            entity_id as trace_id,
                            name,
                            SUM(value) as total_value,
                            COUNT(value) as count_value
                        FROM (
                            SELECT
                                entity_id,
                                name,
                                value
                            FROM feedback_scores
                            WHERE workspace_id = :workspace_id
                            AND entity_type = :entity_type
                            AND entity_id IN (SELECT id FROM trace_final)
                            ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                            LIMIT 1 BY entity_id, name
                        )
                        GROUP BY
                            entity_id,
                            name
                    ) AS tfs ON ei.trace_id = tfs.trace_id
                    WHERE ei.trace_id IN (SELECT id FROM trace_final)
                )
                GROUP BY experiment_id
            ), experiments_fs AS (
                SELECT
                    e.id as id,
                    e.optimization_id as optimization_id,
                    fs.feedback_scores as feedback_scores
                FROM (
                    SELECT
                        id,
                        optimization_id
                    FROM experiments
                    WHERE workspace_id = :workspace_id
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS e
                LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            )
            SELECT
                o.*,
                COUNT(DISTINCT efs.id) AS num_trials,
                maxMap(efs.feedback_scores) AS feedback_scores
            FROM (
                SELECT
                    *
                FROM optimizations
                WHERE workspace_id = :workspace_id
                <if(id)>AND id = :id <endif>
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS o
            LEFT JOIN experiments_fs AS efs ON o.id = efs.optimization_id
            GROUP BY o.*
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            ;
            """;

    private static final String COUNT = """
            WITH trace_final AS (
                SELECT
                    id
                FROM traces
                WHERE workspace_id = :workspace_id
            ),
            feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    if(
                        notEmpty(arrayFilter(x -> length(x) > 0, groupArray(name))),
                        mapFromArrays(
                            arrayDistinct(arrayFilter(x -> length(x) > 0, groupArray(name))),
                            arrayMap(
                                vName -> if(
                                    arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, count_value, trace_id))
                                            )
                                        )
                                    ) = 0,
                                    0,
                                    arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, total_value, trace_id))
                                            )
                                        )
                                    ) / arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, count_value, trace_id))
                                            )
                                        )
                                    )
                                ),
                                arrayDistinct(arrayFilter(x -> length(x) > 0, groupArray(name)))
                            )
                        ),
                        map()
                    ) as feedback_scores
                FROM (
                    SELECT
                        ei.experiment_id,
                        tfs.name,
                        tfs.total_value,
                        tfs.count_value,
                        tfs.trace_id as trace_id
                    FROM experiment_items ei
                    JOIN (
                        SELECT
                            entity_id as trace_id,
                            name,
                            SUM(value) as total_value,
                            COUNT(value) as count_value
                        FROM (
                            SELECT
                                entity_id,
                                name,
                                value
                            FROM feedback_scores
                            WHERE workspace_id = :workspace_id
                            AND entity_type = :entity_type
                            AND entity_id IN (SELECT id FROM trace_final)
                            ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                            LIMIT 1 BY entity_id, name
                        )
                        GROUP BY
                            entity_id,
                            name
                    ) AS tfs ON ei.trace_id = tfs.trace_id
                    WHERE ei.trace_id IN (SELECT id FROM trace_final)
                )
                GROUP BY experiment_id
            ), experiments_fs AS (
                SELECT
                    e.id as id,
                    e.optimization_id as optimization_id,
                    fs.feedback_scores as feedback_scores
                FROM (
                    SELECT
                        id,
                        optimization_id
                    FROM experiments
                    WHERE workspace_id = :workspace_id
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS e
                LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            )
            SELECT
                COUNT(o.id) as count
            FROM (
                SELECT
                    id
                FROM optimizations
                WHERE workspace_id = :workspace_id
                <if(id)>AND id = :id <endif>
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS o
            LEFT JOIN experiments_fs AS efs ON o.id = efs.optimization_id
            GROUP BY o.*
            ;
            """;

    private static final String FIND_OPTIMIZATIONS_DATASET_IDS = """
            SELECT
                distinct dataset_id
            FROM optimizations
            WHERE workspace_id = :workspace_id
            <if(experiment_ids)> AND id IN :experiment_ids <endif>
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            ;
            """;

    private static final String DELETE_BY_IDS = """
            DELETE FROM optimizations
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String UPDATE_BY_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, objective_name, status, metadata, created_at, created_by, last_updated_by
            ) SELECT
                id,
                dataset_id,
                <if(name)> :name <else> name <endif> as name,
                workspace_id,
                objective_name,
                <if(status)> :status <else> status <endif> as status,
                metadata,
                created_at,
                created_by,
                :user_name as last_updated_by
            FROM optimizations
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String FIND_MOST_RECENT_CREATED_OPTIMIZATION_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	max(created_at) as created_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    created_at
                FROM optimizations
                WHERE dataset_id IN :dataset_ids
            	AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY dataset_id
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    public Mono<Void> insert(@NonNull Optimization optimization) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(optimization, connection))
                .then();
    }

    @Override
    public Mono<Optimization> getById(@NonNull UUID id) {
        var template = new ST(FIND);
        template.add("id", id.toString());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(
                        template.render(), connection,
                        statement -> statement.bind("id", id)))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    @Override
    public Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = new ST(FIND_OPTIMIZATIONS_DATASET_IDS);
                    template.add("experiment_ids", ids);
                    var statement = connection.createStatement(template.render());
                    statement.bind("experiment_ids", ids);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(this::mapDatasetId)
                .collectList();
    }

    @Override
    public Mono<Long> delete(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("Deleting optimizations by ids [{}]", Arrays.toString(ids.toArray()));

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Deleted optimizations by ids [{}]", Arrays.toString(ids.toArray()));
                    }
                });
    }

    @Override
    public Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(FIND_MOST_RECENT_CREATED_OPTIMIZATION_BY_DATASET_IDS);
                    statement.bind("dataset_ids", datasetIds);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new DatasetLastOptimizationCreated(
                        row.get("dataset_id", UUID.class),
                        row.get("created_at", Instant.class))));
    }

    @Override
    public Mono<Long> update(@NonNull UUID id, @NonNull OptimizationUpdate update) {
        log.info("Update optimization by id '{}'", id);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> update(id, update, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Updated optimization by id '{}'", id);
                    }
                });
    }

    @WithSpan
    public Mono<Optimization.OptimizationPage> find(int page, int size,
            @NonNull OptimizationSearchCriteria searchCriteria) {
        return getCount(searchCriteria)
                .flatMap(totalCount -> find(page, size, totalCount, searchCriteria))
                .defaultIfEmpty(Optimization.OptimizationPage.empty(page, List.of()));
    }

    private Mono<Long> getCount(@NotNull OptimizationSearchCriteria searchCriteria) {
        var template = new ST(COUNT);

        bindTemplateParams(template, searchCriteria);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(template.render());

                    bindQueryParams(searchCriteria, statement);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map(row -> row.get("count", Long.class)))
                .reduce(Long::sum);
    }

    private Mono<Optimization.OptimizationPage> find(int page, int size, long total,
            OptimizationSearchCriteria searchCriteria) {
        var template = new ST(FIND);

        bindTemplateParams(template, searchCriteria);

        var offset = (page - 1) * size;

        template.add("limit", size);
        template.add("offset", offset);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(template.render())
                            .bind("limit", size)
                            .bind("offset", offset);

                    bindQueryParams(searchCriteria, statement);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(this::mapToDto)
                .collectList()
                .map(optimizations -> new Optimization.OptimizationPage(page, optimizations.size(), total,
                        optimizations, List.of()));
    }

    private void bindTemplateParams(ST template, OptimizationSearchCriteria searchCriteria) {

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> template.add("dataset_deleted", datasetDeleted.toString()));

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> template.add("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> template.add("name", name));

        Optional.ofNullable(searchCriteria.entityType())
                .ifPresent(entityType -> template.add("entity_type", EntityType.TRACE.getType()));
    }

    private void bindQueryParams(OptimizationSearchCriteria searchCriteria, Statement statement) {

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> statement.bind("dataset_deleted", datasetDeleted));

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(searchCriteria.entityType())
                .ifPresent(entityType -> statement.bind("entity_type", EntityType.TRACE.getType()));
    }

    private Publisher<? extends Result> insert(Optimization optimization, Connection connection) {
        var statement = connection.createStatement(INSERT)
                .bind("id", optimization.id())
                .bind("dataset_id", optimization.datasetId())
                .bind("name", optimization.name())
                .bind("objective_name", optimization.objectiveName())
                .bind("status", optimization.status().getValue())
                .bind("metadata", getStringOrDefault(optimization.metadata()));

        return makeFluxContextAware((userName, workspaceId) -> {
            log.info("Inserting optimization with id '{}', datasetId '{}', datasetName '{}', workspaceId '{}'",
                    optimization.id(), optimization.datasetId(), optimization.datasetName(), workspaceId);
            statement.bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        });
    }

    private Publisher<? extends Result> get(String query, Connection connection, Function<Statement, Statement> bind) {
        var statement = connection.createStatement(query)
                .bind("entity_type", EntityType.TRACE.getType());
        return makeFluxContextAware(bindWorkspaceIdToFlux(bind.apply(statement)));
    }

    private Publisher<Optimization> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> {
            return Optimization.builder()
                    .id(row.get("id", UUID.class))
                    .name(row.get("name", String.class))
                    .datasetId(row.get("dataset_id", UUID.class))
                    .objectiveName(row.get("objective_name", String.class))
                    .status(OptimizationStatus.fromString(row.get("status", String.class)))
                    .metadata(getJsonNodeOrDefault(row.get("metadata", String.class)))
                    .createdAt(row.get("created_at", Instant.class))
                    .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                    .createdBy(row.get("created_by", String.class))
                    .lastUpdatedBy(row.get("last_updated_by", String.class))
                    .feedbackScores(getFeedbackScores(row))
                    .numTrials(row.get("num_trials", Long.class))
                    .build();
        });
    }

    private Publisher<DatasetEventInfoHolder> mapDatasetId(Result result) {
        return result.map((row, rowMetadata) -> new DatasetEventInfoHolder(row.get("dataset_id", UUID.class), null));
    }

    private Flux<? extends Result> delete(Set<UUID> ids, Connection connection) {

        var statement = connection.createStatement(DELETE_BY_IDS)
                .bind("ids", ids);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Flux<? extends Result> update(UUID id, OptimizationUpdate update, Connection connection) {
        ST template = buildUpdateTemplate(update);
        var statement = createUpdateStatement(id, update, connection, template.render());

        return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement));
    }

    private ST buildUpdateTemplate(OptimizationUpdate update) {
        ST template = new ST(UPDATE_BY_ID);

        Optional.ofNullable(update.name())
                .ifPresent(name -> template.add("name", name));

        Optional.ofNullable(update.status())
                .ifPresent(status -> template.add("status", status.getValue()));

        return template;
    }

    private Statement createUpdateStatement(UUID id, OptimizationUpdate update, Connection connection, String sql) {
        Statement statement = connection.createStatement(sql);

        Optional.ofNullable(update.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(update.status())
                .ifPresent(status -> statement.bind("status", status.getValue()));

        statement.bind("id", id);

        return statement;
    }
}
