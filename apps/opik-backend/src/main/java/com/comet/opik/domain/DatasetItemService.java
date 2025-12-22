package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.PageColumns;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

@ImplementedBy(DatasetItemServiceImpl.class)
public interface DatasetItemService {

    Mono<Void> verifyDatasetExistsAndSave(DatasetItemBatch batch);

    Mono<Long> saveBatch(UUID datasetId, List<DatasetItem> items);

    Mono<Void> createFromTraces(UUID datasetId, Set<UUID> traceIds, TraceEnrichmentOptions enrichmentOptions);

    Mono<Void> createFromSpans(UUID datasetId, Set<UUID> spanIds,
            SpanEnrichmentOptions enrichmentOptions);

    Mono<DatasetItem> get(UUID id);

    Mono<Void> patch(UUID id, DatasetItem item);

    Mono<Void> batchUpdate(DatasetItemBatchUpdate batchUpdate);

    Mono<Void> delete(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters);

    Mono<DatasetItemPage> getItems(int page, int size, DatasetItemSearchCriteria datasetItemSearchCriteria);

    Flux<DatasetItem> getItems(String workspaceId, DatasetItemStreamRequest request, Visibility visibility);

    Mono<PageColumns> getOutputColumns(UUID datasetId, Set<UUID> experimentIds);

    Mono<ProjectStats> getExperimentItemsStats(UUID datasetId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters);

    /**
     * Apply delta changes to a dataset version, creating a new version with the changes.
     * <p>
     * This operation:
     * <ul>
     *   <li>Validates baseVersion exists and belongs to the dataset</li>
     *   <li>Checks if baseVersion equals the latest version (unless override is true)</li>
     *   <li>Fetches items from baseVersion</li>
     *   <li>Applies delta: adds new items, updates edited items, removes deleted items</li>
     *   <li>Creates a new version record with provided metadata</li>
     *   <li>Updates 'latest' tag to point to the new version</li>
     * </ul>
     *
     * @param datasetId the dataset ID
     * @param changes the delta changes to apply (added, edited, deleted items plus metadata)
     * @param override if true, force create version even if baseVersion is stale
     * @return Mono emitting the newly created version
     * @throws NotFoundException if dataset or baseVersion not found
     * @throws ClientErrorException with 409 status if baseVersion is stale and override is false
     */
    Mono<DatasetVersion> applyDeltaChanges(UUID datasetId, DatasetItemChanges changes, boolean override);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemServiceImpl implements DatasetItemService {

    private final @NonNull DatasetItemDAO dao;
    private final @NonNull DatasetItemVersionDAO versionDao;
    private final @NonNull DatasetService datasetService;
    private final @NonNull DatasetVersionService versionService;
    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull TraceEnrichmentService traceEnrichmentService;
    private final @NonNull SpanEnrichmentService spanEnrichmentService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull TransactionTemplate template;
    private final @NonNull OpikConfiguration config;

    @Override
    @WithSpan
    public Mono<Void> verifyDatasetExistsAndSave(@NonNull DatasetItemBatch batch) {
        if (batch.datasetId() == null && batch.datasetName() == null) {
            return Mono.error(failWithError("dataset_id or dataset_name must be provided"));
        }

        return getDatasetId(batch)
                .flatMap(it -> saveBatch(batch, it))
                .then();
    }

    @Override
    @WithSpan
    public Mono<Void> createFromTraces(
            @NonNull UUID datasetId,
            @NonNull Set<UUID> traceIds,
            @NonNull TraceEnrichmentOptions enrichmentOptions) {

        log.info("Creating dataset items from '{}' traces for dataset '{}'", traceIds.size(), datasetId);

        // Verify dataset exists
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> {
                return template.inTransaction(READ_ONLY, handle -> {
                    var dao = handle.attach(DatasetDAO.class);
                    return dao.findById(datasetId, workspaceId)
                            .orElseThrow(() -> new NotFoundException("Dataset not found: '%s'".formatted(datasetId)));
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }).flatMap(dataset -> {
            // Enrich traces with metadata
            return traceEnrichmentService.enrichTraces(traceIds, enrichmentOptions)
                    .flatMap(enrichedTraces -> {
                        // Convert enriched traces to dataset items
                        List<DatasetItem> datasetItems = enrichedTraces.entrySet().stream()
                                .<DatasetItem>map(entry -> DatasetItem.builder()
                                        .id(idGenerator.generateId())
                                        .source(DatasetItemSource.TRACE)
                                        .traceId(entry.getKey())
                                        .data(entry.getValue())
                                        .build())
                                .toList();

                        // Save dataset items
                        DatasetItemBatch batch = new DatasetItemBatch(null, datasetId, datasetItems);
                        return saveBatch(batch, datasetId);
                    });
        }).then();
    }

    @Override
    @WithSpan
    public Mono<Void> createFromSpans(
            @NonNull UUID datasetId,
            @NonNull Set<UUID> spanIds,
            @NonNull SpanEnrichmentOptions enrichmentOptions) {

        log.info("Creating dataset items from '{}' spans for dataset '{}'", spanIds.size(), datasetId);

        // Verify dataset exists
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> {
                return template.inTransaction(READ_ONLY, handle -> {
                    var dao = handle.attach(DatasetDAO.class);
                    return dao.findById(datasetId, workspaceId)
                            .orElseThrow(() -> new NotFoundException("Dataset not found: '%s'".formatted(datasetId)));
                });
            }).subscribeOn(Schedulers.boundedElastic());
        }).flatMap(dataset -> {
            // Enrich spans with metadata
            return spanEnrichmentService.enrichSpans(spanIds, enrichmentOptions)
                    .flatMap(enrichedSpans -> {
                        // Convert enriched spans to dataset items
                        List<DatasetItem> datasetItems = enrichedSpans.entrySet().stream()
                                .<DatasetItem>map(entry -> DatasetItem.builder()
                                        .id(idGenerator.generateId())
                                        .source(DatasetItemSource.SPAN)
                                        .spanId(entry.getKey())
                                        .data(entry.getValue())
                                        .build())
                                .toList();

                        // Save dataset items
                        DatasetItemBatch batch = new DatasetItemBatch(null, datasetId, datasetItems);
                        return saveBatch(batch, datasetId);
                    });
        }).then();
    }

    private Mono<UUID> getDatasetId(DatasetItemBatch batch) {
        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            Visibility visibility = ctx.get(RequestContext.VISIBILITY);

            return Mono.fromCallable(() -> {

                if (batch.datasetId() == null) {
                    return datasetService.getOrCreate(workspaceId, batch.datasetName(), userName);
                }

                Dataset dataset = datasetService.findById(batch.datasetId(), workspaceId, visibility);

                if (dataset == null) {
                    throw newConflict(
                            "workspace_name from dataset item batch and dataset_id from item does not match");
                }

                return dataset.id();
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    private Throwable failWithError(String error) {
        log.info(error);
        return new ClientErrorException(Response.status(422).entity(new ErrorMessage(List.of(error))).build());
    }

    private ClientErrorException newConflict(String error) {
        log.info(error);
        return new ClientErrorException(Response.status(409).entity(new ErrorMessage(List.of(error))).build());
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> get(@NonNull UUID id) {
        return dao.get(id)
                .flatMap(item -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    Visibility visibility = ctx.get(RequestContext.VISIBILITY);
                    // Verify dataset visibility
                    datasetService.findById(item.datasetId(), workspaceId, visibility);

                    return Mono.just(item);
                }))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Dataset item not found"))));
    }

    @Override
    @WithSpan
    public Mono<Void> patch(@NonNull UUID id, @NonNull DatasetItem item) {
        return get(id)
                .flatMap(existingItem -> {
                    // Build patched item by merging provided fields with existing item
                    // Only non-null fields from the patch are applied
                    var builder = existingItem.toBuilder();

                    // Apply patch fields if provided
                    Optional.ofNullable(item.data()).ifPresent(builder::data);
                    Optional.ofNullable(item.source()).ifPresent(builder::source);
                    Optional.ofNullable(item.traceId()).ifPresent(builder::traceId);
                    Optional.ofNullable(item.spanId()).ifPresent(builder::spanId);
                    Optional.ofNullable(item.tags()).ifPresent(builder::tags);

                    DatasetItem patchedItem = builder.build();

                    // Save the patched item (ClickHouse INSERT replaces existing rows with same ID)
                    DatasetItemBatch batch = new DatasetItemBatch(null, existingItem.datasetId(), List.of(patchedItem));
                    return saveBatch(batch, existingItem.datasetId());
                })
                .then();
    }

    @WithSpan
    public Mono<Void> batchUpdate(@NonNull DatasetItemBatchUpdate batchUpdate) {
        return dao.bulkUpdate(batchUpdate.ids(), batchUpdate.datasetId(), batchUpdate.filters(), batchUpdate.update(),
                batchUpdate.mergeTags());
    }

    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull String workspaceId, @NonNull DatasetItemStreamRequest request,
            Visibility visibility) {
        log.info("Getting dataset items by '{}' on workspaceId '{}'", request, workspaceId);
        return Mono
                .fromCallable(() -> datasetService.findByName(workspaceId, request.datasetName(), visibility))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(dataset -> dao.getItems(dataset.id(), request.steamLimit(), request.lastRetrievedId()));
    }

    @Override
    public Mono<PageColumns> getOutputColumns(@NonNull UUID datasetId, Set<UUID> experimentIds) {
        return dao.getOutputColumns(datasetId, experimentIds)
                .map(columns -> PageColumns.builder().columns(columns).build())
                .switchIfEmpty(Mono.just(PageColumns.empty()));
    }

    @Override
    @WithSpan
    public Mono<Long> saveBatch(@NonNull UUID datasetId, @NonNull List<DatasetItem> items) {
        if (items.isEmpty()) {
            return Mono.just(0L);
        }

        // Create a batch with the items and save it
        DatasetItemBatch batch = new DatasetItemBatch(null, datasetId, items);
        return saveBatch(batch, datasetId);
    }

    private Mono<Long> saveBatch(DatasetItemBatch batch, UUID id) {
        if (batch.items().isEmpty()) {
            return Mono.empty();
        }

        List<DatasetItem> items = addIdIfAbsent(batch);

        return Mono.deferContextual(ctx -> {

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return validateSpans(workspaceId, items)
                    .then(Mono.defer(() -> validateTraces(workspaceId, items)))
                    .then(Mono.defer(() -> dao.save(id, items)));
        });
    }

    private Mono<Void> validateSpans(String workspaceId, List<DatasetItem> items) {
        Set<UUID> spanIds = items.stream()
                .map(DatasetItem::spanId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return spanService.validateSpanWorkspace(workspaceId, spanIds)
                .flatMap(valid -> {
                    if (Boolean.FALSE.equals(valid)) {
                        return failWithConflict("span workspace and dataset item workspace does not match");
                    }

                    return Mono.empty();
                });
    }

    private Mono<Boolean> validateTraces(String workspaceId, List<DatasetItem> items) {
        Set<UUID> traceIds = items.stream()
                .map(DatasetItem::traceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return traceService.validateTraceWorkspace(workspaceId, traceIds)
                .flatMap(valid -> {
                    if (Boolean.FALSE.equals(valid)) {
                        return failWithConflict("trace workspace and dataset item workspace does not match");
                    }

                    return Mono.empty();
                });
    }

    private List<DatasetItem> addIdIfAbsent(DatasetItemBatch batch) {
        return batch.items()
                .stream()
                .map(item -> {
                    IdGenerator.validateVersion(item.id(), "dataset_item");
                    return item;
                })
                .toList();
    }

    private <T> Mono<T> failWithConflict(String message) {
        log.info(message);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(message))));
    }

    private NotFoundException failWithNotFound(String message) {
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    @Override
    @WithSpan
    public Mono<Void> delete(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters) {
        return dao.delete(ids, datasetId, filters).then();
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(
            int page, int size, @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria) {

        // Verify dataset visibility
        datasetService.findById(datasetItemSearchCriteria.datasetId());

        if (StringUtils.isNotBlank(datasetItemSearchCriteria.versionHashOrTag())) {
            // Fetch versioned (immutable) items from dataset_item_versions table
            log.info("Finding versioned dataset items by '{}', page '{}', size '{}'", datasetItemSearchCriteria, page,
                    size);

            return Mono.deferContextual(ctx -> {
                String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                // Resolve version hash/tag to version ID
                UUID versionId = versionService.resolveVersionId(workspaceId,
                        datasetItemSearchCriteria.datasetId(),
                        datasetItemSearchCriteria.versionHashOrTag());
                log.info("Resolved version '{}' to version ID '{}' for dataset '{}'",
                        datasetItemSearchCriteria.versionHashOrTag(), versionId, datasetItemSearchCriteria.datasetId());

                // For versioned items, hasDraft is always false (concept doesn't apply to immutable versions)
                return versionDao.getItems(datasetItemSearchCriteria, page, size, versionId)
                        .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
            });
        } else if (isVersioningEnabled()) {
            // Versioning toggle is ON: fetch items from the latest version
            log.info("Finding latest version dataset items by '{}', page '{}', size '{}'",
                    datasetItemSearchCriteria, page, size);

            return getItemsFromLatestVersion(datasetItemSearchCriteria, page, size);
        } else {
            // Versioning toggle is OFF: fetch draft (current) items from dataset_items table
            log.info("Finding draft dataset items by '{}', page '{}', size '{}'",
                    datasetItemSearchCriteria, page, size);

            return dao.getItems(datasetItemSearchCriteria, page, size)
                    .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
        }
    }

    private boolean isVersioningEnabled() {
        return config.getServiceToggles().isDatasetVersioningEnabled();
    }

    private Mono<DatasetItemPage> getItemsFromLatestVersion(DatasetItemSearchCriteria criteria, int page, int size) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(criteria.datasetId());

        if (latestVersion.isEmpty()) {
            // No versions exist yet - return empty page
            log.info("No versions found for dataset '{}', returning empty page", criteria.datasetId());
            return Mono.just(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
        }

        UUID versionId = latestVersion.get().id();
        log.info("Fetching items from latest version '{}' for dataset '{}'", versionId, criteria.datasetId());

        return versionDao.getItems(criteria, page, size, versionId)
                .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
    }

    public Mono<ProjectStats> getExperimentItemsStats(@NonNull UUID datasetId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        log.info("Getting experiment items stats for dataset '{}' and experiments '{}' with filters '{}'", datasetId,
                experimentIds, filters);
        return dao.getExperimentItemsStats(datasetId, experimentIds, filters)
                .switchIfEmpty(Mono.just(ProjectStats.empty()))
                .doOnSuccess(stats -> log.info("Found experiment items stats for dataset '{}', count '{}'", datasetId,
                        stats.stats().size()));
    }

    @Override
    @WithSpan
    public Mono<DatasetVersion> applyDeltaChanges(@NonNull UUID datasetId,
            @NonNull DatasetItemChanges changes, boolean override) {

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            log.info("Applying delta changes for dataset '{}', baseVersion '{}', override '{}'",
                    datasetId, changes.baseVersion(), override);

            // Verify dataset exists
            datasetService.findById(datasetId);

            // Resolve and validate the base version
            UUID baseVersionId = versionService.resolveVersionId(workspaceId, datasetId,
                    changes.baseVersion().toString());

            // Check if baseVersion is the latest (unless override is set)
            if (!override && !versionService.isLatestVersion(datasetId, baseVersionId)) {
                log.warn("Version conflict: baseVersion '{}' is not the latest for dataset '{}'",
                        changes.baseVersion(), datasetId);
                return Mono.error(new ClientErrorException(
                        Response.status(Response.Status.CONFLICT)
                                .entity(new ErrorMessage(List.of(
                                        "Version conflict: baseVersion is not the latest. " +
                                                "Use override=true to force creation.")))
                                .build()));
            }

            // Generate new version ID
            UUID newVersionId = idGenerator.generateId();
            log.info("Generated new version ID '{}' for dataset '{}'", newVersionId, datasetId);

            // Prepare the delta items
            List<VersionedDatasetItem> addedItems = prepareAddedItems(changes, datasetId);
            List<VersionedDatasetItem> editedItems = prepareEditedItems(changes, datasetId);
            Set<UUID> deletedIds = changes.deletedIds() != null ? changes.deletedIds() : Set.of();

            // Apply delta changes via DAO
            return versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                    addedItems, editedItems, deletedIds)
                    .map(itemsTotal -> {
                        log.info("Applied delta to dataset '{}': itemsTotal '{}'", datasetId, itemsTotal);

                        // Create version metadata
                        DatasetVersion version = versionService.createVersionFromDelta(
                                datasetId,
                                newVersionId,
                                itemsTotal.intValue(),
                                baseVersionId,
                                changes.tags(),
                                changes.changeDescription());

                        log.info("Created version '{}' for dataset '{}' with hash '{}'",
                                version.id(), datasetId, version.versionHash());
                        return version;
                    });
        });
    }

    private List<VersionedDatasetItem> prepareAddedItems(DatasetItemChanges changes, UUID datasetId) {
        if (changes.addedItems() == null || changes.addedItems().isEmpty()) {
            return List.of();
        }

        return changes.addedItems().stream()
                .map(item -> {
                    // Generate new datasetItemId for new items
                    UUID datasetItemId = idGenerator.generateId();
                    return VersionedDatasetItem.fromDatasetItem(item, datasetItemId, datasetId);
                })
                .toList();
    }

    private List<VersionedDatasetItem> prepareEditedItems(DatasetItemChanges changes, UUID datasetId) {
        if (changes.editedItems() == null || changes.editedItems().isEmpty()) {
            return List.of();
        }

        return changes.editedItems().stream()
                .map(item -> {
                    // For edited items, preserve the existing id as datasetItemId
                    // The item.id() should be the row ID from the version, but we need the stable datasetItemId
                    // The UI sends items with id being the row id, we need to look up the datasetItemId
                    // For now, assume item.id() is the datasetItemId (the stable identifier)
                    UUID datasetItemId = item.id();
                    if (datasetItemId == null) {
                        throw new ClientErrorException(
                                Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorMessage(List.of("Edited items must have an id")))
                                        .build());
                    }
                    return VersionedDatasetItem.fromDatasetItem(item, datasetItemId, datasetId);
                })
                .toList();
    }
}
