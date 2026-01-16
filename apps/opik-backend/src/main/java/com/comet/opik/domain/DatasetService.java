package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.DatasetStatus;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.events.DatasetsDeleted;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.BatchOperationsConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.AsyncUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.net.URI;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.Dataset.DatasetPage;
import static com.comet.opik.domain.ExperimentItemDAO.ExperimentSummary;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

@ImplementedBy(DatasetServiceImpl.class)
public interface DatasetService {

    Dataset save(Dataset dataset);

    UUID getOrCreate(String workspaceId, String name, String userName);

    Mono<UUID> getOrCreateDataset(String datasetName);

    Optional<Dataset> getById(UUID id, String workspaceId);

    void update(UUID id, DatasetUpdate dataset);

    Dataset findById(UUID id);

    String findWorkspaceIdByDatasetId(UUID id);

    Dataset findById(UUID id, String workspaceId, Visibility visibility);

    List<Dataset> findByIds(Set<UUID> ids, String workspaceId);

    Dataset findByName(String workspaceId, String name, Visibility visibility);

    void delete(DatasetIdentifier identifier);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    DatasetPage find(int page, int size, DatasetCriteria criteria, List<SortingField> sortingFields);

    Mono<Void> recordExperiments(Set<DatasetLastExperimentCreated> datasetsLastExperimentCreated);

    Mono<Void> recordOptimizations(Set<DatasetLastOptimizationCreated> datasetsLastOptimizationCreated);

    BiInformationResponse getDatasetBIInformation();

    Set<UUID> exists(Set<UUID> datasetIds, String workspaceId);

    long getDailyCreatedCount();

    void updateStatus(UUID id, String workspaceId, DatasetStatus status);

    List<Dataset.PublicWorkspaceInfo> findPublicWorkspacesWithDatasets();

    DatasetPage findPublicDatasetsByWorkspace(String workspaceName, int page, int size, String name);

    Dataset importDataset(String sourceWorkspaceName, UUID sourceDatasetId, String targetName, String targetDescription);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetServiceImpl implements DatasetService {

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ExperimentItemDAO experimentItemDAO;
    private final @NonNull DatasetItemDAO datasetItemDAO;
    private final @NonNull ExperimentDAO experimentDAO;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull @Config BatchOperationsConfig batchOperationsConfig;
    private final @NonNull OptimizationDAO optimizationDAO;
    private final @NonNull EventBus eventBus;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull WorkspaceNameService workspaceNameService;
    private final @NonNull DatasetItemService datasetItemService;
    private final @NonNull @Config("authentication") AuthenticationConfig authenticationConfig;
    private final @NonNull Client client;

    private static String formatDatasetAlreadyExistsMessage(String datasetName) {
        return "Dataset already exists with name '%s'".formatted(datasetName);
    }

    @Override
    public Dataset save(@NonNull Dataset dataset) {

        var builder = dataset.id() == null
                ? dataset.toBuilder().id(idGenerator.generateId())
                : dataset.toBuilder();

        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        builder
                .createdBy(userName)
                .lastUpdatedBy(userName);

        var newDataset = builder.build();

        IdGenerator.validateVersion(newDataset.id(), "dataset");

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);

            try {
                dao.save(newDataset, workspaceId);
                return dao.findById(newDataset.id(), workspaceId).orElseThrow();
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    String message = formatDatasetAlreadyExistsMessage(dataset.name());
                    log.info(message);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(message)));
                } else {
                    throw e;
                }
            }
        });
    }

    @Override
    public UUID getOrCreate(@NonNull String workspaceId, @NonNull String name, @NonNull String userName) {
        var dataset = template.inTransaction(READ_ONLY,
                handle -> handle.attach(DatasetDAO.class).findByName(workspaceId, name));

        if (dataset.isEmpty()) {

            UUID id = idGenerator.generateId();
            log.info("Creating dataset with id '{}', name '{}', workspaceId '{}'", id, name, workspaceId);
            template.inTransaction(WRITE, handle -> {
                handle.attach(DatasetDAO.class)
                        .save(
                                Dataset.builder()
                                        .id(id)
                                        .name(name)
                                        .visibility(Visibility.PRIVATE)
                                        .createdBy(userName)
                                        .lastUpdatedBy(userName)
                                        .build(),
                                workspaceId);
                return null;
            });
            log.info("Created dataset with id '{}', name '{}', workspaceId '{}'", id, name, workspaceId);
            return id;
        }

        UUID id = dataset.get().id();
        log.info("Got dataset with id '{}', name '{}', workspaceId '{}'", id, name, workspaceId);
        return id;
    }

    @Override
    public Mono<UUID> getOrCreateDataset(String datasetName) {
        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> getOrCreate(workspaceId, datasetName, userName))
                    .subscribeOn(Schedulers.boundedElastic());
        })
                .onErrorResume(throwable -> handleDatasetCreationError(throwable, datasetName)
                        .map(Dataset::id));
    }

    @Override
    public Optional<Dataset> getById(@NonNull UUID id, @NonNull String workspaceId) {
        log.info("Getting dataset with id '{}', workspaceId '{}'", id, workspaceId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            var dataset = dao.findById(id, workspaceId);
            log.info("Got dataset with id '{}', workspaceId '{}'", id, workspaceId);
            return dataset;
        });
    }

    @Override
    public void update(@NonNull UUID id, @NonNull DatasetUpdate dataset) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);

            try {
                int result = dao.update(workspaceId, id, dataset, userName);

                if (result == 0) {
                    throw newNotFoundException();
                }
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    String message = formatDatasetAlreadyExistsMessage(dataset.name());
                    log.info(message);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(message)));
                } else {
                    throw e;
                }
            }

            return null;
        });
    }

    @Override
    public Dataset findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        Visibility visibility = requestContext.get().getVisibility();

        return enrichDatasetWithAdditionalInformation(List.of(findById(id, workspaceId, visibility)))
                .get(0);
    }

    @Override
    public String findWorkspaceIdByDatasetId(@NonNull UUID id) {
        log.info("Finding workspaceId by dataset id '{}'", id);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            var workspaceId = dao.findWorkspaceIdByDatasetId(id).orElseThrow(this::newNotFoundException);
            log.info("Found workspaceId by dataset id '{}'", id);
            return workspaceId;
        });
    }

    @Override
    public Dataset findById(@NonNull UUID id, @NonNull String workspaceId, Visibility visibility) {
        log.info("Finding dataset with id '{}', workspaceId '{}'", id, workspaceId);
        Dataset dataset = template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            var d = dao.findById(id, workspaceId).orElseThrow(this::newNotFoundException);
            log.info("Found dataset with id '{}', workspaceId '{}'", id, workspaceId);
            return d;
        });

        return verifyVisibility(dataset, visibility);
    }

    @Override
    public List<Dataset> findByIds(@NonNull Set<UUID> ids, @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("Returning empty datasets for empty ids, workspaceId '{}'", workspaceId);
            return List.of();
        }
        log.info("Finding datasets with ids '{}', workspaceId '{}'", ids, workspaceId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            var datasets = dao.findByIds(ids, workspaceId);
            log.info("Found datasets with ids '{}', workspaceId '{}'", ids, workspaceId);
            return datasets;
        });
    }

    @Override
    public Dataset findByName(@NonNull String workspaceId, @NonNull String name, Visibility visibility) {
        Dataset dataset = template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);

            Dataset d = dao.findByName(workspaceId, name).orElseThrow(this::newNotFoundException);

            log.info("Found dataset with name '{}', id '{}', workspaceId '{}'", name, d.id(), workspaceId);
            return d;
        });

        return verifyVisibility(dataset, visibility);
    }

    /**
     * Deletes a dataset by name.
     * <br>
     * The dataset items are not deleted, because they may be linked to experiments.
     **/
    @Override
    public void delete(@NonNull DatasetIdentifier identifier) {
        String workspaceId = requestContext.get().getWorkspaceId();

        Dataset dataset = findByName(workspaceId, identifier.datasetName(), Visibility.PRIVATE);

        template.inTransaction(WRITE, handle -> {
            deleteDatasetVersionData(handle, Set.of(dataset.id()), workspaceId);

            var datasetDao = handle.attach(DatasetDAO.class);
            datasetDao.delete(workspaceId, identifier.datasetName());
            return null;
        });

        eventBus.post(new DatasetsDeleted(
                Set.of(dataset.id()),
                workspaceId,
                requestContext.get().getUserName()));
    }

    private NotFoundException newNotFoundException() {
        String message = "Dataset not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    /**
     * Deletes a dataset by id.
     * <br>
     * The dataset items are not deleted, because they may be linked to experiments.
     **/
    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            deleteDatasetVersionData(handle, Set.of(id), workspaceId);

            var datasetDao = handle.attach(DatasetDAO.class);
            datasetDao.delete(id, workspaceId);
            return null;
        });

        eventBus.post(new DatasetsDeleted(
                Set.of(id),
                workspaceId,
                requestContext.get().getUserName()));
    }

    @Override
    public void delete(Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            deleteDatasetVersionData(handle, ids, workspaceId);

            var datasetDao = handle.attach(DatasetDAO.class);
            datasetDao.delete(ids, workspaceId);
            return null;
        });

        eventBus.post(new DatasetsDeleted(
                ids,
                workspaceId,
                requestContext.get().getUserName()));
    }

    /**
     * Deletes version-related data for datasets to avoid foreign key constraint violations.
     * <p>
     * This method must be called before deleting datasets to ensure proper cleanup of:
     * <ul>
     *   <li>dataset_version_tags (child table with FK to datasets)</li>
     *   <li>dataset_versions (child table with FK to datasets)</li>
     * </ul>
     *
     * @param handle the JDBI handle for the current transaction
     * @param datasetIds the set of dataset IDs to delete version data for
     * @param workspaceId the workspace ID
     */
    private void deleteDatasetVersionData(Handle handle, Set<UUID> datasetIds, String workspaceId) {
        var versionDao = handle.attach(DatasetVersionDAO.class);

        // Delete in the correct order to respect foreign key constraints
        versionDao.deleteAllTagsByDatasetIds(datasetIds, workspaceId);
        versionDao.deleteAllVersionsByDatasetIds(datasetIds, workspaceId);
    }

    @Override
    public DatasetPage find(int page, int size, @NonNull DatasetCriteria criteria, List<SortingField> sortingFields) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        Visibility visibility = requestContext.get().getVisibility();

        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        String filtersSQL = Optional.ofNullable(criteria.filters())
                .flatMap(f -> filterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.DATASET))
                .orElse(null);

        Map<String, Object> filterMapping = Optional.ofNullable(criteria.filters())
                .map(filterQueryBuilder::toStateSQLMapping)
                .orElse(Map.of());

        // withExperimentsOnly refers to Regular experiments only
        if (criteria.withExperimentsOnly() || criteria.promptId() != null) {

            Mono<Set<UUID>> datasetIds = experimentDAO.findAllDatasetIds(criteria)
                    .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, userName, workspaceId))
                    .map(dto -> dto.stream()
                            .filter(datasetEventInfoHolder -> datasetEventInfoHolder.type() == ExperimentType.REGULAR)
                            .map(DatasetEventInfoHolder::datasetId)
                            .collect(toSet()));

            DatasetPage datasetPage = datasetIds.flatMap(ids -> {

                int maxExperimentInClauseSize = batchOperationsConfig.getDatasets().getMaxExperimentInClauseSize();

                if (ids.isEmpty()) {
                    return Mono.just(DatasetPage.empty(page, sortingFactory.getSortableFields()));
                } else {
                    if (ids.size() <= maxExperimentInClauseSize) {
                        return fetchUsingMemory(page, size, criteria, ids, workspaceId, sortingFieldsSql, visibility,
                                filtersSQL, filterMapping);
                    } else {
                        return fetchUsingTempTable(page, size, criteria, ids, workspaceId, sortingFieldsSql,
                                visibility, filtersSQL, filterMapping);
                    }
                }
            }).subscribeOn(Schedulers.boundedElastic()).block();

            return DatasetPage.builder()
                    .content(enrichDatasetWithAdditionalInformation(datasetPage.content()))
                    .page(datasetPage.page())
                    .size(datasetPage.size())
                    .total(datasetPage.total())
                    .sortableBy(sortingFactory.getSortableFields())
                    .build();
        }

        // For now, we are not going to use the criteria.withExperimentsOnly() method due to the migration.
        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(DatasetDAO.class);
            int offset = (page - 1) * size;

            long count = repository.findCount(workspaceId, criteria.name(), criteria.withExperimentsOnly(),
                    criteria.withOptimizationsOnly(), visibility, filtersSQL, filterMapping);

            List<Dataset> datasets = enrichDatasetWithAdditionalInformation(
                    repository.find(size, offset, workspaceId, criteria.name(), criteria.withExperimentsOnly(),
                            criteria.withOptimizationsOnly(),
                            sortingFieldsSql, visibility, filtersSQL, filterMapping));

            return new DatasetPage(datasets, page, datasets.size(), count, sortingFactory.getSortableFields());
        });
    }

    private Mono<DatasetPage> fetchUsingTempTable(int page, int size, DatasetCriteria criteria, Set<UUID> ids,
            String workspaceId, String sortingFields, Visibility visibility, String filters,
            Map<String, Object> filterMapping) {

        String tableName = idGenerator.generateId().toString().replace("-", "_");
        int maxExperimentInClauseSize = batchOperationsConfig.getDatasets().getMaxExperimentInClauseSize();

        return Mono.fromCallable(() -> {

            // Create a temporary table to store the dataset ids
            template.inTransaction(WRITE, handle -> {
                var repository = handle.attach(DatasetDAO.class);
                repository.createTempTable(tableName);
                return null;
            });

            // Insert the dataset ids into the temporary table
            Lists.partition(List.copyOf(ids), maxExperimentInClauseSize).forEach(chunk -> {
                template.inTransaction(WRITE, handle -> {
                    var repository = handle.attach(DatasetDAO.class);
                    return repository.insertTempTable(tableName, chunk);
                });
            });

            return template.inTransaction(READ_ONLY, handle -> {
                var repository = handle.attach(DatasetDAO.class);
                long count = repository.findCountByTempTable(workspaceId, tableName, criteria.name(), visibility,
                        filters, filterMapping);
                int offset = (page - 1) * size;
                List<Dataset> datasets = repository.findByTempTable(workspaceId, tableName, criteria.name(), size,
                        offset, sortingFields, visibility, filters, filterMapping);
                return new DatasetPage(datasets, page, datasets.size(), count, sortingFactory.getSortableFields());
            });
        }).doFinally(signalType -> {
            template.inTransaction(WRITE, handle -> {
                var repository = handle.attach(DatasetDAO.class);
                repository.dropTempTable(tableName);
                return null;
            });
        });
    }

    private Mono<DatasetPage> fetchUsingMemory(int page, int size, DatasetCriteria criteria, Set<UUID> ids,
            String workspaceId, String sortingFields, Visibility visibility, String filters,
            Map<String, Object> filterMapping) {
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(DatasetDAO.class);
            long count = repository.findCountByIds(workspaceId, ids, criteria.name(), visibility, filters,
                    filterMapping);
            int offset = (page - 1) * size;
            List<Dataset> datasets = repository.findByIds(workspaceId, ids, criteria.name(), size, offset,
                    sortingFields, visibility, filters, filterMapping);
            return new DatasetPage(datasets, page, datasets.size(), count, sortingFactory.getSortableFields());
        }));
    }

    @Override
    public BiInformationResponse getDatasetBIInformation() {
        log.info("Getting dataset BI events daily data");
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            var biInformation = dao.getDatasetsBIInformation(DemoData.DATASETS);
            return BiInformationResponse.builder()
                    .biInformation(biInformation)
                    .build();
        });
    }

    @Override
    public Set<UUID> exists(@NonNull Set<UUID> datasetIds, @NonNull String workspaceId) {

        if (datasetIds.isEmpty()) {
            return Set.of();
        }

        int maxExperimentInClauseSize = batchOperationsConfig.getDatasets().getMaxExperimentInClauseSize();

        if (datasetIds.size() > maxExperimentInClauseSize) {

            log.info("Checking dataset existence using temporary table");

            String tableName = idGenerator.generateId().toString().replace("-", "_");

            template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DatasetDAO.class);
                dao.createTempTable(tableName);
                return null;
            });

            Lists.partition(List.copyOf(datasetIds), maxExperimentInClauseSize).forEach(chunk -> {
                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetDAO.class);
                    return dao.insertTempTable(tableName, chunk);
                });
            });

            try {
                return template.inTransaction(READ_ONLY, handle -> {
                    var dao = handle.attach(DatasetDAO.class);
                    return dao.existsByTempTable(workspaceId, tableName);
                });
            } finally {
                template.inTransaction(WRITE, handle -> {
                    var dao = handle.attach(DatasetDAO.class);
                    dao.dropTempTable(tableName);
                    return null;
                });
            }
        }

        log.info("Checking dataset existence using memory");

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            return dao.exists(datasetIds, workspaceId);
        });
    }

    private List<Dataset> enrichDatasetWithAdditionalInformation(List<Dataset> datasets) {
        Set<UUID> ids = datasets.stream().map(Dataset::id).collect(toSet());

        Map<UUID, ExperimentSummary> experimentSummary = experimentItemDAO.findExperimentSummaryByDatasetIds(ids)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext))
                .toStream()
                .collect(toMap(ExperimentSummary::datasetId, Function.identity()));

        Map<UUID, DatasetItemSummary> datasetItemSummaryMap = datasetItemDAO.findDatasetItemSummaryByDatasetIds(ids)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext))
                .toStream()
                .collect(toMap(DatasetItemSummary::datasetId, Function.identity()));

        Map<UUID, OptimizationDAO.OptimizationSummary> optimizationSummaryMap = optimizationDAO
                .findOptimizationSummaryByDatasetIds(ids)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, requestContext))
                .toStream()
                .collect(toMap(OptimizationDAO.OptimizationSummary::datasetId, Function.identity()));

        Map<UUID, DatasetVersion> latestVersionsByDatasetId = fetchLatestVersionsByDatasetIds(ids);

        return datasets.stream()
                .map(dataset -> {
                    var resume = experimentSummary.computeIfAbsent(dataset.id(), ExperimentSummary::empty);
                    var datasetItemSummary = datasetItemSummaryMap.computeIfAbsent(dataset.id(),
                            DatasetItemSummary::empty);
                    var optimizationSummary = optimizationSummaryMap.computeIfAbsent(dataset.id(),
                            OptimizationDAO.OptimizationSummary::empty);
                    var latestVersion = latestVersionsByDatasetId.get(dataset.id());

                    // When versioning is enabled and a latest version exists, use itemsTotal from the version
                    // Otherwise, fall back to the legacy dataset_items count
                    Long itemsCount;
                    if (featureFlags.isDatasetVersioningEnabled() && latestVersion != null
                            && latestVersion.itemsTotal() != null) {
                        itemsCount = latestVersion.itemsTotal().longValue();
                    } else {
                        itemsCount = datasetItemSummary.datasetItemsCount();
                    }

                    return dataset.toBuilder()
                            .experimentCount(resume.experimentCount())
                            .datasetItemsCount(itemsCount)
                            .optimizationCount(optimizationSummary.optimizationCount())
                            .mostRecentExperimentAt(resume.mostRecentExperimentAt())
                            .mostRecentOptimizationAt(optimizationSummary.mostRecentOptimizationAt())
                            .latestVersion(DatasetVersionMapper.INSTANCE.toDatasetVersionSummary(latestVersion))
                            .build();
                })
                .toList();
    }

    private Map<UUID, DatasetVersion> fetchLatestVersionsByDatasetIds(Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Map.of();
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            List<DatasetVersion> latestVersions = dao.findLatestVersionsByDatasetIds(datasetIds, workspaceId);
            return latestVersions.stream()
                    .collect(toMap(DatasetVersion::datasetId, Function.identity()));
        });
    }

    @Override
    @WithSpan
    public Mono<Void> recordExperiments(Set<DatasetLastExperimentCreated> datasetsLastExperimentCreated) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetsLastExperimentCreated),
                "Argument 'datasetsLastExperimentCreated' must not be empty");

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromRunnable(() -> template.inTransaction(WRITE, handle -> {

                var dao = handle.attach(DatasetDAO.class);

                int[] results = dao.recordExperiments(workspaceId, datasetsLastExperimentCreated);

                log.info("Updated '{}' datasets with last experiment created time", results.length);

                return Mono.empty();
            }));
        }).subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    @WithSpan
    public Mono<Void> recordOptimizations(Set<DatasetLastOptimizationCreated> datasetsLastOptimizationCreated) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetsLastOptimizationCreated),
                "Argument 'datasetsLastOptimizationCreated' must not be empty");

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromRunnable(() -> template.inTransaction(WRITE, handle -> {

                var dao = handle.attach(DatasetDAO.class);

                int[] results = dao.recordOptimizations(workspaceId, datasetsLastOptimizationCreated);

                log.info("Updated '{}' datasets with last optimization created time", results.length);

                return Mono.empty();
            }));
        }).subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    @WithSpan
    public long getDailyCreatedCount() {
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            return dao.getDatasetsBIInformation(DemoData.DATASETS)
                    .stream()
                    .mapToLong(BiInformationResponse.BiInformation::count)
                    .sum();
        });
    }

    @Override
    @WithSpan
    public void updateStatus(@NonNull UUID id, @NonNull String workspaceId,
            @NonNull DatasetStatus status) {
        log.info("Updating status for dataset '{}' on workspaceId '{}' to '{}'", id, workspaceId, status);
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            int result = dao.updateStatus(workspaceId, id, status);

            if (result == 0) {
                log.warn("Dataset '{}' not found on workspaceId '{}' - status update skipped", id, workspaceId);
                return null;
            }

            log.info("Successfully updated status for dataset '{}' on workspaceId '{}' to '{}'", id,
                    workspaceId, status);
            return null;
        });
    }

    private Dataset verifyVisibility(@NonNull Dataset dataset, Visibility visibility) {
        boolean publicOnly = Optional.ofNullable(visibility)
                .map(v -> v == Visibility.PUBLIC)
                .orElse(false);

        return Optional.of(dataset)
                .filter(d -> !publicOnly || d.visibility() == Visibility.PUBLIC)
                .orElseThrow(this::newNotFoundException);
    }

    private Mono<Dataset> handleDatasetCreationError(Throwable throwable, String datasetName) {
        if (throwable instanceof EntityAlreadyExistsException) {
            return Mono.deferContextual(ctx -> {
                String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                return Mono.fromCallable(() -> findByName(workspaceId, datasetName, Visibility.PRIVATE))
                        .subscribeOn(Schedulers.boundedElastic());
            });
        }
        return Mono.error(throwable);
    }

    @Override
        // Get all workspace IDs that have at least one public dataset
        List<String> workspaceIds = template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            return dao.findPublicWorkspaceIds();
        });

        Optional<String> reactServiceBaseUrl = Optional.ofNullable(authenticationConfig.getReactService())
                .map(AuthenticationConfig.UrlConfig::url);
        
        if (reactServiceBaseUrl.isEmpty()) {
            log.warn("React service is not configured, cannot resolve workspace names");
            return List.of();
        }

        if (workspaceIds.isEmpty()) {
            log.info("No workspaces found with datasets");
            return List.of();
        }

        // Convert workspace IDs to names using React service
        List<Dataset.PublicWorkspaceInfo> workspaces = workspaceIds.stream()
                .map(workspaceId -> {
                    try {
                        String workspaceName = workspaceNameService.getWorkspaceName(workspaceId, reactServiceBaseUrl.get());
                        return new Dataset.PublicWorkspaceInfo(workspaceId, workspaceName);
                    } catch (javax.ws.rs.WebApplicationException | javax.ws.rs.ProcessingException e) {
                        log.warn("Failed to get workspace name for workspaceId '{}': {}", workspaceId, e.getMessage());
                        // Use workspace ID as fallback if name resolution fails
                        return new Dataset.PublicWorkspaceInfo(workspaceId, workspaceId);
                    }
                })
                .collect(Collectors.toList());

        log.info("Found {} accessible workspaces with datasets", workspaces.size());
        return workspaces;
    }

    @Override
    public DatasetPage findPublicDatasetsByWorkspace(String workspaceName, int page, int size, String name) {
        log.info("Finding accessible datasets for workspace '{}', page '{}', size '{}', name '{}'", workspaceName, page, size, name);

        // Convert workspace name to ID
        String workspaceId = getWorkspaceId(workspaceName);
        log.info("Resolved workspace name '{}' to workspace ID '{}'", workspaceName, workspaceId);

        // Note: We return all datasets from the workspace (not filtered by visibility)
        // The frontend will only call this with workspaces the user has access to,
        // so returning all datasets is appropriate.
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetDAO.class);
            int offset = (page - 1) * size;

            // Use PUBLIC visibility to return only public datasets
            long count = dao.findCount(workspaceId, name, false, false, Visibility.PUBLIC, null, Map.of());
            log.info("Found {} public datasets for workspace ID '{}'", count, workspaceId);
            List<Dataset> datasets = dao.find(size, offset, workspaceId, name, false, false, "", Visibility.PUBLIC, null, Map.of());
            log.info("Retrieved {} public datasets for workspace ID '{}'", datasets.size(), workspaceId);

            List<Dataset> enrichedDatasets = enrichDatasetWithAdditionalInformation(datasets);

            return new DatasetPage(enrichedDatasets, page, enrichedDatasets.size(), count, sortingFactory.getSortableFields());
        });
    }

    @Override
    public Dataset importDataset(String sourceWorkspaceName, UUID sourceDatasetId, String targetName, String targetDescription) {
        log.info("Importing dataset '{}' from workspace '{}' to current workspace with name '{}'",
                sourceDatasetId, sourceWorkspaceName, targetName);

        String targetWorkspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Convert source workspace name to ID
        String sourceWorkspaceId = getWorkspaceId(sourceWorkspaceName);

        // Verify source dataset exists and user has access to it
        // Note: We don't check visibility here - if the user can see the workspace/dataset in the UI,
        // they have access to it. The frontend only shows workspaces/datasets the user has access to.
        Dataset sourceDataset = findById(sourceDatasetId, sourceWorkspaceId, null);
        if (sourceDataset == null) {
            throw new NotFoundException("Dataset not found or you don't have access to it");
        }

        // Create new dataset in target workspace
        Dataset newDataset = save(Dataset.builder()
                .name(targetName)
                .description(targetDescription)
                .visibility(Visibility.PRIVATE)
                .build());

        log.info("Created new dataset '{}' in workspace '{}', copying items from source dataset '{}'",
                newDataset.id(), targetWorkspaceId, sourceDatasetId);

        // Copy items from source dataset to target dataset
        copyItemsFromDataset(sourceWorkspaceId, sourceDatasetId, newDataset.id())
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, targetWorkspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();

        log.info("Successfully imported dataset '{}' from workspace '{}' to dataset '{}' in workspace '{}'",
                sourceDatasetId, sourceWorkspaceName, newDataset.id(), targetWorkspaceId);

        return newDataset;
    }

    private String getWorkspaceId(String workspaceName) {
        // If workspaceName is already a UUID (workspace ID), return it directly
        // This handles the case where workspace name resolution failed and we got the ID as the name
        try {
            UUID.fromString(workspaceName);
            // It's a valid UUID, so it's already a workspace ID
            log.debug("Workspace identifier '{}' is a UUID, using it directly as workspace ID", workspaceName);
            return workspaceName;
        } catch (IllegalArgumentException e) {
            // Not a UUID, so it's a workspace name that needs to be resolved
        }

        Optional<String> reactServiceBaseUrl = Optional.ofNullable(authenticationConfig.getReactService())
                .map(AuthenticationConfig.UrlConfig::url);
        
        if (reactServiceBaseUrl.isEmpty()) {
            log.error("React service is not configured, cannot resolve workspace name '{}'", workspaceName);
            throw new NotFoundException("Workspace not found: " + workspaceName);
        }

        String reactServiceBaseUrlValue = reactServiceBaseUrl.get();

        Response response = null;
        try {
            response = client.target(URI.create(reactServiceBaseUrlValue))
                    .path("workspaces")
                    .path("workspace-id")
                    .queryParam("name", workspaceName)
                    .request()
                    .get();

            if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                String workspaceId = response.readEntity(String.class);
                log.debug("Resolved workspace '{}' to ID '{}' using React service", workspaceName, workspaceId);
                return workspaceId;
            } else {
                // This will throw an appropriate exception based on the response status
                getWorkspaceIdFromResponse(response);
                // Should never reach here, but needed for compilation
                throw new NotFoundException("Workspace not found: " + workspaceName);
            }
        } catch (NotFoundException e) {
            // Re-throw NotFoundException as-is
            throw e;
        } catch (Exception e) {
            log.error("Failed to get workspace ID from React service for '{}'", workspaceName, e);
            throw new NotFoundException("Workspace not found: " + workspaceName);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Exception e) {
                    log.warn("Failed to close response: {}", e.getMessage());
                }
            }
        }
    }

    private String getWorkspaceIdFromResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(String.class);
        } else if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            var errorResponse = response.readEntity(ReactServiceErrorResponse.class);
            log.error("Workspace not found by name: {}", errorResponse.msg());
            throw new ClientErrorException(errorResponse.msg(), Response.Status.BAD_REQUEST);
        }

        log.warn("Unexpected error while getting workspace id: {}", response.getStatus());
        throw new InternalServerErrorException();
    }

    private Mono<Void> copyItemsFromDataset(String sourceWorkspaceId, UUID sourceDatasetId, UUID targetDatasetId) {
        log.info("Copying items from dataset '{}' in workspace '{}' to dataset '{}'",
                sourceDatasetId, sourceWorkspaceId, targetDatasetId);

        String targetWorkspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // First, get the source dataset to obtain its name
        Dataset sourceDataset = findById(sourceDatasetId, sourceWorkspaceId, Visibility.PUBLIC);

        // Stream items from source dataset
        DatasetItemStreamRequest streamRequest = DatasetItemStreamRequest.builder()
                .datasetName(sourceDataset.name())
                .steamLimit(2000)
                .build();

        return datasetItemService.getItems(sourceWorkspaceId, streamRequest, Visibility.PUBLIC)
                .buffer(100) // Batch items in groups of 100
                .flatMap(itemList -> {
                    if (itemList.isEmpty()) {
                        return Mono.empty();
                    }

                    // Create batch with items (remove IDs so new ones are generated)
                    DatasetItemBatch batch = DatasetItemBatch.builder()
                            .datasetId(targetDatasetId)
                            .items(itemList.stream()
                                    .map(item -> item.toBuilder().id(null).build())
                                    .collect(Collectors.toList()))
                            .build();

                    // Save items in target workspace context
                    return datasetItemService.verifyDatasetExistsAndSave(batch)
                            .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, targetWorkspaceId)
                                    .put(RequestContext.USER_NAME, userName));
                }, 1) // Process one batch at a time
                .then()
                .doOnSuccess(v -> log.info("Successfully copied items from dataset '{}' to dataset '{}'",
                        sourceDatasetId, targetDatasetId))
                .doOnError(error -> log.error("Error copying items from dataset '{}' to dataset '{}': {}",
                        sourceDatasetId, targetDatasetId, error.getMessage()));
    }

}
