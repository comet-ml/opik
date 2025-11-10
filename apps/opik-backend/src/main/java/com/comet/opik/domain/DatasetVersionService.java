package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.DatabaseUtils;
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
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetVersionServiceImpl.class)
public interface DatasetVersionService {

    String LATEST_TAG = "latest";

    // Error message templates
    String ERROR_VERSION_HASH_EXISTS = "Version with hash '%s' already exists for dataset '%s'";
    String ERROR_TAG_EXISTS = "Tag '%s' already exists for this dataset";
    String ERROR_CANNOT_DELETE_LATEST_TAG = "Cannot delete '%s' tag - it is automatically managed";
    String ERROR_VERSION_HASH_NOT_FOUND = "Version with hash '%s' not found for dataset '%s'";
    String ERROR_TAG_NOT_FOUND = "Tag '%s' not found for dataset '%s'";
    String ERROR_VERSION_NOT_FOUND = "Version not found for dataset '%s' with hash or tag '%s'";

    /**
     * Commits a new version for the specified dataset with metadata and optional tag.
     * <p>
     * This operation:
     * <ul>
     *   <li>Generates a content-based hash for the version (placeholder until OPIK-3015)</li>
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
     * @throws ConflictException if the version hash already exists (deduplication)
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

        // TODO OPIK-3015: Calculate hash based on actual dataset items from ClickHouse
        // For now, use timestamp-based hash as placeholder
        String versionHash = calculatePlaceholderVersionHash(datasetId);

        return template.inTransaction(WRITE, handle -> {
            var datasetVersionDAO = handle.attach(DatasetVersionDAO.class);

            // TODO OPIK-3015: Get actual item count and calculate diff stats from ClickHouse
            // For now, use placeholder values for metadata-only testing
            int itemsCount = 0;
            int itemsAdded = 0;
            int itemsModified = 0;
            int itemsDeleted = 0;

            // Create new version
            var versionId = idGenerator.generateId();
            var version = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .itemsCount(itemsCount)
                    .itemsAdded(itemsAdded)
                    .itemsModified(itemsModified)
                    .itemsDeleted(itemsDeleted)
                    .changeDescription(request.changeDescription())
                    .metadata(request.metadata())
                    .createdBy(userName)
                    .lastUpdatedBy(userName)
                    .build();

            DatabaseUtils.handleStateDbDuplicateConstraint(
                    () -> datasetVersionDAO.insert(version, workspaceId),
                    ERROR_VERSION_HASH_EXISTS.formatted(versionHash, datasetId));

            log.info("Created version with hash '{}' for dataset '{}'", versionHash, datasetId);

            // Remove 'latest' tag from previous version (if exists)
            datasetVersionDAO.deleteTag(datasetId, LATEST_TAG, workspaceId);

            // Always add 'latest' tag to the new version
            datasetVersionDAO.insertTag(datasetId, LATEST_TAG, versionId, userName, workspaceId);
            log.info("Added '{}' tag to version '{}' for dataset '{}'", LATEST_TAG, versionHash, datasetId);

            // Add custom tag if provided
            if (StringUtils.isNotBlank(request.tag())) {
                DatabaseUtils.handleStateDbDuplicateConstraint(
                        () -> datasetVersionDAO.insertTag(datasetId, request.tag(), versionId, userName, workspaceId),
                        ERROR_TAG_EXISTS.formatted(request.tag()));
            }

            // TODO OPIK-3015: Create immutable snapshots in ClickHouse dataset_item_versions table

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
        log.info("Getting latest version for dataset: '{}'", datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findLatestVersion(datasetId, workspaceId);
        });
    }

    @Override
    public void createTag(@NonNull UUID datasetId, @NonNull String versionHash,
            @NonNull DatasetVersionTag tagRequest) {
        log.info("Creating tag '{}' for version '{}' of dataset: '{}'", tagRequest.tag(), versionHash, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            // Find version by hash
            var version = dao.findByHash(datasetId, versionHash, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            ERROR_VERSION_HASH_NOT_FOUND.formatted(versionHash, datasetId)));

            // Insert tag
            DatabaseUtils.handleStateDbDuplicateConstraint(
                    () -> dao.insertTag(datasetId, tagRequest.tag(), version.id(), userName, workspaceId),
                    ERROR_TAG_EXISTS.formatted(tagRequest.tag()));

            return null;
        });

        log.info("Created tag '{}' for version '{}' of dataset: '{}'", tagRequest.tag(), versionHash, datasetId);
    }

    @Override
    public void deleteTag(@NonNull UUID datasetId, @NonNull String tag) {
        log.info("Deleting tag '{}' from dataset: '{}'", tag, datasetId);

        // Prevent deletion of 'latest' tag - it's managed automatically
        if (LATEST_TAG.equals(tag)) {
            throw new ClientErrorException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(new ErrorMessage(
                                    List.of(ERROR_CANNOT_DELETE_LATEST_TAG.formatted(LATEST_TAG))))
                            .build());
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        int deleted = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.deleteTag(datasetId, tag, workspaceId);
        });

        if (deleted == 0) {
            throw new NotFoundException(ERROR_TAG_NOT_FOUND.formatted(tag, datasetId));
        }

        log.info("Deleted tag '{}' from dataset: '{}'", tag, datasetId);
    }

    @Override
    public UUID resolveVersionId(@NonNull UUID datasetId, @NonNull String hashOrTag) {
        log.info("Resolving version ID for dataset: '{}', hashOrTag: '{}'", datasetId, hashOrTag);

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

            throw new NotFoundException(ERROR_VERSION_NOT_FOUND.formatted(datasetId, hashOrTag));
        });
    }

    /**
     * Calculate placeholder hash for version identification.
     * TODO OPIK-3015: Replace with actual content-based hash from dataset items.
     */
    private String calculatePlaceholderVersionHash(UUID datasetId) {
        try {
            // Use timestamp + dataset ID for unique hash per commit
            String input = datasetId.toString() + ":" + System.currentTimeMillis();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string (first 16 chars for display)
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                String hex = Integer.toHexString(0xff & hashBytes[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
