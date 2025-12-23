package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemUpdate;
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
import static com.comet.opik.infrastructure.DatabaseUtils.generateUuidPool;
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

    /**
     * Saves dataset items, routing to either versioned or legacy storage based on configuration.
     * <p>
     * When dataset versioning is enabled:
     * <ul>
     *   <li>Resolves dataset ID from batch (creates dataset if needed)</li>
     *   <li>Creates a new version on top of the latest one</li>
     *   <li>If no versions exist, creates the first version</li>
     * </ul>
     * When versioning is disabled (legacy mode):
     * <ul>
     *   <li>Saves items to the legacy dataset_items table</li>
     * </ul>
     *
     * @param batch the batch of items to save (must include datasetId or datasetName)
     * @return Mono completing when save operation finishes
     */
    Mono<Void> save(DatasetItemBatch batch);

    /**
     * Saves items and creates a new version on top of the latest version.
     * <p>
     * This operation is used when dataset versioning is enabled. Instead of saving to
     * a legacy table, it directly creates a new version with the provided items.
     * <ul>
     *   <li>If batch is empty, returns immediately without creating a version</li>
     *   <li>If no versions exist, creates the first version with all items as "added"</li>
     *   <li>If versions exist, determines which items are new vs updated based on ID matching</li>
     *   <li>Creates a new version on top of the latest one</li>
     * </ul>
     *
     * @param batch the batch of items to save
     * @param datasetId the resolved dataset ID
     * @return Mono emitting the newly created version, or empty if batch is empty
     */
    Mono<DatasetVersion> saveItemsWithVersion(DatasetItemBatch batch, UUID datasetId);
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
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (isVersioningEnabled()) {
                log.info("Patching item '{}' with versioning", id);
                return patchItemWithVersionById(id, item, workspaceId, userName);
            }

            // Legacy mode: get from draft table and update
            return get(id)
                    .flatMap(existingItem -> {
                        // Build patched item by merging provided fields with existing item
                        var builder = existingItem.toBuilder();

                        // Apply patch fields if provided
                        Optional.ofNullable(item.data()).ifPresent(builder::data);
                        Optional.ofNullable(item.source()).ifPresent(builder::source);
                        Optional.ofNullable(item.traceId()).ifPresent(builder::traceId);
                        Optional.ofNullable(item.spanId()).ifPresent(builder::spanId);
                        Optional.ofNullable(item.tags()).ifPresent(builder::tags);

                        DatasetItem patchedItem = builder.build();

                        log.info("Patching item '{}' in legacy table for dataset '{}'",
                                id, existingItem.datasetId());
                        DatasetItemBatch batch = new DatasetItemBatch(null, existingItem.datasetId(),
                                List.of(patchedItem));
                        return saveBatch(batch, existingItem.datasetId());
                    });
        }).then();
    }

    /**
     * Patches a single item by its dataset_item_id and creates a new version.
     * First looks up the item in the versioned table to get its current state.
     */
    private Mono<Long> patchItemWithVersionById(UUID datasetItemId, DatasetItem patchData,
            String workspaceId, String userName) {
        // First, get the dataset ID from the versioned table
        return versionDao.getDatasetIdByItemId(datasetItemId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Item '{}' not found in versioned table", datasetItemId);
                    return Mono.error(failWithNotFound("Dataset item not found"));
                }))
                .flatMap(datasetId -> {
                    // Get the latest version (using overload that takes workspaceId)
                    Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

                    if (latestVersion.isEmpty()) {
                        log.info("No versions exist for dataset '{}', cannot patch in versioning mode", datasetId);
                        return Mono.error(failWithNotFound("No versions exist for dataset"));
                    }

                    UUID baseVersionId = latestVersion.get().id();
                    int baseItemsCount = latestVersion.get().itemsTotal();
                    UUID newVersionId = idGenerator.generateId();

                    // Get the existing item from the latest version
                    return getVersionedItemById(datasetId, datasetItemId)
                            .flatMap(existingItem -> {
                                // Apply patch to the existing item
                                VersionedDatasetItem patchedItem = applyPatchToVersionedItem(
                                        existingItem, patchData, datasetId, userName);

                                log.info("Creating version with single item edit for dataset '{}', baseVersion='{}'",
                                        datasetId, baseVersionId);

                                // Apply delta with only the edited item
                                return versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                                        List.of(), // No added items
                                        List.of(patchedItem), // Single edited item
                                        Set.of(), // No deleted items
                                        baseItemsCount)
                                        .map(itemsTotal -> {
                                            log.info("Applied patch delta to dataset '{}': itemsTotal '{}'",
                                                    datasetId, itemsTotal);

                                            // Create version metadata
                                            versionService.createVersionFromDelta(
                                                    datasetId,
                                                    newVersionId,
                                                    itemsTotal.intValue(),
                                                    baseVersionId,
                                                    null, // No tags
                                                    "Updated 1 item",
                                                    workspaceId,
                                                    userName);

                                            log.info("Created version '{}' for dataset '{}' after patch",
                                                    newVersionId, datasetId);
                                            return itemsTotal;
                                        });
                            });
                });
    }

    /**
     * Applies patch data to a versioned item, returning a new VersionedDatasetItem with the changes.
     */
    private VersionedDatasetItem applyPatchToVersionedItem(VersionedDatasetItem existingItem, DatasetItem patchData,
            UUID datasetId, String userName) {
        var builder = existingItem.toBuilder()
                .lastUpdatedAt(java.time.Instant.now())
                .lastUpdatedBy(userName);

        // Apply patch fields if provided (non-null)
        Optional.ofNullable(patchData.data()).ifPresent(builder::data);
        Optional.ofNullable(patchData.source()).ifPresent(builder::source);
        Optional.ofNullable(patchData.traceId()).ifPresent(builder::traceId);
        Optional.ofNullable(patchData.spanId()).ifPresent(builder::spanId);
        Optional.ofNullable(patchData.tags()).ifPresent(builder::tags);

        return builder.build();
    }

    @WithSpan
    public Mono<Void> batchUpdate(@NonNull DatasetItemBatchUpdate batchUpdate) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (isVersioningEnabled()) {
                log.info("Batch updating items with versioning, idsSize='{}', filtersSize='{}'",
                        batchUpdate.ids() != null ? batchUpdate.ids().size() : 0,
                        batchUpdate.filters() != null ? batchUpdate.filters().size() : 0);
                return batchUpdateWithVersion(batchUpdate, workspaceId, userName);
            }

            // Legacy: bulk update in legacy table
            log.info("Batch updating items in legacy table, idsSize='{}', filtersSize='{}'",
                    batchUpdate.ids() != null ? batchUpdate.ids().size() : 0,
                    batchUpdate.filters() != null ? batchUpdate.filters().size() : 0);
            return dao.bulkUpdate(batchUpdate.ids(), batchUpdate.filters(), batchUpdate.update(),
                    batchUpdate.mergeTags());
        });
    }

    /**
     * Batch updates items and creates a new version with the edits.
     * <p>
     * This operation is used when dataset versioning is enabled.
     * It fetches items matching the criteria from the latest version,
     * applies the updates, and creates a new version with the edited items.
     */
    private Mono<Void> batchUpdateWithVersion(DatasetItemBatchUpdate batchUpdate, String workspaceId, String userName) {
        // We need to determine the dataset ID from the first item
        // For batch update by IDs, get the first item's dataset
        if (batchUpdate.ids() != null && !batchUpdate.ids().isEmpty()) {
            UUID firstItemId = batchUpdate.ids().iterator().next();

            return versionDao.getDatasetIdByItemId(firstItemId)
                    .switchIfEmpty(dao.get(firstItemId).map(DatasetItem::datasetId))
                    .flatMap(datasetId -> batchUpdateByIdsWithVersion(datasetId, batchUpdate, workspaceId, userName));
        }

        // For batch update by filters, we need the dataset ID from the filter context
        // This is a limitation - filters need to be scoped to a dataset
        // For now, return an error indicating filters require dataset ID in versioning mode
        log.warn("Batch update by filters without dataset ID is not supported with versioning enabled");
        return Mono.error(new ClientErrorException(
                "Batch update by filters requires explicit item IDs when versioning is enabled",
                Response.Status.BAD_REQUEST));
    }

    /**
     * Batch updates items by IDs and creates a new version.
     */
    private Mono<Void> batchUpdateByIdsWithVersion(UUID datasetId, DatasetItemBatchUpdate batchUpdate,
            String workspaceId, String userName) {
        log.info("Batch updating '{}' items with versioning for dataset '{}'",
                batchUpdate.ids().size(), datasetId);

        // Get the latest version (using overload that takes workspaceId)
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

        if (latestVersion.isEmpty()) {
            // No versions exist - fall back to legacy update
            log.info("No versions exist for dataset '{}', falling back to legacy batch update", datasetId);
            return dao.bulkUpdate(batchUpdate.ids(), batchUpdate.filters(), batchUpdate.update(),
                    batchUpdate.mergeTags());
        }

        UUID baseVersionId = latestVersion.get().id();
        int baseItemsCount = latestVersion.get().itemsTotal();
        UUID newVersionId = idGenerator.generateId();

        // Fetch items to be updated from the latest version
        return fetchItemsFromVersionByIds(datasetId, baseVersionId, batchUpdate.ids())
                .map(item -> applyUpdateToItem(item, batchUpdate.update(), batchUpdate.mergeTags(), userName))
                .collectList()
                .flatMap(editedItems -> {
                    if (editedItems.isEmpty()) {
                        log.info("No items found to update for dataset '{}'", datasetId);
                        return Mono.empty();
                    }

                    log.info("Creating version with '{}' edited items for dataset '{}', baseVersion='{}'",
                            editedItems.size(), datasetId, baseVersionId);

                    // Apply delta with the edited items
                    return versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                            List.of(), // No added items
                            editedItems,
                            Set.of(), // No deleted items
                            baseItemsCount)
                            .map(itemsTotal -> {
                                log.info("Applied batch update delta to dataset '{}': itemsTotal '{}'",
                                        datasetId, itemsTotal);

                                // Create version metadata
                                String changeDescription = editedItems.size() == 1
                                        ? "Updated 1 item"
                                        : "Updated " + editedItems.size() + " items";

                                versionService.createVersionFromDelta(
                                        datasetId,
                                        newVersionId,
                                        itemsTotal.intValue(),
                                        baseVersionId,
                                        null, // No tags
                                        changeDescription,
                                        workspaceId,
                                        userName);

                                log.info("Created version '{}' for dataset '{}' after batch update",
                                        newVersionId, datasetId);
                                return itemsTotal;
                            });
                })
                .then();
    }

    /**
     * Fetches items from a specific version by their dataset_item_id using a single batch query.
     * This avoids N+1 queries by fetching all items in one database call.
     */
    private Flux<VersionedDatasetItem> fetchItemsFromVersionByIds(UUID datasetId, UUID versionId, Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Flux.empty();
        }

        log.debug("Fetching '{}' items by dataset_item_ids from version '{}' in dataset '{}'",
                itemIds.size(), versionId, datasetId);

        // Use the batch method to fetch all items in a single query
        return versionDao.getItemsByDatasetItemIds(datasetId, itemIds)
                .map(this::mapDatasetItemToVersionedItem);
    }

    /**
     * Maps a DatasetItem (from the versioned table) to a VersionedDatasetItem.
     */
    private VersionedDatasetItem mapDatasetItemToVersionedItem(DatasetItem item) {
        return VersionedDatasetItem.builder()
                .datasetItemId(item.draftItemId() != null ? item.draftItemId() : item.id())
                .datasetId(item.datasetId())
                .data(item.data())
                .source(item.source())
                .traceId(item.traceId())
                .spanId(item.spanId())
                .tags(item.tags())
                .createdAt(item.createdAt())
                .lastUpdatedAt(item.lastUpdatedAt())
                .createdBy(item.createdBy())
                .lastUpdatedBy(item.lastUpdatedBy())
                .build();
    }

    /**
     * Gets a single versioned item by its dataset_item_id from the latest version.
     */
    private Mono<VersionedDatasetItem> getVersionedItemById(UUID datasetId, UUID datasetItemId) {
        // Use the DAO method that queries the versioned table directly
        return versionDao.getItemByDatasetItemId(datasetId, datasetItemId)
                .map(this::mapDatasetItemToVersionedItem);
    }

    /**
     * Applies an update to an item, returning a new VersionedDatasetItem with the changes.
     */
    private VersionedDatasetItem applyUpdateToItem(VersionedDatasetItem item, DatasetItemUpdate update,
            Boolean mergeTags, String userName) {
        var builder = item.toBuilder()
                .lastUpdatedAt(java.time.Instant.now())
                .lastUpdatedBy(userName);

        // Apply updates if provided
        if (update.data() != null) {
            builder.data(update.data());
        }
        if (update.tags() != null) {
            if (Boolean.TRUE.equals(mergeTags) && item.tags() != null) {
                // Merge tags
                Set<String> mergedTags = new java.util.HashSet<>(item.tags());
                mergedTags.addAll(update.tags());
                builder.tags(mergedTags);
            } else {
                builder.tags(update.tags());
            }
        }

        return builder.build();
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
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (isVersioningEnabled()) {
                log.info("Deleting items with versioning. datasetId='{}', itemIdsSize='{}', filtersSize='{}'",
                        datasetId, ids != null ? ids.size() : 0, filters != null ? filters.size() : 0);
                return deleteItemsWithVersion(ids, datasetId, filters, workspaceId, userName);
            }

            // Legacy: delete from legacy table
            log.info("Deleting items from legacy table. datasetId='{}', itemIdsSize='{}', filtersSize='{}'",
                    datasetId, ids != null ? ids.size() : 0, filters != null ? filters.size() : 0);
            return dao.delete(ids, datasetId, filters).then();
        });
    }

    /**
     * Deletes items and creates a new version without the deleted items.
     * <p>
     * This operation is used when dataset versioning is enabled. Instead of deleting from
     * a legacy table, it creates a new version with the items excluded.
     * <ul>
     *   <li>Resolves the dataset ID (from the provided datasetId or by querying the items)</li>
     *   <li>Gets the latest version for the dataset</li>
     *   <li>If no versions exist, falls back to legacy delete</li>
     *   <li>Otherwise, applies a delta with the deletions to create a new version</li>
     * </ul>
     */
    private Mono<Void> deleteItemsWithVersion(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters,
            String workspaceId, String userName) {

        // Case 1: Deleting by datasetId with filters
        if (datasetId != null) {
            return deleteByDatasetIdWithVersion(datasetId, filters, workspaceId, userName);
        }

        // Case 2: Deleting by item IDs - need to find the dataset
        if (ids != null && !ids.isEmpty()) {
            return deleteByItemIdsWithVersion(ids, workspaceId, userName);
        }

        // No valid input
        return Mono.empty();
    }

    /**
     * Deletes items by datasetId with optional filters, creating a new version.
     * <p>
     * Uses an efficient SQL-based approach that copies items NOT matching the filters
     * to the new version, avoiding the need to load all item IDs into memory.
     */
    private Mono<Void> deleteByDatasetIdWithVersion(UUID datasetId, List<DatasetItemFilter> filters,
            String workspaceId, String userName) {
        log.info("Deleting items by datasetId '{}' with versioning, filtersSize='{}'",
                datasetId, filters != null ? filters.size() : 0);

        // Verify dataset exists
        datasetService.findById(datasetId, workspaceId, null);

        // Get the latest version (using overload that takes workspaceId)
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

        if (latestVersion.isEmpty()) {
            // No versions exist - fall back to legacy delete
            log.info("No versions exist for dataset '{}', falling back to legacy delete", datasetId);
            return dao.delete(null, datasetId, filters).then();
        }

        UUID baseVersionId = latestVersion.get().id();
        int baseItemsCount = latestVersion.get().itemsTotal();
        UUID newVersionId = idGenerator.generateId();

        // Empty filters = delete all (copy nothing to new version)
        Mono<Long> copyMono;
        if (filters == null || filters.isEmpty()) {
            log.info("Empty filters = delete all. Creating empty version '{}' for dataset '{}'",
                    newVersionId, datasetId);
            copyMono = Mono.just(0L);
        } else {
            // Generate UUID pool for the copy operation (worst case = all items copied)
            List<UUID> uuids = generateUuidPool(idGenerator, baseItemsCount);

            // Use efficient filter-based copy - copies items NOT matching the filters
            copyMono = versionDao.copyVersionItems(datasetId, baseVersionId, newVersionId, filters, uuids);
        }

        return copyMono
                .flatMap(newVersionItemCount -> {
                    int deletedCount = baseItemsCount - newVersionItemCount.intValue();

                    log.info("Creating version metadata: dataset='{}', baseVersion='{}', newVersion='{}', " +
                            "deletedCount='{}', newItemCount='{}'",
                            datasetId, baseVersionId, newVersionId, deletedCount, newVersionItemCount);

                    // Create version metadata
                    String changeDescription = deletedCount == 1
                            ? "Deleted 1 item"
                            : "Deleted " + deletedCount + " items";

                    versionService.createVersionFromDelta(
                            datasetId,
                            newVersionId,
                            newVersionItemCount.intValue(),
                            baseVersionId,
                            null, // No tags
                            changeDescription,
                            workspaceId,
                            userName);

                    return Mono.empty();
                })
                .then();
    }

    /**
     * Deletes items by item IDs, creating a new version.
     * Items are grouped by dataset since the API allows deleting items across datasets.
     */
    private Mono<Void> deleteByItemIdsWithVersion(Set<UUID> ids, String workspaceId, String userName) {
        log.info("Deleting '{}' items by IDs with versioning", ids.size());

        // Get the first item to determine the dataset ID
        // Note: This assumes all items belong to the same dataset (common case for SDK usage)
        UUID firstItemId = ids.iterator().next();

        // First try to get from versioned table (when versioning is enabled, items are there)
        return versionDao.getDatasetIdByItemId(firstItemId)
                .switchIfEmpty(
                        // Fall back to draft table if not found in versioned table
                        dao.get(firstItemId)
                                .map(DatasetItem::datasetId))
                .flatMap(datasetId -> {
                    if (datasetId == null) {
                        log.warn("Could not find item '{}' or its dataset", firstItemId);
                        return Mono.empty();
                    }

                    log.info("Resolved dataset '{}' for item deletion", datasetId);

                    // Get the latest version (use overload that takes workspaceId since we're in reactive context)
                    Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

                    if (latestVersion.isEmpty()) {
                        // No versions exist - fall back to legacy delete
                        log.info("No versions exist for dataset '{}', falling back to legacy delete", datasetId);
                        return dao.delete(ids, null, null).then();
                    }

                    UUID baseVersionId = latestVersion.get().id();
                    int baseVersionItemCount = latestVersion.get().itemsTotal();
                    UUID newVersionId = idGenerator.generateId();

                    // The provided IDs are dataset_item_id values (stable reference IDs)
                    log.info("Creating new version for dataset '{}' with '{}' items deleted",
                            datasetId, ids.size());

                    return createVersionWithDeletion(datasetId, baseVersionId, newVersionId, ids,
                            baseVersionItemCount, workspaceId, userName);
                })
                .then();
    }

    /**
     * Creates a new version with the specified items deleted (excluded from the new version).
     */
    private Mono<Void> createVersionWithDeletion(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            Set<UUID> deletedIds, int baseVersionItemCount, String workspaceId, String userName) {

        // Apply delta with only deletions (no adds or edits)
        return versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                List.of(), // No added items
                List.of(), // No edited items
                deletedIds,
                baseVersionItemCount)
                .map(itemsTotal -> {
                    log.info("Applied deletion delta to dataset '{}': itemsTotal '{}'", datasetId, itemsTotal);

                    // Create version metadata
                    DatasetVersion version = versionService.createVersionFromDelta(
                            datasetId,
                            newVersionId,
                            itemsTotal.intValue(),
                            baseVersionId,
                            null, // No tags
                            null, // No change description (auto-generated)
                            workspaceId,
                            userName);

                    log.info("Created version '{}' for dataset '{}' after deletion", version.id(), datasetId);
                    return version;
                })
                .then();
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

            return Mono.deferContextual(ctx -> {
                String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                return getItemsFromLatestVersion(datasetItemSearchCriteria, page, size, workspaceId);
            });
        } else {
            // Versioning toggle is OFF: fetch draft (current) items from dataset_items table
            log.info("Finding draft dataset items by '{}', page '{}', size '{}'",
                    datasetItemSearchCriteria, page, size);

            return dao.getItems(datasetItemSearchCriteria, page, size)
                    .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
        }
    }

    private boolean isVersioningEnabled() {
        return config.getServiceToggles() != null
                && config.getServiceToggles().isDatasetVersioningEnabled();
    }

    private Mono<DatasetItemPage> getItemsFromLatestVersion(DatasetItemSearchCriteria criteria, int page, int size,
            String workspaceId) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(criteria.datasetId(), workspaceId);

        if (latestVersion.isEmpty()) {
            // No versions exist yet - fall back to draft items
            // This allows users to work with draft items until the first version is committed
            log.info("No versions found for dataset '{}', falling back to draft items", criteria.datasetId());
            return dao.getItems(criteria, page, size)
                    .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
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
            String userName = ctx.get(RequestContext.USER_NAME);

            log.info("Applying delta changes for dataset '{}', baseVersion '{}', override '{}'",
                    datasetId, changes.baseVersion(), override);

            // Verify dataset exists (using explicit workspaceId since we're in reactive context)
            datasetService.findById(datasetId, workspaceId, null);

            // The baseVersion is the version ID directly (not a hash or tag)
            UUID baseVersionId = changes.baseVersion();

            // Verify the base version exists and get its item count
            DatasetVersion baseVersion = versionService.getVersionById(baseVersionId, datasetId, workspaceId);
            int baseVersionItemCount = baseVersion.itemsTotal();

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
                    addedItems, editedItems, deletedIds, baseVersionItemCount)
                    .map(itemsTotal -> {
                        log.info("Applied delta to dataset '{}': itemsTotal '{}'", datasetId, itemsTotal);

                        // Create version metadata
                        DatasetVersion version = versionService.createVersionFromDelta(
                                datasetId,
                                newVersionId,
                                itemsTotal.intValue(),
                                baseVersionId,
                                changes.tags(),
                                changes.changeDescription(),
                                workspaceId,
                                userName);

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
                    // For edited items, use draftItemId (the stable identifier across versions)
                    // If draftItemId is not available, fall back to id
                    UUID datasetItemId = item.draftItemId() != null ? item.draftItemId() : item.id();
                    if (datasetItemId == null) {
                        throw new ClientErrorException(
                                Response.status(Response.Status.BAD_REQUEST)
                                        .entity(new ErrorMessage(
                                                List.of("Edited items must have an id or draftItemId")))
                                        .build());
                    }
                    return VersionedDatasetItem.fromDatasetItem(item, datasetItemId, datasetId);
                })
                .toList();
    }

    @Override
    @WithSpan
    public Mono<Void> save(@NonNull DatasetItemBatch batch) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (isVersioningEnabled()) {
                UUID datasetId = resolveDatasetId(batch, workspaceId, userName);
                log.info("Saving items with versioning for dataset '{}'", datasetId);
                return saveItemsWithVersion(batch, datasetId)
                        .contextWrite(c -> c.put(RequestContext.WORKSPACE_ID, workspaceId)
                                .put(RequestContext.USER_NAME, userName))
                        .then();
            }

            // Legacy: save to legacy table
            log.info("Saving items to legacy table for dataset '{}'", batch.datasetId());
            return verifyDatasetExistsAndSave(batch);
        });
    }

    private UUID resolveDatasetId(DatasetItemBatch batch, String workspaceId, String userName) {
        if (batch.datasetId() == null) {
            return datasetService.getOrCreate(workspaceId, batch.datasetName(), userName);
        }

        Dataset dataset = datasetService.findById(batch.datasetId(), workspaceId, null);
        if (dataset == null) {
            throw new NotFoundException("Dataset not found: '%s'".formatted(batch.datasetId()));
        }
        return dataset.id();
    }

    @Override
    @WithSpan
    public Mono<DatasetVersion> saveItemsWithVersion(@NonNull DatasetItemBatch batch, @NonNull UUID datasetId) {
        if (batch.items() == null || batch.items().isEmpty()) {
            log.debug("Empty batch, skipping version creation for dataset '{}'", datasetId);
            return Mono.empty();
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            log.info("Saving items with version for dataset '{}', itemCount '{}'",
                    datasetId, batch.items().size());

            // Verify dataset exists
            datasetService.findById(datasetId, workspaceId, null);

            // Get the latest version (if exists) - using overload that takes workspaceId
            Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

            if (latestVersion.isEmpty()) {
                // No versions exist yet - create the first version with all items as "added"
                return createFirstVersion(datasetId, batch.items(), workspaceId, userName);
            }

            // Versions exist - apply delta on top of the latest
            UUID baseVersionId = latestVersion.get().id();
            return createVersionWithDelta(datasetId, baseVersionId, batch.items(), workspaceId, userName);
        });
    }

    private Mono<DatasetVersion> createFirstVersion(UUID datasetId, List<DatasetItem> items,
            String workspaceId, String userName) {
        log.info("Creating first version for dataset '{}' with '{}' items", datasetId, items.size());

        UUID newVersionId = idGenerator.generateId();

        // All items are "added" for the first version
        List<VersionedDatasetItem> addedItems = items.stream()
                .map(item -> {
                    UUID datasetItemId = item.id() != null ? item.id() : idGenerator.generateId();
                    return VersionedDatasetItem.fromDatasetItem(item, datasetItemId, datasetId);
                })
                .toList();

        // Use applyDelta with no base version items (empty copy)
        // We need a special path since there's no base version
        return versionDao.insertItems(datasetId, newVersionId, addedItems, workspaceId, userName)
                .map(itemsTotal -> {
                    log.info("Inserted '{}' items for first version of dataset '{}'", itemsTotal, datasetId);

                    // Create version metadata (first version - all items are "added")
                    DatasetVersion version = versionService.createVersionFromDelta(
                            datasetId,
                            newVersionId,
                            itemsTotal.intValue(),
                            null, // No base version for first version
                            null, // No tags
                            null, // No change description
                            workspaceId,
                            userName);

                    log.info("Created first version '{}' for dataset '{}' with hash '{}'",
                            version.id(), datasetId, version.versionHash());
                    return version;
                });
    }

    private Mono<DatasetVersion> createVersionWithDelta(UUID datasetId, UUID baseVersionId,
            List<DatasetItem> items, String workspaceId, String userName) {
        log.info("Creating version with delta for dataset '{}', baseVersion '{}', itemCount '{}'",
                datasetId, baseVersionId, items.size());

        UUID newVersionId = idGenerator.generateId();

        // Get existing item IDs from the base version to determine adds vs edits
        return versionDao.getItemIdsAndHashes(datasetId, baseVersionId)
                .collectList()
                .flatMap(existingItems -> {
                    Set<UUID> existingItemIds = existingItems.stream()
                            .map(DatasetItemIdAndHash::itemId)
                            .collect(Collectors.toSet());

                    // Classify incoming items as added or edited
                    List<VersionedDatasetItem> addedItems = new java.util.ArrayList<>();
                    List<VersionedDatasetItem> editedItems = new java.util.ArrayList<>();

                    for (DatasetItem item : items) {
                        UUID itemId = item.id();
                        if (itemId != null && existingItemIds.contains(itemId)) {
                            // Existing item - treat as edit
                            editedItems.add(VersionedDatasetItem.fromDatasetItem(item, itemId, datasetId));
                        } else {
                            // New item - treat as add
                            UUID newItemId = itemId != null ? itemId : idGenerator.generateId();
                            addedItems.add(VersionedDatasetItem.fromDatasetItem(item, newItemId, datasetId));
                        }
                    }

                    log.info("Classified items: added='{}', edited='{}' for dataset '{}'",
                            addedItems.size(), editedItems.size(), datasetId);

                    // Apply delta changes - no deletions in PUT flow
                    int baseVersionItemCount = existingItems.size();
                    return versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                            addedItems, editedItems, Set.of(), baseVersionItemCount)
                            .map(itemsTotal -> {
                                log.info("Applied delta to dataset '{}': itemsTotal '{}'", datasetId, itemsTotal);

                                // Create version metadata
                                DatasetVersion version = versionService.createVersionFromDelta(
                                        datasetId,
                                        newVersionId,
                                        itemsTotal.intValue(),
                                        baseVersionId,
                                        null, // No tags
                                        null, // No change description
                                        workspaceId,
                                        userName);

                                log.info("Created version '{}' for dataset '{}' with hash '{}'",
                                        version.id(), datasetId, version.versionHash());
                                return version;
                            });
                });
    }
}
