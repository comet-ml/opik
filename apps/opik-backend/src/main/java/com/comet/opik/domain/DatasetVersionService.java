package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
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
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetVersionServiceImpl.class)
public interface DatasetVersionService {

    String LATEST_TAG = "latest";

    // Error message templates
    String ERROR_VERSION_HASH_EXISTS = "Version hash collision detected for dataset '%s' - please retry";
    String ERROR_TAG_EXISTS = "Tag '%s' already exists for this dataset";
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
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetVersionServiceImpl implements DatasetVersionService {

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull DatasetItemDAO datasetItemDAO;

    @Override
    public DatasetVersion commitVersion(@NonNull UUID datasetId, @NonNull DatasetVersionCreate request) {
        log.info("Committing version for dataset: '{}'", datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Generate version ID and hash (UUID-based, like prompt versions)
        UUID versionId = idGenerator.generateId();
        String versionHash = CommitUtils.getCommit(versionId);
        log.info("Generated version hash '{}' for dataset '{}'", versionHash, datasetId);

        // Fetch current dataset items from ClickHouse to create snapshot
        var fetchedItems = datasetItemDAO.getItems(datasetId, Integer.MAX_VALUE, null)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .collectList()
                .block();

        // Make effectively final for lambda
        final List<DatasetItem> currentItems;
        if (fetchedItems == null || fetchedItems.isEmpty()) {
            log.warn("No items found for dataset: '{}'", datasetId);
            currentItems = List.of();
        } else {
            currentItems = fetchedItems;
        }

        log.info("Fetched '{}' items for dataset '{}' version '{}'",
                currentItems.size(), datasetId, versionHash);

        return template.inTransaction(WRITE, handle -> {
            var datasetVersionDAO = handle.attach(DatasetVersionDAO.class);

            // Get previous version for diff calculation
            var previousVersion = datasetVersionDAO.findByTag(datasetId, LATEST_TAG, workspaceId);

            // Calculate diff statistics
            DiffStatistics diffStats;
            if (previousVersion.isPresent()) {
                var previousItems = datasetItemDAO.getAllVersionedItems(datasetId, previousVersion.get().id())
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, userName)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .collectList()
                        .block();

                diffStats = calculateDiffStatistics(
                        previousItems != null ? previousItems : List.of(),
                        currentItems);
            } else {
                // First version - all items are new
                diffStats = new DiffStatistics(currentItems.size(), currentItems.size(), 0, 0);
            }

            log.info("Diff statistics for dataset '{}': added='{}', modified='{}', deleted='{}'",
                    datasetId, diffStats.itemsAdded, diffStats.itemsModified, diffStats.itemsDeleted);

            // Create new version (versionId and versionHash already generated above)
            var version = DatasetVersionMapper.INSTANCE.toDatasetVersion(
                    versionId, datasetId, versionHash,
                    diffStats.itemsCount, diffStats.itemsAdded, diffStats.itemsModified, diffStats.itemsDeleted,
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

            // Create immutable snapshot in ClickHouse dataset_item_versions table
            if (!currentItems.isEmpty()) {
                datasetItemDAO.saveVersionSnapshot(versionId, datasetId, currentItems)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, userName)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block();
                log.info("Saved version snapshot with '{}' items for version '{}'", currentItems.size(), versionId);
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

    /**
     * Calculate diff statistics between previous and current dataset versions.
     */
    private DiffStatistics calculateDiffStatistics(List<DatasetItem> previousItems, List<DatasetItem> currentItems) {
        // Build maps for efficient lookup
        // For versioned items, use draftItemId (which links to original draft item)
        // For draft items, use id
        var previousMap = previousItems.stream()
                .collect(Collectors.toMap(DatasetVersionServiceImpl::getIdForComparison, item -> item));

        var currentMap = currentItems.stream()
                .collect(Collectors.toMap(DatasetVersionServiceImpl::getIdForComparison, item -> item));

        // Calculate added items (in current but not in previous)
        var addedIds = new java.util.HashSet<>(currentMap.keySet());
        addedIds.removeAll(previousMap.keySet());

        // Calculate deleted items (in previous but not in current)
        var deletedIds = new java.util.HashSet<>(previousMap.keySet());
        deletedIds.removeAll(currentMap.keySet());

        // Calculate modified items (in both but with different data)
        var commonIds = new java.util.HashSet<>(currentMap.keySet());
        commonIds.retainAll(previousMap.keySet());

        int modifiedCount = 0;
        for (UUID id : commonIds) {
            var prevItem = previousMap.get(id);
            var currItem = currentMap.get(id);

            // Compare data maps
            if (!java.util.Objects.equals(prevItem.data(), currItem.data())) {
                modifiedCount++;
            }
        }

        return new DiffStatistics(
                currentItems.size(),
                addedIds.size(),
                modifiedCount,
                deletedIds.size());
    }

    private static UUID getIdForComparison(DatasetItem item) {
        return item.draftItemId() != null ? item.draftItemId() : item.id();
    }

    /**
     * Internal record to hold diff statistics.
     */
    private record DiffStatistics(
            int itemsCount,
            int itemsAdded,
            int itemsModified,
            int itemsDeleted) {
    }

    @Override
    public DatasetVersionDiff compareVersions(@NonNull UUID datasetId, @NonNull String fromHashOrTag,
            String toHashOrTag) {

        log.info("Comparing versions: from='{}', to='{}', dataset='{}'", fromHashOrTag, toHashOrTag, datasetId);

        // Resolve 'from' version identifier to version ID
        UUID fromVersionId = resolveVersionId(datasetId, fromHashOrTag);

        DatasetVersionDiffStats stats;
        String toVersionLabel;

        if (toHashOrTag == null) {
            // Compare version with current draft
            log.info("Comparing version='{}' with draft for dataset='{}'", fromHashOrTag, datasetId);
            stats = datasetItemDAO.computeDiffStatsWithDraft(datasetId, fromVersionId).block();
            toVersionLabel = "draft";
        } else {
            // Compare two versions
            UUID toVersionId = resolveVersionId(datasetId, toHashOrTag);
            stats = datasetItemDAO.computeDiffStats(datasetId, fromVersionId, toVersionId).block();
            toVersionLabel = toHashOrTag;
        }

        if (stats == null) {
            log.error("Failed to compute diff statistics for dataset='{}', from='{}', to='{}'",
                    datasetId, fromHashOrTag, toHashOrTag);
            throw new InternalServerErrorException("Failed to compute diff statistics");
        }

        log.info("Computed diff: from='{}', to='{}', added='{}', modified='{}', deleted='{}', unchanged='{}'",
                fromHashOrTag, toVersionLabel,
                stats.itemsAdded(), stats.itemsModified(),
                stats.itemsDeleted(), stats.itemsUnchanged());

        return DatasetVersionDiff.builder()
                .fromVersion(fromHashOrTag)
                .toVersion(toVersionLabel)
                .statistics(DatasetVersionDiffMapper.INSTANCE.toApiDiffStatistics(stats))
                .build();
    }
}
