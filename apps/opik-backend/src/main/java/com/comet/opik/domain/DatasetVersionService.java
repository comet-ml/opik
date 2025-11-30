package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionDiff;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetVersionServiceImpl.class)
public interface DatasetVersionService {

    String LATEST_TAG = "latest";

    // Error message templates
    String ERROR_VERSION_HASH_EXISTS = "Version hash collision detected for dataset '%s'";
    String ERROR_TAG_EXISTS = "Tag already exists for this dataset, tag='%s'";
    String ERROR_CANNOT_DELETE_LATEST_TAG = "Cannot delete '%s' tag - it is automatically managed";
    String ERROR_VERSION_HASH_NOT_FOUND = "Version with hash not found hash='%s' datasetId='%s'";
    String ERROR_VERSION_NOT_FOUND = "Version not found for dataset hash='%s' datasetId='%s'";

    /**
     * Commits a new version for the specified dataset with metadata and optional tag.
     * <p>
     * This operation:
     * <ul>
     *   <li>Generates a UUID-based hash for the version (last 8 chars of UUID)</li>
     *   <li>Creates immutable snapshot of current dataset items in ClickHouse</li>
     *   <li>Calculates diff statistics compared to previous version</li>
     *   <li>Stores version metadata including change statistics</li>
     *   <li>Automatically assigns the 'latest' tag to the new version</li>
     *   <li>Removes the 'latest' tag from the previous version if exists</li>
     *   <li>Optionally adds a custom tag if provided in the request</li>
     * </ul>
     *
     * @param datasetId the unique identifier of the dataset to version
     * @param request version creation details including optional tag, change description, and metadata
     * @return the created dataset version with generated hash, statistics, and assigned tags
     * @throws ConflictException if the custom tag already exists for this dataset
     */
    DatasetVersion commitVersion(UUID datasetId, DatasetVersionCreate request);

    /**
     * Retrieves a paginated list of versions for the specified dataset, ordered by creation time (newest first).
     *
     * @param datasetId the unique identifier of the dataset
     * @param page the page number (1-indexed, must be >= 1)
     * @param size the number of versions per page (must be >= 1)
     * @return a page containing dataset versions with their associated tags and metadata
     * @throws IllegalArgumentException if page or size are less than 1
     */
    DatasetVersionPage getVersions(UUID datasetId, int page, int size);

    /**
     * Retrieves a specific dataset version by its content hash.
     *
     * @param datasetId the unique identifier of the dataset
     * @param versionHash the SHA-256 hash of the version content
     * @return an Optional containing the version if found, empty otherwise
     */
    Optional<DatasetVersion> getVersionByHash(UUID datasetId, String versionHash);

    /**
     * Retrieves a dataset version by its tag name.
     *
     * @param datasetId the unique identifier of the dataset
     * @param tag the tag name (e.g., "baseline", "v1.0", "latest")
     * @return an Optional containing the version if found, empty otherwise
     */
    Optional<DatasetVersion> getVersionByTag(UUID datasetId, String tag);

    /**
     * Retrieves the most recently created version for the specified dataset.
     * This is equivalent to getting the version tagged with 'latest'.
     *
     * @param datasetId the unique identifier of the dataset
     * @return an Optional containing the latest version if any versions exist, empty otherwise
     */
    Optional<DatasetVersion> getLatestVersion(UUID datasetId);

    /**
     * Retrieves the most recently created version for the specified dataset in a given workspace.
     * This is used internally for reactive flows where RequestContext is not available.
     *
     * @param datasetId the unique identifier of the dataset
     * @param workspaceId the workspace identifier
     * @return an Optional containing the latest version if any versions exist, empty otherwise
     */
    Optional<DatasetVersion> findLatestVersion(UUID datasetId, String workspaceId);

    /**
     * Adds a tag to an existing dataset version for easy reference.
     *
     * @param datasetId the unique identifier of the dataset
     * @param versionHash the hash of the version to tag
     * @param tag the tag to create (e.g., "baseline", "production")
     * @throws NotFoundException if the version with the specified hash is not found
     * @throws ConflictException if the tag already exists for this dataset
     */
    void createTag(UUID datasetId, String versionHash, DatasetVersionTag tag);

    /**
     * Deletes a tag from a dataset version.
     * <p>
     * Note: The 'latest' tag cannot be deleted as it is automatically managed by the system.
     * Attempting to delete it will result in a BadRequestException.
     *
     * @param datasetId the unique identifier of the dataset
     * @param tag the tag name to delete
     * @throws NotFoundException if the tag does not exist
     * @throws ClientErrorException if attempting to delete the 'latest' tag
     */
    void deleteTag(UUID datasetId, String tag);

    /**
     * Resolves a version identifier (hash or tag) to a version ID.
     * <p>
     * This method tries to find a version by hash first, then by tag if not found by hash.
     *
     * @param datasetId the unique identifier of the dataset
     * @param hashOrTag either a version hash or a tag name
     * @return the UUID of the matching version
     * @throws NotFoundException if no version is found with the given hash or tag
     */
    UUID resolveVersionId(UUID datasetId, String hashOrTag);

    DatasetVersionDiff compareVersions(UUID datasetId, String fromHashOrTag, String toHashOrTag);

    /**
     * Restores a dataset to a previous version state.
     * <p>
     * This operation:
     * <ul>
     *   <li>Replaces all draft items with items from the specified version</li>
     *   <li>If the version is not the latest, creates a new version snapshot</li>
     *   <li>If the version is the latest, only replaces draft items (revert functionality)</li>
     * </ul>
     *
     * @param datasetId the unique identifier of the dataset
     * @param versionRef version hash or tag to restore from
     * @return the restored version (existing if latest, new if not latest)
     * @throws NotFoundException if the version is not found
     */
    DatasetVersion restoreVersion(UUID datasetId, String versionRef);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetVersionServiceImpl implements DatasetVersionService {

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull DatasetItemDAO datasetItemDAO;
    private final @NonNull DatasetItemVersionDAO datasetItemVersionDAO;

    @Override
    public DatasetVersion commitVersion(@NonNull UUID datasetId, @NonNull DatasetVersionCreate request) {
        log.info("Committing version for dataset: '{}'", datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Generate version ID and hash (UUID-based, like prompt versions)
        UUID versionId = idGenerator.generateId();
        String versionHash = CommitUtils.getCommit(versionId);
        log.info("Generated version hash '{}' for dataset '{}'", versionHash, datasetId);

        // Count items first to determine how many UUIDs we need to generate
        Long itemCount = datasetItemDAO.getDraftItemIdsAndHashes(datasetId)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .count()
                .block();
        log.info("Dataset '{}' has '{}' items to snapshot", datasetId, itemCount);

        // Generate UUIDs in Java (double the count for safety)
        int uuidCount = itemCount.intValue() * 2;
        List<UUID> uuids = IntStream.range(0, uuidCount)
                .mapToObj(i -> idGenerator.generateId())
                .toList();
        log.info("Generated '{}' UUIDs for dataset '{}' snapshot", uuidCount, datasetId);

        // Create snapshot in ClickHouse using pre-generated UUIDs
        Long snapshotCount = datasetItemVersionDAO.makeSnapshot(datasetId, versionId, uuids)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();
        log.info("Saved version snapshot with '{}' items for version '{}'", snapshotCount, versionId);

        return template.inTransaction(WRITE, handle -> {
            var datasetVersionDAO = handle.attach(DatasetVersionDAO.class);

            // Get previous version for diff calculation
            var previousVersion = datasetVersionDAO.findByTag(datasetId, LATEST_TAG, workspaceId);

            // Calculate diff statistics by comparing IDs and hashes AFTER snapshot is saved
            // This only loads IDs and hashes, not full item data
            DatasetVersionDiffStats diffStats = previousVersion
                    .map(datasetVersion -> calculateDiffStatistics(datasetId, datasetVersion.id(), versionId))
                    .orElseGet(() -> new DatasetVersionDiffStats(itemCount.intValue(), 0, 0, itemCount.intValue()));

            log.info("Diff statistics for dataset '{}': added='{}', modified='{}', deleted='{}', unchanged='{}'",
                    datasetId, diffStats.itemsAdded(), diffStats.itemsModified(),
                    diffStats.itemsDeleted(), diffStats.itemsUnchanged());

            // Create a new version with calculated diff statistics
            var version = DatasetVersionMapper.INSTANCE.toDatasetVersion(
                    versionId, datasetId, versionHash,
                    itemCount.intValue(),
                    diffStats.itemsAdded(),
                    diffStats.itemsModified(),
                    diffStats.itemsDeleted(),
                    request, userName);

            EntityConstraintHandler.handle(() -> {
                datasetVersionDAO.insert(version, workspaceId);
                return version;
            }).withError(() -> new EntityAlreadyExistsException(
                    new ErrorMessage(List.of(ERROR_VERSION_HASH_EXISTS.formatted(datasetId)))));

            log.info("Created version with hash '{}' for dataset '{}'", versionHash, datasetId);

            // Remove 'latest' tag from previous version (if exists)
            datasetVersionDAO.deleteTag(datasetId, LATEST_TAG, workspaceId);

            // Always add 'latest' tag to the new version
            datasetVersionDAO.insertTag(datasetId, LATEST_TAG, versionId, userName, workspaceId);
            log.info("Added '{}' tag to version '{}' for dataset '{}'", LATEST_TAG, versionHash, datasetId);

            // Add custom tag if provided
            if (StringUtils.isNotBlank(request.tag())) {
                EntityConstraintHandler.handle(() -> {
                    datasetVersionDAO.insertTag(datasetId, request.tag(), versionId, userName, workspaceId);
                    return null;
                }).withError(() -> new EntityAlreadyExistsException(
                        new ErrorMessage(List.of(ERROR_TAG_EXISTS.formatted(request.tag())))));
            }

            return datasetVersionDAO.findById(versionId, workspaceId).orElseThrow();
        });
    }

    @Override
    public DatasetVersionPage getVersions(@NonNull UUID datasetId, int page, int size) {
        Preconditions.checkArgument(page >= 1, "Page must be greater than or equal to 1");
        Preconditions.checkArgument(size >= 1, "Size must be greater than or equal to 1");

        log.info("Getting versions for dataset: '{}', page: '{}', size: '{}'", datasetId, page, size);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            int offset = (page - 1) * size;
            var versions = dao.findByDatasetId(datasetId, workspaceId, size, offset);
            var total = dao.countByDatasetId(datasetId, workspaceId);

            return new DatasetVersionPage(versions, page, size, total);
        });
    }

    @Override
    public Optional<DatasetVersion> getVersionByHash(@NonNull UUID datasetId, @NonNull String versionHash) {
        log.info("Getting version by hash for dataset: '{}', hash: '{}'", datasetId, versionHash);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByHash(datasetId, versionHash, workspaceId);
        });
    }

    @Override
    public Optional<DatasetVersion> getVersionByTag(@NonNull UUID datasetId, @NonNull String tag) {
        log.info("Getting version by tag for dataset: '{}', tag: '{}'", datasetId, tag);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByTag(datasetId, tag, workspaceId);
        });
    }

    @Override
    public Optional<DatasetVersion> getLatestVersion(@NonNull UUID datasetId) {
        return getVersionByTag(datasetId, LATEST_TAG);
    }

    @Override
    public Optional<DatasetVersion> findLatestVersion(@NonNull UUID datasetId, @NonNull String workspaceId) {
        log.info("Getting latest version for dataset: '{}', workspace: '{}'", datasetId, workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByTag(datasetId, LATEST_TAG, workspaceId);
        });
    }

    @Override
    public void createTag(@NonNull UUID datasetId, @NonNull String versionHash,
            @NonNull DatasetVersionTag tagRequest) {
        log.info("Creating tag, tag='{}', version='{}', dataset='{}'", tagRequest.tag(), versionHash, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            // Find version by hash
            var version = dao.findByHash(datasetId, versionHash, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            ERROR_VERSION_HASH_NOT_FOUND.formatted(versionHash, datasetId)));

            // Insert tag
            EntityConstraintHandler.handle(() -> {
                dao.insertTag(datasetId, tagRequest.tag(), version.id(), userName, workspaceId);
                return null;
            }).withError(() -> new EntityAlreadyExistsException(
                    new ErrorMessage(List.of(ERROR_TAG_EXISTS.formatted(tagRequest.tag())))));

            return null;
        });

        log.info("Created tag, tag='{}', version='{}', dataset='{}'", tagRequest.tag(), versionHash, datasetId);
    }

    @Override
    public void deleteTag(@NonNull UUID datasetId, @NonNull String tag) {
        log.info("Deleting tag, tag='{}', dataset='{}'", tag, datasetId);

        // Prevent deletion of 'latest' tag - it's managed automatically
        if (LATEST_TAG.equals(tag)) {
            throw new ClientErrorException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(new ErrorMessage(
                                    List.of(ERROR_CANNOT_DELETE_LATEST_TAG.formatted(LATEST_TAG))))
                            .build());
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            dao.deleteTag(datasetId, tag, workspaceId);
            return null;
        });

        log.info("Deleted tag, tag='{}', dataset='{}'", tag, datasetId);
    }

    @Override
    public UUID resolveVersionId(@NonNull UUID datasetId, @NonNull String hashOrTag) {
        log.info("Resolving version ID, hashOrTag='{}', dataset='{}'", hashOrTag, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            // Try to find by hash first
            var versionByHash = dao.findByHash(datasetId, hashOrTag, workspaceId);
            if (versionByHash.isPresent()) {
                return versionByHash.get().id();
            }

            // Try to find by tag
            var versionByTag = dao.findByTag(datasetId, hashOrTag, workspaceId);
            if (versionByTag.isPresent()) {
                return versionByTag.get().id();
            }

            throw new NotFoundException(ERROR_VERSION_NOT_FOUND.formatted(hashOrTag, datasetId));
        });
    }

    @Override
    public DatasetVersionDiff compareVersions(@NonNull UUID datasetId, @NonNull String fromHashOrTag,
            String toHashOrTag) {

        log.info("Comparing versions: from='{}', to='{}', dataset='{}'", fromHashOrTag, toHashOrTag, datasetId);

        // Resolve 'from' and to 'to' version IDs
        UUID fromVersionId = resolveVersionId(datasetId, fromHashOrTag);
        UUID toVersionId = Optional.ofNullable(toHashOrTag)
                .map(hashOrTag -> resolveVersionId(datasetId, hashOrTag))
                .orElse(null);
        var stats = calculateDiffStatistics(datasetId, fromVersionId, toVersionId);

        String toVersionLabel = toHashOrTag != null ? toHashOrTag : "draft";

        log.info("Computed diff: from='{}', to='{}', added='{}', modified='{}', deleted='{}', unchanged='{}'",
                fromHashOrTag, toVersionLabel,
                stats.itemsAdded(), stats.itemsModified(),
                stats.itemsDeleted(), stats.itemsUnchanged());

        return DatasetVersionDiff.builder()
                .fromVersion(fromHashOrTag)
                .toVersion(toVersionLabel)
                .statistics(stats)
                .build();
    }

    private Mono<List<DatasetItemIdAndHash>> getItems(UUID datasetId, UUID versionId, String userName,
            String workspaceId) {
        if (versionId == null) {
            return datasetItemDAO.getDraftItemIdsAndHashes(datasetId)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.USER_NAME, userName)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .collectList();
        }

        return datasetItemVersionDAO.getItemIdsAndHashes(datasetId, versionId)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .collectList();
    }

    private DatasetVersionDiffStats calculateDiffStatistics(UUID datasetId, UUID fromVersionId, UUID toVersionId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var fromItems = datasetItemVersionDAO.getItemIdsAndHashes(datasetId, fromVersionId)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .collectList()
                .block();

        var toItems = getItems(datasetId, toVersionId, userName, workspaceId).block();

        return calculateDiffStatistics(fromItems, toItems);
    }

    /**
     * Calculate diff statistics between two lists of items (identified by ID and hash).
     * Compares items by itemId and detects additions, deletions, modifications, and unchanged items.
     */
    private static DatasetVersionDiffStats calculateDiffStatistics(List<DatasetItemIdAndHash> fromItems,
            List<DatasetItemIdAndHash> toItems) {

        log.debug("Calculating diff: fromItems count='{}', toItems count='{}'", fromItems.size(), toItems.size());

        // Build maps for efficient lookup by itemId
        var fromMap = fromItems.stream()
                .collect(Collectors.toMap(DatasetItemIdAndHash::itemId, item -> item));

        var toMap = toItems.stream()
                .collect(Collectors.toMap(DatasetItemIdAndHash::itemId, item -> item));

        var fromIds = fromMap.keySet();
        var toIds = toMap.keySet();

        // Calculate added items (in 'to' but not in 'from')
        var addedIds = CollectionUtils.subtract(toIds, fromIds);
        int added = addedIds.size();

        // Calculate deleted items (in 'from' but not in 'to')
        var deletedIds = CollectionUtils.subtract(fromIds, toIds);
        int deleted = deletedIds.size();

        // Calculate modified and unchanged items (items in both versions)
        var commonIds = CollectionUtils.intersection(fromIds, toIds);
        int modified = 0;
        int unchanged = 0;

        for (UUID itemId : commonIds) {
            var fromItem = fromMap.get(itemId);
            var toItem = toMap.get(itemId);

            if (fromItem.dataHash() != toItem.dataHash()) {
                modified++;
            } else {
                unchanged++;
            }
        }

        log.info("Diff calculated: added='{}', modified='{}', deleted='{}', unchanged='{}'",
                added, modified, deleted, unchanged);

        return new DatasetVersionDiffStats(added, modified, deleted, unchanged);
    }

    @Override
    public DatasetVersion restoreVersion(@NonNull UUID datasetId, @NonNull String versionRef) {
        log.info("Restoring dataset '{}' to version '{}'", datasetId, versionRef);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Resolve version reference to version ID and get version details
        UUID versionId = resolveVersionId(datasetId, versionRef);
        DatasetVersion versionToRestore = template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findById(versionId, workspaceId).orElseThrow(
                    () -> new NotFoundException(ERROR_VERSION_NOT_FOUND.formatted(versionRef, datasetId)));
        });

        // Check if this is the latest version
        Optional<DatasetVersion> latestVersion = getLatestVersion(datasetId);
        boolean isLatestVersion = latestVersion.isPresent()
                && latestVersion.get().id().equals(versionId);

        log.info("Restoring version '{}' for dataset '{}', isLatest='{}'",
                versionRef, datasetId, isLatestVersion);

        // Step 1: Delete all draft items
        Long deletedCount = datasetItemDAO.deleteAllDraftItems(datasetId)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        log.info("Deleted '{}' draft items for dataset '{}'", deletedCount, datasetId);

        // Step 2: Copy items from version to draft
        Long restoredCount = restoreVersionItemsStreaming(versionId, datasetId, workspaceId, userName);
        log.info("Restored '{}' items from version '{}' to draft for dataset '{}'",
                restoredCount, versionRef, datasetId);

        // Step 3: If not latest version, commit a new version with the restored items
        if (!isLatestVersion) {
            log.info("Creating new version snapshot after restore for dataset '{}'", datasetId);
            return commitVersion(datasetId, DatasetVersionCreate.builder()
                    .changeDescription("Restored from version: " + versionRef)
                    .build());
        } else {
            // If restoring to latest version, just return the existing version (revert scenario)
            log.info("Restored to latest version '{}' for dataset '{}' (revert scenario)", versionRef, datasetId);
            return versionToRestore;
        }
    }

    /**
     * Streams items from a version and restores them as draft items in batches.
     *
     * @param versionId the version ID to restore items from
     * @param datasetId the dataset ID to restore items to
     * @param workspaceId the workspace ID for context
     * @param userName the user name for context
     * @return the total number of items restored
     */
    private Long restoreVersionItemsStreaming(UUID versionId, UUID datasetId, String workspaceId, String userName) {
        log.info("Restoring version items using streaming for version: '{}', dataset: '{}'", versionId, datasetId);

        return processItemsInBatches(
                (batchSize, lastRetrievedId) -> fetchAndRestoreBatch(versionId, datasetId, workspaceId, userName,
                        batchSize, lastRetrievedId),
                workspaceId, userName);
    }

    /**
     * Fetches a batch of items from a version, restores them as draft items, and returns the batch state.
     *
     * @param versionId the version ID to restore items from
     * @param datasetId the dataset ID to restore items to
     * @param workspaceId the workspace ID for context
     * @param userName the user name for context
     * @param batchSize the number of items to fetch per batch
     * @param lastRetrievedId the ID of the last retrieved item for pagination (null for first batch)
     * @return Mono containing the batch state (items processed and last ID), or empty if no more items
     */
    private Mono<BatchState> fetchAndRestoreBatch(UUID versionId, UUID datasetId, String workspaceId, String userName,
            int batchSize, UUID lastRetrievedId) {

        return datasetItemVersionDAO.getVersionItems(datasetId, versionId, batchSize, lastRetrievedId)
                .collectList()
                .flatMap(items -> {
                    if (items.isEmpty()) {
                        return Mono.empty();
                    }

                    log.debug("Restoring batch of '{}' items from version: '{}'", items.size(), versionId);

                    // Convert versioned items to draft items (preserve original IDs from draftItemId)
                    List<com.comet.opik.api.DatasetItem> draftItems = items.stream()
                            .map(item -> item.toBuilder()
                                    .id(item.draftItemId()) // Preserve original draft item ID
                                    .draftItemId(null) // Not applicable for draft items
                                    .build())
                            .toList();

                    // Save as draft items
                    return datasetItemDAO.save(datasetId, draftItems)
                            .map(count -> {
                                UUID lastId = items.get(items.size() - 1).id();
                                return new BatchState(lastId, (long) items.size());
                            });
                })
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId));
    }

    /**
     * Generic method to process items in batches using reactive streaming.
     * This avoids code duplication between save and restore operations.
     *
     * @param batchProcessor function that processes a single batch given batch size and last retrieved ID
     * @param workspaceId the workspace ID for context
     * @param userName the user name for context
     * @return the total number of items processed
     */
    private Long processItemsInBatches(BatchProcessor batchProcessor, String workspaceId, String userName) {
        final int batchSize = 1000;

        return Flux.defer(() -> batchProcessor.process(batchSize, null))
                .expand(state -> {
                    if (state.lastRetrievedId() == null) {
                        return Mono.empty();
                    }
                    return batchProcessor.process(batchSize, state.lastRetrievedId());
                })
                .reduce(0L, (total, state) -> total + state.itemsProcessed())
                .doOnSuccess(total -> log.info("Finished processing all items. Total processed: '{}'", total))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();
    }

    /**
     * Functional interface for batch processing operations.
     */
    @FunctionalInterface
    private interface BatchProcessor {
        Mono<BatchState> process(int batchSize, UUID lastRetrievedId);
    }

    /**
     * Internal record to track batch processing state for reactive streaming.
     *
     * @param lastRetrievedId the ID of the last retrieved item for pagination (null if no more items)
     * @param itemsProcessed the number of items processed in this batch
     */
    private record BatchState(UUID lastRetrievedId, long itemsProcessed) {
    }
}
