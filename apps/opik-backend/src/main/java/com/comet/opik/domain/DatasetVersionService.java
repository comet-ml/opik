package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionDiff;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.DatasetVersionUpdate;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.ExecutionPolicy;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.DatabaseUtils.generateUuidPool;
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
     * Updates an existing dataset version's change_description and/or adds new tags.
     * <p>
     * This operation:
     * <ul>
     *   <li>Updates the change_description if provided</li>
     *   <li>Adds new tags to the version if provided</li>
     * </ul>
     *
     * @param datasetId the unique identifier of the dataset
     * @param versionHash the hash of the version to update
     * @param request the update request containing optional change_description and tags_to_add
     * @return the updated dataset version
     * @throws NotFoundException if the version with the specified hash is not found
     * @throws ConflictException if any of the tags already exist for this dataset
     */
    DatasetVersion updateVersion(UUID datasetId, String versionHash, DatasetVersionUpdate request);

    /**
     * Resolves a version identifier (hash or tag) to a version ID.
     * <p>
     * This method tries to find a version by hash first, then by tag if not found by hash.
     *
     * @param workspaceId the workspace ID for the request
     * @param datasetId the unique identifier of the dataset
     * @param hashOrTag either a version hash or a tag name
     * @return the UUID of the matching version
     * @throws NotFoundException if no version is found with the given hash or tag
     */
    UUID resolveVersionId(String workspaceId, UUID datasetId, String hashOrTag);

    DatasetVersionDiff compareVersions(UUID datasetId, String fromHashOrTag, String toHashOrTag);

    /**
     * Gets the latest version for a dataset.
     * Safe to call from reactive contexts where RequestContext is not available.
     *
     * @param datasetId the dataset ID
     * @param workspaceId the workspace ID
     * @return Optional containing the latest version, or empty if no versions exist
     */
    Optional<DatasetVersion> getLatestVersion(UUID datasetId, String workspaceId);

    /**
     * Gets a specific version by its ID.
     *
     * @param workspaceId the workspace ID
     * @param datasetId the dataset ID
     * @param versionId the version ID
     * @return the version
     * @throws NotFoundException if the version is not found
     */
    DatasetVersion getVersionById(String workspaceId, UUID datasetId, UUID versionId);

    /**
     * Gets a specific version by its version name (e.g., 'v1', 'v373').
     *
     * @param datasetId the dataset ID
     * @param versionName the version name (e.g., 'v1', 'v373')
     * @return the version
     * @throws NotFoundException if the version is not found
     */
    DatasetVersion getVersionByName(UUID datasetId, String versionName);

    /**
     * Gets multiple versions by their IDs.
     *
     * @param versionIds the collection of version IDs to retrieve
     * @param workspaceId the workspace ID
     * @return list of versions (may be empty if no versions found)
     */
    List<DatasetVersion> findByIds(Collection<UUID> versionIds, String workspaceId);

    /**
     * Checks if the given version ID is the latest version for the dataset.
     * Safe to call from reactive contexts where RequestContext is not available.
     *
     * @param workspaceId the workspace ID
     * @param datasetId the dataset ID
     * @param versionId the version ID to check
     * @return true if versionId is the latest version, false otherwise
     */
    boolean isLatestVersion(String workspaceId, UUID datasetId, UUID versionId);

    /**
     * Checks if the dataset has any versions.
     * Safe to call from reactive contexts where RequestContext is not available.
     */
    boolean hasVersions(String workspaceId, UUID datasetId);

    /**
     * Finds a version by its batch ID.
     * Used to support SDK batch operations where multiple API calls share the same batch_group_id.
     *
     * @param batchGroupId the batch group ID to search for
     * @param datasetId the dataset ID
     * @param workspaceId the workspace ID
     * @return Optional containing the version if found, empty otherwise
     */
    Optional<DatasetVersion> findByBatchGroupId(UUID batchGroupId, UUID datasetId, String workspaceId);

    /**
     * Creates a new version from the result of applying delta changes.
     * This is called after items have been written to the versions table.
     *
     * @param datasetId the dataset ID
     * @param newVersionId the ID for the new version
     * @param itemsTotal total number of items in the new version
     * @param baseVersionId the base version ID (for diff calculation)
     * @param tags optional tags for the new version
     * @param changeDescription optional description of the changes
     * @param evaluators optional default evaluators for the version
     * @param executionPolicy optional default execution policy for the version
     * @param batchGroupId optional batch group ID for SDK batch operations
     * @param workspaceId the workspace ID (required when called from reactive context)
     * @param userName the user name (required when called from reactive context)
     * @return the created version
     */
    DatasetVersion createVersionFromDelta(UUID datasetId, UUID newVersionId, int itemsTotal,
            UUID baseVersionId, List<String> tags, String changeDescription,
            List<EvaluatorItem> evaluators, ExecutionPolicy executionPolicy,
            boolean clearExecutionPolicy,
            UUID batchGroupId, String workspaceId, String userName);

    /**
     * Restores a dataset to a previous version state by creating a new version.
     * <p>
     * This operation copies items directly from the source version to a new version
     * within the versioned items table, bypassing the draft table entirely.
     * <ul>
     *   <li>If the version is the latest, returns it as-is (no-op)</li>
     *   <li>Otherwise, creates a new version with items copied from the source version</li>
     *   <li>Calculates diff statistics between the previous latest and the new version</li>
     * </ul>
     *
     * @param datasetId the unique identifier of the dataset
     * @param versionRef version hash or tag to restore from
     * @return Mono emitting the restored version (existing if latest, new if not latest)
     * @throws NotFoundException if the version is not found
     */
    Mono<DatasetVersion> restoreVersion(UUID datasetId, String versionRef);
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

    private Optional<DatasetVersion> getVersionByTag(@NonNull String workspaceId, @NonNull UUID datasetId,
            @NonNull String tag) {
        log.info("Getting version by tag for dataset: '{}', tag: '{}'", datasetId, tag);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByTag(datasetId, tag, workspaceId);
        });
    }

    @Override
    public Optional<DatasetVersion> getLatestVersion(@NonNull UUID datasetId, @NonNull String workspaceId) {
        return getVersionByTag(workspaceId, datasetId, LATEST_TAG);
    }

    @Override
    public Optional<DatasetVersion> findByBatchGroupId(@NonNull UUID batchGroupId, @NonNull UUID datasetId,
            @NonNull String workspaceId) {
        log.info("Finding version by batch_group_id '{}' for dataset '{}'", batchGroupId, datasetId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByBatchGroupId(batchGroupId, datasetId, workspaceId);
        });
    }

    @Override
    public DatasetVersion getVersionById(@NonNull String workspaceId, @NonNull UUID datasetId,
            @NonNull UUID versionId) {
        log.info("Getting version by ID '{}' for dataset '{}'", versionId, datasetId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findById(versionId, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            ERROR_VERSION_NOT_FOUND.formatted(versionId.toString(), datasetId)));
        });
    }

    @Override
    public DatasetVersion getVersionByName(@NonNull UUID datasetId, @NonNull String versionName) {
        log.info("Getting version by name '{}' for dataset '{}'", versionName, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByVersionName(datasetId, versionName, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            "Version '%s' not found for dataset '%s'".formatted(versionName, datasetId)));
        });
    }

    @Override
    public List<DatasetVersion> findByIds(@NonNull Collection<UUID> versionIds, @NonNull String workspaceId) {
        if (CollectionUtils.isEmpty(versionIds)) {
            return List.of();
        }

        log.info("Finding '{}' versions by IDs", versionIds.size());

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByIds(versionIds, workspaceId);
        });
    }

    @Override
    public boolean hasVersions(@NonNull String workspaceId, @NonNull UUID datasetId) {
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.countByDatasetId(datasetId, workspaceId) > 0;
        });
    }

    @Override
    public boolean isLatestVersion(@NonNull String workspaceId, @NonNull UUID datasetId, @NonNull UUID versionId) {
        return getLatestVersion(datasetId, workspaceId)
                .map(latest -> latest.id().equals(versionId))
                .orElse(false);
    }

    @Override
    public DatasetVersion createVersionFromDelta(@NonNull UUID datasetId, @NonNull UUID newVersionId,
            int itemsTotal, UUID baseVersionId, List<String> tags, String changeDescription,
            List<EvaluatorItem> evaluators, ExecutionPolicy executionPolicy,
            boolean clearExecutionPolicy,
            UUID batchGroupId, @NonNull String workspaceId, @NonNull String userName) {

        log.info(
                "Creating version from delta for dataset '{}', newVersionId '{}', itemsTotal '{}', baseVersionId '{}', batchGroupId '{}'",
                datasetId, newVersionId, itemsTotal, baseVersionId, batchGroupId);

        String versionHash = CommitUtils.getCommit(newVersionId);

        return template.inTransaction(WRITE, handle -> {
            var datasetVersionDAO = handle.attach(DatasetVersionDAO.class);

            // Calculate diff statistics against the base version (if exists)
            DatasetVersionDiffStats diffStats;
            if (baseVersionId != null) {
                diffStats = calculateDiffStatistics(datasetId, baseVersionId, newVersionId,
                        workspaceId, userName);
            } else {
                // First version - all items are "added"
                diffStats = new DatasetVersionDiffStats(itemsTotal, 0, 0, 0);
            }

            log.info("Delta diff for dataset '{}': added='{}', modified='{}', deleted='{}', unchanged='{}'",
                    datasetId, diffStats.itemsAdded(), diffStats.itemsModified(),
                    diffStats.itemsDeleted(), diffStats.itemsUnchanged());

            // Create version record
            var version = DatasetVersionMapper.INSTANCE.toDatasetVersion(
                    newVersionId, datasetId, versionHash,
                    itemsTotal,
                    diffStats.itemsAdded(),
                    diffStats.itemsModified(),
                    diffStats.itemsDeleted(),
                    DatasetVersionCreate.builder()
                            .tags(tags)
                            .changeDescription(changeDescription)
                            .evaluators(evaluators)
                            .executionPolicy(executionPolicy)
                            .build(),
                    userName);

            EntityConstraintHandler.handle(() -> {
                if (baseVersionId != null) {
                    datasetVersionDAO.insertWithBaseVersion(version, baseVersionId, clearExecutionPolicy, workspaceId);
                } else {
                    datasetVersionDAO.insert(version, workspaceId);
                }
                return version;
            }).withError(() -> new EntityAlreadyExistsException(
                    new ErrorMessage(List.of(ERROR_VERSION_HASH_EXISTS.formatted(datasetId)))));

            log.info("Created version with hash '{}' for dataset '{}'", versionHash, datasetId);

            // Associate batch_group_id if provided
            if (batchGroupId != null) {
                datasetVersionDAO.updateBatchGroupId(newVersionId, batchGroupId, workspaceId, userName);
                log.info("Associated batch_group_id '{}' with version '{}' for dataset '{}'",
                        batchGroupId, versionHash, datasetId);
            }

            // Remove 'latest' tag from previous version (if exists)
            datasetVersionDAO.deleteTag(datasetId, LATEST_TAG, workspaceId);

            // Always add 'latest' tag to the new version
            datasetVersionDAO.insertTag(datasetId, LATEST_TAG, newVersionId, userName, workspaceId);
            log.info("Added '{}' tag to version '{}' for dataset '{}'", LATEST_TAG, versionHash, datasetId);

            // Add custom tags from the request
            insertTags(datasetVersionDAO, datasetId, newVersionId, tags, userName, workspaceId);

            return datasetVersionDAO.findById(newVersionId, workspaceId).orElseThrow();
        });
    }

    /**
     * Inserts multiple tags for a dataset version in a single batch operation.
     * Filters out blank tags and duplicates before insertion.
     *
     * @throws EntityAlreadyExistsException if any tag already exists for the dataset
     */
    private void insertTags(DatasetVersionDAO dao, UUID datasetId, UUID versionId,
            Collection<String> tags, String userName, String workspaceId) {
        if (CollectionUtils.isEmpty(tags)) {
            return;
        }

        List<String> validTags = tags.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();

        if (validTags.isEmpty()) {
            return;
        }

        EntityConstraintHandler.handle(() -> {
            dao.insertTags(datasetId, validTags, versionId, userName, workspaceId);
            return null;
        }).withError(() -> new EntityAlreadyExistsException(
                new ErrorMessage(List.of("One or more tags already exist for this dataset"))));

        log.info("Added '{}' tags to version for dataset '{}'", validTags.size(), datasetId);
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
    public DatasetVersion updateVersion(@NonNull UUID datasetId, @NonNull String versionHash,
            @NonNull DatasetVersionUpdate request) {
        log.info("Updating version, hash='{}', dataset='{}'", versionHash, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            // Find version by hash
            var version = dao.findByHash(datasetId, versionHash, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            ERROR_VERSION_HASH_NOT_FOUND.formatted(versionHash, datasetId)));

            // Update change_description if provided
            if (request.changeDescription() != null) {
                dao.updateChangeDescription(version.id(), request.changeDescription(), userName, workspaceId);
                log.info("Updated change_description for version '{}' of dataset '{}'", versionHash, datasetId);
            }

            // Add new tags if provided
            insertTags(dao, datasetId, version.id(), request.tagsToAdd(), userName, workspaceId);

            log.info("Updated version, hash='{}', dataset='{}'", versionHash, datasetId);
            return dao.findById(version.id(), workspaceId).orElseThrow();
        });
    }

    @Override
    public UUID resolveVersionId(@NonNull String workspaceId, @NonNull UUID datasetId, @NonNull String hashOrTag) {
        log.info("Resolving version ID, hashOrTag='{}', dataset='{}'", hashOrTag, datasetId);

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

        String workspaceId = requestContext.get().getWorkspaceId();

        // Resolve 'from' and to 'to' version IDs
        UUID fromVersionId = resolveVersionId(workspaceId, datasetId, fromHashOrTag);
        UUID toVersionId = Optional.ofNullable(toHashOrTag)
                .map(hashOrTag -> resolveVersionId(workspaceId, datasetId, hashOrTag))
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
        return datasetItemVersionDAO.getItemIdsAndHashes(datasetId, versionId)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .collectList();
    }

    private DatasetVersionDiffStats calculateDiffStatistics(UUID datasetId, UUID fromVersionId, UUID toVersionId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        return calculateDiffStatistics(datasetId, fromVersionId, toVersionId, workspaceId, userName);
    }

    private DatasetVersionDiffStats calculateDiffStatistics(UUID datasetId, UUID fromVersionId, UUID toVersionId,
            String workspaceId, String userName) {
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

            // Compare data hash
            boolean dataChanged = fromItem.dataHash() != toItem.dataHash();

            // Compare tags as sets (order-independent)
            boolean tagsChanged = !toTagSet(fromItem.tags()).equals(toTagSet(toItem.tags()));

            boolean evaluatorsChanged = fromItem.evaluatorsHash() != toItem.evaluatorsHash();
            boolean executionPolicyChanged = fromItem.executionPolicyHash() != toItem.executionPolicyHash();

            if (dataChanged || tagsChanged || evaluatorsChanged || executionPolicyChanged) {
                modified++;
            } else {
                unchanged++;
            }
        }

        log.info("Diff calculated: added='{}', modified='{}', deleted='{}', unchanged='{}'",
                added, modified, deleted, unchanged);

        return new DatasetVersionDiffStats(added, modified, deleted, unchanged);
    }

    /**
     * Converts a set of tags to a non-null Set for order-independent comparison.
     * Returns an empty set if the input is null.
     *
     * @param tags the tags set to convert, may be null
     * @return a Set containing the tags, or an empty set if input is null
     */
    private static Set<String> toTagSet(Set<String> tags) {
        return Optional.ofNullable(tags).orElseGet(Set::of);
    }

    @Override
    public Mono<DatasetVersion> restoreVersion(@NonNull UUID datasetId, @NonNull String versionRef) {
        log.info("Restoring dataset '{}' to version '{}'", datasetId, versionRef);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        return Mono.fromCallable(() -> buildRestoreContext(datasetId, versionRef, workspaceId, userName))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(context -> {
                    if (context.isLatestVersion) {
                        log.info("Version '{}' is already the latest for dataset '{}', returning as-is",
                                versionRef, datasetId);
                        return Mono.just(context.sourceVersion);
                    }
                    return createRestoredVersion(datasetId, versionRef, context);
                });
    }

    private RestoreContext buildRestoreContext(UUID datasetId, String versionRef,
            String workspaceId, String userName) {
        UUID sourceVersionId = resolveVersionId(workspaceId, datasetId, versionRef);

        DatasetVersion sourceVersion = template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findById(sourceVersionId, workspaceId).orElseThrow(
                    () -> new NotFoundException(ERROR_VERSION_NOT_FOUND.formatted(versionRef, datasetId)));
        });

        Optional<DatasetVersion> latestVersion = getLatestVersion(datasetId, workspaceId);
        boolean isLatestVersionFlag = latestVersion.isPresent()
                && latestVersion.get().id().equals(sourceVersionId);

        return new RestoreContext(sourceVersionId, sourceVersion, latestVersion.orElse(null),
                isLatestVersionFlag, workspaceId, userName);
    }

    private Mono<DatasetVersion> createRestoredVersion(UUID datasetId, String versionRef, RestoreContext context) {
        log.info("Creating new version by copying items from version '{}' for dataset '{}'", versionRef, datasetId);

        UUID newVersionId = idGenerator.generateId();
        String newVersionHash = CommitUtils.getCommit(newVersionId);

        return copyItemsToNewVersion(datasetId, context, newVersionId)
                .flatMap(copiedCount -> {
                    log.info("Copied '{}' items from version '{}' to new version '{}' for dataset '{}'",
                            copiedCount, versionRef, newVersionHash, datasetId);
                    return createRestoredVersionMetadata(datasetId, versionRef, context,
                            newVersionId, newVersionHash, copiedCount.intValue());
                });
    }

    private Mono<Long> copyItemsToNewVersion(UUID datasetId, RestoreContext context, UUID newVersionId) {
        // Generate UUID pool based on source version item count
        int sourceItemCount = context.sourceVersion.itemsTotal();
        List<UUID> uuids = generateUuidPool(idGenerator, sourceItemCount);

        return datasetItemVersionDAO
                .copyVersionItems(datasetId, context.sourceVersionId, newVersionId, null, uuids)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, context.userName)
                        .put(RequestContext.WORKSPACE_ID, context.workspaceId));
    }

    private Mono<DatasetVersion> createRestoredVersionMetadata(UUID datasetId, String versionRef,
            RestoreContext context, UUID newVersionId, String newVersionHash, int itemsTotal) {
        return Mono.fromCallable(() -> template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            DatasetVersionDiffStats diffStats = calculateRestoreDiffStats(datasetId, context, newVersionId);
            log.info("Restore diff for dataset '{}': added='{}', modified='{}', deleted='{}', unchanged='{}'",
                    datasetId, diffStats.itemsAdded(), diffStats.itemsModified(),
                    diffStats.itemsDeleted(), diffStats.itemsUnchanged());

            var version = DatasetVersionMapper.INSTANCE.toDatasetVersion(
                    newVersionId, datasetId, newVersionHash, itemsTotal,
                    diffStats.itemsAdded(), diffStats.itemsModified(), diffStats.itemsDeleted(),
                    DatasetVersionCreate.builder()
                            .changeDescription("Restored from version: " + versionRef)
                            .evaluators(context.sourceVersion.evaluators())
                            .executionPolicy(context.sourceVersion.executionPolicy())
                            .build(),
                    context.userName);

            insertVersionAndUpdateTags(dao, datasetId, version, newVersionId, context);

            log.info("Created restored version '{}' for dataset '{}'", newVersionHash, datasetId);
            return dao.findById(newVersionId, context.workspaceId).orElseThrow();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    private DatasetVersionDiffStats calculateRestoreDiffStats(UUID datasetId, RestoreContext context,
            UUID newVersionId) {
        if (context.previousLatestVersion == null) {
            return new DatasetVersionDiffStats(0, 0, 0, 0);
        }
        return calculateDiffStatistics(datasetId, context.previousLatestVersion.id(),
                newVersionId, context.workspaceId, context.userName);
    }

    private void insertVersionAndUpdateTags(DatasetVersionDAO dao, UUID datasetId,
            DatasetVersion version, UUID newVersionId, RestoreContext context) {
        EntityConstraintHandler.handle(() -> {
            dao.insert(version, context.workspaceId);
            return version;
        }).withError(() -> new EntityAlreadyExistsException(
                new ErrorMessage(List.of(ERROR_VERSION_HASH_EXISTS.formatted(datasetId)))));

        dao.deleteTag(datasetId, LATEST_TAG, context.workspaceId);
        dao.insertTag(datasetId, LATEST_TAG, newVersionId, context.userName, context.workspaceId);
    }

    private record RestoreContext(UUID sourceVersionId, DatasetVersion sourceVersion,
            DatasetVersion previousLatestVersion, boolean isLatestVersion, String workspaceId, String userName) {
    }
}
