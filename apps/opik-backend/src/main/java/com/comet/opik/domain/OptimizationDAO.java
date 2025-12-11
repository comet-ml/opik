package com.comet.opik.domain;

import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
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
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Instant;
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

    record OptimizationSummary(UUID datasetId, long optimizationCount, Instant mostRecentOptimizationAt) {
        public static OptimizationSummary empty(UUID datasetId) {
            return new OptimizationSummary(datasetId, 0, null);
        }
    }

    Mono<Void> upsert(Optimization optimization);

    Mono<Optimization> getById(UUID id);

    Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids);

    Mono<Long> delete(Set<UUID> ids);

    Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds);

    Mono<Long> update(UUID id, OptimizationUpdate update);

    Mono<Long> updateDatasetDeleted(Set<UUID> datasetIds);

    Mono<Optimization.OptimizationPage> find(int page, int size, @NonNull OptimizationSearchCriteria searchCriteria);

    Flux<OptimizationSummary> findOptimizationSummaryByDatasetIds(Set<UUID> datasetIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationDAOImpl implements OptimizationDAO {

    private static final String UPSERT = """
            INSERT INTO optimizations (
                id,
                dataset_id,
                name,
                workspace_id,
                objective_name,
                status,
                metadata,
                studio_config,
                created_by,
                last_updated_by,
                last_updated_at
            )
            VALUES (
                :id,
                :dataset_id,
                :name,
                :workspace_id,
                :objective_name,
                :status,
                :metadata,
                :studio_config,
                :created_by,
                :last_updated_by,
                COALESCE(parseDateTime64BestEffortOrNull(:last_updated_at, 6), now64(6))
            )
            ;
            """;

    private static final String FIND = """
            WITH optimization_final AS (
                SELECT
                    *
                FROM optimizations FINAL
                WHERE workspace_id = :workspace_id
                <if(id)>AND id = :id <endif>
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            ), experiments_final AS (
                SELECT
                    id,
                    optimization_id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND optimization_id IN (SELECT id FROM optimization_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_final AS (
                SELECT
                    DISTINCT
                        experiment_id,
                        trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = :entity_type
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = :entity_type
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_final)
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ), feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(value) AS values
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value
                FROM feedback_scores_combined_grouped
            ), feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(fs_avg.name),
                        groupArray(fs_avg.avg_value)
                    ) AS feedback_scores
                FROM (
                    SELECT
                        et.experiment_id,
                        fs.name,
                        avg(fs.value) AS avg_value
                    FROM experiment_items_final as et
                    LEFT JOIN (
                        SELECT
                            name,
                            entity_id AS trace_id,
                            value
                        FROM feedback_scores_final
                    ) fs ON fs.trace_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            )
            SELECT
                o.*,
                o.id as id,
                COUNT(DISTINCT e.id) FILTER (WHERE e.id != '') AS num_trials,
                maxMap(fs.feedback_scores) AS feedback_scores
            FROM optimization_final AS o
            LEFT JOIN experiments_final AS e ON o.id = e.optimization_id
            LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            GROUP BY o.*
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            ;
            """;

    private static final String COUNT = """
            SELECT
                COUNT(id) as count
            FROM (
                SELECT
                    id
                FROM optimizations FINAL
                WHERE workspace_id = :workspace_id
                <if(id)>AND id = :id <endif>
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            )
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
            	id, dataset_id, name, workspace_id, objective_name, status, metadata, created_at, created_by, last_updated_by, studio_config
            )
            SELECT
                id,
                dataset_id,
                <if(name)> :name <else> name <endif> as name,
                workspace_id,
                objective_name,
                <if(status)> :status <else> status <endif> as status,
                metadata,
                created_at,
                created_by,
                :user_name as last_updated_by,
                studio_config
            FROM optimizations
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SET_DATASET_DELETED_TO_TRUE_BY_DATASET_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, objective_name, status, metadata, created_at, created_by, last_updated_at, last_updated_by, dataset_deleted, studio_config
            )
            SELECT
                id,
                dataset_id,
                name as name,
                workspace_id,
                objective_name,
                status as status,
                metadata,
                created_at,
                created_by,
                last_updated_at,
                last_updated_by,
                true as dataset_deleted,
                studio_config
            FROM optimizations
            WHERE workspace_id = :workspace_id
            AND dataset_id IN :dataset_ids
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 by id
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

    private static final String FIND_OPTIMIZATION_SUMMARY_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	count(distinct id) as optimization_count,
            	max(last_updated_at) as most_recent_optimization_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    last_updated_at
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
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

    @Override
    public Mono<Void> upsert(@NonNull Optimization optimization) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> upsert(optimization, connection))
                .then();
    }

    @Override
    public Mono<Optimization> getById(@NonNull UUID id) {
        var template = TemplateUtils.newST(FIND);
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
                    var template = TemplateUtils.newST(FIND_OPTIMIZATIONS_DATASET_IDS);
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
        log.info("Deleting optimizations by ids, size '{}'", ids.size());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Deleted optimizations by ids, size '{}'", ids.size());
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

    @Override
    public Mono<Long> updateDatasetDeleted(@NonNull Set<UUID> datasetIds) {
        log.info("Set to true optimization dataset_deleted for datasetIds '{}'", datasetIds);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> updateDatasetDeleted(datasetIds, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Set to true optimization dataset_deleted is done for datasetIds '{}'", datasetIds);
                    }
                });
    }

    @Override
    public Mono<Optimization.OptimizationPage> find(int page, int size,
            @NonNull OptimizationSearchCriteria searchCriteria) {
        return getCount(searchCriteria)
                .flatMap(totalCount -> find(page, size, totalCount, searchCriteria))
                .defaultIfEmpty(Optimization.OptimizationPage.empty(page, List.of()));
    }

    @Override
    public Flux<OptimizationSummary> findOptimizationSummaryByDatasetIds(@NonNull Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Flux.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(FIND_OPTIMIZATION_SUMMARY_BY_DATASET_IDS);

                    statement.bind("dataset_ids", datasetIds);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new OptimizationSummary(
                        row.get("dataset_id", UUID.class),
                        row.get("optimization_count", Long.class),
                        row.get("most_recent_optimization_at", Instant.class))));
    }

    private Mono<Long> getCount(OptimizationSearchCriteria searchCriteria) {
        var template = TemplateUtils.newST(COUNT);

        bindTemplateParams(template, searchCriteria);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(template.render());

                    bindQueryParams(searchCriteria, statement, false);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map(row -> row.get("count", Long.class)))
                .reduce(Long::sum);
    }

    private Mono<Optimization.OptimizationPage> find(int page, int size, long total,
            OptimizationSearchCriteria searchCriteria) {
        var template = TemplateUtils.newST(FIND);

        bindTemplateParams(template, searchCriteria);

        var offset = (page - 1) * size;

        template.add("limit", size);
        template.add("offset", offset);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(template.render())
                            .bind("limit", size)
                            .bind("offset", offset);

                    bindQueryParams(searchCriteria, statement, true);

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

        Optional.ofNullable(searchCriteria.studioOnly())
                .filter(Boolean.TRUE::equals)
                .ifPresent(studioOnly -> template.add("studio_only", "true"));

        Optional.ofNullable(searchCriteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.OPTIMIZATION))
                .ifPresent(optimizationFilters -> template.add("filters", optimizationFilters));

        Optional.ofNullable(searchCriteria.entityType())
                .ifPresent(entityType -> template.add("entity_type", EntityType.TRACE.getType()));
    }

    private void bindQueryParams(OptimizationSearchCriteria searchCriteria, Statement statement, boolean isFindQuery) {

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> statement.bind("dataset_deleted", datasetDeleted));

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(searchCriteria.filters())
                .ifPresent(filters -> filterQueryBuilder.bind(statement, filters, FilterStrategy.OPTIMIZATION));

        if (isFindQuery) {
            Optional.ofNullable(searchCriteria.entityType())
                    .ifPresent(entityType -> statement.bind("entity_type", EntityType.TRACE.getType()));
        }
    }

    private Publisher<? extends Result> upsert(Optimization optimization, Connection connection) {

        var statement = connection.createStatement(UPSERT)
                .bind("id", optimization.id())
                .bind("dataset_id", optimization.datasetId())
                .bind("name", optimization.name())
                .bind("objective_name", optimization.objectiveName())
                .bind("status", optimization.status().getValue())
                .bind("metadata", getStringOrDefault(optimization.metadata()));

        if (optimization.studioConfig() != null) {
            try {
                String studioConfigJson = JsonUtils.writeValueAsString(optimization.studioConfig());
                statement.bind("studio_config", studioConfigJson);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to serialize studio_config for optimization: '%s'".formatted(optimization.id()), e);
            }
        } else {
            statement.bindNull("studio_config", String.class);
        }

        if (optimization.lastUpdatedAt() != null) {
            statement.bind("last_updated_at", optimization.lastUpdatedAt().toString());
        } else {
            statement.bindNull("last_updated_at", String.class);
        }

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
            OptimizationStudioConfig studioConfig = null;
            String studioConfigJson = row.get("studio_config", String.class);
            if (StringUtils.isNotEmpty(studioConfigJson)) {
                try {
                    studioConfig = JsonUtils.readValue(studioConfigJson, OptimizationStudioConfig.class);
                } catch (Exception e) {
                    log.error("Failed to deserialize studio_config for optimization: '{}'",
                            row.get("id", UUID.class), e);
                }
            }

            return Optimization.builder()
                    .id(row.get("id", UUID.class))
                    .name(row.get("name", String.class))
                    .datasetId(row.get("dataset_id", UUID.class))
                    .objectiveName(row.get("objective_name", String.class))
                    .status(OptimizationStatus.fromString(row.get("status", String.class)))
                    .metadata(getJsonNodeOrDefault(row.get("metadata", String.class)))
                    .studioConfig(studioConfig)
                    .createdAt(row.get("created_at", Instant.class))
                    .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                    .createdBy(row.get("created_by", String.class))
                    .lastUpdatedBy(row.get("last_updated_by", String.class))
                    .feedbackScores(getFeedbackScores(row, "feedback_scores"))
                    .numTrials(row.get("num_trials", Long.class))
                    .build();
        });
    }

    private Publisher<DatasetEventInfoHolder> mapDatasetId(Result result) {
        return result.map((row, rowMetadata) -> new DatasetEventInfoHolder(row.get("dataset_id", UUID.class), null));
    }

    private Flux<? extends Result> delete(Set<UUID> ids, Connection connection) {

        var statement = connection.createStatement(DELETE_BY_IDS)
                .bind("ids", ids.toArray(UUID[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Flux<? extends Result> update(UUID id, OptimizationUpdate update, Connection connection) {
        var template = buildUpdateTemplate(update);

        var statement = createUpdateStatement(id, update, connection, template.render());

        return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement));
    }

    private Flux<? extends Result> updateDatasetDeleted(Set<UUID> datasetIds, Connection connection) {
        Statement statement = connection.createStatement(SET_DATASET_DELETED_TO_TRUE_BY_DATASET_ID);
        statement.bind("dataset_ids", datasetIds);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private ST buildUpdateTemplate(OptimizationUpdate update) {
        var template = TemplateUtils.newST(UPDATE_BY_ID);

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
