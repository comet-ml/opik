package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemEdit;
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
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.RetryUtils;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.domain.DatasetItemVersionDAO.DatasetItemIdMapping;
import static com.comet.opik.infrastructure.DatabaseUtils.generateUuidPool;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

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

    Mono<Void> delete(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters, UUID batchGroupId);

    Mono<DatasetItemPage> getItems(int page, int size, DatasetItemSearchCriteria datasetItemSearchCriteria);

    Flux<DatasetItem> getItems(String workspaceId, DatasetItemStreamRequest request,
            List<DatasetItemFilter> filters, Visibility visibility);

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
     *   <li>If batchGroupId is null: Mutates the latest version by appending items (backwards compatibility)</li>
     *   <li>If batchGroupId is provided: Creates a new version with batch grouping (multiple batches can share the same version)</li>
     *   <li>If no versions exist, creates the first version regardless of batchGroupId</li>
     *   <li>Returns the DatasetVersion (newly created or mutated)</li>
     * </ul>
     * When versioning is disabled (legacy mode):
     * <ul>
     *   <li>Saves items to the legacy dataset_items table</li>
     *   <li>Returns empty Mono</li>
     * </ul>
     *
     * @param batch the batch of items to save (must include datasetId or datasetName, may include batchGroupId)
     * @return Mono emitting the DatasetVersion when versioning is enabled, or empty when disabled
     */
    Mono<DatasetVersion> save(DatasetItemBatch batch);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemServiceImpl implements DatasetItemService {

    private final @NonNull DatasetItemDAO dao;
    private final @NonNull DatasetItemVersionDAO versionDao;
    private final @NonNull DatasetService datasetService;
    private final @NonNull DatasetVersionService versionService;
    private final @NonNull ExperimentDAO experimentDao;
    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull TraceEnrichmentService traceEnrichmentService;
    private final @NonNull SpanEnrichmentService spanEnrichmentService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull TransactionTemplate template;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull DatasetVersioningMigrationService migrationService;
    private final @NonNull @Config OpikConfiguration config;

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
            String userName = ctx.get(RequestContext.USER_NAME);

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

                        // Save dataset items - route to versioned or legacy based on toggle
                        if (featureFlags.isDatasetVersioningEnabled()) {
                            log.info("Creating dataset items from traces with versioning for dataset '{}'", datasetId);
                            return saveItemsWithVersion(
                                    DatasetItemBatch.builder().datasetId(datasetId).items(datasetItems).build(),
                                    datasetId, null)
                                    .then(Mono.just(0L));
                        }

                        // Legacy: save to legacy table
                        DatasetItemBatch batch = DatasetItemBatch.builder().datasetId(datasetId).items(datasetItems)
                                .build();
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
            String userName = ctx.get(RequestContext.USER_NAME);

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

                        // Save dataset items - route to versioned or legacy based on toggle
                        if (featureFlags.isDatasetVersioningEnabled()) {
                            log.info("Creating dataset items from spans with versioning for dataset '{}'", datasetId);
                            return saveItemsWithVersion(
                                    DatasetItemBatch.builder().datasetId(datasetId).items(datasetItems).build(),
                                    datasetId, null)
                                    .then(Mono.just(0L));
                        }

                        // Legacy: save to legacy table
                        DatasetItemBatch batch = DatasetItemBatch.builder().datasetId(datasetId).items(datasetItems)
                                .build();
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
        if (featureFlags.isDatasetVersioningEnabled()) {
            // When versioning is enabled, only query the versioned table
            return versionDao.getItemById(id)
                    .flatMap(item -> Mono.deferContextual(ctx -> {
                        String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                        Visibility visibility = ctx.get(RequestContext.VISIBILITY);
                        // Verify dataset visibility
                        datasetService.findById(item.datasetId(), workspaceId, visibility);

                        return Mono.just(item);
                    }))
                    .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Dataset item not found"))));
        }

        // Legacy mode: only query the draft table
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

            if (featureFlags.isDatasetVersioningEnabled()) {
                log.info("Patching item '{}' with versioning", id);
                return patchItemWithVersionById(id, item, workspaceId, userName);
            }

            // Legacy mode: get from draft table and update
            return get(id)
                    .flatMap(existingItem -> {
                        // Build patched item by merging provided fields with existing item
                        var builder = existingItem.toBuilder();
                        applyPatchFields(builder, item);
                        DatasetItem patchedItem = builder.build();

                        log.info("Patching item '{}' in legacy table for dataset '{}'",
                                id, existingItem.datasetId());
                        DatasetItemBatch batch = DatasetItemBatch.builder()
                                .datasetId(existingItem.datasetId())
                                .items(List.of(patchedItem))
                                .build();
                        return saveBatch(batch, existingItem.datasetId());
                    });
        }).then();
    }

    /**
     * Patches a single item by its row ID and creates a new version.
     * The frontend sends 'id' (row ID) but patching works on dataset_item_id (stable IDs).
     * This method first maps the row ID to dataset_item_id, then performs the patch.
     */
    private Mono<Long> patchItemWithVersionById(UUID rowId, DatasetItem patchData,
            String workspaceId, String userName) {
        // Map row ID to dataset_item_id
        // The frontend sends 'id' (row ID) but we need 'dataset_item_id' (stable ID) for patching
        return versionDao.mapRowIdsToDatasetItemIds(Set.of(rowId))
                .collectList()
                .flatMap(mappings -> {
                    if (mappings.isEmpty()) {
                        // No mapping found - item doesn't exist or was deleted
                        log.warn("Item with row ID '{}' not found", rowId);
                        return Mono.error(failWithNotFound("Dataset item not found"));
                    }

                    // Use the mapped dataset_item_id
                    UUID datasetItemId = mappings.get(0).datasetItemId();
                    return patchItemWithVersion(datasetItemId, patchData, workspaceId, userName);
                });
    }

    /**
     * Patches a single item by its stable dataset_item_id and creates a new version.
     */
    private Mono<Long> patchItemWithVersion(UUID datasetItemId, DatasetItem patchData,
            String workspaceId, String userName) {
        // Resolve which dataset contains this item
        return versionDao.resolveDatasetIdFromItemId(datasetItemId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Item '{}' not found in versioned table", datasetItemId);
                    return Mono.error(failWithNotFound("Dataset item not found"));
                }))
                .flatMap(datasetId -> {
                    // Ensure dataset is migrated if lazy migration is enabled
                    return ensureLazyMigration(datasetId, workspaceId)
                            .thenReturn(datasetId);
                })
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
                    return versionDao.getItemByDatasetItemId(datasetId, baseVersionId, datasetItemId)
                            .switchIfEmpty(Mono.defer(() -> {
                                log.warn("Item '{}' not found in dataset '{}' version '{}'",
                                        datasetItemId, datasetId, baseVersionId);
                                return Mono.error(failWithNotFound("Dataset item not found"));
                            }))
                            .flatMap(existingItem -> {
                                // Apply patch to the existing item
                                DatasetItem patchedItem = applyPatchToItem(
                                        existingItem, patchData, datasetId, userName);

                                log.info("Creating version with single item edit for dataset '{}', baseVersion='{}'",
                                        datasetId, baseVersionId);

                                // Generate UUIDs for items
                                // The edited item is excluded from the copy, so we need baseItemsCount - 1 UUIDs for unchanged items
                                // Use Math.max to handle edge case where baseItemsCount could be 0 or 1
                                int unchangedCount = Math.max(0, baseItemsCount - 1);
                                List<UUID> unchangedUuids = generateUnchangedUuidsReversed(unchangedCount);

                                DatasetItem patchedItemWithId = patchedItem.toBuilder()
                                        .id(existingItem.id()) // Preserve the original row ID
                                        .build();

                                // Apply delta with only the edited item
                                return versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                                        List.of(), // No added items
                                        List.of(patchedItemWithId), // Single edited item
                                        Set.of(), // No deleted items
                                        unchangedUuids,
                                        Set.of())
                                        .flatMap(itemsTotal -> {
                                            log.info("Applied patch delta to dataset '{}': itemsTotal '{}'",
                                                    datasetId, itemsTotal);

                                            // Create version metadata
                                            return Mono.fromCallable(() -> versionService.createVersionFromDelta(
                                                    datasetId,
                                                    newVersionId,
                                                    itemsTotal.intValue(),
                                                    baseVersionId,
                                                    null, // No tags
                                                    "Updated 1 item",
                                                    null, // No batch group ID
                                                    workspaceId,
                                                    userName))
                                                    .retryWhen(RetryUtils.handleOnDeadLocks())
                                                    .doOnSuccess(version -> log.info(
                                                            "Created version '{}' for dataset '{}' after patch",
                                                            newVersionId, datasetId))
                                                    .thenReturn(itemsTotal);
                                        });
                            });
                });
    }

    /**
     * Applies patch fields from patchData to a builder.
     * Only non-null fields from patchData are applied.
     */
    private void applyPatchFields(DatasetItem.DatasetItemBuilder builder, DatasetItem patchData) {
        Optional.ofNullable(patchData.data()).ifPresent(builder::data);
        Optional.ofNullable(patchData.source()).ifPresent(builder::source);
        Optional.ofNullable(patchData.traceId()).ifPresent(builder::traceId);
        Optional.ofNullable(patchData.spanId()).ifPresent(builder::spanId);
        Optional.ofNullable(patchData.tags()).ifPresent(builder::tags);
    }

    /**
     * Applies patch data to an item, returning a new DatasetItem with the changes.
     */
    private DatasetItem applyPatchToItem(DatasetItem existingItem, DatasetItem patchData,
            UUID datasetId, String userName) {
        var builder = existingItem.toBuilder()
                .lastUpdatedAt(java.time.Instant.now())
                .lastUpdatedBy(userName);

        applyPatchFields(builder, patchData);

        return builder.build();
    }

    @WithSpan
    public Mono<Void> batchUpdate(@NonNull DatasetItemBatchUpdate batchUpdate) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (featureFlags.isDatasetVersioningEnabled()) {
                log.info("Batch updating items with versioning, idsSize='{}', filtersSize='{}'",
                        batchUpdate.ids() != null ? batchUpdate.ids().size() : 0,
                        batchUpdate.filters() != null ? batchUpdate.filters().size() : 0);
                return batchUpdateWithVersion(batchUpdate, workspaceId, userName);
            }

            // Legacy: bulk update in legacy table
            log.info("Batch updating items in legacy table, idsSize='{}', filtersSize='{}'",
                    batchUpdate.ids() != null ? batchUpdate.ids().size() : 0,
                    batchUpdate.filters() != null ? batchUpdate.filters().size() : 0);
            return dao.bulkUpdate(batchUpdate.ids(), batchUpdate.datasetId(), batchUpdate.filters(),
                    batchUpdate.update(),
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
        // Determine the dataset ID
        UUID datasetId;

        if (batchUpdate.datasetId() != null) {
            // Use the explicitly provided dataset ID (required for filter-based updates)
            datasetId = batchUpdate.datasetId();
            log.info("Using provided dataset ID '{}' for batch update by filters with versioning", datasetId);

            return batchUpdateByFiltersWithVersioning(datasetId, batchUpdate, workspaceId, userName);
        }

        // For batch update by IDs without explicit dataset ID, map row IDs to dataset_item_ids
        // The frontend sends 'id' (row ID) but we need 'dataset_item_id' (stable ID) for updates
        if (CollectionUtils.isNotEmpty(batchUpdate.ids())) {
            return versionDao.mapRowIdsToDatasetItemIds(batchUpdate.ids())
                    .collectList()
                    .flatMap(mappings -> {
                        if (mappings.isEmpty()) {
                            // No mappings found - items don't exist or were deleted
                            log.warn("No mappings found for provided row IDs, items not found");
                            return Mono.error(failWithNotFound("Dataset items not found"));
                        }

                        // Verify all mappings belong to the same dataset
                        validateMappingsBelongToSameDataset(mappings);

                        // Extract dataset_item_ids and dataset_id from mappings (NO additional query!)
                        Set<UUID> datasetItemIds = mappings.stream()
                                .map(DatasetItemIdMapping::datasetItemId)
                                .collect(Collectors.toSet());
                        UUID mappedDatasetId = mappings.get(0).datasetId();

                        log.info("Mapped '{}' row IDs to '{}' dataset_item_ids for dataset '{}'",
                                batchUpdate.ids().size(), datasetItemIds.size(), mappedDatasetId);

                        // Create a new batchUpdate with mapped dataset_item_ids
                        DatasetItemBatchUpdate mappedBatchUpdate = DatasetItemBatchUpdate.builder()
                                .ids(datasetItemIds)
                                .filters(batchUpdate.filters())
                                .datasetId(batchUpdate.datasetId())
                                .update(batchUpdate.update())
                                .mergeTags(batchUpdate.mergeTags())
                                .build();

                        return batchUpdateByIdsWithVersioning(mappedDatasetId, mappedBatchUpdate, workspaceId,
                                userName);
                    });
        }

        // This should not happen due to validation, but handle it gracefully
        log.error("Batch update with versioning requires either IDs or dataset ID with filters");
        return Mono.error(new BadRequestException(
                "Batch update requires either item IDs or dataset ID with filters"));
    }

    /**
     * Batch updates items by IDs and creates a new version.
     */
    private Mono<Void> batchUpdateByIdsWithVersioning(UUID datasetId, DatasetItemBatchUpdate batchUpdate,
            String workspaceId, String userName) {

        int updateSize = batchUpdate.ids().size();
        log.info("Batch updating '{}' items by IDs with versioning for dataset '{}'", updateSize, datasetId);

        // Ensure dataset is migrated if lazy migration is enabled
        return ensureLazyMigration(datasetId, workspaceId)
                .then(Mono.defer(() -> {
                    // Get the latest version
                    return getLatestVersionOrError(datasetId, workspaceId)
                            .flatMap(latestVersion -> {
                                UUID baseVersionId = latestVersion.id();
                                UUID newVersionId = idGenerator.generateId();
                                int baseItemsCount = latestVersion.itemsTotal();

                                // For ID-based: generate single UUID pool and split it
                                int totalPoolSize = baseItemsCount * 2; // Conservative: 2x base count
                                List<UUID> allUuids = generateUuidPool(idGenerator, totalPoolSize);
                                List<UUID> updateUuids = allUuids.subList(0, updateSize);
                                List<UUID> copyUuids = allUuids.subList(updateSize, allUuids.size());

                                log.debug(
                                        "Split UUID pool for ID-based update: updateSize='{}', copySize='{}'",
                                        updateUuids.size(), copyUuids.size());

                                // Perform batch update
                                return versionDao
                                        .batchUpdateItems(datasetId, baseVersionId, newVersionId,
                                                batchUpdate,
                                                updateUuids)
                                        .flatMap(updatedCount -> {
                                            if (updatedCount == 0) {
                                                log.info("No items found to update for dataset '{}'",
                                                        datasetId);
                                                return Mono.empty();
                                            }

                                            log.info(
                                                    "Batch updated '{}' items by IDs for dataset '{}', baseVersion='{}'",
                                                    updatedCount, datasetId, baseVersionId);

                                            // Generate UUIDs for unchanged items
                                            List<UUID> unchangedUuids = generateUnchangedUuidsReversed(
                                                    baseItemsCount);

                                            // Copy unchanged items using applyDelta (exclude updated IDs)
                                            return versionDao
                                                    .applyDelta(datasetId, baseVersionId, newVersionId,
                                                            List.of(), // No added items
                                                            List.of(), // No edited items (already done via batch update)
                                                            batchUpdate.ids(), // Exclude updated items from copy
                                                            unchangedUuids,
                                                            Set.of())
                                                    .flatMap(unchangedCount -> createVersionMetadata(
                                                            datasetId, newVersionId, baseVersionId,
                                                            updatedCount, unchangedCount, false,
                                                            workspaceId, userName));
                                        });
                            });
                }))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .then();
    }

    /**
     * Batch updates items by filters and creates a new version.
     */
    private Mono<Void> batchUpdateByFiltersWithVersioning(UUID datasetId, DatasetItemBatchUpdate batchUpdate,
            String workspaceId, String userName) {

        log.info("Batch updating items by filters with versioning for dataset '{}'", datasetId);

        // Ensure dataset is migrated if lazy migration is enabled
        return ensureLazyMigration(datasetId, workspaceId)
                .then(Mono.defer(() -> {
                    // Get the latest version
                    return getLatestVersionOrError(datasetId, workspaceId)
                            .flatMap(latestVersion -> {
                                UUID baseVersionId = latestVersion.id();
                                UUID newVersionId = idGenerator.generateId();
                                int baseItemsCount = latestVersion.itemsTotal();

                                // For filter-based: generate 2 separate UUID pools
                                List<UUID> updateUuids = generateUuidPool(idGenerator, baseItemsCount * 2);
                                List<UUID> copyUuids = generateUuidPool(idGenerator, baseItemsCount * 2);

                                log.debug(
                                        "Generated separate UUID pools for filter-based update: updateSize='{}', copySize='{}'",
                                        updateUuids.size(), copyUuids.size());

                                // Perform batch update
                                return versionDao
                                        .batchUpdateItems(datasetId, baseVersionId, newVersionId,
                                                batchUpdate,
                                                updateUuids)
                                        .flatMap(updatedCount -> {
                                            if (updatedCount == 0) {
                                                log.info("No items found to update for dataset '{}'",
                                                        datasetId);
                                                return Mono.empty();
                                            }

                                            log.info(
                                                    "Batch updated '{}' items by filters for dataset '{}', baseVersion='{}'",
                                                    updatedCount, datasetId, baseVersionId);

                                            // Copy unchanged items (those NOT matching the filters)
                                            // Special case: empty filters list means "select all" - no unchanged items to copy
                                            if (batchUpdate.filters() != null
                                                    && batchUpdate.filters().isEmpty()) {
                                                // Empty filters means all items were updated - nothing to copy
                                                log.info(
                                                        "Empty filters (select all) - skipping copy of unchanged items");
                                                return createVersionMetadata(
                                                        datasetId, newVersionId, baseVersionId,
                                                        updatedCount, 0L, true,
                                                        workspaceId, userName);
                                            }

                                            // Copy unchanged items using copyVersionItems (exclude matching filters)
                                            return versionDao
                                                    .copyVersionItems(datasetId, baseVersionId,
                                                            newVersionId,
                                                            batchUpdate.filters(), copyUuids)
                                                    .flatMap(unchangedCount -> createVersionMetadata(
                                                            datasetId, newVersionId, baseVersionId,
                                                            updatedCount, unchangedCount, true,
                                                            workspaceId, userName));
                                        });
                            });
                }))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .then();
    }

    /**
     * Shared helper: Gets the latest version or returns an error if none exists.
     */
    private Mono<DatasetVersion> getLatestVersionOrError(UUID datasetId, String workspaceId) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

        if (latestVersion.isEmpty()) {
            log.error("No versions exist for dataset '{}'", datasetId);
            return Mono.error(failWithNotFound("No versions exist for dataset"));
        }

        return Mono.just(latestVersion.get());
    }

    /**
     * Shared helper: Creates version metadata after a successful batch update.
     */
    private Mono<Void> createVersionMetadata(UUID datasetId, UUID newVersionId, UUID baseVersionId,
            long updatedCount, long unchangedCount, boolean isFilterBased,
            String workspaceId, String userName) {

        long itemsTotal = updatedCount + unchangedCount;
        log.info("Applied batch update delta to dataset '{}': updated='{}', unchanged='{}', total='{}', type='{}'",
                datasetId, updatedCount, unchangedCount, itemsTotal, isFilterBased ? "filter" : "ids");

        // Create version metadata
        String changeDescription = createChangeDescription(updatedCount, isFilterBased);

        return Mono.fromCallable(() -> versionService.createVersionFromDelta(
                datasetId,
                newVersionId,
                (int) itemsTotal,
                baseVersionId,
                null, // No tags
                changeDescription,
                null, // No batch group ID
                workspaceId,
                userName))
                .retryWhen(RetryUtils.handleOnDeadLocks())
                .doOnSuccess(version -> log.info("Created version '{}' for dataset '{}' after batch update",
                        newVersionId, datasetId))
                .then();
    }

    /**
     * Creates a human-readable change description for version metadata.
     */
    private String createChangeDescription(long count, boolean isFilterBased) {
        if (count == 1) {
            return isFilterBased ? "Updated 1 item by filters" : "Updated 1 item";
        }
        return isFilterBased
                ? "Updated " + count + " items by filters"
                : "Updated " + count + " items";
    }

    /**
     * Validates that all item mappings belong to the same dataset.
     * Throws BadRequestException if items span multiple datasets.
     */
    private void validateMappingsBelongToSameDataset(List<DatasetItemIdMapping> mappings) {
        Set<UUID> distinctDatasetIds = mappings.stream()
                .map(DatasetItemIdMapping::datasetId)
                .collect(Collectors.toSet());

        if (distinctDatasetIds.size() > 1) {
            log.error("Batch update with IDs spans multiple datasets: '{}'", distinctDatasetIds);
            throw new BadRequestException("Cannot batch update items across multiple datasets");
        }
    }

    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull String workspaceId, @NonNull DatasetItemStreamRequest request,
            @NonNull List<DatasetItemFilter> filters, Visibility visibility) {
        log.info("Getting dataset items for dataset '{}' (hasFilters={}), version='{}', workspaceId='{}'",
                request.datasetName(), !filters.isEmpty(),
                request.datasetVersion(), workspaceId);

        return Mono
                .fromCallable(() -> datasetService.findByName(workspaceId, request.datasetName(), visibility))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(dataset -> Mono.deferContextual(ctx -> {
                    // Ensure dataset is migrated if lazy migration is enabled
                    return ensureLazyMigration(dataset.id(), workspaceId)
                            .thenReturn(dataset);
                }))
                .flatMapMany(dataset -> {
                    // 3-tier version resolution logic:
                    // 1. If version parameter is specified, use it
                    // 2. If feature toggle is ON and no version specified, use latest version
                    // 3. Otherwise, use legacy table (existing behavior)

                    String versionHashOrTag = request.datasetVersion();

                    // Case 1: Version explicitly specified
                    if (versionHashOrTag != null && !versionHashOrTag.isBlank()) {
                        log.info("Using explicitly provided version '{}' for streaming dataset '{}' items",
                                versionHashOrTag, dataset.id());
                        return Mono.fromCallable(() -> versionService.resolveVersionId(workspaceId, dataset.id(),
                                versionHashOrTag))
                                .flatMapMany(versionId -> versionDao.getItems(dataset.id(), versionId,
                                        request.steamLimit(), request.lastRetrievedId(), filters));
                    }

                    // Case 2: Feature toggle ON and no version specified - use latest version
                    if (featureFlags.isDatasetVersioningEnabled()) {
                        log.info("Feature toggle ON, using latest version for streaming dataset '{}' items",
                                dataset.id());
                        return Mono.fromCallable(() -> versionService.getLatestVersion(dataset.id(), workspaceId))
                                .flatMapMany(latestVersionOpt -> {
                                    if (latestVersionOpt.isPresent()) {
                                        UUID versionId = latestVersionOpt.get().id();
                                        log.info("Streaming from latest version '{}' for dataset '{}'", versionId,
                                                dataset.id());
                                        return versionDao.getItems(dataset.id(), versionId, request.steamLimit(),
                                                request.lastRetrievedId(), filters);
                                    } else {
                                        // No version exists yet - return empty
                                        log.warn("No versions exist for dataset '{}', returning empty stream",
                                                dataset.id());
                                        return Flux.empty();
                                    }
                                });
                    }

                    // Case 3: Feature toggle OFF - use legacy table
                    log.info("Feature toggle OFF, using legacy table for streaming dataset '{}' items", dataset.id());
                    return dao.getItems(dataset.id(), request.steamLimit(), request.lastRetrievedId(), filters);
                });
    }

    @Override
    public Mono<PageColumns> getOutputColumns(@NonNull UUID datasetId, Set<UUID> experimentIds) {
        if (featureFlags.isDatasetVersioningEnabled()) {
            log.info("Getting output columns with versioning for dataset '{}', experimentIds '{}'", datasetId,
                    experimentIds);

            return versionDao.getExperimentItemsOutputColumns(datasetId, experimentIds)
                    .map(columns -> PageColumns.builder().columns(columns).build())
                    .switchIfEmpty(Mono.just(PageColumns.empty()));
        }

        // Versioning toggle is OFF: use legacy table
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

        // Create a batch with the items
        DatasetItemBatch batch = DatasetItemBatch.builder().datasetId(datasetId).items(items).build();

        // Route to versioned or legacy based on toggle
        if (featureFlags.isDatasetVersioningEnabled()) {
            log.info("Saving batch with versioning for dataset '{}', itemCount '{}'", datasetId, items.size());
            return saveItemsWithVersion(batch, datasetId, null)
                    .map(version -> (long) items.size())
                    .defaultIfEmpty((long) items.size());
        }

        // Legacy: save to legacy table
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
    public Mono<Void> delete(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters, UUID batchGroupId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (!featureFlags.isDatasetVersioningEnabled()) {
                // Legacy: delete from legacy table
                log.info("Deleting items from legacy table. datasetId='{}', itemIdsSize='{}', filtersSize='{}'",
                        datasetId, ids != null ? ids.size() : 0, filters != null ? filters.size() : 0);
                return dao.delete(ids, datasetId, filters).then();
            }

            if (batchGroupId == null) {
                // No batch_group_id: mutate the latest version (backwards compatibility)
                log.info(
                        "Mutating latest version with delete (no batch_group_id). datasetId='{}', itemIdsSize='{}', filtersSize='{}'",
                        datasetId, ids != null ? ids.size() : 0, filters != null ? filters.size() : 0);
                return deleteItemsWithVersion(ids, datasetId, filters, workspaceId, userName, null);
            }

            // batch_group_id provided: create new version with batch grouping
            log.info(
                    "Creating version with batch grouping for delete. batchGroupId='{}', datasetId='{}', itemIdsSize='{}', filtersSize='{}'",
                    batchGroupId, datasetId, ids != null ? ids.size() : 0, filters != null ? filters.size() : 0);
            return getDatasetIdOrResolveItemDatasetId(datasetId, ids)
                    .flatMap(resolvedDatasetId -> handleGroupedDeletion(
                            batchGroupId, ids, resolvedDatasetId, filters, workspaceId, userName, true));
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
            String workspaceId, String userName, UUID batchGroupId) {
        // Case 1: Deleting by item IDs
        if (CollectionUtils.isNotEmpty(ids)) {
            return deleteByItemIdsWithVersion(ids, workspaceId, userName, batchGroupId);
        }

        // Case 2: Deleting by datasetId with filters
        if (datasetId != null) {
            return deleteByDatasetIdWithVersion(datasetId, filters, workspaceId, userName, batchGroupId);
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
            String workspaceId, String userName, UUID batchGroupId) {
        // Derive createVersion from batchGroupId: null means mutate latest, non-null means create new
        boolean createVersion = batchGroupId != null;
        log.info(
                "Deleting items by datasetId '{}' with versioning, filtersSize='{}', batchGroupId='{}', createVersion='{}'",
                datasetId, filters != null ? filters.size() : 0, batchGroupId, createVersion);

        // Verify dataset exists
        datasetService.findById(datasetId, workspaceId, null);

        // Ensure dataset is migrated if lazy migration is enabled
        return ensureLazyMigration(datasetId, workspaceId)
                .then(Mono.defer(() -> {
                    // Get the latest version (using overload that takes workspaceId)
                    Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

                    if (latestVersion.isEmpty()) {
                        // No versions exist - fall back to legacy delete
                        log.info("No versions exist for dataset '{}', falling back to legacy delete", datasetId);
                        return dao.delete(null, datasetId, filters).then();
                    }

                    // Handle in-place mutation for filter-based deletions when createVersion=false
                    if (!createVersion) {
                        log.info("Mutating latest version '{}' for dataset '{}' (createVersion=false)",
                                latestVersion.get().id(),
                                datasetId);

                        return deleteItemsFromExistingVersionByFilters(datasetId, latestVersion.get().id(), filters,
                                workspaceId,
                                userName);
                    }

                    // Create a new version with deletions
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

                                log.info(
                                        "Creating version metadata: dataset='{}', baseVersion='{}', newVersion='{}', " +
                                                "deletedCount='{}', newItemCount='{}'",
                                        datasetId, baseVersionId, newVersionId, deletedCount, newVersionItemCount);

                                // Create version metadata
                                String changeDescription = deletedCount == 1
                                        ? "Deleted 1 item"
                                        : "Deleted " + deletedCount + " items";

                                return Mono.fromCallable(() -> versionService.createVersionFromDelta(
                                        datasetId,
                                        newVersionId,
                                        newVersionItemCount.intValue(),
                                        baseVersionId,
                                        null, // No tags
                                        changeDescription,
                                        batchGroupId, // Pass batch group ID
                                        workspaceId,
                                        userName)).retryWhen(RetryUtils.handleOnDeadLocks());
                            })
                            .then();
                }));
    }

    /**
     * Deletes items by item IDs, creating a new version or mutating the latest version.
     */
    private Mono<Void> deleteByItemIdsWithVersion(Set<UUID> ids, String workspaceId, String userName,
            UUID batchGroupId) {
        // Derive createVersion from batchGroupId: null means mutate latest, non-null means create new
        boolean createVersion = batchGroupId != null;
        log.info("Deleting '{}' items by IDs with versioning, batchGroupId='{}', createVersion='{}'",
                ids.size(), batchGroupId, createVersion);

        // Try to map the provided IDs as row IDs (from frontend) to dataset_item_ids
        return versionDao.mapRowIdsToDatasetItemIds(ids)
                .collectList()
                .flatMap(mappings -> {
                    if (mappings.isEmpty()) {
                        // IDs are already dataset_item_ids (from SDK) - resolve dataset from any existing item
                        log.info("No row ID mappings found, treating as dataset_item_ids and resolving dataset");

                        // Try to resolve dataset ID from any of the provided IDs (not just the first)
                        // This handles cases where some IDs may not exist (already deleted)
                        return versionDao.resolveDatasetIdFromItemIds(ids)
                                .flatMap(datasetId -> {
                                    log.info("Resolved dataset '{}' for deletion request with '{}' item IDs",
                                            datasetId, ids.size());
                                    return deleteByDatasetItemIdsInDataset(ids, datasetId, workspaceId, userName,
                                            batchGroupId, createVersion);
                                })
                                .switchIfEmpty(Mono.defer(() -> {
                                    // None of the items found - DELETE is idempotent, so this is not an error
                                    log.info(
                                            "None of the '{}' items found in versioned table, treating as already deleted",
                                            ids.size());
                                    return Mono.empty();
                                }));
                    }

                    // Successfully mapped row IDs to dataset_item_ids
                    Set<UUID> datasetItemIds = mappings.stream()
                            .map(DatasetItemVersionDAO.DatasetItemIdMapping::datasetItemId)
                            .collect(Collectors.toSet());
                    UUID datasetId = mappings.get(0).datasetId();

                    log.info("Mapped '{}' row IDs to '{}' dataset_item_ids for dataset '{}'",
                            ids.size(), datasetItemIds.size(), datasetId);

                    return deleteByDatasetItemIdsInDataset(datasetItemIds, datasetId, workspaceId, userName,
                            batchGroupId, createVersion);
                });
    }

    /**
     * Deletes items by dataset_item_id values within a known dataset.
     */
    private Mono<Void> deleteByDatasetItemIdsInDataset(Set<UUID> datasetItemIds, UUID datasetId,
            String workspaceId, String userName, UUID batchGroupId, boolean createVersion) {
        log.info("Deleting '{}' items from dataset '{}' with versioning, batchGroupId='{}', createVersion='{}'",
                datasetItemIds.size(), datasetId, batchGroupId, createVersion);

        // Ensure dataset is migrated if lazy migration is enabled
        return ensureLazyMigration(datasetId, workspaceId)
                .then(Mono.defer(() -> {
                    // Get the latest version (use overload that takes workspaceId since we're in reactive context)
                    Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

                    if (latestVersion.isEmpty()) {
                        // No versions exist
                        if (!createVersion) {
                            // createVersion=false: Nothing to mutate, just return empty (idempotent delete)
                            log.info("No versions exist for dataset '{}', nothing to delete (createVersion=false)",
                                    datasetId);
                            return Mono.empty();
                        }
                        // createVersion=true: Fall back to legacy delete
                        log.info("No versions exist for dataset '{}', falling back to legacy delete", datasetId);
                        return dao.delete(datasetItemIds, null, null).then();
                    }

                    UUID latestVersionId = latestVersion.get().id();
                    int baseVersionItemCount = latestVersion.get().itemsTotal();

                    // If createVersion=false, mutate the latest version instead of creating a new one
                    if (!createVersion) {
                        log.info("Mutating latest version '{}' for dataset '{}' (createVersion=false)", latestVersionId,
                                datasetId);
                        return deleteItemsFromExistingVersion(datasetItemIds, datasetId, null, latestVersionId,
                                workspaceId,
                                userName);
                    }

                    // createVersion=true: Create a new version with deletions
                    UUID newVersionId = idGenerator.generateId();
                    log.info("Creating new version for dataset '{}' with '{}' items deleted",
                            datasetId, datasetItemIds.size());

                    return createVersionWithDeletion(datasetId, latestVersionId, newVersionId, datasetItemIds,
                            baseVersionItemCount, batchGroupId, workspaceId, userName);
                }));
    }

    /**
     * Creates a new version with the specified items deleted (excluded from the new version).
     */
    private Mono<Void> createVersionWithDeletion(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            Set<UUID> deletedIds, int baseVersionItemCount, UUID batchGroupId,
            String workspaceId, String userName) {

        // Generate UUIDs for unchanged items (items that are NOT being deleted)
        int unchangedItemCount = baseVersionItemCount - deletedIds.size();
        List<UUID> unchangedUuids = generateUnchangedUuidsReversed(unchangedItemCount);

        // Apply delta with only deletions (no adds or edits)
        return versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                List.of(), // No added items
                List.of(), // No edited items
                deletedIds,
                unchangedUuids,
                Set.of())
                .flatMap(itemsTotal -> {
                    log.info("Applied deletion delta to dataset '{}': itemsTotal '{}'", datasetId, itemsTotal);

                    // Create version metadata
                    return Mono.fromCallable(() -> versionService.createVersionFromDelta(
                            datasetId,
                            newVersionId,
                            itemsTotal.intValue(),
                            baseVersionId,
                            null, // No tags
                            null, // No change description (auto-generated)
                            batchGroupId, // Include batch group ID if provided
                            workspaceId,
                            userName))
                            .retryWhen(RetryUtils.handleOnDeadLocks())
                            .doOnSuccess(version -> log.info("Created version '{}' for dataset '{}' after deletion",
                                    version.id(), datasetId));
                })
                .then();
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(
            int page, int size, @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria) {

        // Verify dataset visibility
        datasetService.findById(datasetItemSearchCriteria.datasetId());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // Ensure dataset is migrated if lazy migration is enabled
            return ensureLazyMigration(datasetItemSearchCriteria.datasetId(), workspaceId)
                    .then(Mono.defer(() -> getItemsInternal(page, size, datasetItemSearchCriteria)));
        });
    }

    private Mono<DatasetItemPage> getItemsInternal(
            int page, int size, @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria) {

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
        } else if (featureFlags.isDatasetVersioningEnabled()) {
            // Versioning toggle is ON
            return Mono.deferContextual(ctx -> {
                String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                // If experimentIds are present, fetch items using experiment-specific versions
                if (CollectionUtils.isNotEmpty(datasetItemSearchCriteria.experimentIds())) {
                    log.info(
                            "Finding dataset items with experiment items by '{}', page '{}', size '{}' (using experiment-specific versions)",
                            datasetItemSearchCriteria, page, size);

                    return getItemsWithExperimentItems(datasetItemSearchCriteria, page, size,
                            workspaceId);
                }

                // Otherwise, fetch items from the latest version
                log.info("Finding latest version dataset items by '{}', page '{}', size '{}'",
                        datasetItemSearchCriteria, page, size);
                return getItemsFromLatestVersion(datasetItemSearchCriteria, page, size, workspaceId);
            });
        } else {
            // Versioning toggle is OFF: fetch items from dataset_items table
            log.info("Finding draft dataset items by '{}', page '{}', size '{}'",
                    datasetItemSearchCriteria, page, size);

            return dao.getItems(datasetItemSearchCriteria, page, size)
                    .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
        }
    }

    private Mono<DatasetItemPage> getItemsFromLatestVersion(DatasetItemSearchCriteria criteria, int page, int size,
            String workspaceId) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(criteria.datasetId(), workspaceId);

        if (latestVersion.isEmpty()) {
            // No versions exist yet - fall back to legacy items
            log.info("No versions found for dataset '{}', falling back to draft items", criteria.datasetId());
            return dao.getItems(criteria, page, size)
                    .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
        }

        UUID versionId = latestVersion.get().id();
        log.info("Fetching items from latest version '{}' for dataset '{}'", versionId, criteria.datasetId());

        return versionDao.getItems(criteria, page, size, versionId)
                .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
    }

    /**
     * Helper method to get the fallback version ID for a dataset.
     * Returns the latest version ID if it exists, otherwise returns empty.
     *
     * @param datasetId The dataset ID
     * @param workspaceId The workspace ID
     * @return Optional containing the fallback version ID, or empty if no versions exist
     */
    private Optional<UUID> getFallbackVersionId(UUID datasetId, String workspaceId) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

        if (latestVersion.isEmpty()) {
            log.error("No versions found for dataset '{}' when versioning is enabled", datasetId);
            return Optional.empty();
        }

        return Optional.of(latestVersion.get().id());
    }

    /**
     * Helper method to get experiment items stats using the legacy DAO.
     * This is used as a fallback when versioning is disabled or no versions exist yet.
     *
     * @param datasetId The dataset ID
     * @param experimentIds The experiment IDs
     * @param filters The filters to apply
     * @return Mono containing the project stats
     */
    private Mono<ProjectStats> getExperimentItemsStatsFromLegacyDao(UUID datasetId,
            Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        return dao.getExperimentItemsStats(datasetId, experimentIds, filters)
                .switchIfEmpty(Mono.just(ProjectStats.empty()))
                .doOnSuccess(stats -> log.info("Found experiment items stats for dataset '{}', count '{}'",
                        datasetId, stats.stats().size()));
    }

    private Mono<DatasetItemPage> getItemsWithExperimentItems(DatasetItemSearchCriteria criteria,
            int page, int size, String workspaceId) {
        Optional<UUID> fallbackVersionId = getFallbackVersionId(criteria.datasetId(), workspaceId);

        if (fallbackVersionId.isEmpty()) {
            return Mono.just(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
        }

        log.info(
                "Fetching items with experiment items for dataset '{}', using version '{}' as fallback for experiments without explicit version",
                criteria.datasetId(), fallbackVersionId.get());

        // Fetch items using experiment-specific versions, falling back to fallbackVersionId for experiments without a version
        return versionDao.getItemsWithExperimentItems(criteria, page, size, fallbackVersionId.get())
                .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
    }

    public Mono<ProjectStats> getExperimentItemsStats(@NonNull UUID datasetId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        log.info("Getting experiment items stats for dataset '{}' and experiments '{}' with filters '{}'", datasetId,
                experimentIds, filters);

        if (!featureFlags.isDatasetVersioningEnabled()) {
            // Feature toggle OFF - use legacy DAO
            log.debug("Dataset versioning disabled, using legacy DAO for stats");
            return getExperimentItemsStatsFromLegacyDao(datasetId, experimentIds, filters);
        }

        // Feature toggle ON - use versioned DAO with experiment-specific versions
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            Optional<UUID> fallbackVersionId = getFallbackVersionId(datasetId, workspaceId);

            if (fallbackVersionId.isEmpty()) {
                // No versions exist yet - fall back to legacy DAO
                log.info("No versions found for dataset '{}', falling back to legacy DAO for stats", datasetId);
                return getExperimentItemsStatsFromLegacyDao(datasetId, experimentIds, filters);
            }

            log.debug(
                    "Dataset versioning enabled, using version '{}' as fallback for experiments without explicit version",
                    fallbackVersionId.get());
            return versionDao.getExperimentItemsStats(datasetId, fallbackVersionId.get(), experimentIds, filters)
                    .switchIfEmpty(Mono.just(ProjectStats.empty()))
                    .doOnSuccess(stats -> log.info(
                            "Found experiment items stats for dataset '{}', count '{}' (using experiment-specific versions with fallback '{}')",
                            datasetId, stats.stats().size(), fallbackVersionId.get()));
        });
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
            DatasetVersion baseVersion = versionService.getVersionById(workspaceId, datasetId, baseVersionId);
            int baseVersionItemCount = baseVersion.itemsTotal();

            // Check if baseVersion is the latest (unless override is set)
            if (!override && !versionService.isLatestVersion(workspaceId, datasetId, baseVersionId)) {
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

            // Prepare added items (synchronous - no merging needed)
            List<DatasetItem> addedItems = prepareAddedItems(changes, datasetId);
            Set<UUID> deletedRowIds = changes.deletedIds() != null ? changes.deletedIds() : Set.of();

            // Resolve edited item row IDs to stable dataset_item_ids (no fetch needed)
            List<DatasetItemEdit> editedItemEdits = changes.editedItems() != null ? changes.editedItems() : List.of();
            Set<UUID> editedRowIds = editedItemEdits.stream()
                    .map(DatasetItemEdit::id)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Mono<Map<UUID, UUID>> editMappingsMono = resolveEditMappings(editedRowIds);
            Mono<Set<UUID>> deletedItemIdsMono = mapRowIdsToDatasetItemIds(deletedRowIds);

            return Mono.zip(editMappingsMono, deletedItemIdsMono)
                    .flatMap(tuple -> {
                        Map<UUID, UUID> rowIdToDatasetItemId = tuple.getT1();
                        Set<UUID> deletedIds = tuple.getT2();

                        Set<UUID> editedDatasetItemIds = new HashSet<>(rowIdToDatasetItemId.values());

                        // Generate UUIDs for all items in the correct order for ClickHouse's ORDER BY id DESC
                        // Since UUIDv7 is time-ordered (later = larger) and we sort DESC (largest first),
                        // we need to generate UUIDs in reverse order of desired appearance:
                        // 1. Unchanged items first (smallest UUIDs) - will appear LAST
                        // 2. Edited items second (middle UUIDs) - will appear in MIDDLE
                        // 3. Added items last (largest UUIDs) - will appear FIRST

                        // However, we reverse the unchanged UUID pool to maintain original order
                        List<UUID> unchangedUuids = generateUnchangedUuidsReversed(baseVersionItemCount);
                        List<UUID> editedUuids = generateUuidPool(idGenerator, editedItemEdits.size());
                        List<UUID> addedUuids = generateUuidPool(idGenerator, addedItems.size());

                        List<DatasetItem> addedItemsWithIds = withAssignedRowIds(addedItems, addedUuids);

                        // Edit items via INSERT...SELECT (merge happens in SQL, not Java)
                        Mono<Long> editedCountMono = versionDao.editItemsViaSelectInsert(
                                datasetId, baseVersionId, newVersionId,
                                editedItemEdits, rowIdToDatasetItemId, editedUuids);

                        // Apply delta for added items + copy unchanged (exclude edited + deleted)
                        return editedCountMono
                                .flatMap(editedCount -> versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                                        addedItemsWithIds, List.of(), deletedIds, unchangedUuids,
                                        editedDatasetItemIds)
                                        .map(otherCount -> editedCount + otherCount))
                                .flatMap(itemsTotal -> {
                                    log.info("Applied delta to dataset '{}': itemsTotal '{}'", datasetId, itemsTotal);

                                    return Mono.fromCallable(() -> versionService.createVersionFromDelta(
                                            datasetId,
                                            newVersionId,
                                            itemsTotal.intValue(),
                                            baseVersionId,
                                            changes.tags(),
                                            changes.changeDescription(),
                                            null,
                                            workspaceId,
                                            userName))
                                            .doOnSuccess(version -> log.info(
                                                    "Created version '{}' for dataset '{}' with hash '{}'",
                                                    version.id(), datasetId, version.versionHash()));
                                });
                    });
        });
    }

    /**
     * Prepares added items by setting their stable IDs.
     * For new items, we generate a new stable ID.
     */
    private List<DatasetItem> prepareAddedItems(DatasetItemChanges changes, UUID datasetId) {
        if (changes.addedItems() == null || changes.addedItems().isEmpty()) {
            return List.of();
        }

        return changes.addedItems().stream()
                .map(item -> {
                    // Generate new stable ID for new items
                    UUID stableId = idGenerator.generateId();
                    // Set datasetItemId (stable ID) but leave id null - it will be assigned later in this method
                    return item.toBuilder()
                            .id(null)
                            .datasetItemId(stableId)
                            .datasetId(datasetId)
                            .build();
                })
                .toList();
    }

    private Mono<Map<UUID, UUID>> resolveEditMappings(Set<UUID> editedRowIds) {
        if (editedRowIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        return versionDao.mapRowIdsToDatasetItemIds(editedRowIds)
                .collectList()
                .flatMap(mappings -> {
                    Map<UUID, UUID> map = mappings.stream()
                            .collect(Collectors.toMap(
                                    DatasetItemVersionDAO.DatasetItemIdMapping::rowId,
                                    DatasetItemVersionDAO.DatasetItemIdMapping::datasetItemId));

                    Set<UUID> missing = editedRowIds.stream()
                            .filter(id -> !map.containsKey(id))
                            .collect(Collectors.toSet());

                    if (!missing.isEmpty()) {
                        return Mono.error(failWithNotFound("Items not found for IDs: " + missing));
                    }
                    return Mono.just(map);
                });
    }

    /**
     * Maps row IDs (from API response's 'id' field) to stable dataset_item_ids.
     * The frontend sends row IDs but the versioned deletion logic needs dataset_item_ids.
     */
    private Mono<Set<UUID>> mapRowIdsToDatasetItemIds(Set<UUID> rowIds) {
        if (rowIds == null || rowIds.isEmpty()) {
            return Mono.just(Set.of());
        }

        return versionDao.mapRowIdsToDatasetItemIds(rowIds)
                .collectList()
                .map(mappings -> {
                    if (mappings.isEmpty()) {
                        // IDs might already be dataset_item_ids (from SDK or older clients)
                        log.debug("No row ID mappings found, assuming IDs are already dataset_item_ids");
                        return rowIds;
                    }

                    Set<UUID> datasetItemIds = mappings.stream()
                            .map(DatasetItemVersionDAO.DatasetItemIdMapping::datasetItemId)
                            .collect(Collectors.toSet());

                    log.debug("Mapped '{}' row IDs to '{}' dataset_item_ids", rowIds.size(), datasetItemIds.size());
                    return datasetItemIds;
                });
    }

    @Override
    @WithSpan
    public Mono<DatasetVersion> save(@NonNull DatasetItemBatch batch) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (!featureFlags.isDatasetVersioningEnabled()) {
                // Legacy: save to legacy table
                log.info("Saving items to legacy table for dataset '{}'", batch.datasetId());
                return verifyDatasetExistsAndSave(batch).then(Mono.empty());
            }

            UUID datasetId = resolveDatasetId(batch, workspaceId, userName);
            UUID batchGroupId = batch.batchGroupId();

            if (batchGroupId == null) {
                // No batch_group_id: mutate the latest version (backwards compatibility)
                log.info("Mutating latest version for dataset '{}' (no batch_group_id)", datasetId);
                return mutateLatestVersionWithInsert(batch, datasetId, workspaceId, userName);
            }

            // batch_group_id provided: create new version with batch grouping
            log.info("Creating version with batch grouping for dataset '{}', batch_group_id: '{}'", datasetId,
                    batchGroupId);
            return handleGroupedInsertion(batchGroupId, batch, datasetId, workspaceId, userName);
        });
    }

    /**
     * Mutates the latest version by inserting/updating items.
     * Used when batchGroupId is null (backwards compatibility).
     */
    private Mono<DatasetVersion> mutateLatestVersionWithInsert(DatasetItemBatch batch, UUID datasetId,
            String workspaceId, String userName) {
        log.info("Mutating latest version for dataset '{}' with '{}' items", datasetId, batch.items().size());

        // Get the latest version
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

        if (latestVersion.isEmpty()) {
            // No versions exist - create the first version
            log.info("No versions exist for dataset '{}', creating first version", datasetId);
            return saveItemsWithVersion(batch, datasetId, null)
                    .contextWrite(c -> c.put(RequestContext.WORKSPACE_ID, workspaceId)
                            .put(RequestContext.USER_NAME, userName));
        }

        // Version exists - insert items directly into it
        UUID latestVersionId = latestVersion.get().id();
        log.info("Inserting '{}' items into existing version '{}'", batch.items().size(), latestVersionId);

        return insertItemsIntoVersion(batch, datasetId, latestVersionId, workspaceId, userName);
    }

    /**
     * Shared method to insert items into an existing version.
     * Handles validation, classification of new vs updated items, and count updates.
     * Used by mutateLatestVersionWithInsert and handleGroupedInsertion.
     *
     * @param batch the batch of items to insert
     * @param datasetId the dataset ID
     * @param versionId the version ID to insert into
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @return Mono emitting the updated dataset version
     */
    private Mono<DatasetVersion> insertItemsIntoVersion(DatasetItemBatch batch, UUID datasetId, UUID versionId,
            String workspaceId, String userName) {
        // Validate and prepare items
        List<DatasetItem> validatedItems = addIdIfAbsent(batch);

        // Ensure all items have datasetItemId set (use id field if datasetItemId is null)
        List<DatasetItem> normalizedItems = validatedItems.stream()
                .map(item -> {
                    if (item.datasetItemId() == null) {
                        UUID stableId = item.id() != null ? item.id() : idGenerator.generateId();
                        return item.toBuilder()
                                .datasetItemId(stableId)
                                .build();
                    }
                    return item;
                })
                .toList();

        return Mono.deferContextual(ctx -> {
            // Validate spans and traces
            return validateSpans(workspaceId, normalizedItems)
                    .then(validateTraces(workspaceId, normalizedItems))
                    .then(Mono.defer(() -> {
                        // Get existing item IDs to determine which are new vs updates
                        return versionDao.getItemIdsAndHashes(datasetId, versionId)
                                .collectList()
                                .flatMap(existingItems -> {
                                    Set<UUID> existingItemIds = existingItems.stream()
                                            .map(DatasetItemIdAndHash::itemId)
                                            .collect(Collectors.toSet());

                                    // Classify items as new or updates
                                    int newItemsCount = 0;
                                    int updatedItemsCount = 0;

                                    for (DatasetItem item : normalizedItems) {
                                        UUID stableId = item.datasetItemId();
                                        if (existingItemIds.contains(stableId)) {
                                            updatedItemsCount++;
                                        } else {
                                            newItemsCount++;
                                        }
                                    }

                                    int finalNewItemsCount = newItemsCount;
                                    int finalUpdatedItemsCount = updatedItemsCount;

                                    log.info("Inserting into version '{}': new='{}', updated='{}'",
                                            versionId, finalNewItemsCount, finalUpdatedItemsCount);

                                    // Insert items directly into the existing version
                                    return versionDao
                                            .insertItems(datasetId, versionId, normalizedItems, workspaceId, userName)
                                            .then(Mono.fromCallable(() -> {
                                                updateVersionCountsForInsert(versionId, workspaceId, finalNewItemsCount,
                                                        finalUpdatedItemsCount, userName);
                                                return versionService.getVersionById(workspaceId, datasetId, versionId);
                                            }).subscribeOn(Schedulers.boundedElastic()));
                                });
                    }));
        }).contextWrite(c -> c.put(RequestContext.WORKSPACE_ID, workspaceId)
                .put(RequestContext.USER_NAME, userName));
    }

    /**
     * Updates version counts after inserting items into an existing version.
     * Extracted to reduce complexity and improve testability.
     *
     * @param versionId The version ID to update
     * @param workspaceId The workspace ID
     * @param newItemsCount Number of new items inserted
     * @param updatedItemsCount Number of items updated
     * @param userName The user performing the update
     */
    private void updateVersionCountsForInsert(UUID versionId, String workspaceId, int newItemsCount,
            int updatedItemsCount, String userName) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            var currentVersion = dao.findById(versionId, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            "Version not found: '%s'".formatted(versionId)));

            // Only increment total by new items (not updates)
            int newTotal = currentVersion.itemsTotal() + newItemsCount;
            int newAdded = currentVersion.itemsAdded() + newItemsCount;
            int newModified = currentVersion.itemsModified() + updatedItemsCount;

            dao.updateCounts(versionId, newTotal, newAdded, newModified,
                    currentVersion.itemsDeleted(), workspaceId, userName);
            return null;
        });
    }

    /**
     * Updates version counts after deleting items from an existing version.
     * Extracted to reduce complexity and improve testability.
     *
     * @param versionId The version ID to update
     * @param workspaceId The workspace ID
     * @param currentVersion The current version before deletion
     * @param deletedCount Number of items deleted
     * @param userName The user performing the update
     */
    private void updateVersionCountsForDelete(UUID versionId, String workspaceId, DatasetVersion currentVersion,
            int deletedCount, String userName) {
        int newTotal = currentVersion.itemsTotal() - deletedCount;
        int newDeleted = currentVersion.itemsDeleted() + deletedCount;

        log.info("deleteItemsFromExistingVersion: updating counts - newTotal='{}', newDeleted='{}'",
                newTotal, newDeleted);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            dao.updateCounts(versionId, newTotal, currentVersion.itemsAdded(),
                    currentVersion.itemsModified(), newDeleted, workspaceId, userName);
            return null;
        });
    }

    /**
     * Creates a new version with batch_group_id by reusing existing version creation logic.
     * This delegates to either createFirstVersion or createVersionWithDelta, then associates
     * the batch_group_id with the created version.
     */

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

    private Mono<DatasetVersion> saveItemsWithVersion(DatasetItemBatch batch, UUID datasetId, UUID batchGroupId) {
        if (batch.items() == null || batch.items().isEmpty()) {
            log.debug("Empty batch, skipping version creation for dataset '{}'", datasetId);
            return Mono.empty();
        }

        // Validate UUID versions and add IDs if absent
        List<DatasetItem> validatedItems = addIdIfAbsent(batch);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            log.info("Saving items with version for dataset '{}', itemCount '{}', batchGroupId '{}'",
                    datasetId, batch.items().size(), batchGroupId);

            // Validate span and trace workspaces before proceeding
            return validateSpans(workspaceId, validatedItems)
                    .then(Mono.defer(() -> validateTraces(workspaceId, validatedItems)))
                    .then(Mono.defer(() -> {
                        // Verify dataset exists
                        datasetService.findById(datasetId, workspaceId, null);

                        // Ensure dataset is migrated if lazy migration is enabled
                        return ensureLazyMigration(datasetId, workspaceId);
                    }))
                    .then(Mono.defer(() -> {

                        // Get the latest version (if exists) - using overload that takes workspaceId
                        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId,
                                workspaceId);

                        if (latestVersion.isEmpty()) {
                            // No versions exist yet - create the first version with all items as "added"
                            return createFirstVersion(datasetId, validatedItems, batchGroupId, workspaceId,
                                    userName);
                        }

                        // Versions exist - apply delta on top of the latest
                        UUID baseVersionId = latestVersion.get().id();
                        return createVersionWithDelta(datasetId, baseVersionId, validatedItems, batchGroupId,
                                workspaceId, userName);
                    }));
        });
    }

    private Mono<DatasetVersion> createFirstVersion(UUID datasetId, List<DatasetItem> items,
            UUID batchGroupId, String workspaceId, String userName) {
        log.info("Creating first version for dataset '{}' with '{}' items", datasetId, items.size());

        UUID newVersionId = idGenerator.generateId();

        // All items are "added" for the first version
        // Set datasetItemId as the stable ID for each item
        // Use datasetItemId if already set, otherwise use id, otherwise generate new
        List<DatasetItem> addedItems = items.stream()
                .map(item -> {
                    UUID stableId = item.id() != null ? item.id() : idGenerator.generateId();
                    return item.toBuilder()
                            .datasetItemId(stableId)
                            .datasetId(datasetId)
                            .build();
                })
                .toList();

        // Use applyDelta with no base version items (empty copy)
        // We need a special path since there's no base version
        return versionDao.insertItems(datasetId, newVersionId, addedItems, workspaceId, userName)
                .flatMap(itemsTotal -> {
                    log.info("Inserted '{}' items for first version of dataset '{}'", itemsTotal, datasetId);

                    // Determine change description based on whether this is a batch operation
                    String changeDescription = batchGroupId != null
                            ? "Auto-created from SDK batch operation"
                            : null;

                    // Create version metadata (first version - all items are "added")
                    return Mono.fromCallable(() -> versionService.createVersionFromDelta(
                            datasetId,
                            newVersionId,
                            itemsTotal.intValue(),
                            null, // No base version for first version
                            null, // No tags
                            changeDescription,
                            batchGroupId, // Include batch group ID if provided
                            workspaceId,
                            userName))
                            .retryWhen(RetryUtils.handleOnDeadLocks())
                            .doOnSuccess(
                                    version -> log.info("Created first version '{}' for dataset '{}' with hash '{}'",
                                            version.id(), datasetId, version.versionHash()));
                });
    }

    private Mono<DatasetVersion> createVersionWithDelta(UUID datasetId, UUID baseVersionId,
            List<DatasetItem> items, UUID batchGroupId, String workspaceId, String userName) {
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
                    List<DatasetItem> addedItems = new ArrayList<>();
                    List<DatasetItem> editedItems = new ArrayList<>();

                    for (DatasetItem item : items) {
                        // Try datasetItemId first, then fall back to id for backwards compatibility
                        UUID stableId = item.datasetItemId() != null ? item.datasetItemId() : item.id();
                        if (stableId != null && existingItemIds.contains(stableId)) {
                            // Existing item - treat as edit
                            editedItems.add(item.toBuilder()
                                    .datasetItemId(stableId)
                                    .datasetId(datasetId)
                                    .build());
                        } else {
                            // New item - treat as add
                            UUID newItemId = stableId != null ? stableId : idGenerator.generateId();
                            addedItems.add(item.toBuilder()
                                    .datasetItemId(newItemId)
                                    .datasetId(datasetId)
                                    .build());
                        }
                    }

                    log.info("Classified items: added='{}', edited='{}' for dataset '{}'",
                            addedItems.size(), editedItems.size(), datasetId);

                    // Calculate unchanged items: items in base version that are NOT being edited
                    Set<UUID> editedItemIds = editedItems.stream()
                            .map(DatasetItem::datasetItemId)
                            .collect(Collectors.toSet());
                    int unchangedItemCount = (int) existingItems.stream()
                            .filter(item -> !editedItemIds.contains(item.itemId()))
                            .count();

                    // Generate UUIDs for unchanged, added, and edited items
                    List<UUID> unchangedUuids = generateUnchangedUuidsReversed(unchangedItemCount);
                    List<UUID> addedUuids = generateUuidPool(idGenerator, addedItems.size());

                    // Assign row IDs to added items
                    List<DatasetItem> addedItemsWithIds = withAssignedRowIds(addedItems, addedUuids);

                    // Apply delta changes - no deletions in PUT flow
                    return versionDao.applyDelta(datasetId, baseVersionId, newVersionId,
                            addedItemsWithIds, editedItems, Set.of(), unchangedUuids,
                            Set.of())
                            .flatMap(itemsTotal -> {
                                log.info("Applied delta to dataset '{}': itemsTotal '{}'", datasetId, itemsTotal);

                                // Determine change description based on whether this is a batch operation
                                String changeDescription = batchGroupId != null
                                        ? "Auto-created from SDK batch operation"
                                        : null;

                                // Create version metadata
                                return Mono.fromCallable(() -> versionService.createVersionFromDelta(
                                        datasetId,
                                        newVersionId,
                                        itemsTotal.intValue(),
                                        baseVersionId,
                                        null, // No tags
                                        changeDescription,
                                        batchGroupId, // Include batch group ID if provided
                                        workspaceId,
                                        userName)).retryWhen(RetryUtils.handleOnDeadLocks())
                                        .doOnSuccess(version -> log.info(
                                                "Created version '{}' for dataset '{}' with hash '{}'",
                                                version.id(), datasetId, version.versionHash()));
                            });
                });
    }

    /**
     * Generate UUIDs for unchanged items, reversed to maintain their original order.
     * This is necessary because ClickHouse sorts by id DESC, and UUIDv7 is time-ordered.
     */
    private List<UUID> generateUnchangedUuidsReversed(int count) {
        List<UUID> uuids = generateUuidPool(idGenerator, count);
        List<UUID> reversed = new ArrayList<>(uuids);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Assigns row IDs to a list of dataset items.
     * Creates new DatasetItem instances with the specified UUIDs as their row IDs.
     *
     * @param items the items to assign row IDs to
     * @param uuids the UUIDs to use as row IDs (must have same size as items)
     * @return new list with items containing assigned row IDs
     */
    private List<DatasetItem> withAssignedRowIds(List<DatasetItem> items, List<UUID> uuids) {
        return IntStream.range(0, items.size())
                .mapToObj(i -> items.get(i).toBuilder()
                        .id(uuids.get(i))
                        .build())
                .toList();
    }

    /**
     * Deletes items from an existing version (subsequent batches with same batch_group_id).
     * Similar to appendItemsToVersion for inserts, but removes items instead.
     *
     * Note: Only supports deletion by explicit item IDs. Filter-based deletions cannot be batched
     * because the client doesn't know which items will be deleted.
     */
    private Mono<Void> deleteItemsFromExistingVersion(Set<UUID> ids, UUID datasetId,
            List<DatasetItemFilter> filters, UUID versionId,
            String workspaceId, String userName) {

        log.info("Deleting items from existing version '{}' for dataset '{}'", versionId, datasetId);

        // Only explicit IDs are supported for batched deletions
        if (CollectionUtils.isEmpty(ids)) {
            log.warn("Batched deletion requires explicit item IDs. Filters are not supported for batched deletions.");
            return Mono.empty();
        }

        return Mono.defer(() -> {
            // Get current version to update counts
            DatasetVersion currentVersion = versionService.getVersionById(workspaceId, datasetId, versionId);

            log.info(
                    "deleteItemsFromExistingVersion: currentVersion itemsTotal='{}', itemsDeleted='{}', versionId='{}'",
                    currentVersion.itemsTotal(), currentVersion.itemsDeleted(), versionId);

            log.info("deleteItemsFromExistingVersion: attempting to remove '{}' items", ids.size());

            // Remove items from the version
            return versionDao.removeItemsFromVersion(datasetId, versionId, ids, workspaceId)
                    .flatMap(deletedCount -> {
                        log.info("deleteItemsFromExistingVersion: removeItemsFromVersion returned deletedCount='{}'",
                                deletedCount);

                        if (deletedCount == 0) {
                            log.info("No items deleted from version '{}'", versionId);
                            return Mono.<Void>empty();
                        }

                        // Update version counts in MySQL
                        return Mono.fromCallable(() -> {
                            updateVersionCountsForDelete(versionId, workspaceId, currentVersion,
                                    deletedCount.intValue(), userName);
                            log.info("Deleted '{}' items from version '{}', new total '{}'",
                                    deletedCount, versionId, currentVersion.itemsTotal() - deletedCount.intValue());
                            return null;
                        }).subscribeOn(Schedulers.boundedElastic());
                    })
                    .then();
        });
    }

    /**
     * Deletes items from an existing version using filters.
     * This is used for filter-based deletions when createVersion=false.
     * Null or empty filter list means "delete all" (no filters = match everything).
     */
    private Mono<Void> deleteItemsFromExistingVersionByFilters(UUID datasetId, UUID versionId,
            List<DatasetItemFilter> filters, String workspaceId, String userName) {

        log.info(
                "Deleting items from existing version '{}' for dataset '{}' using filters (null or empty = delete all)",
                versionId, datasetId);

        return Mono.defer(() -> {
            // Get current version to update counts
            DatasetVersion currentVersion = versionService.getVersionById(workspaceId, datasetId, versionId);

            log.info(
                    "deleteItemsFromExistingVersionByFilters: currentVersion itemsTotal='{}', itemsDeleted='{}', versionId='{}'",
                    currentVersion.itemsTotal(), currentVersion.itemsDeleted(), versionId);

            // Remove items matching filters from the version
            return versionDao.removeItemsFromVersionByFilters(datasetId, versionId, filters, workspaceId)
                    .flatMap(deletedCount -> {
                        log.info(
                                "deleteItemsFromExistingVersionByFilters: removeItemsFromVersionByFilters returned deletedCount='{}'",
                                deletedCount);

                        if (deletedCount == 0) {
                            log.info("No items deleted from version '{}'", versionId);
                            return Mono.<Void>empty();
                        }

                        // Update version counts in MySQL
                        return Mono.fromCallable(() -> {
                            updateVersionCountsForDelete(versionId, workspaceId, currentVersion,
                                    deletedCount.intValue(), userName);
                            log.info("Deleted '{}' items from version '{}', new total '{}'",
                                    deletedCount, versionId, currentVersion.itemsTotal() - deletedCount.intValue());
                            return null;
                        }).subscribeOn(Schedulers.boundedElastic());
                    })
                    .then();
        });
    }

    /**
     * Creates a new version with deletions for a new batch_group_id.
     * This is the first batch of deletions for this batch_group_id.
     */
    /**
     * Creates a new version with batch_group_id for delete operations by reusing existing deletion logic.
     * This delegates to the existing createVersionWithDeletion method, then associates
     * the batch_group_id with the created version.
     */

    /**
     * Resolves the datasetId for a delete operation.
     * If datasetId is provided, uses it directly.
     * If only itemIds are provided, resolves datasetId by looking up the row IDs.
     *
     * @param datasetId the dataset ID (may be null)
     * @param ids the item IDs to delete (row IDs from client, may be null)
     * @return Mono emitting the resolved datasetId
     */
    private Mono<UUID> getDatasetIdOrResolveItemDatasetId(UUID datasetId, Set<UUID> ids) {
        if (datasetId != null) {
            return Mono.just(datasetId);
        } else if (CollectionUtils.isNotEmpty(ids)) {
            // Map row IDs to get dataset_id directly from the mapping
            return versionDao.mapRowIdsToDatasetItemIds(ids)
                    .map(DatasetItemVersionDAO.DatasetItemIdMapping::datasetId)
                    .next(); // Get the first mapping's dataset_id (all should be from same dataset)
        } else {
            return Mono.error(new BadRequestException("Must provide either datasetId or itemIds"));
        }
    }

    /**
     * Handles grouped deletion operations using batch_group_id.
     * If a version exists for the batch_group_id, appends deletions to it.
     * Otherwise, creates a new version with the deletions.
     *
     * Maps incoming row IDs to stable dataset_item_ids before processing.
     *
     * @param batchGroupId the batch group ID
     * @param ids the item IDs to delete (may be UI row IDs)
     * @param datasetId the resolved dataset ID
     * @param filters optional filters
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @return Mono completing when deletion is done
     */
    private Mono<Void> handleGroupedDeletion(UUID batchGroupId, Set<UUID> ids, UUID datasetId,
            List<DatasetItemFilter> filters, String workspaceId, String userName, boolean createVersion) {

        // For filter-based deletions, ids is null - skip mapping and proceed directly
        if (ids == null) {
            return proceedWithGroupedDeletion(batchGroupId, Set.of(), datasetId, filters, workspaceId, userName,
                    createVersion);
        }

        // First, map row IDs to dataset_item_ids
        return versionDao.mapRowIdsToDatasetItemIds(ids)
                .collectList()
                .flatMap(mappings -> {
                    // Determine the stable dataset_item_ids to use
                    Set<UUID> datasetItemIds;
                    UUID resolvedDatasetId = datasetId;

                    if (mappings.isEmpty()) {
                        // No mappings found - IDs are already stable dataset_item_ids (SDK or direct usage)
                        log.info("No row ID mappings found for batch_group_id '{}', treating as dataset_item_ids",
                                batchGroupId);
                        datasetItemIds = ids;

                        // If datasetId is null, resolve it from any existing item (not just first)
                        if (resolvedDatasetId == null && !ids.isEmpty()) {
                            return versionDao.resolveDatasetIdFromItemIds(ids)
                                    .flatMap(resolvedId -> {
                                        log.info("Resolved dataset '{}' for batch_group_id '{}'", resolvedId,
                                                batchGroupId);
                                        return proceedWithGroupedDeletion(batchGroupId, datasetItemIds, resolvedId,
                                                filters, workspaceId, userName, createVersion);
                                    })
                                    .switchIfEmpty(Mono.defer(() -> {
                                        log.info(
                                                "None of the '{}' items found for batch_group_id '{}', treating as already deleted",
                                                ids.size(), batchGroupId);
                                        return Mono.empty();
                                    }));
                        }
                    } else {
                        // Successfully mapped row IDs to dataset_item_ids
                        datasetItemIds = mappings.stream()
                                .map(DatasetItemVersionDAO.DatasetItemIdMapping::datasetItemId)
                                .collect(Collectors.toSet());

                        // If datasetId is null, use the dataset from the first mapping
                        if (resolvedDatasetId == null) {
                            resolvedDatasetId = mappings.get(0).datasetId();
                        }

                        log.info("Mapped '{}' row IDs to '{}' dataset_item_ids for batch_group_id '{}', dataset '{}'",
                                ids.size(), datasetItemIds.size(), batchGroupId, resolvedDatasetId);
                    }

                    return proceedWithGroupedDeletion(batchGroupId, datasetItemIds, resolvedDatasetId,
                            filters, workspaceId, userName, createVersion);
                });
    }

    /**
     * Proceeds with grouped deletion after row IDs have been mapped to dataset_item_ids.
     */
    private Mono<Void> proceedWithGroupedDeletion(UUID batchGroupId, Set<UUID> datasetItemIds, UUID datasetId,
            List<DatasetItemFilter> filters, String workspaceId, String userName, boolean createVersion) {
        return Mono.fromCallable(() -> versionService.findByBatchGroupId(batchGroupId, datasetId, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optionalVersion -> {
                    if (optionalVersion.isPresent()) {
                        // Version exists - this is a subsequent batch of deletions
                        var existingVersion = optionalVersion.get();
                        log.info("Deleting '{}' items from existing version '{}' for batch_group_id '{}'",
                                datasetItemIds.size(), existingVersion.id(), batchGroupId);
                        return deleteItemsFromExistingVersion(datasetItemIds, datasetId, filters,
                                existingVersion.id(), workspaceId, userName);
                    } else {
                        // No version with this batch_group_id - create new version with deletions
                        log.info("Creating new version with batch_group_id '{}' for dataset '{}' with '{}' deletions",
                                batchGroupId, datasetId, datasetItemIds.size());
                        return deleteItemsWithVersion(datasetItemIds, datasetId, filters, workspaceId, userName,
                                batchGroupId);
                    }
                });
    }

    /**
     * Handles grouped insertion operations using batch_group_id.
     * If a version exists for the batch_group_id, appends items to it.
     * Otherwise, creates a new version with the items.
     *
     * @param batchGroupId the batch group ID
     * @param batch the batch of items to insert
     * @param datasetId the dataset ID
     * @param workspaceId the workspace ID
     * @param userName the user name
     * @return Mono emitting the dataset version
     */
    private Mono<DatasetVersion> handleGroupedInsertion(UUID batchGroupId, DatasetItemBatch batch,
            UUID datasetId, String workspaceId, String userName) {
        return Mono.fromCallable(() -> versionService.findByBatchGroupId(batchGroupId, datasetId, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optionalVersion -> {
                    if (optionalVersion.isPresent()) {
                        // Version exists - append items to it
                        var existingVersion = optionalVersion.get();
                        log.info("Appending '{}' items to existing version '{}' for batch_group_id '{}'",
                                batch.items().size(), existingVersion.id(), batchGroupId);
                        return insertItemsIntoVersion(batch, datasetId, existingVersion.id(), workspaceId, userName);
                    } else {
                        // No version with this batch_group_id - create new one
                        log.info("Creating new version with batch_group_id '{}' for dataset '{}'",
                                batchGroupId, datasetId);
                        return saveItemsWithVersion(batch, datasetId, batchGroupId)
                                .contextWrite(ctx -> ctx
                                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                                        .put(RequestContext.USER_NAME, userName));
                    }
                });
    }

    /**
     * Ensures a dataset is migrated to the versioning system if lazy migration is enabled.
     * <p>
     * This method checks if lazy migration is enabled in the configuration. If so, it calls
     * the migration service to ensure the dataset has been migrated before proceeding
     * with the CRUD operation.
     *
     * @param datasetId   the dataset ID to ensure is migrated
     * @param workspaceId the workspace ID
     * @return a Mono that completes when the dataset is ensured to be migrated (or immediately if lazy migration is disabled)
     */
    private Mono<Void> ensureLazyMigration(UUID datasetId, String workspaceId) {
        if (!config.getDatasetVersioningMigration().isLazyEnabled()) {
            return Mono.empty();
        }

        log.debug("Lazy migration is enabled, ensuring dataset '{}' is migrated", datasetId);
        return migrationService.ensureDatasetMigrated(datasetId, workspaceId);
    }

}
