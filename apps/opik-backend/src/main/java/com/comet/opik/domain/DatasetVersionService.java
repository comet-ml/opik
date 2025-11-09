package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetVersionServiceImpl.class)
public interface DatasetVersionService {

    DatasetVersion commitVersion(UUID datasetId, DatasetVersionCreate request);

    DatasetVersionPage getVersions(UUID datasetId, int page, int size);

    Optional<DatasetVersion> getVersionByHash(UUID datasetId, String versionHash);

    Optional<DatasetVersion> getVersionByTag(UUID datasetId, String tag);

    Optional<DatasetVersion> getLatestVersion(UUID datasetId);

    void createTag(UUID datasetId, String versionHash, DatasetVersionTag tag);

    void deleteTag(UUID datasetId, String tag);

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

        return template.inTransaction(WRITE, handle -> {
            var datasetVersionDAO = handle.attach(DatasetVersionDAO.class);
            var datasetItemDAOHandle = handle.attach(DatasetItemDAO.class);

            // Get current draft items - use streaming to collect all items
            var currentItems = datasetItemDAOHandle.getItems(datasetId, Integer.MAX_VALUE, null)
                    .collectList()
                    .block();

            // Calculate hash based on current items
            String versionHash = calculateVersionHash(currentItems);

            // Check if version with this hash already exists (deduplication)
            var existingVersion = datasetVersionDAO.findByHash(datasetId, versionHash, workspaceId);
            if (existingVersion.isPresent()) {
                log.info("Version with hash '{}' already exists for dataset '{}'", versionHash, datasetId);

                // If tag is provided, add it to existing version
                if (request.tag() != null && !request.tag().isBlank()) {
                    try {
                        datasetVersionDAO.insertTag(datasetId, request.tag(), existingVersion.get().id(), userName,
                                workspaceId);
                    } catch (Exception e) {
                        throw new EntityAlreadyExistsException(new ErrorMessage(
                                java.util.List
                                        .of("Tag '%s' already exists for this dataset".formatted(request.tag()))));
                    }
                }

                return datasetVersionDAO.findById(existingVersion.get().id(), workspaceId).orElseThrow();
            }

            // Calculate diff statistics
            var diffStats = calculateDiffStats(datasetId, currentItems, workspaceId);

            // Create new version
            var versionId = idGenerator.generateId();
            var version = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .itemsCount(currentItems.size())
                    .itemsAdded(diffStats.added())
                    .itemsModified(diffStats.modified())
                    .itemsDeleted(diffStats.deleted())
                    .changeDescription(request.changeDescription())
                    .metadata(request.metadata())
                    .createdBy(userName)
                    .lastUpdatedBy(userName)
                    .build();

            datasetVersionDAO.insert(version, workspaceId);

            log.info("Created version with hash '{}' for dataset '{}'", versionHash, datasetId);

            // Add tag if provided
            if (request.tag() != null && !request.tag().isBlank()) {
                try {
                    datasetVersionDAO.insertTag(datasetId, request.tag(), versionId, userName, workspaceId);
                } catch (Exception e) {
                    throw new EntityAlreadyExistsException(new ErrorMessage(
                            java.util.List.of("Tag '%s' already exists for this dataset".formatted(request.tag()))));
                }
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
                            "Version with hash '%s' not found for dataset '%s'".formatted(versionHash, datasetId)));

            // Insert tag
            try {
                dao.insertTag(datasetId, tagRequest.tag(), version.id(), userName, workspaceId);
            } catch (Exception e) {
                throw new EntityAlreadyExistsException(new ErrorMessage(java.util.List
                        .of("Tag '%s' already exists for this dataset".formatted(tagRequest.tag()))));
            }

            return null;
        });

        log.info("Created tag '{}' for version '{}' of dataset: '{}'", tagRequest.tag(), versionHash, datasetId);
    }

    @Override
    public void deleteTag(@NonNull UUID datasetId, @NonNull String tag) {
        log.info("Deleting tag '{}' from dataset: '{}'", tag, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();

        int deleted = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.deleteTag(datasetId, tag, workspaceId);
        });

        if (deleted == 0) {
            throw new NotFoundException("Tag '%s' not found for dataset '%s'".formatted(tag, datasetId));
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

            throw new NotFoundException(
                    "Version not found for dataset '%s' with hash or tag '%s'".formatted(datasetId, hashOrTag));
        });
    }

    /**
     * Calculate SHA-256 hash of dataset items for version identification and deduplication.
     * Hash is deterministic - same items produce same hash.
     */
    private String calculateVersionHash(List<DatasetItem> items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Sort items by ID to ensure deterministic order
            var sortedItems = items.stream()
                    .sorted((a, b) -> a.id().compareTo(b.id()))
                    .collect(Collectors.toList());

            // Hash each item's ID and data
            for (DatasetItem item : sortedItems) {
                digest.update(item.id().toString().getBytes(StandardCharsets.UTF_8));
                if (item.data() != null) {
                    digest.update(item.data().toString().getBytes(StandardCharsets.UTF_8));
                }
            }

            // Convert to hex string (first 16 chars for display)
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString().substring(0, 16); // Short hash for display
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Calculate diff statistics by comparing current items with last version.
     */
    private DiffStats calculateDiffStats(UUID datasetId, List<DatasetItem> currentItems, String workspaceId) {
        // Get last version
        var lastVersion = template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findLatestVersion(datasetId, workspaceId);
        });

        // If no previous version, all items are added
        if (lastVersion.isEmpty()) {
            return new DiffStats(currentItems.size(), 0, 0);
        }

        // For now, we'll track items added/deleted by count since OPIK-3015 handles actual storage
        // This is metadata-only tracking
        int previousCount = lastVersion.get().itemsCount();
        int currentCount = currentItems.size();

        int added = Math.max(0, currentCount - previousCount);
        int deleted = Math.max(0, previousCount - currentCount);

        return new DiffStats(added, 0, deleted); // Modified will be calculated in OPIK-3015
    }

    private record DiffStats(int added, int modified, int deleted) {
    }
}
